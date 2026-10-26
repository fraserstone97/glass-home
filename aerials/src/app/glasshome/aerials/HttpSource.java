package app.glasshome.aerials;

import android.media.MediaDataSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A video streamed straight from Apple in 4 MB ranged chunks, fed to MediaPlayer. This TV's
 * media server can't fetch these files itself, so the app does it over a normal connection.
 * Apple's files keep their index at the very end, so with the size known up front (aerials.json)
 * the last chunk is fetched straight away, alongside the first. Once the player has read the
 * index it reads in order: the next two chunks are fetched ahead, and only a couple already
 * played are kept.
 */
final class HttpSource extends MediaDataSource {

    static final int CHUNK = 4 << 20, KEEP = 2, AHEAD = 2;

    final String url;
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    final Map<Long, Future<byte[]>> chunks = new LinkedHashMap<Long, Future<byte[]>>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Future<byte[]>> e) {
            return size() > KEEP + AHEAD;
        }
    };
    final long size;
    /**
     * The player has been past the first chunk (to the index, or on through the film). Until then
     * nothing is read ahead, so the first chunk and the index never queue behind 8 MB of it.
     */
    boolean indexed;
    volatile boolean closed;

    /** A file of a known size. */
    private HttpSource(String url, long size) {
        this.url = url;
        this.size = size;
    }

    /**
     * A clip to play. Its index is fetched now, or comes from disk with its first chunk when the
     * last session kept it as the opener (head and tail; see AerialPlayer.keepOpener).
     */
    HttpSource(String url, long size, File head, File tail) {
        this(url, size);
        if (head != null) kept(0, head);
        if (tail != null) kept(lastChunk(), tail);
        else prefetch(lastChunk());
    }

    /**
     * Saves a clip's first and last chunks to files, for the next session's opener. Blocking:
     * for a background thread.
     */
    static void save(String url, long size, File head, File tail) throws IOException {
        HttpSource s = new HttpSource(url, size);
        try {
            write(s.fetch(0), head);
            write(s.fetch(s.lastChunk()), tail);
        } finally {
            s.close();
        }
    }

    /** Written whole and then renamed, so a half-written file is never taken for an opener. */
    static void write(byte[] b, File f) throws IOException {
        File part = new File(f.getPath() + ".part");
        Files.write(part.toPath(), b);
        if (!part.renameTo(f)) throw new IOException("rename " + f);
    }

    long lastChunk() {
        return (size - 1) / CHUNK;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
        if (closed) throw new IOException("closed");
        long total = getSize();
        if (position >= total) return -1;
        int done = 0;
        while (done < length && position + done < total) {
            long at = position + done;
            long index = at / CHUNK;
            byte[] data = chunk(index);
            if (index > 0) indexed = true;
            if (indexed) for (int a = 1; a <= AHEAD; a++) if ((index + a) * CHUNK < total) prefetch(index + a);
            int from = (int) (at - index * CHUNK);
            int n = Math.min(length - done, data.length - from);
            if (n <= 0) break;
            System.arraycopy(data, from, buffer, offset + done, n);
            done += n;
        }
        return done;
    }

    byte[] chunk(long index) throws IOException {
        try {
            return prefetch(index).get();
        } catch (Exception e) {
            synchronized (this) {
                chunks.remove(index);
            }
            throw new IOException(e);
        }
    }

    /** A chunk's download: started now, unless it is already on its way or kept. */
    synchronized Future<byte[]> prefetch(long index) {
        Future<byte[]> f = chunks.get(index);
        if (f == null) {
            f = pool.submit(() -> fetch(index));
            chunks.put(index, f);
        }
        return f;
    }

    /** A chunk kept on disk, read on the pool and then deleted; fetched as usual if it won't read whole. */
    synchronized void kept(long index, File f) {
        long length = Math.min(size, (index + 1) * CHUNK) - index * CHUNK;
        chunks.put(index, pool.submit(() -> {
            byte[] b = null;
            try {
                b = Files.readAllBytes(f.toPath());
            } catch (IOException ignored) {
                // fetched instead
            }
            f.delete();
            return b != null && b.length == length ? b : fetch(index);
        }));
    }

    byte[] fetch(long index) throws IOException {
        long start = index * CHUNK, end = Math.min(size, start + CHUNK) - 1;
        IOException last = null;
        for (int attempt = 0; attempt < 3 && !closed; attempt++) {
            HttpURLConnection c = open();
            c.setRequestProperty("Range", "bytes=" + start + "-" + end);
            try {
                int code = c.getResponseCode();
                if (code >= 500) throw new IOException("HTTP " + code);  // the server's trouble: try again
                // Anything but that part of a file this size (a 416, or a file Apple has replaced
                // since aerials.json was made) won't come right on a retry: the clip fails over to the next.
                String range = c.getHeaderField("Content-Range");
                if (code != 206 || range == null || !range.endsWith("/" + size)) {
                    last = new IOException("HTTP " + code + ", " + range);
                    break;
                }
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[(int) (end - start + 1)];
                    int n = 0, r;
                    while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                    if (n == b.length) return b;
                    last = new IOException("short read");
                }
            } catch (IOException e) {
                last = e;
            } finally {
                c.disconnect();
            }
        }
        throw last != null ? last : new IOException("closed");
    }

    HttpURLConnection open() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "GlassAerials/1.0 (Android TV)");
        return c;
    }

    @Override
    public void close() {
        closed = true;
        pool.shutdownNow();
        synchronized (this) {
            chunks.clear();
        }
    }
}

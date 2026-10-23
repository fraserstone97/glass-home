package app.glasshome.aerials;

import android.media.MediaDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A video streamed straight from Apple in 4 MB ranged chunks, fed to MediaPlayer. This TV's
 * media server can't fetch these files itself, so the app does it over a normal connection.
 * The next two chunks are fetched ahead while one plays, and a handful are kept for the seeks
 * the player makes to read the file's index at the end.
 */
final class HttpSource extends MediaDataSource {

    static final int CHUNK = 4 << 20, KEEP = 6, AHEAD = 2;

    final String url;
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    final Map<Long, Future<byte[]>> chunks = new LinkedHashMap<Long, Future<byte[]>>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Future<byte[]>> e) {
            return size() > KEEP + AHEAD;
        }
    };
    long size = -1;
    volatile boolean closed;

    HttpSource(String url) {
        this.url = url;
    }

    @Override
    public synchronized long getSize() throws IOException {
        if (size < 0) {
            HttpURLConnection c = open();
            c.setRequestMethod("HEAD");
            try {
                if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
                size = c.getContentLengthLong();
            } finally {
                c.disconnect();
            }
        }
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
            for (int a = 1; a <= AHEAD; a++) if ((index + a) * CHUNK < total) chunk(index + a, false);
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
            return chunk(index, true).get();
        } catch (Exception e) {
            synchronized (this) {
                chunks.remove(index);
            }
            throw new IOException(e);
        }
    }

    synchronized Future<byte[]> chunk(long index, boolean touch) {
        Future<byte[]> f = chunks.get(index);
        if (f == null) {
            f = pool.submit(() -> fetch(index));
            chunks.put(index, f);
        }
        return f;
    }

    byte[] fetch(long index) throws IOException {
        long start = index * CHUNK, end = Math.min(size, start + CHUNK) - 1;
        IOException last = null;
        for (int attempt = 0; attempt < 3 && !closed; attempt++) {
            HttpURLConnection c = open();
            c.setRequestProperty("Range", "bytes=" + start + "-" + end);
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[(int) (end - start + 1)];
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                if (n == b.length) return b;
                last = new IOException("short read");
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
        c.setRequestProperty("User-Agent", "tvOSAerials/1.0 (Android TV)");
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

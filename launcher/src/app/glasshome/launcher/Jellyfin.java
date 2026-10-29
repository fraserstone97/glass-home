package app.glasshome.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Your own Jellyfin server as Wholphin's top shelf: your library shuffled, opened straight
 * in Wholphin. The server is found on the network by Jellyfin's own discovery
 * broadcast, and signed in with Quick Connect (a code approved in Jellyfin), so no password or key
 * is ever typed on the TV. Settings -> Jellyfin.
 */
final class Jellyfin {
    static final String WHOLPHIN = "com.github.damontecres.wholphin";
    static final String K_SERVER = "server", K_NAME = "name", K_TOKEN = "token", K_USER = "user", K_ID = "id",
            K_SYNCED = "synced", K_TRIED = "tried", K_REJECTED = "rejected";
    /** How long a shelf is reused before it's fetched again. */
    static final long FRESH_MS = 3 * 60 * 1000L;

    private Jellyfin() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("jellyfin", Context.MODE_PRIVATE);
    }

    static boolean connected(Context c) {
        return !prefs(c).getString(K_TOKEN, "").isEmpty() && !prefs(c).getString(K_USER, "").isEmpty();
    }

    static String server(Context c) {
        return prefs(c).getString(K_SERVER, "");
    }

    static String name(Context c) {
        return prefs(c).getString(K_NAME, "");
    }

    static void disconnect(Context c) {
        prefs(c).edit().remove(K_TOKEN).remove(K_USER).remove(K_SYNCED).remove(K_TRIED).remove(K_REJECTED).apply();
        file(c).delete();
    }

    /**
     * Finds your Jellyfin server on the network ("who is JellyfinServer?" on UDP 7359), wherever its
     * address has moved to. Once signed in it only ever takes the same server back (by its id), so
     * another one on the network can't take its place. Off the main thread.
     */
    static boolean discover(Context c) {
        String want = prefs(c).getString(K_ID, "");
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(600);
            byte[] q = "who is JellyfinServer?".getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), 7359));
            byte[] buf = new byte[2048];
            String addr = null, name = null, id = null;
            long until = System.currentTimeMillis() + 2500;
            while (System.currentTimeMillis() < until) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException t) {
                    continue;
                }
                JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                String a = o.optString("Address"), i = o.optString("Id");
                if (a.isEmpty() || !want.isEmpty() && !want.equals(i)) continue;
                addr = a;
                name = o.optString("Name");
                id = i;
                break;
            }
            if (addr == null) return false;
            prefs(c).edit().putString(K_SERVER, addr).putString(K_NAME, name).putString(K_ID, id).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Library work off the main thread, on its own so a server that's away never holds up Home's artwork. */
    static final java.util.concurrent.ExecutorService BACKGROUND = java.util.concurrent.Executors.newSingleThreadExecutor();
    static volatile long lookedAt;

    /** The server didn't answer: look for it again in the background (at most once a minute). */
    static void lookAgain(Context c) {
        long now = System.currentTimeMillis();
        if (now - lookedAt < 60_000L) return;
        lookedAt = now;
        Context app = c.getApplicationContext();
        BACKGROUND.execute(() -> discover(app));
    }

    static String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("device", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("device", id).apply();
        }
        return id;
    }

    private static String authHeader(Context c) {
        String token = prefs(c).getString(K_TOKEN, "");
        return "MediaBrowser Client=\"Glass Home\", Device=\"TV\", DeviceId=\"" + deviceId(c) + "\", Version=\"1.0\""
                + (token.isEmpty() ? "" : ", Token=\"" + token + "\"");
    }

    private static JSONObject call(Context c, String method, String path, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(server(c) + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(8000);
        con.setRequestMethod(method);
        con.setRequestProperty("Authorization", authHeader(c));
        con.setRequestProperty("Accept", "application/json");
        if (body != null) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            int code = con.getResponseCode();
            if (code == 401) throw new SecurityException("Jellyfin sign-in no longer accepted");
            if (code / 100 != 2) throw new IllegalStateException("Jellyfin HTTP " + code);
            String s = new String(Suggestions.readAll(con.getInputStream()), StandardCharsets.UTF_8).trim();
            return s.startsWith("[") ? new JSONObject().put("Items", new JSONArray(s))
                    : s.isEmpty() ? new JSONObject() : new JSONObject(s);
        } finally {
            con.disconnect();
        }
    }

    // ---------------------------------------------------------------- Quick Connect

    /** Starts Quick Connect: returns {code, secret}, or null when the server has it switched off. */
    static String[] quickConnectStart(Context c) {
        try {
            JSONObject o = call(c, "POST", "/QuickConnect/Initiate", null);
            return new String[]{o.getString("Code"), o.getString("Secret")};
        } catch (Exception e) {
            return null;
        }
    }

    /** True once the code was approved and the TV is signed in; false while it's still waiting. */
    static boolean quickConnectPoll(Context c, String secret) {
        try {
            JSONObject o = call(c, "GET", "/QuickConnect/Connect?Secret=" + secret, null);
            if (!o.optBoolean("Authenticated", false)) return false;
            JSONObject a = call(c, "POST", "/Users/AuthenticateWithQuickConnect",
                    new JSONObject().put("Secret", secret).toString());
            prefs(c).edit().putString(K_TOKEN, a.getString("AccessToken"))
                    .putString(K_USER, a.getJSONObject("User").getString("Id")).putBoolean(K_REJECTED, false).apply();
            learnId(c);
            return true;
        } catch (Exception e) {
            // Never the exception's text: it can carry the server's address.
            android.util.Log.w("Jellyfin", "Quick Connect failed");
            return false;
        }
    }

    /** Remembers which server this is, so it's always found again (and never another). */
    static void learnId(Context c) {
        if (!prefs(c).getString(K_ID, "").isEmpty()) return;
        try {
            String id = call(c, "GET", "/System/Info/Public", null).optString("Id");
            if (!id.isEmpty()) prefs(c).edit().putString(K_ID, id).apply();
        } catch (Exception ignored) {
            // learnt next time
        }
    }

    // ---------------------------------------------------------------- search

    /** Films and series in your library matching a search: {id, name, year}. Off the main thread. */
    static List<String[]> search(Context c, String q) {
        List<String[]> out = new ArrayList<>();
        if (!connected(c) || q.trim().isEmpty()) return out;
        try {
            String u = prefs(c).getString(K_USER, "");
            JSONObject r = call(c, "GET", "/Users/" + u + "/Items?Recursive=true&IncludeItemTypes=Movie,Series&Limit=12"
                    + "&Fields=ProductionYear&searchTerm=" + java.net.URLEncoder.encode(q.trim(), "UTF-8"), null);
            JSONArray a = r.optJSONArray("Items");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("Id"), o.optString("Name"), String.valueOf(o.optInt("ProductionYear", 0)),
                        "Series".equals(o.optString("Type")) ? "tv" : "movie"});
            }
        } catch (SecurityException e) {
            prefs(c).edit().putBoolean(K_REJECTED, true).apply();
        } catch (Exception e) {
            // Asleep, away or moved: search the services alone this time, and look for it again.
            lookAgain(c);
        }
        return out;
    }

    // ---------------------------------------------------------------- what's in the library

    /**
     * Every film and series in the library, {id, name, year, "movie" or "tv", TMDB id}: a short
     * line a title, kept on the TV, so a title's page knows at once whether it's yours however it
     * was reached (a search for its name, an actor's, Related), with no wait for the server.
     */
    static File indexFile(Context c) {
        return new File(c.getFilesDir(), "jellyfin_index.json");
    }

    private static List<String[]> owned = new ArrayList<>();
    private static long ownedStamp = -1;
    /** When the list was last asked for, so a server that's away is asked at most once a minute. */
    static volatile long indexTried;

    /** The saved list (empty before it's first fetched), read from disk only when it has changed. */
    static synchronized List<String[]> owned(Context c) {
        File f = indexFile(c);
        if (!connected(c) || !f.exists()) return new ArrayList<>();
        if (f.lastModified() != ownedStamp) {
            try {
                JSONArray a = new JSONArray(new String(Suggestions.readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
                List<String[]> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONArray t = a.getJSONArray(i);
                    out.add(new String[]{t.optString(0), t.optString(1), t.optString(2), t.optString(3), t.optString(4)});
                }
                owned = out;
                ownedStamp = f.lastModified();
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }
        return owned;
    }

    /** Out of date, and not asked for in the last minute. */
    static boolean indexDue(Context c) {
        long now = System.currentTimeMillis();
        File f = indexFile(c);
        return connected(c) && now - indexTried > 60_000L && (!f.exists() || now - f.lastModified() > FRESH_MS);
    }

    /** Fetches the list again (off the main thread): true when it was. */
    static boolean fetchIndex(Context c) {
        if (!connected(c)) return false;
        indexTried = System.currentTimeMillis();
        try {
            String u = prefs(c).getString(K_USER, "");
            JSONArray a = call(c, "GET", "/Users/" + u + "/Items?Recursive=true&IncludeItemTypes=Movie,Series"
                    + "&Fields=ProductionYear,ProviderIds&EnableImages=false&EnableUserData=false", null).optJSONArray("Items");
            JSONArray out = new JSONArray();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                JSONObject ids = o.optJSONObject("ProviderIds");
                out.put(new JSONArray().put(o.optString("Id")).put(o.optString("Name"))
                        .put(String.valueOf(o.optInt("ProductionYear", 0)))
                        .put("Series".equals(o.optString("Type")) ? "tv" : "movie")
                        .put(ids == null ? "" : ids.optString("Tmdb")));
            }
            Suggestions.save(indexFile(c), out.toString());
            owned(c);  // read here, not on the page that asks next
            return true;
        } catch (SecurityException e) {
            prefs(c).edit().putBoolean(K_REJECTED, true).apply();
        } catch (Exception e) {
            lookAgain(c);  // asleep, away or moved: the saved list stands meanwhile
        }
        return false;
    }

    /**
     * Your copy of a title: by its TMDB id where both know it (whatever either calls it), else by
     * its name, kind and year (a year either side, as release dates differ).
     */
    static String find(List<String[]> all, String title, int year, boolean series, int tmdb) {
        String want = SearchBrain.norm(Suggestions.cleanTitle(title));
        for (String[] m : all) {
            if (series != "tv".equals(m[3])) continue;
            int theirs = Suggestions.parseInt(m[4]);
            if (tmdb > 0 && theirs > 0) {
                if (theirs == tmdb) return m[0];
                continue;
            }
            int y = Suggestions.parseInt(m[2]);
            if (!want.isEmpty() && SearchBrain.norm(m[1]).equals(want) && (year <= 0 || y <= 0 || Math.abs(y - year) <= 1)) return m[0];
        }
        return null;
    }

    static String poster(Context c, String id) {
        return server(c) + "/Items/" + id + "/Images/Primary?maxHeight=450&quality=90";
    }

    static void play(Context c, String id) {
        Suggestions.start(c, new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("wholphin://view?itemId=" + id)).setPackage(WHOLPHIN)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---------------------------------------------------------------- the shelf

    static File file(Context c) {
        return new File(c.getFilesDir(), "jellyfin_shelf_4.json");
    }

    /** The saved shelf, at once; null when there's nothing yet. */
    static List<Suggestions.Item> shelf;
    static long shelfStamp = -1;

    /** The saved shelf, read from disk only when it has changed (it's asked for mid-animation). */
    static synchronized List<Suggestions.Item> cached(Context c) {
        File f = file(c);
        if (!connected(c) || !f.exists()) return null;
        if (f.lastModified() != shelfStamp) {
            try {
                JSONArray a = new JSONArray(new String(Suggestions.readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
                List<Suggestions.Item> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) out.add(Suggestions.Item.fromJson(a.getJSONObject(i)));
                shelf = out;
                shelfStamp = f.lastModified();
            } catch (Exception e) {
                return null;
            }
        }
        return new ArrayList<>(shelf);
    }

    static boolean stale(Context c) {
        File f = file(c);
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > FRESH_MS;
    }

    /**
     * Fetches the shelf: films and series picked at random from your library, a new draw each
     * time it's fetched, one card per film or series. Off the main thread. A moved server is found
     * again.
     */
    static List<Suggestions.Item> refresh(Context c) {
        if (!connected(c)) return null;
        prefs(c).edit().putLong(K_TRIED, System.currentTimeMillis()).apply();
        // Older shelf formats, never read again.
        for (String old : new String[]{"jellyfin_shelf.json", "jellyfin_shelf_2.json", "jellyfin_shelf_3.json"}) {
            new File(c.getFilesDir(), old).delete();
        }
        List<Suggestions.Item> out = new ArrayList<>();
        try {
            fill(c, out);
        } catch (SecurityException e) {
            // Signed out on the server (the device was removed there): kept, and Settings offers
            // to sign in again; the saved shelf stays meanwhile.
            prefs(c).edit().putBoolean(K_REJECTED, true).apply();
            // Fixed text: a message-less exception would make Log throw on this thread.
            android.util.Log.w("Jellyfin", "sign-in no longer accepted");
            return null;
        } catch (Exception e) {
            // Asleep, away, or at a new address: find it again and try once more. Nothing is
            // forgotten when it can't be reached; the saved shelf stays until it can.
            out.clear();
            if (!discover(c)) {
                android.util.Log.w("Jellyfin", "server not reachable");
                return null;
            }
            try {
                fill(c, out);
            } catch (Exception again) {
                android.util.Log.w("Jellyfin", "server not reachable");
                return null;
            }
        }
        prefs(c).edit().putLong(K_SYNCED, System.currentTimeMillis()).putBoolean(K_REJECTED, false).apply();
        learnId(c);
        try {
            JSONArray a = new JSONArray();
            for (Suggestions.Item it : out) a.put(it.toJson());
            Suggestions.save(file(c), a.toString());
        } catch (Exception ignored) {
            // shown this time, fetched again next time
        }
        if (indexDue(c)) fetchIndex(c);  // the list of what's yours, kept as fresh as the shelf
        return out;
    }

    /** Titles on Wholphin's shelf: as many as the other services show, not a page dot per film. */
    static final int SHELF = 8;

    private static void fill(Context c, List<Suggestions.Item> out) throws Exception {
        String u = prefs(c).getString(K_USER, "");
        String fields = "&Fields=Overview,Genres,ProductionYear,ParentBackdropItemId,ParentBackdropImageTags,BackdropImageTags,ImageTags"
                + "&EnableImageTypes=Backdrop,Logo";
        Set<String> seen = new HashSet<>();
        // Only titles with a backdrop to show behind them; a few more than are shown all the same.
        add(c, call(c, "GET", "/Users/" + u + "/Items?Recursive=true&SortBy=Random&ImageTypes=Backdrop&Limit=16"
                + "&IncludeItemTypes=Movie,Series" + fields, null),
                out, seen);
    }

    private static void add(Context c, JSONObject res, List<Suggestions.Item> out, Set<String> seen) {
        JSONArray a = res.optJSONArray("Items");
        for (int i = 0; a != null && i < a.length() && out.size() < SHELF; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("Id");
            boolean episode = "Episode".equals(o.optString("Type"));
            String show = episode ? o.optString("SeriesId") : id;
            if (!seen.add(show)) continue;  // one card per programme
            Suggestions.Item it = new Suggestions.Item();
            it.id = Math.abs(id.hashCode());
            it.type = episode || "Series".equals(o.optString("Type")) ? "tv" : "movie";
            it.title = episode ? o.optString("SeriesName") : o.optString("Name");
            String ep = episode ? "S" + o.optInt("ParentIndexNumber") + ", E" + o.optInt("IndexNumber") + ": "
                    + o.optString("Name") + ". " : "";
            it.overview = ep + o.optString("Overview");
            int y = o.optInt("ProductionYear", 0);
            it.year = y > 0 ? String.valueOf(y) : "";
            JSONArray g = o.optJSONArray("Genres");
            List<String> gs = new ArrayList<>();
            for (int k = 0; g != null && k < g.length() && k < 2; k++) gs.add(g.optString(k));
            it.genres = String.join(" · ", gs);
            // The film's or the series' own backdrop, at the screen's width.
            JSONArray own = o.optJSONArray("BackdropImageTags");
            String art = own != null && own.length() > 0 ? id : o.optString("ParentBackdropItemId", "");
            if (art.isEmpty()) continue;  // nothing to show behind it
            it.backdrop = server(c) + "/Items/" + art + "/Images/Backdrop?maxWidth=1920&quality=90";
            it.logo = "";  // the title in text, like the other recommendations
            it.link = "wholphin://view?itemId=" + id;
            out.add(it);
        }
    }
}

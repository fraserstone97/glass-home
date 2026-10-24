package app.glasshome.launcher;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Top-shelf suggestions: what's trending in the UK on the user's streaming services (recent and
 * well rated), from JustWatch. Results and artwork are cached on disk; everything here runs off the UI thread.
 */
final class Suggestions {

    static final String PREFS = "suggestions";
    static final String K_ENABLED = "enabled", K_TOKEN = "tmdb_token", K_TYPES = "types",
            K_PROVIDERS = "providers", K_LANGS = "languages", K_ADVANCE = "advance_sec", K_VERSION = "version",
            K_SOURCE = "source", K_SHELF = "shelf";
    /** What the top shelf shows: recommendations or nothing. */
    static final String SHELF_RECS = "recs", SHELF_NONE = "none";
    /** Recommendation sources: JustWatch needs no key (unofficial API); TMDB needs a token. */
    static final String SRC_JUSTWATCH = "justwatch", SRC_TMDB = "tmdb";
    static final long MAX_AGE_MS = 2L * 60 * 60 * 1000;  // recommendations rotate every 2 hours
    /** POOL titles are fetched per service; PER_SOURCE of them are picked at random each refresh. */
    static final int PER_SOURCE = 3, POOL = 20, MAX_ITEMS = 10;
    /** Recommendations must be rated at least this on IMDb, by at least this many people. */
    static final double MIN_SCORE = 6.8;
    static final int MIN_VOTES = 1500, MIN_VOTES_FILM = 10000;

    /** A streaming service available in the UK: TMDB provider id, display name, Android TV package. */
    static final class Provider {
        final int id;
        final String name, pkg, jwShort, jwTech;

        Provider(int id, String name, String pkg, String jwShort, String jwTech) {
            this.id = id;
            this.name = name;
            this.pkg = pkg;
            this.jwShort = jwShort;
            this.jwTech = jwTech;
        }
    }

    static final List<Provider> PROVIDERS = Arrays.asList(
            new Provider(38, "BBC iPlayer", "bbc.iplayer.android", "bbc", "bbc"),
            new Provider(41, "ITVX", "air.ITVMobilePlayer", "itv", "itv"),
            new Provider(103, "Channel 4", "com.channel4.ondemand", "al4", "all4"),
            new Provider(8, "Netflix", "com.netflix.ninja", "nfx", "netflix"),
            new Provider(9, "Prime Video", "com.amazon.amazonvideo.livingroom", "amp", "amazonprime"),
            new Provider(337, "Disney+", "com.disney.disneyplus", "dnp", "disneyplus"),
            new Provider(350, "Apple TV", "com.apple.atve.androidtv.appletv", "atp", "appletvplus"),
            new Provider(39, "NOW", "com.bskyb.nowtv.beta", "ntv", "nowtv"),
            new Provider(1899, "HBO Max", "com.wbd.stream", "mxx", "max"),
            new Provider(531, "Paramount+", "com.cbs.ott", "pmp", "paramountplus"));

    /** Original-language filters offered in settings (TMDB ISO 639-1 codes). */
    static final String[] LANG_CODES = {"en", "fr", "es", "de", "it", "da", "sv", "ko", "ja"};
    static final String[] LANG_NAMES = {"English", "French", "Spanish", "German", "Italian", "Danish", "Swedish", "Korean", "Japanese"};

    static final JSONArray NOT_UK_AUDIENCE = new JSONArray(java.util.Arrays.asList("IN", "PK", "BD", "LK", "NP"));

    static final class Item {
        int id, providerId;
        String type, title, overview, backdrop, logo, year, genres;
        /** Exact title page on the service (JustWatch), opened directly in the app when it supports it. */
        String link = "";
        double rating;
        /** News: when the story was published (ms), for "2 h ago". */
        long published;
        /** JustWatch's first few backdrops; the sharpest becomes the backdrop (not saved). */
        final List<String> alts = new ArrayList<>();
        /** The title on TMDB, for its full-size artwork (not saved). */
        int tmdbId;

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("providerId", providerId).put("type", type)
                    .put("title", title).put("overview", overview).put("backdrop", backdrop)
                    .put("logo", logo == null ? "" : logo).put("year", year).put("genres", genres).put("rating", rating)
                    .put("link", link).put("pub", published);
        }

        static Item fromJson(JSONObject o) {
            Item i = new Item();
            i.id = o.optInt("id");
            i.providerId = o.optInt("providerId");
            i.type = o.optString("type");
            i.title = o.optString("title");
            i.overview = o.optString("overview");
            // Lists saved before the switch to JustWatch's 1920 px backdrops get them too.
            i.backdrop = o.optString("backdrop").replace("/s1440/", "/s1920/");
            i.logo = o.optString("logo");
            if (i.logo.isEmpty()) i.logo = null;
            i.year = o.optString("year");
            i.genres = o.optString("genres");
            i.rating = o.optDouble("rating", 0);
            i.link = o.optString("link");
            i.published = o.optLong("pub");
            return i;
        }

        Provider provider() {
            for (Provider p : PROVIDERS) if (p.id == providerId) return p;
            return null;
        }
    }

    // ------------------------------------------------------------------ settings

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** What this profile's top shelf shows (each profile chooses; older global setting as fallback). */
    static String shelf(Context c) {
        String own = Profiles.homePrefs(c).getString(K_SHELF, null);
        if (own != null) return own;
        SharedPreferences p = prefs(c);
        if (!p.contains(K_SHELF) && !p.getBoolean(K_ENABLED, true)) return SHELF_NONE;  // older setting
        return p.getString(K_SHELF, SHELF_RECS);
    }

    static void setShelf(Context c, String value) {
        Profiles.homePrefs(c).edit().putString(K_SHELF, value).apply();
        changed(c);
    }

    static void setShelf(Context c, boolean recs) {
        setShelf(c, recs ? SHELF_RECS : SHELF_NONE);
    }

    static boolean showRecs(Context c) {
        return !shelf(c).equals(SHELF_NONE);
    }

    static boolean enabled(Context c) {
        return !shelf(c).equals(SHELF_NONE);
    }

    static String source(Context c) {
        return prefs(c).getString(K_SOURCE, SRC_JUSTWATCH);
    }

    /** Whether the chosen source is usable right now (JustWatch always; TMDB once a token is set). */
    static boolean ready(Context c) {
        return !source(c).equals(SRC_TMDB) || hasToken(c);
    }

    static boolean hasToken(Context c) {
        return !prefs(c).getString(K_TOKEN, "").trim().isEmpty();
    }

    /** "both", "movie" or "tv". */
    static String types(Context c) {
        return prefs(c).getString(K_TYPES, "both");
    }

    static Set<String> providers(Context c) {
        // Until chosen in Settings: the services this TV has installed, so nothing shows as "Get".
        Set<String> def = new HashSet<>();
        for (Provider p : PROVIDERS) if (installed(c, p.pkg)) def.add(String.valueOf(p.id));
        return new HashSet<>(prefs(c).getStringSet(K_PROVIDERS, def));
    }

    static Set<String> languages(Context c) {
        return new HashSet<>(prefs(c).getStringSet(K_LANGS, new HashSet<>()));
    }

    static int advanceSeconds(Context c) {
        return prefs(c).getInt(K_ADVANCE, 8);
    }

    /** Bumped on every settings change so the home screen knows to refetch. */
    static void changed(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putInt(K_VERSION, p.getInt(K_VERSION, 0) + 1).apply();
        cacheFile(c).delete();
    }

    static int version(Context c) {
        return prefs(c).getInt(K_VERSION, 0);
    }

    // ------------------------------------------------------------------ data

    /** One cache per content mix, so profiles with different choices don't overwrite each other. */
    static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "suggestions_" + shelf(c) + "_uk9.json");  // bump when the service list or the picking changes
    }

    /** Cached list, or null when missing/stale. */
    static List<Item> cached(Context c, boolean allowStale) {
        File f = cacheFile(c);
        if (!f.exists()) return null;
        long maxAge = MAX_AGE_MS;
        if (!allowStale && System.currentTimeMillis() - f.lastModified() > maxAge) return null;
        try {
            JSONArray a = new JSONArray(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writes a saved list whole, then swaps it in: a reader at the same moment (Home reloading the
     * carousel) gets the old list or the new one, never a half-written file read as "nothing".
     */
    static void save(File f, String json) throws java.io.IOException {
        File tmp = new File(f.getPath() + ".part");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("couldn't save " + f.getName());
    }

    /** Fetches fresh suggestions from the chosen source (blocking; call off the UI thread). */
    static List<Item> fetch(Context c) throws Exception {
        List<Item> out = new ArrayList<>();
        Exception err = null;
        if (showRecs(c) && (!source(c).equals(SRC_TMDB) || hasToken(c))) {
            try {
                out = fetchRecs(c);
            } catch (Exception e) {
                err = e;
            }
        }
        android.util.Log.i("TvHome", "top shelf: " + out.size() + " recommendations");
        if (out.isEmpty() && err != null) throw err;
        JSONArray a = new JSONArray();
        for (Item it : out) a.put(it.toJson());
        save(cacheFile(c), a.toString());
        return out;
    }

    /** Recommendations from the chosen source, interleaved across services. */
    static List<Item> fetchRecs(Context c) throws Exception {
        List<List<Item>> sources = source(c).equals(SRC_TMDB) ? fetchTmdb(c) : fetchJustWatch(c);
        // Rotate: a random pick from each service's popular pool, so the carousel changes through the day.
        java.util.Random rnd = new java.util.Random();
        for (List<Item> src : sources) java.util.Collections.shuffle(src, rnd);
        java.util.Collections.shuffle(sources, rnd);
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int round = 0; round < PER_SOURCE && out.size() < MAX_ITEMS; round++) {
            for (List<Item> src : sources) {
                if (round < src.size() && out.size() < MAX_ITEMS) {
                    Item it = src.get(round);
                    if (seen.add(it.title.toLowerCase()) && sharp(c, it)) out.add(it);
                }
            }
        }
        saveByService(c, sources);
        return out;
    }

    /**
     * The title as people say it: without the country or year JustWatch adds to tell versions
     * apart ("Have I Got News for You (UK)", "The Office (US)", "Dune (2021)").
     */
    static String cleanTitle(String t) {
        return t.replaceAll("\\s*\\((UK|US|GB|AU|NZ|IE|CA|[0-9]{4})\\)\\s*$", "").trim();
    }

    /** Per service, kept for the app's own top shelf: up to this many sharp titles each. */
    static final int PER_SERVICE = 8;

    /** Each service's popular titles (series and films interleaved), for when its app is focused. */
    static void saveByService(Context c, List<List<Item>> sources) {
        Map<Integer, List<List<Item>>> by = new LinkedHashMap<>();
        for (List<Item> src : sources) {
            if (src.isEmpty()) continue;
            List<List<Item>> l = by.get(src.get(0).providerId);
            if (l == null) by.put(src.get(0).providerId, l = new ArrayList<>());
            l.add(src);
        }
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<Integer, List<List<Item>>> e : by.entrySet()) {
                JSONArray a = new JSONArray();
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < POOL && a.length() < PER_SERVICE; i++) {
                    for (List<Item> src : e.getValue()) {
                        if (i < src.size() && a.length() < PER_SERVICE) {
                            Item it = src.get(i);
                            if (seen.add(it.title.toLowerCase()) && sharp(c, it)) a.put(it.toJson());
                        }
                    }
                }
                o.put(String.valueOf(e.getKey()), a);
            }
            save(serviceFile(c), o.toString());
        } catch (Exception ignored) {
            // the mixed carousel still works without it
        }
        pruneImages(c);
    }

    static File serviceFile(Context c) {
        return new File(c.getFilesDir(), "suggestions_by_service_4.json");
    }

    /** The titles for the app with this package (its own top shelf), or an empty list. */
    static List<Item> forService(Context c, String pkg) {
        List<Item> out = new ArrayList<>();
        Provider p = null;
        for (Provider q : PROVIDERS) if (q.pkg.equals(pkg)) p = q;
        File f = serviceFile(c);
        if (p == null || !f.exists()) return out;
        try {
            JSONObject o = new JSONObject(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            JSONArray a = o.optJSONArray(String.valueOf(p.id));
            for (int i = 0; a != null && i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
            // nothing for this app
        }
        return out;
    }

    /** Artwork older than three days leaves the disk cache, so it can't fill the TV's storage. */
    static void pruneImages(Context c) {
        File[] files = new File(c.getCacheDir(), "tmdb").listFiles();
        long cut = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000;
        for (int i = 0; files != null && i < files.length; i++) {
            if (files[i].lastModified() < cut) files[i].delete();
        }
    }

    static final String JW_QUERY = "query P($country: Country!, $language: Language!, $first: Int!, $filter: TitleFilter, $sort: PopularTitlesSorting!) {"
            + " popularTitles(country: $country, filter: $filter, first: $first, sortBy: $sort, sortRandomSeed: 0) {"
            + " edges { node { id objectType content(country: $country, language: $language) {"
            + " title originalReleaseYear shortDescription genres { shortName } backdrops { backdropUrl } scoring { imdbScore imdbVotes }"
            + " externalIds { tmdbId } }"
            + " offers(country: $country, platform: WEB) { standardWebURL package { technicalName } } } } } }";

    /** What's popular in the UK per service, from JustWatch's public GraphQL endpoint (no key). */
    static List<List<Item>> fetchJustWatch(Context c) throws Exception {
        String t = types(c);
        List<String> kinds = t.equals("both") ? Arrays.asList("tv", "movie") : Arrays.asList(t);
        Set<String> provs = providers(c);
        List<List<Item>> sources = new ArrayList<>();
        for (Provider p : PROVIDERS) {
            if (!provs.contains(String.valueOf(p.id))) continue;
            for (String kind : kinds) {
                // What's worth watching now: trending this week, from the last two years, rated
                // 6.8+ on IMDb by enough people to mean it, and nothing made for children.
                int thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
                JSONObject filter = new JSONObject()
                        .put("packages", new JSONArray().put(p.jwShort))
                        .put("objectTypes", new JSONArray().put(kind.equals("tv") ? "SHOW" : "MOVIE"))
                        .put("monetizationTypes", new JSONArray().put("FLATRATE").put("FREE").put("ADS"))
                        .put("releaseYear", new JSONObject().put("min", thisYear - 2))
                        .put("imdbScore", new JSONObject().put("min", MIN_SCORE))
                        .put("excludeGenres", new JSONArray().put("fml").put("ani"))
                        // Popular with UK audiences overall, not with one community: South Asian
                        // productions trend in the UK charts but aren't what this home wants.
                        .put("excludeProductionCountries", NOT_UK_AUDIENCE);
                JSONObject vars = new JSONObject().put("country", "GB").put("language", "en")
                        .put("first", POOL).put("filter", filter).put("sort", "TRENDING");
                JSONObject body = new JSONObject().put("query", JW_QUERY).put("variables", vars);
                JSONObject res = post("https://apis.justwatch.com/graphql", body.toString());
                JSONArray edges = res.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
                List<Item> list = new ArrayList<>();
                for (int i = 0; i < edges.length() && list.size() < POOL; i++) {
                    JSONObject n = edges.getJSONObject(i).getJSONObject("node");
                    JSONObject ct = n.getJSONObject("content");
                    JSONArray bds = ct.optJSONArray("backdrops");
                    if (bds == null || bds.length() == 0) continue;
                    JSONObject score = ct.optJSONObject("scoring");
                    // Too obscure to judge; films need a wider audience than new British series.
                    if (score == null || score.optInt("imdbVotes", 0) < (kind.equals("tv") ? MIN_VOTES : MIN_VOTES_FILM)) continue;
                    Item it = new Item();
                    it.id = Math.abs(n.optString("id").hashCode());
                    it.type = kind;
                    it.providerId = p.id;
                    it.title = cleanTitle(ct.optString("title"));
                    it.overview = ct.optString("shortDescription");
                    for (int b = 0; b < Math.min(3, bds.length()); b++) {
                        it.alts.add("https://images.justwatch.com" + bds.getJSONObject(b).getString("backdropUrl")
                                .replace("{profile}", "s1920").replace("{format}", "jpg"));
                    }
                    it.backdrop = it.alts.get(0);
                    JSONObject ext = ct.optJSONObject("externalIds");
                    it.tmdbId = ext == null ? 0 : parseInt(ext.optString("tmdbId"));
                    it.logo = "";
                    int y = ct.optInt("originalReleaseYear", 0);
                    it.year = y > 0 ? String.valueOf(y) : "";
                    JSONObject sc = ct.optJSONObject("scoring");
                    it.rating = sc == null ? 0 : sc.optDouble("imdbScore", 0);
                    it.genres = jwGenres(ct.optJSONArray("genres"));
                    JSONArray offers = n.optJSONArray("offers");
                    for (int k = 0; offers != null && k < offers.length(); k++) {
                        JSONObject o = offers.getJSONObject(k);
                        if (p.jwTech.equals(o.getJSONObject("package").optString("technicalName"))) {
                            it.link = o.optString("standardWebURL");
                            break;
                        }
                    }
                    list.add(it);
                }
                sources.add(list);
            }
        }
        return sources;
    }

    static String jwGenres(JSONArray g) {
        Map<String, String> names = new LinkedHashMap<>();
        String[][] table = {{"act", "Action"}, {"ani", "Animation"}, {"cmy", "Comedy"}, {"crm", "Crime"},
                {"doc", "Documentary"}, {"drm", "Drama"}, {"fml", "Family"}, {"fnt", "Fantasy"}, {"hst", "History"},
                {"hrr", "Horror"}, {"msc", "Music"}, {"trl", "Thriller"}, {"rma", "Romance"}, {"scf", "Sci-Fi"},
                {"spt", "Sport"}, {"war", "War"}, {"wsn", "Western"}, {"rly", "Reality"}};
        for (String[] r : table) names.put(r[0], r[1]);
        List<String> out = new ArrayList<>();
        for (int i = 0; g != null && i < g.length() && out.size() < 2; i++) {
            String n = names.get(g.optJSONObject(i).optString("shortName"));
            if (n != null) out.add(n);
        }
        return String.join(" \u00b7 ", out);
    }

    static JSONObject post(String url, String json) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(12000);
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)");
        try {
            try (OutputStream os = con.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            return new JSONObject(new String(readAll(con.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            con.disconnect();
        }
    }

    static List<List<Item>> fetchTmdb(Context c) throws Exception {
        Map<Integer, String> movieGenres = genres(c, "movie"), tvGenres = genres(c, "tv");
        String t = types(c);
        List<String> kinds = t.equals("both") ? Arrays.asList("tv", "movie") : Arrays.asList(t);
        Set<String> provs = providers(c), langs = languages(c);

        // One popular list per (service, kind), then interleave so every service gets a turn.
        List<List<Item>> sources = new ArrayList<>();
        for (Provider p : PROVIDERS) {
            if (!provs.contains(String.valueOf(p.id))) continue;
            for (String kind : kinds) {
                StringBuilder q = new StringBuilder("/discover/").append(kind)
                        .append("?watch_region=GB&with_watch_monetization_types=flatrate&sort_by=popularity.desc")
                        .append("&language=en-GB&include_adult=false&with_watch_providers=").append(p.id);
                if (!langs.isEmpty()) q.append("&with_original_language=").append(URLEncoder.encode(String.join("|", langs), "UTF-8"));
                JSONArray results = get(c, q.toString()).optJSONArray("results");
                List<Item> list = new ArrayList<>();
                for (int i = 0; results != null && i < results.length() && list.size() < POOL; i++) {
                    JSONObject r = results.getJSONObject(i);
                    String backdrop = r.optString("backdrop_path", "");
                    if (backdrop.isEmpty() || backdrop.equals("null")) continue;
                    Item it = new Item();
                    it.id = r.getInt("id");
                    it.type = kind;
                    it.providerId = p.id;
                    it.title = r.optString(kind.equals("tv") ? "name" : "title");
                    it.overview = r.optString("overview");
                    it.backdrop = backdrop;
                    String date = r.optString(kind.equals("tv") ? "first_air_date" : "release_date", "");
                    it.year = date.length() >= 4 ? date.substring(0, 4) : "";
                    it.rating = r.optDouble("vote_average", 0);
                    it.genres = genreNames(r.optJSONArray("genre_ids"), kind.equals("tv") ? tvGenres : movieGenres);
                    list.add(it);
                }
                sources.add(list);
            }
        }
        return sources;
    }

    /** Title-treatment logo for an item (English preferred), fetched once and cached in the list. */
    static String logoFor(Context c, Item it) {
        if (it.logo != null) return it.logo;
        if (!source(c).equals(SRC_TMDB)) return "";
        try {
            JSONArray logos = get(c, "/" + it.type + "/" + it.id + "/images?include_image_language=en,null")
                    .optJSONArray("logos");
            String best = "";
            for (int i = 0; logos != null && i < logos.length(); i++) {
                JSONObject l = logos.getJSONObject(i);
                if (best.isEmpty() || "en".equals(l.optString("iso_639_1"))) {
                    best = l.optString("file_path");
                    if ("en".equals(l.optString("iso_639_1"))) break;
                }
            }
            it.logo = best;
        } catch (Exception e) {
            it.logo = "";
        }
        return it.logo;
    }

    static Map<Integer, String> genres(Context c, String kind) {
        File f = new File(c.getFilesDir(), "genres_" + kind + ".json");
        try {
            JSONObject o;
            if (f.exists()) {
                o = new JSONObject(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            } else {
                o = get(c, "/genre/" + kind + "/list?language=en");
                save(f, o.toString());
            }
            Map<Integer, String> m = new LinkedHashMap<>();
            JSONArray g = o.optJSONArray("genres");
            for (int i = 0; g != null && i < g.length(); i++) {
                m.put(g.getJSONObject(i).getInt("id"), g.getJSONObject(i).getString("name"));
            }
            return m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    static String genreNames(JSONArray ids, Map<Integer, String> names) {
        List<String> out = new ArrayList<>();
        for (int i = 0; ids != null && i < ids.length() && out.size() < 2; i++) {
            String n = names.get(ids.optInt(i));
            if (n != null) out.add(n.replace("Action & Adventure", "Action").replace("Sci-Fi & Fantasy", "Sci-Fi"));
        }
        return String.join(" \u00b7 ", out);
    }

    static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * A title's best backdrop on TMDB, where studios upload their key art at full size (often
     * 4K): textless first (no title printed on it), then English, the best rated, at least
     * 1920 px wide. Null without a key, without a match, or offline.
     */
    static String tmdbBackdrop(Context c, String type, int tmdbId) {
        if (tmdbId <= 0 || !hasToken(c)) return null;
        try {
            JSONObject r = get(c, "/" + ("tv".equals(type) ? "tv" : "movie") + "/" + tmdbId
                    + "/images?include_image_language=null,en");
            JSONArray b = r.optJSONArray("backdrops");
            String best = null;
            double bestScore = -1;
            for (int i = 0; b != null && i < b.length(); i++) {
                JSONObject o = b.getJSONObject(i);
                if (o.optInt("width") < 1920) continue;
                boolean textless = o.isNull("iso_639_1");
                // Textless first, then best liked; a 4K original only if nothing near the screen's
                // own width will do (it looks no sharper here, and takes far longer to fetch and open).
                double score = (textless ? 10 : 0) + (o.optInt("width") <= 2560 ? 1.5 : 0)
                        + o.optDouble("vote_average", 0) + Math.min(o.optInt("vote_count"), 10) * 0.05;
                if (score > bestScore) {
                    bestScore = score;
                    best = o.optString("file_path");
                }
            }
            return best == null || best.isEmpty() ? null : "https://image.tmdb.org/t/p/original" + best;
        } catch (Exception e) {
            return null;
        }
    }

    /** TMDB GET. Accepts either a v4 read-access token (eyJ\u2026) or a v3 API key. */
    static JSONObject get(Context c, String path) throws Exception {
        String token = prefs(c).getString(K_TOKEN, "").trim();
        if (token.isEmpty()) throw new IllegalStateException("No TMDB token");
        String url = "https://api.themoviedb.org/3" + path;
        boolean bearer = token.startsWith("eyJ");
        if (!bearer) url += (url.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(token, "UTF-8");
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Accept", "application/json");
        if (bearer) con.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IllegalStateException("TMDB HTTP " + code);
            return new JSONObject(new String(readAll(con.getInputStream()), StandardCharsets.UTF_8));
        } finally {
            con.disconnect();
        }
    }

    // ------------------------------------------------------------------ images

    /** TMDB image, cached on disk, decoded no larger than maxWidth (the box has little RAM). */
    static Bitmap image(Context c, String path, String size, int maxWidth) {
        if (path == null || path.isEmpty()) return null;
        try {
            File f = download(c, path, size);
            if (f == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= maxWidth) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A full-screen picture (backdrop): opened at about the width given in 16-bit colour (half the
     * memory, and no difference under the dark shades it sits beneath), and sent to the graphics
     * chip straight away, so showing it doesn't stall a frame.
     */
    static Bitmap screenImage(Context c, String url, int width) {
        try {
            File f = download(c, url, "");
            if (f == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            int w = o.outWidth / o.inSampleSize;
            if (w > width * 11 / 10) {
                o.inScaled = true;
                o.inDensity = w;
                o.inTargetDensity = width;
            }
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) b.prepareToDraw();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** The image's file in the disk cache, downloaded on first use; null when it can't be fetched. */
    static File download(Context c, String path, String size) throws Exception {
        File dir = new File(c.getCacheDir(), "tmdb");
        dir.mkdirs();
        boolean abs = path.startsWith("http");
        File f = new File(dir, (abs ? "u" + Integer.toHexString(path.hashCode()) : size + "_" + path.replace("/", "")) + ".img");
        if (!f.exists()) {
            HttpURLConnection con = (HttpURLConnection) new URL(abs ? path : "https://image.tmdb.org/t/p/" + size + path).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            try {
                if (con.getResponseCode() != 200) return null;
                byte[] data = readAll(con.getInputStream());
                File tmp = new File(dir, f.getName() + ".part");
                try (OutputStream os = new FileOutputStream(tmp)) {
                    os.write(data);
                }
                tmp.renameTo(f);
            } finally {
                con.disconnect();
            }
        }
        return f;
    }

    /**
     * False for artwork that was upscaled from a small original (soft and blocky on a 4K screen).
     * JustWatch serves every backdrop at one size, so size can't tell; fine detail can: the edge
     * energy of the centre at full size against the same area at half size. Real detail keeps
     * the ratio around 0.6-1.2; an upscaled image falls to about 0.4.
     */
    static boolean sharp(Context c, String url) {
        return sharpness(c, url) >= 0.55;
    }

    /**
     * Picks the sharpest of a title's first few backdrops (the first is often a soft frame from an
     * episode, the others the show's key art) and says whether it is sharp enough to show.
     */
    static boolean sharp(Context c, Item it) {
        // With a TMDB key, the studio's own artwork at full size; JustWatch's pictures otherwise.
        String art = tmdbBackdrop(c, it.type, it.tmdbId);
        if (art != null) {
            it.backdrop = art;
            return true;
        }
        if (it.alts.size() < 2) return sharp(c, it.backdrop);
        double best = -1;
        for (int i = 0; i < it.alts.size(); i++) {
            double r = sharpness(c, it.alts.get(i));
            // The first backdrop is JustWatch's own pick: another has to be clearly sharper.
            if (r > best + (i == 0 ? 0 : 0.05)) {
                best = r;
                it.backdrop = it.alts.get(i);
            }
        }
        return best >= 0.55;
    }

    /** The ratio below for one image: 1 when it can't be measured, 0 when it can't be fetched. */
    static double sharpness(Context c, String url) {
        if (url == null || !url.startsWith("http")) return 1;
        // Scored on the 1440 px copy: the threshold was set on that size (1920 px copies of the
        // same art score about 0.1 lower), and it's a smaller download.
        url = url.replace("/s1920/", "/s1440/");
        try {
            File f = download(c, url, "");
            if (f == null) return 0;
            android.graphics.BitmapRegionDecoder d = android.graphics.BitmapRegionDecoder.newInstance(f.getPath());
            int w = d.getWidth(), h = d.getHeight();
            android.graphics.Rect r = new android.graphics.Rect(w / 4, h / 4, w * 3 / 4, h * 3 / 4);
            BitmapFactory.Options o = new BitmapFactory.Options();
            Bitmap full = d.decodeRegion(r, o);
            o.inSampleSize = 2;
            Bitmap half = d.decodeRegion(r, o);
            d.recycle();
            double ratio = edges(full) / Math.max(1e-6, edges(half));
            full.recycle();
            half.recycle();
            return ratio;
        } catch (Exception e) {
            return 1;  // can't measure: don't drop the title for it
        }
    }

    /** Standard deviation of a 4-neighbour Laplacian over the image's luminance. */
    static double edges(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        float[] y = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            y[i] = 0.299f * ((p >> 16) & 255) + 0.587f * ((p >> 8) & 255) + 0.114f * (p & 255);
        }
        double sum = 0, sq = 0;
        int n = 0;
        for (int j = 1; j < h - 1; j++) {
            for (int i = 1; i < w - 1; i++) {
                int k = j * w + i;
                double l = 4 * y[k] - y[k - 1] - y[k + 1] - y[k - w] - y[k + w];
                sum += l;
                sq += l * l;
                n++;
            }
        }
        double mean = sum / n;
        return Math.sqrt(Math.max(0, sq / n - mean * mean));
    }

    static byte[] readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // ------------------------------------------------------------------ deep links

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Opens the title in its streaming app as directly as the app allows: the app's own search
     * for the title (search intent, then its web-search link), falling back to launching the app,
     * and to its Play Store page when it isn't installed. TMDB has no per-service title ids, so
     * search is the most precise link available.
     */
    static void open(Context c, Item it) {
        Provider p = it.provider();
        if (p == null) return;
        if (!installed(c, p.pkg)) {
            start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + p.pkg)));
            return;
        }
        String q = it.title;
        List<Intent> tries = new ArrayList<>();
        if (it.link != null && !it.link.isEmpty()) tries.add(view(it.link, p.pkg));
        Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(p.pkg).putExtra(SearchManager.QUERY, q);
        tries.add(search);
        String enc;
        try {
            enc = URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            enc = q;
        }
        switch (p.id) {
            case 8: tries.add(view("https://www.netflix.com/search?q=" + enc, p.pkg)); break;
            case 9: tries.add(view("https://www.primevideo.com/search/?phrase=" + enc, p.pkg)); break;
            case 38: tries.add(view("https://www.bbc.co.uk/iplayer/search?q=" + enc, p.pkg)); break;
            case 350: tries.add(view("https://tv.apple.com/search?term=" + enc, p.pkg)); break;
            case 337: tries.add(view("https://www.disneyplus.com/search?q=" + enc, p.pkg)); break;
            default: break;
        }
        PackageManager pm = c.getPackageManager();
        for (Intent i : tries) {
            if (i.resolveActivity(pm) != null && start(c, i)) return;
        }
        Intent launch = pm.getLeanbackLaunchIntentForPackage(p.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(p.pkg);
        if (launch != null) start(c, launch);
    }

    static Intent view(String url, String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg);
    }

    static boolean start(Context c, Intent i) {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}

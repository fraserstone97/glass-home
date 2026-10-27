package app.glasshome.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Guide's listings: Freely's own London guide (the channels on Freely for a London address,
 * and seven days either side of today), the same data its web guide shows. Only the channels
 * whose broadcaster's app plays them are kept, as those are the ones the Guide can open. Fetched
 * a day at a time and kept on disk; a day is fetched again when its copy is four hours old.
 */
final class GuideData {
    private GuideData() { }

    static final String API = "https://www.freely.co.uk/api/";
    /** Freely's network for a London postcode (its web guide's default region). */
    static final String NID = "64865";
    static final TimeZone LONDON = TimeZone.getTimeZone("Europe/London");
    static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    /** How far back and forward the guide goes, as Freely's does. */
    static final int DAYS_BACK = 7, DAYS_AHEAD = 7;
    static final long FRESH_MS = 4 * HOUR;

    /** The broadcasters whose own apps play their channels, live and on demand: owner, app, app name. */
    static final String[][] APPS = {
            {"BBC", "bbc.iplayer.android", "BBC iPlayer"},
            {"ITV PLC", "air.ITVMobilePlayer", "ITVX"},
            {"Channel4", "com.channel4.ondemand", "Channel 4"},
            {"Channel 5 Broadcasting Ltd", "com.channel5.my5", "My5"},
    };

    static final class Channel {
        String id, name, logo, live, pkg, app;
        int number;
        /** Its programmes, in time order, from every day fetched so far. */
        final List<Show> shows = new ArrayList<>();
    }

    static final class Show {
        String title, subtitle, image, catchUp;
        long start, end;
        Channel channel;
        /** Catch-up: from when until when it can be watched in the channel's app. */
        long availableFrom, availableTo;

        boolean onNow(long now) {
            return start <= now && now < end;
        }

        boolean watchable(long now) {
            return catchUp != null && availableFrom <= now && now < availableTo;
        }
    }

    // ---------------------------------------------------------------- days

    /** Midnight in London on the day of t. */
    static long dayStart(long t) {
        Calendar c = Calendar.getInstance(LONDON, Locale.UK);
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** The midnight n days after the one given (days aren't always 24 hours: the clocks change). */
    static long addDays(long midnight, int n) {
        Calendar c = Calendar.getInstance(LONDON, Locale.UK);
        c.setTimeInMillis(midnight);
        c.add(Calendar.DAY_OF_MONTH, n);
        return c.getTimeInMillis();
    }

    // ---------------------------------------------------------------- channels

    /** The channels the Guide can open, in channel-number order; from disk when fresh. */
    static List<Channel> channels(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "guide_channels.json");
        String json = fresh(f, DAY) ? read(f) : null;
        if (json == null) {
            try {
                json = get(API + "channel-list?nid=" + NID);
                Suggestions.save(f, json);
            } catch (Exception e) {
                if (!f.exists()) throw e;
                json = read(f);  // offline: the last list
            }
        }
        JSONArray a = new JSONObject(json).getJSONObject("data").getJSONArray("services");
        List<Channel> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.getJSONObject(i);
            if (!"tv".equals(s.optString("origination_type"))) continue;
            String[] app = app(s.optString("owner"));
            if (app == null) continue;
            Channel ch = new Channel();
            ch.id = s.optString("service_id");
            ch.name = s.optString("title");
            ch.number = s.optInt("logical_channel_number");
            ch.pkg = app[1];
            ch.app = app[2];
            JSONObject img = s.optJSONObject("images");
            ch.logo = img == null ? null : img.optString("16x9_white", null);
            JSONObject web = s.optJSONObject("streams") == null ? null : s.optJSONObject("streams").optJSONObject("web");
            ch.live = web != null && web.optBoolean("live") ? web.optString("url", null) : null;
            out.add(ch);
        }
        Collections.sort(out, (x, y) -> Integer.compare(x.number, y.number));
        return out;
    }

    static String[] app(String owner) {
        for (String[] a : APPS) if (a[0].equals(owner)) return a;
        return null;
    }

    // ---------------------------------------------------------------- listings

    /** One day's programmes (the day starting at midnight), by channel id; from disk when fresh. */
    static Map<String, List<Show>> day(Context c, long midnight) throws Exception {
        File f = new File(c.getCacheDir(), "guide_" + midnight / 1000 + ".json");
        // A day that is over doesn't change, but its catch-up does: kept for a day, then fetched again.
        long now = System.currentTimeMillis();
        boolean past = addDays(midnight, 1) < now;
        String json = fresh(f, past ? DAY : FRESH_MS) ? read(f) : null;
        if (json == null) {
            try {
                json = get(API + "tv-guide?nid=" + NID + "&start=" + midnight / 1000);
                if (json.isEmpty()) throw new java.io.IOException("empty");
                Suggestions.save(f, json);
            } catch (Exception e) {
                if (!f.exists()) throw e;
                json = read(f);
            }
        }
        Map<String, List<Show>> out = new HashMap<>();
        JSONArray progs = new JSONObject(json).getJSONObject("data").getJSONArray("programs");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.UK);
        for (int i = 0; i < progs.length(); i++) {
            JSONObject p = progs.getJSONObject(i);
            JSONArray events = p.optJSONArray("events");
            if (events == null) continue;
            List<Show> list = new ArrayList<>();
            for (int k = 0; k < events.length(); k++) {
                JSONObject e = events.getJSONObject(k);
                Show s = new Show();
                try {
                    s.start = iso.parse(e.getString("start_time")).getTime();
                } catch (Exception bad) {
                    continue;
                }
                s.end = s.start + duration(e.optString("duration"));
                if (s.end <= s.start) continue;
                s.title = e.optString("main_title");
                String sub = e.optString("secondary_title", "");
                // BBC's "subtitle" is often just the date; that's on screen already.
                s.subtitle = sub.matches("\\d{1,2}/\\d{1,2}/\\d{4}") ? "" : sub;
                String img = e.optString("image_url", "");
                if (img.isEmpty()) img = e.optString("fallback_image_url", "");
                s.image = img.isEmpty() ? null : img.replace("http://", "https://");
                JSONObject od = e.optJSONObject("on_demand");
                if (od != null) {
                    JSONObject tv = od.optJSONObject("player_links") == null ? null : od.optJSONObject("player_links").optJSONObject("tv");
                    s.catchUp = tv == null ? null : tv.optString("program_url", null);
                    try {
                        s.availableFrom = iso.parse(od.getString("start_of_availability")).getTime();
                        s.availableTo = iso.parse(od.getString("end_of_availability")).getTime();
                    } catch (Exception bad) {
                        s.catchUp = null;
                    }
                }
                list.add(s);
            }
            out.put(p.optString("service_id"), list);
        }
        return out;
    }

    static final Pattern ISO_DURATION = Pattern.compile("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");

    /** An ISO 8601 duration (PT1H30M) in milliseconds. */
    static long duration(String d) {
        Matcher m = ISO_DURATION.matcher(d == null ? "" : d);
        if (!m.matches()) return 0;
        long s = 0;
        if (m.group(1) != null) s += Long.parseLong(m.group(1)) * 86400;
        if (m.group(2) != null) s += Long.parseLong(m.group(2)) * 3600;
        if (m.group(3) != null) s += Long.parseLong(m.group(3)) * 60;
        if (m.group(4) != null) s += Long.parseLong(m.group(4));
        return s * 1000;
    }

    /** Adds a day's programmes to the channels, leaving out any already there (a day's edges overlap). */
    static void merge(List<Channel> channels, Map<String, List<Show>> day) {
        for (Channel ch : channels) {
            List<Show> add = day.get(ch.id);
            if (add == null || add.isEmpty()) continue;
            List<Show> all = ch.shows;
            for (Show s : add) {
                boolean have = false;
                for (Show o : all) {
                    if (o.start == s.start) {
                        have = true;
                        break;
                    }
                }
                if (have) continue;
                s.channel = ch;
                all.add(s);
            }
            Collections.sort(all, (x, y) -> Long.compare(x.start, y.start));
        }
    }

    /** Old days' copies, no longer in the guide's range. */
    static void prune(Context c) {
        long oldest = addDays(dayStart(System.currentTimeMillis()), -DAYS_BACK - 1) / 1000;
        File[] files = c.getCacheDir().listFiles((d, n) -> n.startsWith("guide_") && n.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            try {
                long t = Long.parseLong(f.getName().substring(6, f.getName().length() - 5));
                if (t < oldest) f.delete();
            } catch (NumberFormatException e) {
                f.delete();
            }
        }
    }

    // ---------------------------------------------------------------- network and disk

    static boolean fresh(File f, long ms) {
        return f.exists() && System.currentTimeMillis() - f.lastModified() < ms;
    }

    static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(Suggestions.readAll(in), StandardCharsets.UTF_8);
        }
    }

    static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(20000);
        // Freely's guide answers browsers: an app's default agent gets an empty reply.
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        con.setRequestProperty("Accept", "application/json");
        try {
            if (con.getResponseCode() != 200) throw new java.io.IOException("HTTP " + con.getResponseCode());
            return new String(Suggestions.readAll(con.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }

    /** A Freely picture at a width: its server wants one, and only 100, 200, 400 or 800. */
    static String sized(String url, int width) {
        return url == null ? null : url + (url.contains("?") ? "&" : "?") + "w=" + width;
    }
}

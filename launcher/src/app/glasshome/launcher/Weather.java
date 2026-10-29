package app.glasshome.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Current weather and a 5-day forecast from free services that need no key: Open-Meteo,
 * MET Norway (Yr) and wttr.in. The chosen source is tried first and the others are fallbacks.
 * The location comes from the internet address (ipapi.co, ipwho.is, geojs.io; looked up once a
 * day) unless a city is chosen in Settings. Every source is stored in one format, cached, and
 * refreshed on a schedule (every 15 minutes by default). Day and night come from the sun's position
 * when the forecast is shown, so the icons turn to the moon at sunset even with no new fetch.
 */
final class Weather {
    static final String PREFS = "weather";
    static final String K_ENABLED = "enabled", K_UNITS = "units", K_PLACE = "place", K_LAT = "lat", K_LON = "lon",
            K_MANUAL = "manual", K_SOURCE = "source", K_REFRESH = "refresh_min",
            K_CACHE = "cache2", K_CACHE_AT = "cache_at", K_CACHE_KEY = "cache_key",
            K_AUTO_LAT = "auto_lat", K_AUTO_LON = "auto_lon", K_AUTO_PLACE = "auto_place", K_AUTO_AT = "auto_at";
    static final String SRC_METOFFICE = "metoffice", SRC_OPEN_METEO = "openmeteo", SRC_MET = "met", SRC_WTTR = "wttr";
    static final String[] SOURCES = {SRC_METOFFICE, SRC_OPEN_METEO, SRC_MET, SRC_WTTR};
    static final int[] REFRESH_CHOICES = {10, 15, 30, 60};
    static final String UA = "GlassHome/1.1 (github.com/fraserstone97/glass-home)";

    private Weather() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, true);
    }

    /** "c" or "f"; automatic picks Fahrenheit for the US, Liberia and Myanmar. */
    static String units(Context c) {
        String u = prefs(c).getString(K_UNITS, "auto");
        if (!u.equals("auto")) return u;
        String cc = Locale.getDefault().getCountry();
        return cc.equals("US") || cc.equals("LR") || cc.equals("MM") ? "f" : "c";
    }

    static boolean manual(Context c) {
        return prefs(c).getBoolean(K_MANUAL, false);
    }

    /** The city chosen; else (automatic) the one found from the internet address. */
    static String place(Context c) {
        SharedPreferences p = prefs(c);
        return manual(c) ? p.getString(K_PLACE, "") : p.getString(K_AUTO_PLACE, p.getString(K_PLACE, ""));
    }

    static String source(Context c) {
        return prefs(c).getString(K_SOURCE, SRC_METOFFICE);
    }

    static String sourceName(String s) {
        return s.equals(SRC_METOFFICE) ? "Met Office" : s.equals(SRC_MET) ? "MET Norway" : s.equals(SRC_WTTR) ? "wttr.in" : "Open-Meteo";
    }

    /** Minutes between updates (15 by default). */
    static int refreshMinutes(Context c) {
        return prefs(c).getInt(K_REFRESH, 15);
    }

    /** Settings changed: the next refresh fetches again. */
    static void changed(Context c) {
        prefs(c).edit().remove(K_CACHE_AT).apply();
    }

    // ---------------------------------------------------------------- data

    static final class Hour {
        static final int HOUR = 0, SUNRISE = 1, SUNSET = 2;
        long time;
        int temp, code, rain = -1;
        int type;  // HOUR, or a sunrise or sunset cell put between the hours (never stored)
        boolean day;
        String label;
    }

    static final class Day {
        String date, label;
        int code, max, min, rain = -1;
        long rise, set;  // 0 when the source doesn't give them
    }

    static final class Data {
        String place = "", source = SRC_OPEN_METEO, windUnit = "km/h";
        long fetchedAt;
        int temp, code, humidity, wind, max, min;
        boolean day = true, fahrenheit;
        double lat = Double.NaN, lon = Double.NaN;
        final List<Hour> hours = new ArrayList<>();
        final List<Day> days = new ArrayList<>();

        JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("place", place).put("source", source).put("windUnit", windUnit).put("fetchedAt", fetchedAt)
                    .put("temp", temp).put("code", code).put("humidity", humidity)
                    .put("wind", wind).put("day", day).put("f", fahrenheit);
            if (!Double.isNaN(lat)) j.put("lat", lat).put("lon", lon);  // JSON has no NaN
            JSONArray h = new JSONArray();
            for (Hour x : hours) {
                h.put(new JSONObject().put("t", x.time).put("temp", x.temp).put("code", x.code).put("day", x.day)
                        .put("rain", x.rain));
            }
            JSONArray d = new JSONArray();
            for (Day x : days) {
                d.put(new JSONObject().put("date", x.date).put("code", x.code).put("max", x.max).put("min", x.min)
                        .put("rain", x.rain).put("rise", x.rise).put("set", x.set));
            }
            return j.put("hours", h).put("days", d);
        }

        static Data fromJson(JSONObject j) throws Exception {
            Data d = new Data();
            d.place = j.optString("place");
            d.source = j.optString("source", SRC_OPEN_METEO);
            d.windUnit = j.optString("windUnit", "km/h");
            d.fetchedAt = j.optLong("fetchedAt");
            d.temp = j.getInt("temp");
            d.code = j.getInt("code");
            d.humidity = j.optInt("humidity");
            d.wind = j.optInt("wind");
            d.day = j.optBoolean("day", true);
            d.fahrenheit = j.optBoolean("f", false);
            d.lat = j.optDouble("lat", Double.NaN);
            d.lon = j.optDouble("lon", Double.NaN);
            JSONArray h = j.getJSONArray("hours");
            for (int i = 0; i < h.length(); i++) {
                JSONObject o = h.getJSONObject(i);
                Hour x = new Hour();
                x.time = o.getLong("t");
                x.temp = o.getInt("temp");
                x.code = o.getInt("code");
                x.day = o.optBoolean("day", true);
                x.rain = o.optInt("rain", -1);
                d.hours.add(x);
            }
            JSONArray a = j.getJSONArray("days");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Day x = new Day();
                x.date = o.getString("date");
                x.code = o.getInt("code");
                x.max = o.getInt("max");
                x.min = o.getInt("min");
                x.rain = o.optInt("rain", -1);
                x.rise = o.optLong("rise");
                x.set = o.optLong("set");
                d.days.add(x);
            }
            return d;
        }
    }

    /** After this long without a new forecast, "Now" comes from the cached hourly forecast. */
    private static final long STALE_NOW = 60 * 60000L;

    // The last result of cached(), with what it was made from and the time it holds for.
    private static String memoRaw, memoZone;
    private static boolean memo24;
    private static Locale memoLocale;
    private static long memoFrom, memoUntil;
    private static Data memo;

    /**
     * The cached forecast ready to show: hours from the current hour ("Now" first, with sunrise and
     * sunset between them), days from today ("Today" first), and today's high/low. Null when nothing
     * has loaded yet, or when the forecast is too old to cover the current hour. It is parsed once
     * and shared until something it shows changes (the hour, day or night, a new forecast), so
     * callers must not change it.
     */
    static synchronized Data cached(Context c) {
        String raw = prefs(c).getString(K_CACHE, null);
        if (raw == null) return null;
        long now = System.currentTimeMillis();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        Locale loc = Locale.getDefault();
        String zone = TimeZone.getDefault().getID();
        if (raw.equals(memoRaw) && is24 == memo24 && loc.equals(memoLocale) && zone.equals(memoZone)
                && now >= memoFrom && now < memoUntil
                && (memo == null || Double.isNaN(memo.lat) || sunUp(memo.lat, memo.lon, now) == memo.day)) {
            return memo;
        }
        Data d;
        try {
            d = present(Data.fromJson(new JSONObject(raw)), now, is24);
        } catch (Exception e) {
            d = null;
        }
        // Good from this hour's start (a clock set back makes it again) until the next hour, the
        // switch to the hourly "Now", or the next sunrise or sunset cell.
        long hour = localHourStart(), until = hour + 3600000L;
        if (d != null && d.fetchedAt + STALE_NOW > now) until = Math.min(until, d.fetchedAt + STALE_NOW);
        if (d != null) {
            for (Hour h : d.hours) {
                if (h.type != Hour.HOUR) {
                    until = Math.min(until, h.time);
                    break;
                }
            }
        }
        memoRaw = raw;
        memoZone = zone;
        memo24 = is24;
        memoLocale = loc;
        memoFrom = hour;
        memoUntil = until;
        memo = d;
        return d;
    }

    private static Data present(Data d, long now, boolean is24) throws Exception {
        List<Hour> hours = new ArrayList<>(d.hours);
        List<Day> days = new ArrayList<>(d.days);
        d.hours.clear();
        d.days.clear();
        long hourStart = localHourStart();
        boolean old = now - d.fetchedAt > STALE_NOW, sun = !Double.isNaN(d.lat);
        SimpleDateFormat hourFmt = new SimpleDateFormat(is24 ? "HH" : "h a", Locale.getDefault());
        for (Hour h : hours) {
            if (h.time < hourStart || d.hours.size() >= 6) continue;
            if (d.hours.isEmpty()) {
                h.label = "Now";
                if (old) {
                    // Hours since the last fetch (standby, or every source failing): this hour's
                    // forecast is nearer the truth than the conditions back then.
                    d.temp = h.temp;
                    d.code = h.code;
                    d.day = h.day;
                } else {
                    h.temp = d.temp;
                    h.code = d.code;
                }
            } else {
                h.label = hourFmt.format(new Date(h.time)).replace(" ", "").toUpperCase(Locale.getDefault());
                if (sun) h.day = sunUp(d.lat, d.lon, h.time);
            }
            d.hours.add(h);
        }
        if (old && d.hours.isEmpty()) return null;  // older than its own forecast: wait for a new one
        // Day or night from the sun now, not from the fetch: a forecast fetched by day turns to the
        // moon at sunset. Caches from before the location was stored keep the source's flag.
        if (sun) d.day = sunUp(d.lat, d.lon, now);
        if (!d.hours.isEmpty()) d.hours.get(0).day = d.day;

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now));
        SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat dayFmt = new SimpleDateFormat("EEE", Locale.getDefault());
        for (Day x : days) {
            if (x.date.compareTo(today) < 0 || d.days.size() >= 5) continue;
            x.label = d.days.isEmpty() ? "Today" : dayFmt.format(ymd.parse(x.date));
            d.days.add(x);
        }

        // Sunrise and sunset between the hours, at their exact times, as Apple Weather shows them.
        if (d.hours.size() > 1) {
            long last = d.hours.get(d.hours.size() - 1).time;
            SimpleDateFormat timeFmt = new SimpleDateFormat(is24 ? "HH:mm" : "h:mma", Locale.getDefault());
            for (int k = 0; k < Math.min(2, d.days.size()); k++) {
                Day x = d.days.get(k);
                long rise = x.rise, set = x.set;
                if ((rise == 0 || set == 0) && sun) {  // MET Norway gives no times: work them out
                    long[] t = sunTimes(d.lat, d.lon, ymd.parse(x.date).getTime() + 12 * 3600000L);
                    if (rise == 0) rise = t[0];
                    if (set == 0) set = t[1];
                }
                sunCell(d.hours, rise, Hour.SUNRISE, now, last, timeFmt);
                sunCell(d.hours, set, Hour.SUNSET, now, last, timeFmt);
            }
            while (d.hours.size() > 6) d.hours.remove(d.hours.size() - 1);
        }

        if (!d.days.isEmpty()) {
            d.max = Math.max(d.days.get(0).max, d.temp);
            d.min = Math.min(d.days.get(0).min, d.temp);
        } else {
            d.max = d.min = d.temp;
        }
        return d;
    }

    /** Puts a sunrise or sunset cell after the hour it falls in, if it is still to come and before the last hour. */
    private static void sunCell(List<Hour> hours, long t, int type, long now, long last, SimpleDateFormat fmt) {
        if (t <= now || t >= last) return;
        Hour e = new Hour();
        e.time = t;
        e.type = type;
        e.label = fmt.format(new Date(t)).replace(" ", "").toUpperCase(Locale.getDefault());
        int i = 1;
        while (i < hours.size() && hours.get(i).time <= t) i++;
        hours.add(i, e);
    }

    static boolean stale(Context c) {
        SharedPreferences p = prefs(c);
        return System.currentTimeMillis() - p.getLong(K_CACHE_AT, 0) > refreshMinutes(c) * 60000L
                || !key(c).equals(p.getString(K_CACHE_KEY, ""));
    }

    private static String key(Context c) {
        return units(c) + "|" + source(c) + (manual(c) ? "|" + prefs(c).getFloat(K_LAT, 0) + "," + prefs(c).getFloat(K_LON, 0) : "|auto");
    }

    /** How long the location found from the internet address is kept: the TV doesn't move. */
    private static final long LOCATION_TTL = 24 * 3600000L;

    private static boolean busy, again;
    private static Runnable againDone;

    /**
     * Fetches in the background when the cache is due; {@code done} runs on the main thread once a
     * new forecast is saved (not after a failure, so a page rebuilt on it can't loop while offline).
     * A forced fetch asked for while one is running (Wi-Fi back while a fetch still waits out its
     * timeouts) runs as soon as that one ends.
     */
    static void refresh(Context ctx, boolean force, Runnable done) {
        Context c = ctx.getApplicationContext();
        if (!enabled(c) || (!force && !stale(c))) return;
        if (busy) {
            if (force) {
                again = true;
                againDone = done;
            }
            return;
        }
        busy = true;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            boolean saved = false;
            // What it's fetched for (units, source, place), read once at the start.
            String k = key(c);
            try {
                SharedPreferences p = prefs(c);
                double lat, lon;
                String place;
                boolean auto = !manual(c);
                if (!auto) {
                    lat = p.getFloat(K_LAT, 0);
                    lon = p.getFloat(K_LON, 0);
                    place = p.getString(K_PLACE, "");
                } else if (System.currentTimeMillis() - p.getLong(K_AUTO_AT, 0) < LOCATION_TTL) {
                    lat = p.getFloat(K_AUTO_LAT, 0);
                    lon = p.getFloat(K_AUTO_LON, 0);
                    place = p.getString(K_AUTO_PLACE, "");
                } else {
                    double[] ll = new double[2];
                    try {
                        place = locate(ll);
                        lat = ll[0];
                        lon = ll[1];
                        p.edit().putFloat(K_AUTO_LAT, (float) lat).putFloat(K_AUTO_LON, (float) lon)
                                .putString(K_AUTO_PLACE, place).putLong(K_AUTO_AT, System.currentTimeMillis()).apply();
                    } catch (Exception e) {
                        // The lookup services are down or rate-limited: the last place found will do.
                        if (!p.contains(K_AUTO_LAT)) throw e;
                        lat = p.getFloat(K_AUTO_LAT, 0);
                        lon = p.getFloat(K_AUTO_LON, 0);
                        place = p.getString(K_AUTO_PLACE, "");
                    }
                }
                boolean f = units(c).equals("f");
                // The chosen source first, then the others.
                List<String> order = new ArrayList<>();
                order.add(source(c));
                for (String s : SOURCES) if (!order.contains(s)) order.add(s);
                Data d = null;
                for (String s : order) {
                    try {
                        d = s.equals(SRC_MET) ? fetchMet(lat, lon, f) : s.equals(SRC_WTTR) ? fetchWttr(lat, lon, f)
                                : fetchOpenMeteo(lat, lon, f, s.equals(SRC_METOFFICE));
                        d.source = s;
                        break;
                    } catch (Exception ignored) {
                        // try the next source
                    }
                }
                if (d != null && !k.equals(key(c))) {
                    // Settings changed while it was fetching (units, the city): it's for the old
                    // ones, so it isn't kept, and the new ones are fetched next.
                    again = true;
                } else if (d != null) {
                    if (!place.isEmpty() || d.place.isEmpty()) d.place = place;
                    d.fetchedAt = System.currentTimeMillis();
                    d.fahrenheit = f;
                    d.lat = lat;
                    d.lon = lon;
                    SharedPreferences.Editor e = p.edit().putString(K_CACHE, d.toJson().toString())
                            .putLong(K_CACHE_AT, d.fetchedAt).putString(K_CACHE_KEY, k);
                    if (!auto) e.putString(K_PLACE, d.place);  // (the city you chose keeps its own name)
                    e.apply();
                    saved = true;
                } else if (auto) {
                    p.edit().putLong(K_AUTO_AT, 0).apply();  // every source failed: look the place up again next time
                }
            } catch (Exception ignored) {
                // Keep showing the last forecast.
            }
            boolean got = saved;
            main.post(() -> {
                busy = false;
                if (got && done != null) done.run();
                if (again) {
                    again = false;
                    Runnable next = againDone;
                    againDone = null;
                    refresh(c, true, next);
                }
            });
        }).start();
    }

    // ---------------------------------------------------------------- sources

    /**
     * Open-Meteo: hourly and daily forecast in the place's own time zone. With metOffice, the
     * forecast comes from the Met Office's own models (UKV 2 km over the UK, global elsewhere),
     * served by Open-Meteo without a key, with wind in mph as in UK forecasts.
     */
    private static Data fetchOpenMeteo(double lat, double lon, boolean f, boolean metOffice) throws Exception {
        JSONObject j = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + (metOffice ? "&models=ukmo_seamless" : "")
                + "&current=temperature_2m,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
                + "&hourly=temperature_2m,weather_code,is_day,precipitation_probability"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                + "&timezone=auto&forecast_days=6&forecast_hours=24"
                + (f ? "&temperature_unit=fahrenheit" : "") + (f || metOffice ? "&wind_speed_unit=mph" : "")));
        Data d = new Data();
        d.windUnit = f || metOffice ? "mph" : "km/h";
        JSONObject cur = j.getJSONObject("current");
        d.temp = round(cur.getDouble("temperature_2m"));
        d.code = cur.getInt("weather_code");
        d.day = cur.optInt("is_day", 1) == 1;
        d.humidity = cur.optInt("relative_humidity_2m");
        d.wind = round(cur.optDouble("wind_speed_10m", 0));
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone(j.optString("timezone", "UTC")));
        JSONObject h = j.getJSONObject("hourly");
        JSONArray ht = h.getJSONArray("time"), hp = h.optJSONArray("precipitation_probability");
        for (int i = 0; i < ht.length(); i++) {
            Hour x = new Hour();
            x.time = iso.parse(ht.getString(i)).getTime();
            x.temp = round(h.getJSONArray("temperature_2m").getDouble(i));
            x.code = h.getJSONArray("weather_code").getInt(i);
            x.day = h.getJSONArray("is_day").getInt(i) == 1;
            x.rain = hp == null || hp.isNull(i) ? -1 : hp.getInt(i);
            d.hours.add(x);
        }
        JSONObject dl = j.getJSONObject("daily");
        JSONArray dt = dl.getJSONArray("time"), rain = dl.optJSONArray("precipitation_probability_max"),
                rise = dl.optJSONArray("sunrise"), set = dl.optJSONArray("sunset");
        for (int i = 0; i < dt.length(); i++) {
            Day x = new Day();
            x.date = dt.getString(i);
            x.code = dl.getJSONArray("weather_code").getInt(i);
            x.max = round(dl.getJSONArray("temperature_2m_max").getDouble(i));
            x.min = round(dl.getJSONArray("temperature_2m_min").getDouble(i));
            x.rain = rain == null || rain.isNull(i) ? -1 : rain.getInt(i);
            x.rise = time(iso, rise == null ? null : rise.optString(i, null));
            x.set = time(iso, set == null ? null : set.optString(i, null));
            d.days.add(x);
        }
        return d;
    }

    /** MET Norway (Yr) Locationforecast: UTC time series in \u00b0C and m/s; days grouped locally. */
    private static Data fetchMet(double lat, double lon, boolean f) throws Exception {
        JSONObject j = new JSONObject(get(String.format(Locale.US,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon)));
        JSONArray ts = j.getJSONObject("properties").getJSONArray("timeseries");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Data d = new Data();
        d.windUnit = f ? "mph" : "km/h";
        long now = System.currentTimeMillis();
        Map<String, Day> days = new LinkedHashMap<>();
        Map<String, Long> noonGap = new LinkedHashMap<>();
        boolean first = true;
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            long t = iso.parse(e.getString("time")).getTime();
            JSONObject data = e.getJSONObject("data");
            JSONObject inst = data.getJSONObject("instant").getJSONObject("details");
            double c = inst.getDouble("air_temperature");
            String sym = symbol(data, "next_1_hours");
            if (sym == null) sym = symbol(data, "next_6_hours");
            if (first && t <= now + 3600000L) {
                double ws = inst.optDouble("wind_speed", 0), rh = inst.optDouble("relative_humidity", 50);
                d.temp = temp(c, f);
                d.humidity = (int) Math.round(rh);
                d.wind = (int) Math.round(f ? ws * 2.237 : ws * 3.6);
                d.code = metCode(sym);
                d.day = sym == null || !sym.contains("night");
                first = false;
            }
            if (t >= now - 3600000L && t <= now + 24 * 3600000L && data.has("next_1_hours")) {
                Hour x = new Hour();
                x.time = t;
                x.temp = temp(c, f);
                x.code = metCode(sym);
                x.day = sym == null || !sym.contains("night");
                d.hours.add(x);
            }
            String date = ymd.format(new Date(t));
            Day day = days.get(date);
            if (day == null) {
                day = new Day();
                day.date = date;
                day.max = Integer.MIN_VALUE;
                day.min = Integer.MAX_VALUE;
                day.code = 3;
                days.put(date, day);
            }
            day.max = Math.max(day.max, temp(c, f));
            day.min = Math.min(day.min, temp(c, f));
            // The day's weather: the symbol nearest midday.
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(t);
            long gap = Math.abs(cal.get(Calendar.HOUR_OF_DAY) - 12);
            Long best = noonGap.get(date);
            String daySym = symbol(data, "next_6_hours");
            if (daySym == null) daySym = sym;
            if (daySym != null && (best == null || gap < best)) {
                noonGap.put(date, gap);
                day.code = metCode(daySym);
            }
        }
        if (first) throw new Exception("No current data");
        d.days.addAll(days.values());
        return d;
    }

    /** wttr.in JSON: current conditions and 3 days in 3-hour steps, local time. */
    private static Data fetchWttr(double lat, double lon, boolean f) throws Exception {
        JSONObject j = new JSONObject(get(String.format(Locale.US, "https://wttr.in/%.4f,%.4f?format=j1", lat, lon)));
        JSONObject cur = j.getJSONArray("current_condition").getJSONObject(0);
        long now = System.currentTimeMillis();
        Data d = new Data();
        d.windUnit = f ? "mph" : "km/h";
        d.temp = cur.getInt(f ? "temp_F" : "temp_C");
        d.humidity = cur.optInt("humidity");
        d.wind = cur.optInt(f ? "windspeedMiles" : "windspeedKmph");
        d.code = wwoCode(cur.optInt("weatherCode", 116));
        d.day = sunUp(lat, lon, now);
        try {
            d.place = j.getJSONArray("nearest_area").getJSONObject(0).getJSONArray("areaName").getJSONObject(0).getString("value");
        } catch (Exception ignored) {
            // no area name
        }
        SimpleDateFormat local = new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US);
        SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US);
        JSONArray w = j.getJSONArray("weather");
        for (int i = 0; i < w.length(); i++) {
            JSONObject o = w.getJSONObject(i);
            Day x = new Day();
            x.date = o.getString("date");
            x.max = o.getInt(f ? "maxtempF" : "maxtempC");
            x.min = o.getInt(f ? "mintempF" : "mintempC");
            JSONArray astro = o.optJSONArray("astronomy");
            if (astro != null && astro.optJSONObject(0) != null) {  // "06:43 AM", local time
                x.rise = time(clock, x.date + " " + astro.optJSONObject(0).optString("sunrise"));
                x.set = time(clock, x.date + " " + astro.optJSONObject(0).optString("sunset"));
            }
            JSONArray hr = o.getJSONArray("hourly");
            x.code = 3;
            x.rain = 0;
            for (int k = 0; k < hr.length(); k++) {
                JSONObject h = hr.getJSONObject(k);
                int hhmm = h.getInt("time");
                int code = wwoCode(h.optInt("weatherCode", 116));
                if (hhmm == 1200) x.code = code;
                x.rain = Math.max(x.rain, h.optInt("chanceofrain", 0));
                Hour hour = new Hour();
                hour.time = local.parse(x.date + " " + String.format(Locale.US, "%04d", hhmm)).getTime();
                hour.temp = h.getInt(f ? "tempF" : "tempC");
                hour.code = code;
                hour.day = sunUp(lat, lon, hour.time);
                hour.rain = h.optInt("chanceofrain", -1);
                d.hours.add(hour);
            }
            d.days.add(x);
        }
        // 3-hour steps: keep the step containing now as the first hour.
        List<Hour> keep = new ArrayList<>();
        for (Hour h : d.hours) if (h.time + 3 * 3600000L > now) keep.add(h);
        if (!keep.isEmpty()) keep.get(0).time = Math.max(keep.get(0).time, localHourStart());
        d.hours.clear();
        d.hours.addAll(keep);
        return d;
    }

    private static String symbol(JSONObject data, String period) {
        JSONObject p = data.optJSONObject(period);
        if (p == null || p.optJSONObject("summary") == null) return null;
        return p.optJSONObject("summary").optString("symbol_code", null);
    }

    /** MET Norway symbol \u2192 WMO weather code. */
    static int metCode(String sym) {
        if (sym == null) return 3;
        String s = sym.replace("_day", "").replace("_night", "").replace("_polartwilight", "");
        if (s.contains("thunder")) return 95;
        if (s.contains("snow")) return s.startsWith("heavy") ? 75 : s.startsWith("light") ? 71 : 73;
        if (s.contains("sleet")) return 68;
        if (s.contains("rain")) {
            if (s.contains("showers")) return s.startsWith("heavy") ? 82 : 80;
            return s.startsWith("heavy") ? 65 : s.startsWith("light") ? 61 : 63;
        }
        switch (s) {
            case "clearsky": return 0;
            case "fair": return 1;
            case "partlycloudy": return 2;
            case "fog": return 45;
            default: return 3;
        }
    }

    /** World Weather Online code (used by wttr.in) \u2192 WMO weather code. */
    static int wwoCode(int c) {
        switch (c) {
            case 113: return 0;
            case 116: return 2;
            case 143: case 248: case 260: return 45;
            case 176: case 263: case 266: case 293: case 296: case 353: return 61;
            case 299: case 302: case 356: return 63;
            case 305: case 308: case 359: return 65;
            case 179: case 227: case 323: case 326: case 368: return 71;
            case 329: case 332: case 335: case 371: return 73;
            case 230: case 338: case 392: case 395: return 75;
            case 185: case 281: return 56;
            case 284: return 57;
            case 311: return 66;
            case 314: return 67;
            case 182: case 317: case 320: case 350: case 362: case 365: case 374: case 377: return 68;
            case 200: case 386: case 389: return 95;
            default: return 3;
        }
    }

    /** A local date and time parsed with fmt, or 0 when it is missing or isn't a time ("No sunset"). */
    private static long time(SimpleDateFormat fmt, String s) {
        if (s == null) return 0;
        try {
            return fmt.parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Start of the current hour in local time (half-hour time zones included). */
    static long localHourStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static int temp(double celsius, boolean f) {
        return round(f ? celsius * 9 / 5 + 32 : celsius);
    }

    private static int round(double v) {
        return (int) Math.round(v);
    }

    /** A chance of rain to show: only from 10%, in steps of 10%, as Apple Weather shows it; -1 for none. */
    static int chance(int raw) {
        return raw < 10 ? -1 : Math.min(100, Math.round(raw / 10f) * 10);
    }

    // ---------------------------------------------------------------- sun

    private static final long DAY = 86400000L;
    private static final double HORIZON = -0.01454;  // sin(-0.833\u00b0): the sun's top edge, with refraction

    /** Whether the sun is up at lat/lon at time t (as WeatherKit's isDaylight). */
    static boolean sunUp(double lat, double lon, long t) {
        double[] s = sun(t);
        double phi = Math.toRadians(lat);
        return Math.sin(phi) * Math.sin(s[0]) + Math.cos(phi) * Math.cos(s[0]) * Math.cos(Math.toRadians(s[1] + lon)) > HORIZON;
    }

    /** Sunrise and sunset (ms) on the day around t at lat/lon; 0 for one that doesn't happen (polar day or night). */
    static long[] sunTimes(double lat, double lon, long t) {
        long shift = (long) (lon / 360 * DAY);
        long noon = Math.floorDiv(t + shift, DAY) * DAY + DAY / 2 - shift;
        noon -= (long) (wrap(sun(noon)[1] + lon) / 360 * DAY);  // the equation of time
        return new long[]{crossing(lat, lon, noon, -1), crossing(lat, lon, noon, 1)};
    }

    /** When the sun's edge crosses the horizon before (side -1) or after (side 1) noon: estimated, then refined once. */
    private static long crossing(double lat, double lon, long noon, int side) {
        long t = noon;
        double phi = Math.toRadians(lat);
        for (int i = 0; i < 2; i++) {
            double[] s = sun(t);
            double cosH = (HORIZON - Math.sin(phi) * Math.sin(s[0])) / (Math.cos(phi) * Math.cos(s[0]));
            if (cosH < -1 || cosH > 1) return 0;
            t += (long) (wrap(side * Math.toDegrees(Math.acos(cosH)) - (s[1] + lon)) / 360 * DAY);
        }
        return t;
    }

    /**
     * The sun's declination (radians) and Greenwich hour angle (degrees) at time t, from Meeus's
     * low-precision solar coordinates: well under a minute out for sunrise and sunset.
     */
    private static double[] sun(long t) {
        double n = t / (double) DAY - 10957.5;  // days since J2000.0
        double g = Math.toRadians(357.528 + 0.9856003 * n);
        double lambda = Math.toRadians(280.460 + 0.9856474 * n + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double eps = Math.toRadians(23.439 - 0.0000004 * n);
        double ra = Math.toDegrees(Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda)));
        return new double[]{Math.asin(Math.sin(eps) * Math.sin(lambda)), (280.46061837 + 360.98564736629 * n - ra) % 360};
    }

    private static double wrap(double deg) {
        deg %= 360;
        return deg > 180 ? deg - 360 : deg < -180 ? deg + 360 : deg;
    }

    // ---------------------------------------------------------------- location

    /** Approximate location from the internet address; returns the city name. */
    private static String locate(double[] out) throws Exception {
        String[] services = {"https://ipapi.co/json/", "https://ipwho.is/", "https://get.geojs.io/v1/ip/geo.json"};
        Exception last = null;
        for (String url : services) {
            try {
                JSONObject j = new JSONObject(get(url));
                if (!j.has("latitude") || j.optBoolean("error") || !j.optBoolean("success", true)) continue;
                out[0] = Double.parseDouble(j.getString("latitude"));
                out[1] = Double.parseDouble(j.getString("longitude"));
                return j.optString("city", "");
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("No location");
    }

    static final class Place {
        String name, detail;
        double lat, lon;
    }

    /** City search for Settings (Open-Meteo geocoding). Call off the main thread. */
    static List<Place> search(String q) throws Exception {
        JSONObject j = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name="
                + URLEncoder.encode(q.trim(), "UTF-8")));
        List<Place> out = new ArrayList<>();
        JSONArray a = j.optJSONArray("results");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            Place pl = new Place();
            pl.name = r.optString("name");
            StringBuilder d = new StringBuilder();
            for (String k : new String[]{"admin1", "country"}) {
                String v = r.optString(k, "");
                if (!v.isEmpty()) d.append(d.length() > 0 ? ", " : "").append(v);
            }
            pl.detail = d.toString();
            pl.lat = r.getDouble("latitude");
            pl.lon = r.getDouble("longitude");
            out.add(pl);
        }
        return out;
    }

    static void setPlace(Context c, Place pl) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (pl == null) e.putBoolean(K_MANUAL, false);
        else e.putBoolean(K_MANUAL, true).putString(K_PLACE, pl.name)
                .putFloat(K_LAT, (float) pl.lat).putFloat(K_LON, (float) pl.lon);
        e.remove(K_CACHE_AT).apply();
    }

    /**
     * WMO weather code -> short description, in WeatherKit's own (en-GB) words. Codes 68 and 69
     * (rain and snow) carry MET Norway's and wttr.in's sleet; Open-Meteo never sends them.
     */
    static String describe(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly Sunny" : "Mostly Clear";
            case 2: return "Partly Cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing Drizzle";
            case 61: case 63: case 80: case 81: return "Rain";
            case 65: case 82: return "Heavy Rain";
            case 66: case 67: return "Freezing Rain";
            case 68: case 69: return "Sleet";
            case 71: case 77: case 85: return "Flurries";
            case 73: return "Snow";
            case 75: case 86: return "Heavy Snow";
            case 95: case 96: return "Thunderstorm";
            case 99: return "Severe Thunderstorm";
            default: return "";
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(10000);
        con.setRequestProperty("User-Agent", UA);
        try {
            return new String(Suggestions.readAll(con.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }
}

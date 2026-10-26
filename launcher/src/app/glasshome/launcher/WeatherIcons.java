package app.glasshome.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.LruCache;

/**
 * Apple's weather symbols (the multicolour .fill set of SF Symbols), drawn in code: a flat yellow
 * sun, cyan rain, and everything else white (cloud, moon, stars, bolt, snow, fog), with a clear gap
 * where a cloud passes in front of the sun or moon. Night symbols have no yellow. All share one
 * scale, so a lone sun is smaller than a cloud with a sun, as in SF Symbols. Sizes are fractions of
 * the bitmap's side, measured from Apple's renders. Cached by symbol and size.
 */
final class WeatherIcons {
    static final String SUNRISE = "sunrise", SUNSET = "sunset";
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(48);
    // Dark-appearance systemYellow and systemCyan, as the symbols take them on tvOS.
    private static final int SUN = 0xFFFFD600, DROP = 0xFF3CD3FE, WHITE = 0xFFFFFFFF;
    private static final float SHORT = 0.079f, LONG = 0.177f;

    private WeatherIcons() {
    }

    static Bitmap get(int code, boolean day, int size) {
        return get(symbol(code, day), size);
    }

    static Bitmap get(String symbol, int size) {
        String key = symbol + size;
        Bitmap b = CACHE.get(key);
        if (b != null) return b;
        b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        draw(new Canvas(b), symbol, size);
        CACHE.put(key, b);
        return b;
    }

    /**
     * WMO weather code -> Apple's symbol, as WeatherKit picks it. Only clear, partly cloudy and
     * drizzle change at night; mostly clear has no cloud, and plain snow is a bare snowflake.
     */
    static String symbol(int code, boolean day) {
        switch (code) {
            case 0: case 1: return day ? "sun.max" : "moon.stars";
            case 2: return day ? "cloud.sun" : "cloud.moon";
            case 45: case 48: return "cloud.fog";
            case 51: case 53: case 55: return day ? "cloud.drizzle" : "cloud.moon.rain";
            case 56: case 57: return "cloud.drizzle";
            case 61: case 63: case 80: case 81: return "cloud.rain";
            case 65: case 82: return "cloud.heavyrain";
            case 66: case 67: case 68: case 69: return "cloud.sleet";
            case 71: case 73: case 77: case 85: return "snowflake";
            case 75: case 86: return "cloud.snow";
            case 95: case 96: case 99: return "cloud.bolt.rain";
            default: return "cloud";
        }
    }

    private static void draw(Canvas c, String symbol, float s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeCap(Paint.Cap.ROUND);
        switch (symbol) {
            case "sun.max":
                sun(c, p, s, 0.5f, 0.5f, 0.174f, 0, 45, 90, 135, 180, 225, 270, 315);
                break;
            case "moon.stars":
                moon(c, p, s, 0.458f, 0.563f, 0.29f);
                star(c, p, s, 0.708f, 0.409f, 0.129f);
                star(c, p, s, 0.541f, 0.224f, 0.074f);
                break;
            case "cloud.sun":  // the cloud hides the lower-left rays
                sun(c, p, s, 0.645f, 0.505f, 0.165f, -135, -90, -45, 0, 45);
                cloud(c, p, s, 0, 0.39f, 0.70f, true);
                break;
            case "cloud.moon":
                moon(c, p, s, 0.74f, 0.407f, 0.203f);
                cloud(c, p, s, 0.048f, 0.333f, 0.705f, true);
                break;
            case "cloud.moon.rain":
                moon(c, p, s, 0.738f, 0.271f, 0.202f);
                cloud(c, p, s, 0.05f, 0.20f, 0.70f, true);
                drops(c, p, s, SHORT, 0.212f, 0.73f, 0.481f, 0.728f, 0.32f, 0.826f, 0.589f, 0.826f);
                break;
            case "cloud.fog":
                cloud(c, p, s, 0.106f, 0.119f, 0.788f, false);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.04f);
                c.drawLine(s * 0.276f, s * 0.741f, s * 0.732f, s * 0.741f, p);
                c.drawLine(s * 0.276f, s * 0.859f, s * 0.732f, s * 0.859f, p);
                break;
            case "cloud.drizzle":
                rainCloud(c, p, s);
                drops(c, p, s, SHORT, 0.303f, 0.698f, 0.587f, 0.698f, 0.413f, 0.795f, 0.697f, 0.795f);
                break;
            case "cloud.rain":
                rainCloud(c, p, s);
                drops(c, p, s, SHORT, 0.303f, 0.698f, 0.587f, 0.698f);
                drops(c, p, s, LONG, 0.445f, 0.698f, 0.72f, 0.698f);
                break;
            case "cloud.heavyrain":
                rainCloud(c, p, s);
                drops(c, p, s, LONG, 0.295f, 0.698f, 0.437f, 0.698f, 0.571f, 0.698f, 0.713f, 0.698f);
                break;
            case "cloud.sleet":  // a row of drops over a row of flakes, the drops short enough to clear them
                rainCloud(c, p, s);
                drops(c, p, s, 0.047f, 0.319f, 0.698f, 0.681f, 0.698f);
                drops(c, p, s, 0.1f, 0.5f, 0.698f);
                flakes(c, p, s, 0.248f, 0.837f, 0.413f, 0.888f, 0.61f, 0.837f);
                break;
            case "cloud.snow":
                rainCloud(c, p, s);
                flakes(c, p, s, 0.287f, 0.734f, 0.484f, 0.734f, 0.673f, 0.734f,
                        0.216f, 0.865f, 0.405f, 0.865f, 0.595f, 0.865f);
                break;
            case "cloud.bolt.rain": {  // a white bolt between the drops
                rainCloud(c, p, s);
                drops(c, p, s, SHORT, 0.248f, 0.698f, 0.327f, 0.795f, 0.721f, 0.698f, 0.80f, 0.795f);
                float[] at = {0.453f, 0.675f, 0.543f, 0.675f, 0.508f, 0.756f, 0.587f, 0.756f,
                        0.437f, 0.927f, 0.476f, 0.805f, 0.399f, 0.805f};
                Path bolt = new Path();
                bolt.moveTo(s * at[0], s * at[1]);
                for (int i = 2; i < at.length; i += 2) bolt.lineTo(s * at[i], s * at[i + 1]);
                bolt.close();
                p.setStyle(Paint.Style.FILL);
                p.setColor(WHITE);
                p.setPathEffect(new CornerPathEffect(s * 0.012f));
                c.drawPath(bolt, p);
                p.setPathEffect(null);
                break;
            }
            case "snowflake": {  // three bars, each end with a V
                p.setStyle(Paint.Style.STROKE);
                p.setColor(WHITE);
                p.setStrokeWidth(s * 0.042f);
                float m = s / 2f, k = s * 0.326f;
                for (int i = 0; i < 6; i++) {
                    double a = Math.toRadians(90 + 60 * i);
                    float dx = (float) Math.cos(a) * k, dy = (float) Math.sin(a) * k;
                    if (i < 3) c.drawLine(m - dx, m - dy, m + dx, m + dy, p);
                    float ax = m + dx * 0.574f, ay = m + dy * 0.574f, ex = m + dx * 0.754f, ey = m + dy * 0.754f;
                    c.drawLine(ax, ay, ex - dy * 0.316f, ey + dx * 0.316f, p);
                    c.drawLine(ax, ay, ex + dy * 0.316f, ey - dx * 0.316f, p);
                }
                break;
            }
            case SUNRISE:
            case SUNSET: {  // half a sun on the horizon, an arrow above it pointing up or down
                p.setStyle(Paint.Style.STROKE);
                p.setColor(WHITE);
                p.setStrokeWidth(s * 0.051f);
                c.drawLine(s * 0.085f, s * 0.827f, s * 0.915f, s * 0.827f, p);
                boolean up = symbol.equals(SUNRISE);
                float m = s / 2f, tip = s * (up ? 0.17f : 0.395f), dir = up ? 1 : -1, k = s * 0.105f;
                c.drawLine(m, s * 0.17f, m, s * 0.395f, p);
                c.drawLine(m, tip, m - k, tip + dir * k, p);
                c.drawLine(m, tip, m + k, tip + dir * k, p);
                c.save();
                c.clipRect(0, 0, s, s * 0.757f);
                sun(c, p, s, 0.5f, 0.689f, 0.183f, 180, -135, -45, 0);
                c.restore();
                break;
            }
            default:
                cloud(c, p, s, 0.09f, 0.23f, 0.82f, false);
        }
    }

    /** The cloud shared by the rain, snow and storm symbols; its base is at 0.625. */
    private static void rainCloud(Canvas c, Paint p, float s) {
        cloud(c, p, s, 0.106f, 0.104f, 0.788f, false);
    }

    /** A sun of radius r with round-capped rays at the given angles (degrees, clockwise from 3 o'clock). */
    private static void sun(Canvas c, Paint p, float s, float x, float y, float r, int... rays) {
        float cx = s * x, cy = s * y, rad = s * r;
        p.setColor(SUN);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(rad * 0.33f);
        for (int deg : rays) {
            double a = Math.toRadians(deg);
            float dx = (float) Math.cos(a) * rad, dy = (float) Math.sin(a) * rad;
            c.drawLine(cx + dx * 1.555f, cy + dy * 1.555f, cx + dx * 1.985f, cy + dy * 1.985f, p);
        }
    }

    /** A crescent: a disc with a larger disc taken out of its upper right. */
    private static void moon(Canvas c, Paint p, float s, float x, float y, float r) {
        float cx = s * x, cy = s * y, rad = s * r;
        Path full = new Path(), bite = new Path();
        full.addCircle(cx, cy, rad, Path.Direction.CW);
        bite.addCircle(cx + rad * 0.5f, cy - rad * 0.63f, rad * 0.93f, Path.Direction.CW);
        full.op(bite, Path.Op.DIFFERENCE);
        p.setColor(WHITE);
        p.setStyle(Paint.Style.FILL);
        c.drawPath(full, p);
    }

    /** A four-pointed star with arms of length a, pinched at the waist like SF's sparkle. */
    private static void star(Canvas c, Paint p, float s, float x, float y, float a) {
        float cx = s * x, cy = s * y, k = s * a, w = k * 0.25f;
        Path path = new Path();
        path.moveTo(cx, cy - k);
        path.quadTo(cx + w, cy - w, cx + k, cy);
        path.quadTo(cx + w, cy + w, cx, cy + k);
        path.quadTo(cx - w, cy + w, cx - k, cy);
        path.quadTo(cx - w, cy - w, cx, cy - k);
        p.setColor(WHITE);
        p.setStyle(Paint.Style.FILL);
        c.drawPath(path, p);
    }

    /**
     * A cloud w wide (0.661 w tall) with its top-left at (l, t): three puffs on a flat base. With
     * gap, a clear band is cut round it first, so it stands apart from the sun or moon behind.
     */
    private static void cloud(Canvas c, Paint p, float s, float l, float t, float w, boolean gap) {
        float x = s * l, y = s * t, k = s * w;
        Path path = new Path(), part = new Path();
        path.addCircle(x + k * 0.455f, y + k * 0.316f, k * 0.316f, Path.Direction.CW);
        part.addCircle(x + k * 0.76f, y + k * 0.421f, k * 0.24f, Path.Direction.CW);
        path.op(part, Path.Op.UNION);
        part.reset();
        part.addCircle(x + k * 0.18f, y + k * 0.481f, k * 0.18f, Path.Direction.CW);
        path.op(part, Path.Op.UNION);
        part.reset();
        part.addRect(new RectF(x + k * 0.18f, y + k * 0.42f, x + k * 0.76f, y + k * 0.661f), Path.Direction.CW);
        path.op(part, Path.Op.UNION);
        if (gap) {
            Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
            clear.setStyle(Paint.Style.STROKE);
            clear.setStrokeWidth(s * 0.07f);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            c.drawPath(path, clear);
        }
        p.setColor(WHITE);
        p.setStyle(Paint.Style.FILL);
        c.drawPath(path, p);
    }

    /** Slanted cyan drops of length len (a fraction of the side), from each (x, y) pair down to the left. */
    private static void drops(Canvas c, Paint p, float s, float len, float... xy) {
        p.setColor(DROP);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.04f);
        for (int i = 0; i < xy.length; i += 2) {
            float x = s * xy[i], y = s * xy[i + 1];
            c.drawLine(x, y, x - s * len * 0.55f, y + s * len, p);
        }
    }

    /** Small six-armed white flakes centred on each (x, y) pair. */
    private static void flakes(Canvas c, Paint p, float s, float... xy) {
        p.setColor(WHITE);
        p.setStyle(Paint.Style.STROKE);
        // Thicker than Apple's, so it stays over 1.5 px in the 48 px hourly icons, and a little
        // shorter, so the flake is no bigger than theirs.
        p.setStrokeWidth(s * 0.033f);
        float k = s * 0.048f;
        for (int i = 0; i < xy.length; i += 2) {
            float x = s * xy[i], y = s * xy[i + 1];
            for (int deg = 30; deg < 180; deg += 60) {
                float dx = (float) Math.cos(Math.toRadians(deg)) * k, dy = (float) Math.sin(Math.toRadians(deg)) * k;
                c.drawLine(x - dx, y - dy, x + dx, y + dy, p);
            }
        }
    }
}

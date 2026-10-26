package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Weather drawn on a glass surface, in Apple Weather's layout.
 * Compact: a pill in the top-left corner, level with the clock, with the icon, temperature and
 * condition. Full: the forecast opened from it - a centred header, the next hours (with the chance
 * of rain, sunrise and sunset) and a 5-day list whose range bars are cut from Apple's fixed
 * temperature gradient.
 */
final class WeatherView extends View {
    static final float COMPACT_H = 34, FULL_W = 380, FULL_H = 476;
    private static final int RAIN = 0xFF3CD3FE;  // Apple's systemCyan, as the drops

    final boolean full;
    final float u;
    Weather.Data data;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular, medium, semibold;
    private final RectF r = new RectF();
    // The 5-day bars' gradients, made once for each forecast rather than on every draw.
    private final Shader[] ranges = new Shader[5];
    private Weather.Data rangesFor;
    private float rangesW;

    WeatherView(Context c, boolean full, float unit) {
        super(c);
        this.full = full;
        this.u = unit;
        regular = Ui.font(c, "regular");
        medium = Ui.font(c, "medium");
        semibold = Ui.font(c, "semibold");
    }

    void setData(Weather.Data d) {
        if (d == data) return;
        Weather.Data was = data;
        data = d;
        if (full) {
            invalidate();
            return;
        }
        // The pill shows only the icon, temperature and condition: a new forecast that leaves them
        // as they were costs no layout pass over Home and no redraw.
        if (was == null || d == null || was.temp != d.temp || was.code != d.code || was.day != d.day) {
            requestLayout();
            invalidate();
        }
    }

    /** The compact pill is as wide as its contents, with even margins. */
    @Override
    protected void onMeasure(int ws, int hs) {
        if (full) {
            super.onMeasure(ws, hs);
            return;
        }
        float w = 0;
        if (data != null) {
            w = 13 * u + 22 * u + 7 * u + measure(data.temp + "\u00b0", 16, medium) + 8 * u
                    + measure(Weather.describe(data.code, data.day), 14, medium) + 15 * u;
        }
        setMeasuredDimension(Math.round(w), View.MeasureSpec.getSize(hs));
    }

    private float measure(String s, float size, Typeface tf) {
        p.setTypeface(tf);
        p.setTextSize(size * u);
        return p.measureText(s);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (data == null) return;
        if (full) drawFull(c);
        else drawCompact(c);
    }

    /**
     * A small glass pill the height of the clock's, on one line like it: icon, temperature and
     * condition. The place and the day's high and low are in the forecast it opens.
     */
    private void drawCompact(Canvas c) {
        Weather.Data d = data;
        float h = getHeight(), pad = 13 * u, is = 22 * u;
        icon(c, WeatherIcons.symbol(d.code, d.day), pad, (h - is) / 2f, is);
        float tx = pad + is + 7 * u, base = h / 2f + 5.6f * u;  // the clock's text sits the same way
        float tw = text(c, d.temp + "\u00b0", tx, base, 16, medium, 1f, Paint.Align.LEFT);
        text(c, Weather.describe(d.code, d.day), tx + tw + 8 * u, base, 14, medium, 0.75f, Paint.Align.LEFT);
    }

    private void drawFull(Canvas c) {
        Weather.Data d = data;
        float w = getWidth(), pad = 20 * u, x0 = pad, x1 = w - pad, mid = w / 2f;
        // Header
        text(c, d.place.isEmpty() ? "My Location" : d.place, mid, pad + 14 * u, 17, semibold, 1f, Paint.Align.CENTER);
        text(c, d.temp + "\u00b0", mid + 5 * u, pad + 66 * u, 54, regular, 1f, Paint.Align.CENTER);
        text(c, Weather.describe(d.code, d.day), mid, pad + 86 * u, 14.5f, medium, 0.85f, Paint.Align.CENTER);
        text(c, "H:" + d.max + "\u00b0  L:" + d.min + "\u00b0", mid, pad + 104 * u, 13.5f, medium, 0.85f, Paint.Align.CENTER);

        // Hourly
        float y = pad + 126 * u;
        text(c, "HOURLY FORECAST", x0, y, 11.5f, semibold, 0.55f, Paint.Align.LEFT);
        text(c, "Humidity " + d.humidity + "%  \u00b7  Wind " + d.wind + " " + d.windUnit, x1, y, 11.5f, medium, 0.55f, Paint.Align.RIGHT);
        hairline(c, x0, x1, y + 8 * u);
        int hn = Math.min(6, d.hours.size());
        float hw = (x1 - x0) / Math.max(1, hn);
        for (int i = 0; i < hn; i++) {
            Weather.Hour h = d.hours.get(i);
            float hx = x0 + hw * (i + 0.5f);
            text(c, h.label, hx, y + 27 * u, 12, semibold, i == 0 ? 1f : 0.8f, Paint.Align.CENTER);
            if (h.type != Weather.Hour.HOUR) {  // sunrise or sunset at its exact time, the word where the temperature goes
                icon(c, hourSymbol(h), hx - 12 * u, y + 35 * u, 24 * u);
                text(c, h.type == Weather.Hour.SUNRISE ? "Sunrise" : "Sunset", hx, y + 79 * u, 12.5f, semibold, 1f, Paint.Align.CENTER);
                continue;
            }
            // The chance of rain under the icon, from 10%, as Apple Weather shows it; the icon keeps its size.
            int rain = Weather.chance(h.rain);
            icon(c, hourSymbol(h), hx - 12 * u, y + (rain > 0 ? 29 : 35) * u, 24 * u);
            if (rain > 0) text(c, rain + "%", hx, y + 65 * u, 10.5f, semibold, 1f, Paint.Align.CENTER, RAIN);
            text(c, h.temp + "\u00b0", hx, y + 79 * u, 14, semibold, 1f, Paint.Align.CENTER);
        }

        // 5-day list with range bars
        y += 102 * u;
        text(c, "5-DAY FORECAST", x0, y, 11.5f, semibold, 0.55f, Paint.Align.LEFT);
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Weather.Day day : d.days) {
            lo = Math.min(lo, day.min);
            hi = Math.max(hi, day.max);
        }
        lo = Math.min(lo, d.temp);
        hi = Math.max(hi, d.temp);
        float span = Math.max(1, hi - lo);
        float row = 36 * u, by0 = x0 + 150 * u, by1 = x1 - 40 * u;
        boolean remake = rangesFor != d || rangesW != w;
        rangesFor = d;
        rangesW = w;
        for (int i = 0; i < Math.min(5, d.days.size()); i++) {
            Weather.Day day = d.days.get(i);
            float ry = y + 8 * u + row * i;
            hairline(c, x0, x1, ry);
            float base = ry + row / 2f + 5 * u;
            text(c, day.label, x0, base, 15, semibold, 1f, Paint.Align.LEFT);
            float ix = x0 + 64 * u;
            int rain = Weather.chance(day.rain);
            icon(c, WeatherIcons.symbol(day.code, true), ix, ry + (rain > 0 ? 2 : 7) * u, 22 * u);
            if (rain > 0) text(c, rain + "%", ix + 11 * u, ry + row - 2.5f * u, 10.5f, semibold, 1f, Paint.Align.CENTER, RAIN);
            text(c, day.min + "\u00b0", by0 - 10 * u, base, 15, semibold, 0.55f, Paint.Align.RIGHT);
            text(c, day.max + "\u00b0", x1, base, 15, semibold, 1f, Paint.Align.RIGHT);
            // Track, then this day's range coloured by temperature.
            float cy = ry + row / 2f, th = 5 * u;
            bar.setShader(null);
            bar.setColor(0x33FFFFFF);
            r.set(by0, cy - th / 2, by1, cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, bar);
            float a = by0 + (by1 - by0) * (day.min - lo) / span, b = by0 + (by1 - by0) * (day.max - lo) / span;
            bar.setColor(0xFFFFFFFF);  // opaque: the track's 20% alpha must not carry over to the range
            if (remake) ranges[i] = rangeShader(day.min, day.max, d.fahrenheit, a, Math.max(b, a + 1));
            bar.setShader(ranges[i]);
            r.set(a, cy - th / 2, Math.max(b, a + th), cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, bar);
            if (i == 0) {  // current temperature on today's bar
                float nx = by0 + (by1 - by0) * (d.temp - lo) / span;
                bar.setShader(null);
                bar.setColor(0xFF1C1C1E);
                c.drawCircle(nx, cy, th * 0.95f, bar);
                bar.setColor(0xFFFFFFFF);
                c.drawCircle(nx, cy, th * 0.6f, bar);
            }
        }
        // Source and time of the last update.
        text(c, Weather.sourceName(d.source) + "  \u00b7  Updated " + timeOf(d.fetchedAt), mid, getHeight() - 14 * u, 11.5f, medium, 0.5f, Paint.Align.CENTER);
    }

    // The footer's time format, kept from one opening of the forecast to the next: making one looks
    // up the language's pattern. Made again when the time zone, language or 12/24-hour clock changes.
    private static java.text.DateFormat timeFmt;
    private static String timeFmtFor;

    private String timeOf(long t) {
        Context ctx = getContext();
        String key = java.util.TimeZone.getDefault().getID() + " " + java.util.Locale.getDefault()
                + " " + android.text.format.DateFormat.is24HourFormat(ctx);
        if (!key.equals(timeFmtFor)) {
            timeFmt = android.text.format.DateFormat.getTimeFormat(ctx);
            timeFmtFor = key;
        }
        return timeFmt.format(new java.util.Date(t));
    }

    /**
     * Draws the forecast's icons ahead, off the main thread, at the sizes drawFull uses: the first
     * opening of the forecast otherwise drew a dozen of them in its first frame and stalled.
     */
    static void prepare(Weather.Data d, float u) {
        if (d == null) return;
        for (int i = 0; i < Math.min(6, d.hours.size()); i++) {
            WeatherIcons.get(hourSymbol(d.hours.get(i)), Math.round(24 * u * 2));
        }
        for (int i = 0; i < Math.min(5, d.days.size()); i++) {
            WeatherIcons.get(WeatherIcons.symbol(d.days.get(i).code, true), Math.round(22 * u * 2));
        }
    }

    /** An hourly cell's symbol: the weather that hour, or sunrise or sunset. */
    private static String hourSymbol(Weather.Hour h) {
        return h.type == Weather.Hour.SUNRISE ? WeatherIcons.SUNRISE : h.type == Weather.Hour.SUNSET ? WeatherIcons.SUNSET
                : WeatherIcons.symbol(h.code, h.day);
    }

    // Apple's temperature gradient (WeatherCore's default scale) from -120 to 120 \u00b0F. Every bar is
    // the slice between its day's low and high, so a temperature always has the same colour.
    private static final float[] STOP_F = {-120, -60, 0, 30, 50, 70, 80, 100, 120};
    private static final int[] STOP_C = {0xFF30004D, 0xFF5856D6, 0xFF007AFF, 0xFF5AC8FA, 0xFF66D4CF,
            0xFFFFCC00, 0xFFFF9500, 0xFFFF3B30, 0xFF770700};

    /** One day's bar from x a to b: the gradient from lo to hi, with each of Apple's stops in between. */
    private static Shader rangeShader(int lo, int hi, boolean fahrenheit, float a, float b) {
        float lf = fahrenheit ? lo : lo * 9f / 5f + 32, hf = fahrenheit ? hi : hi * 9f / 5f + 32;
        int n = 0;
        for (float s : STOP_F) if (s > lf && s < hf) n++;
        int[] colors = new int[n + 2];
        float[] at = new float[n + 2];
        colors[0] = colorAt(lf);
        for (int i = 0, k = 1; i < STOP_F.length; i++) {
            if (STOP_F[i] > lf && STOP_F[i] < hf) {
                colors[k] = STOP_C[i];
                at[k++] = (STOP_F[i] - lf) / (hf - lf);
            }
        }
        colors[n + 1] = colorAt(hf);  // the same colour twice for a day with no range
        at[n + 1] = 1;
        return new LinearGradient(a, 0, b, 0, colors, at, Shader.TileMode.CLAMP);
    }

    /** The gradient's colour at a temperature in \u00b0F, blended in sRGB between the stops either side. */
    private static int colorAt(float tf) {
        if (tf <= STOP_F[0]) return STOP_C[0];
        for (int i = 1; i < STOP_F.length; i++) {
            if (tf <= STOP_F[i]) return mix(STOP_C[i - 1], STOP_C[i], (tf - STOP_F[i - 1]) / (STOP_F[i] - STOP_F[i - 1]));
        }
        return STOP_C[STOP_C.length - 1];
    }

    private static int mix(int a, int b, float k) {
        int r = (int) (((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
        int g = (int) (((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
        int bl = (int) ((a & 255) * (1 - k) + (b & 255) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void icon(Canvas c, String symbol, float x, float y, float size) {
        // Drawn at twice the size and filtered down, so it stays crisp when the pill grows on focus.
        // prepare() must ask for the same sizes.
        Bitmap b = WeatherIcons.get(symbol, Math.round(size * 2));
        iconDst.set(x, y, x + size, y + size);
        c.drawBitmap(b, null, iconDst, iconPaint);
    }

    private final RectF iconDst = new RectF();
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private void hairline(Canvas c, float x0, float x1, float y) {
        p.setShader(null);
        p.setColor(0x33FFFFFF);
        p.setStrokeWidth(Math.max(1, 0.8f * u));
        c.drawLine(x0, y, x1, y, p);
    }

    private float text(Canvas c, String s, float x, float y, float size, Typeface tf, float alpha, Paint.Align al) {
        return text(c, s, x, y, size, tf, alpha, al, 0xFFFFFFFF);
    }

    private float text(Canvas c, String s, float x, float y, float size, Typeface tf, float alpha, Paint.Align al, int color) {
        p.setTypeface(tf);
        p.setTextSize(size * u);
        p.setTextAlign(al);
        p.setColor(color);
        p.setAlpha(Math.round(255 * alpha));
        c.drawText(s, x, y, p);
        return p.measureText(s);
    }
}

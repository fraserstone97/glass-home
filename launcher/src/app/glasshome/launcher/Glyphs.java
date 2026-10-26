package app.glasshome.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** SF Symbols\u2013style glyphs for Control Centre, Settings and Home's drawn cards, drawn in code (no image assets). */
final class Glyphs {
    private Glyphs() { }

    static Bitmap get(String name, int s, int color) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.085f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float m = s / 2f;
        RectF r = new RectF();
        Path path = new Path();
        switch (name) {
            case "power":
                r.set(s * 0.18f, s * 0.2f, s * 0.82f, s * 0.84f);
                c.drawArc(r, -60, 300, false, p);
                c.drawLine(m, s * 0.1f, m, s * 0.48f, p);
                break;
            case "wifi": {  // two heavy arcs over a rounded wedge, like SF Symbols' wifi
                float cy = s * 0.8f;
                p.setStrokeWidth(s * 0.1f);
                for (float rad : new float[]{s * 0.5f, s * 0.306f}) {
                    r.set(m - rad, cy - rad, m + rad, cy + rad);
                    c.drawArc(r, -135, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.03f));
                r.set(m - s * 0.16f, cy - s * 0.16f, m + s * 0.16f, cy + s * 0.16f);
                path.moveTo(m, cy);
                path.arcTo(r, -135, 90);
                path.close();
                c.drawPath(path, p);
                p.setPathEffect(null);
                break;
            }
            case "bluetooth":  // the rune
                p.setStrokeWidth(s * 0.075f);
                path.moveTo(s * 0.3f, s * 0.32f);
                path.lineTo(s * 0.68f, s * 0.66f);
                path.lineTo(m, s * 0.84f);
                path.lineTo(m, s * 0.16f);
                path.lineTo(s * 0.68f, s * 0.34f);
                path.lineTo(s * 0.3f, s * 0.68f);
                c.drawPath(path, p);
                break;
            case "picture":  // a screen with a small landscape
                p.setStrokeWidth(s * 0.075f);
                r.set(s * 0.14f, s * 0.22f, s * 0.86f, s * 0.74f);
                c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
                path.moveTo(s * 0.24f, s * 0.64f);
                path.lineTo(s * 0.42f, s * 0.44f);
                path.lineTo(s * 0.56f, s * 0.58f);
                path.lineTo(s * 0.64f, s * 0.5f);
                path.lineTo(s * 0.76f, s * 0.64f);
                c.drawPath(path, p);
                c.drawLine(s * 0.36f, s * 0.84f, s * 0.64f, s * 0.84f, p);
                break;
            case "sparkles":  // screen saver: SF's big star at the lower right, two small ones above it
                p.setStyle(Paint.Style.FILL);
                for (float[] st : new float[][]{{0.558f, 0.64f, 0.306f}, {0.282f, 0.363f, 0.15f}, {0.485f, 0.145f, 0.09f}}) {
                    float cx = s * st[0], cy = s * st[1], k = s * st[2], w = k * 0.25f;
                    path.reset();
                    path.moveTo(cx, cy - k);
                    path.quadTo(cx + w, cy - w, cx + k, cy);
                    path.quadTo(cx + w, cy + w, cx, cy + k);
                    path.quadTo(cx - w, cy + w, cx - k, cy);
                    path.quadTo(cx - w, cy - w, cx, cy - k);
                    c.drawPath(path, p);
                }
                break;
            case "mediaplayer": {  // a screen with a play button, like SF Symbols' play.rectangle
                p.setStrokeWidth(s * 0.07f);
                r.set(s * 0.1f, s * 0.2f, s * 0.9f, s * 0.8f);
                c.drawRoundRect(r, s * 0.11f, s * 0.11f, p);
                p.setStyle(Paint.Style.FILL);
                p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.04f));
                path.moveTo(s * 0.42f, s * 0.36f);
                path.lineTo(s * 0.66f, m);
                path.lineTo(s * 0.42f, s * 0.64f);
                path.close();
                c.drawPath(path, p);
                p.setPathEffect(null);
                break;
            }
            case "vlc": {  // VLC's cone, as on its Apple TV icon: tip, a band, and the base, in one colour
                p.setStyle(Paint.Style.FILL);
                c.save();
                // Drawn on a 1024 grid (the icon's own), its cone centred in the square.
                float k = s / 680f;
                c.scale(k, k);
                c.translate(-510 + 340, -494 + 340);
                Path tip = new Path();
                tip.moveTo(451, 260);
                tip.lineTo(467, 212);
                tip.cubicTo(478, 180, 495, 170, 512, 170);
                tip.cubicTo(529, 170, 546, 180, 557, 212);
                tip.lineTo(572, 260);
                tip.quadTo(512, 294, 451, 260);
                tip.close();
                c.drawPath(tip, p);
                Path band = new Path();
                band.moveTo(411, 378);
                band.quadTo(512, 450, 612, 378);
                band.lineTo(656, 511);
                band.quadTo(512, 623, 367, 511);
                band.close();
                c.drawPath(band, p);
                Path base = new Path();
                base.moveTo(297, 578);
                base.lineTo(343, 578);
                base.lineTo(330, 618);
                base.quadTo(325, 635, 335, 645);
                base.quadTo(512, 769, 689, 645);
                base.quadTo(699, 635, 694, 618);
                base.lineTo(680, 578);
                base.lineTo(726, 578);
                base.quadTo(752, 578, 762, 605);
                base.lineTo(808, 772);
                base.quadTo(816, 818, 780, 818);
                base.lineTo(241, 818);
                base.quadTo(205, 818, 214, 772);
                base.lineTo(262, 605);
                base.quadTo(270, 578, 297, 578);
                base.close();
                c.drawPath(base, p);
                c.restore();
                break;
            }
            case "photos": {  // two stacked photos, the front one a landscape with the sun
                p.setStrokeWidth(s * 0.06f);
                r.set(s * 0.24f, s * 0.14f, s * 0.92f, s * 0.64f);
                c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
                p.setStyle(Paint.Style.FILL);
                Path front = new Path();
                front.addRoundRect(new RectF(s * 0.08f, s * 0.3f, s * 0.76f, s * 0.86f), s * 0.08f, s * 0.08f, Path.Direction.CW);
                Path view = new Path();
                view.addCircle(s * 0.56f, s * 0.46f, s * 0.06f, Path.Direction.CW);
                view.moveTo(s * 0.14f, s * 0.8f);
                view.lineTo(s * 0.34f, s * 0.54f);
                view.lineTo(s * 0.48f, s * 0.7f);
                view.lineTo(s * 0.58f, s * 0.6f);
                view.lineTo(s * 0.72f, s * 0.8f);
                view.close();
                // Cut the back photo where the front one sits, then draw the front as a frame.
                p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
                c.drawPath(front, p);
                p.setXfermode(null);
                p.setStyle(Paint.Style.STROKE);
                c.drawPath(front, p);
                p.setStyle(Paint.Style.FILL);
                c.drawPath(view, p);
                break;
            }
            case "audio":
                for (int i = 0; i < 2; i++) {
                    float rad = s * (0.2f + 0.16f * i);
                    r.set(m - rad, s * 0.5f - rad, m + rad, s * 0.5f + rad);
                    c.drawArc(r, 140, 260, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                path.moveTo(m, s * 0.52f);
                path.lineTo(m + s * 0.2f, s * 0.86f);
                path.lineTo(m - s * 0.2f, s * 0.86f);
                path.close();
                c.drawPath(path, p);
                break;
            case "timer":  // a ring open at the upper left, a stem down from its top, the hand pointing up-left
                r.set(m - s * 0.34f, m - s * 0.34f, m + s * 0.34f, m + s * 0.34f);
                c.drawArc(r, -95, 323, false, p);
                c.drawLine(m, s * 0.165f, m, s * 0.3f, p);  // its round end flush with the ring's top
                c.drawLine(m, m, m - s * 0.17f, m - s * 0.165f, p);
                break;
            case "gamepad":
                r.set(s * 0.08f, s * 0.3f, s * 0.92f, s * 0.74f);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(r, s * 0.2f, s * 0.2f, p);
                p.setColor(0xFF000000);
                p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.06f);
                c.drawLine(s * 0.24f, m + s * 0.02f, s * 0.38f, m + s * 0.02f, p);
                c.drawLine(s * 0.31f, m - s * 0.05f, s * 0.31f, m + s * 0.09f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(s * 0.66f, m - s * 0.03f, s * 0.035f, p);
                c.drawCircle(s * 0.74f, m + s * 0.06f, s * 0.035f, p);
                p.setXfermode(null);
                break;
            case "gear": {  // gearshape.fill: eight broad, tapering teeth on a disc and a plain hole
                p.setStyle(Paint.Style.FILL);
                path.addCircle(m, m, s * 0.27f, Path.Direction.CW);
                Path part = new Path();
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(-90 + 45 * i);
                    float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
                    float r0 = s * 0.238f, w0 = s * 0.094f, r1 = s * 0.36f, w1 = s * 0.058f;
                    part.reset();
                    part.moveTo(m + dx * r0 - dy * w0, m + dy * r0 + dx * w0);
                    part.lineTo(m + dx * r1 - dy * w1, m + dy * r1 + dx * w1);
                    part.lineTo(m + dx * r1 + dy * w1, m + dy * r1 - dx * w1);
                    part.lineTo(m + dx * r0 + dy * w0, m + dy * r0 - dx * w0);
                    part.close();
                    path.op(part, Path.Op.UNION);
                }
                part.reset();
                part.addCircle(m, m, s * 0.12f, Path.Direction.CW);
                path.op(part, Path.Op.DIFFERENCE);
                p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.02f));
                c.drawPath(path, p);
                p.setPathEffect(null);
                break;
            }
            case "magnifier": {  // magnifyingglass, as SF draws it: one even, weighty stroke, a big ring and a short handle (Search)
                float cx = s * 0.41f, rad = s * 0.3f, e = rad * 0.7071f;
                p.setStrokeWidth(s * 0.09f);
                c.drawCircle(cx, cx, rad, p);
                c.drawLine(cx + e, cx + e, s * 0.86f, s * 0.86f, p);
                break;
            }
            case "input":  // a screen with an arrow coming in from the left (Inputs)
                r.set(s * 0.3f, s * 0.2f, s * 0.88f, s * 0.8f);
                path.moveTo(s * 0.3f, s * 0.36f);
                path.lineTo(s * 0.3f, s * 0.28f);
                path.quadTo(s * 0.3f, s * 0.2f, s * 0.38f, s * 0.2f);
                path.lineTo(s * 0.8f, s * 0.2f);
                path.quadTo(s * 0.88f, s * 0.2f, s * 0.88f, s * 0.28f);
                path.lineTo(s * 0.88f, s * 0.72f);
                path.quadTo(s * 0.88f, s * 0.8f, s * 0.8f, s * 0.8f);
                path.lineTo(s * 0.38f, s * 0.8f);
                path.quadTo(s * 0.3f, s * 0.8f, s * 0.3f, s * 0.72f);
                path.lineTo(s * 0.3f, s * 0.64f);
                c.drawPath(path, p);
                c.drawLine(s * 0.1f, m, s * 0.6f, m, p);
                c.drawLine(s * 0.47f, s * 0.37f, s * 0.6f, m, p);
                c.drawLine(s * 0.47f, s * 0.63f, s * 0.6f, m, p);
                break;
            case "antenna":  // tuner: a mast with signal arcs
                c.drawLine(m, s * 0.42f, m, s * 0.86f, p);
                c.drawLine(s * 0.36f, s * 0.86f, s * 0.64f, s * 0.86f, p);
                for (int i = 0; i < 2; i++) {
                    float rad = s * (0.14f + 0.14f * i);
                    r.set(m - rad, s * 0.36f - rad, m + rad, s * 0.36f + rad);
                    c.drawArc(r, -150, 120, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(m, s * 0.36f, s * 0.06f, p);
                break;
            case "play": {  // play.fill: a solid triangle with softened corners, nudged right to look centred
                p.setStyle(Paint.Style.FILL);
                p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.06f));
                path.moveTo(s * 0.3f, s * 0.16f);
                path.lineTo(s * 0.84f, m);
                path.lineTo(s * 0.3f, s * 0.84f);
                path.close();
                c.drawPath(path, p);
                p.setPathEffect(null);
                break;
            }
            case "pause":  // pause.fill: two rounded bars
                p.setStyle(Paint.Style.FILL);
                r.set(s * 0.24f, s * 0.16f, s * 0.42f, s * 0.84f);
                c.drawRoundRect(r, s * 0.05f, s * 0.05f, p);
                r.offset(s * 0.34f, 0);
                c.drawRoundRect(r, s * 0.05f, s * 0.05f, p);
                break;
            case "playpause": {  // playpause.fill: a smaller play triangle and the two pause bars
                p.setStyle(Paint.Style.FILL);
                p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.04f));
                path.moveTo(s * 0.1f, s * 0.26f);
                path.lineTo(s * 0.5f, m);
                path.lineTo(s * 0.1f, s * 0.74f);
                path.close();
                c.drawPath(path, p);
                p.setPathEffect(null);
                r.set(s * 0.58f, s * 0.26f, s * 0.69f, s * 0.74f);
                c.drawRoundRect(r, s * 0.035f, s * 0.035f, p);
                r.offset(s * 0.2f, 0);
                c.drawRoundRect(r, s * 0.035f, s * 0.035f, p);
                break;
            }
            case "house": {  // Home Screen, like SF Symbols' house.fill: a roof line over the body, a chimney, a square door
                p.setStrokeWidth(s * 0.081f);
                path.moveTo(s * 0.086f, s * 0.466f);
                path.lineTo(m, s * 0.151f);
                path.lineTo(s * 0.914f, s * 0.466f);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                c.drawRect(s * 0.757f, s * 0.19f, s * 0.815f, s * 0.376f, p);
                Path h = new Path();
                h.moveTo(s * 0.194f, s * 0.489f);
                h.lineTo(m, s * 0.241f);
                h.lineTo(s * 0.806f, s * 0.489f);
                h.lineTo(s * 0.806f, s * 0.891f);
                h.lineTo(s * 0.194f, s * 0.891f);
                h.close();
                Path door = new Path();
                door.addRect(s * 0.41f, s * 0.589f, s * 0.59f, s * 0.832f, Path.Direction.CW);
                h.op(door, Path.Op.DIFFERENCE);
                p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.03f));
                c.drawPath(h, p);
                p.setPathEffect(null);
                break;
            }
            case "shield":  // Permissions
                path.moveTo(m, s * 0.12f);
                path.lineTo(s * 0.8f, s * 0.26f);
                path.cubicTo(s * 0.8f, s * 0.58f, s * 0.68f, s * 0.78f, m, s * 0.9f);
                path.cubicTo(s * 0.32f, s * 0.78f, s * 0.2f, s * 0.58f, s * 0.2f, s * 0.26f);
                path.close();
                c.drawPath(path, p);
                c.drawLine(s * 0.38f, s * 0.5f, s * 0.47f, s * 0.6f, p);
                c.drawLine(s * 0.47f, s * 0.6f, s * 0.64f, s * 0.4f, p);
                break;
            case "shelf":  // Top Shelf: a wide card above a row of small ones
                r.set(s * 0.12f, s * 0.2f, s * 0.88f, s * 0.58f);
                c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) {
                    r.set(s * (0.12f + i * 0.27f), s * 0.68f, s * (0.34f + i * 0.27f), s * 0.82f);
                    c.drawRoundRect(r, s * 0.03f, s * 0.03f, p);
                }
                break;
            case "hdmi": {  // HDMI port: wide top, tapered lower corners, contact strip
                path.moveTo(s * 0.12f, s * 0.34f);
                path.lineTo(s * 0.88f, s * 0.34f);
                path.lineTo(s * 0.88f, s * 0.52f);
                path.lineTo(s * 0.76f, s * 0.66f);
                path.lineTo(s * 0.24f, s * 0.66f);
                path.lineTo(s * 0.12f, s * 0.52f);
                path.close();
                c.drawPath(path, p);
                p.setStrokeWidth(s * 0.06f);
                c.drawLine(s * 0.3f, s * 0.47f, s * 0.7f, s * 0.47f, p);
                break;
            }
            case "av": {  // composite: three RCA jacks
                for (int i = 0; i < 3; i++) {
                    float x = s * (0.22f + i * 0.28f);
                    c.drawCircle(x, m, s * 0.11f, p);
                    Paint dot = new Paint(p);
                    dot.setStyle(Paint.Style.FILL);
                    c.drawCircle(x, m, s * 0.035f, dot);
                }
                break;
            }
            case "component": {  // component video: three jacks in a triangle
                float[][] at = {{0.5f, 0.3f}, {0.3f, 0.66f}, {0.7f, 0.66f}};
                for (float[] q : at) {
                    c.drawCircle(s * q[0], s * q[1], s * 0.12f, p);
                    Paint dot = new Paint(p);
                    dot.setStyle(Paint.Style.FILL);
                    c.drawCircle(s * q[0], s * q[1], s * 0.035f, dot);
                }
                break;
            }
            case "svideo": {  // S-Video: round mini-DIN with four pins
                c.drawCircle(m, m, s * 0.32f, p);
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                float[][] pins = {{0.38f, 0.42f}, {0.62f, 0.42f}, {0.36f, 0.6f}, {0.64f, 0.6f}};
                for (float[] q : pins) c.drawCircle(s * q[0], s * q[1], s * 0.04f, dot);
                r.set(s * 0.45f, s * 0.68f, s * 0.55f, s * 0.76f);
                c.drawRect(r, dot);
                break;
            }
            case "vga": {  // VGA / DVI: D-sub with rows of pins
                path.moveTo(s * 0.1f, s * 0.3f);
                path.lineTo(s * 0.9f, s * 0.3f);
                path.lineTo(s * 0.8f, s * 0.7f);
                path.lineTo(s * 0.2f, s * 0.7f);
                path.close();
                c.drawPath(path, p);
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                for (int row = 0; row < 2; row++) {
                    for (int i = 0; i < 5 - row; i++) {
                        c.drawCircle(s * (0.3f + row * 0.05f + i * 0.1f), s * (0.44f + row * 0.13f), s * 0.025f, dot);
                    }
                }
                break;
            }
            case "displayport": {  // DisplayPort: rectangle with one cut corner
                path.moveTo(s * 0.12f, s * 0.32f);
                path.lineTo(s * 0.88f, s * 0.32f);
                path.lineTo(s * 0.88f, s * 0.68f);
                path.lineTo(s * 0.26f, s * 0.68f);
                path.lineTo(s * 0.12f, s * 0.54f);
                path.close();
                c.drawPath(path, p);
                p.setStrokeWidth(s * 0.06f);
                c.drawLine(s * 0.32f, s * 0.5f, s * 0.72f, s * 0.5f, p);
                break;
            }
            case "scart": {  // SCART: wide connector with one slanted corner
                path.moveTo(s * 0.08f, s * 0.34f);
                path.lineTo(s * 0.92f, s * 0.34f);
                path.lineTo(s * 0.92f, s * 0.66f);
                path.lineTo(s * 0.2f, s * 0.66f);
                path.lineTo(s * 0.08f, s * 0.5f);
                path.close();
                c.drawPath(path, p);
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 6; i++) c.drawCircle(s * (0.28f + i * 0.11f), m, s * 0.025f, dot);
                break;
            }
            case "usb": {  // USB trident
                c.drawLine(m, s * 0.86f, m, s * 0.18f, p);
                Paint fill = new Paint(p);
                fill.setStyle(Paint.Style.FILL);
                path.moveTo(m, s * 0.1f);
                path.lineTo(m - s * 0.08f, s * 0.22f);
                path.lineTo(m + s * 0.08f, s * 0.22f);
                path.close();
                c.drawPath(path, fill);
                c.drawCircle(m, s * 0.86f, s * 0.07f, fill);
                c.drawLine(m, s * 0.64f, s * 0.28f, s * 0.48f, p);
                c.drawLine(s * 0.28f, s * 0.48f, s * 0.28f, s * 0.38f, p);
                c.drawCircle(s * 0.28f, s * 0.33f, s * 0.055f, fill);
                c.drawLine(m, s * 0.54f, s * 0.72f, s * 0.4f, p);
                c.drawLine(s * 0.72f, s * 0.4f, s * 0.72f, s * 0.32f, p);
                r.set(s * 0.66f, s * 0.22f, s * 0.78f, s * 0.32f);
                c.drawRect(r, fill);
                break;
            }
            default:
                break;
        }
        return b;
    }
}

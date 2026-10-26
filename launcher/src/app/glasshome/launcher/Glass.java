package app.glasshome.launcher;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;

/**
 * The one Liquid Glass material for controls everywhere (Control Centre, Search, title pages,
 * Settings, menus): a translucent body, a soft highlight across the top, and a thin rim lit from
 * the top left, as tvOS 26's. Focus fades an opaque white plate in over it (setFocus, 0..1), so
 * every focused control looks and moves the same way.
 * Cheap to draw: three gradients and a rounded rectangle, no blur, no bitmaps.
 */
final class Glass extends Drawable {

    /**
     * Body strength: REGULAR for most controls, DARK for controls sitting on a dark glass panel
     * (tvOS Control Centre). DARK is a light fill, as tvOS's controls there read a little lighter
     * than the panel, never a darker hole in it.
     */
    static final int REGULAR = 0, DARK = 1;

    final float radius, rimWidth;
    final int weight;
    final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF box = new RectF(), shape = new RectF();
    float focus;
    /**
     * How far (px) the glass sits inside its bounds at rest, on each side: it grows out to them as
     * focus comes in. For rows whose plate grows while the words stay put (tvOS menus); 0 for most.
     */
    float grow;
    /** The focus fade running now: a new one cancels it, so two never fight over the plate. */
    ValueAnimator anim;

    Glass(float radius, float rimWidth, int weight) {
        this.radius = radius;
        this.rimWidth = Math.max(1f, rimWidth);
        this.weight = weight;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(this.rimWidth);
        plate.setColor(0xFFFFFFFF);  // opaque, as tvOS's: its centre reads pure white
    }

    /** 0 = resting glass, 1 = focused (white); Glass.focus animates it. */
    public void setFocus(float f) {
        focus = f;
        if (tint != null) tint.at(f);
        invalidateSelf();
    }

    /** What sits on the glass and changes colour with its focus (text, glyphs): told every frame. */
    interface Tint {
        void at(float focus);
    }

    Tint tint;

    public float getFocus() {
        return focus;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        box.set(b);
        float h = box.height();
        boolean dark = weight == DARK;
        int a0 = dark ? 0x1F : 0x2C;
        int a1 = dark ? 0x12 : 0x18;
        body.setShader(new LinearGradient(0, box.top, 0, box.bottom, (a0 << 24) | 0xFFFFFF, (a1 << 24) | 0xFFFFFF,
                Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, box.top, 0, box.top + h * 0.5f, dark ? 0x14FFFFFF : 0x1AFFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        rim.setShader(rimShader(box, Math.min(radius, Math.min(box.width(), h) / 2f), dark ? 0.5f : 1f));
    }

    /**
     * tvOS 26's rim light, as measured on its Control Centre: brightest on the top-left corner, then
     * along the top edge; the sides about half that; the bottom faint, with a second, smaller peak on
     * the bottom-right corner. It goes round the shape by angle from the centre, with each corner's
     * stop at that corner's real angle, so a wide capsule is lit like a square button. A straight
     * gradient can't: on a wide shape it lights the top and bottom edges alike. `strength` scales it.
     */
    static Shader rimShader(RectF box, float r, float strength) {
        float cx = box.centerX(), cy = box.centerY();
        float k = r * (1f - (float) Math.sqrt(0.5));  // a corner arc's middle, in from the box's corner
        // Clockwise from the right edge (0): bottom 0.25, left 0.5, top 0.75.
        float[] at = {0f, turn(box.right - k - cx, box.bottom - k - cy), 0.25f, turn(box.left + k - cx, box.bottom - k - cy),
                0.5f, turn(box.left + k - cx, box.top + k - cy), 0.75f, turn(box.right - k - cx, box.top + k - cy), 1f};
        int[] light = {0x4A, 0x5C, 0x20, 0x14, 0x4A, 0x80, 0x70, 0x52, 0x4A};
        int[] c = new int[light.length];
        for (int i = 0; i < c.length; i++) c[i] = (Math.min(0xFF, Math.round(light[i] * strength)) << 24) | 0xFFFFFF;
        return new SweepGradient(cx, cy, c, at);
    }

    /** The angle of (x, y) from the centre as a fraction of a turn, clockwise from the right. */
    static float turn(float x, float y) {
        double t = Math.atan2(y, x) / (2 * Math.PI);
        return (float) (t < 0 ? t + 1 : t);
    }

    @Override
    public void draw(Canvas c) {
        RectF b = box;
        float in = grow * (1f - focus);
        if (in > 0f) {
            shape.set(box);
            shape.inset(in, in);
            b = shape;
        }
        float r = Math.min(radius, Math.min(b.width(), b.height()) / 2f);
        c.drawRoundRect(b, r, r, body);
        c.save();
        c.clipRect(b.left, b.top, b.right, b.top + b.height() * 0.5f);
        c.drawRoundRect(b, r, r, sheen);
        c.restore();
        float w = rimWidth / 2f;
        c.drawRoundRect(b.left + w, b.top + w, b.right - w, b.bottom - w, r - w, r - w, rim);
        if (focus > 0f) {
            plate.setAlpha(Math.round(focus * 0xFF));
            c.drawRoundRect(b, r, r, plate);
        }
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** Fades the white focus plate in or out with the shared timing: tvOS's, about 170 ms each way. */
    static void focus(Glass g, boolean has) {
        focus(g, has, 170, 180, MainActivity.EASE, MainActivity.EASE);
    }

    /** As focus, on a timing of its own (tvOS menu rows: 100 ms in, 200 ms out). */
    static void focus(Glass g, boolean has, long inMs, long outMs, TimeInterpolator in, TimeInterpolator out) {
        if (g.anim != null) g.anim.cancel();
        ValueAnimator a = ValueAnimator.ofFloat(g.getFocus(), has ? 1f : 0f);
        a.addUpdateListener(v -> g.setFocus((float) v.getAnimatedValue()));
        a.setDuration(has ? inMs : outMs);
        a.setInterpolator(has ? in : out);
        g.anim = a;
        a.start();
    }

    /**
     * The spring for small controls: the tiles' quick rise and small overshoot, but settled in
     * about half the time. A long, slow settle on a wide pill read as jitter.
     */
    static final Spring SNAP = new Spring(0.8f, 14f);

    /**
     * tvOS Control Centre's focus pop: past the target by half as far again a third of the way in,
     * then settled (a 1.08 lift peaks at 1.12 about 110 ms into 330, as measured on Apple's).
     */
    static final TimeInterpolator POP;

    static {
        Path p = new Path();
        p.moveTo(0f, 0f);
        p.cubicTo(0.1f, 0.75f, 0.2f, 1.5f, 0.33f, 1.5f);
        p.cubicTo(0.48f, 1.5f, 0.6f, 1f, 1f, 1f);
        POP = new PathInterpolator(p);
    }

    /** The last direction the remote moved focus: -1/0/+1 across and down (set by navSound). */
    static int dirX, dirY;

    /**
     * The shared lift, with the app tiles' jiggle: the control arrives with a small push from
     * the direction the remote moved, then the spring settles it as it lifts. Eases back down.
     */
    static void lift(View v, boolean has, float scale, float z) {
        lift(v, has, scale, z, 1f, true, 460, SNAP);
    }

    /**
     * As lift, for a view shown at `rest` scale when not focused. Without `asImage`, it's drawn
     * afresh at each size instead of as a stretched picture: for a little text (the weather pill)
     * that keeps every frame sharp, where a picture softens for a moment and then snaps.
     */
    static void lift(View v, boolean has, float scale, float z, float rest, boolean asImage) {
        lift(v, has, scale, z, rest, asImage, 460, SNAP);
    }

    /**
     * As lift, rising over `ms` on `curve`. A POP arrives without the push: its overshoot is in
     * size alone, where a push would be thrown half as far again past its place.
     */
    static void lift(View v, boolean has, float scale, float z, float rest, boolean asImage, long ms, TimeInterpolator curve) {
        v.animate().cancel();
        v.animate().setStartDelay(0);  // a delay set for an earlier fade would hold the lift back
        ViewPropertyAnimator a;
        if (has) {
            float push = curve == POP ? 0f : 4f * v.getResources().getDisplayMetrics().density;
            v.setTranslationX(-dirX * push);
            v.setTranslationY(-dirY * push);
            // On a layer while it moves: drawn once and scaled as an image, instead of every
            // letter being re-drawn at a new size each frame (which made the lift stutter).
            a = v.animate().scaleX(scale).scaleY(scale).translationX(0).translationY(0).translationZ(z)
                    .setDuration(ms).setInterpolator(curve);
        } else {
            a = v.animate().scaleX(rest).scaleY(rest).translationX(0).translationY(0).translationZ(0)
                    .setDuration(260).setInterpolator(MainActivity.EASE);
        }
        if (asImage) a.withLayer();
        a.start();
    }

    /**
     * A press with nowhere to go: the focused control leans a little that way and springs back, as
     * tvOS's do at a wall. Only its position moves, so a lift still under way carries on.
     */
    static void bump(View v, int dirX, int dirY) {
        float d = 4f * v.getResources().getDisplayMetrics().density;
        v.animate().translationX(dirX * d).translationY(dirY * d).setStartDelay(0).setDuration(90)
                .setInterpolator(MainActivity.EASE)
                .withEndAction(() -> v.animate().translationX(0).translationY(0).setDuration(280)
                        .setInterpolator(new DecelerateInterpolator()).start())
                .start();
    }
}

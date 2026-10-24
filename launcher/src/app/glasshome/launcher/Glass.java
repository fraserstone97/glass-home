package app.glasshome.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The one Liquid Glass material for controls everywhere (Control Centre, Search, title pages,
 * Settings, menus): a translucent body, a soft highlight across the top, and a thin rim that
 * catches the light at the top edge and fades towards the bottom. Focus fades a white plate in
 * over it (setFocus, 0..1), so every focused control looks and moves the same way.
 * Cheap to draw: three gradients and a rounded rectangle, no blur, no bitmaps.
 */
final class Glass extends Drawable {

    /**
     * Body strength: CLEAR over pictures and video, REGULAR for most controls, SOLID for dense lists,
     * DARK for controls sitting on a glass panel (tvOS Control Centre: a shade darker than the panel).
     */
    static final int CLEAR = 0, REGULAR = 1, SOLID = 2, DARK = 3;

    final float radius, rimWidth;
    final int weight;
    final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF box = new RectF(), top = new RectF();
    float focus;

    Glass(float radius, float rimWidth, int weight) {
        this.radius = radius;
        this.rimWidth = Math.max(1f, rimWidth);
        this.weight = weight;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(this.rimWidth);
        plate.setColor(0xF7FFFFFF);
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
        int a0 = weight == CLEAR ? 0x22 : weight == SOLID ? 0x38 : dark ? 0x2E : 0x2C;
        int a1 = weight == CLEAR ? 0x10 : weight == SOLID ? 0x24 : dark ? 0x3D : 0x18;
        int base = dark ? 0x000000 : 0xFFFFFF;
        body.setShader(new LinearGradient(0, box.top, 0, box.bottom, (a0 << 24) | base, (a1 << 24) | base,
                Shader.TileMode.CLAMP));
        top.set(box.left, box.top, box.right, box.top + h * 0.5f);
        sheen.setShader(new LinearGradient(0, box.top, 0, box.top + h * 0.5f, dark ? 0x0AFFFFFF : 0x1AFFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, box.top, 0, box.bottom, dark
                        ? new int[]{0x26FFFFFF, 0x0DFFFFFF, 0x08FFFFFF, 0x14FFFFFF}
                        : new int[]{0x66FFFFFF, 0x1FFFFFFF, 0x14FFFFFF, 0x29FFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        float r = Math.min(radius, Math.min(box.width(), box.height()) / 2f);
        c.drawRoundRect(box, r, r, body);
        c.save();
        c.clipRect(top);
        c.drawRoundRect(box, r, r, sheen);
        c.restore();
        float in = rimWidth / 2f;
        c.drawRoundRect(box.left + in, box.top + in, box.right - in, box.bottom - in, r - in, r - in, rim);
        if (focus > 0f) {
            plate.setAlpha(Math.round(focus * 0xF7));
            c.drawRoundRect(box, r, r, plate);
        }
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** Fades the white focus plate in or out with the shared timing. */
    static void focus(Glass g, boolean has) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(g.getFocus(), has ? 1f : 0f);
        a.addUpdateListener(v -> g.setFocus((float) v.getAnimatedValue()));
        a.setDuration(has ? 200 : 260);
        a.setInterpolator(MainActivity.EASE);
        a.start();
    }

    /**
     * The spring for small controls: the tiles' quick rise and small overshoot, but settled in
     * about half the time. A long, slow settle on a wide pill read as jitter.
     */
    static final Spring SNAP = new Spring(0.8f, 14f);

    /** The last direction the remote moved focus: -1/0/+1 across and down (set by navSound). */
    static int dirX, dirY;

    /**
     * The shared lift, with the app tiles' jiggle: the control arrives with a small push from
     * the direction the remote moved, then the spring settles it as it lifts. Eases back down.
     */
    static void lift(android.view.View v, boolean has, float scale, float z) {
        v.animate().cancel();
        if (has) {
            float push = 4f * v.getResources().getDisplayMetrics().density;
            v.setTranslationX(-dirX * push);
            v.setTranslationY(-dirY * push);
            // On a layer while it moves: drawn once and scaled as an image, instead of every
            // letter being re-drawn at a new size each frame (which made the lift stutter).
            v.animate().scaleX(scale).scaleY(scale).translationX(0).translationY(0).translationZ(z)
                    .setDuration(460).setInterpolator(SNAP).withLayer().start();
        } else {
            v.animate().scaleX(1f).scaleY(1f).translationX(0).translationY(0).translationZ(0)
                    .setDuration(260).setInterpolator(MainActivity.EASE).withLayer().start();
        }
    }
}

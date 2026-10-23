package app.glasshome.launcher;

import android.animation.TimeInterpolator;

/**
 * A damped spring as a time curve, the motion tvOS uses for focus: a quick rise, one small
 * overshoot and a soft settle, with no visible wobble. damping is the ratio (1 = no overshoot;
 * 0.7 overshoots about 4%), omega how many radians it travels over the animation's duration.
 */
final class Spring implements TimeInterpolator {
    final float damping, omega, end;

    Spring(float damping, float omega) {
        this.damping = damping;
        this.omega = omega;
        this.end = raw(1f);
    }

    float raw(float t) {
        double z = damping, w = omega, wd = w * Math.sqrt(1 - z * z);
        double e = Math.exp(-z * w * t);
        return (float) (1 - e * (Math.cos(wd * t) + z * w / wd * Math.sin(wd * t)));
    }

    @Override
    public float getInterpolation(float t) {
        return raw(t) / end;  // lands exactly on 1 when the animation ends
    }
}

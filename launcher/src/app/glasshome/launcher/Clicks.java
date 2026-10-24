package app.glasshome.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.view.KeyEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Navigation sounds in the spirit of tvOS: a short, soft, glassy tick when focus moves, a rounder
 * "tock" on select and a lower, softer tick on Back. They're synthesised once into the app's cache
 * (no sound files to ship, the same on every TV) and played through a SoundPool for low latency.
 * Settings -> Home Screen -> Navigation Clicks switches them off.
 */
final class Clicks {
    private static SoundPool pool;
    private static int move, select, back;
    private static long lastMove;

    private Clicks() {
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("sounds", Context.MODE_PRIVATE).getBoolean("nav_sounds", true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("sounds", Context.MODE_PRIVATE).edit().putBoolean("nav_sounds", on).apply();
    }

    static synchronized void init(Context c) {
        if (pool != null) return;
        Context app = c.getApplicationContext();
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        move = load(app, "tick", new float[]{2350f, 1180f}, new float[]{0.55f, 0.3f}, 0.0055f, 0.045f, 0.32f, 0f);
        select = load(app, "tock", new float[]{880f, 1760f, 2640f}, new float[]{0.6f, 0.28f, 0.1f}, 0.016f, 0.11f, 0.42f, -0.06f);
        back = load(app, "back", new float[]{620f, 1240f}, new float[]{0.6f, 0.25f}, 0.011f, 0.08f, 0.3f, -0.04f);
    }

    /**
     * A struck, glassy tone: partials that die away quickly (decay seconds), a soft 1 ms attack so
     * nothing clicks, a touch of breath at the very start, and an optional pitch fall (glide, as a
     * fraction over the sound's length).
     */
    private static int load(Context c, String name, float[] freqs, float[] amps, float decay, float len, float gain, float glide) {
        int rate = 44100, n = Math.round(rate * len);
        short[] pcm = new short[n];
        java.util.Random noise = new java.util.Random(7);
        for (int i = 0; i < n; i++) {
            float t = i / (float) rate;
            float env = (float) Math.exp(-t / decay) * Math.min(1f, t / 0.001f);
            float v = 0;
            for (int k = 0; k < freqs.length; k++) {
                float f = freqs[k] * (1f + glide * t / len);
                // Higher partials fade faster, as on struck glass.
                v += amps[k] * Math.sin(2 * Math.PI * f * t) * Math.exp(-t * k * 60);
            }
            v += (noise.nextFloat() - 0.5f) * 0.25f * Math.exp(-t / 0.0012f);
            pcm[i] = (short) Math.round(Math.max(-1f, Math.min(1f, v * env * gain)) * 32767);
        }
        File f = new File(c.getCacheDir(), "click_" + name + "_v2.wav");
        try {
            if (!f.exists()) {
                ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
                b.put("RIFF".getBytes()).putInt(36 + n * 2).put("WAVE".getBytes()).put("fmt ".getBytes())
                        .putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                        .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(n * 2);
                for (short s : pcm) b.putShort(s);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(b.array());
                }
            }
            return pool.load(f.getPath(), 1);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void play(Context c, int id, float volume) {
        init(c);
        if (id != 0 && enabled(c)) pool.play(id, volume, volume, 1, 0, 1f);
    }

    /** Focus moved. Held arrows repeat fast: the ticks thin out rather than buzz. */
    static void move(Context c, int keyCode) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastMove < 55) return;
        lastMove = now;
        play(c, move, 0.7f);
    }

    /** Something was chosen. */
    static void select(Context c) {
        play(c, select, 0.75f);
    }

    /** Back. */
    static void back(Context c) {
        play(c, back, 0.6f);
    }
}

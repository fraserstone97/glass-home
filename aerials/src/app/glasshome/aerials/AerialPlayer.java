package app.glasshome.aerials;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Apple TV aerials, as shown by the screen saver (AerialDream) and the full-screen preview
 * (AerialActivity). Each clip streams in 4K from Apple (HttpSource) onto a hardware video
 * surface, in Apple's SDR grade (as an Apple TV shows them with an SDR home screen: no jump
 * into HDR brightness), with no text on screen until you ask: Up shows where you are, as Apple TV
 * does, for a few seconds at the bottom of the screen, and Right moves on to the next aerial.
 *
 * Two decks, like Apple TV: the next clip loads on the second surface while the current one
 * plays, then dissolves in over its last seconds. If the next clip isn't ready in time (a slow
 * connection), the current one fades to black while it is still moving and the next fades up
 * when it arrives. Clips come from a shuffled bag: every clip plays once before any repeats, and
 * the next session's first clip is kept on disk, so it starts as soon as the decoder is ready.
 */
final class AerialPlayer {

    static final PathInterpolator EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);
    /** Load the next clip this long before the current one ends; dissolve over the last XFADE. */
    static final int PRELOAD = 20000, XFADE = 2200, FADE = 1400, PREPARE_TIMEOUT = 30000;
    /** Right's dissolve: quicker, as it answers a button. */
    static final int SKIP_XFADE = 1000;
    /**
     * How early the next clip starts before its dissolve is due. A 4K clip takes most of a second
     * to show its first frame; started any later, the outgoing clip reached its end and froze
     * part-way through the dissolve.
     */
    static final int LEAD = 1200;

    /** How long the place name stays up after Up, and its fades. */
    static final int INFO_HOLD = 4000, INFO_IN = 450, INFO_OUT = 700;

    /** How long after a session's first fade-up the next session's opener is fetched: well clear of its start. */
    static final int OPENER_AFTER = 15000;

    static final class Clip {
        String url, name;
        /** The file's length in bytes (aerials.json). */
        long size;
        /** Apple's captions, by the second of the clip they start at. */
        final TreeMap<Integer, String> poi = new TreeMap<>();
    }

    /** One video surface and the player on it. */
    final class Deck {
        SurfaceView view;
        SurfaceHolder holder;
        boolean surface, prepared, rendering;
        MediaPlayer mp;
        HttpSource src;
        Clip clip;

        void release() {
            MediaPlayer m = mp;
            HttpSource s = src;
            mp = null;
            src = null;
            prepared = rendering = false;
            if (m != null) {
                try {
                    m.reset();
                } catch (IllegalStateException ignored) {
                    // already torn down
                }
                m.release();
            }
            if (s != null) s.close();
        }
    }

    /** One opener download at a time, across the screen saver and the preview. */
    static final AtomicBoolean saving = new AtomicBoolean();

    final Handler handler = new Handler(Looper.getMainLooper());
    final List<Clip> clips = new ArrayList<>();
    final Deck[] decks = new Deck[2];
    /** The deck on screen now; the other one is loading or idle. */
    Deck live;
    View curtain, info;
    TextView infoName, infoCaption;
    int failures;
    /** Not before this (uptime) does tick load the next clip again, after one that failed. */
    long retryAt;
    /**
     * dissolving: the next clip is dissolving in over the live one; skipping: Right has asked for
     * the next clip now; fadingOut: the live clip is fading to black, the next not being ready;
     * openerQueued: this session has set the next one's opener going (see keepOpener).
     */
    boolean playing, dissolving, skipping, fadingOut, openerQueued;
    /** Deck 1's opacity (see topAlpha), and the dissolve that drives it. */
    float topAlpha = 1f;
    ValueAnimator fade;
    SurfaceControl.Transaction tx;
    final Context ctx;
    /** Glass Home's design unit: 1/960 of the screen width (2 px at 1080p), whatever the TV's dpi. */
    final float unit;

    AerialPlayer(Context c) {
        ctx = c;
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        unit = Math.min(Math.max(m.widthPixels, m.heightPixels) / 960f, Math.min(m.widthPixels, m.heightPixels) / 540f);
    }

    /** The player's view: two stacked video surfaces, and a black curtain for the first fade. */
    View build() {
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            Deck d = new Deck();
            d.view = new SurfaceView(ctx);
            if (i == 1) d.view.setZOrderMediaOverlay(true);  // the second deck always sits on top
            root.addView(d.view, new FrameLayout.LayoutParams(-1, -1));
            d.holder = d.view.getHolder();
            d.holder.addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {
                    d.surface = true;
                    // A new surface starts opaque, and SurfaceView sets it so again as it first
                    // places it: deck 1 gets its opacity back once that has gone through.
                    if (d == decks[1]) d.view.post(() -> topAlpha(topAlpha));
                    if (playing && live == null && decks[0].surface && decks[1].surface) first();
                }
                @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hgt) { }
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    d.surface = false;
                    // Nothing plays on without both: the session starts again (first()) if they return.
                    reset();
                }
            });
            decks[i] = d;
        }
        curtain = new View(ctx);
        curtain.setBackgroundColor(Color.BLACK);
        root.addView(curtain, new FrameLayout.LayoutParams(-1, -1));
        // Only as tall as its shade: a fade draws the label offscreen, and a strip costs less than the screen.
        root.addView(buildInfo(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return root;
    }

    float u(float units) {
        return units * unit;
    }

    /**
     * The place name and caption, bottom left in the tvOS safe area (80 px in, 60 px up) over a
     * soft shade, in tvOS's Title 3 and Body sizes.
     */
    View buildInfo() {
        FrameLayout box = new FrameLayout(ctx);
        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x8C000000, 0x40000000, 0x00000000}));
        box.addView(shade, new FrameLayout.LayoutParams(-1, Math.round(u(160)), Gravity.BOTTOM));
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        infoName = new TextView(ctx);
        infoName.setTextColor(Color.WHITE);
        infoName.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(24));
        infoName.setTypeface(medium);
        infoName.setShadowLayer(u(8), 0, u(1), 0x66000000);
        infoCaption = new TextView(ctx);
        infoCaption.setTextColor(0xE6FFFFFF);
        infoCaption.setTextSize(TypedValue.COMPLEX_UNIT_PX, u(14.5f));
        infoCaption.setTypeface(medium);
        infoCaption.setShadowLayer(u(6), 0, u(1), 0x66000000);
        infoCaption.setMaxWidth(Math.round(u(560)));
        text.addView(infoName);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = Math.round(u(3));
        text.addView(infoCaption, cp);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tp.leftMargin = Math.round(u(40));
        tp.bottomMargin = Math.round(u(30));
        box.addView(text, tp);
        conceal(box);
        info = box;
        inter();
        return box;
    }

    /** Glass Home's Inter, from the launcher, as on every other screen; the system's sans-serif until then, or without it. */
    void inter() {
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                AssetManager a = ctx.createPackageContext("app.glasshome.launcher", 0).getAssets();
                Typeface name = Typeface.createFromAsset(a, "fonts/Inter-SemiBold.ttf");
                Typeface caption = Typeface.createFromAsset(a, "fonts/Inter-Medium.ttf");
                // Posted on the view, so a session starting (which clears the handler) can't drop it.
                infoName.post(() -> {
                    infoName.setTypeface(name);
                    infoCaption.setTypeface(caption);
                });
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                // Glass Home isn't installed: sans-serif it is
            }
        }, "AerialFonts").start();
    }

    /**
     * Shown and hidden for real, not just faded: while the curtain and the label are gone, nothing
     * of the window covers the videos, so the TV can leave it out when it composes the screen.
     */
    static void reveal(View v) {
        if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
    }

    static void conceal(View v) {
        v.setAlpha(0f);
        v.setVisibility(View.GONE);
    }

    /** Up: where this is, for a few seconds. Pressing it again keeps it up. */
    void showInfo() {
        // During a dissolve, the clip coming in is the one you're looking at.
        Deck d = dissolving ? other(live) : live;
        // Not while closing, or fading to black (the name would stay up over the next clip).
        if (info == null || !playing || fadingOut || d == null || d.clip == null) return;
        int sec = 0;
        try {
            if (d.mp != null) sec = d.mp.getCurrentPosition() / 1000;
        } catch (IllegalStateException ignored) {
            // between clips: the first caption will do
        }
        Map.Entry<Integer, String> e = d.clip.poi.floorEntry(sec);
        if (e == null && !d.clip.poi.isEmpty()) e = d.clip.poi.firstEntry();
        infoName.setText(d.clip.name);
        infoCaption.setText(e == null ? "" : e.getValue());
        infoCaption.setVisibility(e == null ? View.GONE : View.VISIBLE);
        handler.removeCallbacks(hideInfo);
        info.animate().cancel();
        reveal(info);
        info.setTranslationY(info.getAlpha() > 0f ? info.getTranslationY() : u(10));
        info.animate().alpha(1f).translationY(0).setDuration(INFO_IN).setInterpolator(EASE).start();
        handler.postDelayed(hideInfo, INFO_HOLD);
    }

    final Runnable hideInfo = () -> {
        if (info == null) return;
        info.animate().cancel();
        if (info.getVisibility() != View.VISIBLE) return;
        info.animate().alpha(0f).setDuration(INFO_OUT).setInterpolator(EASE)
                .withEndAction(() -> info.setVisibility(View.GONE)).start();
    };

    /**
     * The remote, for both the screen saver and the preview: Up shows the place name and Right
     * moves on to the next aerial, as on Apple TV; any other button returns true, meaning close.
     * Only the press counts, so a release does nothing.
     */
    boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
            showInfo();
            return false;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // One aerial a press: held down, it doesn't run on through them.
            if (e.getRepeatCount() == 0) skip();
            return false;
        }
        return true;
    }

    /** A session, from black (any earlier one let go first). */
    void start() {
        reset();
        if (clips.isEmpty()) load();
        reveal(curtain);
        curtain.setAlpha(1f);
        conceal(info);
        openerQueued = false;
        retryAt = 0;
        playing = true;
        if (decks[0].surface && decks[1].surface) first();
    }

    /**
     * Holds the picture still, for the system's fade back to where you were (a black curtain
     * here made a press look like black, then Home); stop() lets the players go once it's over.
     */
    void pause() {
        playing = false;
        halt();
        for (Deck d : decks) {
            if (d == null || d.mp == null) continue;
            try {
                // A player that hasn't started can't pause: that would put it in error.
                if (d.mp.isPlaying()) d.mp.pause();
            } catch (IllegalStateException ignored) {
                // between states: stop() lets it go
            }
        }
    }

    void stop() {
        playing = false;
        reset();
    }

    /** Cancels everything under way: the timers, the fades and the dissolve. */
    void halt() {
        handler.removeCallbacksAndMessages(null);
        if (fade != null) fade.cancel();
        if (curtain != null) curtain.animate().cancel();
        if (info != null) info.animate().cancel();
        dissolving = skipping = fadingOut = false;
    }

    /** halt(), and both players let go. */
    void reset() {
        halt();
        for (Deck d : decks) if (d != null) d.release();
        live = null;
    }

    // ---------------------------------------------------------------- clips

    void load() {
        try (InputStream in = ctx.getAssets().open("aerials.json")) {
            byte[] b = new byte[in.available()];
            int n = 0;
            while (n < b.length) n += in.read(b, n, b.length - n);
            JSONArray a = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Clip c = new Clip();
                c.url = o.getString("url");
                c.name = o.optString("name");
                c.size = o.getLong("size");
                JSONObject poi = o.optJSONObject("poi");
                if (poi != null) {
                    for (Iterator<String> k = poi.keys(); k.hasNext(); ) {
                        String at = k.next();
                        c.poi.put(Integer.parseInt(at), poi.getString(at));
                    }
                }
                clips.add(c);
            }
        } catch (Exception e) {
            clips.clear();
        }
    }

    SharedPreferences prefs() {
        return ctx.getSharedPreferences("shuffle", Context.MODE_PRIVATE);
    }

    /**
     * The next clip from a shuffled bag kept across sessions: all of them play once, in random
     * order, before a new bag starts (never with the clip that just played).
     */
    Clip pick() {
        SharedPreferences p = prefs();
        String bag = p.getString("bag", "");
        int pos = p.getInt("pos", 0);
        String[] order = bag.isEmpty() ? new String[0] : bag.split(",");
        if (order.length != clips.size() || pos >= order.length) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < clips.size(); i++) idx.add(i);
            Collections.shuffle(idx, new Random());
            int last = p.getInt("last", -1);
            if (idx.size() > 1 && idx.get(0) == last) Collections.swap(idx, 0, idx.size() - 1);
            StringBuilder sb = new StringBuilder();
            for (int i : idx) sb.append(sb.length() == 0 ? "" : ",").append(i);
            order = sb.toString().split(",");
            pos = 0;
        }
        int i = Integer.parseInt(order[pos]);
        p.edit().putString("bag", String.join(",", order)).putInt("pos", pos + 1).putInt("last", i).apply();
        return clips.get(i);
    }

    /**
     * Keeps the next session's first clip on disk, its first chunk and its index (Apple TV plays
     * aerials from storage, so they start at once). It is taken from the bag now, so it doesn't
     * also play this session, and fetched quietly in the background.
     */
    final Runnable keepOpener = () -> {
        SharedPreferences p = prefs();
        if (!playing || clips.isEmpty() || p.getString("opener", null) != null) return;
        // Taken here, before the clip leaves the bag: while the other player (screen saver or
        // preview) is saving one, a second save would lose its clip.
        if (!saving.compareAndSet(false, true)) return;
        Clip c = pick();
        File head = opener("head"), tail = opener("tail");
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                HttpSource.save(c.url, c.size, head, tail);
                p.edit().putString("opener", c.url).putLong("openerSize", c.size).apply();
            } catch (IOException | RuntimeException e) {
                // the next session streams its start, as without it
            } finally {
                saving.set(false);
            }
        }, "AerialOpener").start();
    };

    File opener(String part) {
        return new File(ctx.getCacheDir(), "opener." + part);
    }

    /**
     * The opener kept by the last session, if it is still good (the same file at Apple, both parts
     * on disk). Taken either way: it is used once.
     */
    Clip opener() {
        SharedPreferences p = prefs();
        String url = p.getString("opener", null);
        if (url == null) return null;
        long size = p.getLong("openerSize", -1);
        p.edit().remove("opener").remove("openerSize").apply();
        if (opener("head").isFile() && opener("tail").isFile()) {
            for (Clip c : clips) if (c.url.equals(url) && c.size == size) return c;
        }
        opener("head").delete();
        opener("tail").delete();
        return null;
    }

    Deck other(Deck d) {
        return d == decks[0] ? decks[1] : decks[0];
    }

    // ---------------------------------------------------------------- playback

    /**
     * Deck 1's opacity, set on its surface. Behind the window, a SurfaceView's own alpha only
     * fades the hole it punches while its video stays opaque, so a View fade showed as a cut.
     * Before Android 10 there's no surface to reach, and the View's alpha is all there is.
     */
    void topAlpha(float a) {
        topAlpha = a;
        SurfaceView v = decks[1].view;
        if (Build.VERSION.SDK_INT < 29) {
            v.setAlpha(a);
            return;
        }
        SurfaceControl sc = v.getSurfaceControl();
        if (sc == null || !sc.isValid()) return;
        // One transaction for every frame of a dissolve (apply() leaves it empty for the next).
        if (tx == null) tx = new SurfaceControl.Transaction();
        tx.setAlpha(sc, a).apply();
    }

    /** The first clip of a session fades up from black on the bottom deck: the opener, if one was kept. */
    void first() {
        if (!playing || clips.isEmpty()) return;
        curtain.animate().cancel();
        reveal(curtain);
        curtain.setAlpha(1f);
        topAlpha(0f);
        Clip c = opener();
        if (c != null) load(decks[0], c, true, opener("head"), opener("tail"));
        else load(decks[0], true);
    }

    /** Loads the next clip onto a deck; startNow plays it as soon as it is ready. */
    void load(Deck d, boolean startNow) {
        load(d, pick(), startNow, null, null);
    }

    /** load(), for a given clip; head and tail are its opener on disk (see keepOpener). */
    void load(Deck d, Clip clip, boolean startNow, File head, File tail) {
        d.release();
        MediaPlayer mp = new MediaPlayer();
        d.mp = mp;
        d.clip = clip;
        try {
            d.src = new HttpSource(clip.url, clip.size, head, tail);
            mp.setDataSource(d.src);
            mp.setDisplay(d.holder);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(m -> {
                if (m != d.mp) return;
                m.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                d.prepared = true;
                handler.removeCallbacks(timeout);
                // Now, if asked (or for Right); at the dissolve point, unless the screen is already
                // fading to black for want of it; or straight away when the screen is black waiting for it.
                if (startNow || live == null || skipping || (dissolveDue() && !fadingOut)) begin(d);
            });
            mp.setOnInfoListener((m, what, extra) -> {
                if (m == d.mp && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    d.rendering = true;
                    failures = 0;
                    shown(d);
                }
                return false;
            });
            mp.setOnCompletionListener(m -> {
                if (m == d.mp && d == live) ended();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                if (m == d.mp) failed(d);
                return true;
            });
            mp.prepareAsync();
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, PREPARE_TIMEOUT);
        } catch (Exception e) {
            failed(d);
        }
    }

    final Runnable timeout = () -> {
        for (Deck d : decks) if (d != live && d.mp != null && !d.prepared) failed(d);
    };

    /** A clip that won't load: try another; after a few in a row (offline), wait a minute. */
    void failed(Deck d) {
        d.release();
        // Fading to black already: its hand-off loads another.
        if (!playing || fadingOut) return;
        if (dissolving) {
            // The clip going out: the dissolve carries on to the new one.
            if (d == live) return;
            // The clip coming in: the live one goes back on top and plays on to its end.
            fade.cancel();
            dissolving = false;
            topAlpha(live == decks[1] ? 1f : 0f);
        }
        skipping = false;
        failures++;
        long wait = failures >= 4 ? 60000 : 500;
        if (failures >= 4) failures = 0;
        if (d != live && live != null && live.mp != null) {
            // The next clip, the live one playing on: tick loads another once the wait is over
            // (it stops once it has started one, so it is set going again). If the live clip has
            // failed too, there's nothing for tick to watch: black, below.
            retryAt = SystemClock.uptimeMillis() + wait;
            handler.removeCallbacks(tick);
            handler.post(tick);
            return;
        }
        // On screen: black (not the failed clip's last frame) until another comes up.
        live = null;
        reveal(curtain);
        curtain.animate().cancel();
        curtain.animate().alpha(1f).setDuration(FADE).setInterpolator(EASE).start();
        handler.postDelayed(() -> {
            // The next clip may have come up meanwhile, and is playing.
            if (!playing || live != null) return;
            for (Deck x : decks) x.release();
            load(decks[0], true);  // shown() hides deck 1 as it comes up
        }, wait);
    }

    void begin(Deck d) {
        if (!playing || d.mp == null) return;
        // Deck 1 coming in over a clip still on screen starts unseen, for the dissolve to bring
        // up; otherwise its opacity is set afresh (a new surface starts opaque).
        if (d == decks[1]) topAlpha(live != null && live != d ? 0f : topAlpha);
        d.mp.start();
    }

    /** A deck's first frame is up: fade it in from black, or dissolve it over the live deck. */
    void shown(Deck d) {
        // While the screen fades to black for want of it, the fade's hand-off brings it up.
        if (!playing || fadingOut) return;
        if (live == null || live == d || live.mp == null) {
            // Nothing (or nothing playing) under it: the curtain lifts.
            topAlpha(d == decks[1] ? 1f : 0f);
            live = d;
            skipping = false;
            curtain.animate().cancel();
            curtain.animate().alpha(0f).setDuration(FADE).setInterpolator(EASE)
                    .withEndAction(() -> curtain.setVisibility(View.GONE)).start();
            handler.removeCallbacks(tick);
            handler.post(tick);
            if (!openerQueued) {
                openerQueued = true;
                handler.postDelayed(keepOpener, OPENER_AFTER);
            }
            return;
        }
        Deck out = live;
        // The place name belonged to the clip that is leaving.
        handler.removeCallbacks(hideInfo);
        hideInfo.run();
        dissolving = true;
        // Deck 1 carries the dissolve either way: in when the new clip is on it, out (revealing
        // the new clip underneath) when it is on deck 0. Only its surface changes, so the two
        // videos blend with no drawing in the app at all.
        fade = ValueAnimator.ofFloat(topAlpha, d == decks[1] ? 1f : 0f);
        fade.setDuration(skipping ? SKIP_XFADE : XFADE);
        fade.setInterpolator(EASE);
        fade.addUpdateListener(a -> topAlpha((float) a.getAnimatedValue()));
        fade.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;

            @Override public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(Animator a) {
                if (cancelled || !playing) return;
                out.release();
                live = d;
                dissolving = skipping = false;
                curtain.animate().cancel();
                conceal(curtain);
                handler.removeCallbacks(tick);
                handler.post(tick);
            }
        });
        fade.start();
    }

    boolean dissolveDue() {
        MediaPlayer m = live == null ? null : live.mp;
        if (m == null) return false;
        try {
            return m.getDuration() > 0 && m.getCurrentPosition() >= m.getDuration() - XFADE - LEAD;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Watches the live clip: loads the next in good time, and starts it for the dissolve. */
    final Runnable tick = new Runnable() {
        @Override public void run() {
            Deck d = live;
            if (d == null || d.mp == null || !playing || dissolving || fadingOut) return;
            int at, len;
            try {
                at = d.mp.getCurrentPosition();
                len = d.mp.getDuration();
            } catch (IllegalStateException e) {
                return;
            }
            Deck next = other(d);
            if (len > 0 && at >= len - PRELOAD && next.mp == null && next.surface
                    && SystemClock.uptimeMillis() >= retryAt) load(next, false);
            if (len > 0 && at >= len - XFADE - LEAD && next.prepared && !next.mp.isPlaying()) {
                begin(next);
                return;  // shown() takes over when its first frame arrives
            }
            // Still not ready: fade to black over the clip's last moving seconds, not on its frozen last frame.
            if (len > 0 && at >= len - FADE - 200 && !next.prepared) {
                fadeOut();
                return;
            }
            // One at a time: a load that failed straight away above has set it going again (failed()).
            handler.removeCallbacks(this);
            handler.postDelayed(this, 200);
        }
    };

    /** Right: on to the next aerial now, as Apple TV's swipe right (or a press right on the ring). */
    void skip() {
        Deck d = live;
        // Not while one change is under way already, or the screen is black or fading.
        if (!playing || d == null || d.mp == null || dissolving || skipping || fadingOut || curtain.getAlpha() > 0f) return;
        Deck next = other(d);
        if (next.prepared) {
            if (next.mp.isPlaying()) return;  // its dissolve is on the way
            skipping = true;
            begin(next);
        } else if (next.mp != null) {
            skipping = true;  // still loading: onPrepared starts it
        } else if (next.surface) {
            skipping = true;
            load(next, true);
        }
    }

    /** The live clip ended before the next one could dissolve in. */
    void ended() {
        if (!playing || dissolving || fadingOut) return;
        fadeOut();
    }

    /** The next clip isn't ready (a slow connection): the live one fades to black, and the next fades up when it arrives. */
    void fadeOut() {
        fadingOut = true;
        skipping = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(hideInfo);
        hideInfo.run();
        Deck next = other(live);
        reveal(curtain);
        curtain.animate().cancel();
        curtain.animate().alpha(1f).setDuration(FADE).setInterpolator(EASE).withEndAction(() -> {
            fadingOut = false;
            Deck old = live;
            live = null;
            if (old != null) old.release();
            // Deck 1 shows only with the next clip on it: whatever it last held would cover deck 0.
            topAlpha(next == decks[1] ? 1f : 0f);
            if (next.rendering) shown(next);
            else if (next.prepared) begin(next);
            else if (next.mp == null) load(next, true);
            // else: still loading; onPrepared sees no live clip and starts it
        }).start();
    }
}

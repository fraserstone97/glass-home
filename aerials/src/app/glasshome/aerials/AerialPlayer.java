package app.glasshome.aerials;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Apple TV aerials, as shown by the screen saver (AerialDream) and the full-screen preview
 * (AerialActivity). Each clip streams in 4K from Apple (HttpSource) onto a hardware video
 * surface, in Apple's SDR grade (as an Apple TV shows them with an SDR home screen: no jump
 * into HDR brightness), with no text on screen until you ask: Up shows where you are, as Apple TV
 * does, for a few seconds at the bottom of the screen.
 *
 * Two decks, like Apple TV: the next clip loads on the second surface while the current one
 * plays, then dissolves in over its last seconds. If the next clip isn't ready in time (a slow
 * connection), the current one fades to black and the next fades up when it arrives. Clips come
 * from a shuffled bag: every clip plays once before any repeats. Any button wakes the TV.
 */
final class AerialPlayer {

    static final PathInterpolator EASE = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);
    /** Load the next clip this long before the current one ends; dissolve over the last XFADE. */
    static final int PRELOAD = 20000, XFADE = 2200, FADE = 1400, PREPARE_TIMEOUT = 30000;

    /** How long the place name stays up after Up, and its fades. */
    static final int INFO_HOLD = 4000, INFO_IN = 450, INFO_OUT = 700;

    static final class Clip {
        String url, name;
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
        long started;

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

    final Handler handler = new Handler(Looper.getMainLooper());
    final List<Clip> clips = new ArrayList<>();
    final Deck[] decks = new Deck[2];
    /** The deck on screen now; the other one is loading or idle. */
    Deck live;
    View curtain, info;
    TextView infoName, infoCaption;
    int failures;
    boolean playing, dissolving;
    final Context ctx;

    AerialPlayer(Context c) {
        ctx = c;
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
                    if (playing && live == null && decks[0].surface && decks[1].surface) first();
                }
                @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hgt) { }
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    d.surface = false;
                    d.release();
                }
            });
            decks[i] = d;
        }
        curtain = new View(ctx);
        curtain.setBackgroundColor(Color.BLACK);
        root.addView(curtain, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildInfo(), new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    float px(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    /** The place name and caption, bottom left in the TV safe area, over a soft shade. */
    View buildInfo() {
        FrameLayout box = new FrameLayout(ctx);
        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x8C000000, 0x40000000, 0x00000000}));
        box.addView(shade, new FrameLayout.LayoutParams(-1, Math.round(px(260)), Gravity.BOTTOM));
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        infoName = new TextView(ctx);
        infoName.setTextColor(Color.WHITE);
        infoName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);
        infoName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        infoName.setShadowLayer(px(8), 0, px(1), 0x66000000);
        infoCaption = new TextView(ctx);
        infoCaption.setTextColor(0xE6FFFFFF);
        infoCaption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        infoCaption.setShadowLayer(px(6), 0, px(1), 0x66000000);
        infoCaption.setMaxWidth(Math.round(px(560)));
        text.addView(infoName);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = Math.round(px(4));
        text.addView(infoCaption, cp);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tp.leftMargin = Math.round(px(64));
        tp.bottomMargin = Math.round(px(48));
        box.addView(text, tp);
        box.setAlpha(0f);
        info = box;
        return box;
    }

    /** Up: where this is, for a few seconds. Pressing it again keeps it up. */
    void showInfo() {
        Deck d = live;
        if (info == null || d == null || d.clip == null) return;
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
        info.setTranslationY(info.getAlpha() > 0f ? info.getTranslationY() : px(10));
        info.animate().alpha(1f).translationY(0).setDuration(INFO_IN).setInterpolator(EASE).start();
        handler.postDelayed(hideInfo, INFO_HOLD);
    }

    final Runnable hideInfo = () -> {
        if (info == null) return;
        info.animate().cancel();
        info.animate().alpha(0f).setDuration(INFO_OUT).setInterpolator(EASE).start();
    };

    /**
     * The remote, for both the screen saver and the preview: Up shows the place name; any other
     * button returns true, meaning close. Only the press counts, so Up's release does nothing.
     */
    boolean key(android.view.KeyEvent e) {
        if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
        if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            showInfo();
            return false;
        }
        return true;
    }

    void start() {
        if (clips.isEmpty()) load();
        playing = true;
        if (live == null && decks[0].surface && decks[1].surface) first();
    }

    void stop() {
        playing = false;
        handler.removeCallbacksAndMessages(null);
        for (Deck d : decks) if (d != null) d.release();
        live = null;
        dissolving = false;
        if (curtain != null) curtain.setAlpha(1f);
        if (info != null) info.setAlpha(0f);
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

    /**
     * The next clip from a shuffled bag kept across sessions: all of them play once, in random
     * order, before a new bag starts (never with the clip that just played).
     */
    Clip pick() {
        SharedPreferences p = ctx.getSharedPreferences("shuffle", Context.MODE_PRIVATE);
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

    static void log(String m) {
        android.util.Log.i("Aerials", m);
    }

    int deck(Deck d) {
        return d == decks[0] ? 0 : 1;
    }

    Deck other(Deck d) {
        return d == decks[0] ? decks[1] : decks[0];
    }

    // ---------------------------------------------------------------- playback

    /** The first clip of a session fades up from black on the bottom deck. */
    void first() {
        if (!playing || clips.isEmpty()) return;
        decks[1].view.setAlpha(0f);
        load(decks[0], true);
    }

    /** Loads the next clip onto a deck; startNow plays it as soon as it is ready. */
    void load(Deck d, boolean startNow) {
        d.release();
        Clip clip = pick();
        log("load deck " + deck(d) + (startNow ? " (now)" : " (next)") + " " + clip.url.substring(clip.url.lastIndexOf('/') + 1));
        MediaPlayer mp = new MediaPlayer();
        d.mp = mp;
        d.clip = clip;
        try {
            d.src = new HttpSource(clip.url);
            mp.setDataSource(d.src);
            mp.setDisplay(d.holder);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(m -> {
                if (m != d.mp) return;
                m.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                d.prepared = true;
                handler.removeCallbacks(timeout);
                // Now, if asked; at the dissolve point; or straight away when the screen is black waiting for it.
                if (startNow || live == null || dissolveDue()) begin(d);
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
        if (live != null && !live.rendering) failed(live);
    };

    /** A clip that won't load: try another; after a few in a row (offline), wait a minute. */
    void failed(Deck d) {
        log("failed deck " + deck(d));
        d.release();
        failures++;
        boolean onScreen = d == live || live == null;
        if (d == live) live = null;
        long wait = failures >= 4 ? 60000 : 500;
        if (failures >= 4) failures = 0;
        handler.postDelayed(() -> {
            if (!playing) return;
            if (onScreen) {
                curtain.setAlpha(1f);
                decks[1].view.setAlpha(0f);
                load(decks[0], true);
            } else {
                load(d, false);
            }
        }, wait);
    }

    void begin(Deck d) {
        log("play deck " + deck(d));
        d.started = System.currentTimeMillis();
        d.mp.start();
    }

    /** A deck's first frame is up: fade it in from black, or dissolve it over the live deck. */
    void shown(Deck d) {
        if (live == null || live == d || live.mp == null) {
            // Nothing (or nothing playing) under it: the curtain lifts.
            if (d == decks[1]) d.view.setAlpha(1f);
            live = d;
            curtain.animate().cancel();
            curtain.animate().alpha(0f).setDuration(FADE).setInterpolator(EASE).start();
            handler.removeCallbacks(tick);
            handler.post(tick);
            return;
        }
        Deck out = live;
        log("dissolve to deck " + deck(d));
        // The place name belonged to the clip that is leaving.
        handler.removeCallbacks(hideInfo);
        hideInfo.run();
        dissolving = true;
        View top = decks[1].view;
        // The top deck carries the dissolve either way: in when the new clip is on top,
        // out (revealing the new clip underneath) when it is on the bottom.
        top.animate().cancel();
        top.animate().alpha(d == decks[1] ? 1f : 0f).setDuration(XFADE).setInterpolator(EASE).withEndAction(() -> {
            out.release();
            live = d;
            dissolving = false;
            curtain.setAlpha(0f);
            handler.removeCallbacks(tick);
            handler.post(tick);
        }).start();
    }

    boolean dissolveDue() {
        MediaPlayer m = live == null ? null : live.mp;
        if (m == null) return false;
        try {
            return m.getDuration() > 0 && m.getCurrentPosition() >= m.getDuration() - XFADE - 200;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Watches the live clip: loads the next in good time, and starts it for the dissolve. */
    final Runnable tick = new Runnable() {
        @Override public void run() {
            Deck d = live;
            if (d == null || d.mp == null || !playing || dissolving) return;
            int at, len;
            try {
                at = d.mp.getCurrentPosition();
                len = d.mp.getDuration();
            } catch (IllegalStateException e) {
                return;
            }
            Deck next = other(d);
            if (len > 0 && at >= len - PRELOAD && next.mp == null && next.surface) load(next, false);
            if (len > 0 && at >= len - XFADE - 200 && next.prepared && !next.mp.isPlaying()) {
                begin(next);
                return;  // shown() takes over when its first frame arrives
            }
            handler.postDelayed(this, 200);
        }
    };

    /** The live clip ended without the next one ready: fade to black and wait for it. */
    void ended() {
        if (dissolving) return;
        log("ended before the next was ready: fade through black");
        Deck next = other(live);
        curtain.animate().cancel();
        curtain.animate().alpha(1f).setDuration(FADE).setInterpolator(EASE).withEndAction(() -> {
            Deck old = live;
            live = null;
            if (old != null) old.release();
            decks[1].view.setAlpha(next == decks[1] ? 1f : 0f);
            if (next.prepared) begin(next);
            else if (next.mp == null) load(next, true);
            // else: still loading; onPrepared sees no live clip and starts it
        }).start();
    }
}

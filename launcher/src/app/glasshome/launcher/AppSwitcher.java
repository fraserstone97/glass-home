package app.glasshome.launcher;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The App Switcher, as on Apple TV: press Home twice quickly (KeysService) and the apps you've
 * used lately line up as big cards over a dimmed Home, the oldest on the left and a card for Home
 * itself on the right. The app you were just in is in front (Home's card is, when Home twice came
 * from Home): Left goes back through older apps, Right towards Home. OK goes back into one (it
 * grows out of its card), or on Home's card to Home; Back or Home closes. It's built when
 * asked for and dropped when it closes, so Home carries nothing for it the rest of the time.
 * Which apps you've used comes from Android's usage history, which install.sh lets Home read;
 * without that, Home twice is just Home.
 */
final class AppSwitcher {
    static final int MAX = 10;
    static final String AERIALS = "app.glasshome.aerials";

    final MainActivity home;
    /** The apps, oldest first; their cells and cards share the index, and Home's comes after them. */
    final List<MainActivity.AppEntry> apps;
    final FrameLayout layer, strip;
    final ImageView backdrop;
    final List<View> cells = new ArrayList<>();
    final List<ImageView> cards = new ArrayList<>();
    final int w, h, pitch;
    /** When the cards have all risen into place (uptime). */
    final long riseEnd;
    int sel;
    /** The key whose press began while the switcher was up: only its release acts. */
    int pressed = -1;
    volatile boolean closing;

    /** Finds the apps you've used (off the main thread), shows them, then draws their cards. */
    static void open(MainActivity home) {
        // Not Home's own entries (the Inputs card is one): the first Home press always returns to
        // Home, so they'd always come first.
        Map<String, MainActivity.AppEntry> byPkg = new HashMap<>();
        for (MainActivity.AppEntry e : home.all.values()) {
            if (e.pkg != null && !e.pkg.equals(AERIALS) && !e.pkg.equals(home.getPackageName())
                    && !byPkg.containsKey(e.pkg)) byPkg.put(e.pkg, e);
        }
        int w = Math.round(home.screenW * 0.36f), h = w * 9 / 16;
        // Its own thread: Home's queue is busy with the carousel and weather just after it returns.
        new Thread(() -> {
            Map<String, Long> left = new HashMap<>();
            List<MainActivity.AppEntry> recent = recent(home, byPkg, left);
            if (recent.isEmpty()) return;
            // Pressed from inside an app (the first press only just left it), that app is in
            // front; pressed on Home, Home's own card is.
            Long out = left.get(recent.get(0).pkg);
            boolean fromApp = out == null || System.currentTimeMillis() - out < 1500;
            home.runOnUiThread(() -> {
                if (home.switcher != null || home.isFinishing()) return;
                AppSwitcher s = home.switcher = new AppSwitcher(home, recent, w, h, fromApp);
                new Thread(s::drawCards, "AppSwitcher").start();
            });
        }, "AppSwitcher").start();
    }

    /**
     * Each app's card art, nearest the front first, so the ones you see are drawn before those further
     * left. Those off the screen (two or more left of the front) wait until the cards have risen:
     * decoding and drawing them meanwhile would take from the rise's frames.
     */
    void drawCards() {
        int first = sel;
        for (int i = apps.size() - 1; i >= 0 && !closing; i--) {
            if (i == first - 2) {
                SystemClock.sleep(Math.max(0, riseEnd - SystemClock.uptimeMillis()));
                if (closing) return;
            }
            Bitmap b = Suggestions.onGpu(cardArt(home, apps.get(i), w, h));
            ImageView card = cards.get(i);
            if (b != null) home.runOnUiThread(() -> card.setImageBitmap(b));
        }
    }

    /**
     * Apps opened in the last day, the latest first, as Android's usage history has them. `left`
     * gets when each was last left, for those left since they were last opened.
     */
    static List<MainActivity.AppEntry> recent(MainActivity home, Map<String, MainActivity.AppEntry> byPkg,
                                              Map<String, Long> left) {
        List<MainActivity.AppEntry> out = new ArrayList<>();
        UsageStatsManager um = home.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events = um == null ? null : um.queryEvents(now - 24 * 3600_000L, now);
        if (events == null) return out;
        Map<String, Long> last = new HashMap<>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            String p = e.getPackageName();
            if (!byPkg.containsKey(p)) continue;
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                last.put(p, e.getTimeStamp());
                left.remove(p);
            } else if (e.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                left.put(p, e.getTimeStamp());
            }
        }
        List<String> pkgs = new ArrayList<>(last.keySet());
        Collections.sort(pkgs, (x, y) -> Long.compare(last.get(y), last.get(x)));
        for (String p : pkgs) {
            if (out.size() == MAX) break;
            out.add(byPkg.get(p));
        }
        return out;
    }

    /**
     * The app's card at switcher size: the same art as its tile on Home, from the biggest banner it
     * ships. Only pictures Home doesn't draw itself are touched here, off the main thread.
     */
    static Bitmap cardArt(MainActivity home, MainActivity.AppEntry e, int w, int h) {
        Drawable d = e.banner;
        boolean drawn = e.bannerRes == 0 || MainActivity.TVOS_CARD_PKGS.contains(e.pkg)
                || MainActivity.ZOOM_BANNER_PKGS.contains(e.pkg);
        if (!drawn) {
            Drawable big = home.hiRes(home.getPackageManager(), e.pkg, e.bannerRes, null);
            if (big != null) d = big;
            else if (!(d instanceof BitmapDrawable) && e.tileArt != null) d = new BitmapDrawable(home.getResources(), e.tileArt);
        }
        return d == null ? null : MainActivity.art(d, w, h);
    }

    AppSwitcher(MainActivity home, List<MainActivity.AppEntry> recent, int w, int h, boolean fromApp) {
        this.home = home;
        apps = new ArrayList<>(recent);
        Collections.reverse(apps);  // the oldest on the left
        this.w = w;
        this.h = h;
        pitch = w + home.dp(26);
        layer = new FrameLayout(home);
        layer.setClickable(true);
        layer.setClipChildren(false);

        // Home's own picture, blurred and dimmed, the way the switcher sits over tvOS.
        backdrop = new ImageView(home);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap blur = home.heroDrawer != null ? home.heroDrawer : home.wallBlur;
        if (blur != null) {
            backdrop.setImageBitmap(blur);
            backdrop.setColorFilter(0xA6000000, PorterDuff.Mode.SRC_ATOP);
        } else {
            backdrop.setBackgroundColor(0xF0000000);
        }
        backdrop.setAlpha(0f);
        layer.addView(backdrop, home.match());

        strip = new FrameLayout(home);
        strip.setClipChildren(false);
        layer.addView(strip, home.match());
        int head = home.dp(34);
        int left = (home.screenW - w) / 2;
        int top = Math.round((home.screenH - head - h) / 2f);
        int n = apps.size();
        for (int i = 0; i <= n; i++) {
            MainActivity.AppEntry a = i < n ? apps.get(i) : null;  // null: Home's card, last
            LinearLayout cell = new LinearLayout(home);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setClipChildren(false);
            cell.forceHasOverlappingRendering(false);  // fades as one alpha, without an off-screen pass

            // The app's icon and name above its card.
            LinearLayout name = new LinearLayout(home);
            name.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(home);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (a == null) {
                icon.setImageBitmap(Glyphs.get("house", home.dp(20), Color.WHITE));
            } else {
                Drawable.ConstantState own = a.icon == null ? null : a.icon.getConstantState();
                icon.setImageDrawable(own != null ? own.newDrawable(home.getResources()) : a.icon);  // its own copy
            }
            name.addView(icon, new LinearLayout.LayoutParams(home.dp(20), home.dp(20)));
            TextView label = home.text(14, Ui.font(home, "semibold"), 1f);
            label.setText(a == null ? "Home" : a.label);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = home.dp(8);
            name.addView(label, lp);
            cell.addView(name, new LinearLayout.LayoutParams(w, head));

            // Its colour until the art is drawn, a moment later.
            ImageView card = new ImageView(home);
            GradientDrawable well = new GradientDrawable();
            well.setCornerRadius(home.dp(14));
            well.setColor(a == null ? 0xFF1C1C1E : a.color);
            card.setBackground(well);
            card.setClipToOutline(true);
            card.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(card, new LinearLayout.LayoutParams(w, h));

            cell.setPivotX(w / 2f);
            cell.setPivotY(head + h / 2f);
            cell.setTranslationX(left + i * pitch);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(w, head + h, Gravity.TOP | Gravity.START);
            cp.topMargin = top;
            strip.addView(cell, cp);
            cells.add(cell);
            cards.add(card);
        }
        sel = fromApp ? n - 1 : n;
        strip.setTranslationX(-sel * pitch);
        homeCard(cards.get(n));
        home.root.addView(layer, home.match());
        home.handler.removeCallbacks(home.heroAdvance);  // nothing moves underneath meanwhile

        // In: the picture dims up while the cards rise into place, the nearest first.
        riseEnd = SystemClock.uptimeMillis() + 670;  // 560 ms, after up to 105 ms of stagger
        backdrop.animate().alpha(1f).setDuration(260).setInterpolator(MainActivity.EASE).start();
        for (int k = 0; k < cells.size(); k++) {
            View c = cells.get(k);
            float s = rest(k);
            c.setScaleX(s * 0.9f);
            c.setScaleY(s * 0.9f);
            c.setTranslationY(home.dp(18));
            c.setAlpha(0f);
            c.animate().scaleX(s).scaleY(s).translationY(0).alpha(fade(k)).setStartDelay(Math.min(Math.abs(k - sel), 3) * 35L)
                    .setDuration(560).setInterpolator(Glass.SNAP).start();
        }
    }

    /**
     * Home's card shows Home as it is: a copy of its screen at card size, made by the GPU and
     * handed to the card off the main thread. Home's picture stands in until it arrives (or if
     * there's no copy to be had). Taken before the switcher is added, though its first frame is
     * see-through anyway.
     */
    void homeCard(ImageView card) {
        Drawable now = home.heroShown && home.heroFront.getDrawable() != null ? home.heroFront.getDrawable()
                : home.wallView.getDrawable();
        Drawable.ConstantState cs = now == null ? null : now.getConstantState();
        if (cs != null) card.setImageDrawable(cs.newDrawable(home.getResources()));
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        Bitmap snap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(home.getWindow(), snap, r -> {
                if (r != android.view.PixelCopy.SUCCESS || closing) return;
                new Thread(() -> {
                    Bitmap b = Suggestions.onGpu(snap);
                    home.runOnUiThread(() -> {
                        if (!closing) card.setImageBitmap(b);
                    });
                }, "AppSwitcher").start();
            }, home.handler);
        } catch (IllegalArgumentException e) {
            // no surface to copy from yet: the picture stays
        }
    }

    float rest(int k) {
        return k == sel ? 1f : 0.86f;
    }

    float fade(int k) {
        return k == sel ? 1f : 0.5f;
    }

    void move(int to) {
        int from = sel;
        sel = to;
        strip.animate().translationX(-to * pitch).setStartDelay(0).setDuration(520).setInterpolator(Glass.SNAP).start();
        for (int k : new int[]{from, to}) {
            cells.get(k).animate().scaleX(rest(k)).scaleY(rest(k)).translationY(0).alpha(fade(k)).setStartDelay(0)
                    .setDuration(460).setInterpolator(Glass.SNAP).start();
        }
    }

    /** At either end the row gives a little and springs back, as tvOS does. */
    void bump(int dir) {
        float at = -sel * pitch;
        strip.animate().translationX(at - dir * home.dp(10)).setStartDelay(0).setDuration(110)
                .setInterpolator(MainActivity.EASE)
                .withEndAction(() -> strip.animate().translationX(at).setDuration(420).setInterpolator(Glass.SNAP).start())
                .start();
    }

    /** Home's keys while the switcher is up. Volume and the like still reach the TV. */
    boolean key(KeyEvent ev) {
        int k = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        if (down && ev.getRepeatCount() == 0) pressed = k;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) {
                    if (sel > 0) move(sel - 1);
                    else if (ev.getRepeatCount() == 0) bump(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    if (sel < cells.size() - 1) move(sel + 1);
                    else if (ev.getRepeatCount() == 0) bump(1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Back into the app, growing out of its card. The switcher stays up underneath
                // until Home is out of sight (onStop), so Home doesn't flash in between. Home's
                // own card is Home, already underneath: the switcher just goes.
                if (!down && pressed == k) {
                    if (sel == apps.size()) close();
                    else home.launchAnimated(cards.get(sel), apps.get(sel));
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down && pressed == k) close();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    /** Out: the cards sink and fade, then the dimmed picture, and Home is back as it was. */
    void close() {
        if (closing) return;
        closing = true;
        home.switcher = null;
        for (View c : cells) {
            c.animate().scaleX(c.getScaleX() * 0.94f).scaleY(c.getScaleY() * 0.94f).alpha(0f).setStartDelay(0)
                    .setDuration(200).setInterpolator(MainActivity.EASE).start();
        }
        backdrop.animate().alpha(0f).setStartDelay(60).setDuration(260).setInterpolator(MainActivity.EASE)
                .withEndAction(() -> home.root.removeView(layer)).start();
        home.scheduleAdvance();
        // The focused dock app's own shelf, held back while the switcher was up.
        home.handler.removeCallbacks(home.shelfUpdate);
        home.handler.postDelayed(home.shelfUpdate, 260);
    }

    /** Gone at once: an app opened from it is now in front. */
    void dismissNow() {
        closing = true;
        home.switcher = null;
        home.root.removeView(layer);
    }
}

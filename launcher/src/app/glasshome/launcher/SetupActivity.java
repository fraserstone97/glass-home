package app.glasshome.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * First-time setup, in the style of Apple TV's own setup: one question per page, a large title
 * and a short line of help at the top, choices as a centred list of capsules (white when
 * focused) and the way on as a capsule button beneath them; Back goes back. Pages: Welcome,
 * background, Home Screen, weather, permissions, done. Shown once; Settings › Home Screen ›
 * Run Setup Again repeats it.
 */
public class SetupActivity extends Activity {
    static final String K_DONE = "setup_done";
    static final ArgbEvaluator EV = new ArgbEvaluator();

    FrameLayout root;
    ImageView backdrop;
    LinearLayout page;
    int index = 0;
    final Handler handler = new Handler(Looper.getMainLooper());

    interface Step {
        void build();
    }

    final List<Step> steps = new ArrayList<>();
    /** The pages that show what's been allowed or chosen elsewhere: re-drawn on coming back. */
    static final int WEATHER_STEP = 3, PERMISSIONS_STEP = 4;  // must match the order below

    static boolean done(Activity a) {
        return a.getSharedPreferences("setup", MODE_PRIVATE).getBoolean(K_DONE, false);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Clicks.init(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x66000000, 0xB3000000}));
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        page.setPadding(0, u(54), 0, u(28));
        root.addView(page, plp);
        setContentView(root);

        steps.add(this::hello);
        steps.add(this::look);
        steps.add(this::homeContent);
        steps.add(this::weather);
        steps.add(this::homeAndPermissions);
        steps.add(this::finish_);
        if (getIntent().getBooleanExtra("skip", false)) {  // adb: --ez skip true marks setup as done
            getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean(K_DONE, true).apply();
            finish();
            return;
        }
        show(getIntent().getIntExtra("step", 0), 0);  // adb: --ei step N opens a page directly
    }

    int u(float v) {
        return Ui.px(this, v);
    }

    // ---------------------------------------------------------------- navigation

    /** Focus is being placed by the page, not the remote: rows take it at once, already lit. */
    boolean snap;
    ObjectAnimator fade;

    void show(int i, int dir) {
        handler.removeCallbacksAndMessages(null);
        index = Math.max(0, Math.min(i, steps.size() - 1));
        page.animate().cancel();
        if (fade != null) fade.cancel();
        page.removeAllViews();
        snap = true;
        steps.get(index).build();
        snap = false;
        if (dir == 0) {
            // The same page re-drawn after a change: a quick fade only.
            page.setTranslationX(0);
            page.setAlpha(0.6f);
            page.animate().alpha(1f).setDuration(120).setInterpolator(SettingsActivity.LINEAR).start();
            return;
        }
        // A new page fades in over 300 ms as it settles from the side you're heading, on the
        // Apple TV app's page curve.
        page.setAlpha(0f);
        page.setTranslationX(u(30) * dir);
        page.animate().translationX(0).setDuration(500).setInterpolator(SettingsActivity.SLIDE).start();
        fade = ObjectAnimator.ofFloat(page, View.ALPHA, 1f);
        fade.setDuration(300);
        fade.setInterpolator(SettingsActivity.LINEAR);
        fade.start();
    }

    void next() {
        show(index + 1, 1);
    }

    /** The same page again after a change (a switch, a permission, a city), with focus where it was. */
    void redraw() {
        View list = page.findViewWithTag("list");
        View f = getCurrentFocus();
        int at = list instanceof ViewGroup && f != null ? ((ViewGroup) list).indexOfChild(f) : -1;
        show(index, 0);
        focusRow(at);
    }

    /** Puts focus on the list's row `i` (the last one if there are fewer now). */
    void focusRow(int i) {
        View v = page.findViewWithTag("list");
        if (i < 0 || !(v instanceof LinearLayout) || ((LinearLayout) v).getChildCount() == 0) return;
        LinearLayout l = (LinearLayout) v;
        snap = true;
        l.getChildAt(Math.min(i, l.getChildCount() - 1)).requestFocus();
        snap = false;
    }

    @Override
    public void onBackPressed() {
        if (index > 0) show(index - 1, -1);
        // The first page has nowhere to go back to: setup stays until it is finished.
    }

    /** Away to a permission prompt, the Home role's dialog or Settings (Choose a City). */
    boolean paused;

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from it: show what was allowed or chosen there.
        if (paused && (index == WEATHER_STEP || index == PERMISSIONS_STEP)) redraw();
        paused = false;
    }

    // ---------------------------------------------------------------- building blocks

    TextView text(String s, float size, String weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        Ui.textSize(t, size);
        t.setTypeface(Ui.font(this, weight));
        t.setTextColor(color);
        return t;
    }

    TextView title(String s) {
        TextView t = text(s, 34, "display", Color.WHITE);
        t.setLetterSpacing(-0.02f);
        t.setGravity(Gravity.CENTER);
        page.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    TextView subtitle(String s) {
        TextView t = text(s, 14, "regular", 0xB3FFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(520), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = u(10);
        lp.bottomMargin = u(26);
        page.addView(t, lp);
        return t;
    }

    LinearLayout list() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        if (page.findViewWithTag("list") == null) l.setTag("list");
        page.addView(l, new LinearLayout.LayoutParams(u(380), ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    /**
     * A capsule that grows 4 px each way and turns white when focused, the words staying the same
     * size; `words` darken with it. Focus placed while a page is built arrives already lit.
     */
    Glass capsule(View v, long inMs, long outMs, TextView... words) {
        Glass bg = new Glass(u(40), u(0.5f), Glass.REGULAR);
        bg.grow = u(2);
        int[] rest = new int[words.length];
        for (int i = 0; i < words.length; i++) rest[i] = words[i].getCurrentTextColor();
        bg.tint = f -> {
            for (int i = 0; i < words.length; i++) {
                // Secondary words keep their transparency on the white plate.
                words[i].setTextColor((int) EV.evaluate(f, rest[i], (rest[i] & 0xFF000000) | 0x111114));
            }
        };
        v.setBackground(bg);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((x, has) -> {
            if (snap) {
                if (bg.anim != null) bg.anim.cancel();
                bg.setFocus(has ? 1f : 0f);
            } else {
                Glass.focus(bg, has, inMs, outMs, SettingsActivity.ROW_IN, SettingsActivity.ROW_OUT);
            }
        });
        return bg;
    }

    /** A choice row: title, optional detail and a tick (in the title's colour), as Settings' rows. */
    View option(LinearLayout list, String label, String detail, boolean checked, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(u(18), 0, u(18), 0);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(label, 16, "medium", Color.WHITE);
        texts.addView(l);
        TextView d = null;
        if (detail != null && !detail.isEmpty()) {
            d = text(detail, 12, "regular", 0x99FFFFFF);
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(d);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tick = text(checked ? "✓" : "", 17, "semibold", Color.WHITE);
        r.addView(tick);
        if (d != null) capsule(r, 100, 200, l, d, tick);
        else capsule(r, 100, 200, l, tick);
        r.setOnClickListener(v -> action.run());
        // 66 px capsules (84 with a detail line) on tvOS's 80 px pitch, with room to grow.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                d == null ? u(37) : u(46));
        lp.bottomMargin = u(3);
        list.addView(r, lp);
        if (list.getChildCount() == 1) r.requestFocus();
        return r;
    }

    /** The way on (Get Started, Continue, Done): a capsule button centred under the choices. */
    View button(LinearLayout list, String label, Runnable action) {
        TextView b = text(label, 15.5f, "semibold", Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        capsule(b, 75, 150, b);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(300), u(37));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        if (list.getChildCount() > 0) lp.topMargin = u(12);
        list.addView(b, lp);
        if (list.getChildCount() == 1) b.requestFocus();
        return b;
    }

    void dots() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 1; i < steps.size() - 1; i++) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == index ? Color.WHITE : 0x59FFFFFF);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(6), u(6));
            lp.leftMargin = lp.rightMargin = u(4);
            d.addView(dot, lp);
        }
        View spacer = new View(this);
        page.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        page.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    void setBackdrop(String style) {
        Bitmap b = MainActivity.makeWallpaperBase(style);
        backdrop.setImageDrawable(new BitmapDrawable(getResources(), b));
        backdrop.animate().alpha(1f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- pages

    /** Welcome, then Get Started. */
    void hello() {
        backdrop.animate().alpha(0f).setDuration(300).start();
        View top = new View(this);
        page.addView(top, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView hi = title("Welcome");
        Ui.textSize(hi, 64);
        hi.setAlpha(0f);
        hi.setTranslationY(u(12));
        hi.animate().alpha(1f).translationY(0).setDuration(700).start();
        LinearLayout l = list();
        button(l, "Get Started", this::next);
        View mid = new View(this);
        page.addView(mid, new LinearLayout.LayoutParams(1, 0, 1f));
    }

    /** Backgrounds preview live behind the page as you move through them. */
    void look() {
        title("Choose a Background");
        subtitle("It appears behind your apps.");
        String cur = Profiles.homePrefs(this).getString("wallpaper", "night");
        setBackdrop(cur);
        LinearLayout l = list();
        for (int i = 0; i < MainActivity.WALL_KEYS.length; i++) {
            String key = MainActivity.WALL_KEYS[i];
            View r = option(l, MainActivity.WALL_NAMES[i], null, key.equals(cur), () -> {
                Profiles.homePrefs(this).edit().putString("wallpaper", key).apply();
                next();
            });
            View.OnFocusChangeListener base = r.getOnFocusChangeListener();
            r.setOnFocusChangeListener((v, has) -> {
                base.onFocusChange(v, has);
                if (has) setBackdrop(key);
            });
            if (key.equals(cur)) r.requestFocus();
        }
        dots();
    }

    /** What the top of the Home Screen shows. */
    void homeContent() {
        backdrop.animate().alpha(0.5f).setDuration(300).start();
        title("Home Screen");
        subtitle("Choose what appears at the top.");
        boolean recs = Suggestions.showRecs(this);
        LinearLayout l = list();
        option(l, "Recommendations", "What’s popular on your apps.", recs, () -> {
            Suggestions.setShelf(this, recs ? Suggestions.SHELF_NONE : Suggestions.SHELF_RECS);
            redraw();
        });
        button(l, "Continue", this::next);
        dots();
    }

    /** The city the TV's internet address puts it in has been looked up (once per setup). */
    boolean lookedUpCity;

    void weather() {
        title("Weather");
        subtitle("See the forecast on the Home Screen.");
        LinearLayout l = list();
        boolean manual = Weather.manual(this);
        String city = manual ? "" : Weather.place(this);
        option(l, "Use My Location", city.isEmpty() ? null : city, Weather.enabled(this) && !manual, () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, true).apply();
            Weather.setPlace(this, null);
            next();
        });
        option(l, "Choose a City…", manual ? Weather.place(this) : null, manual, () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, true).apply();
            startActivity(new Intent(this, SettingsActivity.class).putExtra("page", SettingsActivity.WEATHER));
        });
        option(l, "Not Now", null, !Weather.enabled(this), () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, false).apply();
            Weather.changed(this);
            next();
        });
        // On with what's ticked: the only way past a city chosen in Settings that keeps it.
        button(l, "Continue", this::next);
        // Shows the city it finds. Asked only once: when it can't be found (offline, or the
        // look-up services busy) the page would otherwise ask again on every re-draw, for ever.
        if (!manual && city.isEmpty() && !lookedUpCity) {
            lookedUpCity = true;
            Weather.refresh(this, true, () -> {
                if (index == WEATHER_STEP && !isDestroyed()) redraw();
            });
        }
        dots();
    }

    boolean askedLocation;

    void homeAndPermissions() {
        // Ask right away: Android's own prompt for location (the Wi-Fi name in Control Centre).
        if (!Perms.hasLocation(this) && !askedLocation) {
            askedLocation = true;
            handler.postDelayed(() -> Perms.requestLocation(this), 600);
        }
        title("Almost Done");
        subtitle("Allow these so everything works.");
        LinearLayout l = list();
        boolean home = Perms.isDefaultHome(this);
        option(l, "Use as Home Screen", home ? "On" : "Opens with the Home button.", home,
                () -> Perms.requestDefaultHome(this));
        if (!Perms.hasLocation(this)) {
            option(l, "Show Wi-Fi Name", "Needs location access.", false,
                    () -> Perms.requestLocation(this));
        }
        if (Perms.adminSupported(this) && !SleepReceiver.canSleep(this)) {
            option(l, "Sleep Timer", "Lets " + getString(R.string.app_name) + " turn off the TV.", false, () -> Perms.requestAdmin(this));
        }
        button(l, "Continue", this::next);
        dots();
    }

    void finish_() {
        backdrop.animate().alpha(0f).setDuration(300).start();
        View top = new View(this);
        page.addView(top, new LinearLayout.LayoutParams(1, 0, 1f));
        int cs = u(64);
        Bitmap cb = Bitmap.createBitmap(cs, cs, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cc = new android.graphics.Canvas(cb);
        android.graphics.Paint cp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        cp.setColor(0xFF30D158);
        cc.drawCircle(cs / 2f, cs / 2f, cs / 2f, cp);
        cp.setColor(Color.WHITE);
        cp.setStyle(android.graphics.Paint.Style.STROKE);
        cp.setStrokeWidth(cs * 0.09f);
        cp.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        cp.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        android.graphics.Path tick = new android.graphics.Path();
        tick.moveTo(cs * 0.29f, cs * 0.52f);
        tick.lineTo(cs * 0.44f, cs * 0.67f);
        tick.lineTo(cs * 0.72f, cs * 0.37f);
        cc.drawPath(tick, cp);
        ImageView check = new ImageView(this);
        check.setImageBitmap(cb);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(u(64), u(64));
        clp.bottomMargin = u(18);
        page.addView(check, clp);
        title("You’re All Set");
        subtitle("You can change this anytime in Settings.");
        LinearLayout l = list();
        button(l, "Done", () -> {
            getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean(K_DONE, true).apply();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            // Home stays still while Setup fades away, as every page closes.
            overridePendingTransition(R.anim.hold, R.anim.page_out);
        });
        View bottom = new View(this);
        page.addView(bottom, new LinearLayout.LayoutParams(1, 0, 1.4f));
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent ev) {
        MainActivity.navSound(this, ev);
        return super.dispatchKeyEvent(ev);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

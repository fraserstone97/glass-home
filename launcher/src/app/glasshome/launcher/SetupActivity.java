package app.glasshome.launcher;

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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * First-time setup, in the style of Apple TV's own setup: one question per page, a large title
 * and a short line of help at the top, choices as a centred list (white when focused), OK moves
 * on and Back goes back. Pages: Welcome, background, Home Screen, weather,
 * home screen & permissions, done. Shown once; Settings \u2192 Home \u2192 Run Setup Again repeats it.
 */
public class SetupActivity extends Activity {
    static final String K_DONE = "setup_done";

    FrameLayout root;
    ImageView backdrop;
    LinearLayout page;
    int index = 0;
    final Handler handler = new Handler(Looper.getMainLooper());

    interface Step {
        void build();
    }

    final List<Step> steps = new ArrayList<>();

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

    void show(int i, int dir) {
        handler.removeCallbacksAndMessages(null);
        index = Math.max(0, Math.min(i, steps.size() - 1));
        page.animate().cancel();
        page.removeAllViews();
        steps.get(index).build();
        // New page: slide and fade. Same page re-drawn after a switch: a quick fade only.
        page.setAlpha(dir == 0 ? 0.6f : 0f);
        page.setTranslationX(u(30) * dir);
        page.animate().alpha(1f).translationX(0).setDuration(dir == 0 ? 120 : 320).start();
    }

    void next() {
        show(index + 1, 1);
    }

    @Override
    public void onBackPressed() {
        if (index > 0) show(index - 1, -1);
        // The first page has nowhere to go back to: setup stays until it is finished.
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (index == 6) show(6, 0);  // refresh the ticks
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (index == 6) show(6, 0);
    }

    // ---------------------------------------------------------------- building blocks

    TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Ui.font(this, "display"));
        t.setLetterSpacing(-0.02f);
        Ui.textSize(t, 34);
        t.setGravity(Gravity.CENTER);
        page.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    TextView subtitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xB3FFFFFF);
        t.setTypeface(Ui.font(this, "regular"));
        Ui.textSize(t, 14);
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
        page.addView(l, new LinearLayout.LayoutParams(u(440), ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    /** A choice row: title, optional detail and a tick; white with dark text when focused. */
    View option(LinearLayout list, String label, String detail, boolean checked, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(u(18), u(8), u(16), u(8));
        Glass bg = new Glass(u(12), u(0.5f), Glass.REGULAR);
        r.setBackground(bg);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.WHITE);
        l.setTypeface(Ui.font(this, "medium"));
        Ui.textSize(l, 15);
        texts.addView(l);
        TextView d = null;
        if (detail != null && !detail.isEmpty()) {
            d = new TextView(this);
            d.setText(detail);
            d.setTextColor(0x99FFFFFF);
            d.setTypeface(Ui.font(this, "regular"));
            Ui.textSize(d, 12);
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(d);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tick = new TextView(this);
        tick.setText(checked ? "\u2713" : "");
        tick.setTextColor(0xFF30D158);
        tick.setTypeface(Ui.font(this, "semibold"));
        Ui.textSize(tick, 17);
        r.addView(tick);
        TextView dRef = d;
        r.setOnFocusChangeListener((v, has) -> {
            Glass.focus(bg, has);
            android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
            android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofFloat(has ? 0f : 1f, has ? 1f : 0f);
            fade.setDuration(has ? 200 : 260);
            fade.setInterpolator(MainActivity.EASE);
            fade.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                l.setTextColor((int) ev.evaluate(t, Color.WHITE, 0xFF111114));
                if (dRef != null) dRef.setTextColor((int) ev.evaluate(t, 0x99FFFFFF, 0x99111114));
                tick.setTextColor((int) ev.evaluate(t, 0xFF30D158, 0xFF1E9E48));
            });
            fade.start();
            Glass.lift(v, has, 1.04f, 0);
        });
        r.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                d == null ? u(46) : u(56));
        lp.bottomMargin = u(8);
        list.addView(r, lp);
        if (list.getChildCount() == 1) r.requestFocus();
        return r;
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
        option(l, "Get Started", null, false, this::next);
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
        option(l, "Recommendations", "What\u2019s popular on your apps.", recs, () -> {
            Suggestions.setShelf(this, !recs);
            show(index, 0);
            focusRow(0);
        });
        option(l, "Continue", null, false, this::next);
        dots();
    }

    /** After re-building a page, put focus back on the row that was pressed. */
    void focusRow(int i) {
        View v = page.findViewWithTag("list");
        if (v instanceof LinearLayout && i < ((LinearLayout) v).getChildCount()) ((LinearLayout) v).getChildAt(i).requestFocus();
    }

    void weather() {
        title("Weather");
        subtitle("See the forecast on the Home Screen.");
        LinearLayout l = list();
        String city = Weather.place(this);
        option(l, "Use My Location", city.isEmpty() ? null : city,
                Weather.enabled(this) && !Weather.manual(this), () -> {
                    Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, true).apply();
                    Weather.setPlace(this, null);
                    next();
                });
        option(l, "Choose a City\u2026", Weather.manual(this) ? Weather.place(this) : null, Weather.manual(this), () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, true).apply();
            Intent i = new Intent(this, SettingsActivity.class).putExtra("page", SettingsActivity.WEATHER);
            startActivity(i);
        });
        option(l, "Not Now", null, !Weather.enabled(this), () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, false).apply();
            Weather.changed(this);
            next();
        });
        if (city.isEmpty()) Weather.refresh(this, true, () -> {
            if (index == 3) show(3, 0);  // show the detected city
        });
        dots();
    }


    boolean askedLocation;

    void homeAndPermissions() {
        // Ask right away: Android's own prompt for location (the Wi-Fi name in Control Center).
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
        if (Perms.adminSupported(this) && !Perms.hasAdmin(this)) {
            option(l, "Sleep Timer", "Lets " + getString(R.string.app_name) + " turn off the TV.", false, () -> Perms.requestAdmin(this));
        }
        option(l, "Continue", null, false, this::next);
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
        title("You\u2019re All Set");
        subtitle("You can change this anytime in Settings.");
        LinearLayout l = list();
        option(l, "Done", null, false, () -> {
            getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean(K_DONE, true).apply();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
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

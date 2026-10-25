package app.glasshome.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * tvOS-style settings: a main list of sections, each opening its own page, with the page's icon
 * and title on the left and its rows on the right; Back returns to the main list.
 *
 * A TMDB token can also be supplied over adb:
 *   adb shell am start -n app.glasshome.launcher/.SettingsActivity --es tmdb_token &lt;token&gt;
 */
public class SettingsActivity extends Activity {

    LinearLayout list;
    final List<Runnable> refreshers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Clicks.init(this);
        String token = getIntent().getStringExtra("tmdb_token");
        if (token != null && !token.trim().isEmpty()) {
            Suggestions.prefs(this).edit().putString(Suggestions.K_TOKEN, token.trim()).apply();
            Suggestions.changed(this);
            Toast.makeText(this, "TMDB connected.", Toast.LENGTH_SHORT).show();
        }
        build();
        String pg = getIntent().getStringExtra("page");  // adb: --es page "Top Shelf"
        if (pg != null) show(pg);
    }

    // tvOS-style pages: the main list opens a page per section; Back returns to the main list.
    static final String ROOT = "root", SHELF = "Top Shelf",
            INPUTS = "Inputs", WEATHER = "Weather", HOME = "Home Screen",
            PERMS = "Permissions", ABOUT = "About", SAVER = "Screen Saver";

    FrameLayout leftPane;
    ScrollView scroller;
    String page = ROOT, lastPage = null;
    final java.util.Map<String, android.view.View> navRows = new java.util.HashMap<>();

    void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B10);
        // Over Home's last picture, blurred right down and dimmed further (the app drawer's glass):
        // Settings reads as a layer over Home, in Home's colours, rather than a black page.
        MainActivity home = MainActivity.live.get();
        android.graphics.Bitmap glass = home == null ? null : home.heroDrawer != null ? home.heroDrawer : home.wallBlur;
        if (glass != null && !glass.isRecycled()) {
            ImageView back = new ImageView(this);
            back.setScaleType(ImageView.ScaleType.CENTER_CROP);
            back.setImageBitmap(glass);
            back.setColorFilter(0x94000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
            root.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // It covers the screen: no fills drawn underneath it every frame.
            root.setBackground(null);
            getWindow().setBackgroundDrawable(null);
        }
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(split, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        leftPane = new FrameLayout(this);
        split.addView(leftPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setClipChildren(false);
        scroller.setPadding(0, Ui.px(this, 60), Ui.px(this, 44), Ui.px(this, 60));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(Ui.px(this, 12), 0, Ui.px(this, 12), 0);
        scroller.addView(list);
        split.addView(scroller, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f));
        setContentView(root);
        show(ROOT);
    }

    /** Builds one page: its icon and title on the left, its rows on the right. */
    void show(String p) {
        lastPage = page;
        page = p;
        list.removeAllViews();
        refreshers.clear();
        navRows.clear();
        rowsByLabel.clear();
        buildLeft(p, p);
        switch (p) {
            case SHELF: pageShelf(); break;
            case INPUTS: pageInputs(); break;
            case WEATHER: pageWeather(); break;
            case HOME: pageHome(); break;
            case SAVER: pageSaver(); break;
            case PERMS: pagePermissions(); break;
            case ABOUT: pageAbout(); break;
            default: pageRoot(); break;
        }
        scroller.scrollTo(0, 0);
        // Returning to the main list puts focus back on the section you came from.
        android.view.View focus = p.equals(ROOT) && lastPage != null ? navRows.get(lastPage) : null;
        if (focus == null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).isFocusable()) {
                    focus = list.getChildAt(i);
                    break;
                }
            }
        }
        if (focus != null) focus.requestFocus();
        // The first page arrives with the window itself (PageTheme); later ones slide.
        if (shown) slideIn(!p.equals(ROOT));
        shown = true;
    }

    boolean shown;

    /** Pages slide in from the side you're heading (deeper: from the right; back: from the left). */
    void slideIn(boolean deeper) {
        list.setAlpha(0f);
        list.setTranslationX(Ui.px(this, deeper ? 24 : -24));
        list.animate().alpha(1f).translationX(0).setDuration(380).setInterpolator(MainActivity.EASE).withLayer().start();
        leftPane.setAlpha(0f);
        leftPane.animate().alpha(1f).setDuration(320).setInterpolator(MainActivity.EASE).start();
    }

    // ---------------------------------------------------------------- choice pages

    /** The page a choice page was opened from, and the row that opened it (for Back). */
    String choiceFrom, choiceRow;
    final java.util.Map<String, android.view.View> rowsByLabel = new java.util.HashMap<>();

    interface Pick {
        void picked(int which);
    }

    interface Toggle {
        /** False refuses the change (for example, turning off the last app). */
        boolean toggled(int which, boolean on);
    }

    /** Opens a list of options, as on Apple TV: a tick on the current one; picking moves it. */
    void choose(String title, String[] names, int selected, Pick pick) {
        int[] cur = {selected};
        openChoice(title);
        android.view.View sel = null;
        for (int i = 0; i < names.length; i++) {
            int w = i;
            android.view.View r = row(names[i], () -> cur[0] == w ? "\u2713" : "", () -> {
                cur[0] = w;
                pick.picked(w);
            });
            if (i == selected) sel = r;
        }
        focusChoice(sel);
    }

    /** A list of switches: each row ticks on or off. */
    void chooseMany(String title, String[] names, boolean[] on, Toggle toggle) {
        openChoice(title);
        for (int i = 0; i < names.length; i++) {
            int w = i;
            row(names[i], () -> on[w] ? "\u2713" : "", () -> {
                if (toggle.toggled(w, !on[w])) on[w] = !on[w];
            });
        }
        focusChoice(null);
    }

    void openChoice(String title) {
        // A choice opened from another choice (city results after Location) still returns to the section.
        if (!page.equals("choice")) {
            choiceFrom = page;
            choiceRow = title;
        }
        lastPage = page;
        page = "choice";
        list.removeAllViews();
        refreshers.clear();
        navRows.clear();
        rowsByLabel.clear();
        buildLeft(choiceFrom, title);
    }

    void focusChoice(android.view.View sel) {
        if (sel == null && list.getChildCount() > 0) sel = list.getChildAt(0);
        if (sel != null) sel.requestFocus();
        scroller.scrollTo(0, 0);
        slideIn(true);
    }

    @Override
    public void onBackPressed() {
        if (page.equals("choice")) {
            String row = choiceRow;
            show(choiceFrom);
            android.view.View r = rowsByLabel.get(row);
            if (r != null) r.requestFocus();
            slideIn(false);
        } else if (!page.equals(ROOT)) {
            show(ROOT);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String pg = i.getStringExtra("page");
        show(pg != null ? pg : ROOT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();  // e.g. a permission granted on a system screen
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refreshAll();
    }

    /** Left side: the page's icon and its title. */
    void buildLeft(String p, String heading) {
        leftPane.removeAllViews();
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        android.view.View icon;
        if (p.equals(ROOT)) {
            // A silver tile with a gear, like the Settings icon on Apple TV.
            ImageView tile = new ImageView(this);
            tile.setImageBitmap(MainActivity.settingsArt(Ui.px(this, 150), Ui.px(this, 90)));
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(Ui.px(this, 12));
            tile.setBackground(shape);
            tile.setClipToOutline(true);
            icon = tile;
        } else {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(pageIcon(p, Ui.px(this, 150), Ui.px(this, 90)));
            iv.setClipToOutline(true);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(Ui.px(this, 12));
            iv.setBackground(shape);
            icon = iv;
        }
        icon.setElevation(Ui.px(this, 8));
        left.addView(icon, new LinearLayout.LayoutParams(Ui.px(this, 150), Ui.px(this, 90)));

        TextView title = new TextView(this);
        title.setText(p.equals(ROOT) ? "Settings" : heading);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Ui.font(this, "display"));
        title.setLetterSpacing(-0.02f);
        Ui.textSize(title, p.equals(ROOT) ? 34 : 28);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.px(this, 22);
        left.addView(title, tlp);

        leftPane.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /** A 5:3 icon for a section page: a gradient tile with a white glyph (or the section's own art). */
    android.graphics.Bitmap pageIcon(String p, int w, int h) {
        int[] grad;
        String glyph;
        switch (p) {
            case SHELF: grad = new int[]{0xFF64D2FF, 0xFF0A84FF}; glyph = "shelf"; break;
            case INPUTS: grad = new int[]{0xFF5A6478, 0xFF1C212C}; glyph = "hdmi"; break;
            case WEATHER: grad = new int[]{0xFF5AC8FA, 0xFF2D6BD8}; glyph = null; break;
            case HOME: grad = new int[]{0xFF30D158, 0xFF1E9E48}; glyph = "house"; break;
            case SAVER: grad = new int[]{0xFF5E5CE6, 0xFF2C2A8C}; glyph = "sparkles"; break;
            case PERMS: grad = new int[]{0xFF8E8E93, 0xFF48484A}; glyph = "shield"; break;
            default: grad = new int[]{0xFF8E8E93, 0xFF3A3A3C}; glyph = "gear"; break;
        }
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.LinearGradient(0, 0, 0, h, grad[0], grad[1], android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        android.graphics.Bitmap g;
        if (p.equals(WEATHER)) g = WeatherIcons.get(0, true, (int) (h * 0.7f));
        else g = Glyphs.get(glyph, (int) (h * 0.56f), Color.WHITE);
        c.drawBitmap(g, (w - g.getWidth()) / 2f, (h - g.getHeight()) / 2f, null);
        return b;
    }

    // ---------------------------------------------------------------- pages

    void pageRoot() {
        nav(SHELF, () -> {
            return Suggestions.showRecs(this) ? "Recommendations" : "Off";
        });
        if (!Inputs.list(this).isEmpty()) nav(INPUTS, () -> Inputs.showCard(this) ? "On" : "Off");
        nav(WEATHER, () -> !Weather.enabled(this) ? "Off" : Weather.place(this).isEmpty() ? "On" : Weather.place(this));
        nav(HOME, () -> {
            String k = Profiles.homePrefs(this).getString("wallpaper", "night");
            int i = java.util.Arrays.asList(MainActivity.WALL_KEYS).indexOf(k);
            return MainActivity.WALL_NAMES[Math.max(0, i)];
        });
        nav(SAVER, this::dreamName);
        nav(PERMS, () -> {
            int missing = (Perms.hasLocation(this) ? 0 : 1)
                    + (Perms.hasAdmin(this) || !Perms.adminSupported(this) ? 0 : 1) + (Perms.isDefaultHome(this) ? 0 : 1);
            return missing == 0 ? "All Allowed" : missing + " Not Allowed";
        });
        nav(ABOUT, () -> "Version " + version());
        row("TV Settings", () -> "", () -> Suggestions.start(this, new Intent(Settings.ACTION_SETTINGS)));
    }


    void pageShelf() {
        row("Show", () -> {
            return Suggestions.showRecs(this) ? "Recommendations" : "Off";
        }, () -> {
            String[] keys = {Suggestions.SHELF_RECS, Suggestions.SHELF_NONE};
            String[] names = {"Recommendations", "Off"};
            int sel = Math.max(0, java.util.Arrays.asList(keys).indexOf(Suggestions.shelf(this)));
            choose("Show", names, sel, w -> {
                Suggestions.setShelf(this, keys[w]);
            });
        });
        if (Suggestions.showRecs(this)) {
            section("Recommendations");
            row("Source", () -> Suggestions.source(this).equals(Suggestions.SRC_TMDB) ? "TMDB" : "JustWatch", this::chooseSource);
            row("Content", () -> {
                String t = Suggestions.types(this);
                return t.equals("movie") ? "Movies" : t.equals("tv") ? "TV Shows" : "Movies and TV Shows";
            }, this::chooseTypes);
            row("Apps", () -> {
                int n = Suggestions.providers(this).size();
                return n == Suggestions.PROVIDERS.size() ? "All" : n + " Apps";
            }, this::chooseProviders);
            if (Suggestions.source(this).equals(Suggestions.SRC_TMDB)) row("Languages", () -> {
                Set<String> l = Suggestions.languages(this);
                if (l.isEmpty()) return "Any";
                List<String> names = new ArrayList<>();
                for (int i = 0; i < Suggestions.LANG_CODES.length; i++) {
                    if (l.contains(Suggestions.LANG_CODES[i])) names.add(Suggestions.LANG_NAMES[i]);
                }
                return names.size() > 2 ? names.size() + " languages" : String.join(", ", names);
            }, this::chooseLanguages);
            if (Suggestions.source(this).equals(Suggestions.SRC_TMDB)) {
                row("TMDB Token", () -> Suggestions.hasToken(this) ? "Connected" : "Not Set", this::enterToken);
            }
        }
        if (Suggestions.enabled(this)) {
            section("Carousel");
            row("Advance Automatically", () -> {
                int sec = Suggestions.advanceSeconds(this);
                return sec == 0 ? "Off" : "Every " + sec + " Seconds";
            }, this::chooseAdvance);
            row("Refresh Now", () -> "", () -> {
                Suggestions.changed(this);
                Toast.makeText(this, "Refreshing.", Toast.LENGTH_SHORT).show();
            });
        }
    }



    void pageInputs() {
        row("Show on Home Screen", () -> Inputs.showCard(this) ? "On" : "Off", () ->
                Inputs.prefs(this).edit().putBoolean(Inputs.K_CARD, !Inputs.showCard(this)).apply());
        section("This TV");
        for (Inputs.Input in : Inputs.list(this)) {
            row(in.label, () -> in.disconnected ? "Not Connected" : "", () -> {
                if (!Inputs.open(this, in)) Toast.makeText(this, "Can't switch to " + in.label, Toast.LENGTH_SHORT).show();
            });
        }
    }

    void pageWeather() {
        row("Show Weather", () -> Weather.enabled(this) ? "On" : "Off", () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, !Weather.enabled(this)).apply();
            Weather.changed(this);
        });
        row("Location", () -> Weather.manual(this) ? Weather.place(this)
                : Weather.place(this).isEmpty() ? "Automatic" : "Automatic \u00b7 " + Weather.place(this), this::chooseLocation);
        row("Temperature", () -> {
            String u = Weather.prefs(this).getString(Weather.K_UNITS, "auto");
            return u.equals("c") ? "Celsius" : u.equals("f") ? "Fahrenheit" : "Automatic";
        }, () -> {
            String[] keys = {"auto", "c", "f"};
            String[] names = {"Automatic", "Celsius (\u00b0C)", "Fahrenheit (\u00b0F)"};
            String cur = Weather.prefs(this).getString(Weather.K_UNITS, "auto");
            int sel = cur.equals("c") ? 1 : cur.equals("f") ? 2 : 0;
            choose("Temperature", names, sel, w -> {
                Weather.prefs(this).edit().putString(Weather.K_UNITS, keys[w]).apply();
                Weather.changed(this);
                refreshAll();
            });
        });
        section("Updates");
        row("Source", () -> Weather.sourceName(Weather.source(this)), () -> {
            String[] keys = Weather.SOURCES;
            String[] names = {"Met Office", "Open-Meteo", "MET Norway", "wttr.in"};
            int sel = java.util.Arrays.asList(keys).indexOf(Weather.source(this));
            choose("Source", names, Math.max(0, sel), w -> {
                Weather.prefs(this).edit().putString(Weather.K_SOURCE, keys[w]).apply();
                Weather.changed(this);
                refreshAll();
                Toast.makeText(this, "If it\u2019s unavailable, another source is used.", Toast.LENGTH_LONG).show();
            });
        });
        row("Update Every", () -> Weather.refreshMinutes(this) + " Minutes", () -> {
            int[] mins = Weather.REFRESH_CHOICES;
            String[] names = new String[mins.length];
            int sel = 1;
            for (int i = 0; i < mins.length; i++) {
                names[i] = mins[i] + " Minutes";
                if (mins[i] == Weather.refreshMinutes(this)) sel = i;
            }
            choose("Update Every", names, sel, w -> {
                Weather.prefs(this).edit().putInt(Weather.K_REFRESH, mins[w]).apply();
                refreshAll();
            });
        });
    }

    void pageHome() {
        row("Default Home Screen", () -> Perms.isDefaultHome(this) ? getString(R.string.app_name) : "Set as Default\u2026",
                () -> Perms.requestDefaultHome(this));
        row("Background", () -> {
            String k = Profiles.homePrefs(this).getString("wallpaper", "night");
            int i = java.util.Arrays.asList(MainActivity.WALL_KEYS).indexOf(k);
            return MainActivity.WALL_NAMES[Math.max(0, i)];
        }, () -> {
            String k = Profiles.homePrefs(this).getString("wallpaper", "night");
            int cur = Math.max(0, java.util.Arrays.asList(MainActivity.WALL_KEYS).indexOf(k));
            choose("Background", MainActivity.WALL_NAMES, cur, w -> {
                Profiles.homePrefs(this).edit().putString("wallpaper", MainActivity.WALL_KEYS[w]).apply();
                refreshAll();
            });
        });
        row("Navigation Clicks", () -> Clicks.enabled(this) ? "On" : "Off",
                () -> Clicks.setEnabled(this, !Clicks.enabled(this)));
        row("Full Screen When Idle", () -> {
            int sec = Profiles.homePrefs(this).getInt("idle_sec", 20);
            return sec == 0 ? "Off" : sec >= 60 ? "After " + sec / 60 + (sec == 60 ? " Minute" : " Minutes")
                    : "After " + sec + " Seconds";
        }, () -> {
            int[] secs = {0, 10, 20, 30, 60};
            String[] names = {"Off", "After 10 Seconds", "After 20 Seconds", "After 30 Seconds", "After 1 Minute"};
            int cur = Profiles.homePrefs(this).getInt("idle_sec", 20), sel = 2;
            for (int i = 0; i < secs.length; i++) if (secs[i] == cur) sel = i;
            choose("Full Screen When Idle", names, sel, w -> {
                Profiles.homePrefs(this).edit().putInt("idle_sec", secs[w]).apply();
                refreshAll();
            });
        });
        section("Setup");
        row("Run Setup Again", () -> "", () -> startActivity(new Intent(this, SetupActivity.class)));
        row("Reset Home Screen Layout", () -> "", () -> {
            Profiles.homePrefs(this).edit().remove("order").apply();
            Toast.makeText(this, "Layout reset.", Toast.LENGTH_SHORT).show();
        });
    }

    void pagePermissions() {
        row("Sleep Timer", () -> Perms.hasAdmin(this) ? "Allowed"
                : Perms.adminSupported(this) ? "Allow\u2026" : "Not on this TV", () -> Perms.requestAdmin(this));
        row("Home Screen", () -> Perms.isDefaultHome(this) ? "Allowed" : "Allow\u2026",
                () -> Perms.requestDefaultHome(this));
        row("Wi-Fi Name", () -> Perms.hasLocation(this) ? "Allowed" : "Allow\u2026",
                () -> Perms.requestLocation(this));
    }

    void pageAbout() {
        row(getString(R.string.app_name), () -> "Version " + version(), () -> { });
        row("Weather", () -> Weather.sourceName(Weather.source(this)), () -> { });
        row("Artwork", () -> Suggestions.hasToken(this) ? "TMDB" : "JustWatch", () -> { });
        if (Suggestions.hasToken(this)) {
            // TMDB's terms: credit, and say it isn't their product.
            TextView n = new TextView(this);
            n.setText("This product uses the TMDB API but is not endorsed or certified by TMDB.");
            n.setTextColor(0x8CFFFFFF);
            Ui.textSize(n, 11.5f);
            n.setPadding(Ui.px(this, 14), Ui.px(this, 10), Ui.px(this, 14), 0);
            list.addView(n);
        }
    }

    // ---------------------------------------------------------------- screen saver

    /** The TV's own screen saver settings, read and written directly (tvOS holds the permission). */
    static final String DREAM_ON = "screensaver_enabled", DREAM = "screensaver_components";
    static final String AERIALS = "app.glasshome.aerials/app.glasshome.aerials.AerialDream";

    void pageSaver() {
        row("Screen Saver", this::dreamName, this::chooseDream);
        if (!dreamOn()) return;
        row("Start After", () -> after(System("screen_off_timeout", 600000)), () -> {
            int[] mins = {2, 5, 10, 15, 30, 60};
            String[] names = new String[mins.length];
            int sel = -1;
            for (int i = 0; i < mins.length; i++) {
                names[i] = minutes(mins[i]);
                if (mins[i] * 60000 == System("screen_off_timeout", 600000)) sel = i;
            }
            choose("Start After", names, sel, w -> {
                int ms = mins[w] * 60000;
                putSystem("screen_off_timeout", ms);
                // The TV switches off no sooner than the screen saver starts.
                int off = Secure("sleep_timeout", -1);
                if (off > 0 && off < ms + 5 * 60000) putSecure("sleep_timeout", ms + 30 * 60000);
            });
        });
        row("Turn Off TV", () -> {
            int off = Secure("sleep_timeout", -1);
            return off <= 0 ? "Never" : after(off);
        }, () -> {
            int start = System("screen_off_timeout", 600000);
            int[] mins = {30, 60, 120, 240, -1};
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.List<Integer> ms = new java.util.ArrayList<>();
            int cur = Secure("sleep_timeout", -1), sel = -1;
            for (int m : mins) {
                int v = m < 0 ? -1 : m * 60000;
                if (v > 0 && v <= start) continue;  // can't switch off before the screen saver starts
                if (v == cur || (v < 0 && cur <= 0)) sel = ms.size();
                names.add(m < 0 ? "Never" : "After " + minutes(m));
                ms.add(v);
            }
            choose("Turn Off TV", names.toArray(new String[0]), sel, w -> putSecure("sleep_timeout", ms.get(w)));
        });
        if (AERIALS.equals(dreamComponent())) {
            section("Aerials");
            row("Preview", () -> "", () -> Suggestions.start(this,
                    new Intent().setClassName("app.glasshome.aerials", "app.glasshome.aerials.AerialActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
    }

    boolean dreamOn() {
        return !"0".equals(Settings.Secure.getString(getContentResolver(), DREAM_ON));
    }

    String dreamComponent() {
        String comp = Settings.Secure.getString(getContentResolver(), DREAM);
        if (comp == null || comp.isEmpty()) return "";
        android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(comp.split(",")[0]);
        return cn == null ? "" : cn.flattenToString();
    }

    /** Every screen saver on the TV, with Apple-style names for the ones we know. */
    java.util.List<String[]> dreams() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        android.content.pm.PackageManager pm = getPackageManager();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentServices(
                new Intent("android.service.dreams.DreamService"), 0)) {
            android.content.pm.ServiceInfo si = ri.serviceInfo;
            String comp = new android.content.ComponentName(si.packageName, si.name).flattenToString();
            String name;
            switch (si.packageName) {
                case "app.glasshome.aerials": name = "Aerials"; break;
                case "com.google.android.apps.tv.dreamx": name = "Ambient Mode"; break;
                case "com.android.dreams.basic": name = "Colours"; break;
                default: name = titleCase(ri.loadLabel(pm).toString()); break;
            }
            out.add(new String[]{comp, name});
        }
        // Aerials first, the rest alphabetically.
        out.sort((x, y) -> AERIALS.equals(x[0]) ? -1 : AERIALS.equals(y[0]) ? 1 : x[1].compareToIgnoreCase(y[1]));
        return out;
    }

    String dreamName() {
        if (!dreamOn()) return "Off";
        String comp = dreamComponent();
        for (String[] d : dreams()) if (d[0].equals(comp)) return d[1];
        return "TV Default";
    }

    void chooseDream() {
        java.util.List<String[]> ds = dreams();
        String[] names = new String[ds.size() + 1];
        int sel = dreamOn() ? -1 : ds.size();
        String comp = dreamComponent();
        for (int i = 0; i < ds.size(); i++) {
            names[i] = ds.get(i)[1];
            if (dreamOn() && ds.get(i)[0].equals(comp)) sel = i;
        }
        names[ds.size()] = "Off";
        choose("Screen Saver", names, sel, w -> {
            if (w == ds.size()) {
                putSecure(DREAM_ON, 0);
            } else {
                putSecureString(DREAM, ds.get(w)[0]);
                putSecure(DREAM_ON, 1);
            }
        });
    }

    /** "Information display" -> "Information Display", as Apple writes names. */
    static String titleCase(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean start = true;
        for (char ch : s.toCharArray()) {
            b.append(start ? Character.toUpperCase(ch) : ch);
            start = ch == ' ';
        }
        return b.toString();
    }

    static String minutes(int m) {
        return m >= 60 && m % 60 == 0 ? (m / 60) + (m == 60 ? " Hour" : " Hours") : m + (m == 1 ? " Minute" : " Minutes");
    }

    static String after(int ms) {
        return "After " + minutes(Math.max(1, Math.round(ms / 60000f)));
    }

    int System(String key, int def) {
        return Settings.System.getInt(getContentResolver(), key, def);
    }

    int Secure(String key, int def) {
        return Settings.Secure.getInt(getContentResolver(), key, def);
    }

    void putSystem(String key, int v) {
        try {
            Settings.System.putInt(getContentResolver(), key, v);
        } catch (SecurityException e) {
            cantChange();
        }
    }

    void putSecure(String key, int v) {
        putSecureString(key, String.valueOf(v));
    }

    void putSecureString(String key, String v) {
        try {
            Settings.Secure.putString(getContentResolver(), key, v);
        } catch (SecurityException e) {
            cantChange();
        }
    }

    void cantChange() {
        Toast.makeText(this, "Change this in TV Settings.", Toast.LENGTH_LONG).show();
    }

    String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    void section(String name) {
        TextView t = new TextView(this);
        t.setText(name.toUpperCase());
        t.setTextColor(0x8CFFFFFF);
        t.setTypeface(Ui.font(this, "medium"));
        t.setLetterSpacing(0.06f);
        Ui.textSize(t, 11.5f);
        t.setPadding(Ui.px(this, 14), Ui.px(this, 18), 0, Ui.px(this, 6));
        list.addView(t);
    }

    interface Value {
        String get();
    }

    /** A row that opens a section page: label, current value and a chevron. */
    void nav(String target, Value value) {
        android.view.View r = row(target, () -> value.get() + "   \u203a", () -> show(target));
        navRows.put(target, r);
    }

    /** A settings row: label left, current value right; focus lifts it onto a light plate. */
    android.view.View row(String label, Value value, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        int padH = Ui.px(this, 16);
        r.setPadding(padH, 0, padH, 0);
        Glass bg = new Glass(Ui.px(this, 12), Ui.px(this, 0.5f), Glass.SOLID);
        r.setBackground(bg);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.WHITE);
        l.setTypeface(Ui.font(this, "medium"));
        Ui.textSize(l, 15);
        l.setSingleLine(true);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setTextColor(0x99FFFFFF);
        v.setTypeface(Ui.font(this, "regular"));
        Ui.textSize(v, 15);
        v.setSingleLine(true);
        r.addView(v);
        Runnable refresh = () -> v.setText(value.get());
        refresh.run();
        refreshers.add(refresh);

        r.setOnFocusChangeListener((view, has) -> {
            // The highlight fades between glass and white rather than snapping; the text follows it.
            Glass.focus(bg, has);
            android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofFloat(has ? 0f : 1f, has ? 1f : 0f);
            android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
            fade.setDuration(has ? 200 : 260);
            fade.setInterpolator(MainActivity.EASE);
            fade.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                l.setTextColor((int) ev.evaluate(t, Color.WHITE, 0xFF111114));
                v.setTextColor((int) ev.evaluate(t, 0x99FFFFFF, 0x99111114));
            });
            fade.start();
            Glass.lift(view, has, 1.03f, 0);
        });
        r.setOnClickListener(view -> {
            action.run();
            for (Runnable x : refreshers) x.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px(this, 40));
        lp.bottomMargin = Ui.px(this, 6);
        list.addView(r, lp);
        rowsByLabel.put(label, r);
        return r;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent ev) {
        MainActivity.navSound(this, ev);
        return super.dispatchKeyEvent(ev);
    }

    AlertDialog.Builder dialog(String title) {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title);
    }

    void refreshAll() {
        for (Runnable x : refreshers) x.run();
    }

    void chooseSource() {
        String[] keys = {Suggestions.SRC_JUSTWATCH, Suggestions.SRC_TMDB};
        String[] names = {"JustWatch", "TMDB (Needs a Token)"};
        int cur = Suggestions.source(this).equals(Suggestions.SRC_TMDB) ? 1 : 0;
        choose("Source", names, cur, w -> {
            Suggestions.prefs(this).edit().putString(Suggestions.K_SOURCE, keys[w]).apply();
            Suggestions.changed(this);
            refreshAll();
            if (w == 1 && !Suggestions.hasToken(this)) enterToken();
        });
    }

    /** Token entry on the TV itself (no adb needed). Accepts the v4 read token or the short v3 API key. */
    void enterToken() {
        android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setHint("v3 API key (32 characters) or read-access token");
        in.setText(Suggestions.prefs(this).getString(Suggestions.K_TOKEN, ""));
        in.setSelection(in.getText().length());
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = Ui.px(this, 14);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);
        dialog("TMDB token")
                .setMessage("Get it free at themoviedb.org \u2192 Settings \u2192 API. The short \"API Key\" is easiest to type.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Suggestions.prefs(this).edit().putString(Suggestions.K_TOKEN, in.getText().toString().trim()).apply();
                    Suggestions.changed(this);
                    refreshAll();
                })
                .setNeutralButton("Clear", (d, w) -> {
                    Suggestions.prefs(this).edit().remove(Suggestions.K_TOKEN).apply();
                    Suggestions.changed(this);
                    refreshAll();
                })
                .setNegativeButton("Cancel", null)
                .show();
        in.requestFocus();
    }

    /** Automatic (from the internet address) or a city found by name. */
    void chooseLocation() {
        String[] names = {"Automatic", "Choose a City\u2026"};
        choose("Location", names, Weather.manual(this) ? 1 : 0, w -> {
            if (w == 0) {
                Weather.setPlace(this, null);
                refreshAll();
            } else {
                searchCity();
            }
        });
    }

    void searchCity() {
        android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setHint("City");
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        int pad = Ui.px(this, 14);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);
        dialog("Choose City")
                .setView(box)
                .setPositiveButton("Search", (d, w) -> {
                    String q = in.getText().toString().trim();
                    if (q.isEmpty()) return;
                    new Thread(() -> {
                        List<Weather.Place> found;
                        try {
                            found = Weather.search(q);
                        } catch (Exception e) {
                            found = null;
                        }
                        List<Weather.Place> res = found;
                        runOnUiThread(() -> showCities(q, res));
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
        in.requestFocus();
    }

    void showCities(String q, List<Weather.Place> res) {
        if (isFinishing()) return;
        if (res == null) {
            Toast.makeText(this, "Can\u2019t search right now. Check your connection.", Toast.LENGTH_LONG).show();
            return;
        }
        if (res.isEmpty()) {
            Toast.makeText(this, "No city called \u201c" + q + "\u201d", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[res.size()];
        for (int i = 0; i < names.length; i++) {
            Weather.Place pl = res.get(i);
            names[i] = pl.detail.isEmpty() ? pl.name : pl.name + "  \u00b7  " + pl.detail;
        }
        choose("Choose City", names, -1, w -> {
            Weather.setPlace(this, res.get(w));
            onBackPressed();  // straight back to Weather, showing the new city
        });
    }


    void chooseTypes() {
        String[] keys = {"both", "movie", "tv"};
        String[] names = {"Movies and TV Shows", "Movies", "TV Shows"};
        int cur = java.util.Arrays.asList(keys).indexOf(Suggestions.types(this));
        choose("Content", names, cur, w -> {
            Suggestions.prefs(this).edit().putString(Suggestions.K_TYPES, keys[w]).apply();
            Suggestions.changed(this);
            refreshAll();
        });
    }

    void chooseProviders() {
        int n = Suggestions.PROVIDERS.size();
        String[] names = new String[n];
        boolean[] on = new boolean[n];
        Set<String> cur = Suggestions.providers(this);
        for (int i = 0; i < n; i++) {
            Suggestions.Provider p = Suggestions.PROVIDERS.get(i);
            names[i] = p.name + (Suggestions.installed(this, p.pkg) ? "" : " (Not Installed)");
            on[i] = cur.contains(String.valueOf(p.id));
        }
        chooseMany("Apps", names, on, (w, c) -> {
            Set<String> sel = new java.util.HashSet<>();
            for (int i = 0; i < n; i++) if (i == w ? c : on[i]) sel.add(String.valueOf(Suggestions.PROVIDERS.get(i).id));
            if (sel.isEmpty()) {
                Toast.makeText(this, "Choose at least one app.", Toast.LENGTH_SHORT).show();
                return false;
            }
            Suggestions.prefs(this).edit().putStringSet(Suggestions.K_PROVIDERS, sel).apply();
            Suggestions.changed(this);
            return true;
        });
    }

    void chooseLanguages() {
        int n = Suggestions.LANG_CODES.length;
        boolean[] on = new boolean[n];
        Set<String> cur = Suggestions.languages(this);
        for (int i = 0; i < n; i++) on[i] = cur.contains(Suggestions.LANG_CODES[i]);
        chooseMany("Languages", Suggestions.LANG_NAMES, on, (w, c) -> {
            Set<String> sel = new java.util.HashSet<>();
            for (int i = 0; i < n; i++) if (i == w ? c : on[i]) sel.add(Suggestions.LANG_CODES[i]);
            Suggestions.prefs(this).edit().putStringSet(Suggestions.K_LANGS, sel).apply();
            Suggestions.changed(this);
            return true;
        });
    }

    void chooseAdvance() {
        int[] secs = {0, 6, 8, 12};
        String[] names = {"Off", "Every 6 Seconds", "Every 8 Seconds", "Every 12 Seconds"};
        int cur = 0;
        for (int i = 0; i < secs.length; i++) if (secs[i] == Suggestions.advanceSeconds(this)) cur = i;
        choose("Advance Automatically", names, cur, w -> {
            Suggestions.prefs(this).edit().putInt(Suggestions.K_ADVANCE, secs[w]).apply();
            refreshAll();
        });
    }
}

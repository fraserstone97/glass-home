package app.glasshome.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Settings as tvOS 26 lays them out: the page's title centred at the top in dim grey, its artwork
 * on the left with a line about whatever is focused beneath it, and its rows on the right as
 * capsules. The main list opens a page per section, a row with choices opens its own list, and
 * Back returns the way you came; the lists slide past each other as they change.
 *
 * A TMDB token can also be supplied over adb:
 *   adb shell am start -n app.glasshome.launcher/.SettingsActivity --es tmdb_token &lt;token&gt;
 */
public class SettingsActivity extends Activity {

    static final ArgbEvaluator EV = new ArgbEvaluator();
    /** tvOS menu rows: focus comes in over 100 ms and goes over 200. */
    static final AccelerateInterpolator ROW_IN = new AccelerateInterpolator(1f);
    static final DecelerateInterpolator ROW_OUT = new DecelerateInterpolator(1f);
    static final LinearInterpolator LINEAR = new LinearInterpolator();
    /** The Apple TV app's page movement (its stiffness 150, damping 30 spring as a curve). */
    static final PathInterpolator SLIDE = new PathInterpolator(0.2f, 0.7f, 0.25f, 1f);

    LinearLayout list;
    final List<Runnable> refreshers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ClipAudit.watch(this);  // a development check, off unless asked for over adb
        Clicks.init(this);
        // Typing (a TMDB key, a city) brings the TV's keyboard up over the lower half; the field
        // sits at the top of the list, so the page stays where it is.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        String token = getIntent().getStringExtra("tmdb_token");
        if (token != null && !token.trim().isEmpty()) {
            Suggestions.prefs(this).edit().putString(Suggestions.K_TOKEN, token.trim()).apply();
            Suggestions.changed(this);
        }
        build();
        opened = getIntent().getStringExtra("page");  // adb: --es page "Top Shelf"
        show(opened != null ? opened : ROOT, 0, null);
    }

    /** The page Settings was opened at from elsewhere (Setup's Weather): Back from it goes back there. */
    String opened;

    int u(float units) {
        return Ui.px(this, units);
    }

    // tvOS-style pages: the main list opens a page per section; Back returns to the main list.
    static final String ROOT = "root", SHELF = "Top Shelf",
            INPUTS = "Inputs", WEATHER = "Weather", HOME = "Home Screen",
            PERMS = "Permissions", ABOUT = "About", SAVER = "Screen Saver";
    /** A list of choices opened from a row (a page of its own). */
    static final String CHOICE = "choice";

    TextView heading, about;
    FrameLayout art;
    ScrollView scroller;
    /** The lists: the current page's, and for a moment the one it's replacing. */
    FrameLayout pages;
    String page = ROOT;
    final Map<String, View> navRows = new HashMap<>();

    void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B10);
        // Over Home's last picture, blurred right down and dimmed further (the app drawer's glass):
        // Settings reads as a layer over Home, in Home's colours, rather than a black page.
        MainActivity home = MainActivity.live.get();
        Bitmap glass = home == null ? null : home.heroDrawer != null ? home.heroDrawer : home.wallBlur;
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

        // The page's title, centred over the whole screen in dim grey, as tvOS 26's.
        heading = text("", 28.5f, "semibold", 0x4DFFFFFF);
        heading.setLetterSpacing(0.006f);
        heading.setSingleLine(true);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hl.topMargin = u(26);
        root.addView(heading, hl);

        // Left: the page's artwork, and beneath it a line about whatever is focused.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        art = new FrameLayout(this);
        left.addView(art, new LinearLayout.LayoutParams(u(260), u(156)));
        about = text("", 14.5f, "regular", 0x99FFFFFF);
        about.setGravity(Gravity.CENTER_HORIZONTAL);
        about.setMaxLines(3);
        about.setEllipsize(TextUtils.TruncateAt.END);
        about.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(u(380), ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = u(18);
        left.addView(about, al);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(u(380), ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.leftMargin = u(263 - 190);  // centred under the art, whose middle is 526 px in
        ll.topMargin = u(175);
        root.addView(left, ll);

        // Right: the rows, from x 1060 to 1840 px. They scroll up under the title and are cut off
        // there, not at the first row's place. The scroller reaches 80 px past the rows each side
        // (its padding), so a list sliding in or out isn't cut off at the column's edges. That
        // needs the scroller itself to let its child draw there: by default it clips the pages to
        // their own bounds, the column, and the lists were cut flat at its edges as they slid.
        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(u(40), u(45), u(40), u(30));
        pages = new FrameLayout(this);
        pages.setClipChildren(false);
        scroller.addView(pages, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(u(390 + 80), ViewGroup.LayoutParams.MATCH_PARENT);
        sl.leftMargin = u(530 - 40);
        sl.topMargin = u(55);
        root.addView(scroller, sl);
        setContentView(root);
    }

    TextView text(String s, float size, String weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        Ui.textSize(t, size);
        t.setTypeface(Ui.font(this, weight));
        t.setTextColor(color);
        return t;
    }

    /**
     * Builds a page. `dir` is the way you're going (+1 deeper, -1 back, 0 for a page that simply
     * appears); `row` is the row to land on, else the section you came back from, else the first.
     */
    void show(String p, int dir, String row) {
        String from = page;
        page = p;
        begin(p.equals(ROOT) ? "Settings" : p, p, dir);
        pageLine = p.equals(ROOT) ? "" : line(p);
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
        // Returning to the main list puts focus back on the section you came from.
        land(row != null ? rowsByLabel.get(row) : p.equals(ROOT) ? navRows.get(from) : null, dir);
    }

    /** A page has been shown: from now on titles, art and lines change with a fade. */
    boolean shown;
    /** Focus is being placed by the page, not the remote: rows take it at once, already lit. */
    boolean snap;

    /** Starts a new page: a new list in place of the last, which slides away. */
    void begin(String title, String artKey, int dir) {
        refreshers.clear();
        navRows.clear();
        rowsByLabel.clear();
        hints.clear();
        LinearLayout old = list;
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        pages.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (old != null) {
            // Nothing in the old list can be reached while it goes.
            old.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            old.setEnabled(false);
            if (dir == 0) {
                pages.removeView(old);
            } else {
                // It leaves from where it was scrolled to, while the new page starts at the top.
                old.setTranslationY(-scroller.getScrollY());
                glide(old, false, dir);
            }
        }
        scroller.scrollTo(0, 0);
        setHeading(title);
        setArt(artKey);
    }

    /** Puts focus on the page's row (else its first), then brings the page in from the side you're heading. */
    void land(View focus, int dir) {
        for (int i = 0; focus == null && i < list.getChildCount(); i++) {
            if (list.getChildAt(i).isFocusable()) focus = list.getChildAt(i);
        }
        snap = true;
        if (focus != null) focus.requestFocus();
        snap = false;
        if (dir != 0 && shown) glide(list, true, dir);
        shown = true;
    }

    /**
     * A page's list going out or coming in, as tvOS Settings pushes a page: the old one fades in
     * 150 ms as it slides 80 px away; the new one fades in over 300 ms as it settles from 80 px
     * the other side over 600, on the Apple TV app's page curve. Drawn as one picture while it
     * moves: its rows sit still, since a page arrives with its focus already lit.
     */
    void glide(View v, boolean in, int dir) {
        if (v.getTag() instanceof ValueAnimator) ((ValueAnimator) v.getTag()).cancel();
        float x0 = in ? u(40) * dir : v.getTranslationX(), x1 = in ? 0f : x0 - u(40) * dir, a0 = in ? 0f : v.getAlpha();
        v.setTranslationX(x0);
        v.setAlpha(a0);
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(in ? 600 : 300);
        a.setInterpolator(LINEAR);
        a.addUpdateListener(an -> {
            float t = an.getAnimatedFraction();
            v.setTranslationX(x0 + (x1 - x0) * SLIDE.getInterpolation(t));
            v.setAlpha(in ? Math.min(1f, t * 2f) : a0 * Math.max(0f, 1f - t * 2f));
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                v.setLayerType(View.LAYER_TYPE_NONE, null);
                if (!in) pages.removeView(v);
            }
        });
        v.setTag(a);
        a.start();
    }

    /** What the title says, or is about to (it fades out, changes, and fades back). */
    String headingText = "";

    void setHeading(String t) {
        if (t.equals(headingText)) return;
        headingText = t;
        heading.animate().cancel();
        if (!shown) {
            heading.setText(t);
            return;
        }
        heading.animate().alpha(0f).setDuration(100).setInterpolator(LINEAR).withEndAction(() -> {
            heading.setText(t);
            heading.animate().alpha(1f).setDuration(200).setInterpolator(LINEAR).start();
        }).start();
    }

    /** The artwork shown, by page; each is drawn once. */
    String artKey;
    final Map<String, Bitmap> arts = new HashMap<>();

    /** The page's artwork: it stays when a page shares it (a section and its choices), else crossfades. */
    void setArt(String key) {
        if (key.equals(artKey)) return;
        artKey = key;
        Bitmap b = arts.get(key);
        if (b == null) {
            // A silver tile with a gear for the main list, like the Settings icon on Apple TV.
            b = key.equals(ROOT) ? MainActivity.settingsArt(u(260), u(156)) : pageIcon(key, u(260), u(156));
            arts.put(key, b);
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(b);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(u(21));
        iv.setBackground(shape);
        iv.setClipToOutline(true);
        iv.setElevation(u(8));
        art.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!shown) {
            while (art.getChildCount() > 1) art.removeViewAt(0);
            return;
        }
        iv.setAlpha(0f);
        iv.animate().alpha(1f).setDuration(300).setInterpolator(LINEAR).withEndAction(() -> {
            while (art.getChildCount() > 1 && art.getChildAt(0) != iv) art.removeViewAt(0);
        }).start();
    }

    // ---------------------------------------------------------------- the line under the art

    /** The page's own line, shown for rows without one of their own. */
    String pageLine = "";
    /** Each row's line, shown while it's focused. */
    final Map<View, String> hints = new HashMap<>();
    String aboutText = "";

    View hint(View row, String text) {
        hints.put(row, text);
        return row;
    }

    /** Says something under the art (the result of what was just pressed), until focus moves on. */
    void say(String s) {
        setAbout(s);
    }

    void setAbout(String s) {
        if (s == null) s = "";
        if (s.equals(aboutText)) return;
        aboutText = s;
        String t = s;
        about.animate().cancel();
        if (!shown) {
            about.setText(t);
            return;
        }
        about.animate().alpha(0f).setDuration(100).setInterpolator(LINEAR).withEndAction(() -> {
            about.setText(t);
            about.animate().alpha(1f).setDuration(150).setInterpolator(LINEAR).start();
        }).start();
    }

    /** What each section is for: under the main list's row, and on the section's own page. */
    String line(String p) {
        String app = getString(R.string.app_name);
        switch (p) {
            case SHELF: return "What appears at the top of the Home Screen.";
            case INPUTS: return "The TV’s HDMI inputs, and the card that opens them.";
            case WEATHER: return "The forecast on the Home Screen.";
            case HOME: return "Background, sounds and idle behaviour.";
            case SAVER: return "Aerials, and when the screen saver and the TV switch off.";
            case PERMS: return "What " + app + " is allowed to do on this TV.";
            case ABOUT: return "Version, and where pictures and forecasts come from.";
            default: return "";
        }
    }

    // ---------------------------------------------------------------- choice pages

    /** The page a choice page was opened from, the row that opened it (for Back), and its line. */
    String choiceFrom, choiceRow, choiceLine;
    final Map<String, View> rowsByLabel = new HashMap<>();

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
        View sel = null;
        for (int i = 0; i < names.length; i++) {
            int w = i;
            View r = row(names[i], () -> cur[0] == w ? "✓" : "", () -> {
                refused = false;
                pick.picked(w);
                if (!refused) cur[0] = w;  // the tick stays put if the TV refused it
            });
            if (i == selected) sel = r;
        }
        land(sel, 1);
    }

    /** A list of switches: each row ticks on or off. */
    void chooseMany(String title, String[] names, boolean[] on, Toggle toggle) {
        chooseMany(title, names, null, on, toggle);
    }

    /** As above, with a grey note on the right of each row that isn't ticked ("Not Installed"). */
    void chooseMany(String title, String[] names, String[] notes, boolean[] on, Toggle toggle) {
        openChoice(title);
        for (int i = 0; i < names.length; i++) {
            int w = i;
            row(names[i], () -> on[w] ? "✓" : notes != null && notes[w] != null ? notes[w] : "", () -> {
                if (toggle.toggled(w, !on[w])) on[w] = !on[w];
            });
        }
        land(null, 1);
    }

    void openChoice(String title) {
        // A choice opened from another choice (city results after Location) still returns to the section.
        if (!page.equals(CHOICE)) {
            choiceFrom = page;
            choiceRow = title;
            choiceLine = aboutText;
        }
        page = CHOICE;
        begin(title, choiceFrom, 1);
        pageLine = choiceLine;
    }

    /**
     * Typing on the TV (a TMDB key, a city): a page with the field at the top and the page's
     * actions beneath it, the TV's keyboard opening over the lower half. The keyboard's own
     * Done or Search does what `go` does.
     */
    EditText entry(String title, String hint, String text, int type, int ime, Runnable go) {
        openChoice(title);
        EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint(hint);
        in.setText(text);
        in.setSelection(in.getText().length());
        in.setInputType(type);
        in.setImeOptions(ime | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        in.setOnEditorActionListener((v, id, e) -> {
            // An Enter key arrives here going down and again coming up: one press, one go.
            if (e != null && (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0)) return true;
            // Taking the action ourselves skips Android's own closing of the keyboard: close it
            // here, so it doesn't stay up over the page that comes next (the cities found).
            InputMethodManager im = getSystemService(InputMethodManager.class);
            if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
            go.run();
            return true;
        });
        Ui.textSize(in, 19);
        in.setTypeface(Ui.font(this, "regular"));
        in.setGravity(Gravity.CENTER_VERTICAL);
        in.setPadding(u(18), 0, u(18), 0);
        Glass bg = new Glass(u(40), u(0.5f), Glass.REGULAR);
        bg.grow = u(2);
        in.setBackground(bg);
        bg.tint = f -> {
            in.setTextColor((int) EV.evaluate(f, Color.WHITE, 0xFF111114));
            in.setHintTextColor((int) EV.evaluate(f, 0x66FFFFFF, 0x99111114));
        };
        bg.tint.at(0f);
        in.setOnFocusChangeListener((v, has) -> {
            if (has) setAbout(pageLine);
            if (snap) bg.setFocus(has ? 1f : 0f);
            else Glass.focus(bg, has, 100, 200, ROW_IN, ROW_OUT);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, u(37));
        lp.bottomMargin = u(3);
        list.addView(in, lp);
        return in;
    }

    /** Lands on the field and opens the keyboard. */
    void type(EditText in) {
        land(in, 1);
        in.post(() -> {
            InputMethodManager im = getSystemService(InputMethodManager.class);
            // As asked for (the row was pressed to type): an implicit request is one an input
            // method may turn down on a TV whose remote counts as a keyboard.
            if (im != null && in.hasFocus()) im.showSoftInput(in, 0);
        });
    }

    @Override
    public void onBackPressed() {
        if (page.equals(CHOICE)) {
            show(choiceFrom, -1, choiceRow);
        } else if (!page.equals(ROOT) && !page.equals(opened)) {
            show(ROOT, -1, null);
        } else {
            super.onBackPressed();
        }
    }

    /** Away to a system screen or prompt (a permission, the Home role): its answer shows on return. */
    boolean paused;

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (paused) refreshAll();
        paused = false;
    }

    /** A 5:3 icon for a section page: a gradient tile with a white glyph (or the section's own art). */
    Bitmap pageIcon(String p, int w, int h) {
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
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.LinearGradient(0, 0, 0, h, grad[0], grad[1], android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        Bitmap g;
        // A lone sun is drawn smaller than the symbols with a cloud, as SF Symbols size them.
        if (p.equals(WEATHER)) g = WeatherIcons.get(0, true, (int) (h * 0.82f));
        else g = Glyphs.get(glyph, (int) (h * 0.56f), Color.WHITE);
        c.drawBitmap(g, (w - g.getWidth()) / 2f, (h - g.getHeight()) / 2f, null);
        return b;
    }

    // ---------------------------------------------------------------- pages

    /** The main list: only chevrons, as tvOS's; Permissions says when something still needs allowing. */
    void pageRoot() {
        nav(SHELF, null);
        if (!Inputs.list(this).isEmpty()) nav(INPUTS, null);
        nav(WEATHER, null);
        nav(HOME, null);
        nav(SAVER, null);
        nav(PERMS, () -> {
            int missing = (Perms.hasLocation(this) ? 0 : 1)
                    + (SleepReceiver.canSleep(this) || !Perms.adminSupported(this) ? 0 : 1) + (Perms.isDefaultHome(this) ? 0 : 1);
            return missing == 0 ? "" : missing + " Not Allowed";
        });
        nav(ABOUT, null);
        hint(row("TV Settings", null, () -> Suggestions.start(this, new Intent(Settings.ACTION_SETTINGS))),
                "The TV’s own settings: picture, sound, network and more.");
    }

    void pageShelf() {
        picker("Show", () -> Suggestions.showRecs(this) ? "Recommendations" : "Off", () -> {
            String[] keys = {Suggestions.SHELF_RECS, Suggestions.SHELF_NONE};
            String[] names = {"Recommendations", "Off"};
            int sel = Math.max(0, java.util.Arrays.asList(keys).indexOf(Suggestions.shelf(this)));
            choose("Show", names, sel, w -> Suggestions.setShelf(this, keys[w]));
        });
        if (!Suggestions.showRecs(this)) return;
        section("Recommendations");
        hint(picker("Source", () -> Suggestions.source(this).equals(Suggestions.SRC_TMDB) ? "TMDB" : "JustWatch", this::chooseSource),
                "Where recommendations come from. TMDB needs a free key.");
        picker("Content", () -> {
            String t = Suggestions.types(this);
            return t.equals("movie") ? "Films" : t.equals("tv") ? "Series" : "Films and Series";
        }, this::chooseTypes);
        picker("Apps", () -> {
            int n = Suggestions.providers(this).size();
            return n == Suggestions.PROVIDERS.size() ? "All" : n == 0 ? "None" : n == 1 ? "1 App" : n + " Apps";
        }, this::chooseProviders);
        if (Suggestions.source(this).equals(Suggestions.SRC_TMDB)) {
            picker("Languages", () -> {
                Set<String> l = Suggestions.languages(this);
                if (l.isEmpty()) return "Any";
                List<String> names = new ArrayList<>();
                for (int i = 0; i < Suggestions.LANG_CODES.length; i++) {
                    if (l.contains(Suggestions.LANG_CODES[i])) names.add(Suggestions.LANG_NAMES[i]);
                }
                return names.size() > 2 ? names.size() + " languages" : String.join(", ", names);
            }, this::chooseLanguages);
            picker("TMDB Token", () -> Suggestions.hasToken(this) ? "Connected" : "Not Set", this::enterToken);
        }
        section("Carousel");
        hint(picker("Auto-Advance", () -> {
            int sec = Suggestions.advanceSeconds(this);
            return sec == 0 ? "Off" : "Every " + sec + " Seconds";
        }, this::chooseAdvance), "How often the picture at the top moves on.");
        row("Refresh Now", null, () -> {
            Suggestions.changed(this);
            say("Fresh recommendations arrive when you go back to the Home Screen.");
        });
    }

    void pageInputs() {
        row("Show on Home Screen", () -> Inputs.showCard(this) ? "On" : "Off", () -> {
            Inputs.prefs(this).edit().putBoolean(Inputs.K_CARD, !Inputs.showCard(this)).apply();
            MainActivity.layoutChanged = true;  // Home adds or drops the card when it's back
        });
        section("This TV");
        for (Inputs.Input in : Inputs.list(this)) {
            row(in.label, () -> in.disconnected ? "Not Connected" : "", () -> {
                if (!Inputs.open(this, in)) say("Couldn’t switch to " + in.label + ".");
            });
        }
    }

    void pageWeather() {
        row("Show Weather", () -> Weather.enabled(this) ? "On" : "Off", () -> {
            Weather.prefs(this).edit().putBoolean(Weather.K_ENABLED, !Weather.enabled(this)).apply();
            Weather.changed(this);
        });
        picker("Location", () -> Weather.manual(this) ? Weather.place(this)
                : Weather.place(this).isEmpty() ? "Automatic" : "Automatic · " + Weather.place(this), this::chooseLocation);
        picker("Temperature", () -> {
            String u = Weather.prefs(this).getString(Weather.K_UNITS, "auto");
            return u.equals("c") ? "Celsius" : u.equals("f") ? "Fahrenheit" : "Automatic";
        }, () -> {
            String[] keys = {"auto", "c", "f"};
            String[] names = {"Automatic", "Celsius (°C)", "Fahrenheit (°F)"};
            String cur = Weather.prefs(this).getString(Weather.K_UNITS, "auto");
            int sel = cur.equals("c") ? 1 : cur.equals("f") ? 2 : 0;
            choose("Temperature", names, sel, w -> {
                Weather.prefs(this).edit().putString(Weather.K_UNITS, keys[w]).apply();
                Weather.changed(this);
            });
        });
        section("Updates");
        hint(picker("Source", () -> Weather.sourceName(Weather.source(this)), () -> {
            String[] keys = Weather.SOURCES;
            String[] names = {"Met Office", "Open-Meteo", "MET Norway", "wttr.in"};
            int sel = java.util.Arrays.asList(keys).indexOf(Weather.source(this));
            choose("Source", names, Math.max(0, sel), w -> {
                Weather.prefs(this).edit().putString(Weather.K_SOURCE, keys[w]).apply();
                Weather.changed(this);
            });
        }), "Where forecasts come from. If it’s unavailable, another source is used.");
        picker("Update Every", () -> Weather.refreshMinutes(this) + " Minutes", () -> {
            int[] mins = Weather.REFRESH_CHOICES;
            String[] names = new String[mins.length];
            int sel = 1;
            for (int i = 0; i < mins.length; i++) {
                names[i] = mins[i] + " Minutes";
                if (mins[i] == Weather.refreshMinutes(this)) sel = i;
            }
            choose("Update Every", names, sel, w -> Weather.prefs(this).edit().putInt(Weather.K_REFRESH, mins[w]).apply());
        });
    }

    void pageHome() {
        row("Default Home Screen", () -> Perms.isDefaultHome(this) ? getString(R.string.app_name) : "Set as Default…",
                () -> Perms.requestDefaultHome(this));
        picker("Background", () -> {
            String k = Profiles.homePrefs(this).getString("wallpaper", "night");
            int i = java.util.Arrays.asList(MainActivity.WALL_KEYS).indexOf(k);
            return MainActivity.WALL_NAMES[Math.max(0, i)];
        }, () -> {
            String k = Profiles.homePrefs(this).getString("wallpaper", "night");
            int cur = Math.max(0, java.util.Arrays.asList(MainActivity.WALL_KEYS).indexOf(k));
            choose("Background", MainActivity.WALL_NAMES, cur,
                    w -> Profiles.homePrefs(this).edit().putString("wallpaper", MainActivity.WALL_KEYS[w]).apply());
        });
        hint(row("Navigation Clicks", () -> Clicks.enabled(this) ? "On" : "Off",
                () -> Clicks.setEnabled(this, !Clicks.enabled(this))), "A soft click as you move around.");
        hint(picker("Full Screen When Idle", () -> {
            int sec = Profiles.homePrefs(this).getInt("idle_sec", 20);
            return sec == 0 ? "Off" : sec >= 60 ? minutes(sec / 60) : sec + " Seconds";
        }, () -> {
            int[] secs = {0, 10, 20, 30, 60};
            String[] names = {"Off", "10 Seconds", "20 Seconds", "30 Seconds", "1 Minute"};
            int cur = Profiles.homePrefs(this).getInt("idle_sec", 20), sel = 2;
            for (int i = 0; i < secs.length; i++) if (secs[i] == cur) sel = i;
            choose("Full Screen When Idle", names, sel, w -> Profiles.homePrefs(this).edit().putInt("idle_sec", secs[w]).apply());
        }), "The picture at the top fills the screen when the remote hasn’t been used for a while.");
        section("Setup");
        row("Run Setup Again", null, () -> startActivity(new Intent(this, SetupActivity.class)));
        // Asked first: one press shouldn't undo an arrangement.
        hint(row("Reset Home Screen Layout", null, () -> Perms.alert(this, "Reset Home Screen Layout?",
                "Your apps go back to their original order.", new String[]{"Reset", "Cancel"}, 0, w -> {
                    if (w != 0) return;
                    Profiles.homePrefs(this).edit().remove("order").apply();
                    MainActivity.layoutChanged = true;  // Home rebuilds its grid when it's back
                })), "Puts your apps back in their original order.");
    }

    void pagePermissions() {
        String app = getString(R.string.app_name);
        hint(row("Sleep Timer", () -> SleepReceiver.canSleep(this) ? "Allowed"
                        : Perms.adminSupported(this) ? "Allow…" : "Not on This TV", () -> Perms.requestAdmin(this)),
                "Lets " + app + " turn the TV off from Control Centre.");
        hint(row("Home Screen", () -> Perms.isDefaultHome(this) ? "Allowed" : "Allow…",
                () -> Perms.requestDefaultHome(this)), "Lets the Home button open " + app + ".");
        hint(row("Wi-Fi Name", () -> Perms.hasLocation(this) ? "Allowed" : "Allow…",
                () -> Perms.requestLocation(this)), "Android needs location access to show the network’s name.");
    }

    void pageAbout() {
        row(getString(R.string.app_name), () -> "Version " + version(), () -> { });
        row("Weather", () -> Weather.sourceName(Weather.source(this)), () -> { });
        row("Artwork", () -> Suggestions.hasToken(this) ? "TMDB" : "JustWatch", () -> { });
        if (Suggestions.hasToken(this)) {
            // TMDB's terms: credit, and say it isn't their product.
            TextView n = text("This product uses the TMDB API but is not endorsed or certified by TMDB.", 13.5f, "regular", 0x80FFFFFF);
            n.setPadding(u(18), u(10), u(18), 0);
            list.addView(n);
        }
    }

    // ---------------------------------------------------------------- screen saver

    /** The TV's own screen saver settings, read and written directly (Glass Home holds WRITE_SECURE_SETTINGS, granted by install.sh). */
    static final String DREAM_ON = "screensaver_enabled", DREAM = "screensaver_components";
    static final String AERIALS = "app.glasshome.aerials/app.glasshome.aerials.AerialDream";

    /** In Apple's words: Current Selection, Start After, and the TV's Sleep After. */
    void pageSaver() {
        picker("Current Selection", this::dreamName, this::chooseDream);
        if (!dreamOn()) return;
        picker("Start After", () -> span(System("screen_off_timeout", 600000)), () -> {
            int[] mins = {2, 5, 10, 15, 30, 60};
            String[] names = new String[mins.length];
            int sel = -1;
            for (int i = 0; i < mins.length; i++) {
                names[i] = minutes(mins[i]);
                if (mins[i] * 60000 == System("screen_off_timeout", 600000)) sel = i;
            }
            choose("Start After", names, sel, w -> {
                int ms = mins[w] * 60000;
                if (!putSystem("screen_off_timeout", ms)) return;
                // The TV switches off no sooner than the screen saver starts.
                int off = Secure("sleep_timeout", -1);
                if (off > 0 && off < ms + 5 * 60000) putSecure("sleep_timeout", ms + 30 * 60000);
            });
        });
        picker("Sleep After", () -> {
            int off = Secure("sleep_timeout", -1);
            return span(off);
        }, () -> {
            int start = System("screen_off_timeout", 600000);
            int[] mins = {30, 60, 120, 240, -1};
            List<String> names = new ArrayList<>();
            List<Integer> ms = new ArrayList<>();
            int cur = Secure("sleep_timeout", -1), sel = -1;
            for (int m : mins) {
                int v = m < 0 ? -1 : m * 60000;
                if (v > 0 && v <= start) continue;  // can't switch off before the screen saver starts
                if (v == cur || (v < 0 && cur <= 0)) sel = ms.size();
                names.add(m < 0 ? "Never" : minutes(m));
                ms.add(v);
            }
            choose("Sleep After", names.toArray(new String[0]), sel, w -> putSecure("sleep_timeout", ms.get(w)));
        });
        if (AERIALS.equals(dreamComponent())) {
            section("Aerials");
            row("Preview", null, () -> Suggestions.start(this,
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
    List<String[]> dreams() {
        List<String[]> out = new ArrayList<>();
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
        List<String[]> ds = dreams();
        String[] names = new String[ds.size() + 1];
        int sel = dreamOn() ? -1 : ds.size();
        String comp = dreamComponent();
        for (int i = 0; i < ds.size(); i++) {
            names[i] = ds.get(i)[1];
            if (dreamOn() && ds.get(i)[0].equals(comp)) sel = i;
        }
        names[ds.size()] = "Off";
        choose("Current Selection", names, sel, w -> {
            if (w == ds.size()) {
                putSecure(DREAM_ON, 0);
            } else if (putSecureString(DREAM, ds.get(w)[0])) {
                putSecure(DREAM_ON, 1);  // only once the first is allowed: one refusal, one alert
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

    /** A time setting as tvOS words it ("10 Minutes", "1 Hour"); Android keeps "never" as 0 or the largest int. */
    static String span(int ms) {
        return ms <= 0 || ms == Integer.MAX_VALUE ? "Never" : minutes(Math.max(1, Math.round(ms / 60000f)));
    }

    int System(String key, int def) {
        return Settings.System.getInt(getContentResolver(), key, def);
    }

    int Secure(String key, int def) {
        return Settings.Secure.getInt(getContentResolver(), key, def);
    }

    /** Each write says whether the TV allowed it (false once it has said so in an alert). */
    boolean putSystem(String key, int v) {
        try {
            Settings.System.putInt(getContentResolver(), key, v);
            return true;
        } catch (SecurityException e) {
            cantChange();
            return false;
        }
    }

    boolean putSecure(String key, int v) {
        return putSecureString(key, String.valueOf(v));
    }

    boolean putSecureString(String key, String v) {
        try {
            Settings.Secure.putString(getContentResolver(), key, v);
            return true;
        } catch (SecurityException e) {
            cantChange();
            return false;
        }
    }

    /** A write the TV refused since the last pick: choose() leaves the tick where it was. */
    boolean refused;

    void cantChange() {
        refused = true;
        Perms.alert(this, "Can’t Change This Here", "This TV only lets its own settings change it.",
                new String[]{"Open TV Settings", "Cancel"}, -1, w -> {
                    if (w == 0) Suggestions.start(this, new Intent(Settings.ACTION_SETTINGS));
                });
    }

    String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- rows

    /** A group's header: small grey capitals in line with the rows' labels. */
    void section(String name) {
        TextView t = text(name.toUpperCase(Locale.UK), 13.5f, "semibold", 0x6BFFFFFF);
        t.setLetterSpacing(0.02f);
        t.setPadding(u(18), u(25), 0, u(10));
        list.addView(t);
    }

    interface Value {
        String get();
    }

    /** A row that opens a section page: its name and a chevron (with a value only where something needs doing). */
    void nav(String target, Value value) {
        View r = row(target, value, () -> show(target, 1, null), true);
        navRows.put(target, r);
        String l = line(target);
        if (!l.isEmpty()) hints.put(r, l);
    }

    /** A row that opens a list of choices (or somewhere to type): its value, then a chevron. */
    View picker(String label, Value value, Runnable action) {
        return row(label, value, action, true);
    }

    /** A row that does something, or (On / Off) flips in place. */
    View row(String label, Value value, Runnable action) {
        return row(label, value, action, false);
    }

    /**
     * A settings row as tvOS 26 draws it: a capsule with the label on the left and the value in
     * grey on the right, then a chevron where it opens something. Focus grows the plate 4 px each
     * way and turns it white while the words stay the same size (no scaling, so no layer, and
     * they stay sharp), on tvOS's menu-row timing; a tick is in the label's colour.
     */
    View row(String label, Value value, Runnable action, boolean chevron) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(u(18), 0, u(18), 0);
        Glass bg = new Glass(u(40), u(0.5f), Glass.REGULAR);
        bg.grow = u(2);
        r.setBackground(bg);

        // The label keeps its whole width; a value too long for what's left is the one cut short,
        // as tvOS does.
        TextView l = text(label, 19, "medium", Color.WHITE);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = text("", 19, "regular", 0x80FFFFFF);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setGravity(Gravity.END);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vl.leftMargin = u(12);
        r.addView(v, vl);
        TextView ch = chevron ? text("›", 19, "regular", 0x73FFFFFF) : null;
        if (ch != null) {
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.leftMargin = u(8);
            r.addView(ch, cl);
        }
        // The words darken with the white plate as it fades in, frame by frame.
        bg.tint = f -> {
            l.setTextColor((int) EV.evaluate(f, Color.WHITE, 0xFF111114));
            boolean tick = "✓".contentEquals(v.getText());
            v.setTextColor((int) EV.evaluate(f, tick ? Color.WHITE : 0x80FFFFFF, tick ? 0xFF111114 : 0xFF8E8E93));
            if (ch != null) ch.setTextColor((int) EV.evaluate(f, 0x73FFFFFF, 0xFF8E8E93));
        };
        if (value != null) {
            Runnable refresh = () -> {
                v.setText(value.get());
                bg.tint.at(bg.getFocus());
            };
            refresh.run();
            refreshers.add(refresh);
        }

        r.setOnFocusChangeListener((view, has) -> {
            if (has) setAbout(hints.containsKey(view) ? hints.get(view) : pageLine);
            if (snap) {
                if (bg.anim != null) bg.anim.cancel();
                bg.setFocus(has ? 1f : 0f);
            } else {
                Glass.focus(bg, has, 100, 200, ROW_IN, ROW_OUT);
            }
        });
        r.setOnClickListener(view -> {
            LinearLayout mine = list;
            action.run();
            // Values change in place; a page that has just been built has worked its own out.
            if (list == mine) refreshAll();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, u(37));
        lp.bottomMargin = u(3);  // 33 px plates (66 px) on an 80 px pitch, with room to grow
        list.addView(r, lp);
        rowsByLabel.put(label, r);
        return r;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent ev) {
        MainActivity.navSound(this, ev);
        return super.dispatchKeyEvent(ev);
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
            if (w == 1 && !Suggestions.hasToken(this)) enterToken();
        });
    }

    /** Token entry on the TV itself (no adb needed). Accepts the v4 read token or the short v3 API key. */
    void enterToken() {
        Runnable[] save = new Runnable[1];
        EditText in = entry("TMDB Token", "API key or read-access token", Suggestions.prefs(this).getString(Suggestions.K_TOKEN, ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_DONE, () -> save[0].run());
        pageLine = "Get one free at themoviedb.org › Settings › API. The short “API Key” is the easiest to type.";
        save[0] = () -> {
            Suggestions.prefs(this).edit().putString(Suggestions.K_TOKEN, in.getText().toString().trim()).apply();
            Suggestions.changed(this);
            onBackPressed();
        };
        row("Save", null, save[0]);
        row("Clear", null, () -> {
            Suggestions.prefs(this).edit().remove(Suggestions.K_TOKEN).apply();
            Suggestions.changed(this);
            onBackPressed();
        });
        type(in);
    }

    /** Automatic (from the internet address) or a city found by name. */
    void chooseLocation() {
        String[] names = {"Automatic", "Choose a City…"};
        choose("Location", names, Weather.manual(this) ? 1 : 0, w -> {
            if (w == 0) Weather.setPlace(this, null);
            else searchCity();
        });
    }

    void searchCity() {
        Runnable[] go = new Runnable[1];
        EditText in = entry("Choose a City", "City", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, EditorInfo.IME_ACTION_SEARCH, () -> go[0].run());
        LinearLayout mine = list;
        go[0] = () -> {
            String q = in.getText().toString().trim();
            if (q.isEmpty()) return;
            say("Searching…");
            new Thread(() -> {
                List<Weather.Place> found;
                try {
                    found = Weather.search(q);
                } catch (Exception e) {
                    found = null;
                }
                List<Weather.Place> res = found;
                // Only if the search page is still the one showing.
                runOnUiThread(() -> {
                    if (list == mine && !isDestroyed()) showCities(q, res);
                });
            }).start();
        };
        row("Search", null, go[0]);
        type(in);
    }

    void showCities(String q, List<Weather.Place> res) {
        if (res == null) {
            say("Can’t search right now. Check your connection.");
            return;
        }
        if (res.isEmpty()) {
            say("No city called “" + q + "”.");
            return;
        }
        String[] names = new String[res.size()];
        for (int i = 0; i < names.length; i++) {
            Weather.Place pl = res.get(i);
            names[i] = pl.detail.isEmpty() ? pl.name : pl.name + "  ·  " + pl.detail;
        }
        choose("Choose a City", names, -1, w -> {
            Weather.setPlace(this, res.get(w));
            onBackPressed();  // straight back to Weather, showing the new city
        });
    }

    void chooseTypes() {
        String[] keys = {"both", "movie", "tv"};
        String[] names = {"Films and Series", "Films", "Series"};
        int cur = java.util.Arrays.asList(keys).indexOf(Suggestions.types(this));
        choose("Content", names, cur, w -> {
            Suggestions.prefs(this).edit().putString(Suggestions.K_TYPES, keys[w]).apply();
            Suggestions.changed(this);
        });
    }

    void chooseProviders() {
        int n = Suggestions.PROVIDERS.size();
        String[] names = new String[n], notes = new String[n];
        boolean[] on = new boolean[n];
        Set<String> cur = Suggestions.providers(this);
        for (int i = 0; i < n; i++) {
            Suggestions.Provider p = Suggestions.PROVIDERS.get(i);
            names[i] = p.name;
            if (!Suggestions.installed(this, p.pkg)) notes[i] = "Not Installed";
            on[i] = cur.contains(String.valueOf(p.id));
        }
        chooseMany("Apps", names, notes, on, (w, c) -> {
            Set<String> sel = new java.util.HashSet<>();
            for (int i = 0; i < n; i++) if (i == w ? c : on[i]) sel.add(String.valueOf(Suggestions.PROVIDERS.get(i).id));
            if (sel.isEmpty()) {
                say("Choose at least one app.");
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
        choose("Auto-Advance", names, cur, w -> Suggestions.prefs(this).edit().putInt(Suggestions.K_ADVANCE, secs[w]).apply());
    }
}

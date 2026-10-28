package app.glasshome.launcher;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.text.style.UpdateAppearance;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * tvOS-style Search, laid out as the Apple TV app lays it out for a remote with arrow keys: the
 * field across the top, a six-column keyboard on the left and the results beside it, which slide
 * over to fill the screen when you move into them. Shelves of posters for what's on the UK services
 * this TV has (JustWatch, no key). Dictation uses a speech recogniser other than the Google/Gemini
 * voice app, so the mic button searches content instead of opening an assistant. Started by the
 * remote's mic button (ASSIST / VOICE_ASSIST / SEARCH intents).
 */
public class SearchActivity extends Activity {

    static final String KATNISS = "com.google.android.katniss";
    static final int COLS = 6, REQ_MIC = 7;
    static final String RECENTLY = "Recently Searched", BROWSE = "Browse";
    static final String HINT = "Films, Shows, Cast and More";
    /** Recently Searched, as Apple keeps it: ten titles, each for thirty days. */
    static final int RECENT = 10;
    static final long RECENT_MS = 30L * 24 * 60 * 60 * 1000;
    /** What's popular (it picks the Browse tiles' pictures) is asked for again after half an hour. */
    static final long POPULAR_MS = 30 * 60 * 1000L;

    /** The Apple TV app's lockup focus: in over 85 ms (ease in), out over 230 ms (ease out). */
    static final TimeInterpolator FOCUS_IN = new AccelerateInterpolator(1f), FOCUS_OUT = new DecelerateInterpolator(1f);
    /** Its row and page scrolling (a spring of stiffness 150, damping 30) as a curve. */
    static final PathInterpolator SCROLL = new PathInterpolator(0.2f, 0.7f, 0.25f, 1f);
    /** Its keyboard sliding away (stiffness 150, damping 22). */
    static final PathInterpolator SLIDE = new PathInterpolator(0.3f, 0.7f, 0.3f, 1f);

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Each search on its own thread: a new one never waits for one that's been typed past. */
    final ExecutorService searches = Executors.newCachedThreadPool();
    /** The search running now: a newer one stops it. */
    Future<?> running;
    EditText field;
    /** "Press [mic] to dictate", at the field's right while it's empty. */
    TextView dictate;
    float barGlow, fieldFocus;
    android.animation.ValueAnimator barAnim, fieldAnim;
    KeyGrid keys;
    /** The results column: suggestions, then the shelves. Beside the keyboard until you move into it. */
    FrameLayout results;
    /** The field's row, which goes up off the screen as the page scrolls (pageScrolled). */
    View fieldBar;
    /** Where the results start on the page: under the field, and under the suggestions when there are some. */
    int resultsTop;
    HorizontalScrollView hintRow;
    LinearLayout hints;
    LinearLayout grid;
    ScrollView scroll;
    /** What's popular, kept while Glass Home is running, and when it was fetched. */
    static List<SearchBrain.Shelf> popular;
    static long popularAt;
    SearchBrain brain;
    /** Where the shelves start: Apple's 251 px, 27 px under the field. */
    int HEADER;
    /** How far the keyboard slides away, and the results with it. */
    int KEYS_W;
    SpeechRecognizer recognizer;
    boolean listening;
    volatile int generation;
    final Runnable debounced = () -> search(field.getText().toString().trim());

    /** One search result: a title and the UK services (installed on this TV) that stream it. */
    static final class Result {
        String title, year, type, poster, id, backdrop;
        /** JustWatch's own name for it ("The Office (UK)"), to tell same-named programmes apart. */
        String raw = "";
        /** Another result has the same name: the card shows its year to tell them apart. */
        boolean dupe;
        double imdb;
        int tomato = -1;
        boolean certified, rentBuy;
        /** Where it was made (ISO codes), for keeping loose matches to what UK viewers watch. */
        final List<String> countries = new ArrayList<>();
        int tmdbId, votes;
        /** TMDB's popularity: how much it's watched now. */
        double pop;
        /** On a UK broadcaster's own player (iPlayer, ITVX, Channel 4, My5, U). */
        boolean ukChannel;
        /** JustWatch's genre codes, main genre first. */
        final List<String> genres = new ArrayList<>();
        /** For an actor's or director's work: the part they played. */
        String role;
        final List<Suggestions.Item> offers = new ArrayList<>();

        boolean on(int providerId) {
            for (Suggestions.Item it : offers) if (it.providerId == providerId) return true;
            return false;
        }
        /** An app: how to open it, and its banner. */
        Intent appLaunch;
        Drawable appBanner;
        /** A Browse tile: the service it opens, or the genre it browses (JustWatch's code). */
        Suggestions.Provider service;
        String genre;
    }

    /** Stand-ins in the text for Rotten Tomatoes' icons; showIcons() draws them. */
    static final char FRESH = '\uE000', ROTTEN = '\uE001', CERTIFIED = '\uE002';

    /**
     * "IMDb 8.4 · [tomato] 97%": whichever of the two scores the title has. As on Rotten Tomatoes,
     * 60% and up is a red tomato (fresh), below that a green splat (rotten), and the badge for
     * films and series Rotten Tomatoes has certified fresh.
     */
    static String ratings(double imdb, int tomato, boolean certified) {
        StringBuilder b = new StringBuilder();
        if (imdb > 0) b.append(String.format(java.util.Locale.UK, "IMDb %.1f", imdb));
        if (tomato >= 0) b.append(b.length() > 0 ? " \u00b7 " : "").append(certified && tomato >= 60 ? CERTIFIED : tomato >= 60 ? FRESH : ROTTEN)
                .append(' ').append(tomato).append('%');
        return b.toString();
    }

    /** Swaps the stand-ins in a TextView's text for the icons, sized to its text. */
    static void showIcons(TextView t) {
        CharSequence s = t.getText();
        if (s == null) return;
        String str = s.toString();
        if (str.indexOf(FRESH) < 0 && str.indexOf(ROTTEN) < 0 && str.indexOf(CERTIFIED) < 0) return;
        android.text.SpannableString sp = new android.text.SpannableString(s);
        // About the height of the text's capitals, centred on the line: visible, never bigger
        // than the words beside it. The same rule in Search and on title pages.
        int size = Math.round(t.getTextSize() * 0.82f);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != FRESH && ch != ROTTEN && ch != CERTIFIED) continue;
            android.graphics.drawable.Drawable d = icon(t.getContext(), ch, size);
            if (d == null) continue;
            d.setBounds(0, 0, size, size);
            sp.setSpan(new android.text.style.ImageSpan(d, android.os.Build.VERSION.SDK_INT >= 29
                    ? android.text.style.DynamicDrawableSpan.ALIGN_CENTER : android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                    i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        t.setText(sp);
    }

    /** Rotten Tomatoes' icon artwork (res/drawable-nodpi), at this size; null if it won't open. */
    static android.graphics.drawable.Drawable icon(Context c, char kind, int size) {
        // Each kind and size is made once: a page of results asks for dozens, and decoding the
        // picture for every card cost a results page a sixth of its build time.
        String key = kind + ":" + size;
        android.graphics.Bitmap made = ICONS.get(key);
        if (made == null) {
            made = iconBitmap(c, kind, size);
            if (made == null) return null;
            ICONS.put(key, made);
        }
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), made);
    }

    static final java.util.Map<String, android.graphics.Bitmap> ICONS = new java.util.HashMap<>();

    static android.graphics.Bitmap iconBitmap(Context c, char kind, int size) {
        int id = kind == CERTIFIED ? R.drawable.rt_certified : kind == FRESH ? R.drawable.rt_fresh : R.drawable.rt_rotten;
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeResource(c.getResources(), id);
        if (b == null) return null;
        // Shrunk in halves to its exact size (a 168 px picture squeezed to 14 px in one go
        // looks jagged), so the edges stay smooth.
        while (b.getWidth() / 2 >= size) {
            android.graphics.Bitmap h = android.graphics.Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (h != b) b.recycle();
            b = h;
        }
        return android.graphics.Bitmap.createScaledBitmap(b, size, size, true);
    }

    int u(float units) {
        return Ui.px(this, units);
    }

    long createdAt;
    /** Opened over Home (not over another app). */
    boolean overHome;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ClipAudit.watch(this);  // a development check, off unless asked for over adb
        createdAt = SystemClock.uptimeMillis();
        overHome = MainActivity.onScreen;
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF040405, 0xFF050506, 0xFF000000});
        root.setBackground(bg);
        int sw = Ui.screen(this).x;

        // The Apple TV app's field is 139 px down, under its sidebar's pill; with no pill above it
        // here, it sits 48 px higher, with everything under it. 85 px tall, as Apple's.
        int fieldTop = u(45.5f), fieldH = u(42.5f);
        HEADER = u(101.5f);
        KEYS_W = u(256);
        // Behind everything, as on tvOS: the focused title's artwork, soft and dim.
        ambient = ambientLayer();
        ambient.setAlpha(0f);
        root.addView(ambient, new FrameLayout.LayoutParams(-1, -1));

        // The results column, beside the keyboard: suggestions directly under the field (no band
        // when there are none), then the shelves. It clips at its own edges, so posters scrolled
        // out of a row never show beside the keyboard. The whole page scrolls as one, as tvOS's
        // does: moving down the results takes the field and suggestions up off the screen with
        // them, so nothing ever sits under the field.
        results = new FrameLayout(this);
        resultsTop = fieldTop + fieldH;
        hintRow = new HorizontalScrollView(this);
        hintRow.setHorizontalScrollBarEnabled(false);
        hintRow.setClipChildren(false);
        hintRow.setClipToPadding(false);
        hintRow.setPadding(u(40), u(7), u(40), u(5));  // Apple's 14 px under the field
        hints = new LinearLayout(this) {
            @Override public boolean hasOverlappingRendering() {
                return false;  // the pills sit side by side: fading the row needs no extra layer
            }
        };
        hints.setClipChildren(false);
        hintRow.addView(hints);
        hintRow.setVisibility(View.GONE);
        // The shelves start under the suggestions when there are some, and move up when they go.
        hintRow.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> placeResults());

        // Scrolling follows focus row by row (keepInView), not by Android's own nudging.
        scroll = new ScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                return 0;
            }

            // The arrows only ever move focus: at the last row, Down goes nowhere, rather than
            // scrolling the page on into the room left after it.
            @Override public boolean executeKeyEvent(KeyEvent e) {
                return false;
            }

        };
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        // No fading edge: this TV drew it as a strip of the posters a little out of place.
        // A screen's room after the last shelf, so any row can be brought to the top, however few
        // there are (with less, a short page couldn't scroll far enough and the row above peeked in).
        scroll.setPadding(0, resultsTop + u(14), 0, Ui.screen(this).y);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> pageScrolled(y));
        scroll.setVerticalScrollBarEnabled(false);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        scroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        results.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        hLp.topMargin = resultsTop;
        results.addView(hintRow, hLp);
        FrameLayout.LayoutParams rLp = new FrameLayout.LayoutParams(-1, -1);
        results.setTranslationX(KEYS_W);
        root.addView(results, rLp);

        keys = new KeyGrid();
        FrameLayout.LayoutParams kLp = new FrameLayout.LayoutParams(keys.wide(), keys.tall());
        kLp.leftMargin = u(40) - keys.pad;
        kLp.topMargin = HEADER - keys.pad;
        root.addView(keys, kLp);

        // The header: the field, straight on the page. Nothing passes under it, so it needs no
        // shade: the results scroll it away instead.
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, fieldTop, 0, u(18));
        col.setClipChildren(false);
        col.setClipToPadding(false);

        // tvOS 26's field: a magnifier and large words straight on the page, across its width.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(u(12), 0, u(16), 0);
        bar.setBackground(glassBar(fieldH));
        fieldBar = bar;
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(sw - 2 * u(40), fieldH);
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(bar, barLp);

        ImageView glass = new ImageView(this);
        // The magnifier a little taller than the words' capitals, in the hint's grey.
        glass.setImageBitmap(Glyphs.get("magnifier", u(25), 0x66FFFFFF));
        bar.addView(glass, new LinearLayout.LayoutParams(u(25), u(25)));

        field = new EditText(this);
        field.setBackground(null);
        field.setPadding(0, 0, u(4), 0);  // the large words need the bar's whole height
        field.setSingleLine(true);
        field.setTextColor(0x80FFFFFF);  // Apple's typed words are half white, the hint 30%
        field.setHintTextColor(HINT_COLOUR);
        field.setHint(HINT);
        // Apple's Title 2 (57 px bold) in SF. Inter is wider and heavier at the same size, so a touch
        // smaller and semibold reads as the same size and weight.
        field.setTypeface(Ui.font(this, "semibold"));
        Ui.textSize(field, 26);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setOnEditorActionListener((v, a, e) -> {
            // The phone-style keyboard's Search: it goes, and the results take focus when they come.
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
            ui.removeCallbacks(debounced);
            jump = true;
            search(field.getText().toString().trim());
            return true;
        });
        field.setOnKeyListener((v, k, e) -> {
            if (k != KeyEvent.KEYCODE_DPAD_DOWN || e.getAction() != KeyEvent.ACTION_DOWN) return false;
            showKeys();  // down from the field is the keyboard, as on tvOS
            return true;
        });
        field.setOnFocusChangeListener((v, f) -> focusField(f));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) {
                fieldChanged();
                if (quiet || listening) return;  // words put there by Search itself, or being dictated
                ui.removeCallbacks(debounced);
                jump = false;  // typing: focus stays on the keyboard
                String t = s.toString().trim();
                // Back to something already answered (a letter rubbed out): at once, no wait.
                ui.postDelayed(debounced, t.isEmpty() || SearchBrain.cached(t) != null ? 0 : 250);
            }
        });
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(0, -1, 1f);
        fLp.leftMargin = u(10);
        // The words, and at the right the dictation hint or, while listening, a small waveform in
        // its place. The field itself doesn't change: tvOS dictation shows the words as they're
        // heard, with no listening screen of its own.
        FrameLayout fieldBox = new FrameLayout(this);
        fieldBox.addView(field, new FrameLayout.LayoutParams(-1, -1));
        wave = new WaveView();
        wave.setAlpha(0f);
        FrameLayout.LayoutParams wLp = new FrameLayout.LayoutParams(u(150), u(26), Gravity.END | Gravity.CENTER_VERTICAL);
        wLp.rightMargin = u(10);
        fieldBox.addView(wave, wLp);
        dictate = dictateHint();
        fieldBox.addView(dictate, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        bar.addView(fieldBox, fLp);
        root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Focus into the results slides the keyboard away and the results over it; back out
        // slides them back.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (now == null) return;
            boolean inside = isIn(now, scroll);
            slide(inside);
            if (!inside) glide(0);  // the keyboard, field or a suggestion: the page back at its top
            if (inside) {
                lastResult = new WeakReference<>(now);
                ui.removeCallbacks(ambientTop);  // the focused result's background, not the top one's
            }
        });

        setContentView(root);
        enter(root, col);
        brain = new SearchBrain(this);
        brain.recent = recentIds();
        setupRecognizer();
        live = new WeakReference<>(this);
        // Your services, for Browse: found off the main thread (each is a question to the system).
        searches.execute(this::findServices);
        // A query handed over (a name chosen in a title page's cast; adb: --es q "films with tom
        // hardy"): searched at once, and focus goes to the results, as onNewIntent does.
        String given = getIntent().getStringExtra("q");
        if (given != null) {
            showText(given);
            jump = true;
        }
        search(given == null ? "" : given.trim());
        keys.requestFocus();
        if (isVoiceIntent(getIntent())) listenSoon();
    }

    /**
     * The tvOS-style arrival: the page fades up over Home while the search field drops softly into
     * place on a spring. Shelves already known are simply there; results that arrive meanwhile
     * wait for it (show) and then rise in.
     */
    void enter(View root, View header) {
        root.setAlpha(0f);
        header.setTranslationY(-u(30));
        header.setScaleX(0.96f);
        header.setScaleY(0.96f);
        // Results that arrive while the page is arriving wait for it (show), so building their
        // rows never takes frames from the entrance; the page built here in onCreate doesn't. A
        // deadline, not a flag cleared later, which a slow first frame could clear before it's set.
        enterEnd = SystemClock.uptimeMillis() + 660;
        root.post(() -> entering = true);
        root.animate().alpha(1f).setDuration(300).setInterpolator(MainActivity.EASE).withEndAction(() -> {
            // Faded in over Home: from now on the page is solid, so the TV draws only it, not Home
            // underneath as well. (Over another app it stays see-through, so that app isn't stopped.)
            if (overHome && android.os.Build.VERSION.SDK_INT >= 30 && !isFinishing()) setTranslucent(false);
        }).start();
        // As one image while it moves (like the lifting pills): no text re-drawn at each size. Not
        // when it opens listening: then the glow and waveform inside it change every frame, and a
        // layer over them would be drawn again each frame too.
        android.view.ViewPropertyAnimator a = header.animate().translationY(0).scaleX(1f).scaleY(1f).setStartDelay(40)
                .setDuration(620).setInterpolator(Glass.SNAP);
        if (!isVoiceIntent(getIntent())) a.withLayer();
        a.start();
    }

    /** Leaving for Home or another app: next time Search starts fresh (a title page keeps it). */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (asking) return;  // the system's microphone question, over the page: Search stays for the answer
        stopListening();
        finish();
        overridePendingTransition(0, R.anim.page_out);
    }

    @Override
    public void onBackPressed() {
        // From the results, Back goes to the keyboard first (and the top of the page), as on
        // tvOS; from the keyboard it leaves.
        View f = getCurrentFocus();
        if (f != null && isIn(f, results)) {
            glide(0);
            showKeys();
            return;
        }
        super.onBackPressed();
        overridePendingTransition(0, R.anim.page_out);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        live = new WeakReference<>(this);
        // A name chosen in a title page's cast: their films and series, ready to choose from.
        String q = i.getStringExtra("q");
        if (q != null) {
            jump = true;
            setQuery(q);
        } else {
            fresh();  // opened again (Home, the mic button): a new search, never last night's
        }
        if (isVoiceIntent(i)) listenSoon();
    }

    /**
     * A new visit: the field empty, the page before a search at its top, the keyboard focused. For
     * Search opened again while it was still there underneath (the TV slept on it, or the mic
     * button was pressed over it), and for coming back to it after a long time away.
     */
    void fresh() {
        ui.removeCallbacks(debounced);
        ui.removeCallbacks(relanded);
        ui.removeCallbacks(listenRun);
        stopListening();
        setStatus("");
        lastResult = null;
        if (field.length() > 0 || !lastQuery.isEmpty()) setQuery("");
        if (vScroll != null) vScroll.cancel();
        ui.removeCallbacks(vStart);
        scroll.scrollTo(0, 0);
        slide(false);
        showKeys();
    }

    /** Away this long (the TV asleep, the screensaver), Search is fresh when it's back. */
    static final long AWAY_MS = 5 * 60 * 1000L;
    long stoppedAt;

    @Override
    protected void onStop() {
        super.onStop();
        stoppedAt = SystemClock.uptimeMillis();
        stopped = true;
    }

    /** Out of sight: under a title page, or behind another app. */
    boolean stopped;

    @Override
    protected void onRestart() {
        super.onRestart();
        stopped = false;
        ui.removeCallbacks(letGo);
        // Under a title page it keeps its results for Back; otherwise a long absence starts again.
        if (!opened && SystemClock.uptimeMillis() - stoppedAt > AWAY_MS) fresh();
    }

    /**
     * Another app is in front, Search waiting behind it (under the title page you watched from):
     * once you've been away as long as Search takes to start afresh, the pictures kept for moving
     * back along the results are let go, up to 40 MB, so the app you're watching has the TV's
     * memory. Android never closes Home's process to make room (the remote's key service keeps
     * it up), so this is the only way it gets it back. At once, out of sight, when Android says
     * memory is short. A quick trip (a trailer) comes back to them all.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_UI_HIDDEN) ui.postDelayed(letGo, AWAY_MS);
        else if (stopped && level >= TRIM_MEMORY_RUNNING_LOW) letGo.run();
    }

    final Runnable letGo = () -> {
        AMBIENT.evictAll();
        POSTERS.trimToSize(4 << 20);
        DetailActivity.AHEAD.evictAll();
    };

    static boolean isVoiceIntent(Intent i) {
        String a = i == null ? null : i.getAction();
        return a != null && !a.equals(Intent.ACTION_MAIN) && !a.equals(Intent.ACTION_SEARCH);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        MainActivity.navSound(this, ev);  // the same ticks as everywhere else
        // While listening (the remote can't say when its mic button is let go, and ignores it
        // pressed again mid-sentence): OK searches what's been said now, Back stops without one.
        int k = ev.getKeyCode();
        if (listening && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
            if (ev.getAction() == KeyEvent.ACTION_UP && ev.getRepeatCount() == 0) {
                if (k == KeyEvent.KEYCODE_BACK) {
                    ui.removeCallbacks(listenRun);
                    stopListening();
                } else {
                    finishSpeech();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST) {
            if (e.getRepeatCount() == 0) startListening();
            return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListening();
    }

    /** A title page was opened from here (Search is under it until it closes). */
    boolean opened;

    @Override
    protected void onResume() {
        super.onResume();
        // Back from a title page opened here: Recently Searched has it at the front now. After the
        // page's closing fade, so rebuilding takes no frames from it.
        if (opened) {
            opened = false;
            if (lastQuery.isEmpty()) ui.postDelayed(relanded, 350);
        }
    }

    final Runnable relanded = this::reland;

    /** The page before a search again, with its pictures (a search since may have pushed them out). */
    void reland() {
        if (lastQuery.isEmpty()) search("");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Whatever is still on its way is for a page that's gone: nothing may start work now (the
        // executors below refuse it, which would take Home's process down with them).
        generation++;
        ui.removeCallbacksAndMessages(null);
        if (live.get() == this) live.clear();
        if (recognizer != null) recognizer.destroy();
        io.shutdownNow();
        searches.shutdownNow();
        ambientIo.shutdownNow();
        ambientNow.shutdownNow();
        // Search shares Home's process: its pictures don't stay behind in it.
        AMBIENT.evictAll();
        POSTERS.trimToSize(4 << 20);
        waiting.clear();
    }

    static boolean isIn(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == parent) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- voice

    /** A speech recogniser that is not the Google/Gemini voice app (e.g. Google's speech services). */
    ComponentName recognitionService() {
        List<ResolveInfo> l = getPackageManager().queryIntentServices(
                new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        for (ResolveInfo r : l) {
            if (!KATNISS.equals(r.serviceInfo.packageName)) {
                return new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
            }
        }
        return null;
    }

    void setupRecognizer() {
        ComponentName cn = recognitionService();
        if (cn == null) {
            dictate.setVisibility(View.GONE);
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, cn);
        checkLanguage();
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {
                setListening(true);
                quietEnd = false;
                ui.removeCallbacks(quietStop);
                ui.postDelayed(quietStop, QUIET_MS);
                ui.removeCallbacks(maxStop);
                ui.postDelayed(maxStop, 10000);
            }
            // Sound started: words should follow within a couple of seconds. If none do, it was the
            // room (a fan, the telly), which mustn't keep the microphone open.
            @Override public void onBeginningOfSpeech() {
                ui.removeCallbacks(quietStop);
                ui.postDelayed(quietStop, NOISE_MS);
            }
            @Override public void onRmsChanged(float db) {
                // Loudness over the room's own hum: the floor follows the quietest moments, so the
                // wave stays low in silence and rises when you speak.
                if (rmsCount++ == 0 || db < noiseFloor) noiseFloor = db;
                else noiseFloor += (db - noiseFloor) * 0.004f;
                if (wave != null) wave.level = Math.max(0f, Math.min(1f, (db - noiseFloor - 1f) / 6f));
            }
            @Override public void onBufferReceived(byte[] buf) { }
            // You've stopped talking: the light goes, and the answer follows.
            @Override public void onEndOfSpeech() {
                ending = true;
                setListening(false);
            }
            @Override public void onError(int err) {
                ui.removeCallbacks(quietStop);
                ui.removeCallbacks(pauseStop);
                if (!listening && !ending) return;  // stopped on purpose (a second press)
                ending = false;
                boolean quiet = quietEnd && (err == SpeechRecognizer.ERROR_NO_MATCH || err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                quietEnd = false;
                setListening(false);
                unsay();
                if (quiet) return;
                setStatus(err == SpeechRecognizer.ERROR_NO_MATCH || err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? "Didn\u2019t catch that. Try again."
                        : err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "Microphone access is off."
                        : "Voice isn\u2019t available. Type instead.");
            }
            @Override public void onResults(Bundle r) {
                ui.removeCallbacks(quietStop);
                ui.removeCallbacks(pauseStop);
                ending = false;
                setListening(false);
                if (isDestroyed()) return;
                List<String> words = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (words == null || words.isEmpty()) {
                    unsay();
                    return;
                }
                float[] sure = r.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                jump = true;
                if (words.size() == 1) {
                    setQuery(words.get(0));
                    return;
                }
                // Several things it might have heard ("June", "Dune"): the one that names a real,
                // well-known title is searched.
                showText(words.get(0));
                int gen = generation;
                searches.execute(() -> {
                    String best = brain.bestSpoken(words, sure);
                    ui.post(() -> {
                        if (gen != generation || isDestroyed()) return;
                        setQuery(best);
                    });
                });
            }
            @Override public void onPartialResults(Bundle r) {
                List<String> words = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (words != null && !words.isEmpty() && !words.get(0).isEmpty()) {
                    // Talking: listening ends at the first real pause after the words, as if the
                    // button had been let go then (the remote never says when it is).
                    ui.removeCallbacks(quietStop);
                    ui.removeCallbacks(pauseStop);
                    ui.postDelayed(pauseStop, PAUSE_MS);
                    // The words go into the field as they're heard (tvOS dictates into it); the
                    // waveform beside them carries on until you stop.
                    heard = true;
                    showText(words.get(0));
                }
            }
            @Override public void onEvent(int t, Bundle p) { }
        });
    }

    /**
     * British English, on the TV itself: checks the speech service has its UK model and asks it to
     * fetch it if not, so it isn't quietly listening for American English instead.
     */
    void checkLanguage() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB");
        try {
            recognizer.checkRecognitionSupport(i, getMainExecutor(), new android.speech.RecognitionSupportCallback() {
                @Override public void onSupportResult(android.speech.RecognitionSupport s) {
                    boolean have = false, can = false;
                    for (String l : s.getInstalledOnDeviceLanguages()) have |= l.equalsIgnoreCase("en-GB");
                    for (String l : s.getPendingOnDeviceLanguages()) have |= l.equalsIgnoreCase("en-GB");
                    for (String l : s.getSupportedOnDeviceLanguages()) can |= l.equalsIgnoreCase("en-GB");
                    if (!have && can && recognizer != null) recognizer.triggerModelDownload(i);
                }
                @Override public void onError(int error) {
                    // the service can't say: it listens with what it has
                }
            });
        } catch (Exception e) {
            // as above
        }
    }

    /** Words heard so far are in the field; speech has ended and its words are on their way. */
    boolean heard, ending;
    /** The text that was in the field before listening. */
    String before = "";

    void startListening() {
        if (recognizer == null) {
            setListening(false);  // shown as listening while the mic settled (listenSoon)
            setStatus("Voice isn\u2019t available. Type instead.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            asking = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        recognizer.cancel();
        rmsCount = 0;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-GB")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Lean towards what's on TV now and the titles you've looked up before.
            ArrayList<String> likely = new ArrayList<>();
            for (Result r : recentTitles()) likely.add(r.title);
            if (popular != null) for (SearchBrain.Shelf sh : popular) for (Result r : sh.items) likely.add(r.title);
            if (likely.size() > 60) likely = new ArrayList<>(likely.subList(0, 60));
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, likely);
        }
        setListening(true);
        recognizer.startListening(i);
    }

    void stopListening() {
        ui.removeCallbacks(quietStop);
        ui.removeCallbacks(pauseStop);
        ui.removeCallbacks(maxStop);
        ending = false;
        if (recognizer != null && listening) recognizer.cancel();
        if (listening) {
            setListening(false);
            unsay();
        }
    }

    /** Listening ended without a search: the field goes back to what it said before. */
    void unsay() {
        if (heard) showText(before);
        heard = false;
    }

    /** When the remote's mic button was pressed (uptime ms), 0 when Search was opened another way. */
    static volatile long pressedAt;

    /**
     * The TV's own voice system opens and then closes the built-in microphone for about half a
     * second after the remote's mic button is pressed; listening starts once it has let go, or the
     * close cuts our recording off. The page shows it's listening straight away.
     */
    static final long MIC_SETTLE = 150;

    void listenSoon() {
        long wait = pressedAt == 0 ? 0 : pressedAt + MIC_SETTLE - SystemClock.uptimeMillis();
        pressedAt = 0;
        if (wait <= 0) {
            startListening();
            return;
        }
        setListening(true);
        ui.removeCallbacks(listenRun);
        ui.postDelayed(listenRun, wait);
    }

    final Runnable listenRun = this::startListening;

    /** Nothing said this long after the microphone opened: stop quietly, as Siri does. */
    static final long QUIET_MS = 2500;
    /** Sound with no words in it for this long: the room, not you. */
    static final long NOISE_MS = 2000;
    /** A pause this long after words: you've finished, so what you said is searched. */
    static final long PAUSE_MS = 1100;
    final Runnable pauseStop = () -> {
        if (listening && recognizer != null) recognizer.stopListening();
    };
    final Runnable quietStop = () -> {
        if (!listening || recognizer == null) return;
        // End the recording rather than throw it away: anything heard is still searched, and if
        // nothing was said the recogniser reports no match, which is shown quietly.
        quietEnd = true;
        recognizer.stopListening();
    };

    /** The silence timer ended listening: a "nothing heard" result isn't an error to show. */
    boolean quietEnd;

    /** Never listens for more than this, whatever the room sounds like: then searches what it heard. */
    final Runnable maxStop = () -> {
        if (listening && recognizer != null) recognizer.stopListening();
    };

    /** The mic button pressed while listening: stop. Called by the remote-buttons helper. */
    static boolean stopIfListening() {
        SearchActivity a = live.get();
        if (a == null || !a.listening) return false;
        a.ui.post(() -> {
            a.ui.removeCallbacks(a.listenRun);
            a.stopListening();
            a.setStatus("");
        });
        return true;
    }
    static WeakReference<SearchActivity> live = new WeakReference<>(null);

    /** The mic button was let go: stop listening and search what was said so far. */
    static void released() {
        SearchActivity a = live.get();
        if (a != null) a.ui.post(a::finishSpeech);
    }

    void finishSpeech() {
        // Ends the audio; the recogniser then delivers its results.
        if (recognizer != null && listening) recognizer.stopListening();
    }

    /** Asking for the microphone: the system's question opens over Search, which isn't being left. */
    boolean asking;

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        asking = false;
        if (req == REQ_MIC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startListening();
        else if (req == REQ_MIC) setStatus("You can still type to search.");
    }

    void setListening(boolean on) {
        if (listening == on) return;
        listening = on;
        if (on) {
            heard = false;
            before = field.getText().toString();
        }
        animateBar(on ? 1f : 0f);
        fieldChanged();
    }

    // ---------------------------------------------------------------- the field

    static final int HINT_COLOUR = 0x4DFFFFFF;
    /** Words put in the field by Search itself (dictation, a suggestion): not a search of their own. */
    boolean quiet;
    ValueAnimator hintAnim;
    final Runnable hintBack = () -> showHint(HINT);

    /** Puts words in the field without searching them. */
    void showText(String s) {
        quiet = true;
        field.setText(s);
        field.setSelection(field.length());
        quiet = false;
    }

    /** Puts words in the field and searches them now, without the typing wait. */
    void setQuery(String s) {
        showText(s);
        ui.removeCallbacks(debounced);
        search(s.trim());
    }

    /**
     * A message (nothing heard, no microphone) shows in the empty field in place of its hint for
     * three seconds, as tvOS shows nothing under the field. With words in the field, it isn't shown.
     */
    void setStatus(String text) {
        ui.removeCallbacks(hintBack);
        showHint(text.isEmpty() ? HINT : text);
        if (!text.isEmpty()) ui.postDelayed(hintBack, 3000);
    }

    /** The hint crossfades instead of jumping. */
    void showHint(String text) {
        if (text.contentEquals(field.getHint())) return;
        if (hintAnim != null) hintAnim.cancel();
        int full = Color.alpha(HINT_COLOUR);
        hintAnim = ValueAnimator.ofFloat(1f, 0f);
        hintAnim.setDuration(120);
        hintAnim.setInterpolator(MainActivity.EASE);
        hintAnim.addUpdateListener(a -> field.setHintTextColor(Color.argb(Math.round(full * (float) a.getAnimatedValue()), 255, 255, 255)));
        hintAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;
                field.setHint(text);
                hintAnim = ValueAnimator.ofFloat(0f, 1f);
                hintAnim.setDuration(220);
                hintAnim.setInterpolator(MainActivity.EASE);
                hintAnim.addUpdateListener(b -> field.setHintTextColor(Color.argb(Math.round(full * (float) b.getAnimatedValue()), 255, 255, 255)));
                hintAnim.start();
            }
        });
        hintAnim.start();
    }

    /** The dictation hint shows only on an empty field when not listening; suggestions only with words in it. */
    void fieldChanged() {
        boolean empty = field.length() == 0;
        float to = empty && !listening ? 1f : 0f;
        if (dictate.getAlpha() != to) dictate.animate().alpha(to).setDuration(150).setInterpolator(MainActivity.EASE).start();
        if (empty) setHints(Collections.emptyList());
    }

    /**
     * "Press [mic] to dictate": the mic in a small grey disc, in the hint's grey. Apple says "Hold";
     * this TV's remote can't report a hold (KeysService), so here it's a press.
     */
    TextView dictateHint() {
        TextView t = new TextView(this);
        int d = u(19);
        BitmapDrawable mic = new BitmapDrawable(getResources(), micDisc(d));
        mic.setBounds(0, 0, d, d);
        SpannableStringBuilder sb = new SpannableStringBuilder("Press \u00a0 to dictate");
        sb.setSpan(new ImageSpan(mic, android.os.Build.VERSION.SDK_INT >= 29 ? DynamicDrawableSpan.ALIGN_CENTER
                : DynamicDrawableSpan.ALIGN_BASELINE), 6, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(sb);
        t.setTextColor(HINT_COLOUR);
        t.setTypeface(Ui.font(this, "semibold"));
        Ui.textSize(t, 17);
        return t;
    }

    /** A grey disc with a dark microphone in it (the remote's mic button, as tvOS draws it). */
    static Bitmap micDisc(int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = s / 2f, base = s / 2f;
        p.setColor(0x80FFFFFF);
        c.drawCircle(cx, cx, base, p);
        p.setColor(0xFF1C1C1E);
        float w = base * 0.36f, h = base * 0.66f, top = cx - base * 0.56f;
        RectF rect = new RectF(cx - w / 2, top, cx + w / 2, top + h);
        c.drawRoundRect(rect, w / 2, w / 2, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(base * 0.11f);
        p.setStrokeCap(Paint.Cap.ROUND);
        float r = base * 0.34f;
        rect.set(cx - r, top + h - r * 1.35f, cx + r, top + h + r * 0.65f);
        c.drawArc(rect, 0, 180, false, p);
        c.drawLine(cx, top + h + r * 0.65f, cx, cx + base * 0.58f, p);
        return b;
    }

    /** The field with focus (OK opens the TV's own keyboard): its glass capsule shows faintly. */
    void focusField(boolean f) {
        if (fieldAnim != null) fieldAnim.cancel();
        fieldAnim = ValueAnimator.ofFloat(fieldFocus, f ? 1f : 0f);
        fieldAnim.setDuration(f ? 75 : 150);
        fieldAnim.addUpdateListener(a -> {
            fieldFocus = (float) a.getAnimatedValue();
            paintBar();
        });
        fieldAnim.start();
    }

    /**
     * The field's Liquid Glass capsule. At rest there's none, as on tvOS 26 (just the magnifier and
     * the words); it shows faintly while the field itself has focus.
     */
    LayerDrawable glassBar(int h) {
        float r = h / 2f;  // half the field's height: a true capsule, as on tvOS
        barBody = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x30FFFFFF, 0x22FFFFFF, 0x2AFFFFFF});
        barBody.setCornerRadius(r);
        barSheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x12FFFFFF, 0x00FFFFFF});
        // Only the top half: rounded on top with the bar's own radius, square below, so its
        // corners follow the bar's curve exactly instead of poking out.
        barSheen.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        LayerDrawable l = new LayerDrawable(new Drawable[]{barBody, barSheen});
        l.setLayerInsetBottom(1, h / 2);
        paintBar();
        return l;
    }

    GradientDrawable barBody, barSheen;

    void paintBar() {
        int a = Math.round(255 * fieldFocus * 0.6f);
        barBody.setAlpha(a);
        barSheen.setAlpha(a);
    }

    static final int[] WAVE = {0xFFC4EEFF, 0xFFFFFFFF, 0xFFDCCBFF, 0xFFFFFFFF, 0xFFC4EEFF};

    /** The waveform fades in for as long as dictation listens, and out again. */
    void animateBar(float to) {
        if (barAnim != null) barAnim.cancel();
        barAnim = android.animation.ValueAnimator.ofFloat(barGlow, to);
        barAnim.setDuration(to > 0f ? 220 : 300);
        barAnim.setInterpolator(MainActivity.EASE);
        barAnim.addUpdateListener(a -> {
            barGlow = (float) a.getAnimatedValue();
            wave.setAlpha(barGlow);
            if (barGlow > 0f) wave.postInvalidateOnAnimation();
        });
        barAnim.start();
    }

    WaveView wave;

    /**
     * A small tvOS-style dictation waveform at the field's right: thin rounded bars that rise and
     * fall with your voice. It moves gently on its own while listening (this TV's speech service doesn't
     * always report loudness), and louder when it hears you.
     */
    final class WaveView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Matrix m = new android.graphics.Matrix();
        android.graphics.LinearGradient shader;
        float[] h = new float[0];
        float level, shown, t;
        long last;
        /** A bar's width, worked out once rather than every frame. */
        final float bw = Math.max(2f, u(2.2f));

        WaveView() {
            super(SearchActivity.this);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), ht = getHeight();
            if (w <= 0) return;
            // Timed by the clock, not by frames: the same pace however busy the TV is.
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 || now - last > 200 ? 1 / 60f : (now - last) / 1000f;
            last = now;
            t += dt;
            shown += (level - shown) * Math.min(1f, dt * 9f);
            float energy = Math.min(1f, 0.16f + shown * 0.95f);
            float gap = bw * 1.7f, span = w * 0.86f;
            int n = Math.max(8, (int) ((span + gap) / (bw + gap)));
            if (h.length != n) h = new float[n];
            float x0 = (w - (n * bw + (n - 1) * gap)) / 2f;
            if (shader == null) {
                shader = new android.graphics.LinearGradient(0, 0, w * 0.6f, 0, WAVE, null,
                        android.graphics.Shader.TileMode.MIRROR);
            }
            m.setTranslate(t * w * 0.12f, 0);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            // The loudest part drifts along the field, as a voice rises and falls.
            float centre = 0.5f + 0.16f * (float) Math.sin(t * 0.8f) + 0.06f * (float) Math.sin(t * 2.1f);
            for (int i = 0; i < n; i++) {
                float pos = i / (float) (n - 1);
                float d = (pos - centre) / 0.36f;
                float env = (float) Math.exp(-d * d) * (float) Math.sin(Math.PI * pos);
                float wob = 0.55f + 0.45f * (float) Math.sin(t * 5.2f + i * 0.6f) * (float) Math.cos(t * 2.3f - i * 0.23f);
                float target = bw + (ht - bw) * env * energy * wob;
                h[i] += (target - h[i]) * Math.min(1f, dt * 16f);
                float x = x0 + i * (bw + gap), hh = Math.max(bw, h[i]);
                // Quiet ends fade away, so it's a voice in the middle of the field, not a dotted line.
                float edge = (float) Math.sin(Math.PI * pos);
                p.setAlpha(Math.round(255 * Math.min(1f, 0.15f + edge * 1.3f)));
                c.drawRoundRect(x, (ht - hh) / 2f, x + bw, (ht + hh) / 2f, bw / 2f, bw / 2f, p);
            }
            if (getAlpha() > 0f) postInvalidateOnAnimation();
            else last = 0;
        }
    }

    // ---------------------------------------------------------------- keyboard

    /** The keyboard has slid away (focus is in the results). */
    boolean slid;

    /**
     * Focus into the results: the keyboard slides off to the left and the results over to fill the
     * screen, as the Apple TV app's do (560 ms; translations only, so nothing is laid out again).
     * Back out, they slide back. Away, the keyboard can't be found by the arrows, only by Left
     * from the start of a row (showKeys).
     */
    void slide(boolean away) {
        if (slid == away) return;
        slid = away;
        keys.setFocusable(!away);
        keys.animate().translationX(away ? -KEYS_W : 0).setDuration(560).setInterpolator(SLIDE).start();
        results.animate().translationX(away ? 0 : KEYS_W).setDuration(560).setInterpolator(SLIDE).start();
    }

    /** To the keyboard, on the key last used. */
    void showKeys() {
        keys.setFocusable(true);
        keys.requestFocus();
    }

    /** Right from the keyboard: the suggestions beside its top rows, else the results. */
    void rightOfKeys(int row) {
        boolean pills = hintRow.getVisibility() == View.VISIBLE && hints.getChildCount() > 0;
        View to = pills && row <= 1 ? hints.getChildAt(0) : entry();
        if (to == null && pills) to = hints.getChildAt(0);
        if (to != null) to.requestFocus();
    }

    /** The last result focused, to go back to from the keyboard. */
    WeakReference<View> lastResult;

    /** The result to move to from the keyboard: the last one you were on, else the first in view. */
    View entry() {
        View last = lastResult == null ? null : lastResult.get();
        if (last != null && last.isAttachedToWindow() && isIn(last, grid)) return last;
        Rect r = new Rect();
        int top = scroll.getScrollY();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View sec = grid.getChildAt(i);
            if (!(sec instanceof ViewGroup) || sec.getBottom() <= top + u(40)) continue;
            for (View f : sec.getFocusables(View.FOCUS_RIGHT)) {
                f.getDrawingRect(r);
                ((ViewGroup) sec).offsetDescendantRectToMyCoords(f, r);
                if (r.left >= 0 && sec.getTop() + r.bottom > top) return f;
            }
        }
        return null;
    }

    /**
     * The Apple TV app's keyboard for a remote with arrow keys: six columns, a to z in order under a
     * row choosing letters or numbers, then Space, Delete and Clear. One view that draws itself:
     * the focused key is a white platter that glides from key to key (75 ms), fading away when the
     * keyboard loses focus (150 ms) and back when it returns (75 ms). OK types; Delete repeats
     * while held; the remote's number keys (and a real keyboard's letters) type too.
     */
    final class KeyGrid extends View {
        final String[] abc = {"abcdef", "ghijkl", "mnopqr", "stuvwx", "yz"};
        final String[] num = {"123456", "7890.,", "'&-:!?"};
        static final int ROWS = 7;
        /** The platter's corners are Apple's 20 px; the keys' own follow them, 6 px further in. */
        final int pitch = u(36), key = u(32), grow = u(3), radius = u(10) - grow, pad = u(14), padBottom = u(26);
        final int blur = u(12.5f), drop = u(10);
        /** Pixels per design unit, looked up once rather than every frame it draws. */
        final float unit = Ui.unit(SearchActivity.this);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint pic = new Paint(Paint.FILTER_BITMAP_FLAG);
        final ArgbEvaluator ev = new ArgbEvaluator();
        final Map<String, Bitmap> shadows = new HashMap<>();
        final RectF cell = new RectF(), from = new RectF(), to = new RectF(), at = new RectF(), plate = new RectF();
        final Path del = new Path();
        final Paint.FontMetrics fm = new Paint.FontMetrics();
        boolean digits;
        /** The focused key (its row, and its place in that row), and the one before it. */
        int row = 1, place, oldRow = -1, oldPlace;
        /** The column up and down aim for, kept across short rows (z, then Delete, then z again). */
        float want;
        float move = 1f, shown, press;
        ValueAnimator moveAnim, showAnim, pressAnim;

        KeyGrid() {
            super(SearchActivity.this);
            setFocusable(true);
            box(row, place, at);
            from.set(at);
            to.set(at);
            ink.setTextAlign(Paint.Align.CENTER);
        }

        int wide() {
            return 2 * pad + COLS * pitch - (pitch - key);
        }

        int tall() {
            return pad + ROWS * pitch - (pitch - key) + padBottom;
        }

        String letters(int r) {
            String[] set = digits ? num : abc;
            return r - 1 < set.length ? set[r - 1] : "";
        }

        int keys(int r) {
            return r == 0 ? 2 : r == ROWS - 1 ? 3 : letters(r).length();
        }

        /** Columns a key spans: the tabs three, Space, Delete and Clear two, letters one. */
        int span(int r) {
            return r == 0 ? 3 : r == ROWS - 1 ? 2 : 1;
        }

        void box(int r, int i, RectF out) {
            float left = pad + i * span(r) * pitch, top = pad + r * pitch;
            out.set(left, top, left + span(r) * pitch - (pitch - key), top + key);
        }

        float centre(int r, int i) {
            return i * span(r) + span(r) / 2f - 0.5f;
        }

        /** The key in row r nearest the column wanted. */
        int nearest(int r, float col) {
            int best = 0;
            for (int i = 1; i < keys(r); i++) if (Math.abs(centre(r, i) - col) < Math.abs(centre(r, best) - col)) best = i;
            return best;
        }

        void go(int r, int i, boolean across) {
            oldRow = row;
            oldPlace = place;
            from.set(at);
            row = r;
            place = i;
            if (across) want = centre(r, i);
            box(r, i, to);
            if (moveAnim != null) moveAnim.cancel();
            moveAnim = ValueAnimator.ofFloat(0f, 1f);
            moveAnim.setDuration(75);
            moveAnim.setInterpolator(FOCUS_OUT);
            moveAnim.addUpdateListener(a -> {
                move = (float) a.getAnimatedValue();
                at.set(from.left + (to.left - from.left) * move, from.top + (to.top - from.top) * move,
                        from.right + (to.right - from.right) * move, from.bottom + (to.bottom - from.bottom) * move);
                invalidate();
            });
            moveAnim.start();
        }

        @Override protected void onFocusChanged(boolean f, int dir, Rect prev) {
            super.onFocusChanged(f, dir, prev);
            if (showAnim != null) showAnim.cancel();
            showAnim = ValueAnimator.ofFloat(shown, f ? 1f : 0f);
            showAnim.setDuration(f ? 75 : 150);
            showAnim.setInterpolator(f ? FOCUS_IN : FOCUS_OUT);
            showAnim.addUpdateListener(a -> {
                shown = (float) a.getAnimatedValue();
                invalidate();
            });
            showAnim.start();
        }

        /** A press sinks the platter halfway towards the key (80 ms), and it comes back on release. */
        void pressed(boolean down) {
            if (pressAnim != null) pressAnim.cancel();
            pressAnim = ValueAnimator.ofFloat(press, down ? 1f : 0f);
            pressAnim.setDuration(80);
            pressAnim.setInterpolator(down ? FOCUS_OUT : FOCUS_IN);
            pressAnim.addUpdateListener(a -> {
                press = (float) a.getAnimatedValue();
                invalidate();
            });
            pressAnim.start();
        }

        @Override public boolean onKeyDown(int k, KeyEvent e) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (place > 0) go(row, place - 1, true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (place + 1 < keys(row)) go(row, place + 1, true);
                    else rightOfKeys(row);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP: {
                    int r = row - 1;
                    while (r >= 0 && keys(r) == 0) r--;
                    if (r < 0) field.requestFocus();
                    else go(r, nearest(r, want), false);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    int r = row + 1;
                    while (r < ROWS && keys(r) == 0) r++;
                    if (r < ROWS) go(r, nearest(r, want), false);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (e.getRepeatCount() == 0) {
                        pressed(true);
                        type();
                    } else if (row == ROWS - 1 && place == 1) {
                        erase();  // Delete repeats while held
                    }
                    return true;
                case KeyEvent.KEYCODE_DEL:
                    erase();
                    return true;
                case KeyEvent.KEYCODE_SPACE:
                    space();
                    return true;
                default:
                    int ch = e.getUnicodeChar();
                    if (ch > 0 && Character.isLetterOrDigit(ch)) {
                        add(Character.toLowerCase((char) ch));
                        return true;
                    }
                    return super.onKeyDown(k, e);
            }
        }

        @Override public boolean onKeyUp(int k, KeyEvent e) {
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                pressed(false);
                return true;
            }
            return super.onKeyUp(k, e);
        }

        void type() {
            if (row == 0) {
                boolean d = place == 1;
                if (d != digits) {
                    digits = d;
                    invalidate();
                }
            } else if (row == ROWS - 1) {
                if (place == 0) space();
                else if (place == 1) erase();
                else field.getText().clear();
            } else {
                add(letters(row).charAt(place));
            }
        }

        void add(char c) {
            field.getText().append(c);
            field.setSelection(field.length());
        }

        void space() {
            Editable t = field.getText();
            if (t.length() > 0 && t.charAt(t.length() - 1) != ' ') add(' ');
        }

        void erase() {
            Editable t = field.getText();
            if (t.length() > 0) t.delete(t.length() - 1, t.length());
        }

        /** The platter's soft shadow (Apple's: 20 px down, blurred 25, black at 30%), made once per size. */
        Bitmap shadow(int w, int h) {
            String k = w + "x" + h;
            Bitmap b = shadows.get(k);
            if (b == null) {
                b = Bitmap.createBitmap(w + 2 * blur, h + 2 * blur + drop, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.BLACK);
                p.setShadowLayer(blur, 0, drop, 0x4D000000);
                RectF r = new RectF(blur, blur, blur + w, blur + h);
                c.drawRoundRect(r, radius + grow, radius + grow, p);
                p.clearShadowLayer();
                p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
                c.drawRoundRect(r, radius + grow, radius + grow, p);
                shadows.put(k, b);
            }
            return b;
        }

        /** How focused key (r, i) looks now: 1 for the one the platter is on, fading between keys. */
        float lit(int r, int i) {
            if (r == row && i == place) return shown * move;
            if (r == oldRow && i == oldPlace) return shown * (1f - move);
            return 0f;
        }

        @Override protected void onDraw(Canvas c) {
            // The chosen set's tab, and Space, Delete and Clear, are grey caps.
            fill.setColor(0x26FFFFFF);
            box(0, digits ? 1 : 0, cell);
            c.drawRoundRect(cell, radius, radius, fill);
            fill.setColor(0x1AFFFFFF);
            for (int i = 0; i < 3; i++) {
                box(ROWS - 1, i, cell);
                c.drawRoundRect(cell, radius, radius, fill);
            }
            // The focus platter, under the glyphs.
            if (shown > 0f) {
                float g = grow * (1f - 0.5f * press);
                plate.set(at.left - g, at.top - g, at.right + g, at.bottom + g);
                pic.setAlpha(Math.round(255 * shown));
                Bitmap sh = shadow(Math.round(to.width() + 2 * grow), Math.round(to.height() + 2 * grow));
                cell.set(plate.left - blur, plate.top - blur, plate.right + blur, plate.bottom + blur + drop);
                c.drawBitmap(sh, null, cell, pic);
                fill.setColor(Color.WHITE);
                fill.setAlpha(Math.round(255 * shown));
                c.drawRoundRect(plate, radius + g, radius + g, fill);
            }
            // Letters or numbers and symbols.
            ink.setTypeface(Ui.font(SearchActivity.this, "regular"));
            ink.setTextSize(22.5f * unit);
            for (int r = 1; r < ROWS - 1; r++) {
                String l = letters(r);
                for (int i = 0; i < l.length(); i++) glyph(c, r, i, l, i, i + 1, 0xA6FFFFFF);
            }
            // The tabs: the chosen set white, the other grey.
            ink.setTypeface(Ui.font(SearchActivity.this, "semibold"));
            ink.setTextSize(14.5f * unit);
            glyph(c, 0, 0, "abc", digits ? 0x80FFFFFF : Color.WHITE);
            glyph(c, 0, 1, "123", digits ? Color.WHITE : 0x80FFFFFF);
            ink.setTypeface(Ui.font(SearchActivity.this, "medium"));
            ink.setTextSize(12.5f * unit);
            glyph(c, ROWS - 1, 0, "Space", 0xD9FFFFFF);
            glyph(c, ROWS - 1, 2, "Clear", 0xD9FFFFFF);
            deleteKey(c);
        }

        void glyph(Canvas c, int r, int i, String s, int colour) {
            glyph(c, r, i, s, 0, s.length(), colour);
        }

        void glyph(Canvas c, int r, int i, String s, int from, int to, int colour) {
            box(r, i, cell);
            ink.setColor(colourOf(r, i, colour));
            ink.getFontMetrics(fm);
            c.drawText(s, from, to, cell.centerX(), cell.centerY() - (fm.ascent + fm.descent) / 2f, ink);
        }

        /** A key's glyph colour: its own, turning dark as the platter comes onto it. */
        int colourOf(int r, int i, int colour) {
            float l = lit(r, i);
            return l <= 0f ? colour : (int) ev.evaluate(l, colour, 0xFF111114);
        }

        /**
         * ⌫ as Apple draws it (delete.left): a tag pointing left, its point a little over a third of
         * its length, every corner rounded, the cross centred in the square part.
         */
        final android.graphics.CornerPathEffect corners = new android.graphics.CornerPathEffect(2.4f * unit);

        void deleteKey(Canvas c) {
            box(ROWS - 1, 1, cell);
            float h = 13f * unit, w = 19f * unit, l = cell.centerX() - w / 2, t = cell.centerY() - h / 2, tip = h * 0.62f;
            del.rewind();
            del.moveTo(l, t + h / 2);
            del.lineTo(l + tip, t);
            del.lineTo(l + w, t);
            del.lineTo(l + w, t + h);
            del.lineTo(l + tip, t + h);
            del.close();
            ink.setColor(colourOf(ROWS - 1, 1, 0xD9FFFFFF));
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(1.5f * unit);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setPathEffect(corners);
            c.drawPath(del, ink);
            ink.setPathEffect(null);
            float x = l + tip + (w - tip) / 2f - 0.6f * unit, y = t + h / 2f, d = h * 0.19f;
            c.drawLine(x - d, y - d, x + d, y + d, ink);
            c.drawLine(x - d, y + d, x + d, y - d, ink);
            ink.setStyle(Paint.Style.FILL);
        }
    }

    // ---------------------------------------------------------------- suggestions

    /** The suggestions showing, and what was typed when they were made. */
    List<String> hinted = new ArrayList<>();
    String hintedFor = "";

    /**
     * Up to five suggestions in one row directly under the field, as the Apple TV app shows them:
     * the titles that match what's typed and, for a name, the person. Made from answers already
     * here, so they cost no requests.
     */
    void suggestFrom(List<SearchBrain.Shelf> shelves) {
        List<String> next = new ArrayList<>();
        boolean titles = false;
        for (SearchBrain.Shelf sh : shelves) {
            if (sh.person != null) {
                hint(next, sh.person);
                continue;
            }
            if (titles || isApps(sh)) continue;
            titles = true;
            for (Result r : sh.items) if (SearchBrain.matchScore(lastQuery, r.title) >= 85) hint(next, r.title);
        }
        if (next.size() > 5) next = new ArrayList<>(next.subList(0, 5));
        setHints(next);
    }

    static void hint(List<String> l, String s) {
        String low = s.toLowerCase(Locale.UK);
        if (!l.contains(low)) l.add(low);
    }

    /** New suggestions crossfade in (150 ms out, 200 ms in); the row is there only while it has some. */
    void setHints(List<String> next) {
        if (next.equals(hinted) && lastQuery.equals(hintedFor)) return;
        hinted = new ArrayList<>(next);
        hintedFor = lastQuery;
        hints.animate().cancel();
        if (next.isEmpty() || field.length() == 0 || hintRow.getVisibility() != View.VISIBLE) {
            fillHints(next);
            hints.setAlpha(0f);
            hints.animate().alpha(1f).setDuration(200).start();
            return;
        }
        hints.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            fillHints(hinted);
            hints.animate().alpha(1f).setDuration(200).start();
        }).start();
    }

    void fillHints(List<String> next) {
        View f = getCurrentFocus();
        int at = f != null && f.getParent() == hints ? hints.indexOfChild(f) : -1;
        hints.removeAllViews();
        for (String s : next) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, u(32));
            if (hints.getChildCount() > 0) lp.leftMargin = u(10);
            hints.addView(pill(s), lp);
        }
        boolean show = !next.isEmpty() && field.length() > 0;
        hintRow.setVisibility(show ? View.VISIBLE : View.GONE);
        hintRow.scrollTo(0, 0);
        if (!show) placeResults();  // gone, so no layout of its own to say so
        // A suggestion chosen stays chosen as the row changes under it.
        if (at >= 0) {
            if (show) hints.getChildAt(Math.min(at, hints.getChildCount() - 1)).requestFocus();
            else showKeys();
        }
    }

    /** Text whose colour changes without the text being laid out again (the rest of a suggestion). */
    static final class Ink extends CharacterStyle implements UpdateAppearance {
        int colour;

        Ink(int colour) {
            this.colour = colour;
        }

        @Override public void updateDrawState(TextPaint tp) {
            tp.setColor(colour);
        }
    }

    /** One suggestion: a magnifier and the words, the part typed in white and the rest at half. */
    View pill(String s) {
        Glass white = new Glass(u(16), u(0.6f), Glass.REGULAR);
        TextView t = new TextView(this);
        String typed = lastQuery.toLowerCase(Locale.UK).trim();
        int from = typed.isEmpty() ? -1 : s.indexOf(typed);
        SpannableString sp = new SpannableString(s);
        List<Ink> rest = new ArrayList<>();
        if (from >= 0) {
            if (from > 0) {
                Ink k = new Ink(0x80FFFFFF);
                rest.add(k);
                sp.setSpan(k, 0, from, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (from + typed.length() < s.length()) {
                Ink k = new Ink(0x80FFFFFF);
                rest.add(k);
                sp.setSpan(k, from + typed.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        t.setText(sp);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Ui.font(this, "medium"));
        // Apple's: Headline text and a 40 px magnifier, at most 500 px wide.
        Ui.textSize(t, 19);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setMaxWidth(u(250));
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(u(12), 0, u(16), 0);
        int g = u(20);
        BitmapDrawable glyph = new BitmapDrawable(getResources(), Glyphs.get("magnifier", g, Color.WHITE));
        glyph.setBounds(0, 0, g, g);
        t.setCompoundDrawables(glyph, null, null, null);
        t.setCompoundDrawablePadding(u(6));
        t.setBackground(white);
        t.setFocusable(true);
        // The words darken with the plate as it fades, not ahead of it.
        ArgbEvaluator ev = new ArgbEvaluator();
        white.tint = f -> {
            int on = (int) ev.evaluate(f, Color.WHITE, 0xFF111114);
            glyph.setTint(on);
            for (Ink k : rest) k.colour = (int) ev.evaluate(f, 0x80FFFFFF, 0x99111114);
            t.setTextColor(on);
        };
        t.setOnFocusChangeListener((v, has) -> {
            Glass.focus(white, has, 75, 150, FOCUS_IN, FOCUS_OUT);
            // Drawn afresh at each size (no layer): the text recolours every frame of the fade.
            Glass.lift(v, has, 1.06f, u(4), 1f, false, 160, FOCUS_OUT);
        });
        t.setOnClickListener(v -> setQuery(s));
        t.setOnKeyListener((v, k, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                View to = entry();
                if (to != null) to.requestFocus();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // Along the suggestions; past the first, the keyboard; past the last, nothing
                // (not a poster somewhere below).
                boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT;
                View next = v.focusSearch(left ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                if (next != null && next.getParent() == hints) return false;
                if (left) showKeys();
                return true;
            }
            return false;
        });
        return t;
    }

    // ---------------------------------------------------------------- search

    /** What is being searched for now ("" while the field is empty). */
    String lastQuery = "";

    void search(String q) {
        if (isDestroyed()) return;
        lastQuery = q;
        int gen = ++generation;
        if (running != null) running.cancel(true);
        boolean tile = fromTile;
        fromTile = false;
        if (q.isEmpty()) {
            land(gen);
            return;
        }
        running = searches.submit(() -> {
            List<SearchBrain.Shelf> shelves = new ArrayList<>();
            boolean failed = false;
            try {
                // The top results go up as soon as they're known; related titles and the rest
                // slide in after them when they arrive.
                shelves.addAll(brain.answer(q, early -> {
                    if (gen != generation) return;
                    List<SearchBrain.Shelf> first = mine(q, early, tile);
                    if (gen != generation) return;
                    ui.post(() -> {
                        if (gen == generation) show(first);
                    });
                    try {
                        searches.execute(() -> posters(gen, first));
                    } catch (Exception e) {
                        // the page has closed
                    }
                }, () -> gen != generation));
            } catch (Exception e) {
                failed = true;
            }
            if (gen != generation) return;
            shelves = mine(q, shelves, tile);
            if (gen != generation) return;
            List<SearchBrain.Shelf> all = shelves;
            boolean offline = failed && all.isEmpty();
            ui.post(() -> {
                if (gen == generation) show(all, offline);
            });
            posters(gen, all);
        });
    }

    /**
     * The page before a search, as Apple's: Recently Searched and the Browse grid, at once. What's
     * popular, which picks Browse's pictures, is fetched again only when it's half an hour old.
     * Pictures wait until the page has arrived.
     */
    void land(int gen) {
        // Within a visit Browse keeps its first pictures (popular fetched again behind it is for the
        // next visit), so coming back to this page changes nothing under you but Recently Searched.
        List<SearchBrain.Shelf> kept = landed.isEmpty() ? popular : landed;
        show(kept == null ? new ArrayList<>() : kept);
        boolean fresh = kept != null && System.currentTimeMillis() - popularAt < POPULAR_MS;
        running = searches.submit(() -> {
            List<SearchBrain.Shelf> got = kept;
            if (!fresh) {
                try {
                    got = popularShelves();
                } catch (Exception e) {
                    got = kept;  // offline: what we had
                }
                if (gen != generation) return;
                if (got != null && !got.isEmpty() && got != kept) {
                    popular = got;
                    popularAt = System.currentTimeMillis();
                }
                if (kept == null && got != null) {
                    List<SearchBrain.Shelf> first = got;
                    ui.post(() -> {
                        if (gen == generation) show(first);
                    });
                }
            }
            long wait = createdAt + 700 - SystemClock.uptimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (gen != generation) return;
            // Popular fetched again behind the one from memory is for the next visit's pictures.
            List<SearchBrain.Shelf> shown = kept != null ? kept : got;
            List<SearchBrain.Shelf> onScreen = new ArrayList<>();
            onScreen.add(new SearchBrain.Shelf(RECENTLY, recentTitles()));
            browseArt(gen, shown);
            posters(gen, onScreen);
        });
    }

    List<SearchBrain.Shelf> popularShelves() throws Exception {
        List<SearchBrain.Shelf> shelves = new ArrayList<>();
        List<Result> all = brain.popularNow(), films = new ArrayList<>(), series = new ArrayList<>();
        for (Result r : all) ("tv".equals(r.type) ? series : films).add(r);
        if (!series.isEmpty()) shelves.add(new SearchBrain.Shelf("Popular Series", series));
        if (!films.isEmpty()) shelves.add(new SearchBrain.Shelf("Popular Films", films));
        return shelves;
    }

    /**
     * Your own: the apps that match, placed where they belong among the shelves. From a service's
     * Browse tile (tile), its titles are what's wanted, not its app.
     */
    List<SearchBrain.Shelf> mine(String q, List<SearchBrain.Shelf> in, boolean tile) {
        List<SearchBrain.Shelf> shelves = SearchBrain.copy(in);
        SearchBrain.Query read = SearchBrain.read(q);
        String key = read.text.isEmpty() ? q : read.text;
        List<Result> apps = matchApps(key);
        if (!apps.isEmpty()) {
            // "netflix", "bbc": the app is what's wanted; otherwise apps come last.
            boolean exact = !tile && SearchBrain.matchScore(key, apps.get(0).title) >= 85;
            shelves.add(exact ? 0 : shelves.size(), new SearchBrain.Shelf("Apps", apps));
        }
        return shelves;
    }

    /** The TV's apps (name and how to open them), read once a visit. */
    List<Object[]> apps;

    synchronized List<Object[]> apps() {
        if (apps == null) {
            apps = new ArrayList<>();
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
                if (r.activityInfo.packageName.equals(getPackageName())) continue;
                apps.add(new Object[]{String.valueOf(r.loadLabel(pm)), r});
            }
        }
        return apps;
    }

    /** Installed apps whose name contains the query. */
    List<Result> matchApps(String q) {
        List<Result> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        String lq = q.toLowerCase(Locale.UK);
        for (Object[] app : apps()) {
            String label = (String) app[0];
            boolean hit = label.toLowerCase(Locale.UK).contains(lq) || SearchBrain.matchScore(q, label) >= 85;
            if (!hit) continue;
            ResolveInfo r = (ResolveInfo) app[1];
            Result a = new Result();
            a.title = label;
            a.type = "app";
            a.appLaunch = Suggestions.launchIntent(this, r.activityInfo.packageName);
            if (a.appLaunch == null) continue;
            a.appBanner = r.activityInfo.loadBanner(pm);
            if (a.appBanner == null) a.appBanner = r.loadIcon(pm);
            out.add(a);
            if (out.size() >= COLS) break;
        }
        return out;
    }

    // ---------------------------------------------------------------- pictures

    /**
     * Posters ready to show, by address: handed to the graphics chip when made, and kept within
     * 16 MB, so returning to a search or the page before one shows them at once.
     */
    static final android.util.LruCache<String, Bitmap> POSTERS = new android.util.LruCache<String, Bitmap>(16 << 20) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b.getWidth() * b.getHeight() * 4;
        }
    };
    static final Set<String> DECODING = ConcurrentHashMap.newKeySet();

    /** A picture's views, waiting for it to arrive. */
    static final class Slot {
        final ImageView art;
        final Sec sec;

        Slot(ImageView art, Sec sec) {
            this.art = art;
            this.sec = sec;
        }
    }

    final Map<String, List<Slot>> waiting = new HashMap<>();

    void await(String url, ImageView art, Sec s) {
        if (url == null) return;
        List<Slot> l = waiting.get(url);
        if (l == null) waiting.put(url, l = new ArrayList<>());
        l.add(new Slot(art, s));
    }

    /** Posters fill in as they arrive, in order, each fading into its placeholder. */
    void posters(int gen, List<SearchBrain.Shelf> shelves) {
        for (SearchBrain.Shelf sh : shelves) {
            for (Result r : sh.items) {
                if (gen != generation) return;
                String url = r.poster;
                if (url == null || "app".equals(r.type) || POSTERS.get(url) != null || !DECODING.add(url)) continue;
                try {
                    Bitmap bm = Suggestions.image(this, url, "", u(150));
                    if (bm == null) continue;
                    Bitmap shown = Suggestions.onGpu(bm);
                    POSTERS.put(url, shown);
                    ui.post(() -> fill(url, shown));
                } finally {
                    DECODING.remove(url);
                }
            }
        }
    }

    void fill(String url, Bitmap bm) {
        if (isDestroyed()) return;
        List<Slot> l = waiting.remove(url);
        if (l == null) return;
        long now = SystemClock.uptimeMillis();
        for (Slot s : l) {
            s.art.setImageBitmap(bm);
            // In a shelf rising as one picture it's simply there: a fade would make the whole
            // picture be drawn again every frame.
            if (s.sec != null && s.sec.riseAt > 0 && now - s.sec.riseAt < 600) continue;
            s.art.setImageAlpha(0);
            ObjectAnimator a = ObjectAnimator.ofInt(s.art, "imageAlpha", 0, 255);
            a.setDuration(240);
            a.setInterpolator(new LinearInterpolator());
            a.start();
        }
    }

    // ---------------------------------------------------------------- results

    /** The shelves on screen now. */
    List<SearchBrain.Shelf> showing = new ArrayList<>();

    /** A shelf on screen: its results and the views that show them. */
    static final class Sec {
        LinearLayout view;
        TextView heading;
        /** Recently Searched, with its compact cards. */
        boolean recent;
        /** Top Results: its posters say what each is ("Series · Crime") under the title, as Apple's. */
        boolean top;
        /** A shelf that scrolls sideways, or (grid) rows of six. */
        HorizontalScrollView hs;
        LinearLayout row;
        boolean grid;
        final List<Result> items = new ArrayList<>();
        int built, firstBuild, w, gap;
        /** When its rise starts (uptime ms; 0 when it doesn't rise), and its stagger. */
        long riseAt, riseDelay;
        ObjectAnimator scroller;
        int scrollTo = -1;
    }

    static boolean isApps(SearchBrain.Shelf sh) {
        return !sh.items.isEmpty() && "app".equals(sh.items.get(0).type);
    }

    /** a shows everything b does, in the same order (and maybe more after it). */
    static boolean startsWith(SearchBrain.Shelf a, SearchBrain.Shelf b) {
        if (!a.heading.equals(b.heading) || a.items.size() < b.items.size()) return false;
        for (int k = 0; k < b.items.size(); k++) {
            Result x = a.items.get(k), y = b.items.get(k);
            if (!x.title.equals(y.title) || !TextUtils.equals(x.year, y.year)) return false;
        }
        return true;
    }

    /** Results that arrive while the page is still arriving wait for it: after its first frame, until enterEnd (uptime ms). */
    boolean entering;
    long enterEnd;
    /** The Popular shelves on the page before a search. */
    List<SearchBrain.Shelf> landed = new ArrayList<>();
    Runnable pendingShow;
    /** Focus goes to the first result when they come (a spoken search, the TV keyboard's Search). */
    boolean jump;
    /** The search is a Browse tile's (Netflix): its titles come first, and its app goes last. */
    boolean fromTile;
    int rmsCount;
    float noiseFloor;
    View firstCard;

    void show(List<SearchBrain.Shelf> shelves) {
        show(shelves, false);
    }

    /**
     * Results as tvOS shelves: a heading over a row that scrolls sideways (a single shelf of
     * results is a grid). Top Results, someone's work and Apps for a search;
     * Recently Searched and the Browse grid before one. Nothing found is
     * said in their place (failed: it couldn't connect).
     */
    void show(List<SearchBrain.Shelf> data, boolean failed) {
        if (isDestroyed()) return;
        ui.removeCallbacks(pendingShow);
        long now = SystemClock.uptimeMillis();
        if (entering && !data.isEmpty() && now < enterEnd) {
            pendingShow = () -> show(data, failed);
            ui.postDelayed(pendingShow, enterEnd - now);
            return;
        }
        if (lastQuery.isEmpty()) landed = data;
        List<SearchBrain.Shelf> shelves = new ArrayList<>(lastQuery.isEmpty() ? landing(data) : data);
        shelves.removeIf(sh -> sh.items.isEmpty());
        if (!lastQuery.isEmpty()) suggestFrom(shelves);
        if (extend(shelves)) {
            jumpIn();
            return;
        }
        boolean hadFocus = grid.hasFocus();
        showing = new ArrayList<>(shelves);
        grid.removeAllViews();
        waiting.clear();
        tiles.clear();
        firstCard = null;
        lastResult = null;
        if (vScroll != null) vScroll.cancel();
        ui.removeCallbacks(vStart);
        scroll.scrollTo(0, 0);
        vTarget = 0;
        // One shelf of results fills the screen as a grid, as Apple's does.
        int found = 0;
        for (SearchBrain.Shelf sh : shelves) if (!isApps(sh)) found++;
        boolean asGrid = !lastQuery.isEmpty() && found == 1;
        int[] delay = {0};
        List<Result> all = new ArrayList<>();
        for (SearchBrain.Shelf sh : shelves) {
            markDupes(sh);
            // Before a search, Browse is Apple's grid of tiles, under Recently Searched's row.
            boolean browse = lastQuery.isEmpty() && BROWSE.equals(sh.heading);
            section(sh, delay, -1, browse || asGrid && !isApps(sh));
            all.addAll(sh.items);
        }
        if (!lastQuery.isEmpty() && shelves.isEmpty()) nothingFound(failed);
        // Backgrounds are got ready for results; before a search they load as you move to one.
        if (!lastQuery.isEmpty()) prepareAmbient(all);
        // The background follows the top result once typing pauses (each letter's top result isn't
        // fetched and decoded on the way), and clears when there's nothing to show.
        Result first = null;
        for (Result r : all) if (!"app".equals(r.type)) {
            first = r;
            break;
        }
        ui.removeCallbacks(ambientTop);
        if (first != null && !lastQuery.isEmpty()) {
            Result top = first;
            ambientTop = () -> ambientFor(top);
            ui.postDelayed(ambientTop, 400);
        } else {
            ambientFor = null;
            ambient.animate().cancel();
            ambient.animate().alpha(0f).setDuration(300).setInterpolator(MainActivity.EASE).start();
        }
        // Focus was on a card that's gone: the new first one, or (nothing found) the keyboard, never
        // the empty page itself.
        if (hadFocus && (!jump || firstCard == null)) {
            if (firstCard != null) firstCard.requestFocus();
            else showKeys();
        }
        jumpIn();
    }

    void jumpIn() {
        if (jump && firstCard != null) firstCard.requestFocus();
        jump = false;
    }

    /**
     * New results that keep every shelf on screen, in the same order, each starting with what it
     * shows now (related titles after Top Results, someone's work arriving): only what's new is
     * added, so nothing on screen jumps or blinks. Recently Searched with a title opened since
     * (at its front now) is made again on its own, so coming back from a title keeps your place.
     */
    boolean extend(List<SearchBrain.Shelf> next) {
        if (showing.isEmpty() || grid.getChildCount() != showing.size()) return false;
        // A lone shelf is a grid: with another beside it, the page is laid out again as rows.
        int found = 0;
        for (SearchBrain.Shelf sh : next) if (!isApps(sh)) found++;
        for (int i = 0; found > 1 && i < grid.getChildCount(); i++) if (((Sec) grid.getChildAt(i).getTag()).grid) return false;
        int[] match = new int[next.size()];
        boolean[] again = new boolean[next.size()];
        int p = 0;
        for (int i = 0; i < next.size(); i++) {
            SearchBrain.Shelf sh = next.get(i), was = p < showing.size() ? showing.get(p) : null;
            again[i] = was != null && !startsWith(sh, was) && RECENTLY.equals(sh.heading) && RECENTLY.equals(was.heading);
            match[i] = was != null && (again[i] || startsWith(sh, was)) ? p++ : -1;
        }
        if (p < showing.size()) return false;
        int[] delay = {0};
        List<Result> added = new ArrayList<>();
        for (int i = 0; i < next.size(); i++) {
            SearchBrain.Shelf sh = next.get(i);
            markDupes(sh);
            if (match[i] >= 0 && !again[i]) {
                Sec s = (Sec) grid.getChildAt(i).getTag();
                if (sh.items.size() <= s.items.size()) continue;
                List<Result> more = new ArrayList<>(sh.items.subList(s.items.size(), sh.items.size()));
                queue(s, more, 0);
                added.addAll(more);
            } else {
                section(sh, delay, i, false);
                added.addAll(sh.items);
                if (again[i]) replaced((Sec) grid.getChildAt(i + 1).getTag(), (Sec) grid.getChildAt(i).getTag());
            }
        }
        showing = new ArrayList<>(next);
        if (!added.isEmpty() && !lastQuery.isEmpty()) prepareAmbient(added);
        return true;
    }

    /**
     * A shelf made again (now) in place of the one just after it (old), which goes. Focus in the old
     * one follows its title to the new one (the title just opened is at the front now), else goes
     * to the new one's first card; it moves before the old shelf goes, so it never lands elsewhere.
     */
    void replaced(Sec old, Sec now) {
        View first = focusable(now.row.getChildAt(0));
        View f = old.view.hasFocus() ? getCurrentFocus() : null;
        if (f != null) {
            int k = old.row.indexOfChild(f);
            Result was = k >= 0 && k < old.items.size() ? old.items.get(k) : null;
            View to = first;
            for (int j = 0; was != null && j < now.row.getChildCount(); j++) {
                Result r = now.items.get(j);
                if (r.title.equals(was.title) && TextUtils.equals(r.year, was.year)) to = focusable(now.row.getChildAt(j));
            }
            if (to != null) to.requestFocus();
        }
        if (firstCard != null && isIn(firstCard, old.view)) firstCard = first;
        grid.removeView(old.view);
    }

    /** Titles sharing a name in a shelf show their year (The Office, 2001 and 2005). */
    static void markDupes(SearchBrain.Shelf sh) {
        Map<String, Integer> n = new HashMap<>();
        for (Result r : sh.items) n.merge(SearchBrain.norm(r.title), 1, Integer::sum);
        for (Result r : sh.items) r.dupe = n.get(SearchBrain.norm(r.title)) > 1;
    }

    /** "No Results", as Apple says it, centred where the results would be. */
    void nothingFound(boolean failed) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        // Its own width, so it centres (a vertical row's default is the full width, text at the left).
        box.addView(text(failed ? "Can\u2019t Connect" : "No Results", 24, "display", Color.WHITE), new LinearLayout.LayoutParams(-2, -2));
        TextView m = text(failed ? "Check your internet connection and try again."
                : "There were no results for \u201c" + lastQuery + "\u201d. Please try a new search.", 14.5f, "regular", 0x80FFFFFF);
        m.setGravity(Gravity.CENTER);
        m.setMaxWidth(u(600));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = u(8);
        box.addView(m, ml);
        // As wide as the space beside the keyboard, so it's centred in what you can see.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.screen(this).x - KEYS_W, -2);
        lp.topMargin = u(80);
        grid.addView(box, lp);
    }

    TextView text(String s, float size, String weight, int colour) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTypeface(Ui.font(this, weight));
        Ui.textSize(t, size);
        return t;
    }

    static ViewOutlineProvider round(int radius) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        };
    }

    /**
     * One shelf: its heading (Apple's: 31 px bold at half white, white while the shelf has focus),
     * then its cards. Shelves added after the page has arrived rise in, staggered.
     */
    void section(SearchBrain.Shelf sh, int[] delay, int index, boolean asGrid) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setClipChildren(false);
        Sec s = new Sec();
        s.view = v;
        s.recent = RECENTLY.equals(sh.heading);
        s.top = "Top Results".equals(sh.heading);
        s.grid = asGrid;
        s.gap = u(20);
        // Apple's six-column grid: 260 x 390 posters, 40 px apart, 80 px in from the edges.
        s.w = (Ui.screen(this).x - 2 * u(40) - (COLS - 1) * s.gap) / COLS;
        v.setTag(s);
        s.heading = text(sh.heading, 15.5f, "display", Color.WHITE);
        s.heading.setAlpha(0.5f);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.leftMargin = u(40);
        v.addView(s.heading, hl);
        if (!asGrid) {
            s.hs = new HorizontalScrollView(this) {
                // The row scrolls as the TV app's do (scrollRow), not by Android's own nudging,
                // which ignores the padding and cut the far tiles' lift off at the screen's edge.
                @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                    return 0;
                }
            };
            s.hs.setHorizontalScrollBarEnabled(false);
            s.hs.setClipChildren(false);
            s.hs.setClipToPadding(false);
            s.hs.setPadding(u(40), u(17), u(40), 0);  // room for the lift at either end and on top
            s.row = new LinearLayout(this);
            s.row.setClipChildren(false);
            s.row.setTag(ROW);
            s.hs.addView(s.row);
            v.addView(s.hs, new LinearLayout.LayoutParams(-1, -2));
        }
        // Room under the last line for a focused card's title and caption, which drop 4 as they
        // show: the shelf ended at their resting edge and cut them flat there. The gap below
        // gives it back, so the shelves sit where they did.
        int under = u(6);
        v.setPadding(0, 0, 0, under);
        v.setClipToPadding(false);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.bottomMargin = (s.recent ? u(13) : u(28)) - under;
        grid.addView(v, index < 0 ? grid.getChildCount() : index, sl);
        // New results rise in shelf by shelf. While the page itself is still fading in they're
        // simply there, so its arrival stays smooth.
        boolean rises = SystemClock.uptimeMillis() - createdAt > 700;
        s.riseDelay = Math.min(delay[0]++, 4) * 70L;
        if (rises) s.riseAt = SystemClock.uptimeMillis() + s.riseDelay;
        s.firstBuild = sh.items.size();
        if (rises && asGrid) rise(s.heading, s.riseDelay, 0.5f);
        queue(s, sh.items, asGrid ? COLS : COLS + 1);
        if (rises && !asGrid) rise(v, s.riseDelay, 1f);
    }

    /**
     * Rises into place as one picture. Started after the posts that build its cards, so what moves
     * doesn't change under it.
     */
    void rise(View v, long delay, float to) {
        v.setAlpha(0f);
        v.setTranslationY(u(14));
        ui.post(() -> {
            // Focus already in it (a spoken search jumps to the first result): its card is lifting,
            // so no single picture, which would be drawn again every frame; and a grid's heading
            // rises to full white, not back to half.
            View shelf = v.getParent() instanceof View ? (View) v.getParent() : null;
            boolean focused = v.hasFocus() || shelf != null && shelf.hasFocus();
            android.view.ViewPropertyAnimator a = v.animate().alpha(focused ? 1f : to).translationY(0)
                    .setStartDelay(delay).setDuration(460).setInterpolator(MainActivity.EASE);
            if (!v.hasFocus()) a.withLayer();
            a.start();
        });
    }

    /**
     * Adds results to a shelf: the cards on screen at once (atOnce), the rest a row at a time over
     * the next frames, so a page of results never stalls a frame on building eighty cards at once.
     */
    void queue(Sec s, List<Result> rs, int atOnce) {
        s.items.addAll(rs);
        int now = Math.min(rs.size(), atOnce);
        build(s, rs.subList(0, now));
        for (int from = now; from < rs.size(); from += COLS) {
            List<Result> part = new ArrayList<>(rs.subList(from, Math.min(rs.size(), from + COLS)));
            ui.post(() -> {
                if (s.view.getParent() != grid) return;  // replaced by newer results meanwhile
                build(s, part);
            });
        }
    }

    void build(Sec s, List<Result> rs) {
        for (Result r : rs) {
            int i = s.built++;
            View card = card(s, r);
            if (firstCard == null) firstCard = focusable(card);
            LinearLayout row = s.row;
            int at = i;
            if (s.grid) {
                at = i % COLS;
                row = at == 0 ? gridRow(s, i / COLS) : (LinearLayout) s.view.getChildAt(s.view.getChildCount() - 1);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s.recent ? u(280) : s.w, -2);
            if (at > 0) lp.leftMargin = s.gap;
            row.addView(card, lp);
        }
    }

    /** A row of a grid: the first two rise with the shelf, later ones (and ones added after) just appear. */
    LinearLayout gridRow(Sec s, int n) {
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);  // a lifted poster grows up into the padding, not cut at its old edge
        row.setTag(ROW);
        // The cards carry their title's room under them, inside the row: a row rises in as one
        // picture its own size, and a focused card's caption, 4 lower, was cut flat at its edge.
        row.setPadding(u(40), u(17), u(40), u(4));
        s.view.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (s.riseAt > 0 && n < 2 && n * COLS < s.firstBuild) rise(row, s.riseDelay + n * 70L, 1f);
        return row;
    }

    /** Marks a row of cards (a shelf's, or one of a grid's), for rowOf. */
    static final Object ROW = new Object();

    /** The row a card is in; null outside the shelves. */
    static View rowOf(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p.getTag() == ROW) return p;
        }
        return null;
    }

    static View focusable(View card) {
        ArrayList<View> l = new ArrayList<>();
        card.addFocusables(l, View.FOCUS_DOWN);
        return l.isEmpty() ? null : l.get(0);
    }

    View card(Sec s, Result r) {
        if (s.recent) return recentCard(s, r);
        if ("browse".equals(r.type)) return tile(s, r);
        if ("app".equals(r.type)) return appCard(s, r);
        return poster(s, r);
    }

    /** A card took or lost focus: its shelf's heading, the background, and scrolling to keep it in view. */
    void focused(Sec s, View card, Result r, boolean f) {
        headingFocus(s, f);
        ui.removeCallbacks(aheadRun);
        if (!f) return;
        if (r != null) ambientFor(r);
        // A title resting in focus has its page's details asked for, so OK opens it whole.
        if (r != null && r.id != null && r.appLaunch == null && !"app".equals(r.type) && !"browse".equals(r.type)) {
            aheadRun = () -> DetailActivity.ahead(this, r.id, r.tmdbId, r.type);
            ui.postDelayed(aheadRun, DetailActivity.AHEAD_MS);
        }
        scrollRow(s, card);
        keepInView(card);
    }

    void headingFocus(Sec s, boolean f) {
        if (f) {
            s.heading.animate().alpha(1f).setDuration(85).setInterpolator(FOCUS_IN).start();
            return;
        }
        // Moving along the shelf, focus leaves one card for the next: only a shelf really left dims.
        ui.post(() -> {
            if (!s.view.hasFocus()) s.heading.animate().alpha(0.5f).setDuration(230).setInterpolator(FOCUS_OUT).start();
        });
    }

    /** Lifts (or lowers) a card with the Apple TV app's focus timing. */
    static void lift(View v, boolean f, float scale, float z) {
        v.animate().scaleX(f ? scale : 1f).scaleY(f ? scale : 1f).translationZ(f ? z : 0)
                .setDuration(f ? 85 : 230).setInterpolator(f ? FOCUS_IN : FOCUS_OUT).start();
    }

    /**
     * A card's glass sheen and rim fading in or out with its focus. Each fade stops the last: after
     * a quick Right and Left, a fade out still running would otherwise end the sheen at nothing.
     */
    static void sheen(Drawable sheen, boolean f) {
        ObjectAnimator a = ObjectAnimator.ofInt(sheen, "alpha", sheen.getAlpha(), f ? 255 : 0);
        a.setAutoCancel(true);
        a.setDuration(f ? 85 : 230);
        a.start();
    }

    /** Where OK went down, for its release; a long hold that has acted (held). */
    View pressed;
    boolean held;

    /**
     * OK on a card, the Apple TV app's way: it sinks halfway back on the press and comes up on the
     * release, then opens. Holding it for a second runs `hold` instead (when there is one). Left
     * from the first card of a row goes back to the keyboard.
     */
    boolean cardKey(View v, View card, float scale, int k, KeyEvent e, Runnable open, Runnable hold) {
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            // Along the row as usual. At its ends Android would look anywhere that way, and find
            // another row's tiles scrolled out of sight: Left goes back to the keyboard instead,
            // and Right stays put, as on tvOS.
            // Searched for within the row only: the field and suggestions, moved up off the screen
            // as the page scrolls, are still where they were laid out as far as Android's own
            // search knows, level with a lower row, and it chose them (Right went nowhere).
            boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT;
            View row = rowOf(v);
            View next = row instanceof ViewGroup ? android.view.FocusFinder.getInstance()
                    .findNextFocus((ViewGroup) row, v.findFocus() != null ? v.findFocus() : v, left ? View.FOCUS_LEFT : View.FOCUS_RIGHT) : null;
            if (next != null) next.requestFocus(left ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
            else if (left) showKeys();
            return true;
        }
        if (k != KeyEvent.KEYCODE_DPAD_CENTER && k != KeyEvent.KEYCODE_ENTER && k != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                pressed = v;
                held = false;
                sink(card, scale, true, null);
            } else if (hold != null && !held && pressed == v && e.getEventTime() - e.getDownTime() >= 1000) {
                held = true;
                hold.run();
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            boolean go = pressed == v && !held;
            pressed = null;
            if (!held) sink(card, scale, false, go ? open : null);
            held = false;
            return true;
        }
        return false;
    }

    static void sink(View v, float scale, boolean down, Runnable then) {
        float to = down ? 1f + (scale - 1f) / 2f : scale;
        v.animate().scaleX(to).scaleY(to).setDuration(80).setInterpolator(down ? FOCUS_OUT : FOCUS_IN)
                .withEndAction(then).start();
    }

    /** What a title is, as tvOS words it ("Series · Thriller"): what it is and its main genre. */
    static String caption(Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.role != null) {
            // An actor's or director's work: when, and who they played.
            sb.append(r.year == null ? "" : r.year);
            if (!r.role.isEmpty() && !"Director".equals(r.role)) sb.append(sb.length() > 0 ? " \u00b7 " : "").append("as ").append(r.role);
            return sb.toString();
        }
        sb.append("tv".equals(r.type) ? "Series" : "Film");
        for (String g : r.genres) {
            String n = Suggestions.JW_GENRE_NAMES.get(g);
            if (n == null) continue;
            sb.append(" \u00b7 ").append(n);
            break;
        }
        if (r.dupe && r.year != null && !r.year.isEmpty()) sb.append(" \u00b7 ").append(r.year);
        return sb.toString();
    }

    /**
     * A poster, as the Apple TV app's search shows it: just the art, its corners rounded. Focused,
     * it lifts by 10% (85 ms in, 230 ms out) with a soft shadow and a glass sheen and rim, and its
     * title fades in under it. Nothing is laid over the art.
     */
    View poster(Sec s, Result r) {
        int w = s.w, h = w * 3 / 2, radius = u(10);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setFocusable(true);
        art.setBackgroundColor(0xFF2A2A30);
        art.setOutlineProvider(round(radius));
        art.setClipToOutline(true);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0AFFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(radius);
        sheen.setStroke(Math.max(1, u(0.6f)), 0x55FFFFFF);
        sheen.setAlpha(0);
        art.setForeground(sheen);
        box.addView(art, new LinearLayout.LayoutParams(w, h));
        TextView name = text(r.title, 12f, "medium", Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setPadding(0, 0, 0, u(3));  // room for descenders: a y's tail was cut off
        name.setAlpha(0f);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(w, -2);
        nl.topMargin = u(13);
        box.addView(name, nl);
        // Top Results says what each is, under its title; the other shelves give the title alone.
        TextView kind = null;
        if (s.top) {
            kind = text(caption(r), 10.5f, "regular", 0x99FFFFFF);
            kind.setSingleLine(true);
            kind.setEllipsize(TextUtils.TruncateAt.END);
            kind.setGravity(Gravity.CENTER_HORIZONTAL);
            kind.setPadding(0, 0, 0, u(3));
            kind.setAlpha(0f);
            box.addView(kind, new LinearLayout.LayoutParams(w, -2));
        }
        TextView under = kind;
        Bitmap ready = r.poster == null ? null : POSTERS.get(r.poster);
        if (ready != null) art.setImageBitmap(ready);
        else await(r.poster, art, s);
        art.setOnFocusChangeListener((v, f) -> {
            lift(art, f, 1.1f, u(14));
            sheen(sheen, f);
            name.animate().alpha(f ? 1f : 0f).translationY(f ? u(4) : 0).setDuration(f ? 85 : 230)
                    .setInterpolator(f ? FOCUS_IN : FOCUS_OUT).start();
            if (under != null) under.animate().alpha(f ? 1f : 0f).translationY(f ? u(4) : 0).setDuration(f ? 85 : 230)
                    .setInterpolator(f ? FOCUS_IN : FOCUS_OUT).start();
            focused(s, box, r, f);
        });
        art.setOnKeyListener((v, k, e) -> cardKey(v, art, 1.1f, k, e, () -> open(r), null));
        return box;
    }

    /** An app's banner, its name under it as it takes focus. */
    View appCard(Sec s, Result r) {
        int w = s.w, h = w * 3 / 5, radius = u(10);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setFocusable(true);
        art.setBackgroundColor(0xFF2A2A30);
        art.setImageDrawable(r.appBanner);
        art.setOutlineProvider(round(radius));
        art.setClipToOutline(true);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0AFFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(radius);
        sheen.setStroke(Math.max(1, u(0.6f)), 0x55FFFFFF);
        sheen.setAlpha(0);
        art.setForeground(sheen);
        box.addView(art, new LinearLayout.LayoutParams(w, h));
        TextView name = text(r.title, 12.5f, "medium", Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setAlpha(0f);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(w, -2);
        nl.topMargin = u(12);
        box.addView(name, nl);
        art.setOnFocusChangeListener((v, f) -> {
            lift(art, f, 1.1f, u(14));
            sheen(sheen, f);
            name.animate().alpha(f ? 1f : 0f).translationY(f ? u(3) : 0).setDuration(f ? 85 : 230)
                    .setInterpolator(f ? FOCUS_IN : FOCUS_OUT).start();
            focused(s, box, null, f);
        });
        art.setOnKeyListener((v, k, e) -> cardKey(v, art, 1.1f, k, e, () -> open(r), null));
        return box;
    }

    // ---------------------------------------------------------------- recently searched

    SharedPreferences prefs() {
        return getSharedPreferences("search", MODE_PRIVATE);
    }

    /**
     * Recently Searched, as Apple keeps it: the titles opened from Search (not what was typed),
     * newest first, ten at most, each for thirty days, on this TV only.
     */
    List<Result> recentTitles() {
        List<Result> out = new ArrayList<>();
        long cut = System.currentTimeMillis() - RECENT_MS;
        try {
            JSONArray a = new JSONArray(prefs().getString("titles", "[]"));
            for (int i = 0; i < a.length() && out.size() < RECENT; i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optLong("at") < cut) continue;
                Result r = new Result();
                r.id = o.optString("id", null);
                r.title = o.optString("title");
                r.type = o.optString("type", "movie");
                r.year = o.optString("year");
                r.poster = o.optString("poster", null);
                r.backdrop = o.optString("backdrop", null);
                r.tmdbId = o.optInt("tmdbId");
                String g = o.optString("genre");
                if (!g.isEmpty()) r.genres.add(g);
                out.add(r);
            }
        } catch (Exception ignored) {
            // nothing kept yet, or unreadable: start again
        }
        return out;
    }

    Set<String> recentIds() {
        Set<String> ids = new HashSet<>();
        for (Result r : recentTitles()) if (r.id != null) ids.add(r.id);
        return ids;
    }

    static boolean sameTitle(Result r, JSONObject o) {
        if (r.id != null && r.id.equals(o.optString("id", null))) return true;
        return SearchBrain.norm(r.title).equals(SearchBrain.norm(o.optString("title"))) && TextUtils.equals(r.year, o.optString("year"));
    }

    /** A title opened from Search goes to the front of Recently Searched. */
    void remember(Result r) {
        if (r.title == null) return;
        try {
            JSONArray old = new JSONArray(prefs().getString("titles", "[]")), a = new JSONArray();
            a.put(new JSONObject().put("id", r.id).put("title", r.title).put("type", r.type).put("year", r.year)
                    .put("poster", r.poster).put("backdrop", r.backdrop).put("tmdbId", r.tmdbId)
                    .put("genre", r.genres.isEmpty() ? null : r.genres.get(0)).put("at", System.currentTimeMillis()));
            for (int i = 0; i < old.length() && a.length() < RECENT; i++) {
                JSONObject o = old.getJSONObject(i);
                if (!sameTitle(r, o)) a.put(o);
            }
            prefs().edit().putString("titles", a.toString()).apply();
        } catch (Exception ignored) {
            // not kept this time
        }
        brain.recent = recentIds();
    }

    /** Holding OK on a Recently Searched title takes it off the list; its card fades away. */
    void forget(Sec s, View card, Result r) {
        try {
            JSONArray old = new JSONArray(prefs().getString("titles", "[]")), a = new JSONArray();
            for (int i = 0; i < old.length(); i++) if (!sameTitle(r, old.getJSONObject(i))) a.put(old.getJSONObject(i));
            prefs().edit().putString("titles", a.toString()).apply();
        } catch (Exception ignored) {
            // it stays
        }
        brain.recent = recentIds();
        s.items.remove(r);
        for (SearchBrain.Shelf sh : showing) if (RECENTLY.equals(sh.heading)) sh.items.remove(r);
        // Focus moves on at once: to the next title, else the one before, else the shelf below.
        int at = s.row.indexOfChild(card);
        View next = s.row.getChildAt(at + 1) != null ? s.row.getChildAt(at + 1) : s.row.getChildAt(at - 1);
        View below = grid.getChildAt(grid.indexOfChild(s.view) + 1);
        if (next == null && below != null) next = focusable(below);
        if (next != null) next.requestFocus();
        else showKeys();
        card.setFocusable(false);
        card.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            s.row.removeView(card);
            if (s.row.getChildCount() > 0) {
                ((LinearLayout.LayoutParams) s.row.getChildAt(0).getLayoutParams()).leftMargin = 0;
                s.row.requestLayout();
                return;
            }
            // That was the last: the shelf goes.
            showing.removeIf(sh -> RECENTLY.equals(sh.heading));
            grid.removeView(s.view);
        }).start();
    }

    /**
     * A Recently Searched title as Apple's compact search card: the poster small at the left, the
     * title and "Series · Crime" beside it, on a faint platter that brightens with focus.
     */
    View recentCard(Sec s, Result r) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(u(10), u(10), u(10), u(10));
        card.setFocusable(true);
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(0x1AFFFFFF);
        plate.setCornerRadius(u(15));
        card.setBackground(plate);
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xFF2A2A30);
        thumb.setOutlineProvider(round(u(5)));
        thumb.setClipToOutline(true);
        Bitmap ready = r.poster == null ? null : POSTERS.get(r.poster);
        if (ready != null) thumb.setImageBitmap(ready);
        else await(r.poster, thumb, s);
        card.addView(thumb, new LinearLayout.LayoutParams(u(40), u(60)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(r.title, 14.5f, "medium", Color.WHITE);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(t);
        TextView m = text(caption(r), 11.5f, "regular", 0x80FFFFFF);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(m);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1f);
        wl.leftMargin = u(10);
        card.addView(words, wl);
        ArgbEvaluator ev = new ArgbEvaluator();
        ValueAnimator[] glow = {null};
        int[] now = {0x1AFFFFFF};
        card.setOnFocusChangeListener((v, f) -> {
            lift(card, f, 1.071f, u(6));
            if (glow[0] != null) glow[0].cancel();
            glow[0] = ValueAnimator.ofFloat(0f, 1f);
            int from = now[0], to = f ? 0x33FFFFFF : 0x1AFFFFFF;
            glow[0].setDuration(f ? 85 : 230);
            glow[0].addUpdateListener(a -> {
                now[0] = (int) ev.evaluate((float) a.getAnimatedValue(), from, to);
                plate.setColor(now[0]);
            });
            glow[0].start();
            focused(s, card, r, f);
        });
        card.setOnKeyListener((v, k, e) -> cardKey(v, card, 1.071f, k, e, () -> open(r), () -> forget(s, card, r)));
        return card;
    }

    // ---------------------------------------------------------------- browse

    /** Browse's genres: what they're called and JustWatch's code for each. */
    static final String[][] GENRE_TILES = {{"Comedies", "cmy"}, {"Dramas", "drm"}, {"Thrillers", "trl"},
            {"Crime", "crm"}, {"Documentaries", "doc"}, {"Sci-Fi", "scf"}, {"Horror", "hrr"}, {"Romance", "rma"}};
    /** Each genre tile's picture: a popular title's poster, blurred to colour and light. */
    static final Map<String, Bitmap> TILE_ART = new ConcurrentHashMap<>();
    /** Your services, and their banners (by package), from the last look. */
    static volatile List<Suggestions.Provider> services = Collections.emptyList();
    static final Map<String, Bitmap> BANNERS = new ConcurrentHashMap<>();
    /** Each banner blurred to a glow of its own colours, for its tile's background. */
    static final Map<String, Bitmap> GLOWS = new ConcurrentHashMap<>();
    /** The genre tiles on screen, waiting for their pictures. */
    final Map<String, ImageView> tiles = new HashMap<>();

    /** The page before a search: Recently Searched (when there are any), Browse, then Popular. */
    List<SearchBrain.Shelf> landing(List<SearchBrain.Shelf> popular) {
        List<SearchBrain.Shelf> out = new ArrayList<>();
        out.add(new SearchBrain.Shelf(RECENTLY, recentTitles()));
        List<Result> browse = new ArrayList<>();
        for (String[] g : GENRE_TILES) {
            Result r = new Result();
            r.type = "browse";
            r.title = g[0];
            r.genre = g[1];
            browse.add(r);
        }
        for (Suggestions.Provider p : services) {
            Result r = new Result();
            r.type = "browse";
            r.title = p.name;
            r.service = p;
            browse.add(r);
        }
        out.add(new SearchBrain.Shelf(BROWSE, browse));
        // Nothing else, as Apple's: what's popular only chooses the Browse tiles' pictures.
        return out;
    }

    /** Looks up your services and their banners; Browse gains their tiles. */
    void findServices() {
        SharedPreferences kept = prefs();
        if (kept.contains("recent")) kept.edit().remove("recent").apply();  // the old list of typed searches
        List<Suggestions.Provider> mine = brain.mine();
        PackageManager pm = getPackageManager();
        int bw = u(110), bh = u(62);
        for (Suggestions.Provider p : mine) {
            if (BANNERS.containsKey(p.pkg)) continue;
            Drawable d = null;
            try {
                Intent launch = Suggestions.launchIntent(this, p.pkg);
                if (launch != null) d = pm.getActivityBanner(launch);
                if (d == null) d = pm.getApplicationBanner(p.pkg);
            } catch (Exception ignored) {
                // no banner: the name alone
            }
            if (d == null) continue;
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, bw, bh);
            d.draw(new Canvas(b));
            Bitmap glow = Ui.blur(b, 12, 18, 3, 3);
            if (glow != null) GLOWS.put(p.pkg, Suggestions.onGpu(glow));
            BANNERS.put(p.pkg, Suggestions.onGpu(b));
        }
        if (mine.equals(services)) return;
        services = mine;
        ui.post(() -> {
            if (!isDestroyed() && lastQuery.isEmpty()) show(landed);
        });
    }

    /** The genre tiles' pictures, from Popular's posters (each tile a different one), blurred small. */
    void browseArt(int gen, List<SearchBrain.Shelf> pop) {
        if (pop == null) return;
        Set<String> used = new HashSet<>();
        for (String[] g : GENRE_TILES) {
            if (gen != generation) return;
            if (TILE_ART.containsKey(g[1])) continue;
            Result pick = null;
            for (SearchBrain.Shelf sh : pop) {
                for (Result r : sh.items) {
                    if (r.poster != null && r.genres.contains(g[1]) && used.add(r.poster)) {
                        pick = r;
                        break;
                    }
                }
                if (pick != null) break;
            }
            // Nothing popular now is of that genre (documentaries, romance rarely trend): the
            // genre's own best-known title on your services instead, so no tile is left blank.
            if (pick == null) {
                try {
                    for (Result r : brain.genreTop(g[1])) {
                        if (r.poster != null && used.add(r.poster)) {
                            pick = r;
                            break;
                        }
                    }
                } catch (Exception e) {
                    // offline: the tile's plain glass
                }
                if (gen != generation) return;
            }
            if (pick == null) continue;
            // A still from the title, sharp, cropped to the tile, as Apple's Browse tiles are: scenes,
            // not posters, so the genre's name never sits on top of a title's own lettering. Wide
            // enough that its height fills the 2:3 tile; the poster only when there's no still.
            Bitmap full = pick.backdrop != null ? Suggestions.image(this, pick.backdrop, "", u(360)) : null;
            if (full == null) full = Suggestions.image(this, pick.poster, "", u(150));
            if (full == null) continue;
            Bitmap soft = Suggestions.onGpu(full);
            TILE_ART.put(g[1], soft);
            String code = g[1];
            ui.post(() -> {
                ImageView v = tiles.get(code);
                if (v == null || isDestroyed()) return;
                v.setImageBitmap(soft);
                v.setImageAlpha(0);
                ObjectAnimator a = ObjectAnimator.ofInt(v, "imageAlpha", 0, 255);
                a.setDuration(240);
                a.setInterpolator(new LinearInterpolator());
                a.start();
            });
        }
    }

    /**
     * A Browse tile, as tvOS 26's: a 2:3 tile with its name inside the art, and no caption. A
     * genre's art is a still from a popular title of that genre; a service's is its banner on a
     * dark platter. OK browses it.
     */
    View tile(Sec s, Result r) {
        int w = s.w, h = w * 3 / 2, radius = u(10);
        FrameLayout box = new FrameLayout(this);
        box.setFocusable(true);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_XY);
        if (r.service != null) {
            art.setBackgroundColor(0xFF1C1C1E);
            // The service's own colours, blurred and dimmed behind its logo (Prime Video's blue,
            // ITVX's green), as tvOS's app tiles glow, rather than a grey card.
            Bitmap glow = GLOWS.get(r.service.pkg);
            if (glow != null) {
                art.setImageBitmap(glow);
                art.setColorFilter(0x8C000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
            }
            box.addView(art, new FrameLayout.LayoutParams(w, h));
            Bitmap banner = BANNERS.get(r.service.pkg);
            if (banner != null) {
                ImageView logo = new ImageView(this);
                logo.setImageBitmap(banner);
                logo.setOutlineProvider(round(u(5)));
                logo.setClipToOutline(true);
                box.addView(logo, new FrameLayout.LayoutParams(u(110), u(62), Gravity.CENTER));
            } else {
                // No banner to show (its app isn't on this TV): its name, never a blank tile.
                TextView name = text(r.title, 15.5f, "display", Color.WHITE);
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(2);
                FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(w - u(18), -2, Gravity.CENTER);
                box.addView(name, nl);
            }
        } else {
            art.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF2C2C2E, 0xFF1C1C1E}));
            Bitmap b = TILE_ART.get(r.genre);
            if (b != null) art.setImageBitmap(b);
            else tiles.put(r.genre, art);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(art, new FrameLayout.LayoutParams(w, h));
        }
        // A genre's name sits in its art; a service's logo is its name, so it has no caption.
        if (r.service == null) {
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xB3000000}));
            box.addView(shade, new FrameLayout.LayoutParams(-1, h / 2, Gravity.BOTTOM));
            TextView label = text(r.title, 15.5f, "display", Color.WHITE);
            label.setMaxLines(2);
            FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            ll.leftMargin = ll.bottomMargin = ll.rightMargin = u(9);
            box.addView(label, ll);
        }
        box.setContentDescription(r.title);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0AFFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(radius);
        sheen.setStroke(Math.max(1, u(0.6f)), 0x55FFFFFF);
        sheen.setAlpha(0);
        box.setForeground(sheen);
        box.setOutlineProvider(round(radius));
        box.setClipToOutline(true);
        box.setOnFocusChangeListener((v, f) -> {
            lift(box, f, 1.1f, u(14));
            sheen(sheen, f);
            if (f) ambientNone();
            focused(s, box, null, f);
        });
        box.setOnKeyListener((v, k, e) -> cardKey(v, box, 1.1f, k, e, () -> {
            jump = true;
            fromTile = true;
            setQuery(r.title);
        }, null));
        return box;
    }

    // ---------------------------------------------------------------- scrolling

    /**
     * A row scrolls as the TV app's do: focus walks across the tiles in view, and the row moves
     * only when the next would come within 80 px of the edge, then just enough, on Apple's scroll
     * curve. A new press retargets it from wherever it is.
     */
    void scrollRow(Sec s, View card) {
        HorizontalScrollView hs = s.hs;
        if (hs == null) return;  // a grid row doesn't scroll
        // Focused as soon as it was made (a jump to the first result, a title's new place in a
        // shelf made again): with no size yet the sums below would scroll the row off its start.
        if (!card.isLaidOut()) return;
        int edge = u(40);
        int left = card.getLeft() + hs.getPaddingLeft(), right = left + card.getWidth();
        int max = Math.max(0, s.row.getWidth() + hs.getPaddingLeft() + hs.getPaddingRight() - hs.getWidth());
        // Measured from where the row is going, when it's already on its way.
        boolean moving = s.scroller != null && s.scroller.isRunning();
        int x = moving ? s.scrollTo : hs.getScrollX(), w = hs.getWidth(), target = x;
        if (right > x + w - edge) target = right - (w - edge);
        else if (left < x + edge) target = left - edge;
        target = Math.max(0, Math.min(max, target));
        if (target == x) return;
        if (s.scroller != null) s.scroller.cancel();
        s.scrollTo = target;
        s.scroller = ObjectAnimator.ofInt(hs, "scrollX", target);
        s.scroller.setDuration(700);
        s.scroller.setInterpolator(SCROLL);
        s.scroller.start();
    }

    ObjectAnimator vScroll;
    int vTarget;
    Runnable vStart = () -> { };

    /**
     * The page follows focus as tvOS's does: on the first shelf it's at its top, the field above
     * it; on any other row, that row sits near the top of the screen and everything above it (the
     * field and suggestions too) has gone up off the screen. Never a row half under anything.
     */
    void keepInView(View v) {
        View section = v;
        while (section != null && section.getParent() != grid) {
            section = section.getParent() instanceof View ? (View) section.getParent() : null;
        }
        if (section == null) return;
        int top = section.getTop();
        boolean first = grid.indexOfChild(section) == 0;
        Sec s = (Sec) section.getTag();
        if (s != null && s.grid) {
            View row = v;
            while (row.getParent() != section) row = (View) row.getParent();
            int at = ((ViewGroup) section).indexOfChild(row);
            // A grid's first row keeps its heading with it; later rows go row by row.
            if (at > 1) {
                top = section.getTop() + row.getTop();
                first = false;
            }
        }
        glide(first ? 0 : Math.max(0, scroll.getPaddingTop() + top - u(30)));
    }

    /** The field and suggestions ride up with the page (they're not in it, as they don't slide with the keyboard). */
    void pageScrolled(int y) {
        fieldBar.setTranslationY(-y);
        hintRow.setTranslationY(-y);
        // Scrolled up out of sight, they can't be moved to: Android's focus search would still find
        // them where they were laid out, beside the results now there.
        int block = y > 0 ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS;
        if (hintRow.getDescendantFocusability() != block) {
            hintRow.setDescendantFocusability(block);
            if (fieldBar instanceof ViewGroup) ((ViewGroup) fieldBar).setDescendantFocusability(block);
        }
    }

    /** The shelves start under the field, and under the suggestions while there are some. */
    void placeResults() {
        int top = resultsTop + (hintRow.getVisibility() == View.VISIBLE ? hintRow.getHeight() : 0) + u(14);
        if (scroll.getPaddingTop() != top) scroll.setPadding(0, top, 0, scroll.getPaddingBottom());
    }

    /**
     * Scrolls the page to y on Apple's curve, starting a few frames after the focus change, so the
     * lift leads and the page follows.
     */
    void glide(int y) {
        boolean moving = vScroll != null && vScroll.isRunning();
        if (y == (moving ? vTarget : scroll.getScrollY())) return;
        vTarget = y;
        ui.removeCallbacks(vStart);
        vStart = () -> {
            if (vScroll != null) vScroll.cancel();
            vScroll = ObjectAnimator.ofInt(scroll, "scrollY", y);
            vScroll.setDuration(700);
            vScroll.setInterpolator(SCROLL);
            vScroll.start();
        };
        ui.postDelayed(vStart, 50);
    }

    // ---------------------------------------------------------------- background

    ImageView ambient;
    volatile String ambientFor;

    /**
     * One background layer. Its dark shade rides on it, so with no picture there's nothing extra
     * to draw, and it's kept as one ready image so fading it costs nothing.
     */
    ImageView ambientLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setForeground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x8C000000, 0xE6000000}));
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        return v;
    }

    /**
     * Backgrounds opened and ready to show, by picture address: under 2 MB each (1280 px, 16-bit,
     * on the GPU), a dozen at once, so going back along the results finds them there. The rest
     * wait on disk (prepareAmbient), a quick read away.
     */
    static final android.util.LruCache<String, Bitmap> AMBIENT = new android.util.LruCache<String, Bitmap>(24 << 20) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return Math.max(1, b.getAllocationByteCount());
        }
    };
    /** Getting the results' backgrounds ready, two at a time, behind everything on screen. */
    final ExecutorService ambientIo = Executors.newFixedThreadPool(2, job -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        job.run();
    }, "ambient"));
    /** The focused title's picture, never queued behind the ones being got ready. */
    final ExecutorService ambientNow = Executors.newSingleThreadExecutor();
    /** Backgrounds being got ready now, so none is fetched twice at once. */
    final Set<String> ambientGetting = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * The picture behind a result: the studio's key art (TMDB) when there is one, else JustWatch's
     * 1920 px backdrop, opened at two thirds of the screen's width (1280 px), so the scaling
     * softens it a little and it's never blocky. TMDB's art comes at its own 1280 px size, not the
     * original (often 4K): the same picture on screen, a fraction of the download and decoding.
     */
    Bitmap ambientArt(Result r, String url) {
        Bitmap b = AMBIENT.get(url);
        if (b != null) return b;
        String art = r == null || r.tmdbId <= 0 ? null : Suggestions.tmdbBackdrop(this, r.type, r.tmdbId);
        int full = Ui.screen(this).x * 2 / 3;
        Bitmap src = art == null ? null : Suggestions.screenImage(this, Suggestions.tmdbSized(art, "w1280"), full);
        if (src == null) src = Suggestions.screenImage(this, url, full);
        if (src == null) return null;
        if (!isDestroyed()) AMBIENT.put(url, src);  // not kept in Home's process once Search has gone
        return src;
    }

    /**
     * A result's background fetched to the disk cache, not opened: opening (decoding) big pictures
     * in the background takes the TV's processor from drawing the page, so that's left until
     * focus reaches the result, when it's a quick read from disk.
     */
    void ambientDownload(Result r, String url) {
        try {
            String art = r == null || r.tmdbId <= 0 ? null : Suggestions.tmdbBackdrop(this, r.type, r.tmdbId);
            if (Suggestions.download(this, art != null ? Suggestions.tmdbSized(art, "w1280") : url, "") == null && art != null) {
                Suggestions.download(this, url, "");
            }
        } catch (Exception e) {
            // fetched when focused instead
        }
    }

    /** The results whose backgrounds are to be got ready, in the order they're shown. */
    List<Result> ambientTodo;

    /**
     * Fetches the results' backgrounds as soon as their rows have risen (24 of them, in the order
     * they're shown), two at a time at background priority, so each is on disk before focus
     * reaches it, and opening it then takes a moment.
     */
    void prepareAmbient(List<Result> rs) {
        if (isDestroyed()) return;
        List<Result> todo = new ArrayList<>();
        for (Result r : rs) {
            String u = r.backdrop != null ? r.backdrop : r.poster;
            if (u != null && !"app".equals(r.type) && AMBIENT.get(u) == null && todo.size() < 24) todo.add(r);
        }
        ambientTodo = todo.isEmpty() ? null : todo;
        ui.removeCallbacks(ambientBatch);
        if (ambientTodo != null) ui.postDelayed(ambientBatch, 250);
    }

    final Runnable ambientBatch = () -> {
        List<Result> todo = ambientTodo;
        ambientTodo = null;
        if (todo == null || isDestroyed()) return;
        int gen = generation;
        for (Result r : todo) {
            String u = r.backdrop != null ? r.backdrop : r.poster;
            try {
                ambientIo.execute(() -> {
                    if (gen != generation || AMBIENT.get(u) != null) return;
                    ambientDownload(r, u);
                });
            } catch (Exception e) {
                return;  // the page has closed
            }
        }
    };

    void ambientFor(Result r) {
        if (isDestroyed()) return;
        String url = r.backdrop != null ? r.backdrop : r.poster;
        if (url == null || url.equals(ambientFor)) return;
        ambientFor = url;
        ui.removeCallbacks(ambientRun);
        Bitmap ready = AMBIENT.get(url);
        if (ready != null) {
            showAmbient(ready, 260);  // ready: straight in
            return;
        }
        ambientRun = () -> ambientNow.execute(() -> {
            // Being got ready already: wait for that one rather than fetch it again.
            while (!ambientGetting.add(url)) {
                if (!url.equals(ambientFor)) return;  // focus has moved on
                SystemClock.sleep(40);
            }
            Bitmap bm;
            try {
                if (!url.equals(ambientFor)) return;
                bm = ambientArt(r, url);
            } finally {
                ambientGetting.remove(url);
            }
            ui.post(() -> {
                if (bm == null || !url.equals(ambientFor) || isDestroyed()) return;
                showAmbient(bm, 320);
            });
        });
        // A moment's wait, so moving quickly along a row doesn't fetch each picture on the way.
        ui.postDelayed(ambientRun, 90);
    }

    /**
     * The new picture in its place at once, brightening from half: a cross-fade proper (the old
     * picture on a second full-screen layer under it) cost this TV's GPU a second full-screen
     * fill every frame of every fade, and made one frame in seven late while moving along results.
     */
    void showAmbient(Bitmap b, long ms) {
        ambient.animate().cancel();
        ambient.setImageBitmap(b);
        ambient.setAlpha(Math.min(ambient.getAlpha(), 0.5f));
        ambient.animate().alpha(1f).setDuration(ms).setInterpolator(MainActivity.EASE).start();
    }

    /** Nothing behind the page: Browse's tiles are pictures themselves, so a title's stays not. */
    void ambientNone() {
        if (ambientFor == null) return;
        ambientFor = null;
        ui.removeCallbacks(ambientRun);
        ambient.animate().cancel();
        ambient.animate().alpha(0f).setDuration(300).setInterpolator(MainActivity.EASE).start();
    }

    Runnable ambientRun = () -> { }, ambientTop = () -> { }, aheadRun = () -> { };

    // ---------------------------------------------------------------- opening

    /** An app opens; a show or film opens its page, with the synopsis and every way to watch it. */
    void open(Result r) {
        if (r.appLaunch != null) {
            Suggestions.start(this, r.appLaunch);
            return;
        }
        if (r.id == null && r.tmdbId > 0) {
            // (Remembered once found, as the title its page is for, not twice.)
            // An actor's film from TMDB: find it on JustWatch for its page (where to watch it).
            io.execute(() -> {
                Result found = brain.resolve(r);
                ui.post(() -> {
                    if (isDestroyed()) return;  // left meanwhile: no page popping up over somewhere else
                    if (found != null) {
                        found.backdrop = r.backdrop != null ? r.backdrop : found.backdrop;
                        open(found);
                    } else {
                        setQuery(r.title);
                    }
                });
            });
            return;
        }
        remember(r);
        opened = true;
        startActivity(new Intent(this, DetailActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra("id", r.id).putExtra("title", r.title)
                .putExtra("backdrop", r.backdrop)
                .putExtra("tmdb", r.tmdbId).putExtra("type", r.type));
        overridePendingTransition(R.anim.page_in, R.anim.hold);
    }
}

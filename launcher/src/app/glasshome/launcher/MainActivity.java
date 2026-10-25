package app.glasshome.launcher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** tvOS-style home screen: blurred backdrop, top shelf title, rounded app tile grid. */
public class MainActivity extends Activity {

    static final int COLS = 6;
    static final String SETTINGS_KEY = "special:settings";
    static final String INPUTS_KEY = "special:inputs";
    static final String FOLDER_KEY = "special:folder";
    static final String PREF_ORDER = "order";
    static final String PREF_HIDDEN = "hidden";
    static final String TV_SETTINGS = "com.android.tv.settings";

    /** Packages placed first (in this order) until the user rearranges the grid. */
    static final List<String> DEFAULT_ORDER = Arrays.asList(
            "bbc.iplayer.android",
            "air.ITVMobilePlayer",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.google.android.youtube.tv",
            "com.channel4.ondemand",
            "com.channel5.my5",
            "com.apple.atve.androidtv.appletv",
            "com.bskyb.nowtv.beta",
            "com.wbd.stream",
            "org.jellyfin.androidtv",
            "com.plexapp.android",
            "com.spotify.tv.android",
            "org.videolan.vlc");

    static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** The carousel's motion and the apps' glass: a soft start and a long settle, like the Apple TV Top Shelf. */
    static final PathInterpolator HERO_EASE = new PathInterpolator(0.25f, 0.1f, 0.1f, 1f);
    /**
     * The carousel's fade from one picture to the next: a soft start and a gentle landing, half
     * done in about a fifth of a second (an even fade over a second read as slow).
     */
    static final PathInterpolator DISSOLVE = new PathInterpolator(0.3f, 0f, 0.2f, 1f);
    static final long DISSOLVE_MS = 650;
    /**
     * How far the old and new pictures pan together through a dissolve, as a fraction of the
     * screen. They're drawn this much bigger on each side, so the pan never shows an edge.
     */
    static final float HERO_PAN = 0.02f;
    /** tvOS focused icon is ~1.2x its resting size (303x183 vs 248x148 px in Apple's reference). */
    static final float FOCUS_SCALE = 1.2f;
    /** Focus lift: a damped spring (about 5% overshoot, settled well before the end), like tvOS. */
    static final Spring LIFT = new Spring(0.68f, 10f);
    /** The last focused tile, so a new focus can nudge in the direction the remote moved. */
    int lastTile = -1;

    static class AppEntry {
        String key, pkg, label;
        Drawable banner, icon;
        /** The banner pre-rendered at the focused tile's exact pixel size, with mipmaps. */
        Bitmap tileArt;
        /** The app's own icon and banner resources, for fetching them at a higher density. */
        int iconRes, bannerRes;
        Intent launch;
        int color;
        boolean system;
    }

    /** Scroll position is driven by focus changes, not by ScrollView's own key handling. */
    static class ShelfScrollView extends ScrollView {
        ShelfScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
        @Override public boolean executeKeyEvent(KeyEvent e) { return false; }
    }

    SharedPreferences prefs;
    final Map<String, AppEntry> all = new LinkedHashMap<>();
    /** Visible home apps, in user order. */
    final List<AppEntry> apps = new ArrayList<>();
    /** Apps the user hid; reachable through the "More Apps" folder tile. */
    final List<AppEntry> hiddenApps = new ArrayList<>();
    /** What the grid currently shows: home apps (+ folder tile) or the folder's contents. */
    final List<AppEntry> shown = new ArrayList<>();
    final List<View> tiles = new ArrayList<>();
    final Handler handler = new Handler(Looper.getMainLooper());

    FrameLayout root;
    ImageView wallView;
    /**
     * Top shelf carousel: crossfading backdrops + info panel. heroFront is the newest picture,
     * heroBack the one it fades in over; heroUnder is a third, so a press mid-fade carries on from
     * the blend on screen instead of jumping to one picture.
     */
    ImageView heroUnder, heroBack, heroFront;

    ImageView[] heroLayers() {
        return new ImageView[]{heroUnder, heroBack, heroFront};
    }
    FrameLayout shelfView;
    LinearLayout heroInfo, heroDots;
    /** The dots themselves, inside heroDots (which moves and fades the whole indicator). */
    LinearLayout dotRow;
    ImageView heroLogo;
    TextView heroTitle, heroMeta, heroOverview;
    /** tvOS-style capsule button: glass when idle, solid white with dark text when focused. */
    LinearLayout heroButton;
    TextView heroBtnText;
    ImageView heroBtnIcon;
    ImageView heroProvider;
    GradientDrawable heroBtnBg;
    List<Suggestions.Item> heroItems = new ArrayList<>();
    /** The full carousel (every service's recommendations); heroItems may be one app's subset. */
    List<Suggestions.Item> heroAll = new ArrayList<>();
    /** The dock app whose own titles the carousel shows, or null for the full mix. */
    String shelfPkg;
    int heroIndex;
    boolean heroShown;          // carousel currently forms the background
    int heroVersion = -1;       // Suggestions.version() the items were loaded for
    String wallStyle;
    /** What Liquid Glass samples: a blurred copy of the wallpaper or of the carousel artwork. */
    Bitmap wallBlur, heroBlur, glassBg;
    /** The carousel's picture, deeply blurred and dimmed: behind the apps while they're open. */
    Bitmap heroDrawer;
    ImageView drawerBack;
    final LiquidGlass.Backdrop glassSource = () -> glassBg;
    View dockRow;
    boolean returningFromSettings;
    final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    final java.util.concurrent.ExecutorService preload = java.util.concurrent.Executors.newSingleThreadExecutor();
    volatile int preloadGen;
    final Runnable heroAdvance = () -> heroStep(1, false);
    ShelfScrollView scroll;
    LinearLayout content;
    LinearLayout clockBox;
    /** Home, while it exists: Control Centre hides Home's clock and gear behind it. */
    static java.lang.ref.WeakReference<MainActivity> live = new java.lang.ref.WeakReference<>(null);

    /** Fades the status row (clock pill and gear) out while Control Centre is open, and back. */
    void statusRow(boolean show) {
        if (clockBox == null) return;
        clockBox.animate().cancel();
        // Hiding is instant: the panel covers that corner, and animating Home behind it costs the
        // slide-in its frames on this GPU. Coming back fades in once the panel has gone.
        if (!show) clockBox.setAlpha(0f);
        else clockBox.animate().alpha(1f).setStartDelay(340).setDuration(320).setInterpolator(EASE).start();
    }
    LinearLayout titleBox;
    View chevron;
    TextView shelfTitle, shelfHint;
    float unit = 2f;
    int screenW, screenH, tileW, tileH, gap, side, shelfH, labelH, radius, dockPadV, dockInset, dockOuter;
    int focusedIndex = 0;
    int moveIndex = -1;
    boolean folderMode;
    boolean resumed;
    ObjectAnimator wiggle;
    BroadcastReceiver pkgReceiver;

    final Runnable shelfUpdate = () -> {
        if (focusedIndex >= 0 && focusedIndex < shown.size()) {
            shelfFor(shown.get(focusedIndex), hasDock() && focusedIndex < COLS);
        }
    };
    final Runnable reloadRun = () -> reload(currentKey());

    @Override
    protected void onCreate(Bundle b) {
        live = new java.lang.ref.WeakReference<>(this);
        super.onCreate(b);
        // The wallpaper/artwork always covers the screen: skip the window's own full-screen fill
        // (this Mali-450 GPU is fill-rate bound, so every hidden full-screen layer costs frames).
        getWindow().setBackgroundDrawable(null);
        prefs = Profiles.homePrefs(this);

        android.graphics.Point size = Ui.screen(this);
        screenW = size.x;
        screenH = size.y;
        unit = Ui.unit(this);
        // Measured from Apple's tvOS 26 Home Screen (1920x1080): dock 48 px from the screen edges,
        // 42 px inner padding, 248x148 px icons (5:3, the 800x480 HIG icon), 51 px between icons.
        dockOuter = dp(24);
        dockInset = dp(21);
        side = dockOuter + dockInset;  // grid rows line up with the dock icons (>= 80 pt safe area)
        gap = dp(25);
        tileW = (screenW - 2 * side - (COLS - 1) * gap) / COLS;
        tileH = tileW * 3 / 5;
        labelH = dp(30);
        radius = Math.round(tileW * 0.071f);  // Apple's tvOS 26 card corner: ~7% of card width
        shelfH = (int) (screenH * 0.73f);    // dock top sits ~73% down the screen
        dockPadV = dp(20);

        Clicks.init(this);
        // Startup: the app list (package manager, banners, icon colours) and the wallpaper are
        // prepared in the background while the window's views are built (~250 ms saved on 1 GHz TVs).
        java.util.concurrent.Future<?> appsTask = io.submit(this::loadApps);
        // Once: the calmer Night background replaces Aurora, the old default (still in Settings).
        if (!prefs.getBoolean("night_default", false)) {
            if ("aurora".equals(prefs.getString("wallpaper", "aurora"))) prefs.edit().putString("wallpaper", "night").apply();
            prefs.edit().putBoolean("night_default", true).apply();
        }
        String wall = prefs.getString("wallpaper", "night");
        wallPrefetch = new java.util.concurrent.FutureTask<>(() -> makeWallpaper(wall));
        wallPrefetchStyle = wall;
        new Thread((Runnable) wallPrefetch, "wallpaper").start();
        // After an update that changes what the top shelf holds, start from a fresh carousel.
        if (Suggestions.prefs(this).getInt("cache_format", 0) != 3) {
            Suggestions.changed(this);
            Suggestions.prefs(this).edit().putInt("cache_format", 3).apply();
        }
        buildChrome();
        try {
            appsTask.get();
        } catch (Exception e) {
            loadApps();
        }
        buildGrid();
        focusIndex(0);
        registerPackageReceiver();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // adb perf probe: --es perf weather|status|hero|dockglass|shadows hides one layer to measure its GPU cost.
        String perf = intent.getStringExtra("perf");
        if (perf != null) {
            switch (perf) {
                case "weather": weatherCard.setVisibility(View.GONE); break;
                case "status": clockBox.setVisibility(View.GONE); break;
                case "hero": for (ImageView v : heroLayers()) v.setVisibility(View.GONE); break;
                case "dockglass": if (dockRow != null) dockRow.setBackground(null); break;
                case "shadows": for (View t : tiles) t.setElevation(0); break;
                default: recreate(); break;
            }
            return;
        }
        // adb hook for screenshots: am start -n app.glasshome.launcher/.MainActivity --es panel cc|inputs|weather
        String panel = intent.getStringExtra("panel");
        if (panel != null) {
            closeMenu();
            if (panel.equals("cc")) openControlCentre();
            else if (panel.equals("inputs")) openInputs();
            else if (panel.equals("weather")) showWeather();
            else if (panel.equals("switcher") && switcher == null) AppSwitcher.open(this);  // Home twice (KeysService)
            return;
        }
        if (idle) exitIdle();
        resetIdle();
        // Home lands on the card of the app you just left, as on Apple TV (in the dock, the grid
        // or the folder), else on the first app in the dock. Close any overlay first (Control
        // Center, Inputs, weather forecast, menus, move mode, folder).
        int back = away && appOpened && openedKey != null ? indexOfKey(openedKey) : -1;
        if (switcher != null) switcher.close();
        if (pickerLayer != null) {
            popoverReturn = null;
            FrameLayout l = pickerLayer;
            pickerLayer = null;
            weatherOpen = false;
            root.removeView(l);
            weatherCard.setAlpha(weatherAlpha());
        }
        closeMenu();
        // Home pressed while already on the home screen: jump back to the top of the home grid.
        if (moveIndex >= 0) endMove();
        if (back >= 0) {
            focusIndex(back);
        } else if (folderMode) {
            folderMode = false;
            weatherCard.setAlpha(weatherAlpha());
            buildGrid();
            scroll.scrollTo(0, 0);
            focusIndex(0);
        } else {
            goTop();
        }
    }

    /** A different display, resolution or density: rebuild so every size is recomputed. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        android.graphics.Point size = Ui.screen(this);
        if (size.x != screenW || size.y != screenH || Ui.unit(this) != unit) recreate();
    }

    /** Home is on screen (started), even under a see-through page such as Search. */
    static volatile boolean onScreen;

    @Override
    protected void onStart() {
        super.onStart();
        onScreen = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        onScreen = false;
        away = true;
        if (switcher != null) switcher.dismissNow();
    }

    /** The App Switcher while it's up (Home pressed twice). */
    AppSwitcher switcher;

    /** Home went out of sight (an app in front) and hasn't been back in front since. */
    boolean away;

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        away = false;
        KeysService.ensureOn(this);
        String ws = Profiles.homePrefs(this).getString("wallpaper", "night");
        if (!ws.equals(wallStyle)) {
            wallStyle = ws;
            setWallpaper(ws);
        }
        if (returningFromSettings && !folderMode && moveIndex < 0) reload(currentKey());
        returningFromSettings = false;
        // Back from an app: Android animates the app's window away over Home first, and any work
        // Home does meanwhile costs that animation frames. So the refresh waits until it's done.
        if (appOpened) {
            handler.removeCallbacks(returnRun);
            handler.postDelayed(returnRun, 700);  // in case the callback never comes
        } else {
            handler.post(resumeWork);
        }
        // Setup not finished (e.g. Home was pressed during it): pick it up again.
        if (!SetupActivity.done(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            return;
        }
        // Home never holds the screen on: the TV's screen saver starts when it's idle.
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        resetIdle();
        if (settingsNext) {
            // Control Centre's Home Screen settings: opened from here, once Home is back in front.
            settingsNext = false;
            handler.post(() -> {
                if (resumed) startActivity(new Intent(this, SettingsActivity.class));
            });
        }
        // Opened from the app list while another launcher is the home screen: offer once to take over.
        if (SetupActivity.done(this) && !Perms.isDefaultHome(this) && !prefs.getBoolean("asked_default_home", false)) {
            prefs.edit().putBoolean("asked_default_home", true).apply();
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Use " + getString(R.string.app_name) + " as Your Home Screen?")
                    .setMessage("Your remote\u2019s Home button will open " + getString(R.string.app_name) + ".")
                    .setPositiveButton("Use " + getString(R.string.app_name), (d, w) -> Perms.requestDefaultHome(this))
                    .setNegativeButton("Not Now", null).show();
        }
    }

    /** An app was opened from Home and hasn't come back yet. */
    boolean appOpened;

    /** The app last opened from Home: pressing Home in it comes back to its card. */
    String openedKey;

    /** Settings to open as soon as Home is in front again (asked for in Control Centre). */
    static boolean settingsNext;

    final Runnable returnRun = () -> {
        if (!appOpened) return;
        appOpened = false;
        handler.postDelayed(this.resumeWork, 80);
    };

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        if (appOpened && resumed) {
            handler.removeCallbacks(returnRun);
            handler.post(returnRun);
        }
    }

    /** What Home refreshes when it comes back: the carousel and weather, and the minute timer. */
    final Runnable resumeWork = () -> {
        if (!resumed) return;
        if (switcher != null) {
            // The App Switcher opened over Home as it came back: the carousel and weather refresh
            // once it's gone, not while its cards are rising.
            handler.postDelayed(this.resumeWork, 500);
            return;
        }
        loadHero();
        updateWeather();
        resetIdle();  // the return clears pending callbacks, the idle timer among them
        handler.removeCallbacks(this.weatherTick);
        handler.post(this.weatherTick);
    };

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(resumeWork);
        handler.removeCallbacks(heroAdvance);
        handler.removeCallbacks(weatherTick);
        handler.removeCallbacks(idleRunnable);
        if (idle) exitIdle();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pkgReceiver != null) unregisterReceiver(pkgReceiver);
        if (netCallback != null) {
            try {
                getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) {
                // already unregistered
            }
        }
        handler.removeCallbacksAndMessages(null);
    }

    // ---------------------------------------------------------------- layout

    void buildChrome() {
        root = new FrameLayout(this);
        root.setBackground(null);

        // One ambient wallpaper for every app. It is drawn tiny and upscaled, so it is already
        // soft; a slow drift keeps it alive without a per-frame blur on this weak GPU.
        wallView = new ImageView(this);
        wallView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallStyle = Profiles.homePrefs(this).getString("wallpaper", "night");
        setWallpaper(wallStyle);
        root.addView(wallView, match());

        heroUnder = heroLayer();
        heroBack = heroLayer();
        heroFront = heroLayer();
        root.addView(heroUnder, match());
        root.addView(heroBack, match());
        root.addView(heroFront, match());
        // As on tvOS: with the apps open, the last picture from the carousel stays behind them as
        // soft coloured glass instead of going dark.
        drawerBack = new ImageView(this);
        drawerBack.setScaleType(ImageView.ScaleType.CENTER_CROP);
        drawerBack.setAlpha(0f);
        root.addView(drawerBack, match());
        // The shades that keep text legible are painted into the wallpaper and the artwork
        // (bakeShade, bakeScrims): one full-screen picture to draw, not three layers.

        scroll = new ShelfScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setFillViewport(true);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (!statusFocused()) clockBox.setAlpha(clockAlpha());
            if (weatherCard != null && !weatherCard.isFocused()) weatherCard.setAlpha(weatherAlpha());
            if (dockRow != null) dockRow.invalidate();
        });

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(0, 0, 0, dp(80));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, match());

        // Top shelf: header shown only inside the More Apps folder.
        FrameLayout shelf = new FrameLayout(this);
        shelfView = shelf;
        buildHeroInfo(shelf);
        titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(side, 0, side, dp(26));
        shelfTitle = text(46, Ui.font(this, "display"), 1f);
        shelfTitle.setSingleLine(true);
        shelfTitle.setEllipsize(TextUtils.TruncateAt.END);
        shelfHint = text(15, Ui.font(this, "regular"), 0.6f);
        titleBox.addView(shelfTitle);
        titleBox.addView(shelfHint);
        shelf.addView(titleBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        // Wide, thin chevron above the dock, as on tvOS.
        int cw = dp(30), ch = dp(9);
        Bitmap cb = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        cp.setColor(0xCCFFFFFF);
        cp.setStyle(Paint.Style.STROKE);
        cp.setStrokeWidth(dp(2));
        cp.setStrokeCap(Paint.Cap.ROUND);
        cp.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path cpath = new android.graphics.Path();
        float pad = dp(2);
        cpath.moveTo(pad, ch - pad);
        cpath.lineTo(cw / 2f, pad);
        cpath.lineTo(cw - pad, ch - pad);
        new Canvas(cb).drawPath(cpath, cp);
        ImageView chevronView = new ImageView(this);
        chevronView.setImageBitmap(cb);
        chevron = chevronView;
        FrameLayout.LayoutParams chp = new FrameLayout.LayoutParams(cw, ch,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        chp.bottomMargin = dp(14);
        shelf.addView(chevron, chp);
        content.addView(shelf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, shelfH));

        // tvOS 26 status row (per Apple's Control Center reference): plain time, then a glass
        // Control Center button that turns white and lifts when focused.
        int btn = dp(34);
        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.HORIZONTAL);
        clockBox.setGravity(Gravity.CENTER_VERTICAL);
        clockBox.setClipChildren(false);
        TextClock time = new TextClock(this);
        Ui.textSize(time, 16);
        time.setTypeface(Ui.font(this, "medium"));
        time.setTextColor(Color.WHITE);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("H:mm");
        time.setAllCaps(true);
        // Internet status, left of the time, drawn at its exact size (no scaling, so it stays crisp).
        netIcon = new ImageView(this);
        netIcon.setScaleType(ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.rightMargin = dp(7);
        // The network icon and time on the same glass as the gear beside it and the weather
        // opposite: one material across the top of the screen. Just to read, never focused.
        LinearLayout timeChip = new LinearLayout(this);
        timeChip.setOrientation(LinearLayout.HORIZONTAL);
        timeChip.setGravity(Gravity.CENTER_VERTICAL);
        timeChip.setPadding(dp(13), 0, dp(15), 0);
        timeChip.setBackground(pillGlass(btn / 2f));
        // No text shadow: the pill gives the contrast, and a blurred shadow is cut off at the
        // text's own edges, which showed as a faint dark box around the numbers.
        timeChip.addView(netIcon, nlp);
        timeChip.addView(time);
        clockBox.addView(timeChip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, btn));

        ccButton = new ImageView(this);
        ccButton.setScaleType(ImageView.ScaleType.CENTER);
        ccButton.setImageBitmap(Glyphs.get("gear", dp(20), Color.WHITE));
        LiquidGlass ccGlass = pillGlass(btn / 2f);
        ccGlass.owner = ccButton;
        GradientDrawable ccLit = new GradientDrawable();
        ccLit.setShape(GradientDrawable.OVAL);
        ccLit.setColor(Color.WHITE);
        ccLit.setAlpha(0);
        ccButton.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{ccGlass, ccLit}));
        ccButton.setFocusable(true);
        ccButton.setClickable(true);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(btn, btn);
        cblp.leftMargin = dp(12);
        clockBox.addView(ccButton, cblp);
        android.animation.ArgbEvaluator gearTint = new android.animation.ArgbEvaluator();
        ccButton.setOnFocusChangeListener((v, has) -> {
            // The white plate fades over the glass and the gear darkens with it, frame by frame
            // (one gear image, tinted, rather than a new one drawn and uploaded each time).
            ValueAnimator fade = ValueAnimator.ofFloat(ccLit.getAlpha() / 255f, has ? 1f : 0f);
            fade.setDuration(has ? 200 : 260);
            fade.setInterpolator(EASE);
            fade.addUpdateListener(an -> {
                float t = (float) an.getAnimatedValue();
                ccLit.setAlpha(Math.round(t * 255));
                ccButton.setColorFilter((int) gearTint.evaluate(t, Color.WHITE, 0xFF111114), android.graphics.PorterDuff.Mode.SRC_IN);
            });
            fade.start();
            Glass.lift(v, has, 1.12f, 0);
        });
        ccButton.setOnClickListener(v -> openControlCentre());

        // Room around the button for its focus growth (1.12x) so nothing is clipped;
        // the margins shrink by the same amount, so the row sits exactly where it did.
        int grow = dp(6);
        clockBox.setPadding(grow, grow, grow, grow);
        clockBox.setClipToPadding(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, btn + 2 * grow, Gravity.TOP | Gravity.END);
        // On the weather pill's centre line, so the two read as one row.
        clp.topMargin = dp(24) + (dp(WeatherView.COMPACT_H) - btn) / 2 - grow;
        clp.rightMargin = dockOuter - grow;
        root.addView(clockBox, clp);
        watchNetwork();

        // Weather overview in the top-left corner, over the top shelf; OK opens the forecast.
        // Drawn at its focused size and shown smaller at rest: lifting it on focus never stretches
        // its picture, so the text doesn't soften and then snap sharp.
        weatherCard = new WeatherView(this, false, unit * WEATHER_LIFT);
        // The same glass as the clock pill beside the gear: darker over bright pictures, never a glow.
        weatherGlass = pillGlass(dp(WeatherView.COMPACT_H) * WEATHER_LIFT / 2f);
        weatherCard.setBackground(weatherGlass);
        weatherCard.setFocusable(true);
        weatherCard.setClickable(true);
        weatherCard.setOnFocusChangeListener((v, has) -> {
            weatherGlass.lightUp(has);
            if (has) v.setAlpha(1f);
            // Spring up, ease down: a spring back down overshoots below zero height, which put the
            // pill behind the carousel for a moment (it vanished when focus moved to the gear).
            // Flat lift: its glass gives the TV no shape for a shadow, so one was drawn as a
            // rectangle every frame, which made the lift stutter.
            Glass.lift(v, has, 1f, 0, 1f / WEATHER_LIFT, false);
        });
        weatherCard.setPivotX(0);
        weatherCard.setPivotY(0);
        weatherCard.setScaleX(1f / WEATHER_LIFT);
        weatherCard.setScaleY(1f / WEATHER_LIFT);
        weatherCard.setOnClickListener(v -> showWeather());
        // Top left, level with the status row and inside the same 48 px edge margin.
        // As wide as what it says, so its margins are even whatever the weather and place.
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.round(dp(WeatherView.COMPACT_H) * WEATHER_LIFT),
                Gravity.TOP | Gravity.START);
        wlp.topMargin = dp(24);
        wlp.leftMargin = dockOuter;
        root.addView(weatherCard, wlp);
        updateWeather();

        setContentView(root);
    }

    void buildGrid() {
        stopWiggle();
        shown.clear();
        if (folderMode) {
            shown.addAll(hiddenApps);
        } else {
            shown.addAll(apps);
            if (!hiddenApps.isEmpty()) shown.add(folderEntry());
        }
        // Like tvOS, the home grid has no big title; only the folder gets a header.
        titleBox.setVisibility(folderMode ? View.VISIBLE : View.GONE);
        chevron.setVisibility(folderMode ? View.GONE : View.VISIBLE);
        shelfTitle.setText("More Apps");
        shelfHint.setText("Hold OK for options.");

        // Removing the focused card would make Android hand focus to the first card (and scroll
        // to the top); block that while rebuilding \u2014 callers focus the right card afterwards.
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        while (content.getChildCount() > 1) content.removeViewAt(1);
        tiles.clear();
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(side, 0, side, 0);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = gap / 2;
                View add = row;
                if (i == 0 && hasDock()) {
                    // tvOS 26 dock: the top row sits on one frosted glass panel. The glass is its own
                    // view behind the row, cached as a GPU layer: it is the costliest thing on Home to
                    // draw, and tile animations no longer make it repaint (only a new background or a
                    // scroll does, via invalidateGlass).
                    row.setPadding(dockInset, dockPadV, dockInset, dockPadV);
                    rlp.leftMargin = side - dockInset;
                    rlp.rightMargin = side - dockInset;
                    rlp.bottomMargin = dockGapBelow();
                    FrameLayout dock = new FrameLayout(this);
                    dock.setClipChildren(false);
                    dock.setClipToPadding(false);
                    View glass = new View(this);
                    glass.setBackground(dockBackground());
                    glass.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    dock.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    dock.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    dockRow = glass;
                    add = dock;
                }
                content.addView(add, rlp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLS != 0) lp.leftMargin = gap;
            row.addView(makeCell(shown.get(i), i, i < COLS && hasDock()), lp);
        }
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    View makeCell(AppEntry a, int index, boolean inDock) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);

        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setLongClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(a.banner != null ? 0xFF1C1C1E : a.color);
        tile.setBackground(bg);
        tile.setOutlineProvider(roundedOutline(new float[]{radius}));
        tile.setClipToOutline(true);
        tile.setElevation(0);  // only the focused tile casts a shadow (its lift); resting ones are flat

        ImageView iv = new ImageView(this);
        if (a.banner != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (a.tileArt != null) iv.setImageBitmap(a.tileArt);
            else iv.setImageDrawable(a.banner);
            tile.addView(iv, match());
        } else {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(a.icon);
            int s = tileH * 55 / 100;
            tile.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }

        // tvOS focus has no outline ring: a soft top sheen plus a one-shot glare sweep instead.
        View topSheen = new View(this);
        topSheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1AFFFFFF, 0x00FFFFFF}));
        topSheen.setAlpha(0f);
        tile.addView(topSheen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileH / 2, Gravity.TOP));
        View glare = new View(this);
        glare.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF}));
        glare.setRotation(18f);
        glare.setAlpha(0f);
        tile.addView(glare, new FrameLayout.LayoutParams(tileW * 45 / 100, tileH * 2, Gravity.CENTER_VERTICAL));

        TextView label = text(15, Ui.font(this, "medium"), 0f);
        label.setText(a.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);

        cell.addView(tile, new LinearLayout.LayoutParams(tileW, tileH));
        if (!inDock) cell.addView(label, new LinearLayout.LayoutParams(tileW, labelH));

        tile.setOnFocusChangeListener((v, has) -> {
            if (has) {
                cell.setTranslationZ(dp(10));
                if (moveIndex >= 0) {
                    // Rebuilt while moving: appear already lifted, or every step would "pop".
                    v.setScaleX(FOCUS_SCALE);
                    v.setScaleY(FOCUS_SCALE);
                    v.setTranslationZ(dp(18));
                    label.setAlpha(1f);
                    label.setTranslationY(tileH * (FOCUS_SCALE - 1f) / 2f);
                } else {
                    // Apple TV's jiggle: the card arrives with a small push in the direction the
                    // remote moved, then the spring settles it as it lifts.
                    int from = lastTile;
                    if (from >= 0 && from != index) {
                        boolean sameRow = from / COLS == index / COLS;
                        float push = dp(5);
                        v.setTranslationX(sameRow ? -Math.signum(index - from) * push : 0);
                        v.setTranslationY(sameRow ? 0 : -Math.signum(index - from) * push);
                    }
                    // No GPU layer: the glare and sheen move inside the card, so one would be
                    // re-rendered every frame anyway, at the cost of an extra pass.
                    v.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).translationX(0).translationY(0)
                            .translationZ(dp(18)).setDuration(560).setInterpolator(LIFT).start();
                    label.animate().alpha(1f).translationY(tileH * (FOCUS_SCALE - 1f) / 2f).setDuration(320)
                            .setInterpolator(EASE).start();
                    // Liquid-glass illumination: a glare sweeps across once, then rests on the card.
                    glare.animate().cancel();
                    glare.setTranslationX(-tileW * 0.7f);
                    glare.setAlpha(1f);
                    glare.animate().translationX(tileW * 1.1f).alpha(0f).setStartDelay(60).setDuration(700)
                            .setInterpolator(EASE).start();
                }
                topSheen.animate().alpha(1f).setDuration(260).setInterpolator(EASE).start();
                lastTile = index;
                onTileFocused(index);
            } else if (menuLayer != null && menuReturnFocus == v) {
                // Its menu is open beside it: the card stays up, as on tvOS, until focus moves on.
                glare.animate().cancel();
                glare.setAlpha(0f);
            } else {
                cell.setTranslationZ(0);
                v.animate().scaleX(1f).scaleY(1f).translationX(0).translationY(0).translationZ(0)
                        .setDuration(300).setInterpolator(EASE).start();
                label.animate().alpha(0f).translationY(0).setDuration(160).setInterpolator(EASE).start();
                topSheen.animate().alpha(0f).setDuration(220).setInterpolator(EASE).start();
                glare.animate().cancel();
                glare.setAlpha(0f);
            }
            v.invalidate();
        });
        tile.setCameraDistance(9000 * unit);
        tile.setOnClickListener(v -> {
            if (moveIndex >= 0) return;
            // Pressed state (HIG): the card dips instantly, then the action runs.
            v.animate().scaleX(FOCUS_SCALE * 0.92f).scaleY(FOCUS_SCALE * 0.92f).setDuration(90)
                    .setInterpolator(EASE).withEndAction(() -> {
                        v.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setDuration(420).setInterpolator(LIFT).start();
                        if (FOLDER_KEY.equals(a.key)) enterFolder();
                        else launchAnimated(v, a);
                    }).start();
        });
        tile.setOnLongClickListener(v -> {
            if (moveIndex < 0 && !FOLDER_KEY.equals(a.key)) showOptions(index);
            return true;
        });
        tiles.add(tile);
        return cell;
    }

    /** tvOS-style ambient colour field: soft glows of indigo, teal, magenta and amber on navy. */
    java.util.concurrent.FutureTask<Bitmap> wallPrefetch;
    String wallPrefetchStyle;

    void setWallpaper(String style) {
        Bitmap wp = null;
        if (wallPrefetch != null && style.equals(wallPrefetchStyle)) {
            try {
                wp = wallPrefetch.get();
            } catch (Exception ignored) {
                // generate below
            }
        }
        wallPrefetch = null;
        if (wp == null) wp = makeWallpaper(style);
        wallView.setImageBitmap(wp);
        wallBlur = Ui.glassBackdrop(wp, 160, 90);  // the carousel's size, so the glass can blend between them
        if (!heroShown || heroBlur == null) glassTo(wallBlur, 0);
        else invalidateGlass();
    }

    /** How much the weather pill grows when focused. */
    static final float WEATHER_LIFT = 1.05f;

    /**
     * The one glass for Home's small controls (weather, clock, gear) and Control Centre: the
     * picture behind, softly blurred, with a light tint that follows its brightness. The same
     * everywhere, so they always read as one material.
     */
    LiquidGlass controlGlass(float r) {
        LiquidGlass g = new LiquidGlass(r, 0x1F000000, glassSource, screenW, screenH, unit).adaptive();
        g.rimStrength = 0.45f;
        g.lens = Math.min(dp(6), Math.round(r * 0.3f));
        return g;
    }

    /** Home's own pills (weather, clock, gear): the same glass, clear as tvOS 26's. */
    LiquidGlass pillGlass(float r) {
        return controlGlass(r).clear();
    }

    void invalidateGlass() {
        if (dockRow != null) dockRow.invalidate();
        // Each glass view redraws itself: invalidating a parent doesn't redraw its children, which
        // left the gear and clock showing an old picture (the wallpaper) behind them.
        invalidateTree(clockBox);
        invalidateTree(weatherCard);
        if (menuLayer != null && menuLayer.getChildCount() > 1) menuLayer.getChildAt(1).invalidate();
    }

    static void invalidateTree(View v) {
        if (v == null) return;
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    static final String[] WALL_KEYS = {"night", "aurora", "midnight", "sunset", "ocean", "graphite"};
    static final String[] WALL_NAMES = {"Night", "Aurora", "Midnight", "Sunset", "Ocean", "Graphite"};

    Bitmap makeWallpaper(String style) {
        return bakeShade(makeWallpaperBase(style));
    }

    /** The bottom darkening that keeps the dock and labels legible, painted into the image. */
    static Bitmap bakeShade(Bitmap b) {
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, b.getHeight(), new int[]{0x14000000, 0x40000000, 0xB3000000},
                null, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, b.getWidth(), b.getHeight(), p);
        return b;
    }

    static Bitmap makeWallpaperBase(String style) {
        int w = 192, h = 108;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int base;
        int[] colors;
        switch (style) {
            case "midnight":
                base = 0xFF04060F;
                colors = new int[]{0xFF1B2A6B, 0xFF0F3D63, 0xFF2B1B5E, 0xFF14254F, 0xFF22306E};
                break;
            case "sunset":
                base = 0xFF140812;
                colors = new int[]{0xFFB0412E, 0xFF8E2C6E, 0xFFD08A2E, 0xFF6B2A8E, 0xFFC0563A};
                break;
            case "ocean":
                base = 0xFF031418;
                colors = new int[]{0xFF0C6E86, 0xFF0B8C76, 0xFF1A4FA0, 0xFF0E5E6E, 0xFF2A7FB0};
                break;
            case "night":  // near black with the faintest glow, so the content brings the colour
                base = 0xFF040406;
                colors = new int[]{0xFF1B2140, 0xFF0E2A32, 0xFF241830, 0xFF0C0E14, 0xFF161A26};
                break;
            case "graphite":
                base = 0xFF0C0C0E;
                colors = new int[]{0xFF3A3A42, 0xFF2A2C34, 0xFF4A4A52, 0xFF26262C, 0xFF34363E};
                break;
            default:
                base = 0xFF080C1E;
                colors = new int[]{0xFF4B2DB0, 0xFF0B7F8C, 0xFFB02F6E, 0xFFC27A2C, 0xFF2150C0};
        }
        c.drawColor(base);
        float[][] blobs = {      // centre x, centre y, radius (fractions of width)
                {0.14f, 0.18f, 0.62f},
                {0.88f, 0.22f, 0.50f},
                {0.72f, 0.92f, 0.55f},
                {0.20f, 0.95f, 0.42f},
                {0.50f, 0.45f, 0.38f}};
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        for (int i = 0; i < colors.length; i++) {
            int col = colors[i];
            p.setShader(new RadialGradient(blobs[i][0] * w, blobs[i][1] * h, blobs[i][2] * w,
                    new int[]{col & 0xD9FFFFFF, col & 0x59FFFFFF, col & 0x00FFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }
        return b;
    }

    /**
     * Left out of the app grid on this TV: TCL's quick panel, T-Solo, TCL Home, Tubi, and Live TV
     * (no aerial is connected; the app itself stays, it also runs the HDMI inputs).
     */
    static final Set<String> NOT_APPS = new HashSet<>(Arrays.asList(
            "com.tcl.dashboard", "com.tcl.t_solo", "com.tcl.suspension", "com.tubitv", "com.tcl.tv"));

    /** System/utility apps that get a drawn tvOS-style card instead of their Android art. */
    static final Set<String> TVOS_CARD_PKGS = new HashSet<>(Arrays.asList(
            TV_SETTINGS, "com.android.vending", "com.mitv.dream",
            // Their own banners crowd the logo and the name; as on Apple TV, the icon on its colour.
            "com.spotify.tv.android", "org.videolan.vlc",
            // Prime Video's banner is a soft 320 px picture; its icon is a vector, sharp at any size.
            "com.amazon.amazonvideo.livingroom",
            // TCL's own players have plain banners with the name printed on them.
            "com.tcl.ui_mediaCenter", "com.tcl.gallery",
            // Small logos floating on dark slabs.
            "com.channel4.ondemand"));
    /** Apps whose own banner is right but its logo too small: zoomed in on the logo, on the banner's colour. */
    static final Set<String> ZOOM_BANNER_PKGS = new HashSet<>(Arrays.asList("com.discovery.dplay"));

    /** An app's drawable at 4x density (xxxhdpi) when it has one, else the one already loaded. */
    Drawable hiRes(PackageManager pm, String pkg, int res, Drawable fallback) {
        if (res == 0) return fallback;
        try {
            Drawable d = pm.getResourcesForApplication(pkg).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
            return d != null ? d : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** True when two drawables show the same picture (some apps ship different art per density). */
    static boolean sameArt(Drawable x, Drawable y) {
        int w = 32, h = 18;
        int[] px = new int[w * h], py = new int[w * h];
        for (int k = 0; k < 2; k++) {
            Drawable d = k == 0 ? x : y;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(b));
            d.setBounds(old);
            b.getPixels(k == 0 ? px : py, 0, w, 0, 0, w, h);
            b.recycle();
        }
        long diff = 0;
        for (int i = 0; i < px.length; i++) diff += colorDistance(px[i], py[i]);
        return diff / px.length < 24;  // average per pixel, out of 765
    }

    /**
     * A tile's art, rendered once at the focused tile's exact pixel size: crisp when lifted, and
     * mipmapped so the resting tile scales down cleanly. Big banners are halved in steps first, so
     * thin strokes (Disney+'s script) keep their weight instead of breaking up.
     */
    Bitmap tileArt(Drawable d) {
        if (d == null || tileW <= 0) return null;
        return art(d, Math.round(tileW * FOCUS_SCALE), Math.round(tileH * FOCUS_SCALE));
    }

    /** Art cropped to w x h from the middle, as a tile shows it (the App Switcher's cards too). */
    static Bitmap art(Drawable d, int w, int h) {
        Bitmap src;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            src = ((BitmapDrawable) d).getBitmap();
        } else {
            int iw = Math.max(d.getIntrinsicWidth(), w * 2), ih = Math.max(d.getIntrinsicHeight(), h * 2);
            src = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, iw, ih);
            d.draw(new Canvas(src));
            d.setBounds(old);
        }
        // Centre crop to the tile's shape, as the tile shows it.
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float cw = w / scale, ch = h / scale;
        RectF crop = new RectF((src.getWidth() - cw) / 2f, (src.getHeight() - ch) / 2f,
                (src.getWidth() + cw) / 2f, (src.getHeight() + ch) / 2f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG | Paint.ANTI_ALIAS_FLAG);
        Bitmap cur = src;
        while (crop.width() > w * 2) {
            int hw = Math.round(crop.width() / 2f), hh = Math.round(crop.height() / 2f);
            Bitmap half = Bitmap.createBitmap(hw, hh, Bitmap.Config.ARGB_8888);
            new Canvas(half).drawBitmap(cur, new Rect(Math.round(crop.left), Math.round(crop.top),
                    Math.round(crop.right), Math.round(crop.bottom)), new RectF(0, 0, hw, hh), p);
            if (cur != src) cur.recycle();
            cur = half;
            crop.set(0, 0, hw, hh);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(cur, new Rect(Math.round(crop.left), Math.round(crop.top),
                Math.round(crop.right), Math.round(crop.bottom)), new RectF(0, 0, w, h), p);
        if (cur != src && cur != out) cur.recycle();
        if (!(d instanceof BitmapDrawable)) src.recycle();
        out.setHasMipMap(true);
        out.prepareToDraw();
        return out;
    }

    /**
     * The app's own banner, cropped to its logo and enlarged: everything that differs from the
     * banner's corner colour is the logo; it is scaled to fill the card, on that same colour.
     */
    Bitmap zoomedBanner(AppEntry e) {
        int w = 640, h = 360;
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Rect old = e.banner.copyBounds();
        e.banner.setBounds(0, 0, w, h);
        e.banner.draw(new Canvas(src));
        e.banner.setBounds(old);
        int bg = src.getPixel(4, 4);
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, b = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // The logo is what stands out in brightness; a soft glow behind it doesn't.
                int q = px[y * w + x];
                float lq = 0.2126f * Color.red(q) + 0.7152f * Color.green(q) + 0.0722f * Color.blue(q);
                float lb = 0.2126f * Color.red(bg) + 0.7152f * Color.green(bg) + 0.0722f * Color.blue(bg);
                if (lq - lb > 90) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > b) b = y;
                }
            }
        }
        if (r <= l || b <= t) return src;
        int pad = 6;
        l = Math.max(0, l - pad);
        t = Math.max(0, t - pad);
        r = Math.min(w - 1, r + pad);
        b = Math.min(h - 1, b + pad);
        // Only the logo's own pixels, with a soft edge: the glow and slab around it go transparent.
        int lw0 = r - l + 1, lh0 = b - t + 1;
        int[] cut = new int[lw0 * lh0];
        float lb = 0.2126f * Color.red(bg) + 0.7152f * Color.green(bg) + 0.0722f * Color.blue(bg);
        for (int y = 0; y < lh0; y++) {
            for (int x = 0; x < lw0; x++) {
                int q = px[(t + y) * w + l + x];
                float lq = 0.2126f * Color.red(q) + 0.7152f * Color.green(q) + 0.0722f * Color.blue(q);
                int a = Math.max(0, Math.min(255, Math.round((lq - lb - 40) / 60f * 255)));
                cut[y * lw0 + x] = (a << 24) | (q & 0xFFFFFF);
            }
        }
        Bitmap logo = Bitmap.createBitmap(cut, lw0, lh0, Bitmap.Config.ARGB_8888);
        float k = Math.min(w * 0.72f / logo.getWidth(), h * 0.5f / logo.getHeight());
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, lighten(bg, 0.12f), lighten(bg, -0.15f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        float lw = logo.getWidth() * k, lh = logo.getHeight() * k;
        c.drawBitmap(logo, null, new RectF((w - lw) / 2f, (h - lh) / 2f, (w + lw) / 2f, (h + lh) / 2f), p);
        return out;
    }

    /** The colour made lighter (amount > 0) or darker (< 0) by that fraction of its brightness. */
    static int lighten(int col, float amount) {
        float[] hsv = new float[3];
        Color.colorToHSV(col, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * (1f + amount)));
        return Color.HSVToColor(hsv);
    }

    /** The app's own icon in full colour, rendered at size px (for logos that need their colours). */
    static Bitmap colourIcon(AppEntry e, int size) {
        if (e.icon == null) return null;
        // Adaptive icons: just the foreground (the logo itself), not the coloured square behind it.
        Drawable d = e.icon instanceof android.graphics.drawable.AdaptiveIconDrawable
                ? ((android.graphics.drawable.AdaptiveIconDrawable) e.icon).getForeground() : e.icon;
        if (d == null) d = e.icon;
        Bitmap b = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, size * 2, size * 2);
        d.draw(new Canvas(b));
        d.setBounds(old);
        return fitGlyph(b, size);
    }

    /**
     * Prime Video's icon stacks "prime", "video" and the smile; its banner (one line) is only a
     * soft 320 px picture. This re-sets the sharp icon on one line, as the banner has it: the
     * words side by side on a shared baseline, the smile centred a little right beneath them.
     * Anything unexpected returns the icon as it was.
     */
    static Bitmap oneLine(Bitmap g) {
        int w = g.getWidth(), h = g.getHeight();
        int[] px = new int[w * h];
        g.getPixels(px, 0, w, 0, 0, w, h);
        // Connected shapes (letters, dots, the smile), 8-connected.
        int[] label = new int[w * h];
        int[] stack = new int[w * h];
        List<int[]> comps = new ArrayList<>();  // minX, minY, maxX, maxY, count
        for (int i = 0; i < px.length; i++) {
            if (label[i] != 0 || (px[i] >>> 24) <= 24) continue;
            int id = comps.size() + 1, sp = 0;
            int[] c = {w, h, -1, -1, 0};
            stack[sp++] = i;
            label[i] = id;
            while (sp > 0) {
                int k = stack[--sp], x = k % w, y = k / w;
                c[0] = Math.min(c[0], x); c[1] = Math.min(c[1], y);
                c[2] = Math.max(c[2], x); c[3] = Math.max(c[3], y);
                c[4]++;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int n = ny * w + nx;
                        if (label[n] == 0 && (px[n] >>> 24) > 24) {
                            label[n] = id;
                            stack[sp++] = n;
                        }
                    }
                }
            }
            comps.add(c);
        }
        if (comps.size() < 6) return g;
        // The smile is the lowest shape; the letters are the rest.
        int smile = 0, biggest = 0;
        for (int k = 0; k < comps.size(); k++) {
            if (comps.get(k)[3] > comps.get(smile)[3]) smile = k;
            biggest = Math.max(biggest, comps.get(k)[4]);
        }
        // The smile's arrowhead is a shape of its own: anything centred at or below the smile's top
        // belongs to the smile.
        int smileY = comps.get(smile)[1];
        boolean[] isSmile = new boolean[comps.size()];
        for (int k = 0; k < comps.size(); k++) isSmile[k] = (comps.get(k)[1] + comps.get(k)[3]) / 2f >= smileY;
        int top = h, bottom = 0;
        for (int k = 0; k < comps.size(); k++) {
            if (isSmile[k]) continue;
            top = Math.min(top, comps.get(k)[1]);
            bottom = Math.max(bottom, comps.get(k)[3]);
        }
        float cut = (top + bottom) / 2f;
        // Line of each shape (0 "prime", 1 "video", 2 smile): letters by their middle; a dot
        // (small) goes with the letter right below it.
        int[] line = new int[comps.size()];
        for (int k = 0; k < comps.size(); k++) {
            int[] c = comps.get(k);
            line[k] = isSmile[k] ? 2 : (c[1] + c[3]) / 2f < cut ? 0 : 1;
        }
        for (int k = 0; k < comps.size(); k++) {
            int[] c = comps.get(k);
            if (isSmile[k] || c[4] > biggest * 0.08f) continue;
            int below = -1;
            for (int m = 0; m < comps.size(); m++) {
                int[] o = comps.get(m);
                if (m == k || isSmile[m] || o[4] <= biggest * 0.08f || o[1] <= c[3]) continue;
                if (o[2] < c[0] || o[0] > c[2]) continue;  // must overlap it sideways
                if (below < 0 || o[1] < comps.get(below)[1]) below = m;
            }
            if (below >= 0) line[k] = line[below];
        }
        // Each line's box and baseline (the bottom of its last letter, "e" and "o", which sit on it).
        Rect[] box = {new Rect(w, h, 0, 0), new Rect(w, h, 0, 0), new Rect(w, h, 0, 0)};
        int[] last = {-1, -1};
        for (int k = 0; k < comps.size(); k++) {
            int[] c = comps.get(k);
            Rect b = box[line[k]];
            b.union(c[0], c[1], c[2] + 1, c[3] + 1);
            if (line[k] < 2 && c[4] > biggest * 0.08f && (last[line[k]] < 0 || c[2] > comps.get(last[line[k]])[2])) last[line[k]] = k;
        }
        if (last[0] < 0 || last[1] < 0 || box[2].isEmpty()) return g;
        int[] base = {comps.get(last[0])[3], comps.get(last[1])[3]};
        Rect p = box[0], v = box[1], sm = box[2];
        int gap = Math.round(v.height() * 0.24f);
        int ascent = Math.max(base[0] - p.top, base[1] - v.top);
        int descent = Math.max(p.bottom - base[0], v.bottom - base[1]);
        int smileTop = ascent + (sm.top - base[1]);
        int outW = p.width() + gap + v.width();
        int outH = Math.max(ascent + descent, smileTop + sm.height());
        int[] dx = {-p.left, p.width() + gap - v.left, 0};
        int[] dy = {ascent - base[0], ascent - base[1], smileTop - sm.top};
        dx[2] = Math.round(outW * 0.56f - sm.width() / 2f) - sm.left;
        int[] out = new int[outW * outH];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int k = label[y * w + x];
                if (k == 0) continue;
                int l = line[k - 1], ox = x + dx[l], oy = y + dy[l];
                if (ox >= 0 && oy >= 0 && ox < outW && oy < outH) out[oy * outW + ox] = px[y * w + x];
            }
        }
        return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888);
    }

    /** The glyph without the icon's built-in padding, scaled so its taller side is size px. */
    static Bitmap fitGlyph(Bitmap g, int size) {
        int w = g.getWidth(), h = g.getHeight();
        int[] px = new int[w * h];
        g.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, b = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 24) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > b) b = y;
                }
            }
        }
        if (r < l || b < t) return g;
        Bitmap c = Bitmap.createBitmap(g, l, t, r - l + 1, b - t + 1);
        float k = size / (float) Math.max(c.getWidth(), c.getHeight());
        return Bitmap.createScaledBitmap(c, Math.max(1, Math.round(c.getWidth() * k)), Math.max(1, Math.round(c.getHeight() * k)), true);
    }

    /**
     * tvOS-style card: soft vertical gradient with a top sheen and the app's glyph centred.
     * Settings gets the silver card with a drawn gear, like tvOS.
     */
    Bitmap tvosCard(AppEntry e) {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        boolean settings = TV_SETTINGS.equals(e.pkg);
        int top, bottom;
        if (settings) {                                  // tvOS Settings: silver
            top = 0xFFE9E9EE;
            bottom = 0xFFAFAFB8;
        } else if ("com.android.vending".equals(e.pkg)) { // Google Play: its own colours on white
            top = 0xFFFFFFFF;
            bottom = 0xFFE6E7EC;
        } else if ("com.channel4.ondemand".equals(e.pkg)) { // Channel 4: its white 4 on black
            top = 0xFF232326;
            bottom = 0xFF050506;
        } else if ("com.tcl.ui_mediaCenter".equals(e.pkg)) { // Media Player: indigo
            top = 0xFF6E6BFF;
            bottom = 0xFF3528C9;
        } else if ("com.tcl.gallery".equals(e.pkg)) {   // Gallery: warm sunset
            top = 0xFFFFB347;
            bottom = 0xFFFF4F79;
        } else if ("com.spotify.tv.android".equals(e.pkg)) { // Spotify's green logo on black
            top = 0xFF242424;
            bottom = 0xFF050505;
        } else if ("com.amazon.amazonvideo.livingroom".equals(e.pkg)) { // Prime Video: its own blue
            top = lighten(0xFF0578FF, 0.07f);
            bottom = lighten(0xFF0578FF, -0.06f);
        } else if ("org.videolan.vlc".equals(e.pkg)) {   // VLC: its white cone on orange, as on Apple TV
            top = 0xFFF8A00A;
            bottom = 0xFFE35C00;
        } else {                                         // vivid gradient in the app's own hue
            float[] hsv = new float[3];
            Color.colorToHSV(e.color, hsv);
            float sat = hsv[1] < 0.15f ? 0.12f : 0.7f;   // greys stay neutral
            top = Color.HSVToColor(new float[]{hsv[0], sat * 0.85f, 0.92f});
            bottom = Color.HSVToColor(new float[]{(hsv[0] + 8f) % 360f, Math.min(1f, sat * 1.15f), sat < 0.2f ? 0.42f : 0.68f});
        }
        p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        if (settings) {
            drawSettingsGear(c, w / 2f, h / 2f, h * 0.3f);
            return b;
        }
        String drawn = "com.tcl.ui_mediaCenter".equals(e.pkg) ? "mediaplayer" : "com.tcl.gallery".equals(e.pkg) ? "photos"
                : "org.videolan.vlc".equals(e.pkg) ? "vlc" : null;
        boolean colour = "com.android.vending".equals(e.pkg);
        // Cards that trim and fit their glyph draw it large first and shrink it, so it stays crisp.
        int gs = "com.amazon.amazonvideo.livingroom".equals(e.pkg) ? h * 2 : TVOS_CARD_PKGS.contains(e.pkg) ? h : (int) (h * 0.5f);
        Bitmap glyph = drawn != null ? Glyphs.get(drawn, (int) (h * ("vlc".equals(drawn) ? 0.66f : 0.58f)), Color.WHITE)
                : colour ? colourIcon(e, gs) : whiteGlyph(e, gs);
        if ("com.channel4.ondemand".equals(e.pkg)) {
            // Channel 4: the icon's own "4" (its foreground layer), in white on black.
            // Its icon is a green square with a dark 4: keep the dark strokes, as white.
            Bitmap four = colourIcon(e, gs);
            if (four != null) {
                int fw = four.getWidth(), fh = four.getHeight();
                int[] px = new int[fw * fh];
                four.getPixels(px, 0, fw, 0, 0, fw, fh);
                for (int i = 0; i < px.length; i++) {
                    int q = px[i];
                    float lum = (0.2126f * Color.red(q) + 0.7152f * Color.green(q) + 0.0722f * Color.blue(q)) / 255f;
                    int a = (q >>> 24) < 200 ? 0 : Math.max(0, Math.min(255, Math.round((0.55f - lum) / 0.35f * 255)));
                    px[i] = (a << 24) | 0xFFFFFF;
                }
                glyph = fitGlyph(Bitmap.createBitmap(px, fw, fh, Bitmap.Config.ARGB_8888), (int) (h * 0.5f));
            }
        }
        int edge = glyph == null ? 0 : glyph.getPixel(glyph.getWidth() / 2, Math.min(3, glyph.getHeight() - 1));
        // A square icon is opaque at the middle of its top, left and right edges (a logo shape isn't).
        boolean square = glyph != null && (edge >>> 24) == 0xFF
                && (glyph.getPixel(2, glyph.getHeight() / 2) >>> 24) == 0xFF
                && (glyph.getPixel(glyph.getWidth() - 3, glyph.getHeight() / 2) >>> 24) == 0xFF;
        if (colour && square) {
            // An opaque square icon: the card takes the icon's edge colour, so the square melts in.
            p.setShader(new LinearGradient(0, 0, 0, h, lighten(edge, 0.08f), lighten(edge, -0.12f), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }
        if (glyph != null && "com.amazon.amazonvideo.livingroom".equals(e.pkg)) glyph = oneLine(glyph);
        if (glyph != null && drawn == null && TVOS_CARD_PKGS.contains(e.pkg)) {
            float fit = "com.amazon.amazonvideo.livingroom".equals(e.pkg) ? 1.12f : 0.58f;
            glyph = fitGlyph(glyph, (int) (h * fit));
        }
        if (glyph != null && "com.spotify.tv.android".equals(e.pkg)) {
            // Spotify's logo in Spotify green, as on its Apple TV icon.
            Bitmap g = Bitmap.createBitmap(glyph.getWidth(), glyph.getHeight(), Bitmap.Config.ARGB_8888);
            Paint gp = new Paint(Paint.FILTER_BITMAP_FLAG);
            gp.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFF1ED760, android.graphics.PorterDuff.Mode.SRC_IN));
            new Canvas(g).drawBitmap(glyph, 0, 0, gp);
            glyph = g;
        }
        if (glyph != null) {
            // Soft drop shadow under the glyph, like tvOS system icons.
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            sp.setColorFilter(new android.graphics.PorterDuffColorFilter(0x38000000, android.graphics.PorterDuff.Mode.SRC_IN));
            sp.setMaskFilter(new android.graphics.BlurMaskFilter(h * 0.025f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            float x = (w - glyph.getWidth()) / 2f, y = (h - glyph.getHeight()) / 2f;
            c.drawBitmap(glyph.extractAlpha(), x, y + h * 0.015f, sp);
            c.drawBitmap(glyph, x, y, new Paint(Paint.FILTER_BITMAP_FLAG));
        } else if (e.icon != null) {
            int sz = (int) (h * 0.46f);
            Rect old = e.icon.copyBounds();
            e.icon.setBounds((w - sz) / 2, (h - sz) / 2, (w + sz) / 2, (h + sz) / 2);
            e.icon.draw(c);
            e.icon.setBounds(old);
        }
        return b;
    }

    /**
     * The app's glyph as a flat white shape (tvOS system-icon style): uses the adaptive icon's
     * foreground layer when there is one. Returns null for full-bleed artwork that can't be
     * reduced to a glyph, so it is shown as-is instead of becoming a white blob.
     */
    Bitmap whiteGlyph(AppEntry e, int size) {
        if (e.icon == null) return null;
        Drawable d = e.icon;
        float zoom = 1f;
        if (d instanceof android.graphics.drawable.AdaptiveIconDrawable) {
            Drawable fg = ((android.graphics.drawable.AdaptiveIconDrawable) d).getForeground();
            if (fg != null) {
                d = fg;
                zoom = 1.5f;  // adaptive foregrounds keep the glyph in the centre 2/3
            }
        }
        int big = Math.round(size * zoom);
        Bitmap full = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, big, big);
        d.draw(new Canvas(full));
        d.setBounds(old);
        Bitmap g = Bitmap.createBitmap(full, (big - size) / 2, (big - size) / 2, size, size);
        int[] px = new int[size * size];
        g.getPixels(px, 0, size, 0, 0, size, size);
        int solid = 0;
        for (int v : px) if (Color.alpha(v) > 128) solid++;
        if (solid > px.length * 0.6f || solid < px.length * 0.02f) return null;
        for (int i = 0; i < px.length; i++) px[i] = (Color.alpha(px[i]) << 24) | 0xFFFFFF;
        Bitmap out = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        if (g != full) g.recycle();
        full.recycle();
        return out;
    }

    /** The Settings tile itself (silver, with the gear), for anywhere else that shows it. */
    static Bitmap settingsArt(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFFE9E9EE, 0xFFAFAFB8, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        drawSettingsGear(c, w / 2f, h / 2f, h * 0.3f);
        return b;
    }

    /**
     * The Settings gear: one bold, rounded eight-tooth cog in graphite with a silver hub, lit
     * from above and resting on a soft shadow. All vector, so it's sharp at any size.
     */
    static void drawSettingsGear(Canvas c, float cx, float cy, float r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        android.graphics.Path g = cog(cx, cy, r, r * 0.76f, 8, 0.46f);
        p.setPathEffect(new android.graphics.CornerPathEffect(r * 0.1f));
        p.setColor(0x47000000);
        p.setMaskFilter(new android.graphics.BlurMaskFilter(r * 0.07f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, r * 0.05f);
        c.drawPath(g, p);
        c.restore();
        p.setMaskFilter(null);
        // Graphite, lit from the top (an opaque colour, so the gradient isn't faded by it).
        p.setColor(Color.BLACK);
        p.setShader(new LinearGradient(0, cy - r, 0, cy + r, 0xFF6B6E76, 0xFF2B2D32, Shader.TileMode.CLAMP));
        c.drawPath(g, p);
        p.setPathEffect(null);
        // The hub: a silver disc with a graphite centre.
        p.setShader(new LinearGradient(0, cy - r * 0.36f, 0, cy + r * 0.36f, 0xFFF4F4F7, 0xFFA3A5AD, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 0.36f, p);
        p.setShader(new LinearGradient(0, cy - r * 0.14f, 0, cy + r * 0.14f, 0xFF5A5D64, 0xFF2B2D32, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 0.14f, p);
    }

    /** A cog outline: teeth from rIn out to rOut, each tooth this fraction of its slot wide at the tip. */
    static android.graphics.Path cog(float cx, float cy, float rOut, float rIn, int teeth, float tip) {
        android.graphics.Path g = new android.graphics.Path();
        double step = Math.toRadians(360.0 / teeth);
        float base = Math.min(0.95f, tip + 0.2f);  // a little wider where it meets the body
        for (int i = 0; i < teeth; i++) {
            double a0 = Math.toRadians(i * 360.0 / teeth - 90);
            double[] at = {a0, a0 + step * (0.5 - base / 2), a0 + step * (0.5 - tip / 2),
                    a0 + step * (0.5 + tip / 2), a0 + step * (0.5 + base / 2)};
            float[] rad = {rIn, rIn, rOut, rOut, rIn};
            for (int k = 0; k < at.length; k++) {
                float x = cx + (float) Math.cos(at[k]) * rad[k], y = cy + (float) Math.sin(at[k]) * rad[k];
                if (i == 0 && k == 0) g.moveTo(x, y);
                else g.lineTo(x, y);
            }
        }
        g.close();
        return g;
    }

    static void drawGear(Canvas c, float cx, float cy, float r, int color, int holeColor) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        int teeth = 12;
        float tw = r * 0.30f, th = r * 0.30f;
        for (int i = 0; i < teeth; i++) {
            c.save();
            c.rotate(i * 360f / teeth, cx, cy);
            c.drawRoundRect(cx - tw / 2, cy - r - th * 0.55f, cx + tw / 2, cy - r + th, tw * 0.25f, tw * 0.25f, p);
            c.restore();
        }
        c.drawCircle(cx, cy, r, p);
        p.setColor(holeColor);
        c.drawCircle(cx, cy, r * 0.42f, p);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.08f);
        c.drawCircle(cx, cy, r * 0.62f, p);
    }

    boolean hasDock() {
        return !folderMode;
    }

    int dockGapBelow() {
        return dp(20);
    }

    /** The dock (tvOS 26): clear glass with a big radius, the picture showing through, a crisp rim. */
    Drawable dockBackground() {
        // Apple's dock corner is ~2x the card corner (36 px vs ~18 px at 1080p).
        LiquidGlass g = new LiquidGlass(radius * 2f, 0x0AFFFFFF, glassSource, screenW, screenH, unit).clear();
        g.rimStrength = 0.5f;
        return g;
    }

    /**
     * Liquid Glass (iOS/tvOS 26), drawn from what is actually behind the surface: a blurred,
     * colour-boosted copy of the backdrop aligned to the surface's screen position; a band along
     * the edges that magnifies it (lensing, like thick glass); a light tint; and a specular rim
     * that is bright top-left and softer bottom-right. No grain.
     */
    static class LiquidGlass extends Drawable {
        interface Backdrop {
            Bitmap get();
        }

        final float r;
        final Backdrop backdrop;
        final int screenW, screenH;
        final int baseTint;
        float lens;
        float rimStrength = 1f;
        // Every layer is a single rounded-rect draw (shader-filled), which the GPU does cheaply;
        // there is no clipping and no off-screen/software rendering at draw time.
        final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint lens1 = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint lens2 = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF box = new android.graphics.RectF(), tmp = new android.graphics.RectF();
        final android.graphics.Matrix m = new android.graphics.Matrix();
        final int[] loc = new int[2];
        Bitmap shaderFor;
        android.graphics.BitmapShader shader1, shader2, shader3;

        LiquidGlass(float r, int tint, Backdrop backdrop, int screenW, int screenH, float density) {
            this.r = r;
            this.baseTint = tint;
            this.backdrop = backdrop;
            this.screenW = screenW;
            this.screenH = screenH;
            fill.setColor(tint);
            lens1.setStyle(Paint.Style.STROKE);
            lens2.setStyle(Paint.Style.STROKE);
            lens1.setAlpha(150);
            lens2.setAlpha(150);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.1f * density);
        }

        boolean adaptive;
        int adaptiveTint;
        /** How lit the glass is: 0 resting, 1 focused (a light frost over the picture). */
        float lit;
        Bitmap lumFor;
        int lumGen, lumX = Integer.MIN_VALUE, lumY;

        /** The view this glass sits on, when it is drawn inside another drawable (for its position). */
        View owner;

        /**
         * Clear glass, as tvOS 26's dock and status pills: the picture behind shows through,
         * softened rather than frosted over, under a crisp light rim.
         */
        LiquidGlass clear() {
            clear = true;
            body.setAlpha(105);
            // No magnifying bands at the edge: through clear glass each one added its own step of
            // tint, which showed as lines inside the rim. One smooth surface instead.
            lens = 0;
            rimStrength = Math.max(rimStrength, 0.7f);
            return this;
        }

        boolean clear;

        /**
         * Glass for small controls (Liquid Glass "regular"): the backdrop is desaturated so the
         * control reads as clear glass rather than a coloured blob, and the tint follows the
         * brightness behind it \u2014 darker over bright artwork so white text stays legible, a light
         * frost over dark artwork so the control stays visible.
         */
        LiquidGlass adaptive() {
            adaptive = true;
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(0.4f);
            android.graphics.ColorMatrixColorFilter f = new android.graphics.ColorMatrixColorFilter(cm);
            body.setColorFilter(f);
            lens1.setColorFilter(f);
            lens2.setColorFilter(f);
            return this;
        }

        static final android.animation.ArgbEvaluator ARGB = new android.animation.ArgbEvaluator();

        void setLit(float t) {
            lit = t;
            fill.setColor(fillColor());
            invalidateSelf();
        }

        int fillColor() {
            int rest = adaptive && adaptiveTint != 0 ? adaptiveTint : baseTint;
            return lit <= 0f ? rest : (int) ARGB.evaluate(lit, rest, 0x52FFFFFF);
        }

        /** Fades the light in or out on the shared focus timing, as the white plates do. */
        void lightUp(boolean on) {
            if (litAnim != null) litAnim.cancel();
            litAnim = ValueAnimator.ofFloat(lit, on ? 1f : 0f);
            litAnim.setDuration(on ? 200 : 260);
            litAnim.setInterpolator(EASE);
            litAnim.addUpdateListener(a -> setLit((float) a.getAnimatedValue()));
            litAnim.start();
        }

        ValueAnimator litAnim;

        /** Average luminance (0..1) of the backdrop under this surface, from up to 8x8 samples. */
        float luminanceBehind(Bitmap b) {
            float sx = b.getWidth() / (float) screenW, sy = b.getHeight() / (float) screenH;
            int x0 = Math.max(0, (int) ((loc[0] + box.left) * sx)), y0 = Math.max(0, (int) ((loc[1] + box.top) * sy));
            int x1 = Math.min(b.getWidth() - 1, (int) ((loc[0] + box.right) * sx)), y1 = Math.min(b.getHeight() - 1, (int) ((loc[1] + box.bottom) * sy));
            if (x1 < x0 || y1 < y0) return 0.3f;
            float sum = 0;
            int n = 0;
            for (int j = 0; j < 8; j++) {
                for (int i = 0; i < 8; i++) {
                    int px = b.getPixel(x0 + (x1 - x0) * i / 7, y0 + (y1 - y0) * j / 7);
                    sum += (0.2126f * Color.red(px) + 0.7152f * Color.green(px) + 0.0722f * Color.blue(px)) / 255f;
                    n++;
                }
            }
            return sum / n;
        }

        static int tintFor(float lum) {
            if (lum >= 0.45f) {  // bright: darken, up to 36% black
                int a = Math.round(0x1F + (Math.min(lum, 0.85f) - 0.45f) / 0.4f * (0x5C - 0x1F));
                return a << 24;
            }
            int a = Math.round((0.45f - Math.max(lum, 0.05f)) / 0.4f * 0x24);  // dark: light frost
            return (a << 24) | 0xFFFFFF;
        }

        int rimA(int a) {
            return (Math.round(a * rimStrength) << 24) | 0xFFFFFF;
        }

        @Override protected void onBoundsChange(Rect b) {
            box.set(b);
            float h = box.height();
            glow.setShader(new LinearGradient(0, box.top, 0, box.top + h * 0.5f,
                    0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                    new int[]{rimA(0xD9), rimA(0x40), rimA(0x14), rimA(0x26), rimA(0x8C)},
                    new float[]{0f, 0.22f, 0.5f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            float l = Math.min(lens, Math.min(box.width(), box.height()) / 3f);
            lens1.setStrokeWidth(l);
            lens2.setStrokeWidth(l / 2f);
        }

        /** Maps the full-screen backdrop onto this surface's on-screen spot, optionally magnified. */
        void place(android.graphics.BitmapShader sh, Bitmap b, float zoom) {
            m.reset();
            m.setScale(screenW / (float) b.getWidth(), screenH / (float) b.getHeight());
            m.postTranslate(box.left - loc[0], box.top - loc[1]);
            if (zoom != 1f) m.postScale(zoom, zoom, box.centerX(), box.centerY());
            sh.setLocalMatrix(m);
        }

        @Override public void draw(Canvas c) {
            Bitmap b = backdrop.get();
            Object cb = owner != null ? owner : getCallback();
            if (cb instanceof View) ((View) cb).getLocationOnScreen(loc);
            if (b != null) {
                if (b != shaderFor) {
                    shader1 = new android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader2 = new android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader3 = new android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    body.setShader(shader1);
                    lens1.setShader(shader2);
                    lens2.setShader(shader3);
                    shaderFor = b;
                }
                if (adaptive && (b != lumFor || b.getGenerationId() != lumGen || loc[0] != lumX || loc[1] != lumY)) {
                    // Only when the backdrop (or its pixels, mid-blend) or this surface's position
                    // changes, not every frame.
                    lumFor = b;
                    lumGen = b.getGenerationId();
                    lumX = loc[0];
                    lumY = loc[1];
                    int t = tintFor(luminanceBehind(b));
                    // Clear glass keeps just enough shade for white text over a bright picture.
                    if (clear) t = (Math.round((t >>> 24) * 0.7f) << 24) | (t & 0xFFFFFF);
                    if (t != adaptiveTint) {
                        adaptiveTint = t;
                        fill.setColor(fillColor());
                    }
                }
                place(shader1, b, 1f);
                c.drawRoundRect(box, r, r, body);
                if (lens > 0) {
                    // Edge lensing: the band along the rim shows the backdrop magnified (two soft steps).
                    float w1 = lens1.getStrokeWidth(), w2 = lens2.getStrokeWidth();
                    place(shader2, b, 1.035f);
                    tmp.set(box.left + w1 / 2, box.top + w1 / 2, box.right - w1 / 2, box.bottom - w1 / 2);
                    c.drawRoundRect(tmp, r - w1 / 2, r - w1 / 2, lens1);
                    place(shader3, b, 1.06f);
                    tmp.set(box.left + w2 / 2, box.top + w2 / 2, box.right - w2 / 2, box.bottom - w2 / 2);
                    c.drawRoundRect(tmp, r - w2 / 2, r - w2 / 2, lens2);
                }
            }
            c.drawRoundRect(box, r, r, fill);
            c.drawRoundRect(box, r, r, glow);
            float in = rim.getStrokeWidth() / 2f;
            c.drawRoundRect(box.left + in, box.top + in, box.right - in, box.bottom - in, r - in, r - in, rim);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Outline whose corner radius can be changed while animating (radius[0]). */
    ViewOutlineProvider roundedOutline(float[] r) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r[0]);
            }
        };
    }

    // ---------------------------------------------------------------- suggestions carousel

    ImageView heroLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setScaleX(1f + 2f * HERO_PAN);
        v.setScaleY(1f + 2f * HERO_PAN);
        v.setAlpha(0f);
        return v;
    }

    /** Title logo (or title), meta line, overview, "Open in \u2026" capsule and page dots, bottom-left of the shelf. */
    void buildHeroInfo(FrameLayout shelf) {
        heroInfo = new LinearLayout(this);
        heroInfo.setOrientation(LinearLayout.VERTICAL);
        heroInfo.setClipChildren(false);
        heroInfo.setClipToPadding(false);
        heroInfo.setAlpha(0f);
        heroProvider = new ImageView(this);
        heroProvider.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroProvider.setOutlineProvider(roundedOutline(new float[]{dp(7)}));
        heroProvider.setClipToOutline(true);
        heroProvider.setElevation(dp(2));
        LinearLayout.LayoutParams pvlp = new LinearLayout.LayoutParams(dp(30), dp(30));
        pvlp.bottomMargin = dp(12);
        heroInfo.addView(heroProvider, pvlp);
        heroLogo = new ImageView(this);
        heroLogo.setAdjustViewBounds(true);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_START);
        heroLogo.setMaxWidth(dp(260));
        heroLogo.setMaxHeight(dp(90));
        heroInfo.addView(heroLogo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        heroTitle = text(36, Ui.font(this, "display"), 1f);
        heroTitle.setLetterSpacing(-0.02f);
        heroTitle.setIncludeFontPadding(false);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroInfo.addView(heroTitle, new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT));
        heroMeta = text(12.5f, Ui.font(this, "medium"), 0.8f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(10);
        heroInfo.addView(heroMeta, mlp);
        heroOverview = text(12.5f, Ui.font(this, "regular"), 0.8f);
        heroOverview.setMaxLines(2);
        heroOverview.setEllipsize(TextUtils.TruncateAt.END);
        heroOverview.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(6);
        heroInfo.addView(heroOverview, olp);
        heroButton = new LinearLayout(this);
        heroButton.setOrientation(LinearLayout.HORIZONTAL);
        heroButton.setGravity(Gravity.CENTER_VERTICAL);
        heroButton.setPadding(dp(18), 0, dp(20), 0);
        heroBtnBg = new GradientDrawable();
        heroBtnBg.setCornerRadius(dp(16));
        heroBtnBg.setColor(0x47FFFFFF);
        heroButton.setBackground(heroBtnBg);
        heroBtnText = text(13, Ui.font(this, "semibold"), 1f);
        heroBtnText.setShadowLayer(0, 0, 0, 0);
        heroButton.addView(heroBtnText);
        heroBtnIcon = new ImageView(this);
        heroBtnIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroBtnIcon.setOutlineProvider(roundedOutline(new float[]{dp(5)}));
        heroBtnIcon.setClipToOutline(true);
        LinearLayout.LayoutParams iclp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iclp.leftMargin = dp(9);
        heroButton.addView(heroBtnIcon, iclp);
        // The Open capsule's row: it keeps its height while the capsule itself is hidden.
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setClipChildren(false);
        btnRow.addView(heroButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        blp.topMargin = dp(14);
        heroInfo.addView(btnRow, blp);
        // Overview and button only show while the carousel itself is focused (tvOS).
        heroOverview.setAlpha(0f);
        heroButton.setAlpha(0f);
        heroOverview.setVisibility(View.GONE);
        heroButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        int room = dp(12);
        heroInfo.setPadding(room, room, room, room);
        ilp.leftMargin = side - room;
        ilp.bottomMargin = dp(40) - room;
        root.addView(heroInfo, ilp);
        heroInfoY = heroInfoRestY();
        heroInfo.setTranslationY(heroInfoY);

        heroDots = new LinearLayout(this);
        heroDots.setOrientation(LinearLayout.HORIZONTAL);
        dotRow = new LinearLayout(this);
        dotRow.setOrientation(LinearLayout.HORIZONTAL);
        dotRow.setGravity(Gravity.CENTER_VERTICAL);
        heroDots.addView(dotRow);
        heroDots.setAlpha(0f);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = dp(22);
        root.addView(heroDots, dlp);
        heroDotsY = heroDotsRestY();
        heroDots.setTranslationY(heroDotsY);

        // The shelf itself is the carousel's focus target (UP from the dock).
        shelf.setFocusable(false);
        shelf.setOnFocusChangeListener((v, has) -> heroFocused(has));
    }

    /**
     * Darkening for legibility (left side for the text, bottom for the dock), painted into the
     * artwork once when it loads, so the GPU draws one full-screen image instead of three layers.
     */
    Bitmap bakeScrims(Bitmap src) {
        if (src == null) return null;
        Bitmap b = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
        if (b != src) src.recycle();
        // Three shades: from the left for the title (80% fading out three quarters across), from
        // the bottom for the dock (8% to 70%), along the top for the clock and weather (40% fading
        // out a fifth of the way down). Each darkens by a factor that depends on x or on y alone,
        // so they're applied together in one pass of integer sums over the pixels rather than as
        // three full-size gradients, which kept the processor busy through the dissolve.
        int w = b.getWidth(), h = b.getHeight();
        int[] keepX = new int[w], row = new int[w];
        for (int x = 0; x < w; x++) {
            float fx = (x + 0.5f) / w / 0.75f;
            float left = fx >= 1f ? 0f : fx < 0.5f ? 0.8f - 0.4f * fx / 0.5f : 0.4f * (1f - (fx - 0.5f) / 0.5f);
            keepX[x] = Math.round((1f - left) * 256);
        }
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) / h;
            float bottom = fy < 0.5f ? 0.078f + 0.173f * fy / 0.5f : 0.251f + 0.451f * (fy - 0.5f) / 0.5f;
            float top = fy < 0.2f ? 0.4f * (1f - fy / 0.2f) : 0f;
            int keepY = Math.round((1f - bottom) * (1f - top) * 256);
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int k = keepX[x] * keepY >> 8, p = row[x];
                row[x] = (p & 0xFF000000) | ((p >> 16 & 0xFF) * k >> 8) << 16 | ((p >> 8 & 0xFF) * k >> 8) << 8 | (p & 0xFF) * k >> 8;
            }
            b.setPixels(row, 0, w, 0, y, w, 1);
        }
        return b;
    }

    /** Info block sits just above the dock while a dock app is focused... */
    float heroInfoRestY() {
        return -(screenH - shelfH) + dp(40) - dp(36);
    }

    /** ...and the page dots sit centred between the info and the dock. */
    float heroDotsRestY() {
        return -(screenH - shelfH) + dp(22) - dp(12);
    }

    /** Where the info block and page dots are headed: a slide change cancels their animators, so it puts them back on course. */
    float heroInfoY, heroDotsY;

    void moveHeroInfo(float infoY, float dotsY, long ms) {
        heroInfoY = infoY;
        heroDotsY = dotsY;
        heroInfo.animate().withLayer().translationY(infoY).setDuration(ms).setInterpolator(EASE).start();
        heroDots.animate().withLayer().translationY(dotsY).setDuration(ms).setInterpolator(EASE).start();
    }

    /** Down from the status row: the carousel, or the dock when there is no carousel. */
    void focusBelowStatus() {
        if (shelfView.isFocusable() && !folderMode) {
            heroRequested = true;
            shelfView.requestFocus();
        } else {
            focusIndex(Math.min(focusedIndex, COLS - 1));
        }
    }

    /** Up from the shelf/dock: the status row. */
    void focusTop() {
        ccButton.requestFocus();
    }

    /** Loads cached suggestions at once and refreshes them in the background when stale or reconfigured. */
    void loadHero() {
        boolean shelf = Suggestions.enabled(this) && Suggestions.ready(this);
        if (!shelf) {
            heroItems = new ArrayList<>();
            shelfView.setFocusable(false);
            setHeroShown(false);
            return;
        }
        int v = Suggestions.version(this);
        boolean reconfigured = v != heroVersion;
        heroVersion = v;
        io.execute(() -> {
            Suggestions.byService(this);  // read here, so settling on a dock app never waits on the disk
            List<Suggestions.Item> base = Suggestions.cached(this, true);
            if (base != null && (reconfigured || heroItems.isEmpty())) {
                handler.post(() -> setHeroBase(base));
            }
            // Nothing saved yet: fetched below. What's showing is never cleared for a list that
            // couldn't be read this time, so the carousel can't go blank.
            if (Suggestions.cached(this, false) == null) {
                try {
                    List<Suggestions.Item> fresh = Suggestions.fetch(this);
                    handler.post(() -> setHeroBase(fresh));
                } catch (Exception e) {
                    android.util.Log.w("TvHome", "suggestions: " + e);
                }
            }
        });
    }

    /** A new full carousel: kept as the mix, shown unless an app's own shelf is up. */
    void setHeroBase(List<Suggestions.Item> items) {
        heroAll = items;
        if (shelfPkg == null) setHeroItems(items);
    }

    /**
     * Apple TV's per-app top shelf: settling on a dock app shows that service's own popular
     * titles; anything else (or an app with no titles, like YouTube) shows the full mix.
     */
    void shelfFor(AppEntry a, boolean dock) {
        String pkg = dock && a != null ? a.pkg : null;
        List<Suggestions.Item> own = pkg == null ? null : Suggestions.forService(this, pkg);
        if (own == null || own.size() < 2) pkg = null;
        if (java.util.Objects.equals(pkg, shelfPkg)) return;
        shelfPkg = pkg;
        List<Suggestions.Item> next = pkg == null ? heroAll : own;
        if (next.isEmpty()) return;
        heroDissolve = true;
        setHeroItems(next);
        heroDissolve = false;
    }

    /** setHeroItems is switching between shelves: dissolve, no slide. */
    boolean heroDissolve;

    void setHeroItems(List<Suggestions.Item> items) {
        // Keep the carousel where it was when the list is refreshed (same title -> same position).
        String curTitle = heroItems.isEmpty() ? null : heroItems.get(Math.min(heroIndex, heroItems.size() - 1)).title;
        boolean same = curTitle != null && heroFront.getDrawable() != null;
        if (items.size() > 24) items = new ArrayList<>(items.subList(0, 24));
        heroItems = items;
        int keep = 0;
        for (int i = 0; curTitle != null && i < items.size(); i++) if (items.get(i).title.equals(curTitle)) keep = i;
        heroIndex = keep;
        shelfView.setFocusable(!items.isEmpty());
        int dots = items.size() > 1 ? items.size() : 0;
        if (dots != dotRow.getChildCount()) {
            // A different number of pages (another app's shelf): the old row fades out and the new
            // one fades in, instead of the dots jumping as the row re-centres.
            dotRow.animate().cancel();
            if (dotRow.getChildCount() == 0) buildDots(dots, keep);
            else dotRow.animate().alpha(0f).setDuration(140).setInterpolator(EASE).withEndAction(() -> {
                buildDots(dots, heroIndex);
                dotRow.animate().alpha(1f).setDuration(260).setInterpolator(EASE).start();
            }).start();
        }
        if (items.isEmpty()) {
            setHeroShown(false);
            return;
        }
        // The carousel shows unless focus is down in the app grid: a list refreshed while the
        // status row (gear, weather) or nothing had focus used to hide it behind the drawer's glass.
        View f = getCurrentFocus();
        int i = tiles.indexOf(f);
        boolean inGrid = i >= 0 && !(hasDock() && i < COLS);
        setHeroShown(f == null ? heroShown || tiles.isEmpty() || focusedIndex < COLS : !inGrid);
        if (!(same && items.get(keep).title.equals(curTitle))) showHero(keep, heroDissolve && heroShown ? 2 : 0);
        else selectDot(keep, false);
        // Fetching the rest of the shelf's artwork ahead happens in its own lane, so the picture
        // you're about to see never waits behind it; a newer shelf stops an older one's fetching.
        List<Suggestions.Item> pre = items;
        int gen = ++preloadGen;
        preload.execute(() -> {
            for (Suggestions.Item it : pre) {
                if (gen != preloadGen) return;
                // Onto the disk only: decoding them here (even small) cost a second and a half of
                // processor for a full shelf, which the TV took from the animation running meanwhile.
                try {
                    if (it.backdrop != null && !it.backdrop.isEmpty()) Suggestions.download(this, it.backdrop, "w1280");
                } catch (Exception ignored) {
                    // fetched when it's shown instead
                }
                Suggestions.logoFor(this, it);
            }
        });
    }

    /** Carousel vs launcher background: crossfade between them. */
    void setHeroShown(boolean show) {
        show = show && !heroItems.isEmpty() && !folderMode;
        if (show == heroShown) return;
        heroShown = show;
        boolean glassy = !show && heroDrawer != null;
        glassTo((show || glassy) && heroBlur != null ? heroBlur : wallBlur, show ? 480 : 420);
        float a = show ? 1f : 0f;
        drawerBack.animate().cancel();
        if (glassy) {
            // The apps open over the carousel's picture as blurred glass that fades in over it. The
            // picture stays solid underneath until it's covered: fading it out at the same time let
            // the wallpaper show through mid-way, and the screen brightened.
            drawerBack.setImageBitmap(heroDrawer);
            drawerBack.animate().alpha(1f).setDuration(560).setInterpolator(HERO_EASE).withEndAction(() -> {
                if (heroShown || drawerBack.getAlpha() < 1f) return;
                // Fully covered: the pictures and the wallpaper needn't be drawn underneath.
                for (ImageView v : heroLayers()) hideHeroLayer(v);
                wallView.setVisibility(View.INVISIBLE);
            }).start();
        } else if (show && drawerBack.getAlpha() > 0f) {
            // Back from the apps: the picture goes back solid under the glass, which clears off it.
            for (ImageView v : heroLayers()) if (v != heroFront) hideHeroLayer(v);
            heroFront.animate().cancel();
            heroFront.setTranslationX(0);
            heroFront.setAlpha(1f);
            wallView.setVisibility(View.INVISIBLE);
            drawerBack.animate().alpha(0f).setDuration(420).setInterpolator(HERO_EASE).start();
        } else {
            // No glass (no picture for it yet): the carousel and the wallpaper crossfade.
            wallView.setVisibility(View.VISIBLE);
            drawerBack.animate().alpha(0f).setDuration(420).setInterpolator(HERO_EASE).start();
            heroFront.animate().cancel();
            heroFront.animate().alpha(a).setDuration(show ? 480 : 420).setInterpolator(HERO_EASE).withEndAction(() -> {
                if (heroShown && heroFront.getAlpha() >= 1f) wallView.setVisibility(View.INVISIBLE);
            }).start();
            for (ImageView v : heroLayers()) {
                if (v != heroFront) v.animate().alpha(0f).setDuration(show ? 480 : 420).setInterpolator(HERO_EASE).start();
            }
        }

        // Leaving for the apps: the title and dots go at once, before the rows slide over them;
        // coming back: they return after the artwork has settled.
        flushHeroInfo();
        heroInfo.animate().cancel();
        heroDots.animate().cancel();
        heroInfo.animate().withLayer().alpha(a).translationY(heroInfoY).setStartDelay(show ? 160 : 0).setDuration(show ? 420 : 140)
                .setInterpolator(EASE).start();
        heroDots.animate().alpha(a).translationY(heroDotsY).setStartDelay(show ? 160 : 0).setDuration(show ? 420 : 140).setInterpolator(EASE).start();
        chevron.animate().alpha(1f - a).setDuration(show ? 140 : 420).setInterpolator(EASE).start();
        handler.removeCallbacks(heroAdvance);
        if (show) scheduleAdvance();
    }

    /** The glass's picture part-way between two (the same small size): its own, reused. */
    Bitmap glassMixed;
    int[] glassFrom, glassTarget, glassOut;
    ValueAnimator glassAnim;

    /**
     * Moves every piece of glass on Home (dock, weather, clock, gear) to a new backdrop over ms,
     * on the carousel's own easing: blended pixel by pixel in the glass's tiny picture (160x90,
     * a fraction of a millisecond a frame), so the glass takes on the new picture's colours as the
     * picture itself dissolves in, instead of switching the moment the dissolve begins.
     */
    void glassTo(Bitmap to, long ms) {
        glassTo(to, ms, HERO_EASE);
    }

    void glassTo(Bitmap to, long ms, android.animation.TimeInterpolator curve) {
        if (glassAnim != null) glassAnim.cancel();
        Bitmap from = glassBg;
        if (to == null || from == null || from == to || ms <= 0 || from.isRecycled()
                || from.getWidth() != to.getWidth() || from.getHeight() != to.getHeight()) {
            glassBg = to;
            invalidateGlass();
            return;
        }
        int w = to.getWidth(), h = to.getHeight(), n = w * h;
        if (glassOut == null || glassOut.length != n) {
            glassFrom = new int[n];
            glassTarget = new int[n];
            glassOut = new int[n];
        }
        from.getPixels(glassFrom, 0, w, 0, 0, w, h);  // where it is now, even part-way through a blend
        to.getPixels(glassTarget, 0, w, 0, 0, w, h);
        if (glassMixed == null || glassMixed.getWidth() != w || glassMixed.getHeight() != h) {
            glassMixed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        glassMixed.setPixels(glassFrom, 0, w, 0, 0, w, h);
        glassBg = glassMixed;
        glassAnim = ValueAnimator.ofFloat(0f, 1f);
        glassAnim.setDuration(ms);
        glassAnim.setInterpolator(curve);
        glassAnim.addUpdateListener(a -> {
            int k = Math.round((float) a.getAnimatedValue() * 256), j = 256 - k;
            for (int i = 0; i < n; i++) {
                int p = glassFrom[i], q = glassTarget[i];
                glassOut[i] = 0xFF000000 | (((p >> 16 & 0xFF) * j + (q >> 16 & 0xFF) * k) >> 8) << 16
                        | (((p >> 8 & 0xFF) * j + (q >> 8 & 0xFF) * k) >> 8) << 8 | ((p & 0xFF) * j + (q & 0xFF) * k) >> 8;
            }
            glassMixed.setPixels(glassOut, 0, w, 0, 0, w, h);
            invalidateGlass();
        });
        glassAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;  // a newer move carries on from where this one got to
                glassBg = to;
                invalidateGlass();
            }
        });
        glassAnim.start();
    }

    /**
     * tvOS's app-drawer backdrop: the picture as it's shown (shades and all) blurred right down,
     * colour kept and lifted, and darker: the blur spreads a bright middle into the shaded top,
     * which at 85% left the top of the screen lighter than the picture it covered.
     */
    static Bitmap drawerGlass(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        Bitmap soft = Ui.blur(src, 240, 135, 6, 3);
        if (soft == null) return null;
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.2f);
        cm.postConcat(new android.graphics.ColorMatrix(new float[]{
                0.75f, 0, 0, 0, 0, 0, 0.75f, 0, 0, 0, 0, 0, 0.75f, 0, 0, 0, 0, 0, 1, 0}));
        Bitmap out = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        new Canvas(out).drawBitmap(soft, 0, 0, p);
        soft.recycle();
        return out;
    }

    void scheduleAdvance() {
        handler.removeCallbacks(heroAdvance);
        int sec = Suggestions.advanceSeconds(this);
        if (sec > 0 && heroShown && resumed && switcher == null) handler.postDelayed(heroAdvance, sec * 1000L);
    }

    void heroStep(int dir, boolean user) {
        if (heroItems.size() < 2) return;
        showHero((heroIndex + dir + heroItems.size()) % heroItems.size(), dir);
        if (!user || !shelfView.hasFocus()) scheduleAdvance();
    }

    void buildDots(int n, int active) {
        dotRow.removeAllViews();
        for (int i = 0; i < n; i++) {
            Dot d = new Dot(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6.5f), dp(6.5f));
            lp.leftMargin = lp.rightMargin = dp(1.75f);
            dotRow.addView(d, lp);
        }
        selectDot(active, false);
    }

    /** The current page's dot brightens and grows a touch; the others dim, all on one easing. */
    void selectDot(int index, boolean animate) {
        for (int i = 0; i < dotRow.getChildCount(); i++) {
            ((Dot) dotRow.getChildAt(i)).set(i == index, animate);
        }
    }

    /**
     * A page dot drawn as a true anti-aliased circle at its exact size every frame (scaling a
     * tiny bitmap drew it as a rough diamond on this TV). The current one is bigger and brighter.
     */
    static final class Dot extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float f;  // 0 = other page, 1 = current
        ValueAnimator a;

        Dot(Context c) {
            super(c);
            p.setColor(Color.WHITE);
        }

        void set(boolean on, boolean animate) {
            float to = on ? 1f : 0f;
            if (a != null) a.cancel();
            if (!animate) {
                f = to;
                invalidate();
                return;
            }
            a = ValueAnimator.ofFloat(f, to);
            a.setDuration(420);
            a.setInterpolator(HERO_EASE);
            a.addUpdateListener(v -> {
                f = (float) v.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float max = Math.min(getWidth(), getHeight()) / 2f;
            p.setAlpha(Math.round((0.35f + 0.65f * f) * 255));
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, max * (0.77f + 0.23f * f), p);
        }
    }

    /** dir: +1 next, -1 previous, 0 first show (no slide). */
    void showHero(int index, int dir) {
        heroIndex = index;
        Suggestions.Item it = heroItems.get(index);
        selectDot(index, true);
        int want = index;
        io.execute(() -> {
            Bitmap raw = Suggestions.image(this, it.backdrop, "w1280", screenW);
            Bitmap bd = bakeScrims(raw);
            Bitmap drawer = drawerGlass(bd);
            Bitmap bdBlur = Ui.glassBackdrop(bd, 160, 90);
            // On the GPU now, in the background, so the dissolve doesn't stall on the upload.
            Bitmap shown = Suggestions.onGpu(bd);
            if (bdBlur != null) bdBlur.prepareToDraw();
            if (drawer != null) drawer.prepareToDraw();
            String logoPath = Suggestions.logoFor(this, it);
            Bitmap logo = Suggestions.onGpu(logoPath.isEmpty() ? null : Suggestions.image(this, logoPath, "w500", 500));
            handler.post(() -> {
                if (heroIndex != want || heroItems.isEmpty()) return;
                applyHeroInfo(it, logo, dir);
                if (shown != null) crossfadeHero(shown, dir);
                if (drawer != null) heroDrawer = drawer;
                if (bdBlur != null) {
                    heroBlur = bdBlur;
                    // The glass changes colour with the dissolve, not ahead of it.
                    if (heroShown) glassTo(heroBlur, dir == 0 ? 0 : DISSOLVE_MS, DISSOLVE);
                }
            });
        });
    }

    /** The next title, waiting for the old one to fade out; put in at once if that fade is cut short. */
    Runnable heroInfoNext;

    void flushHeroInfo() {
        Runnable r = heroInfoNext;
        heroInfoNext = null;
        if (r != null) r.run();
    }

    /**
     * Title/meta/overview for an item, with its artwork. Changing slides, the old title fades and
     * drifts off before the new one glides in from the side you pressed, like the artwork (swapped
     * in one frame, the title cut while the picture faded).
     */
    void applyHeroInfo(Suggestions.Item it, Bitmap logo, int dir) {
        if (!heroShown || dir == 0) {
            heroInfoNext = null;
            fillHeroInfo(it, logo);
            return;
        }
        int slide = dir == 2 ? 0 : dir;
        heroInfoNext = () -> fillHeroInfo(it, logo);
        heroInfo.animate().cancel();
        heroInfo.animate().withLayer().alpha(0f).translationX(-dp(10) * slide).translationY(heroInfoY)
                .setStartDelay(0).setDuration(heroInfo.getAlpha() > 0f ? 160 : 0).setInterpolator(EASE).withEndAction(() -> {
                    flushHeroInfo();
                    heroInfo.setTranslationX(dp(18) * slide);
                    heroInfo.animate().withLayer().alpha(1f).translationX(0).translationY(heroInfoY)
                            .setStartDelay(30).setDuration(520).setInterpolator(HERO_EASE).start();
                }).start();
    }

    void fillHeroInfo(Suggestions.Item it, Bitmap logo) {
        if (shelfView.isFocused()) {
            heroButton.setVisibility(View.VISIBLE);
            heroButton.setAlpha(1f);
        }
        Ui.textSize(heroTitle, 36);
        heroTitle.setMaxLines(2);
        heroOverview.setMaxLines(2);
        Ui.textSize(heroOverview, 12.5f);
        if (!shelfView.isFocused()) {  // tvOS: the overview shows only when the shelf is focused
            heroOverview.animate().cancel();
            heroOverview.setAlpha(0f);
            heroOverview.setVisibility(View.GONE);
        }
        heroTitle.setText(it.title);
        heroTitle.setVisibility(View.VISIBLE);
        heroLogo.setImageDrawable(null);
        heroLogo.setVisibility(View.GONE);
        List<String> meta = new ArrayList<>();
        if (!it.year.isEmpty()) meta.add(it.year);
        meta.add(it.type.equals("tv") ? "Series" : "Movie");
        if (!it.genres.isEmpty()) meta.add(it.genres);
        if (it.rating > 0) meta.add(String.format(java.util.Locale.US, "\u2605 %.1f", it.rating));
        heroMeta.setText(String.join("  \u00B7  ", meta));
        heroOverview.setText(it.overview);
        Suggestions.Provider p = it.provider();
        heroBtnText.setText(p != null && !installed(p.pkg) ? "Get" : "\u25B6  Open");
        // The service's own app icon as its badge.
        Drawable appIcon = null;
        String badge = p != null ? p.pkg : null;
        if (badge != null && installed(badge)) appIcon = badgeIcon(badge);
        heroProvider.setImageDrawable(appIcon);
        heroProvider.setVisibility(appIcon == null ? View.GONE : View.VISIBLE);
        heroBtnIcon.setVisibility(View.GONE);
        if (logo != null) {
            heroLogo.setImageBitmap(logo);
            heroLogo.setVisibility(View.VISIBLE);
            heroTitle.setVisibility(View.GONE);
        }
    }

    /** Apps' icons and whether they're installed, asked on every slide: kept until an app changes. */
    final Map<String, Drawable> badges = new java.util.HashMap<>();
    final Map<String, Boolean> installedApps = new java.util.HashMap<>();

    boolean installed(String pkg) {
        Boolean b = installedApps.get(pkg);
        if (b == null) installedApps.put(pkg, b = Suggestions.installed(this, pkg));
        return b;
    }

    Drawable badgeIcon(String pkg) {
        if (badges.containsKey(pkg)) return badges.get(pkg);
        Drawable d = null;
        try {
            d = getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
            // no badge
        }
        badges.put(pkg, d);
        return d;
    }

    /**
     * Artwork transition (tvOS carousel): the new image fades up over the old one while both pan
     * a little together towards the side you pressed, as one movement.
     */
    void crossfadeHero(Bitmap bd, int dir) {
        ImageView next = heroUnder;
        if (next.getAlpha() > 0f && heroBack.getAlpha() < 1f) {
            // A third press mid-fade: the oldest picture is needed for the new one, so the one
            // above it takes its place (a picture for a picture, never a dip to dark).
            heroBack.animate().cancel();
            heroBack.setAlpha(1f);
        }
        heroUnder = heroBack;
        heroBack = heroFront;
        heroFront = next;
        ImageView old = heroBack;
        next.animate().cancel();
        next.setImageBitmap(bd);
        if (dir == 0 || !heroShown) {
            next.setTranslationX(0);
            next.setAlpha(heroShown ? 1f : 0f);
            // Behind the apps' glass while it's still fading in, the pictures stay as they are
            // (the glass hides them once it covers them).
            if (heroShown || drawerBack.getAlpha() >= 1f) {
                hideHeroLayer(old);
                hideHeroLayer(heroUnder);
            }
            return;
        }
        // The new artwork dissolves in on top while what was on screen stays as it is underneath:
        // a picture still fading in stops where it is (no jump to one picture, no dip to dark
        // mid-way, one blend per frame). Both pan the same distance on the same curve: the old
        // one panning by half as much as the new one made the picture seem to jolt back as the
        // new one took over.
        // No GPU layers: a picture alone fades as cheaply without one, and building one made the
        // TV render (and upload) the whole new picture again in the frame the dissolve began.
        ViewGroup layer = (ViewGroup) next.getParent();
        if (layer != null && layer == old.getParent() && layer.indexOfChild(next) < layer.indexOfChild(old)) {
            // Draw the incoming image just above the outgoing one (never above the titles or dock).
            layer.removeView(next);
            layer.addView(next, layer.indexOfChild(old) + 1);
        }
        int slide = dir == 2 ? 0 : dir;  // 2: a shelf change dissolves in place
        float max = screenW * HERO_PAN, pan = max * slide;
        next.setTranslationX(pan);
        next.setAlpha(0f);
        next.animate().translationX(0).alpha(1f).setStartDelay(0).setDuration(DISSOLVE_MS).setInterpolator(DISSOLVE)
                .withEndAction(() -> {
                    // Fully over: what's underneath needn't be drawn.
                    for (ImageView v : heroLayers()) if (v != next) hideHeroLayer(v);
                    if (heroShown) wallView.setVisibility(View.INVISIBLE);
                }).start();
        old.animate().cancel();
        old.animate().translationX(Math.max(-max, Math.min(max, old.getTranslationX() - pan))).setStartDelay(0)
                .setDuration(DISSOLVE_MS).setInterpolator(DISSOLVE).start();
    }

    void hideHeroLayer(ImageView v) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationX(0);
    }

    /** Carousel focused: the dock slides away and the artwork takes the whole screen (tvOS Top Shelf). */
    boolean heroRequested;

    ImageView ccButton, netIcon;
    android.net.ConnectivityManager.NetworkCallback netCallback;
    WeatherView weatherCard;
    LiquidGlass weatherGlass;
    FrameLayout pickerLayer;

    void heroFocused(boolean has) {
        if (has && !heroRequested) {
            // Android parked focus here on its own (startup/rebuild): hand it back to the dock.
            handler.post(() -> focusIndex(Math.min(focusedIndex, Math.max(0, tiles.size() - 1))));
            return;
        }
        if (!has) heroRequested = false;
        heroButtonLit(has);
        if (has) {
            setHeroShown(true);
            heroButton.animate().scaleX(1.08f).scaleY(1.08f).setDuration(420).setInterpolator(LIFT).start();
            heroButton.setElevation(dp(10));
            scroll.smoothScrollTo(0, 0);
            // Slid as it is, not as a GPU layer: the dock card settling inside it would have the
            // whole grid re-rendered into the layer every frame (the dock's glass has its own).
            scroll.animate().translationY(screenH - shelfH + dp(30)).setDuration(500).setInterpolator(EASE)
                    .setUpdateListener(null).withEndAction(this::invalidateGlass).start();
            moveHeroInfo(0, 0, 500);
            heroOverview.setVisibility(View.VISIBLE);
            heroButton.setVisibility(View.VISIBLE);
            heroOverview.animate().alpha(0.85f).setDuration(400).setStartDelay(120).setInterpolator(EASE).start();
            heroButton.animate().alpha(1f).setDuration(400).setStartDelay(120).setInterpolator(EASE).start();
            handler.removeCallbacks(heroAdvance);
        } else {
            heroButton.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(EASE).start();
            heroButton.setElevation(0);
            scroll.animate().translationY(0).setDuration(450).setInterpolator(EASE)
                    .setUpdateListener(null).withEndAction(this::invalidateGlass).start();
            moveHeroInfo(heroInfoRestY(), heroDotsRestY(), 450);
            heroOverview.animate().alpha(0f).setDuration(160).setStartDelay(0).setInterpolator(EASE)
                    .withEndAction(() -> heroOverview.setVisibility(View.GONE)).start();
            heroButton.animate().alpha(0f).setDuration(160).setStartDelay(0).setInterpolator(EASE)
                    .withEndAction(() -> heroButton.setVisibility(View.GONE)).start();
            scheduleAdvance();
        }
    }

    ValueAnimator heroBtnAnim;
    float heroBtnT;

    /** The Open capsule turns white with dark text as the carousel takes focus: a fade, never a flip. */
    void heroButtonLit(boolean on) {
        if (heroBtnAnim != null) heroBtnAnim.cancel();
        android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        heroBtnAnim = ValueAnimator.ofFloat(heroBtnT, on ? 1f : 0f);
        heroBtnAnim.setDuration(on ? 200 : 260);
        heroBtnAnim.setInterpolator(EASE);
        heroBtnAnim.addUpdateListener(a -> {
            heroBtnT = (float) a.getAnimatedValue();
            heroBtnBg.setColor((int) ev.evaluate(heroBtnT, 0x47FFFFFF, 0xFFFFFFFF));
            heroBtnText.setTextColor((int) ev.evaluate(heroBtnT, Color.WHITE, 0xFF111114));
        });
        heroBtnAnim.start();
    }

    /** Carousel and capsule navigation: LEFT/RIGHT browse, OK opens, UP/DOWN move between capsule, carousel and dock. */
    boolean heroKeys(KeyEvent ev) {
        if (moveIndex >= 0) return false;
        int k = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        View f = getCurrentFocus();
        boolean heroActive = shelfView.isFocusable() && !folderMode;
        if (f == shelfView) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (down) heroStep(-1, true); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) heroStep(1, true); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (!down && !heroItems.isEmpty()) openHeroItem(heroItems.get(heroIndex));
                    return true;
                case KeyEvent.KEYCODE_MENU: return true;  // no options for the carousel
                case KeyEvent.KEYCODE_DPAD_UP: if (down) focusTop(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK:
                    if (down) focusIndex(Math.min(focusedIndex, COLS - 1));
                    return true;
                default: return false;
            }
        }
        if (f == ccButton) {
            // Status row: the Control Centre button alone; Left goes to the weather.
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (down && weatherShown()) weatherCard.requestFocus();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_BACK) {
                if (down) focusBelowStatus();
                return true;
            }
            return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        if (f == weatherCard) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) focusTop(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (heroActive) {
                            heroRequested = true;
                            shelfView.requestFocus();
                        } else {
                            focusIndex(Math.min(focusedIndex, COLS - 1));
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;
                default: return false;
            }
        }
        int i = tiles.indexOf(f);
        if (i >= 0 && ((k == KeyEvent.KEYCODE_DPAD_RIGHT && (i % COLS == COLS - 1 || i == tiles.size() - 1))
                || (k == KeyEvent.KEYCODE_DPAD_LEFT && i % COLS == 0))) {
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_UP && i >= 0 && i < COLS && (hasDock() || i < COLS)) {
            if (down) {
                if (heroActive) {
                    heroRequested = true;
                    shelfView.requestFocus();
                } else {
                    focusTop();
                }
            }
            return true;
        }
        return false;
    }

    void openHeroItem(Suggestions.Item it) {
        Suggestions.open(this, it);
    }

    // ---------------------------------------------------------------- launch animation

    /** tvOS-style launch: the app opens out of its tile. */
    void launchAnimated(View tile, AppEntry a) {
        if (INPUTS_KEY.equals(a.key)) {
            openInputs();
            return;
        }

        // As on Apple TV, the app itself grows out of its tile: the system compositor scales the
        // app's real window up from the tile's rectangle on screen, so there is no stand-in
        // picture to blur, nothing for Home to draw, and no wait before the app starts.
        int w = Math.round(tile.getWidth() * tile.getScaleX());
        int h = Math.round(tile.getHeight() * tile.getScaleY());
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(tile, 0, 0, w, h).toBundle();
            startActivity(a.launch, opts);
            appOpened = true;
            openedKey = a.key;
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("Can't open " + a.label);
        }
    }

    static int colorDistance(int x, int y) {
        return Math.abs(Color.red(x) - Color.red(y)) + Math.abs(Color.green(x) - Color.green(y))
                + Math.abs(Color.blue(x) - Color.blue(y));
    }

    float clockAlpha() {
        return Math.max(0f, 1f - scroll.getScrollY() / (shelfH * 0.4f));
    }

    // ---------------------------------------------------------------- focus & shelf

    void onTileFocused(int index) {
        focusedIndex = index;
        boolean dock = hasDock() && index < COLS;
        setHeroShown(dock && !heroItems.isEmpty());
        scrollToRow(index / COLS);
        handler.removeCallbacks(shelfUpdate);
        handler.postDelayed(shelfUpdate, 260);
    }

    /**
     * Row tops come from the fixed grid geometry rather than getTop(), so this also works right
     * after buildGrid() (e.g. while moving a card), before the new rows have been laid out.
     */
    void scrollToRow(int row) {
        int rowH = tileH + labelH + gap / 2;
        int rowTop = !hasDock() ? shelfH + row * rowH
                : row == 0 ? shelfH
                : shelfH + tileH + 2 * dockPadV + dockGapBelow() + (row - 1) * rowH;
        int target = row == 0 ? 0 : rowTop - (int) (screenH * 0.30f);
        scroll.smoothScrollTo(0, Math.max(0, target));
    }

    void focusIndex(int i) {
        if (tiles.isEmpty()) return;
        i = Math.max(0, Math.min(i, tiles.size() - 1));
        tiles.get(i).requestFocus();
        focusedIndex = i;
        int row = i / COLS;
        scroll.post(() -> scrollToRow(row));
    }

    void goTop() {
        scroll.smoothScrollTo(0, 0);
        focusIndex(0);
    }

    String currentKey() {
        return focusedIndex >= 0 && focusedIndex < shown.size() ? shown.get(focusedIndex).key : null;
    }

    int indexOfKey(String key) {
        for (int j = 0; j < shown.size(); j++) if (shown.get(j).key.equals(key)) return j;
        return -1;
    }

    // ---------------------------------------------------------------- folder of hidden apps

    AppEntry folderEntry() {
        AppEntry f = new AppEntry();
        f.key = FOLDER_KEY;
        f.label = "More Apps";
        f.color = 0xFF3A3A3C;
        f.banner = new BitmapDrawable(getResources(), folderArt());
        f.icon = f.banner;
        return f;
    }

    /** Folder card: dark glass gradient, 3x3 app-grid glyph, title and hidden-app count. */
    Bitmap folderArt() {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        // tvOS system-icon style: a vivid vertical gradient, white glyph, no words.
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF6E7BF2, 0xFF3A3FC8, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        int cell = 46, gapPx = 18, grid = cell * 3 + gapPx * 2;
        float gx = (w - grid) / 2f, gy = (h - grid) / 2f;
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(0x33000000);
        sh.setMaskFilter(new android.graphics.BlurMaskFilter(10, android.graphics.BlurMaskFilter.Blur.NORMAL));
        p.setColor(Color.WHITE);
        for (int i = 0; i < 9; i++) {
            float x = gx + (i % 3) * (cell + gapPx), y = gy + (i / 3) * (cell + gapPx);
            c.drawRoundRect(x, y + 5, x + cell, y + cell + 5, 13, 13, sh);
            c.drawRoundRect(x, y, x + cell, y + cell, 13, 13, p);
        }
        return b;
    }

    void enterFolder() {
        if (hiddenApps.isEmpty()) return;
        animateGridSwap(() -> {
            folderMode = true;
            setHeroShown(false);
            weatherCard.setAlpha(0f);
            buildGrid();
            scroll.scrollTo(0, 0);
            focusIndex(0);
        });
    }

    void exitFolder() {
        animateGridSwap(() -> {
            folderMode = false;
            weatherCard.animate().alpha(weatherAlpha()).setDuration(250).setInterpolator(EASE).start();
            buildGrid();
            int i = indexOfKey(FOLDER_KEY);
            focusIndex(i >= 0 ? i : 0);
        });
    }

    /** Quick zoom-through between home and folder, so it reads as "opening" the folder. */
    void animateGridSwap(Runnable swap) {
        scroll.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(140).setInterpolator(EASE)
                .withEndAction(() -> {
                    swap.run();
                    scroll.setScaleX(1.04f);
                    scroll.setScaleY(1.04f);
                    scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(EASE)
                            .withEndAction(null).start();
                }).start();
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        // Idle full-screen carousel: the first press only brings the Home Screen back.
        if (idle) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                exitIdle();
                swallowUp = true;
            }
            return true;
        }
        if (swallowUp && ev.getAction() == KeyEvent.ACTION_UP) {
            swallowUp = false;
            return true;
        }
        resetIdle();
        navSound(this, ev);
        if (switcher != null) return switcher.key(ev) || super.dispatchKeyEvent(ev);
        if (pickerLayer != null && menuLayer == null) {
            int pk = ev.getKeyCode();
            boolean pd = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (pk == KeyEvent.KEYCODE_BACK) {
                if (!pd) closePopover();
                return true;
            }
            if (pk == KeyEvent.KEYCODE_DPAD_UP || pk == KeyEvent.KEYCODE_DPAD_DOWN
                    || pk == KeyEvent.KEYCODE_DPAD_LEFT || pk == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (pd) pickerMove(pk);
                return true;
            }
            return super.dispatchKeyEvent(ev);
        }
        if (menuLayer != null) {
            int mk = ev.getKeyCode();
            if (mk == KeyEvent.KEYCODE_BACK || mk == KeyEvent.KEYCODE_MENU || mk == KeyEvent.KEYCODE_ESCAPE) {
                if (ev.getAction() == KeyEvent.ACTION_UP) closeMenu();
                return true;
            }
            return super.dispatchKeyEvent(ev);
        }
        if (heroKeys(ev)) return true;
        int k = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;

        if (moveIndex >= 0) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:   if (down) moveTo(moveIndex - 1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:  if (down) moveTo(moveIndex + 1); return true;
                case KeyEvent.KEYCODE_DPAD_UP:     if (down) moveTo(moveIndex - COLS); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:   if (down) moveTo(moveIndex + COLS); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:      if (down) endMove(); return true;
                default: return super.dispatchKeyEvent(ev);
            }
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                int i = tiles.indexOf(getCurrentFocus());
                if (i >= 0 && !FOLDER_KEY.equals(shown.get(i).key)) showOptions(i);
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            // Only a press that started here: the release of a Back that closed an app or the
            // screen saver isn't one.
            if (down && ev.getRepeatCount() == 0) backDownAt = ev.getDownTime();
            if (!down && ev.getDownTime() == backDownAt) {
                if (folderMode) exitFolder();
                // On the first app in the dock, as on Apple TV: the screen saver, straight away.
                else if (tiles.indexOf(getCurrentFocus()) == 0) Suggestions.start(this, ControlCentreActivity.screenSaver(this));
                else goTop();
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /** When the Back being pressed went down. */
    long backDownAt;

    // ---------------------------------------------------------------- apps

    void loadApps() {
        all.clear();
        apps.clear();
        hiddenApps.clear();
        PackageManager pm = getPackageManager();
        Set<String> pkgs = new HashSet<>();
        addResolved(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, pkgs, true);
        addResolved(pm, Intent.CATEGORY_LAUNCHER, pkgs, false);
        if (!pkgs.contains(TV_SETTINGS)) addSettingsEntry(pm);
        inputs = Inputs.list(this);
        if (Inputs.showCard(this) && !inputs.isEmpty()) addInputsEntry();
        for (AppEntry e : all.values()) {
            boolean zoom = e.banner != null && ZOOM_BANNER_PKGS.contains(e.pkg);
            boolean card = !zoom && (e.banner == null || TVOS_CARD_PKGS.contains(e.pkg));
            if (zoom || card) {
                // Built from the app's highest-resolution art, which is only held while drawing.
                Drawable icon = e.icon, banner = e.banner;
                e.icon = hiRes(pm, e.pkg, e.iconRes, icon);
                if (banner != null) e.banner = hiRes(pm, e.pkg, e.bannerRes, banner);
                Bitmap art = zoom ? zoomedBanner(e) : tvosCard(e);
                e.icon = icon;
                e.banner = new BitmapDrawable(getResources(), art);
            }
            // The tile itself from the biggest banner the app ships (iPlayer and ITVX have 640 px,
            // Netflix 480), held only while it's drawn: more pixels in, cleaner when scaled down.
            boolean own = !zoom && !card && e.bannerRes != 0;
            Drawable big = own ? hiRes(pm, e.pkg, e.bannerRes, e.banner) : e.banner;
            e.tileArt = tileArt(big != e.banner && sameArt(big, e.banner) ? big : e.banner);
        }

        Set<String> hidden = prefs.getStringSet(PREF_HIDDEN, Collections.emptySet());
        Map<String, AppEntry> pool = new LinkedHashMap<>();
        for (AppEntry e : all.values()) {
            if (hidden.contains(e.key)) hiddenApps.add(e);
            else pool.put(e.key, e);
        }
        Collections.sort(hiddenApps, (x, y) -> x.label.compareToIgnoreCase(y.label));

        for (String key : prefs.getString(PREF_ORDER, "").split(",")) {
            AppEntry e = pool.remove(key);
            if (e != null) apps.add(e);
        }
        List<AppEntry> rest = new ArrayList<>(pool.values());
        Collections.sort(rest, (x, y) -> {
            int px = priority(x), py = priority(y);
            return px != py ? Integer.compare(px, py) : x.label.compareToIgnoreCase(y.label);
        });
        apps.addAll(rest);
    }

    /** Fallback Settings tile for TVs whose settings app has no launcher entry. */
    void addSettingsEntry(PackageManager pm) {
        AppEntry s = new AppEntry();
        s.key = SETTINGS_KEY;
        s.pkg = TV_SETTINGS;
        s.label = "Settings";
        s.system = true;
        s.launch = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            s.banner = pm.getApplicationBanner(s.pkg);
            s.icon = pm.getApplicationIcon(s.pkg);
        } catch (PackageManager.NameNotFoundException e) {
            s.icon = getDrawable(android.R.drawable.ic_menu_preferences);
        }
        s.color = 0xFF3A3A3C;
        all.put(s.key, s);
    }

    int priority(AppEntry e) {
        // Inputs is the first card after the dock row.
        if (INPUTS_KEY.equals(e.key)) return COLS;
        if (isSettings(e)) return 2000;
        int i = DEFAULT_ORDER.indexOf(e.pkg);
        return i < 0 ? 1000 : i < COLS ? i : i + 1;
    }

    boolean isSettings(AppEntry e) {
        return SETTINGS_KEY.equals(e.key) || TV_SETTINGS.equals(e.pkg);
    }

    List<Inputs.Input> inputs = new ArrayList<>();

    /** Inputs card: a graphite tvOS-style card with the input glyph; OK opens the input picker. */
    void addInputsEntry() {
        AppEntry e = new AppEntry();
        e.key = INPUTS_KEY;
        e.pkg = getPackageName();
        e.label = "Inputs";
        e.system = true;
        e.color = 0xFF48484A;
        int w = 640, h = 384;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF5A6478, 0xFF1C212C, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        Bitmap g = Glyphs.get("hdmi", (int) (h * 0.5f), Color.WHITE);
        c.drawBitmap(g, (w - g.getWidth()) / 2f, (h - g.getHeight()) / 2f, null);
        e.banner = new BitmapDrawable(getResources(), b);
        e.icon = e.banner;
        all.put(e.key, e);
    }


    void addResolved(PackageManager pm, String category, Set<String> pkgs, boolean leanback) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(getPackageName()) || pkgs.contains(ai.packageName) || NOT_APPS.contains(ai.packageName)) continue;
            AppEntry e = new AppEntry();
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            e.pkg = ai.packageName;
            e.key = cn.flattenToShortString();
            e.label = ri.loadLabel(pm).toString();
            e.banner = leanback ? ai.loadBanner(pm) : null;
            e.icon = ri.loadIcon(pm);
            e.iconRes = ri.getIconResource();
            e.bannerRes = leanback ? ai.getBannerResource() : 0;
            e.color = tintFrom(e.icon);
            e.system = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            e.launch = new Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            all.put(e.key, e);
            pkgs.add(e.pkg);
        }
    }

    /** Average colour of the icon, darkened so white logos stay readable on top of it. */
    int tintFrom(Drawable d) {
        if (d == null) return 0xFF2C2C2E;
        Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, 16, 16);
        d.draw(new Canvas(b));
        d.setBounds(old);
        long r = 0, g = 0, bl = 0, n = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
        }
        b.recycle();
        if (n == 0) return 0xFF2C2C2E;
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
        hsv[1] = Math.min(hsv[1], 0.8f);
        hsv[2] = Math.min(hsv[2], 0.5f);
        return Color.HSVToColor(hsv);
    }

    void reload(String focusKey) {
        loadApps();
        if (folderMode && hiddenApps.isEmpty()) folderMode = false;
        buildGrid();
        int i = focusKey == null ? -1 : indexOfKey(focusKey);
        focusIndex(i >= 0 ? i : Math.min(focusedIndex, shown.size() - 1));
    }

    void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : apps) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.key);
        }
        prefs.edit().putString(PREF_ORDER, sb.toString()).apply();
    }

    void setHidden(AppEntry a, boolean hide) {
        Set<String> hidden = new HashSet<>(prefs.getStringSet(PREF_HIDDEN, Collections.emptySet()));
        if (hide) hidden.add(a.key);
        else hidden.remove(a.key);
        prefs.edit().putStringSet(PREF_HIDDEN, hidden).apply();
        int i = focusedIndex;
        loadApps();
        if (folderMode && hiddenApps.isEmpty()) folderMode = false;
        buildGrid();
        focusIndex(Math.min(i, shown.size() - 1));
        toast(hide ? a.label + " moved to More Apps" : a.label + " is back on Home");
    }

    void registerPackageReceiver() {
        pkgReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                badges.clear();
                installedApps.clear();
                handler.removeCallbacks(reloadRun);
                handler.postDelayed(reloadRun, 600);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(pkgReceiver, f);
        }
    }

    // ---------------------------------------------------------------- options

    void showOptions(int index) {
        AppEntry a = shown.get(index);
        View tile = tiles.get(index);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Open");
        actions.add(() -> launchAnimated(tile, a));
        if (folderMode) {
            labels.add("Show on Home Screen");
            actions.add(() -> setHidden(a, false));
        } else {
            labels.add("Move");
            actions.add(() -> startMove(index));
            if (!isSettings(a)) {
                labels.add("Hide");
                actions.add(() -> setHidden(a, true));
            }
        }
        if (!isSettings(a) && !INPUTS_KEY.equals(a.key)) {
            labels.add("App Info");
            actions.add(() -> startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.pkg))));
            if (!a.system) {
                labels.add("Uninstall");
                actions.add(() -> startSafe(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))));
            }
        }
        if (!folderMode) {
            labels.add("Reset Layout");
            actions.add(() -> {
                prefs.edit().remove(PREF_ORDER).apply();
                reload(a.key);
            });
        }
        showMenu(tile, a.label, labels, actions, labels.indexOf("Uninstall"));
    }

    // ---------------------------------------------------------------- Control Center

    /** Control Centre: one screen for Home and for the remote's button inside apps. */
    void openControlCentre() {
        startActivity(new Intent(this, ControlCentreActivity.class));
    }

    /** The Inputs tile: Control Centre straight on its Inputs page (Back closes it). */
    void openInputs() {
        startActivity(new Intent(this, ControlCentreActivity.class).putExtra("inputs", true));
    }

    // ---------------------------------------------------------------- internet status

    /** Follows the default network; Android marks it "validated" once the internet is reachable. */
    void watchNetwork() {
        updateNet();
        if (netCallback != null) return;
        netCallback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network n) { handler.post(MainActivity.this::updateNet); }
            @Override public void onLost(android.net.Network n) { handler.post(MainActivity.this::updateNet); }
            @Override public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities nc) {
                handler.post(MainActivity.this::updateNet);
            }
        };
        try {
            getSystemService(android.net.ConnectivityManager.class).registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
    }

    int netState = -1, netBars = -1;
    boolean netEthernet;

    void updateNet() {
        if (netIcon == null) return;
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        int state = nc == null ? 0
                : nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 2 : 1;
        boolean eth = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);
        int bars = 3;
        if (nc != null && !eth) {
            try {
                android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
                int level = android.net.wifi.WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 4);
                bars = level <= 1 ? 1 : level == 2 ? 2 : 3;
            } catch (Exception ignored) {
                // keep full bars
            }
        }
        boolean back = netState == 0 || netState == 1;
        if (state == netState && eth == netEthernet && bars == netBars) return;
        netState = state;
        netEthernet = eth;
        netBars = bars;
        netIcon.setImageBitmap(netGlyph(state, eth, bars, dp(15)));
        netIcon.setContentDescription(state == 2 ? "Online" : state == 1 ? "No internet" : "Offline");
        // Back online: refresh what needs the internet.
        if (state == 2 && back) {
            Weather.refresh(this, true, this::updateWeather);
            loadHero();
        }
    }

    /**
     * Apple-style network symbol, white only: Wi-Fi as a dot-wedge and two bands lit by signal
     * strength (unlit at 30%), or Ethernet; "!" beside it without internet (wifi.exclamationmark)
     * and a slash through dimmed bands with no network (wifi.slash).
     */
    Bitmap netGlyph(int state, boolean eth, int bars, int s) {
        // Wi-Fi alone is a fan 1.2x as wide as it's tall; "no internet" adds a "!" to its right.
        int w = Math.round(s * (state == 1 ? 1.55f : 1.2f));
        Bitmap b = Bitmap.createBitmap(w, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int on = Color.WHITE, off = 0x4DFFFFFF;
        if (eth && state != 0) {
            c.save();
            c.translate(s * 0.1f, 0);  // centred where the Wi-Fi fan would be
            drawEthernet(c, s, state == 2 ? on : off);
            c.restore();
        } else {
            drawWifi(c, s, state == 2 ? bars : 0, on, off);
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(on);
        p.setStrokeCap(Paint.Cap.ROUND);
        if (state == 1) {  // exclamation mark to the right (wifi.exclamationmark)
            p.setStrokeWidth(s * 0.14f);
            float x = s * 1.4f;
            c.drawLine(x, s * 0.16f, x, s * 0.6f, p);
            c.drawCircle(x, s * 0.84f, s * 0.08f, p);
        } else if (state == 0) {  // slash with a clear gap around it (wifi.slash)
            Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
            cut.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
            cut.setStrokeCap(Paint.Cap.ROUND);
            cut.setStrokeWidth(s * 0.24f);
            c.drawLine(s * 0.18f, s * 0.08f, s * 1.0f, s * 0.95f, cut);
            p.setStrokeWidth(s * 0.1f);
            c.drawLine(s * 0.18f, s * 0.08f, s * 1.0f, s * 0.95f, p);
        }
        return b;
    }

    /**
     * Apple's Wi-Fi fan: a quarter circle split into a wedge and two bands with even gaps, the band
     * ends cut straight along the fan's edges and every corner slightly rounded. Drawn four times
     * over size and scaled down smoothly, as type is, so the small curves come out even; bands
     * beyond the signal strength are dimmed.
     */
    void drawWifi(Canvas c, float s, int lit, int on, int off) {
        int k = 4, bw = Math.round(c.getWidth() * k), bh = Math.round(s * k);
        Bitmap big = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(big);
        float S = s * k, cx = (c.getWidth() > s * 1.3f ? s * 0.6f : c.getWidth() / 2f) * k, cy = S * 0.93f;
        float rw = S * 0.32f, gap = S * 0.09f, t = S * 0.17f, d = S * 0.035f;
        // The fan's sides moved in by d: the same quarter, its point a little higher.
        float lift = (float) (d / Math.sin(Math.toRadians(45)));
        android.graphics.Path fan = new android.graphics.Path();
        fan.moveTo(cx, cy - lift);
        fan.lineTo(cx - S * 2, cy - lift - S * 2);
        fan.lineTo(cx + S * 2, cy - lift - S * 2);
        fan.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeWidth(2 * d);   // the stroke puts back the d taken off every edge, rounding the corners
        p.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < 3; i++) {
            float inner = i == 0 ? 0 : rw + gap + (i - 1) * (t + gap), outer = i == 0 ? rw : inner + t;
            android.graphics.Path ring = new android.graphics.Path();
            ring.addCircle(cx, cy, outer - d, android.graphics.Path.Direction.CW);
            if (inner > 0) {
                android.graphics.Path hole = new android.graphics.Path();
                hole.addCircle(cx, cy, inner + d, android.graphics.Path.Direction.CW);
                ring.op(hole, android.graphics.Path.Op.DIFFERENCE);
            }
            ring.op(fan, android.graphics.Path.Op.INTERSECT);
            p.setColor(i < lit ? on : off);
            g.drawPath(ring, p);
        }
        // Down in halves with filtering: each pixel the average of the ones it covers.
        Bitmap half = Bitmap.createScaledBitmap(big, bw / 2, bh / 2, true);
        big.recycle();
        Paint f = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(half, null, new RectF(0, 0, c.getWidth(), s), f);
        half.recycle();
    }

    /** Ethernet: three solid nodes joined by lines, drawn bolder to match the Wi-Fi weight. */
    void drawEthernet(Canvas c, float s, int col) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(col);
        p.setStrokeWidth(s * 0.09f);
        p.setStrokeCap(Paint.Cap.ROUND);
        float m = s * 0.5f;
        c.drawLine(m, s * 0.36f, m, s * 0.52f, p);
        c.drawLine(s * 0.24f, s * 0.52f, s * 0.76f, s * 0.52f, p);
        c.drawLine(s * 0.24f, s * 0.52f, s * 0.24f, s * 0.64f, p);
        c.drawLine(s * 0.76f, s * 0.52f, s * 0.76f, s * 0.64f, p);
        RectF r = new RectF(m - s * 0.15f, s * 0.08f, m + s * 0.15f, s * 0.36f);
        c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
        r.set(s * 0.09f, s * 0.62f, s * 0.39f, s * 0.9f);
        c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
        r.set(s * 0.61f, s * 0.62f, s * 0.91f, s * 0.9f);
        c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
    }

    /** Android TV's system click on D-pad moves, select and Back (Settings -> Home -> Navigation sounds). */
    static void navSound(Context c, KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return;
        int k = ev.getKeyCode();
        if (k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            Glass.dirX = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            Glass.dirY = k == KeyEvent.KEYCODE_DPAD_UP ? -1 : k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        }
        switch (ev.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                Clicks.move(c, ev.getKeyCode());
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (ev.getRepeatCount() == 0) Clicks.select(c);
                break;
            case KeyEvent.KEYCODE_BACK:
                if (ev.getRepeatCount() == 0) Clicks.back(c);
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- idle full-screen carousel

    boolean idle, swallowUp;
    float idleScrollY, idleInfoY, idleDotsY;
    boolean idleOverviewShown;

    /** Seconds without input before the dock slides away (Settings -> Home); 0 = never. */
    int idleSeconds() {
        return Profiles.homePrefs(this).getInt("idle_sec", 20);
    }

    final Runnable idleRunnable = this::enterIdle;

    void resetIdle() {
        handler.removeCallbacks(idleRunnable);
        if (handler.hasCallbacks(idleRise)) {
            // A press while rising to the top: stay up there (the press moves from the dock).
            handler.removeCallbacks(idleRise);
            idleReturnIndex = -1;
        }
        int sec = idleSeconds();
        if (sec > 0 && resumed) handler.postDelayed(idleRunnable, sec * 1000L);
    }

    /**
     * No input for a while: the dock and grid slide down off screen, the weather card fades, and
     * the carousel keeps playing full screen with its title and summary at the bottom.
     */
    void enterIdle() {
        if (idle || !resumed || heroItems.isEmpty() || folderMode
                || pickerLayer != null || menuLayer != null || moveIndex >= 0 || switcher != null) {
            resetIdle();
            return;
        }
        if (!heroShown) {
            // Down in the app grid: rise back to the top first, then go full screen once the
            // carousel is up (the same wait as from the dock); the press that wakes it returns here.
            idleReturnIndex = focusedIndex;
            goTop();
            handler.removeCallbacks(idleRunnable);
            handler.postDelayed(idleRise, 900);
            return;
        }
        idle = true;
        // Full screen shows what's on across all your services, not just the app you were on.
        shelfFor(null, false);
        idleScrollY = scroll.getTranslationY();
        idleInfoY = heroInfoY;
        idleDotsY = heroDotsY;
        idleOverviewShown = heroOverview.getVisibility() == View.VISIBLE;
        scroll.animate().cancel();
        // The apps slide away whole, as on tvOS: no fade, so no full-screen layer to blend.
        scroll.animate().translationY(screenH).setDuration(900).setInterpolator(EASE)
                .setUpdateListener(null).start();
        moveHeroInfo(0, dp(4), 900);
        weatherCard.animate().alpha(0f).setDuration(500).setInterpolator(EASE).start();
        heroButton.animate().alpha(0f).setDuration(300).setInterpolator(EASE).start();
        heroOverview.animate().cancel();
        heroOverview.setVisibility(heroOverview.getText().length() > 0 ? View.VISIBLE : View.GONE);
        heroOverview.animate().alpha(0.85f).setDuration(600).setInterpolator(EASE).start();
        handler.removeCallbacks(heroAdvance);
        scheduleAdvance();
    }

    final Runnable idleRise = () -> {
        if (resumed && heroShown) enterIdle();
        else idleReturnIndex = -1;
    };

    /** Where focus was in the app grid before going full screen, to go back to; -1 for the dock. */
    int idleReturnIndex = -1;

    void exitIdle() {
        if (!idle) return;
        idle = false;
        // Back to the shelf of the app in focus (a dock app shows its own titles again).
        handler.removeCallbacks(shelfUpdate);
        handler.postDelayed(shelfUpdate, 260);
        // Back to the app grid, if that's where it went full screen from: the weather stays hidden
        // there, as it is whenever the grid is scrolled up.
        boolean toGrid = idleReturnIndex >= 0;
        if (toGrid) {
            int back = idleReturnIndex;
            idleReturnIndex = -1;
            handler.postDelayed(() -> focusIndex(back), 120);
        }
        scroll.animate().cancel();
        scroll.animate().translationY(idleScrollY).setDuration(450).setInterpolator(EASE)
                .setUpdateListener(null).withEndAction(this::invalidateGlass).start();
        moveHeroInfo(idleInfoY, idleDotsY, 450);
        weatherCard.animate().alpha(toGrid ? 0f : weatherAlpha()).setDuration(350).setInterpolator(EASE).start();
        if (shelfView.isFocused()) {
            heroButton.animate().alpha(1f).setDuration(300).setInterpolator(EASE).start();
            handler.removeCallbacks(heroAdvance);  // browsing by hand: no auto-advance
        }
        if (!idleOverviewShown) {
            heroOverview.animate().alpha(0f).setDuration(200).setInterpolator(EASE)
                    .withEndAction(() -> heroOverview.setVisibility(View.GONE)).start();
        }
        resetIdle();
    }

    // ---------------------------------------------------------------- weather

    /**
     * Once a minute: fetch when the forecast is due (every 15 minutes by default), move "Now" on,
     * and pick up Wi-Fi signal changes.
     */
    int tickCount;
    final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            Weather.refresh(MainActivity.this, false, MainActivity.this::updateWeather);
            updateWeather();
            // Carousel content rotates even if Home stays open for hours (fetches only when stale).
            if (++tickCount % 5 == 0 && !shelfView.hasFocus()) loadHero();
            updateNet();
            handler.postDelayed(this, 60 * 1000L);
        }
    };

    boolean weatherShown() {
        return weatherCard != null && weatherCard.getVisibility() == View.VISIBLE && weatherAlpha() > 0.5f;
    }

    boolean weatherOpen;

    float weatherAlpha() {
        return folderMode || weatherOpen || idle ? 0f : clockAlpha();
    }

    /** Shows the cached forecast (hidden when weather is off or nothing has loaded yet). */
    void updateWeather() {
        if (weatherCard == null) return;
        Weather.Data d = Weather.enabled(this) ? Weather.cached(this) : null;
        boolean show = d != null;
        if (!show && weatherCard.isFocused()) focusTop();
        weatherCard.setVisibility(show ? View.VISIBLE : View.GONE);
        weatherCard.setData(d);
        if (show) io.execute(() -> WeatherView.prepare(d, unit));
        if (show && !weatherCard.isFocused()) weatherCard.setAlpha(weatherAlpha());
    }

    /** The full forecast: a glass panel opening from the weather card in the top-left corner. */
    void showWeather() {
        Weather.Data d = Weather.cached(this);
        if (d == null) return;
        if (pickerLayer != null) root.removeView(pickerLayer);
        popoverReturn = weatherCard;
        FrameLayout layer = new FrameLayout(this);
        layer.setClickable(true);
        layer.setClipChildren(false);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xA6000000, 0x4D000000, 0x00000000}));
        layer.addView(shade, new FrameLayout.LayoutParams(screenW * 3 / 5, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        WeatherView panel = new WeatherView(this, true, unit);
        LiquidGlass glass = new LiquidGlass(dp(26), 0x33000000, glassSource, screenW, screenH, unit).adaptive();
        glass.lens = dp(7);
        panel.setBackground(glass);
        panel.setFocusable(true);
        panel.setData(d);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(WeatherView.FULL_W), dp(WeatherView.FULL_H),
                Gravity.TOP | Gravity.START);
        plp.topMargin = dp(24);
        plp.leftMargin = dockOuter;
        layer.addView(panel, plp);

        root.addView(layer, match());
        pickerLayer = layer;
        pickerCells = new ArrayList<>();
        pickerCells.add(panel);
        weatherOpen = true;
        // The forecast grows out of the pill, from its corner, on the focus spring; the pill
        // fades into it and the shade comes up behind.
        weatherCard.animate().alpha(0f).setDuration(140).setInterpolator(EASE).start();
        shade.setAlpha(0f);
        shade.animate().alpha(1f).setDuration(300).setInterpolator(EASE).start();
        panel.setPivotX(0);
        panel.setPivotY(0);
        panel.setScaleX(0.7f);
        panel.setScaleY(0.7f);
        panel.setAlpha(0f);
        // As one picture while it grows (no text redrawn at each size); it lands at its own size.
        panel.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Glass.SNAP).withLayer().start();
        ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
        in.setDuration(200);
        in.setInterpolator(EASE);
        in.addUpdateListener(a -> panel.setAlpha((float) a.getAnimatedValue()));
        in.start();
        panel.requestFocus();
        Weather.refresh(this, false, () -> {
            Weather.Data nd = Weather.cached(this);
            if (nd != null && panel.isAttachedToWindow()) panel.setData(nd);
            updateWeather();
        });
    }

    // ---------------------------------------------------------------- popover

    List<View> pickerCells = new ArrayList<>();

    boolean statusFocused() {
        View f = getCurrentFocus();
        return f != null && f == ccButton;
    }

    /** Directional focus inside a popover: nearest cell whose centre lies in that direction. */
    boolean pickerMove(int keyCode) {
        View cur = getCurrentFocus();
        if (!pickerCells.contains(cur)) {
            if (!pickerCells.isEmpty()) pickerCells.get(0).requestFocus();
            return true;
        }
        int[] a = new int[2];
        cur.getLocationOnScreen(a);
        float ax = a[0] + cur.getWidth() / 2f, ay = a[1] + cur.getHeight() / 2f;
        View best = null;
        float bestScore = Float.MAX_VALUE;
        for (View v : pickerCells) {
            if (v == cur) continue;
            int[] b = new int[2];
            v.getLocationOnScreen(b);
            float dx = b[0] + v.getWidth() / 2f - ax, dy = b[1] + v.getHeight() / 2f - ay;
            float main, cross;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: main = -dx; cross = dy; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: main = dx; cross = dy; break;
                case KeyEvent.KEYCODE_DPAD_UP: main = -dy; cross = dx; break;
                default: main = dy; cross = dx; break;
            }
            if (main <= dp(4)) continue;
            float score = main + Math.abs(cross) * 2.2f;
            if (score < bestScore) {
                bestScore = score;
                best = v;
            }
        }
        if (best != null) best.requestFocus();
        return true;
    }

    View popoverReturn;

    void closePopover() {
        if (pickerLayer == null) return;
        FrameLayout l = pickerLayer;
        pickerLayer = null;
        // Back into the pill: the panel shrinks towards its corner as it fades, and the pill returns.
        View panel = l.getChildCount() > 1 ? l.getChildAt(1) : null;
        if (panel != null) panel.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setDuration(200).setInterpolator(EASE).withLayer().start();
        l.getChildAt(0).animate().alpha(0f).setDuration(240).setInterpolator(EASE).withEndAction(() -> root.removeView(l)).start();
        weatherOpen = false;
        if (popoverReturn != null && popoverReturn.isAttachedToWindow()) popoverReturn.requestFocus();
        else goTop();
        popoverReturn = null;
        // Faded up after focus has landed: focusing the pill lifts it, and the lift would stop a fade begun before.
        weatherCard.setAlpha(0f);
        weatherCard.animate().alpha(weatherAlpha()).setDuration(260).setInterpolator(EASE).start();
    }

    // ---------------------------------------------------------------- tvOS context menu

    FrameLayout menuLayer;
    View menuReturnFocus;

    /**
     * tvOS-style context menu: a dark Liquid Glass panel beside the focused card, title on top,
     * rows below; the focused row becomes a white pill with dark text. Back closes it.
     */
    void showMenu(View anchor, String title, List<String> labels, List<Runnable> actions, int destructive) {
        closeMenu();
        menuReturnFocus = anchor;
        menuLayer = new FrameLayout(this);
        menuLayer.setClickable(true);
        // The dim behind the menu fades with it (its own view, so the fade is a single alpha).
        View dim = new View(this);
        dim.setBackgroundColor(0x59000000);
        dim.setAlpha(0f);
        menuLayer.addView(dim, match());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(12), dp(8), dp(8));
        // "Regular" Liquid Glass: heavier dimming so the menu text stays legible over anything.
        LiquidGlass glass = new LiquidGlass(dp(20), 0xB81A1A20, glassSource, screenW, screenH, unit);
        glass.lens = dp(8);
        panel.setBackground(glass);
        panel.setElevation(dp(30));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        TextView head = text(11, Ui.font(this, "semibold"), 0.55f);
        head.setText(title);
        head.setShadowLayer(0, 0, 0, 0);
        head.setPadding(dp(12), 0, dp(12), dp(8));
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(head);

        List<View> rows = new ArrayList<>();
        boolean[] opening = {true};  // the first row starts lit, so the menu pops as one still picture
        for (int i = 0; i < labels.size(); i++) {
            int idx = i;
            TextView row = text(14, Ui.font(this, "medium"), 1f);
            row.setText(labels.get(i));
            row.setShadowLayer(0, 0, 0, 0);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, dp(12), 0);
            row.setFocusable(true);
            row.setClickable(true);
            int base = i == destructive ? 0xFFFF6B5E : Color.WHITE;
            row.setTextColor(base);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(12));
            pill.setColor(Color.WHITE);
            pill.setAlpha(0);
            row.setBackground(pill);
            int focusedText = idx == destructive ? 0xFFD7372A : 0xFF111114;
            row.setOnFocusChangeListener((v, has) -> {
                if (opening[0]) {
                    pill.setAlpha(255);
                    row.setTextColor(focusedText);
                    return;
                }
                // The white plate and the text colour fade together, like every other control.
                android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
                ValueAnimator fade = ValueAnimator.ofFloat(pill.getAlpha() / 255f, has ? 1f : 0f);
                fade.setDuration(has ? 200 : 260);
                fade.setInterpolator(EASE);
                fade.addUpdateListener(an -> {
                    float t = (float) an.getAnimatedValue();
                    pill.setAlpha(Math.round(t * 255));
                    row.setTextColor((int) ev.evaluate(t, base, focusedText));
                });
                fade.start();
                Glass.lift(v, has, 1.04f, 0);
            });
            row.setOnClickListener(v -> {
                closeMenu();
                actions.get(idx).run();
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
            rlp.topMargin = dp(2);
            panel.addView(row, rlp);
            rows.add(row);
        }
        // Wrap focus top <-> bottom and keep it inside the menu.
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setId(View.generateViewId());
        }
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setNextFocusUpId(rows.get((i - 1 + rows.size()) % rows.size()).getId());
            r.setNextFocusDownId(rows.get((i + 1) % rows.size()).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }

        int w = dp(220);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        float aw = anchor.getWidth() * anchor.getScaleX(), ah = anchor.getHeight() * anchor.getScaleY();
        int x = (int) (loc[0] + aw + dp(18));
        if (x + w > screenW - dp(24)) x = (int) (loc[0] - w - dp(18));
        x = Math.max(dp(24), x);
        panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int h = panel.getMeasuredHeight();
        int y = (int) (loc[1] + ah / 2 - h / 2f);
        y = Math.max(dp(24), Math.min(y, screenH - h - dp(24)));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = x;
        plp.topMargin = y;
        menuLayer.addView(panel, plp);
        root.addView(menuLayer, match());

        // Pops out of the card's side on a spring, fading up as it grows. It moves as one picture
        // (a GPU layer), so its words aren't redrawn at a new size every frame; it lands at exactly
        // its own size, so there's no change when the picture gives way to the real thing.
        panel.setPivotX(x > loc[0] ? 0 : w);
        panel.setPivotY(h / 2f);
        panel.setAlpha(0f);
        panel.setScaleX(0.86f);
        panel.setScaleY(0.86f);
        if (!rows.isEmpty()) rows.get(0).requestFocus();
        opening[0] = false;
        panel.animate().scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(Glass.SNAP).withLayer().start();
        // Alpha on its own, shorter animator: the menu is opaque well before the spring settles.
        ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
        in.setDuration(180);
        in.setInterpolator(EASE);
        in.addUpdateListener(a -> panel.setAlpha((float) a.getAnimatedValue()));
        in.start();
        dim.animate().alpha(1f).setDuration(240).setInterpolator(EASE).start();
    }

    void closeMenu() {
        if (menuLayer == null) return;
        FrameLayout m = menuLayer;
        menuLayer = null;
        // Back into the card it came from: a little smaller as it fades, and the dim lifts.
        View panel = m.getChildCount() > 1 ? m.getChildAt(1) : null;
        if (panel != null) panel.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f).setDuration(160).setInterpolator(EASE).withLayer().start();
        m.getChildAt(0).animate().alpha(0f).setDuration(200).setInterpolator(EASE).withEndAction(() -> root.removeView(m)).start();
        if (menuReturnFocus != null && menuReturnFocus.isAttachedToWindow()) menuReturnFocus.requestFocus();
        menuReturnFocus = null;
    }

    void startSafe(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast("Not available on this TV");
        }
    }

    // ---------------------------------------------------------------- move mode

    void startMove(int index) {
        moveIndex = index;
        focusIndex(index);
        startWiggle(tiles.get(index));
        toast("Move with the arrows. Press OK when done.");
    }

    void moveTo(int to) {
        int last = apps.size() - 1;  // the folder tile always stays at the end
        if (to > last && moveIndex / COLS < last / COLS) to = last;
        if (to < 0 || to > last || to == moveIndex) return;
        Map<String, int[]> before = tilePositions();
        AppEntry e = apps.remove(moveIndex);
        apps.add(to, e);
        moveIndex = to;
        saveOrder();
        buildGrid();
        focusIndex(to);
        startWiggle(tiles.get(to));
        slideFrom(before);
    }

    /** Window position of every visible card, keyed by app, for the move animation. */
    Map<String, int[]> tilePositions() {
        Map<String, int[]> m = new LinkedHashMap<>();
        for (int i = 0; i < tiles.size(); i++) {
            int[] loc = new int[2];
            tiles.get(i).getLocationInWindow(loc);
            m.put(shown.get(i).key, loc);
        }
        return m;
    }

    /**
     * tvOS-style reflow: after the grid is rebuilt in its new order, every card starts at its old
     * on-screen position and glides to its new slot, so neighbours visibly make room.
     */
    void slideFrom(Map<String, int[]> before) {
        content.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                content.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < tiles.size(); i++) {
                    int[] old = before.get(shown.get(i).key);
                    if (old == null) continue;
                    View t = tiles.get(i);
                    int[] now = new int[2];
                    t.getLocationInWindow(now);
                    float dx = old[0] - now[0], dy = old[1] - now[1];
                    if (dx == 0 && dy == 0) continue;
                    if (Math.abs(dy) > tileH / 2f && Math.abs(dx) > tileW * 1.5f) {
                        // Wrapped to another row (end of a row): a slide would streak across the
                        // whole screen, so settle into the new slot instead, as tvOS does.
                        View cell = (View) t.getParent();
                        cell.setAlpha(0f);
                        cell.setScaleX(0.86f);
                        cell.setScaleY(0.86f);
                        cell.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).setInterpolator(Glass.SNAP).start();
                        continue;
                    }
                    // Neighbours make room on the focus spring: quick, with a small settle.
                    t.setTranslationX(dx);
                    t.setTranslationY(dy);
                    t.animate().translationX(0).translationY(0).setDuration(420).setInterpolator(Glass.SNAP).start();
                }
                return true;
            }
        });
    }

    void endMove() {
        stopWiggle();
        moveIndex = -1;
    }

    void startWiggle(View v) {
        stopWiggle();
        wiggle = ObjectAnimator.ofFloat(v, View.ROTATION, -1.5f, 1.5f);
        wiggle.setDuration(130);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.start();
    }

    void stopWiggle() {
        if (wiggle != null) {
            wiggle.cancel();
            Object t = wiggle.getTarget();
            if (t instanceof View) ((View) t).setRotation(0);
            wiggle = null;
        }
    }

    // ---------------------------------------------------------------- helpers

    TextView text(float sp, Typeface tf, float alpha) {
        TextView t = new TextView(this);
        Ui.textSize(t, sp);
        t.setTypeface(tf);
        t.setTextColor(Color.WHITE);
        t.setAlpha(alpha);
        t.setShadowLayer(10, 0, 2, 0x99000000);
        return t;
    }

    FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** Design units (1/960 of the screen width), so layout follows the display's resolution. */
    int dp(float v) {
        return Math.round(v * unit);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

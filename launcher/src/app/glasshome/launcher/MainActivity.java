package app.glasshome.launcher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
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
import android.os.SystemClock;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** tvOS 26-style Home: the top-shelf carousel behind a glass dock and the app grid. */
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
    /** The Apple TV app's easeOut and easeIn (quadratic): presses, menus, and focus going out. */
    static final PathInterpolator EASE_OUT_QUAD = new PathInterpolator(0.333f, 0.667f, 0.667f, 1f),
            EASE_IN_QUAD = new PathInterpolator(0.333f, 0f, 0.667f, 0.333f);
    /** A gentle start and a soft landing, for a move nothing was pressed for (the dock leaving when idle). */
    static final PathInterpolator STANDARD = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /**
     * The Apple TV app's row and page scroll (its spring, stiffness 150 and damping 30, as a
     * curve): half way in about a seventh of a second, then a long, soft settle.
     */
    static final PathInterpolator SCROLL = new PathInterpolator(0.2f, 0.7f, 0.25f, 1f);
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
    /** tvOS 26's focused icon is ~1.22x its resting size (304x182 vs 249x149 px, measured on its Home Screen). */
    static final float FOCUS_SCALE = 1.22f;
    /**
     * Pressed (the Apple TV app's "Acknowledge"): half the focus growth, sunk in 80 ms on OK's
     * press and back in 80 ms on its release, then the card opens.
     */
    static final float PRESS_SCALE = 1f + (FOCUS_SCALE - 1f) * 0.5f;
    static final long PRESS_MS = 80;
    /**
     * Focus lift: a damped spring, like tvOS (over 450 ms: 90% in about 120 ms, a 4% overshoot,
     * settled by 300 ms). Going down is slower, so the card you left trails the one you reach.
     */
    static final Spring LIFT = new Spring(0.72f, 10f);

    static class AppEntry {
        String key, pkg, label;
        Drawable banner, icon;
        /** The banner pre-rendered at the focused tile's exact pixel size, with mipmaps. */
        Bitmap tileArt;
        /** The app's own icon and banner resources, for fetching them at a higher density. */
        int iconRes, bannerRes;
        /** Where its banner and icon are still to be loaded from (scan's parallel step); then null. */
        ResolveInfo from;
        /** Its banner is a card Home drew (from its icon, or its banner's logo zoomed), not the app's own. */
        boolean drawn;
        Intent launch;
        int color;
        boolean system;
        /**
         * The app's installed file and its date: a rescan keeps the card (and its drawn art) of an
         * app whose stamp and name haven't changed.
         */
        String stamp;
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
    /** The dots themselves, inside heroDots (which shows and hides the whole indicator). */
    PageDots dotRow;
    /** How many pages the dots show, or are changing to (a row fading out still has the old count). */
    int dotPages;
    /** Full screen's chevrons at the left and right edges: Left and Right browse the carousel. */
    View[] heroArrows;
    ImageView heroLogo;
    TextView heroTitle, heroMeta, heroOverview;
    /** tvOS-style capsule button: glass when idle, solid white with dark text when focused. */
    LinearLayout heroButton;
    TextView heroBtnText;
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
    /**
     * Settings changed what the grid holds (Reset Home Screen Layout, the Inputs card): rebuilt
     * once Home is back in front.
     */
    static volatile boolean layoutChanged;

    /**
     * Home's background lanes, one thread each, kept for the life of the process (a recreate
     * reuses them instead of leaving threads behind). io opens the pictures you're about to see;
     * the others run at background priority, so their work gives way to the UI and drawing.
     */
    static final java.util.concurrent.ExecutorService io = lane("io", false);
    /** The shelf's artwork fetched ahead, onto the disk. */
    static final java.util.concurrent.ExecutorService preload = lane("preload", true);
    /** The top shelf's refresh: many requests and picture checks, never in the carousel's way. */
    static final java.util.concurrent.ExecutorService net = lane("shelf-fetch", true);
    /** Rescans of the installed apps, after one is added, updated or removed. */
    static final java.util.concurrent.ExecutorService scans = lane("apps", true);

    static java.util.concurrent.ExecutorService lane(String name, boolean background) {
        return java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(() -> {
            if (background) android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, name));
    }

    volatile int preloadGen;
    /** The top shelf is being refreshed (on net). */
    volatile boolean fetching;
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
        // slide-in its frames on this GPU. Coming back fades in once the panel has gone, only as far
        // as the scroll allows (down in the app grid it stays hidden).
        if (!show) clockBox.setAlpha(0f);
        else clockBox.animate().alpha(statusFocused() ? 1f : clockAlpha()).setStartDelay(200).setDuration(320)
                .setInterpolator(EASE).start();
    }
    LinearLayout titleBox;
    View chevron;
    TextView shelfTitle, shelfHint, swipeHint;
    float unit = 2f;
    int screenW, screenH, tileW, tileH, gap, side, shelfH, labelH, radius, dockPadV, dockInset, dockOuter;
    int focusedIndex = 0;
    int moveIndex = -1;
    boolean folderMode;
    boolean resumed;
    ObjectAnimator wiggle;
    BroadcastReceiver pkgReceiver;
    /** The grid's glide to a row (scrollToY), and the scroll position it's headed for. */
    ValueAnimator rowScroll;
    int rowScrollTarget = -1;
    /**
     * The last arrow acted on: its event time (held arrows are paced from it), whether it came
     * quickly after the one before, and when it was handled (a focus change moments after that
     * one is the arrow's own, not one Home made).
     */
    long lastArrowAt, arrowAt;
    boolean fastMove;
    /** Focus being put back (a return to Home, a closed menu): the card is simply up, with no lift. */
    boolean snapFocus;
    /** When OK last opened something: a second press within 250 ms is ignored, as on Apple TV. */
    long lastSelectAt;
    final int[] focusLoc = new int[2];

    final Runnable shelfUpdate = () -> {
        if (focusedIndex >= 0 && focusedIndex < shown.size()) {
            shelfFor(shown.get(focusedIndex), hasDock() && focusedIndex < COLS);
        }
    };
    final Runnable reloadRun = this::reload;

    @Override
    protected void onCreate(Bundle b) {
        live = new java.lang.ref.WeakReference<>(this);
        super.onCreate(b);
        ClipAudit.watch(this);  // a development check, off unless asked for over adb
        // The wallpaper/artwork always covers the screen: skip the window's own full-screen fill
        // (this TV's Mali GPU is fill-rate bound, so every hidden full-screen layer costs frames).
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
        radius = Math.round(tileW * 0.105f);  // tvOS 26 icon corners: ~26-28 px on a 249 px icon
        shelfH = (int) (screenH * 0.73f);    // dock top sits ~73% down the screen
        dockPadV = dp(20);

        // The clicks are made (or read back) on their own thread: the first frame doesn't need them.
        new Thread(() -> Clicks.init(this), "clicks").start();
        // Startup: the app list (package manager, banners, icon colours) and the wallpaper are
        // prepared in the background while the window's views are built (about a quarter of a
        // second saved at start-up).
        java.util.concurrent.Future<Map<String, AppEntry>> appsTask = io.submit(() -> scan(null));
        String wall = prefs.getString("wallpaper", "night");
        wallPrefetch = new java.util.concurrent.FutureTask<>(() -> makeWallpaper(wall));
        wallPrefetchStyle = wall;
        new Thread((Runnable) wallPrefetch, "wallpaper").start();
        buildChrome();
        Map<String, AppEntry> found;
        try {
            found = appsTask.get();
        } catch (Exception e) {
            found = scan(null);
        }
        install(found);
        buildGrid();
        focusIndex(0);
        registerPackageReceiver();
        registerScreenOff();
        handler.postDelayed(saveCards, SAVE_CARDS_MS);
    }

    /** How long after the cards change they're saved: well clear of start-up and the carousel's first pictures. */
    static final long SAVE_CARDS_MS = 15_000;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home twice (KeysService) opens the App Switcher; adb screenshots use --es panel cc|inputs|weather.
        String panel = intent.getStringExtra("panel");
        if (panel != null) {
            closeMenu();
            if (panel.equals("cc")) openControlCentre();
            else if (panel.equals("inputs")) openInputs();
            else if (panel.equals("weather")) showWeather();
            else if (panel.equals("switcher") && switcher == null) AppSwitcher.open(this);
            return;
        }
        if (settingsNext) {
            // Control Centre's Home Screen settings, routed through Home (see settingsNext): not a
            // press of Home, so focus stays where it was for when Settings closes.
            if (idle) exitIdle();
            closeMenu();
            return;
        }
        // The TV woke with Home on screen (its power key starts Home with a wake reason): that
        // isn't a press of Home either, so focus stays where it was, as tvOS resumes where you
        // were. A wake over an app still falls through, so focus returns to that app's card.
        String why = intent.getStringExtra("android.intent.extra.EXTRA_START_REASON");
        if (why != null && why.startsWith("WAKE_REASON") && !intent.getBooleanExtra("android.intent.extra.FROM_HOME_KEY", false)
                && !(away && appOpened)) {
            resetIdle();
            return;
        }
        if (idle) exitIdle();
        resetIdle();
        // Home lands on the card of the app you just left, as on Apple TV (in the dock, the grid
        // or the folder), else on the first app in the dock. Close any overlay first (Control
        // Centre, Inputs, weather forecast, menus, move mode, folder).
        int back = away && appOpened && openedKey != null ? indexOfKey(openedKey) : -1;
        if (switcher != null) switcher.close();
        handler.removeCallbacks(pressAction);  // an OK on its way to opening something is dropped
        pressAction = null;
        if (forecastLayer != null) {
            popoverReturn = null;
            FrameLayout l = forecastLayer;
            forecastLayer = null;
            weatherOpen = false;
            root.removeView(l);
            weatherCard.setAlpha(weatherAlpha());
            scanAfter(250);  // a rescan it held back
        }
        closeMenu();
        // Home pressed while already on the home screen: jump back to the top of the home grid.
        if (moveIndex >= 0) endMove();
        if (back >= 0) {
            // Put back, not moved to: the card is simply up when the app has gone, with its row
            // already in view, as the Apple TV app restores focus to an uncovered page.
            snapFocus = true;
            focusIndex(back, false);
            snapFocus = false;
        } else if (folderMode) {
            folderMode = false;
            weatherCard.setAlpha(weatherAlpha());
            buildGrid();
            scrollToY(0, false);
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
        // Leaving Home (an app's button on the remote, the screen saver, sleep) ends move mode and
        // closes a context menu, as tvOS does: nothing is still wiggling when you come back, and
        // the arrows move focus again. Out of sight, so at once rather than animated.
        if (moveIndex >= 0) endMove();
        if (menuLayer != null) {
            FrameLayout m = menuLayer;
            View card = menuReturnFocus;
            menuLayer = null;
            menuReturnFocus = null;
            snapFocus = true;
            if (card != null && card.isAttachedToWindow()) card.requestFocus();
            snapFocus = false;
            root.removeView(m);
        }
        // A card still sunk from a press (Home or an app's button came before OK's release): up again.
        View f = getCurrentFocus();
        if (moveIndex < 0 && f != null && tiles.contains(f)) {
            f.animate().cancel();
            f.setScaleX(FOCUS_SCALE);
            f.setScaleY(FOCUS_SCALE);
            f.setTranslationX(0);
            f.setTranslationY(0);
            f.setTranslationZ(dp(18));
            f.setRotation(0);
        }
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
        String ws = prefs.getString("wallpaper", "night");
        if (!ws.equals(wallStyle)) {
            wallStyle = ws;
            setWallpaper(ws);
        }
        if (layoutChanged) {
            // After Settings' closing animation, which the rebuild would otherwise cost frames.
            layoutChanged = false;
            handler.removeCallbacks(reloadRun);
            handler.postDelayed(reloadRun, 450);
        }
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
        resetIdle();
        if (settingsNext) {
            // Control Centre's Home Screen settings: opened from here, once Home is back in front.
            settingsNext = false;
            handler.post(() -> {
                if (resumed) startActivity(new Intent(this, SettingsActivity.class));
            });
        }
        // Opened from the app list while another launcher is the home screen: offer once to take
        // over. Whether it was asked comes first, so Android isn't asked for the home screen on
        // every return once it has been.
        if (SetupActivity.done(this) && !prefs.getBoolean("asked_default_home", false) && !Perms.isDefaultHome(this)) {
            prefs.edit().putBoolean("asked_default_home", true).apply();
            // A tvOS alert, as every other question in the app (its keys: see dispatchKeyEvent).
            String app = getString(R.string.app_name);
            Perms.alert(this, "Use " + app + " as Your Home Screen?", "Your remote\u2019s Home button will open " + app + ".",
                    new String[]{"Use " + app, "Not Now"}, -1, w -> {
                        if (w == 0) Perms.requestDefaultHome(this);
                    });
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
        // The network as it is now. Just after a wake it is usually still coming back: what needs
        // the internet waits for it, and updateNet refreshes everything the moment it's back.
        readNet();
        loadHero(online(true));
        // The carousel moves on by itself again (leaving Home stopped it), unless you're browsing it.
        if (!shelfView.hasFocus()) scheduleAdvance();
        // The focused dock app's own shelf, held back while its app was opening (launchAnimated).
        if (tiles.contains(getCurrentFocus())) {
            handler.removeCallbacks(shelfUpdate);
            handler.postDelayed(shelfUpdate, 260);
        }
        resetIdle();  // the return clears pending callbacks, the idle timer among them
        // Apps added, updated or removed while Home was out of sight: rescanned now, after the return.
        if (this.appsDirty) reload();
        else applyPendingScan();
        // The weather and the sleep timer's glyph at once, then every minute.
        firstTick = true;
        handler.removeCallbacks(this.weatherTick);
        handler.post(this.weatherTick);
    };

    /** The next weather check is the first since Home came back. */
    boolean firstTick;

    /**
     * Whether to reach for the internet now. Straight after a return or a wake (fresh) only a
     * working connection counts: the link is usually still coming back, and updateNet refreshes
     * everything once it is. Later any network does, so one whose internet check never passes
     * (a blocked connectivity check) still refreshes.
     */
    boolean online(boolean fresh) {
        return netState == 2 || (!fresh && netState == 1);
    }

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
        stopWiggle(false);  // an endless animator would keep this (destroyed) Home alive
        if (pkgReceiver != null) unregisterReceiver(pkgReceiver);
        if (screenOffReceiver != null) unregisterReceiver(screenOffReceiver);
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
        // soft, with no per-frame blur on this weak GPU.
        wallView = new ImageView(this);
        wallView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallStyle = prefs.getString("wallpaper", "night");
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
        // tvOS 26's docked Top Shelf: a wide, thin chevron over "Swipe up for full screen", just
        // above the dock (Up here: the remote has no touch surface). The chevron rises to make room
        // for the hint (setHeroShown); in the app grid it sits alone at the top, pointing back up.
        int cw = dp(27), ch = dp(6);
        float stroke = dp(1.5f);
        Bitmap cb = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        cp.setColor(0xCCFFFFFF);
        cp.setStyle(Paint.Style.STROKE);
        cp.setStrokeWidth(stroke);
        cp.setStrokeCap(Paint.Cap.ROUND);
        cp.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path cpath = new android.graphics.Path();
        float pad = stroke / 2f;  // the stroke spans the whole box, as Apple's 54x12 does
        cpath.moveTo(pad, ch - pad);
        cpath.lineTo(cw / 2f, pad);
        cpath.lineTo(cw - pad, ch - pad);
        new Canvas(cb).drawPath(cpath, cp);
        ImageView chevronView = new ImageView(this);
        chevronView.setImageBitmap(cb);
        chevronView.setAlpha(0f);  // until there's a carousel (heroChrome)
        chevron = chevronView;
        FrameLayout.LayoutParams chp = new FrameLayout.LayoutParams(cw, ch,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        chp.bottomMargin = dp(14);
        shelf.addView(chevron, chp);
        swipeHint = text(12, Ui.font(this, "regular"), 0f);
        // 70% white in the colour, not the view's alpha: text with a shadow at part alpha is drawn
        // through an off-screen layer every frame, and this one rests over the carousel.
        swipeHint.setTextColor(0xB3FFFFFF);
        swipeHint.setText("Press up for full screen");
        FrameLayout.LayoutParams shp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        shp.bottomMargin = dp(10);
        shelf.addView(swipeHint, shp);
        content.addView(shelf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, shelfH));

        // tvOS 26 status row (per Apple's Control Centre reference): plain time, then a glass
        // Control Centre button that turns white and lifts when focused.
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
        // Status glyphs left of the time, as tvOS shows them: only while they have something to
        // say (a sleep timer running, no internet), each drawn at its exact size so it stays crisp.
        timerIcon = new ImageView(this);
        timerIcon.setScaleType(ImageView.ScaleType.CENTER);
        timerIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = dp(7);
        netIcon = new ImageView(this);
        netIcon.setScaleType(ImageView.ScaleType.CENTER);
        netIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.rightMargin = dp(7);
        // The glyphs and time on the same glass as the gear beside it and the weather opposite:
        // one material across the top of the screen. Just to read, never focused.
        timeChip = new LinearLayout(this);
        timeChip.setOrientation(LinearLayout.HORIZONTAL);
        timeChip.setGravity(Gravity.CENTER_VERTICAL);
        timeChip.setPadding(dp(15), 0, dp(15), 0);
        timeChip.setBackground(pillGlass(btn / 2f));
        // No text shadow: the pill gives the contrast, and a blurred shadow is cut off at the
        // text's own edges, which showed as a faint dark box around the numbers.
        timeChip.addView(timerIcon, tlp);
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
            fade.setDuration(has ? 170 : 180);  // Glass.focus's timing, as every other plate
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

        // Room around the button for its focus growth (1.12x, 2 dp a side) and its lean at a wall
        // (bump, 6 dp) so nothing is clipped; the margins shrink by the same amount, so the row
        // sits exactly where it did.
        int grow = dp(10);
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

        // Home's focus tick, as tvOS plays it: only for a move an arrow made (none for focus Home
        // puts somewhere itself, nor for a press into a wall), panned to where focus landed.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (now == null || SystemClock.uptimeMillis() - arrowAt > 150) return;
            float x = 0.5f;
            if (now.isLaidOut()) {
                now.getLocationOnScreen(focusLoc);
                x = (focusLoc[0] + now.getWidth() * now.getScaleX() / 2f) / screenW;
            } else if (tiles.contains(now)) {
                // A card in a grid rebuilt this moment (move mode): not placed yet, so from its column.
                x = (side + tiles.indexOf(now) % COLS * (tileW + gap) + tileW / 2f) / screenW;
            }
            Clicks.move(this, x, fastMove);
        });

        setContentView(root);
    }

    void buildGrid() {
        stopWiggle(false);
        shown.clear();
        if (folderMode) {
            shown.addAll(hiddenApps);
        } else {
            shown.addAll(apps);
            if (folder != null) shown.add(folder);
        }
        // Like tvOS, the home grid has no big title; only the folder gets a header.
        titleBox.setVisibility(folderMode ? View.VISIBLE : View.GONE);
        chevron.setVisibility(folderMode ? View.GONE : View.VISIBLE);
        swipeHint.setVisibility(View.GONE);  // the chevron alone says it
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
                rlp.bottomMargin = rowGap();
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
        tile.setOutlineProvider(roundedOutline(radius));
        tile.setClipToOutline(true);
        tile.setElevation(0);  // only the focused tile casts a shadow (its lift); resting ones are flat
        // tvOS's resting edge: a 1 px hairline of 5% white over the art, so dark icons (Apple TV,
        // Channel 4) keep their shape on the dark dock and drawer.
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(radius);
        edge.setColor(Color.TRANSPARENT);
        edge.setStroke(Math.max(1, Math.round(unit / 2f)), 0x0DFFFFFF);
        tile.setForeground(edge);

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

        // tvOS focus has no outline ring: the icon is lit instead (its specular glow and bright
        // rim, faded in with the lift), plus a one-shot glare sweep. One view over the art, so its
        // fades need no off-screen pass.
        View shine = new View(this);
        shine.setBackground(new Shine(radius, 1.25f * unit));  // a 2.5 px rim, 3 px once lifted
        shine.forceHasOverlappingRendering(false);
        shine.setAlpha(0f);
        tile.addView(shine, match());
        View glare = new View(this);
        glare.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF}));  // a 14% peak (tvOS: 10 to 15%)
        glare.setRotation(18f);
        glare.forceHasOverlappingRendering(false);  // one gradient: its fade needs no off-screen pass
        glare.setAlpha(0f);
        tile.addView(glare, new FrameLayout.LayoutParams(tileW * 45 / 100, tileH * 2, Gravity.CENTER_VERTICAL));

        // Only the grid's cards have a name under them: the dock's don't.
        Name label = inDock ? null : new Name(this, unit, Ui.font(this, "medium"));
        cell.addView(tile, new LinearLayout.LayoutParams(tileW, tileH));
        if (label != null) {
            label.setText(a.label);
            label.setAlpha(0f);
            // As wide as the icon's pitch: only the focused icon shows its name, and it's wider then.
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(tileW + gap, labelH);
            llp.leftMargin = -gap / 2;
            cell.addView(label, llp);
        }

        tile.setOnFocusChangeListener((v, has) -> {
            // Moved here (or away) by the arrow just pressed, rather than put here by Home itself;
            // and quickly after the last move, when tvOS shortens the focus animations.
            boolean byArrow = SystemClock.uptimeMillis() - arrowAt < 60;
            boolean fast = byArrow && fastMove;
            if (has) {
                cell.setTranslationZ(dp(10));
                if (moveIndex >= 0 || snapFocus) {
                    // Rebuilt while moving, or focus put back (a return, a menu closed): already
                    // lifted, or every step would "pop" and every return replay the lift.
                    v.animate().cancel();
                    v.setScaleX(FOCUS_SCALE);
                    v.setScaleY(FOCUS_SCALE);
                    v.setTranslationX(0);
                    v.setTranslationY(0);
                    v.setTranslationZ(dp(18));
                    if (label != null) {
                        label.animate().cancel();
                        label.setAlpha(1f);
                        label.setTranslationY(tileH * (FOCUS_SCALE - 1f) / 2f);
                    }
                    shine.animate().cancel();
                    shine.setAlpha(1f);
                    glare.animate().cancel();
                    glare.setAlpha(0f);
                } else {
                    // Apple TV's jiggle: the card arrives with a small push in the direction the
                    // remote moved, then the spring settles it as it lifts. None on a quick move,
                    // nor for focus no arrow sent (Home, Back).
                    if (byArrow && !fast) {
                        v.setTranslationX(-Glass.dirX * dp(5));
                        v.setTranslationY(-Glass.dirY * dp(5));
                    }
                    // No GPU layer: the glare and the shine animate inside the card, so one would
                    // be re-rendered every frame anyway, at the cost of an extra pass.
                    v.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).translationX(0).translationY(0)
                            .translationZ(dp(18)).setStartDelay(0).setDuration(fast ? 250 : 450).setInterpolator(LIFT).start();
                    if (label != null) {
                        label.animate().alpha(1f).translationY(tileH * (FOCUS_SCALE - 1f) / 2f).setDuration(fast ? 120 : 240)
                                .setInterpolator(EASE).start();
                    }
                    // Liquid-glass illumination: a glare sweeps across once, entering from the side
                    // focus came from. Skipped on quick moves, where it would only flicker.
                    glare.animate().cancel();
                    if (fast) {
                        glare.setAlpha(0f);
                    } else {
                        boolean leftwards = byArrow && Glass.dirX < 0;
                        glare.setTranslationX(leftwards ? tileW * 1.2f : -tileW * 0.7f);
                        glare.setAlpha(1f);
                        glare.animate().translationX(leftwards ? -tileW * 0.7f : tileW * 1.1f).alpha(0f).setStartDelay(60)
                                .setDuration(700).setInterpolator(EASE).start();
                    }
                    shine.animate().alpha(1f).setDuration(260).setInterpolator(EASE).start();
                }
                if (label != null) label.marquee(true);
                onTileFocused(index);
            } else if (menuLayer != null && menuReturnFocus == v) {
                // Its menu is open beside it: the card stays up, as on tvOS, until focus moves on.
                glare.animate().cancel();
                glare.setAlpha(0f);
            } else {
                cell.setTranslationZ(0);
                glare.animate().cancel();
                glare.setAlpha(0f);
                if (label != null) label.marquee(false);
                if (snapFocus) {
                    // Focus put back elsewhere while Home is covered: down at once.
                    v.animate().cancel();
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setTranslationX(0);
                    v.setTranslationY(0);
                    v.setTranslationZ(0);
                    if (label != null) {
                        label.animate().cancel();
                        label.setAlpha(0f);
                        label.setTranslationY(0);
                    }
                    shine.animate().cancel();
                    shine.setAlpha(0f);
                } else {
                    // Down more slowly than the next card rises (tvOS's trail): on Apple's easeOut,
                    // with no overshoot.
                    v.animate().scaleX(1f).scaleY(1f).translationX(0).translationY(0).translationZ(0)
                            .setStartDelay(0).setDuration(fast ? 220 : 420).setInterpolator(EASE_OUT_QUAD).start();
                    if (label != null) label.animate().alpha(0f).translationY(0).setDuration(160).setInterpolator(EASE).start();
                    shine.animate().alpha(0f).setDuration(220).setInterpolator(EASE).start();
                }
            }
            v.invalidate();
        });
        tile.setOnClickListener(v -> {
            if (moveIndex >= 0) return;
            // Released (it sank as OK went down): back up, then the app or folder opens.
            rise(v);
            afterPress(() -> {
                if (FOLDER_KEY.equals(a.key)) enterFolder();
                else launchAnimated(v, a);
            });
        });
        tile.setOnLongClickListener(v -> {
            // Held: back up beside its menu (the folder has none: just back up).
            rise(v);
            if (moveIndex < 0 && !FOLDER_KEY.equals(a.key)) showOptions(index);
            return true;
        });
        tiles.add(tile);
        return cell;
    }

    /** OK went down on a card: it sinks to half its focus growth (Apple TV's press, easeOut 80 ms). */
    void press(View t) {
        t.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setStartDelay(0).setDuration(PRESS_MS)
                .setInterpolator(EASE_OUT_QUAD).start();
    }

    /** OK came up (or the press was held, or cancelled): back to its focused size, easeIn 80 ms. */
    void rise(View t) {
        t.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setStartDelay(0).setDuration(PRESS_MS)
                .setInterpolator(EASE_IN_QUAD).start();
    }

    /** What OK's release opens, waiting for the card to come back up. */
    Runnable pressAction;

    /**
     * Opens what was pressed once the card is back up, 50 ms after its 80 ms return as in the
     * Apple TV app: the app then grows out of the card at its focused size, with nothing on Home
     * still moving underneath. A second OK within 250 ms is the same press, not a second launch.
     */
    void afterPress(Runnable open) {
        long now = SystemClock.uptimeMillis();
        if (now - lastSelectAt < 250) return;
        lastSelectAt = now;
        handler.removeCallbacks(pressAction);
        Runnable r = () -> {
            pressAction = null;
            if (resumed) open.run();
        };
        pressAction = r;
        handler.postDelayed(r, PRESS_MS + 50);
    }

    /**
     * An app's name under its focused card, as tvOS shows it: centred when it fits; a longer one is
     * cut with a fade (never "…") and, only while its card is focused, scrolls after a 2.7 s wait
     * at 30 px a second, coming round again after a 150 px gap and waiting 2.7 s after each pass
     * (the Apple TV app's marquee). TextView's own waits 1.2 s, moves at 30 dp a second rather than
     * px, and stops after one pass. Most names fit, so they never scroll and are drawn once.
     */
    static final class Name extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        final float gap, speed, base;
        String text = "";
        float textW, offset;
        ValueAnimator run;
        final Runnable go = this::scroll;
        /** Its card is focused, so a long name may scroll. */
        boolean on;

        Name(Context c, float unit, Typeface tf) {
            super(c);
            p.setTypeface(tf);
            p.setTextSize(15 * unit);
            p.setColor(Color.WHITE);
            p.setShadowLayer(10, 0, 2, 0x99000000);
            Paint.FontMetrics fm = p.getFontMetrics();
            base = -(fm.ascent + fm.descent) / 2f;
            gap = 75 * unit;     // 150 px at 1080p
            speed = 15 * unit;   // 30 px a second at 1080p
            setHorizontalFadingEdgeEnabled(true);
            setFadingEdgeLength(Math.round(16 * unit));
        }

        void setText(String t) {
            text = t == null ? "" : t;
            textW = p.measureText(text);
            invalidate();
        }

        /**
         * Its card focused: from its start, a long name scrolls after the wait. Or not: it stops
         * where it is as it fades out.
         */
        void marquee(boolean on) {
            this.on = on;
            halt();
            if (!on) return;
            if (offset != 0f) {
                offset = 0f;
                invalidate();
            }
            postDelayed(go, 2700);
        }

        /** Stops the scroll where it is, and any wait before the next pass. */
        void halt() {
            removeCallbacks(go);
            ValueAnimator r = run;
            run = null;  // first, so the cancelled pass doesn't take itself for a finished one
            if (r != null) r.cancel();
        }

        /**
         * Home covered (an app, a page over it, the screen off): the name stops where it is, so
         * nothing scrolls unseen, waking the processor every frame under the app. Back in front, it
         * carries on from there (or waits again, if it was waiting).
         */
        @Override public void onWindowFocusChanged(boolean has) {
            super.onWindowFocusChanged(has);
            if (!on) return;
            halt();
            if (has) postDelayed(go, offset > 0f ? 0 : 2700);
        }

        /** A pass from where the name is now (its start, unless it was stopped part way). */
        void scroll() {
            float w = getWidth();
            if (w <= 0 || textW <= w) return;
            float lap = textW + gap;
            ValueAnimator a = ValueAnimator.ofFloat(offset, lap);
            a.setDuration(Math.round((lap - offset) / speed * 1000));
            a.setInterpolator(new android.view.animation.LinearInterpolator());
            a.addUpdateListener(v -> {
                offset = (float) v.getAnimatedValue();
                invalidate();
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) {
                    if (run != a) return;  // stopped (marquee), not finished
                    run = null;
                    offset = 0f;  // the copy that came round is where the name began
                    invalidate();
                    postDelayed(go, 2700);
                }
            });
            run = a;
            a.start();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            marquee(false);  // a rebuilt grid: nothing keeps running for a card that's gone
        }

        /** The scrolling name fades in at the left as it sets off and out as it comes round (0.3 s each). */
        @Override protected float getLeftFadingEdgeStrength() {
            if (offset <= 0f || textW <= getWidth()) return 0f;
            return Math.min(1f, Math.min(offset, textW + gap - offset) / (speed * 0.3f));
        }

        @Override protected float getRightFadingEdgeStrength() {
            return textW > getWidth() ? 1f : 0f;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), y = getHeight() / 2f + base;
            if (textW <= w) {
                c.drawText(text, (w - textW) / 2f, y, p);
                return;
            }
            // Cut at its own edges: the card's row doesn't clip what hangs over.
            c.save();
            c.clipRect(0, 0, w, getHeight());
            c.drawText(text, -offset, y, p);
            if (offset > 0f) c.drawText(text, textW + gap - offset, y, p);
            c.restore();
        }
    }

    /** The wallpaper, made on its own thread while the window is built. */
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
        if (hintView != null && hintView.getParent() != null) hintView.invalidate();
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

    /** tvOS-style ambient colour field: soft glows of colour on a dark base, one palette per style. */
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
        // They came with Android 8; asking about the class before then fails.
        Drawable d = Build.VERSION.SDK_INT >= 26 && e.icon instanceof android.graphics.drawable.AdaptiveIconDrawable
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
        if (Build.VERSION.SDK_INT >= 26 && d instanceof android.graphics.drawable.AdaptiveIconDrawable) {  // Android 8+
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

    boolean hasDock() {
        return !folderMode;
    }

    int dockGapBelow() {
        return dp(20);
    }

    /** Space under each grid row's label: tvOS 26's rows are about 260 px apart. */
    int rowGap() {
        return dp(26);
    }

    /** The dock (tvOS 26): clear glass with a big radius, the picture showing through, a crisp rim. */
    Drawable dockBackground() {
        // 36 px corners: Apple's dock measures 25-30 px in one study and 50-60 in another.
        LiquidGlass g = new LiquidGlass(dp(18), 0x0AFFFFFF, glassSource, screenW, screenH, unit).clear();
        g.rimStrength = 0.5f;
        return g;
    }

    /**
     * A light along a shape's true diagonal, whatever its proportions: the top-left corner at the
     * first stop, the top-right and bottom-left corners halfway, the bottom-right at the last. A
     * plain corner-to-corner gradient on a wide shape (the dock, a pill) runs almost left to right,
     * lighting its top and bottom edges alike. The box must not be empty.
     */
    static LinearGradient diagonal(RectF b, int[] colors, float[] stops) {
        float w = b.width(), h = b.height(), k = 2 * w * h / (w * w + h * h);
        return new LinearGradient(b.left, b.top, b.left + k * h, b.top + k * w, colors, stops, Shader.TileMode.CLAMP);
    }

    /**
     * A focused icon's light (tvOS 26): the specular highlight, a soft round glow centred a fifth of
     * the icon's height above its top edge at 10% white (tvOS's own values), and a bright rim round
     * the edge, strongest top-left with a second peak bottom-right, as the light falls on it. Its
     * view is invisible at rest, so resting icons don't draw it at all.
     */
    static final class Shine extends Drawable {
        final float r, w;
        final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();

        /** r: the icon's corner radius; w: the rim's width (it grows with the lift). */
        Shine(float r, float w) {
            this.r = r;
            this.w = w;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(w);
        }

        @Override protected void onBoundsChange(Rect b) {
            box.set(b);
            float bw = box.width(), bh = box.height();
            if (bw <= 0 || bh <= 0) return;
            glow.setShader(new RadialGradient(box.centerX(), box.top - bh * 0.2f, bw * 0.75f,
                    new int[]{0x1AFFFFFF, 0x0DFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            rim.setShader(diagonal(box, new int[]{0xB3FFFFFF, 0x47FFFFFF, 0x1FFFFFFF, 0x38FFFFFF, 0x8CFFFFFF},
                    new float[]{0f, 0.22f, 0.5f, 0.78f, 1f}));
        }

        @Override public void draw(Canvas c) {
            if (box.isEmpty()) return;
            c.drawRect(box, glow);
            // Inset by half its width, so the tile's rounded clip doesn't cut it.
            float in = w / 2f;
            c.drawRoundRect(box.left + in, box.top + in, box.right - in, box.bottom - in, r - in, r - in, rim);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * Liquid Glass (iOS/tvOS 26), drawn from what is actually behind the surface: a blurred,
     * colour-boosted copy of the backdrop aligned to the surface's screen position; a band along
     * the edges that magnifies it (lensing, like thick glass); a light tint; and a specular rim
     * along the light's diagonal, brightest top-left with a second peak bottom-right. No grain.
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

        LiquidGlass(float r, int tint, Backdrop backdrop, int screenW, int screenH, float unit) {
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
            rim.setStrokeWidth(1.1f * unit);
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
         * Where the surface is on screen when that's known and fixed (a menu): it opens as a scaled
         * GPU layer, and the scaled position would have it show the wrong part of the picture, then
         * jump once the layer was dropped.
         */
        int[] fixedLoc;
        /**
         * The picture through the glass: its saturation against the backdrop's (which is already
         * boosted 1.6x, tvOS's regular material), and how much of its light comes through (the
         * screen dimmed behind Control Centre or a menu, so the glass is dimmed with it).
         */
        float sat = 1f, dim = 1f;

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
         * Glass for small controls (tvOS's ultra-thin material): the picture's colour comes through
         * at 1.3x, as the status capsule takes on a poster's tone, and the tint follows the
         * brightness behind it \u2014 darker over bright artwork so white text stays legible, a light
         * frost over dark artwork so the control stays visible.
         */
        LiquidGlass adaptive() {
            adaptive = true;
            sat = 0.8f;  // 1.3x the picture's own saturation: 0.8 of the backdrop's 1.6x
            filter();
            return this;
        }

        /**
         * Glass over a screen dimmed to k (Control Centre, menus): it shows the picture as dimmed
         * as everything around it, not as a bright sheet, and picks its tint for that brightness.
         */
        LiquidGlass dimmed(float k) {
            dim = k;
            filter();
            return this;
        }

        /** The picture's saturation and dimming, set once (a colour filter costs nothing per frame). */
        void filter() {
            android.graphics.ColorMatrixColorFilter f = null;
            if (sat != 1f || dim != 1f) {
                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                cm.setSaturation(sat);
                cm.postConcat(new android.graphics.ColorMatrix(new float[]{
                        dim, 0, 0, 0, 0, 0, dim, 0, 0, 0, 0, 0, dim, 0, 0, 0, 0, 0, 1, 0}));
                f = new android.graphics.ColorMatrixColorFilter(cm);
            }
            body.setColorFilter(f);
            lens1.setColorFilter(f);
            lens2.setColorFilter(f);
            invalidateSelf();
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
            litAnim.setDuration(on ? 170 : 180);  // Glass.focus's
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
            if (box.isEmpty()) return;
            glow.setShader(new LinearGradient(0, box.top, 0, box.top + h * 0.5f,
                    0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            // Lit along the true diagonal, as measured on tvOS 26: the top-left corner brightest,
            // the top edge brighter than the bottom, a second peak on the bottom-right corner.
            rim.setShader(diagonal(box, new int[]{rimA(0xD9), rimA(0x60), rimA(0x18), rimA(0x30), rimA(0x8C)},
                    new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}));
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
            if (fixedLoc != null) {
                loc[0] = fixedLoc[0];
                loc[1] = fixedLoc[1];
            } else if (cb instanceof View) {
                ((View) cb).getLocationOnScreen(loc);
            }
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
                    int t = tintFor(luminanceBehind(b) * dim);
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

    /** A rounded-rectangle outline with corners of radius r, for clipping and the lift's shadow. */
    ViewOutlineProvider roundedOutline(float r) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
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

    /**
     * Title logo (or title), meta line, overview and "Open in \u2026" capsule, bottom-left of the
     * shelf; the page dots (bottom centre) and side chevrons for full screen.
     */
    void buildHeroInfo(FrameLayout shelf) {
        heroInfo = new LinearLayout(this);
        heroInfo.setOrientation(LinearLayout.VERTICAL);
        heroInfo.setClipChildren(false);
        heroInfo.setClipToPadding(false);
        heroInfo.setAlpha(0f);
        heroProvider = new ImageView(this);
        heroProvider.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroProvider.setOutlineProvider(roundedOutline(dp(7)));
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
        // Year, genre and rating show only in full screen: docked, tvOS shows the title alone. 80%
        // white in its colour, so at rest it needs no off-screen layer (see swipeHint).
        heroMeta = text(12.5f, Ui.font(this, "medium"), 0f);
        heroMeta.setTextColor(0xCCFFFFFF);
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
        // Room round it for the focused button's lift and shadow (at 12 the shadow's far edge was
        // cut along a line, and at 20 its faint tail still was, 2 levels brighter past the edge);
        // the margins take it back, so nothing moves.
        int room = dp(32);
        heroInfo.setPadding(room, room, room, room);
        ilp.leftMargin = side - room;
        ilp.bottomMargin = dp(40) - room;
        root.addView(heroInfo, ilp);
        heroInfoY = heroInfoRestY();
        heroInfo.setTranslationY(heroInfoY);

        // Page dots, low and centred, only in full screen (tvOS shows none while the dock is up).
        heroDots = new LinearLayout(this);
        heroDots.setOrientation(LinearLayout.HORIZONTAL);
        dotRow = new PageDots(this, unit);
        heroDots.addView(dotRow);
        heroDots.setAlpha(0f);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = dp(22);
        root.addView(heroDots, dlp);
        heroArrows = new View[]{heroArrow(-1), heroArrow(1)};

        // The shelf itself is the carousel's focus target (UP from the dock).
        shelf.setFocusable(false);
        shelf.setOnFocusChangeListener((v, has) -> heroFocused(has));
    }

    /**
     * One of full screen's side chevrons (dir -1 left, 1 right), saying Left and Right browse, as
     * tvOS's carousels do: the Apple TV app's 80 px platter of 50% black with a thin chevron. One
     * picture, drawn once; shown only while the carousel has focus.
     */
    View heroArrow(int dir) {
        int s = dp(40);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x80000000);
        c.drawCircle(s / 2f, s / 2f, s / 2f, p);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.75f * unit);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        // About 24 px tall and half as wide, its point towards the side it browses to.
        float half = dp(6), cx = s / 2f, cy = s / 2f, reach = dp(3);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(cx - dir * reach, cy - half);
        path.lineTo(cx + dir * reach, cy);
        path.lineTo(cx - dir * reach, cy + half);
        c.drawPath(path, p);
        ImageView v = new ImageView(this);
        v.setImageBitmap(b);
        v.setAlpha(0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s,
                Gravity.CENTER_VERTICAL | (dir < 0 ? Gravity.START : Gravity.END));
        lp.leftMargin = dockOuter;
        lp.rightMargin = dockOuter;
        root.addView(v, lp);
        return v;
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
        // the bottom for the dock (8% to 86%, steepest across the dock and near-black below it, as
        // on tvOS), along the top for the clock and weather (40% fading out a fifth of the way
        // down). Each darkens by a factor that depends on x or on y alone, so they're applied
        // together in one pass of integer sums over the pixels rather than as three full-size
        // gradients, which kept the processor busy through the dissolve.
        int w = b.getWidth(), h = b.getHeight();
        int[] keepX = new int[w], row = new int[w];
        for (int x = 0; x < w; x++) {
            float fx = (x + 0.5f) / w / 0.75f;
            float left = fx >= 1f ? 0f : fx < 0.5f ? 0.8f - 0.4f * fx / 0.5f : 0.4f * (1f - (fx - 0.5f) / 0.5f);
            keepX[x] = Math.round((1f - left) * 256);
        }
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) / h;
            float bottom = fy < 0.5f ? 0.078f + 0.173f * fy / 0.5f
                    : fy < 0.73f ? 0.251f + 0.2f * (fy - 0.5f) / 0.23f
                    : 0.451f + 0.409f * (fy - 0.73f) / 0.27f;
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

    /** Info block sits just above the dock while a dock app is focused. */
    float heroInfoRestY() {
        return -(screenH - shelfH) + dp(40) - dp(36);
    }

    /** Where the info block is headed: a slide change cancels its animator, so it puts it back on course. */
    float heroInfoY;

    void moveHeroInfo(float infoY, long ms, android.animation.TimeInterpolator curve) {
        heroInfoY = infoY;
        // No GPU layer: a slide alone needs none, and the button, overview and meta animating
        // inside would have it re-rendered every frame. No delay: an animator keeps its last one
        // (160 ms from setHeroShown, 30 from a title gliding in), which left the title behind the dock.
        heroInfo.animate().setStartDelay(0).translationY(infoY).setDuration(ms).setInterpolator(curve).start();
    }

    /**
     * The shelf's extras for where Home is now. Full screen (the carousel focused, or idle): the
     * meta line and page dots, and the side chevrons while it has focus. Docked: the chevron and
     * "Press up" hint above the dock, nothing else, as tvOS 26. What leaves goes at once; what
     * comes in waits delay, then fades in over ms.
     */
    void heroChrome(long delay, long ms) {
        boolean full = heroShown && (idle || shelfView.hasFocus());
        boolean docked = heroShown && !full;
        boolean arrows = full && !idle && heroItems.size() > 1;
        fadeTo(heroDots, full ? 1f : 0f, delay, ms);
        fadeTo(heroMeta, full ? 1f : 0f, delay, ms);
        // The chevron and hint ride with the apps as they slide away: gone in full screen, or
        // they'd peek in at the bottom edge. In the app grid the chevron stays, at the top. With
        // no carousel at all there's nothing up there to point at.
        fadeTo(chevron, full || heroItems.isEmpty() ? 0f : 1f, delay, ms);

        for (View v : heroArrows) fadeTo(v, arrows ? 1f : 0f, delay, Math.min(ms, 200));
    }

    /** Where each of heroChrome's fades is headed. */
    final Map<View, Float> fadeTargets = new java.util.HashMap<>();

    /**
     * Fades in after delay over ms, or out at once (over at most 160 ms). One already there or on
     * its way isn't started again: even a fade to where it is redraws, and the meta line sits in
     * the title's block, which is a GPU layer while a title glides in.
     */
    void fadeTo(View v, float to, long delay, long ms) {
        Float was = fadeTargets.put(v, to);
        if (was != null ? was == to : v.getAlpha() == to) return;
        boolean out = to < v.getAlpha();
        v.animate().alpha(to).setStartDelay(out ? 0 : delay).setDuration(out ? Math.min(ms, 160) : ms)
                .setInterpolator(EASE).start();
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

    /**
     * Loads cached suggestions at once and, when they're stale or reconfigured and the internet is
     * there (online), refreshes them in the background.
     */
    void loadHero(boolean online) {
        boolean shelf = Suggestions.showRecs(this) && Suggestions.ready(this);
        if (!shelf) {
            heroItems = new ArrayList<>();
            shelfPkg = null;  // no app's own shelf left for a refresh to bring back
            shelfView.setFocusable(false);
            setHeroShown(false);
            reportDrawn();  // no carousel: Home is complete with its dock
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
            // Nothing saved yet, or out of date: fetched on the refresh's own lane. It's many
            // requests and picture checks, and here it held up every carousel picture meanwhile.
            // What's showing is never cleared for a list that couldn't be read this time, so the
            // carousel can't go blank. Out of date is told from the file's age, not a second read.
            boolean stale = base == null || System.currentTimeMillis()
                    - Suggestions.cacheFile(this).lastModified() > Suggestions.MAX_AGE_MS;
            if (online && !fetching && stale) {
                fetching = true;
                net.execute(() -> {
                    try {
                        // Queued behind another Home's refresh (after a recreate): just its result.
                        List<Suggestions.Item> fresh = Suggestions.cached(this, false);
                        if (fresh == null) fresh = Suggestions.fetch(this);
                        Suggestions.byService(this);  // the new shelves read here, not when a dock app is next focused
                        List<Suggestions.Item> items = fresh;
                        handler.post(() -> setHeroBase(items));
                    } catch (Exception e) {
                        // Its kind only: a failed request's message carries its URL, and with it a TMDB key.
                        android.util.Log.w("GlassHome", "suggestions: " + e.getClass().getSimpleName());
                    } finally {
                        fetching = false;
                    }
                });
            }
        });
    }

    /** Home has reported itself fully drawn: the dock and the first picture are up. */
    boolean drawnReported;

    /**
     * Tells Android that start-up is over: logcat shows the real time to a complete Home ("Fully
     * drawn"), and ART's profile marks what ran until now as start-up code.
     */
    void reportDrawn() {
        if (drawnReported) return;
        drawnReported = true;
        reportFullyDrawn();
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
        // Top Shelf turned off: no app's titles either (their saved shelves are still on disk).
        if (!Suggestions.showRecs(this) || !Suggestions.ready(this)) return;
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
        // Apple's carousels hold at most 8 titles (and so 8 dots): every shelf, the mix's limit.
        if (items.size() > Suggestions.MAX_ITEMS) items = new ArrayList<>(items.subList(0, Suggestions.MAX_ITEMS));
        heroItems = items;
        int keep = 0;
        for (int i = 0; curTitle != null && i < items.size(); i++) if (items.get(i).title.equals(curTitle)) keep = i;
        heroIndex = keep;
        shelfView.setFocusable(!items.isEmpty());
        int dots = items.size() > 1 ? items.size() : 0;
        if (dots != dotPages) {
            // A different number of pages (another app's shelf): the old row fades out and the new
            // one fades in, instead of the dots jumping as the row re-centres. Hidden (docked, where
            // most shelf changes happen) it's simply swapped. The count is read when the fade ends,
            // so a shelf switched away from and back meanwhile still gets its own.
            dotPages = dots;
            dotRow.animate().cancel();
            if (dotRow.pages() == 0 || heroDots.getAlpha() == 0f) {
                dotRow.setPages(dots, keep);
                dotRow.setAlpha(1f);
            } else {
                dotRow.animate().alpha(0f).setStartDelay(0).setDuration(140).setInterpolator(EASE).withEndAction(() -> {
                    dotRow.setPages(dotPages, heroIndex);
                    dotRow.animate().alpha(1f).setDuration(260).setInterpolator(EASE).start();
                }).start();
            }
        }
        if (items.isEmpty()) {
            setHeroShown(false);
            if (!idle) heroChrome(0, 200);
            return;
        }
        // The carousel shows unless focus is down in the app grid: a list refreshed while the
        // status row (gear, weather) or nothing had focus used to hide it behind the drawer's glass.
        View f = getCurrentFocus();
        int i = tiles.indexOf(f);
        boolean inGrid = i >= 0 && !(hasDock() && i < COLS);
        setHeroShown(f == null ? heroShown || tiles.isEmpty() || focusedIndex < COLS : !inGrid);
        // The side chevrons, for a shelf refreshed to one title or to more. Not going idle, which
        // switches to the mix on its way and brings the extras up on its own, slower timing.
        if (!idle) heroChrome(0, 200);
        if (!(same && items.get(keep).title.equals(curTitle))) showHero(keep, heroDissolve && heroShown ? 2 : 0);
        else dotRow.select(keep, false);
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
                // Fully covered: the pictures and the wallpaper needn't be drawn underneath. Only
                // the newest picture is kept, for the way back; a dissolve cut short here would
                // otherwise leave the older ones held behind every app.
                for (ImageView v : heroLayers()) {
                    if (v == heroFront) hideHeroLayer(v);
                    else releaseHeroLayer(v);
                }
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

        // Leaving for the apps: the title and its extras go at once, before the rows slide over
        // them; coming back: they return after the artwork has settled. Back in line too, should
        // a title change's glide have been cut short.
        flushHeroInfo();
        heroInfo.animate().cancel();
        heroInfo.animate().withLayer().alpha(a).translationX(0).translationY(heroInfoY).setStartDelay(show ? 160 : 0)
                .setDuration(show ? 420 : 140).setInterpolator(EASE).start();
        // Over the carousel the chevron rises to make room for its hint; in the grid it's alone.
        chevron.animate().translationY(0).setStartDelay(show ? 160 : 0).setDuration(show ? 420 : 140)
                .setInterpolator(EASE).start();
        heroChrome(show ? 160 : 0, show ? 420 : 140);
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
        // Not while a card is being moved: the picture holds still, as tvOS's top shelf does in edit
        // mode, and each step's grid rebuild doesn't share its frames with a dissolve.
        if (sec > 0 && heroShown && resumed && switcher == null && moveIndex < 0) handler.postDelayed(heroAdvance, sec * 1000L);
    }

    void heroStep(int dir, boolean user) {
        if (heroItems.size() < 2) return;
        // Browsing moves no focus, so the focus tick is played here for a press of Left or Right.
        if (user) Clicks.move(this, 0.5f, fastMove);
        showHero((heroIndex + dir + heroItems.size()) % heroItems.size(), dir);
        if (!user || !shelfView.hasFocus()) scheduleAdvance();
    }

    /**
     * tvOS's page control, as the Apple TV app draws it: 10 px dots 12 px apart in white at 25%,
     * the current page a 30 px white capsule, on a capsule of 40% black. A page change narrows the
     * old capsule as the new one widens, together over 280 ms, so the row keeps its width. One
     * view, drawn as true anti-aliased shapes (a scaled bitmap came out as rough diamonds on this
     * TV), and redrawn only during a change: no timer fill, so nothing moves while a slide is up.
     */
    static final class PageDots extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), back = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final float dot, gap, wide, padX, padY;
        /** How far each page's dot is widened into the capsule: 0 a dot, 1 the current page. */
        float[] open = new float[0];
        int current = -1;
        ValueAnimator anim;

        PageDots(Context c, float unit) {
            super(c);
            dot = 5 * unit;
            gap = 6 * unit;
            wide = 15 * unit;
            padX = 7.5f * unit;
            padY = 4 * unit;
            p.setColor(Color.WHITE);
            back.setColor(0x66000000);
        }

        int pages() {
            return open.length;
        }

        /** n pages (fewer than two show nothing), the current one at once. */
        void setPages(int n, int index) {
            if (anim != null) anim.cancel();
            open = new float[n];
            current = index >= 0 && index < n ? index : -1;
            if (current >= 0) open[current] = 1f;
            requestLayout();
            invalidate();
        }

        void select(int index, boolean animate) {
            if (index < 0 || index >= open.length || index == current) return;
            current = index;
            if (anim != null) anim.cancel();
            if (!animate) {
                java.util.Arrays.fill(open, 0f);
                open[index] = 1f;
                invalidate();
                return;
            }
            // From wherever a change cut short left them, so a quick press never jumps.
            float[] from = open.clone();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(280);
            anim.setInterpolator(new android.view.animation.LinearInterpolator());
            anim.addUpdateListener(v -> {
                if (open.length != from.length) return;
                float t = v.getAnimatedFraction();
                for (int i = 0; i < open.length; i++) open[i] = from[i] + ((i == index ? 1f : 0f) - from[i]) * t;
                invalidate();
            });
            anim.start();
        }

        @Override protected void onMeasure(int ws, int hs) {
            int n = open.length;
            if (n < 2) {
                setMeasuredDimension(0, 0);
                return;
            }
            setMeasuredDimension(Math.round(n * dot + (n - 1) * gap + wide - dot + 2 * padX), Math.round(dot + 2 * padY));
        }

        @Override protected void onDraw(Canvas c) {
            if (open.length < 2) return;
            float h = getHeight();
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, h / 2f, h / 2f, back);
            float x = padX;
            for (float o : open) {
                float w = dot + (wide - dot) * o;
                p.setAlpha(Math.round((0.25f + 0.75f * o) * 255));
                r.set(x, padY, x + w, padY + dot);
                c.drawRoundRect(r, dot / 2f, dot / 2f, p);
                x += w + gap;
            }
        }
    }

    /**
     * Counts showHero's requests: a picture asked for before a newer one is dropped (a slide you
     * pressed past, or the shelf of the app you were on a moment ago), not decoded or shown.
     */
    volatile int heroReq;
    /** The title the carousel shows, for going back to it when the next picture can't be had. */
    String heroOnScreen;

    /** dir: +1 next, -1 previous, 0 first show (no slide), 2 another shelf (dissolve in place). */
    void showHero(int index, int dir) {
        showHero(index, dir, 0);
    }

    /** misses: titles this step has already passed over, their pictures not to be had. */
    void showHero(int index, int dir, int misses) {
        int req = ++heroReq;
        heroIndex = index;
        Suggestions.Item it = heroItems.get(index);
        dotRow.select(index, true);
        io.execute(() -> {
            if (req != heroReq) return;
            // Asked again between each step: a picture pressed past mid-way stops there, rather
            // than its shading, blurs and upload holding up the one now wanted (and taking the
            // processor from the dissolve under way).
            Bitmap raw = Suggestions.image(this, it.backdrop, "w1280", screenW);
            if (req != heroReq) return;
            Bitmap bd = bakeScrims(raw);
            if (req != heroReq) return;
            Bitmap drawer = drawerGlass(bd);
            Bitmap bdBlur = Ui.glassBackdrop(bd, 160, 90);
            if (req != heroReq) return;
            // On the GPU now, in the background, so the dissolve doesn't stall on the upload.
            Bitmap shown = Suggestions.onGpu(bd);
            if (bdBlur != null) bdBlur.prepareToDraw();
            if (drawer != null) drawer.prepareToDraw();
            // A title passed over for want of a picture needn't have its logo fetched either.
            String logoPath = shown == null && dir != 0 ? "" : Suggestions.logoFor(this, it);
            Bitmap logo = Suggestions.onGpu(logoPath.isEmpty() ? null : Suggestions.image(this, logoPath, "w500", 500));
            handler.post(() -> {
                if (req != heroReq || heroItems.isEmpty()) return;
                if (shown == null && dir != 0) {
                    // No picture (offline, or a bad file): never one show's title over another's
                    // picture. The step goes on to the next title, as if this one weren't there,
                    // so one bad picture can't hold the carousel up; when none will load (offline)
                    // the slide on screen stays, and the next step tries again.
                    int n = heroItems.size(), step = dir == -1 ? -1 : 1;
                    if (misses + 2 < n) {
                        showHero(((index + step) % n + n) % n, dir, misses + 1);
                        return;
                    }
                    for (int i = 0; i < n; i++) {
                        if (heroItems.get(i).title.equals(heroOnScreen)) {
                            heroIndex = i;
                            dotRow.select(i, true);
                            return;
                        }
                    }
                    // A shelf just switched to has none of its titles on screen: its first goes up
                    // over the picture there, so OK still opens the title you can see.
                    int first = ((index - misses * step) % n + n) % n;
                    heroIndex = first;
                    dotRow.select(first, true);
                    heroOnScreen = heroItems.get(first).title;
                    applyHeroInfo(heroItems.get(first), null, dir);
                    return;
                }
                heroOnScreen = it.title;
                applyHeroInfo(it, logo, dir);
                if (shown != null) {
                    crossfadeHero(shown, dir);
                    reportDrawn();
                }
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
        // Full screen keeps its own: no capsule, and every title's summary (see enterIdle).
        if (shelfView.isFocused() && !idle) {
            heroButton.setVisibility(View.VISIBLE);
            heroButton.setAlpha(1f);
        }
        if (!shelfView.isFocused() && !idle) {  // tvOS: the overview shows only when the shelf is focused
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
        meta.add(it.type.equals("tv") ? "Series" : "Film");
        if (!it.genres.isEmpty()) meta.add(it.genres);
        if (it.rating > 0) meta.add(String.format(java.util.Locale.US, "\u2605 %.1f", it.rating));
        heroMeta.setText(String.join("  \u00B7  ", meta));
        heroOverview.setText(it.overview);
        if (idle) {
            // After the text, so it follows the new title's (some have no summary).
            heroOverview.setVisibility(heroOverview.getText().length() > 0 ? View.VISIBLE : View.GONE);
            heroOverview.setAlpha(0.85f);
        }
        Suggestions.Provider p = it.provider();
        // Not installed: Get and the app's name, as Apple's offers say (no badge names it then).
        heroBtnText.setText(p != null && !installed(p.pkg) ? "Get " + p.name : "\u25B6  Open");
        // The service's own app icon as its badge.
        Drawable appIcon = null;
        String badge = p != null ? p.pkg : null;
        if (badge != null && installed(badge)) appIcon = badgeIcon(badge);
        heroProvider.setImageDrawable(appIcon);
        heroProvider.setVisibility(appIcon == null ? View.GONE : View.VISIBLE);
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
                releaseHeroLayer(old);
                releaseHeroLayer(heroUnder);
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
                    // Fully over: what's underneath needn't be drawn, nor kept.
                    for (ImageView v : heroLayers()) if (v != next) releaseHeroLayer(v);
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

    /**
     * Hidden, and its picture let go: a hidden layer's picture is never shown again (the next
     * dissolve brings a new one), and each holds a full-screen picture's graphics memory, behind
     * every app too. Not recycled, as the render thread may still be drawing it for a frame.
     */
    void releaseHeroLayer(ImageView v) {
        hideHeroLayer(v);
        v.setImageDrawable(null);
    }

    /**
     * Home itself moved focus to the carousel (Up from the dock, Down from the status row or
     * weather): focus Android parks there on its own is handed back to the dock.
     */
    boolean heroRequested;

    ImageView ccButton, netIcon, timerIcon;
    /** The clock pill: the time, with any status glyphs before it. */
    LinearLayout timeChip;
    android.net.ConnectivityManager.NetworkCallback netCallback;
    WeatherView weatherCard;
    LiquidGlass weatherGlass;
    /** The full forecast while it's open over Home. */
    FrameLayout forecastLayer;

    /** Carousel focused: the dock slides away and the artwork takes the whole screen (tvOS Top Shelf). */
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
            // No delay: the animator still holds the last fade's.
            heroButton.animate().scaleX(1.08f).scaleY(1.08f).setStartDelay(0).setDuration(420).setInterpolator(LIFT).start();
            heroButton.setElevation(dp(10));
            scrollToY(0, true);
            // Slid as it is, not as a GPU layer: the dock card settling inside it would have the
            // whole grid re-rendered into the layer every frame (the dock's glass has its own).
            scroll.animate().translationY(screenH - shelfH + dp(30)).setDuration(500).setInterpolator(EASE)
                    .setUpdateListener(null).withEndAction(this::invalidateGlass).start();
            moveHeroInfo(0, 500, EASE);
            heroOverview.setVisibility(View.VISIBLE);
            heroButton.setVisibility(View.VISIBLE);
            heroOverview.animate().alpha(0.85f).setDuration(400).setStartDelay(120).setInterpolator(EASE).start();
            heroButton.animate().alpha(1f).setDuration(400).setStartDelay(120).setInterpolator(EASE).start();
            heroChrome(120, 400);
            handler.removeCallbacks(heroAdvance);
        } else {
            heroButton.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(240).setInterpolator(EASE).start();
            heroButton.setElevation(0);
            scroll.animate().translationY(0).setDuration(450).setInterpolator(EASE)
                    .setUpdateListener(null).withEndAction(this::invalidateGlass).start();
            moveHeroInfo(heroInfoRestY(), 450, EASE);
            heroOverview.animate().alpha(0f).setDuration(160).setStartDelay(0).setInterpolator(EASE)
                    .withEndAction(() -> heroOverview.setVisibility(View.GONE)).start();
            heroButton.animate().alpha(0f).setDuration(160).setStartDelay(0).setInterpolator(EASE)
                    .withEndAction(() -> heroButton.setVisibility(View.GONE)).start();
            heroChrome(120, 400);  // the chevron and hint come back up with the dock
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
        heroBtnAnim.setDuration(on ? 170 : 180);
        heroBtnAnim.setInterpolator(EASE);
        heroBtnAnim.addUpdateListener(a -> {
            heroBtnT = (float) a.getAnimatedValue();
            heroBtnBg.setColor((int) ev.evaluate(heroBtnT, 0x47FFFFFF, 0xFFFFFFFF));
            heroBtnText.setTextColor((int) ev.evaluate(heroBtnT, Color.WHITE, 0xFF111114));
        });
        heroBtnAnim.start();
    }

    /**
     * Home's own arrows: on the carousel Left/Right browse, OK opens, Up goes to the status row,
     * Down/Back to the dock; the status row and weather card; and a bump at every wall.
     */
    boolean heroKeys(KeyEvent ev) {
        if (moveIndex >= 0) return false;
        int k = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        View f = getCurrentFocus();
        boolean heroActive = shelfView.isFocusable() && !folderMode;
        // A first press into a wall (held arrows don't keep bumping).
        boolean wall = down && ev.getRepeatCount() == 0;
        if (f == shelfView) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (down) heroStep(-1, true); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) heroStep(1, true); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    // Only a press that started here: the release of an OK that woke the screen
                    // saver isn't one (it would open the title). The capsule sinks to half its
                    // focus growth as OK goes down and comes back up on release, then the title
                    // opens, as a card does.
                    if (down && ev.getRepeatCount() == 0) {
                        okDownAt = ev.getDownTime();
                        heroButton.animate().scaleX(1.04f).scaleY(1.04f).setStartDelay(0).setDuration(PRESS_MS)
                                .setInterpolator(EASE_OUT_QUAD).start();
                    }
                    if (!down && ev.getDownTime() == okDownAt) {
                        okDownAt = 0;
                        heroButton.animate().scaleX(1.08f).scaleY(1.08f).setStartDelay(0).setDuration(PRESS_MS)
                                .setInterpolator(EASE_IN_QUAD).start();
                        if (!ev.isCanceled() && !heroItems.isEmpty()) {
                            // The title on screen: heroIndex moves on as soon as the next picture
                            // is asked for, while that picture may still be loading.
                            Suggestions.Item it = heroItems.get(Math.min(heroIndex, heroItems.size() - 1));
                            for (Suggestions.Item c : heroItems) {
                                if (c.title.equals(heroOnScreen)) {
                                    it = c;
                                    break;
                                }
                            }
                            Suggestions.Item open = it;
                            afterPress(() -> openHeroItem(open));
                        }
                    }
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
                else if (wall) bump(ccButton, -1, 0);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_BACK) {
                if (down) focusBelowStatus();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (wall) bump(ccButton, k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0, k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 0);
                return true;
            }
            return false;
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
                    if (wall) bump(weatherCard, k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0, k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 0);
                    return true;
                default: return false;
            }
        }
        int i = tiles.indexOf(f);
        if (i >= 0 && ((k == KeyEvent.KEYCODE_DPAD_RIGHT && (i % COLS == COLS - 1 || i == tiles.size() - 1))
                || (k == KeyEvent.KEYCODE_DPAD_LEFT && i % COLS == 0))) {
            if (wall) bump(f, k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, 0);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN && i >= 0 && i / COLS == (tiles.size() - 1) / COLS) {
            if (wall) bump(f, 0, 1);  // the last row: nothing below
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_UP && i >= 0 && i < COLS) {
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

    /**
     * A first press into a wall on Home (a row's end, the last row, the status row's edges): the
     * focused control leans 12 px that way and springs back, as the App Switcher does at its ends,
     * with no tick (nothing moved). Only its position moves, so a lift under way carries on.
     */
    void bump(View v, int dx, int dy) {
        v.animate().translationX(dx * dp(6)).translationY(dy * dp(6)).setStartDelay(0).setDuration(110)
                .setInterpolator(EASE)
                .withEndAction(() -> v.animate().translationX(0).translationY(0).setDuration(420)
                        .setInterpolator(Glass.SNAP).start())
                .start();
    }

    /** When the OK pressed on the carousel went down: only that press's release opens the title. */
    long okDownAt;

    void openHeroItem(Suggestions.Item it) {
        Suggestions.Provider p = it.provider();
        String pkg = p != null && installed(p.pkg) ? p.pkg : null;
        Suggestions.open(this, it);
        if (pkg == null) return;  // its Play Store page (or nothing): not an app to come back from
        // Like an app opened from its card: Home's refresh waits for the return animation, and
        // pressing Home in the app comes back to that app's card (the one that played the title),
        // else to the dock app whose shelf it was on. A return's fallback still waiting would
        // clear that before the new app is left.
        appOpened = true;
        handler.removeCallbacks(returnRun);
        openedKey = null;
        for (AppEntry e : shown) {
            if (e.key.startsWith(pkg + "/")) {
                openedKey = e.key;
                break;
            }
        }
        if (openedKey == null && shelfPkg != null && focusedIndex >= 0 && focusedIndex < shown.size()) {
            openedKey = shown.get(focusedIndex).key;
        }
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
            handler.removeCallbacks(returnRun);  // the last return's fallback would clear appOpened
            // Nothing new on Home under the app as it grows: a shelf change or the next slide
            // would start a dissolve (and its picture's decode) in the launch's frames.
            handler.removeCallbacks(shelfUpdate);
            handler.removeCallbacks(heroAdvance);
        } catch (ActivityNotFoundException | SecurityException e) {
            hint("Can\u2019t open " + a.label, false);
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
        scrollToRow(index / COLS, !snapFocus);
        handler.removeCallbacks(shelfUpdate);
        handler.postDelayed(shelfUpdate, 260);
    }

    /**
     * Row tops come from the fixed grid geometry rather than getTop(), so this also works right
     * after buildGrid() (e.g. while moving a card), before the new rows have been laid out.
     */
    void scrollToRow(int row, boolean animate) {
        int rowH = tileH + labelH + rowGap();
        int rowTop = !hasDock() ? shelfH + row * rowH
                : row == 0 ? shelfH
                : shelfH + tileH + 2 * dockPadV + dockGapBelow() + (row - 1) * rowH;
        scrollToY(row == 0 ? 0 : rowTop - (int) (screenH * 0.30f), animate);
    }

    /**
     * The grid glides to y on the Apple TV app's scroll curve (or jumps there). A new row asked for
     * on the way carries on from where the glide has got to, and the same one asked again (focusIndex
     * asks twice) leaves it running. ScrollView's own smooth scroll can't: a second call within
     * 250 ms jumps straight to the end, which made every return to the top a cut.
     */
    void scrollToY(int y, boolean animate) {
        // Just after a rebuild the content's height is stale until the next layout: then only the
        // top is certain. scrollTo keeps every frame in range, and the call after layout corrects it.
        int max = content.isLayoutRequested() ? Integer.MAX_VALUE : Math.max(0, content.getHeight() - scroll.getHeight());
        y = Math.max(0, Math.min(y, max));
        if (animate && rowScroll != null && rowScroll.isRunning() && y == rowScrollTarget) return;
        if (rowScroll != null) rowScroll.cancel();
        rowScroll = null;
        rowScrollTarget = y;
        int from = scroll.getScrollY();
        if (!animate || y == from) {
            scroll.scrollTo(0, y);
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(from, y);
        a.setDuration(700);
        a.setInterpolator(SCROLL);
        a.addUpdateListener(v -> scroll.scrollTo(0, (int) v.getAnimatedValue()));
        rowScroll = a;
        a.start();
    }

    void focusIndex(int i) {
        focusIndex(i, true);
    }

    /** Focus on card i, with its row brought into view: gliding there, or at once. */
    void focusIndex(int i, boolean animate) {
        if (tiles.isEmpty()) return;
        i = Math.max(0, Math.min(i, tiles.size() - 1));
        tiles.get(i).requestFocus();
        focusedIndex = i;
        int row = i / COLS;
        scroll.post(() -> scrollToRow(row, animate));
    }

    void goTop() {
        scrollToY(0, true);
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

    /**
     * The More Apps card while there are hidden apps (null when there are none), and the apps it
     * shows. It's drawn in partition, when they change, never on a rebuild of the grid: move mode
     * rebuilds it at every step.
     */
    AppEntry folder;
    List<AppEntry> folderShows = new ArrayList<>();
    static final int FOLDER_SHOWS = 6;

    AppEntry folderEntry() {
        AppEntry f = new AppEntry();
        f.key = FOLDER_KEY;
        f.label = "More Apps";
        f.color = 0xFF3A3A3C;
        f.banner = new BitmapDrawable(getResources(), folderArt());
        f.icon = f.banner;
        f.tileArt = tileArt(f.banner);
        return f;
    }

    /**
     * tvOS 26's folder card: light frosted glass, an icon's size, showing the apps inside as small
     * cards from its top-left, three across (up to six here), no words.
     */
    Bitmap folderArt() {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF6A6C74, 0xFF44464E, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h / 2f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h / 2f, p);
        // 168x101 (the cards' 5:3, with their corner), 28 apart: 40 in from the sides, 65 from the top.
        int mw = 168, mh = 101, gapPx = 28;
        RectF r = new RectF();
        android.graphics.Matrix m = new android.graphics.Matrix();
        for (int i = 0; i < folderShows.size(); i++) {
            AppEntry a = folderShows.get(i);
            float x = 40 + (i % 3) * (mw + gapPx), y = 65 + (i / 3) * (mh + gapPx);
            r.set(x, y, x + mw, y + mh);
            if (a.tileArt != null) {
                android.graphics.BitmapShader art = new android.graphics.BitmapShader(a.tileArt,
                        Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                m.setRectToRect(new RectF(0, 0, a.tileArt.getWidth(), a.tileArt.getHeight()), r,
                        android.graphics.Matrix.ScaleToFit.FILL);
                art.setLocalMatrix(m);
                p.setShader(art);
            } else {
                p.setShader(null);
                p.setColor(a.color);
            }
            c.drawRoundRect(r, 18, 18, p);
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
            scrollToY(0, false);
            focusIndex(0);
        });
    }

    void exitFolder() {
        animateGridSwap(() -> {
            folderMode = false;
            weatherCard.animate().alpha(weatherAlpha()).setDuration(250).setInterpolator(EASE).start();
            buildGrid();
            // Under the zoom back in: the folder's card is already up and its row in view.
            int i = indexOfKey(FOLDER_KEY);
            snapFocus = true;
            focusIndex(i >= 0 ? i : 0, false);
            snapFocus = false;
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
        int k = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        if (down && k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Held arrows, paced: Android repeats every 50 ms, faster than tvOS moves and than this
            // TV can draw a lift and a scroll. A hold repeats from 0.3 s in (tvOS's repeat start),
            // then one move per 100 ms; on the carousel one picture per 450 ms (each is a decode
            // and a dissolve), and one step per 250 ms when moving a card (each rebuilds the grid).
            // A single press is never held back.
            long t = ev.getEventTime();
            long pace = moveIndex >= 0 ? 250 : getCurrentFocus() == shelfView ? 450 : 90;
            if (ev.getRepeatCount() > 0 && (t - ev.getDownTime() < 300 || t - lastArrowAt < pace)) return true;
            fastMove = ev.getRepeatCount() > 0 || t - lastArrowAt < 180;
            lastArrowAt = t;
            arrowAt = SystemClock.uptimeMillis();
        }
        // No tick here for arrows: Home's comes from the focus change itself (see buildChrome).
        navSound(this, ev, false);
        if (switcher != null) {
            int was = switcher.sel;
            boolean used = switcher.key(ev);
            // The App Switcher moves no focus, so its tick is played here, when its row moved.
            if (switcher != null && switcher.sel != was) Clicks.move(this, 0.5f, fastMove);
            return used || super.dispatchKeyEvent(ev);
        }
        if (moveIndex >= 0) {
            // A card being moved has the arrows, OK and Back before anything else: a menu or
            // focus left behind (one closing as move mode began) can't keep them from it.
            if (menuLayer != null) closeMenu();
            View mf = getCurrentFocus();
            if (moveIndex < tiles.size() && mf != tiles.get(moveIndex)) focusIndex(moveIndex, false);
            boolean first = ev.getRepeatCount() == 0;
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:   if (down) moveTo(moveIndex - 1, first); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:  if (down) moveTo(moveIndex + 1, first); return true;
                case KeyEvent.KEYCODE_DPAD_UP:     if (down) moveTo(moveIndex - COLS, first); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:   if (down) moveTo(moveIndex + COLS, first); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:      if (down) endMove(); return true;
                default: return super.dispatchKeyEvent(ev);
            }
        }
        if (forecastLayer != null && menuLayer == null) {
            int pk = ev.getKeyCode();
            boolean pd = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (pk == KeyEvent.KEYCODE_BACK) {
                if (!pd) closePopover();
                return true;
            }
            if (pk == KeyEvent.KEYCODE_DPAD_UP || pk == KeyEvent.KEYCODE_DPAD_DOWN
                    || pk == KeyEvent.KEYCODE_DPAD_LEFT || pk == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // The forecast keeps focus while it's open; should focus have slipped off it, an
                // arrow puts it back rather than moving something hidden behind it.
                View panel = forecastLayer.getChildCount() > 1 ? forecastLayer.getChildAt(1) : null;
                if (pd && panel != null && !panel.isFocused()) panel.requestFocus();
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
        // An alert over Home (Perms.alert, outside root) has focus: its buttons and its Back.
        View cf = getCurrentFocus();
        if (cf != null && !inside(cf, root)) return super.dispatchKeyEvent(ev);
        if (heroKeys(ev)) return true;
        if (k == KeyEvent.KEYCODE_MENU) {
            // Only a press that started here, as with Back: the release of a Menu that woke the
            // screen saver isn't one.
            if (down && ev.getRepeatCount() == 0) menuDownAt = ev.getDownTime();
            if (!down && ev.getDownTime() == menuDownAt) {
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
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            // OK on a card: it sinks as the key goes down, and comes back up on release (the
            // click, which then opens it) or when the press is called off.
            View f = getCurrentFocus();
            if (f != null && tiles.contains(f)) {
                if (down && ev.getRepeatCount() == 0) press(f);
                else if (!down && ev.isCanceled()) rise(f);
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    /** When the Back (or Menu) being pressed went down. */
    long backDownAt, menuDownAt;

    /** Whether v is group or somewhere inside it. */
    static boolean inside(View v, View group) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == group) return true;
        return false;
    }

    // ---------------------------------------------------------------- apps

    /**
     * Finds every app and draws its card. It touches nothing of Home's but its own result, so it
     * runs off the main thread. A card in prev whose app hasn't changed (same file, same name) is
     * kept as it is: an app's update redraws its own card, not every card on Home.
     */
    Map<String, AppEntry> scan(Map<String, AppEntry> prev) {
        Map<String, AppEntry> found = new LinkedHashMap<>();
        PackageManager pm = getPackageManager();
        Set<String> pkgs = new HashSet<>();
        List<AppEntry> fresh = new ArrayList<>();
        addResolved(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, pkgs, true, found, prev, fresh);
        addResolved(pm, Intent.CATEGORY_LAUNCHER, pkgs, false, found, prev, fresh);
        if (!pkgs.contains(TV_SETTINGS)) addSettingsEntry(pm, found, prev, fresh);
        if (Inputs.showCard(this) && !Inputs.list(this).isEmpty()) addInputsEntry(found, prev, fresh);
        // Each app's banner and icon are loaded and its card drawn from its biggest art, about 60 ms
        // an app on this TV: three at a time, so a cold start waits about half a second for
        // twenty-four apps rather than a second and a half (Home first appeared after 2.1 s, now 1.2).
        if (fresh.size() < 3) {
            for (AppEntry e : fresh) drawCard(pm, e);
        } else {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (AppEntry e : fresh) jobs.add(pool.submit(() -> drawCard(pm, e)));
            for (java.util.concurrent.Future<?> j : jobs) {
                try {
                    j.get();
                } catch (Exception ignored) {
                    // that app's card keeps its plain banner
                }
            }
            pool.shutdown();
        }
        return found;
    }

    /** One app's card art (scan), from its highest-resolution banner or icon; or as saved, when it is. */
    void drawCard(PackageManager pm, AppEntry e) {
        if (e.from != null) {
            ActivityInfo ai = e.from.activityInfo;
            if (e.icon == null) e.icon = e.from.loadIcon(pm);
            e.color = tintFrom(e.icon);
            e.from = null;
            if (openCard(e)) return;
            e.banner = e.bannerRes != 0 ? ai.loadBanner(pm) : null;
        }
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
        e.drawn = zoom || card;
    }

    // ---------------------------------------------------------------- saved cards

    /**
     * Each app's card, saved once drawn: a cold start (the TV switching on from off, or Home
     * restarting) opens it in a few milliseconds instead of drawing it again from the app's art,
     * about 60 an app, so Home is up sooner. Saved under the app's file and date, its name, the tile's
     * size and this build of Home: anything that would draw it differently finds none, and draws.
     */
    File cardFile(AppEntry e) {
        if (e.stamp == null) return null;  // Home's own cards (Settings, Inputs) are drawn each time
        if (homeBuild == 0) {
            try {
                homeBuild = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            } catch (PackageManager.NameNotFoundException ex) {
                homeBuild = 1;
            }
        }
        String id = e.stamp + "\n" + e.label + "\n" + tileW + "x" + tileH + "\n" + homeBuild;
        return new File(new File(getCacheDir(), "cards"), e.key.replaceAll("[^A-Za-z0-9._-]", "_")
                + "-" + Integer.toHexString(id.hashCode()) + ".card");
    }

    /** When this copy of Home was installed: a new build draws every card afresh. */
    static volatile long homeBuild;

    /** The app's card as saved (cardFile), when there is one: its tile, and its drawn card if Home drew it. */
    boolean openCard(AppEntry e) {
        File f = cardFile(e);
        if (f == null || !f.exists()) return false;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            Bitmap tile = picture(in), drawn = in.readBoolean() ? picture(in) : null;
            if (tile == null) throw new java.io.IOException("unreadable");
            tile.setHasMipMap(true);
            tile.prepareToDraw();
            e.tileArt = tile;
            e.drawn = drawn != null;
            // A card Home drew is the banner it shows everywhere (the App Switcher too); an app's own
            // banner is fetched afresh where it's wanted bigger, so its tile stands in for it.
            e.banner = new BitmapDrawable(getResources(), drawn != null ? drawn : tile);
            return true;
        } catch (Exception ex) {
            f.delete();
            return false;
        }
    }

    static Bitmap picture(java.io.DataInputStream in) throws java.io.IOException {
        byte[] b = new byte[in.readInt()];
        in.readFully(b);
        return android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
    }

    static void picture(java.io.DataOutputStream out, Bitmap b) throws java.io.IOException {
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream(64 << 10);
        b.compress(Bitmap.CompressFormat.PNG, 100, png);
        out.writeInt(png.size());
        png.writeTo(out);
    }

    /**
     * The cards not saved yet, saved a while after they were drawn, on a background lane (never in
     * start-up's way); the saved cards of apps gone or changed are deleted.
     */
    final Runnable saveCards = () -> {
        List<AppEntry> list = new ArrayList<>(all.values());
        scans.execute(() -> {
            File dir = new File(getCacheDir(), "cards");
            dir.mkdirs();
            Set<String> keep = new HashSet<>();
            for (AppEntry e : list) {
                File f = cardFile(e);
                if (f == null || e.tileArt == null || e.tileArt.isRecycled()) continue;
                keep.add(f.getName());
                if (f.exists()) continue;
                Drawable b = e.drawn ? e.banner : null;
                Bitmap drawn = b instanceof BitmapDrawable ? ((BitmapDrawable) b).getBitmap() : null;
                File tmp = new File(dir, f.getName() + ".tmp");
                try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
                    picture(out, e.tileArt);
                    out.writeBoolean(drawn != null);
                    if (drawn != null) picture(out, drawn);
                } catch (Exception ex) {
                    tmp.delete();
                    continue;
                }
                if (!tmp.renameTo(f)) tmp.delete();
            }
            File[] old = dir.listFiles();
            for (File f : old == null ? new File[0] : old) if (!keep.contains(f.getName())) f.delete();
        });
    };

    /** Takes a scan's apps as Home's own (main thread). A card is the same object while its app is unchanged. */
    void install(Map<String, AppEntry> found) {
        all.clear();
        all.putAll(found);
        partition();
    }

    /**
     * Home's apps and the hidden ones, from every app found, in the saved order: no rescan, and
     * nothing drawn but the More Apps card when the apps it shows change, so hiding an app or
     * resetting the layout is immediate.
     */
    void partition() {
        apps.clear();
        hiddenApps.clear();
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
        // The More Apps card shows the first apps inside it: drawn again only when they change.
        List<AppEntry> first = new ArrayList<>(hiddenApps.subList(0, Math.min(FOLDER_SHOWS, hiddenApps.size())));
        if (!first.equals(folderShows)) {
            folderShows = first;
            folder = first.isEmpty() ? null : folderEntry();
        }
    }

    /** Fallback Settings tile for TVs whose settings app has no launcher entry. */
    void addSettingsEntry(PackageManager pm, Map<String, AppEntry> out, Map<String, AppEntry> prev, List<AppEntry> fresh) {
        // Home draws this card itself (the silver gear), the same every time.
        AppEntry old = prev == null ? null : prev.get(SETTINGS_KEY);
        if (old != null) {
            out.put(SETTINGS_KEY, old);
            return;
        }
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
        out.put(s.key, s);
        fresh.add(s);
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

    /** Inputs card: a graphite tvOS-style card with the input glyph; OK opens Control Centre on its Inputs page. */
    void addInputsEntry(Map<String, AppEntry> out, Map<String, AppEntry> prev, List<AppEntry> fresh) {
        AppEntry old = prev == null ? null : prev.get(INPUTS_KEY);
        if (old != null) {  // drawn by Home, the same every time
            out.put(INPUTS_KEY, old);
            return;
        }
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
        out.put(e.key, e);
        fresh.add(e);
    }

    void addResolved(PackageManager pm, String category, Set<String> pkgs, boolean leanback,
                     Map<String, AppEntry> out, Map<String, AppEntry> prev, List<AppEntry> fresh) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            // Home's own activities aren't cards on it, but for the Guide, an app of its own.
            boolean guide = ai.packageName.equals(getPackageName()) && GuideActivity.class.getName().equals(ai.name);
            if (!guide && (ai.packageName.equals(getPackageName()) || pkgs.contains(ai.packageName) || NOT_APPS.contains(ai.packageName))) continue;
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            String key = cn.flattenToShortString(), label = ri.loadLabel(pm).toString();
            // An update installs a new file (a new path, a new date): unchanged, the card is kept.
            String dir = ai.applicationInfo.sourceDir;
            String stamp = (leanback ? "tv " : "") + (dir == null ? "" : dir + " " + new java.io.File(dir).lastModified());
            if (!guide) pkgs.add(ai.packageName);
            AppEntry old = prev == null ? null : prev.get(key);
            if (old != null && stamp.equals(old.stamp) && label.equals(old.label)) {
                out.put(key, old);
                continue;
            }
            AppEntry e = new AppEntry();
            e.pkg = ai.packageName;
            e.key = key;
            e.label = label;
            e.stamp = stamp;
            // Its banner and icon are loaded with its card, several apps at once (drawCard).
            e.from = ri;
            e.iconRes = ri.getIconResource();
            e.bannerRes = leanback ? ai.getBannerResource() : 0;
            e.system = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            e.launch = new Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            out.put(e.key, e);
            fresh.add(e);
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

    /** A rescan that finished while Home couldn't take it (see applyPendingScan). */
    Map<String, AppEntry> pendingScan;
    /** Apps changed while Home was out of sight: rescanned once it's back. */
    boolean appsDirty;
    final Runnable applyScanRun = this::applyPendingScan;

    /**
     * Rescans the installed apps on a background lane, then shows the result. Out of sight, it
     * waits until Home is back rather than take the processor from the app in front.
     */
    void reload() {
        if (!resumed) {
            appsDirty = true;
            return;
        }
        appsDirty = false;
        // From the newest cards there are, including a rescan still held back, so none is drawn twice.
        Map<String, AppEntry> prev = new LinkedHashMap<>(pendingScan != null ? pendingScan : all);
        scans.execute(() -> {
            Map<String, AppEntry> found = scan(prev);
            handler.post(() -> {
                pendingScan = found;
                applyPendingScan();
            });
        });
    }

    /**
     * Puts the latest rescan on screen, focus staying on the card it was on. Not under a card
     * being moved, a menu, the forecast, the full-screen carousel or the App Switcher, which hold
     * on to where cards are: each puts it on screen once it has gone (scanAfter), and the switcher,
     * which can't tell Home, is looked at again shortly.
     */
    void applyPendingScan() {
        Map<String, AppEntry> found = pendingScan;
        if (found == null || !resumed) return;  // resumeWork picks it up when Home is back
        handler.removeCallbacks(applyScanRun);
        if (switcher != null) {
            handler.postDelayed(applyScanRun, 500);
            return;
        }
        if (moveIndex >= 0 || menuLayer != null || forecastLayer != null || idle) return;
        pendingScan = null;
        String key = currentKey();
        View f = getCurrentFocus();
        boolean inGrid = f == null || tiles.contains(f);
        List<AppEntry> was = new ArrayList<>(apps), wasHidden = new ArrayList<>(hiddenApps);
        install(found);
        handler.removeCallbacks(saveCards);
        handler.postDelayed(saveCards, SAVE_CARDS_MS);
        // The same cards in the same places (an update to an app Home doesn't show, which Google's
        // services send often): the grid stays as it is, its focused card not lifted afresh.
        if (apps.equals(was) && hiddenApps.equals(wasHidden)) return;
        leaveEmptyFolder();
        buildGrid();
        int i = key == null ? -1 : indexOfKey(key);
        if (i < 0) i = Math.min(focusedIndex, shown.size() - 1);
        // Focus up on the carousel or the status row stays there; only a card in the grid is put back,
        // simply up (it was already), with no second lift and glare.
        if (inGrid) {
            snapFocus = true;
            focusIndex(i);
            snapFocus = false;
        } else {
            focusedIndex = Math.max(0, i);
        }
    }

    /** Something that held a rescan back has closed: the rescan goes on screen ms later, once it's gone. */
    void scanAfter(long ms) {
        if (pendingScan == null) return;
        handler.removeCallbacks(applyScanRun);
        handler.postDelayed(applyScanRun, ms);
    }

    /** The folder has emptied (its last app shown again or removed): back to Home's own grid. */
    void leaveEmptyFolder() {
        if (!folderMode || !hiddenApps.isEmpty()) return;
        folderMode = false;
        weatherCard.setAlpha(weatherAlpha());
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
        partition();  // the same apps, only regrouped: nothing to scan, only the folder's card to draw
        leaveEmptyFolder();
        buildGrid();
        focusIndex(Math.min(i, shown.size() - 1));
        hint(hide ? a.label + " moved to More Apps" : a.label + " moved to the Home Screen", false);
    }

    /**
     * Apps added, removed or updated: rescanned shortly after (an update sends several of these at
     * once). Part of an app switched on or off only counts for an app Home shows or could show:
     * Google's services do that all the time.
     */
    void registerPackageReceiver() {
        pkgReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String action = i.getAction();
                // An update removes the app and adds it back: the "replaced" that follows counts.
                if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                String pkg = i.getData() == null ? null : i.getData().getSchemeSpecificPart();
                if (Intent.ACTION_PACKAGE_CHANGED.equals(action) && pkg != null && !onHome(pkg)) {
                    if (NOT_APPS.contains(pkg) || pkg.equals(getPackageName())) return;
                    scans.execute(() -> {
                        if (launchable(pkg)) handler.post(MainActivity.this::rescanSoon);
                    });
                    return;
                }
                rescanSoon();
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

    /** An app that matters to Home changed: its badge and whether it's installed are asked afresh too. */
    void rescanSoon() {
        badges.clear();
        installedApps.clear();
        handler.removeCallbacks(reloadRun);
        handler.postDelayed(reloadRun, 600);
    }

    /** Whether Home has a card for the package. */
    boolean onHome(String pkg) {
        for (AppEntry e : all.values()) if (pkg.equals(e.pkg)) return true;
        return false;
    }

    /** Whether the package has something Home would show (a TV or phone launcher entry). */
    boolean launchable(String pkg) {
        PackageManager pm = getPackageManager();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg);
            if (!pm.queryIntentActivities(q, 0).isEmpty()) return true;
        }
        return false;
    }

    /**
     * The TV switched off: a sleep timer ends with it, as TV sleep timers do, so it can't turn the
     * TV off mid-programme after it's switched back on. Only Home's own (running) receiver gets
     * this; the screen saver doesn't send it, so the timer runs on through that.
     */
    BroadcastReceiver screenOffReceiver;

    void registerScreenOff() {
        screenOffReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (SleepReceiver.timerLeft(c) > 0) SleepReceiver.setTimer(c, 0);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOffReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, f);
        }
    }

    // ---------------------------------------------------------------- options

    void showOptions(int index) {
        AppEntry a = shown.get(index);
        View tile = tiles.get(index);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Open");
        // Through OK's guard, so a second OK landing on the card as the menu goes isn't a second launch.
        actions.add(() -> afterPress(() -> launchAnimated(tile, a)));
        // tvOS 26's own words: Edit Home Screen, and Move to (a folder, or the Home Screen).
        if (folderMode) {
            labels.add("Move to Home Screen");
            actions.add(() -> setHidden(a, false));
        } else {
            labels.add("Edit Home Screen");
            actions.add(() -> startMove(index));
            if (!isSettings(a)) {
                labels.add("Move to More Apps");
                actions.add(() -> setHidden(a, true));
            }
        }
        if (!isSettings(a) && !getPackageName().equals(a.pkg)) {  // not Inputs or the Guide: they're Home's own
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
                partition();  // the same apps in their first order: nothing to scan or draw again
                buildGrid();
                int i = indexOfKey(a.key);
                focusIndex(i >= 0 ? i : 0);
            });
        }
        showMenu(tile, a.label, labels, actions, labels.indexOf("Uninstall"));
    }

    // ---------------------------------------------------------------- Control Centre

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

    int netState = -1;
    boolean netEthernet;
    /** The status icon's pictures, each drawn once: a wake usually passes through two or three. */
    final Map<String, Bitmap> netGlyphs = new java.util.HashMap<>();

    /**
     * The network changed. Back online while Home is in front, what needs the internet is
     * refreshed; out of sight (an app in front, the screen off), the next return refreshes
     * whatever is stale instead.
     */
    void updateNet() {
        if (readNet() && resumed) {
            Weather.refresh(this, true, this::updateWeather);
            loadHero(true);
        }
    }

    /** Reads the network's state into the status icon; true when it has just come back online. */
    boolean readNet() {
        if (netIcon == null) return false;
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        int state = nc == null ? 0
                : nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 2 : 1;
        boolean eth = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);
        boolean back = netState == 0 || netState == 1;
        if (state == netState && eth == netEthernet) return false;
        netState = state;
        netEthernet = eth;
        // As tvOS: a working connection shows nothing; only one that isn't gets a glyph.
        if (state != 2) {
            String key = state + (eth ? "e" : "w");
            Bitmap glyph = netGlyphs.get(key);
            if (glyph == null) netGlyphs.put(key, glyph = netGlyph(state, eth, dp(15)));
            netIcon.setImageBitmap(glyph);
            netIcon.setContentDescription(state == 1 ? "No internet" : "Offline");
        }
        netIcon.setVisibility(state == 2 ? View.GONE : View.VISIBLE);
        statusPadding();
        return state == 2 && back;
    }

    /** The sleep timer's glyph, cyan as in Control Centre, while the timer runs (tvOS's status area). */
    void timerGlyph() {
        boolean timer = SleepReceiver.timerLeft(this) > 0;
        if (timer && timerIcon.getDrawable() == null) {
            timerIcon.setImageBitmap(Glyphs.get("timer", dp(15), ControlCentreActivity.CYAN));
        }
        timerIcon.setVisibility(timer ? View.VISIBLE : View.GONE);
        statusPadding();
    }

    /** The time sits evenly in its pill alone, a little nearer the edge after a glyph. */
    void statusPadding() {
        boolean glyph = timerIcon.getVisibility() == View.VISIBLE || netIcon.getVisibility() == View.VISIBLE;
        timeChip.setPadding(glyph ? dp(13) : dp(15), 0, dp(15), 0);  // no layout pass unless it changes
    }

    /**
     * Apple-style network symbol for a connection that isn't working, white only: Wi-Fi's wedge
     * and two bands, or Ethernet, dimmed to 30%, with "!" beside it when there's a network but no
     * internet (wifi.exclamationmark) and a slash through it with no network (wifi.slash).
     */
    Bitmap netGlyph(int state, boolean eth, int s) {
        // Wi-Fi alone is a fan 1.2x as wide as it's tall; "no internet" adds a "!" to its right.
        int w = Math.round(s * (state == 1 ? 1.55f : 1.2f));
        Bitmap b = Bitmap.createBitmap(w, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int on = Color.WHITE, off = 0x4DFFFFFF;
        if (eth && state != 0) {
            c.save();
            c.translate(s * 0.1f, 0);  // centred where the Wi-Fi fan would be
            drawEthernet(c, s, off);
            c.restore();
        } else {
            drawWifi(c, s, off);
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
     * over size and scaled down smoothly, as type is, so the small curves come out even.
     */
    void drawWifi(Canvas c, float s, int col) {
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
            p.setColor(col);
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

    /**
     * Glass Home's own clicks (Clicks) on D-pad moves, select and Back, unless they're off in
     * Settings › Home Screen › Navigation Clicks; an arrow's direction is also kept for Glass.lift,
     * so a newly focused control can nudge the way the remote moved.
     */
    static void navSound(Context c, KeyEvent ev) {
        navSound(c, ev, true);
    }

    /**
     * As navSound; tickMoves false leaves the arrows' tick to the caller, which plays it only when
     * focus really moved (Home), as tvOS never ticks for a press into a wall.
     */
    static void navSound(Context c, KeyEvent ev, boolean tickMoves) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return;
        int k = ev.getKeyCode();
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                Glass.dirX = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                Glass.dirY = k == KeyEvent.KEYCODE_DPAD_UP ? -1 : k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                if (tickMoves) Clicks.move(c, 0.5f, ev.getRepeatCount() > 0);
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
    float idleScrollY, idleInfoY;
    boolean idleOverviewShown;

    /** Seconds without input before the dock slides away (Settings -> Home Screen); 0 = never. */
    int idleSeconds() {
        return prefs.getInt("idle_sec", 20);  // asked on every key press: the store already open
    }

    final Runnable idleRunnable = this::enterIdle;

    void resetIdle() {
        handler.removeCallbacks(idleRunnable);
        if (idleRising) {
            // A press while rising to the top: stay up there (the press moves from the dock), on
            // the shelf of the app in focus, which the rise held back.
            handler.removeCallbacks(idleRise);
            idleRising = false;
            idleReturnIndex = -1;
            handler.removeCallbacks(shelfUpdate);
            handler.postDelayed(shelfUpdate, 260);
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
                || forecastLayer != null || menuLayer != null || moveIndex >= 0 || switcher != null) {
            resetIdle();
            return;
        }
        if (!heroShown) {
            // Down in the app grid: rise back to the top first, then go full screen once the
            // carousel is up (the same wait as from the dock); the press that wakes it returns here.
            idleReturnIndex = focusedIndex;
            goTop();
            // Not the first dock app's own shelf on the way up: full screen dissolves to the mix
            // moments later, and two decodes would land on the rise and the slide.
            handler.removeCallbacks(shelfUpdate);
            handler.removeCallbacks(idleRunnable);
            handler.postDelayed(idleRise, 900);
            idleRising = true;
            return;
        }
        idle = true;
        // Full screen shows what's on across all your services, not just the app you were on.
        shelfFor(null, false);
        idleScrollY = scroll.getTranslationY();
        idleInfoY = heroInfoY;
        idleOverviewShown = heroOverview.getVisibility() == View.VISIBLE;
        scroll.animate().cancel();
        // The apps slide away whole, as on tvOS: no fade, so no full-screen layer to blend. Only as
        // far as the carousel focused takes them (the dock just out of sight), so the whole move is
        // seen, setting off gently: nothing was pressed for it. All the way down to the bottom of
        // the screen, the dock was gone in the first eighth of a second and the rest was unseen.
        scroll.animate().translationY(Math.max(scroll.getTranslationY(), screenH - shelfH + dp(30)))
                .setDuration(700).setInterpolator(STANDARD).setUpdateListener(null).start();
        moveHeroInfo(0, 700, STANDARD);
        heroChrome(0, 600);  // the page dots and meta line come up as the apps go
        weatherCard.animate().alpha(0f).setDuration(500).setInterpolator(EASE).start();
        heroButton.animate().alpha(0f).setDuration(300).setInterpolator(EASE).start();
        heroOverview.animate().cancel();
        heroOverview.setVisibility(heroOverview.getText().length() > 0 ? View.VISIBLE : View.GONE);
        heroOverview.animate().alpha(0.85f).setDuration(600).setInterpolator(EASE).start();
        handler.removeCallbacks(heroAdvance);
        scheduleAdvance();
    }

    final Runnable idleRise = () -> {
        idleRising = false;
        if (resumed && heroShown) enterIdle();
        else idleReturnIndex = -1;
    };

    /** idleRise is waiting to run (Handler.hasCallbacks would need Android 10). */
    boolean idleRising;

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
            // Already on its card, its row in view, before the apps come back up: only the slide
            // shows (a scroll starting under it fought the slide).
            int back = idleReturnIndex;
            idleReturnIndex = -1;
            snapFocus = true;
            focusIndex(back, false);
            snapFocus = false;
        }
        scroll.animate().cancel();
        scroll.animate().translationY(idleScrollY).setDuration(450).setInterpolator(EASE)
                .setUpdateListener(null).withEndAction(this::invalidateGlass).start();
        moveHeroInfo(idleInfoY, 450, EASE);
        heroChrome(0, 200);
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
        scanAfter(500);  // once the apps have slid back up
    }

    // ---------------------------------------------------------------- weather

    /**
     * Once a minute: fetch when the forecast is due (every 15 minutes by default), move "Now" on,
     * and show or clear the sleep timer's glyph (the network's comes with its own callback).
     */
    int tickCount;
    final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            boolean fresh = firstTick;
            firstTick = false;
            if (online(fresh)) Weather.refresh(MainActivity.this, false, MainActivity.this::updateWeather);
            updateWeather();
            // Carousel content rotates even if Home stays open for hours (fetches only when stale).
            if (++tickCount % 5 == 0 && !shelfView.hasFocus()) loadHero(online(false));
            timerGlyph();
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

    /** The forecast the pill shows: one that hasn't changed isn't laid out or prepared again. */
    Weather.Data weatherData;

    /** Shows the cached forecast (hidden when weather is off or nothing has loaded yet). */
    void updateWeather() {
        if (weatherCard == null) return;
        Weather.Data d = Weather.enabled(this) ? Weather.cached(this) : null;
        boolean show = d != null;
        if (!show && weatherCard.isFocused()) focusTop();
        weatherCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (d != weatherData) {
            weatherData = d;
            weatherCard.setData(d);
            if (show) io.execute(() -> WeatherView.prepare(d, unit));
        }
        if (show && !weatherCard.isFocused()) weatherCard.setAlpha(weatherAlpha());
    }

    /** The full forecast: a glass panel opening from the weather card in the top-left corner. */
    void showWeather() {
        Weather.Data d = Weather.cached(this);
        if (d == null) return;
        if (forecastLayer != null) root.removeView(forecastLayer);
        popoverReturn = weatherCard;
        FrameLayout layer = new FrameLayout(this);
        layer.setClickable(true);
        layer.setClipChildren(false);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xA6000000, 0x4D000000, 0x00000000}));
        layer.addView(shade, new FrameLayout.LayoutParams(screenW * 3 / 5, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        WeatherView panel = new WeatherView(this, true, unit);
        // tvOS's regular dark material, as its large panels use: half black over the picture, its
        // colour kept. One dark tint whatever is behind (large glass never turns light), so it's
        // no milky sheet, and the forecast's colours stand out on it.
        LiquidGlass glass = new LiquidGlass(dp(26), 0x80000000, glassSource, screenW, screenH, unit);
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
        forecastLayer = layer;
        weatherOpen = true;
        // The forecast grows out of the pill, from its corner, as the Apple TV app's menus come
        // out of what opened them (from 0.35 of its size, fading up, easeOut 380 ms); the pill
        // fades into it and the shade comes up behind, as a menu's dim does (400 ms, linear).
        shade.setAlpha(0f);
        shade.animate().alpha(1f).setDuration(400).setInterpolator(new android.view.animation.LinearInterpolator()).start();
        panel.setPivotX(0);
        panel.setPivotY(0);
        panel.setScaleX(0.35f);
        panel.setScaleY(0.35f);
        panel.setAlpha(0f);
        // As one picture while it grows (no text redrawn at each size); it lands at its own size.
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(EASE_OUT_QUAD).withLayer().start();
        panel.requestFocus();
        // Faded once focus has left it: the pill's unfocus animation would cut a fade begun before
        // short, leaving it showing through the panel's glass.
        weatherCard.animate().alpha(0f).setDuration(140).setInterpolator(EASE).start();
        Weather.refresh(this, false, () -> {
            Weather.Data nd = Weather.cached(this);
            if (nd != null && panel.isAttachedToWindow()) panel.setData(nd);
            updateWeather();
        });
    }

    // ---------------------------------------------------------------- popover

    boolean statusFocused() {
        View f = getCurrentFocus();
        return f != null && f == ccButton;
    }

    View popoverReturn;

    void closePopover() {
        if (forecastLayer == null) return;
        FrameLayout l = forecastLayer;
        forecastLayer = null;
        // Back into the pill: the panel shrinks towards its corner as it fades (back to 0.35 in
        // 210 ms, as a menu closes), the shade lifts, and the pill returns.
        View panel = l.getChildCount() > 1 ? l.getChildAt(1) : null;
        View shade = l.getChildAt(0);
        shade.animate().alpha(0f).setDuration(100).setInterpolator(new android.view.animation.LinearInterpolator())
                .withEndAction(panel == null ? () -> root.removeView(l) : null).start();
        if (panel != null) {
            panel.animate().scaleX(0.35f).scaleY(0.35f).alpha(0f).setDuration(210).setInterpolator(EASE_OUT_QUAD).withLayer()
                    .withEndAction(() -> root.removeView(l)).start();
        }
        weatherOpen = false;
        if (popoverReturn != null && popoverReturn.isAttachedToWindow()) popoverReturn.requestFocus();
        else goTop();
        popoverReturn = null;
        // Faded up after focus has landed: focusing the pill lifts it, and the lift would stop a fade begun before.
        weatherCard.setAlpha(0f);
        weatherCard.animate().alpha(weatherAlpha()).setDuration(260).setInterpolator(EASE).start();
        scanAfter(250);
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
        // The dim behind the menu fades with it (its own view, so the fade is a single alpha):
        // tvOS dims the screen behind a menu to 70%.
        View dim = new View(this);
        dim.setBackgroundColor(0x4D000000);
        dim.setAlpha(0f);
        menuLayer.addView(dim, match());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // tvOS's menu geometry: 454 px wide, 54 px corners, 21 px inside, 66 px capsule rows whose
        // corners are concentric with the panel's (54 - 21 = 33).
        panel.setPadding(dp(10.5f), dp(12), dp(10.5f), dp(10.5f));
        // tvOS's regular dark material (half black, the picture's colour kept), over the picture
        // as dimmed behind the menu, so the panel darkens with everything around it: the text
        // stays legible over anything.
        LiquidGlass glass = new LiquidGlass(dp(27), 0x80000000, glassSource, screenW, screenH, unit).dimmed(0.7f);
        glass.lens = dp(8);
        panel.setBackground(glass);
        panel.setElevation(dp(30));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        TextView head = text(11, Ui.font(this, "semibold"), 0.55f);
        head.setText(title);
        head.setShadowLayer(0, 0, 0, 0);
        head.setPadding(dp(15), 0, dp(15), dp(8));
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
            row.setPadding(dp(15), 0, dp(15), 0);
            row.setFocusable(true);
            row.setClickable(true);
            int base = i == destructive ? 0xFFFF6B5E : Color.WHITE;
            row.setTextColor(base);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(16.5f));
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
                // The white plate and the text colour fade together, on tvOS's menu-row timing.
                android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
                ValueAnimator fade = ValueAnimator.ofFloat(pill.getAlpha() / 255f, has ? 1f : 0f);
                fade.setDuration(has ? 100 : 200);
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
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(33));
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

        // Beside the card as it will be once back up from the press that opened the menu (it's
        // still rising now): placed from its centre and its focused size, not its size this frame.
        int w = dp(227);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        float cx = loc[0] + anchor.getWidth() * anchor.getScaleX() / 2f;
        float cy = loc[1] + anchor.getHeight() * anchor.getScaleY() / 2f;
        float half = anchor.getWidth() * FOCUS_SCALE / 2f;
        int x = (int) (cx + half + dp(18));
        if (x + w > screenW - dp(24)) x = (int) (cx - half - w - dp(18));
        x = Math.max(dp(24), x);
        panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int h = panel.getMeasuredHeight();
        int y = (int) (cy - h / 2f);
        y = Math.max(dp(24), Math.min(y, screenH - h - dp(24)));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = x;
        plp.topMargin = y;
        menuLayer.addView(panel, plp);
        root.addView(menuLayer, match());
        // The glass samples the picture where the panel will be, not where its opening scale puts it.
        int[] at = new int[2];
        root.getLocationOnScreen(at);
        glass.fixedLoc = new int[]{at[0] + x, at[1] + y};

        // Grows out of the card, as the Apple TV app's menus do: from 0.35 of its size at the side
        // touching the card, level with the card's middle (even when the panel is held inside the
        // screen), fading up, easeOut 380 ms; the dim comes up over 400 ms. It moves as one picture
        // (a GPU layer), so its words aren't redrawn at a new size every frame; it lands at exactly
        // its own size, so there's no change when the picture gives way to the real thing.
        panel.setPivotX(x > cx ? 0 : w);
        panel.setPivotY(Math.max(0f, Math.min(h, cy - y)));
        panel.setAlpha(0f);
        panel.setScaleX(0.35f);
        panel.setScaleY(0.35f);
        if (!rows.isEmpty()) rows.get(0).requestFocus();
        opening[0] = false;
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(EASE_OUT_QUAD).withLayer().start();
        dim.animate().alpha(1f).setDuration(400).setInterpolator(new android.view.animation.LinearInterpolator()).start();
    }

    void closeMenu() {
        if (menuLayer == null) return;
        FrameLayout m = menuLayer;
        menuLayer = null;
        // Back into the card it came from (to 0.35 as it fades, 210 ms), and the dim lifts (100 ms).
        // The layer goes when the panel has, which outlasts the dim.
        View panel = m.getChildCount() > 1 ? m.getChildAt(1) : null;
        // Its rows can't take focus while it fades: beside the card, an arrow pressed meanwhile
        // would land in a menu that's about to go.
        m.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        m.getChildAt(0).animate().alpha(0f).setDuration(100).setInterpolator(new android.view.animation.LinearInterpolator())
                .withEndAction(panel == null ? () -> root.removeView(m) : null).start();
        if (panel != null) {
            panel.animate().scaleX(0.35f).scaleY(0.35f).alpha(0f).setDuration(210).setInterpolator(EASE_OUT_QUAD).withLayer()
                    .withEndAction(() -> root.removeView(m)).start();
        }
        // The card never went down: focus simply returns to it, with no lift or glare replayed.
        snapFocus = true;
        if (menuReturnFocus != null && menuReturnFocus.isAttachedToWindow()) menuReturnFocus.requestFocus();
        else focusIndex(focusedIndex, false);  // its card was redrawn meanwhile: focus never stays in the menu
        snapFocus = false;
        menuReturnFocus = null;
        scanAfter(250);  // after the chosen row's action, and once the menu has faded
    }

    void startSafe(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            hint("Not available on this TV", false);
        }
    }

    // ---------------------------------------------------------------- move mode

    void startMove(int index) {
        closeMenu();
        moveIndex = index;
        handler.removeCallbacks(heroAdvance);
        focusIndex(index);
        startWiggle(tiles.get(index));
        hint("Move with the arrows, then press OK", true);
    }

    /** The card being moved goes to slot to; first is a fresh press (not a held arrow's repeat). */
    void moveTo(int to, boolean first) {
        int last = apps.size() - 1;  // the folder tile always stays at the end
        if (to > last && moveIndex / COLS < last / COLS) to = last;
        if (to < 0 || to > last) {
            // Nowhere to go that way: the card leans towards the press and back, as at any wall.
            if (first && moveIndex < tiles.size()) bump(tiles.get(moveIndex), Glass.dirX, Glass.dirY);
            return;
        }
        if (to == moveIndex) return;
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
                        // The tile fades, not its cell: a fading cell is drawn through a layer cut
                        // to the cell's bounds, which squared off the lifted card being moved and
                        // lost its shadow. (Its label keeps its own fade, shown only with focus.)
                        View cell = (View) t.getParent();
                        cell.setScaleX(0.86f);
                        cell.setScaleY(0.86f);
                        cell.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(EASE_OUT_QUAD).start();
                        t.setAlpha(0f);
                        t.animate().alpha(1f).setDuration(300).setInterpolator(EASE_OUT_QUAD).start();
                        continue;
                    }
                    // Neighbours slide aside with a 300 ms ease, as tvOS's do: visibly making room
                    // (on the focus spring they were nine-tenths there in the first 90 ms, a jump).
                    t.setTranslationX(dx);
                    t.setTranslationY(dy);
                    t.animate().translationX(0).translationY(0).setStartDelay(0).setDuration(300)
                            .setInterpolator(EASE_OUT_QUAD).start();
                }
                return true;
            }
        });
    }

    void endMove() {
        stopWiggle(true);
        hideHint();
        moveIndex = -1;
        scheduleAdvance();
        scanAfter(0);
    }

    /**
     * Edit mode's jiggle, tvOS's own (its Home Screen's): only the card being moved, turning to
     * 1.616 degrees either way and back every 0.325 s, for as long as it's held.
     */
    void startWiggle(View v) {
        stopWiggle(false);
        wiggle = ObjectAnimator.ofFloat(v, View.ROTATION, -1.616f, 1.616f);
        wiggle.setDuration(162);  // each way: the whole swing is 0.325 s
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.start();
    }

    /** The jiggle stops: the card settles level over tvOS's 88 ms (settle), or at once (rebuilt, gone). */
    void stopWiggle(boolean settle) {
        if (wiggle == null) return;
        ObjectAnimator w = wiggle;
        wiggle = null;
        w.cancel();
        if (!(w.getTarget() instanceof View)) return;
        View v = (View) w.getTarget();
        if (settle) v.animate().rotation(0).setStartDelay(0).setDuration(88).setInterpolator(EASE_OUT_QUAD).start();
        else v.setRotation(0);
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

    /** The message on show (hint), and the glass capsule it's on, made the first time. */
    TextView hintView;
    final Runnable hideHintRun = this::hideHint;

    /**
     * A short message on a glass capsule at the top of the screen, tvOS's way (it has no Android
     * toasts): on the status row's centre line, between the weather and the clock, so it never
     * covers the dock or a card being moved. Sticky ones stay until hideHint; the rest go after 2 s.
     */
    void hint(String text, boolean sticky) {
        handler.removeCallbacks(hideHintRun);
        if (hintView == null) {
            hintView = text(13, Ui.font(this, "medium"), 0f);
            hintView.setShadowLayer(0, 0, 0, 0);  // the glass gives the contrast
            hintView.setSingleLine(true);
            hintView.setGravity(Gravity.CENTER);
            hintView.setPadding(dp(16), 0, dp(16), 0);
            hintView.setBackground(pillGlass(dp(16)));
        }
        hintView.setText(text);
        if (hintView.getParent() == null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(24) + (dp(WeatherView.COMPACT_H) - dp(32)) / 2;
            root.addView(hintView, lp);
            hintView.setAlpha(0f);
            hintView.setTranslationY(dp(8));
        }
        // Redrawn once it has risen: the glass takes its place in the picture when drawn, and the
        // rise itself doesn't redraw it.
        TextView v = hintView;
        v.animate().alpha(1f).translationY(0).setStartDelay(0).setDuration(200).setInterpolator(EASE)
                .withEndAction(v::invalidate).start();
        if (!sticky) handler.postDelayed(hideHintRun, 2000);
    }

    /** The hint fades and leaves the screen (nothing left to draw, or to redraw with the glass). */
    void hideHint() {
        handler.removeCallbacks(hideHintRun);
        TextView v = hintView;
        if (v == null || v.getParent() == null) return;
        v.animate().alpha(0f).setStartDelay(0).setDuration(160).setInterpolator(EASE)
                .withEndAction(() -> root.removeView(v)).start();
    }
}

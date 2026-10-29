package app.glasshome.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A title's page, opened from Search, laid out as tvOS 26's: the backdrop, with everything at the
 * bottom left (the title's logo, or its name; "TV Show · Crime · Drama" and the age rating; the
 * synopsis; year, seasons or running time and scores; the main button) and Starring / Director at
 * the bottom right. Then How to Watch: every UK service that streams it, sells or rents it, with
 * the price. Below the fold: Related, Cast & Crew and Information. Installed apps open straight
 * to the title. Data from JustWatch, like Search.
 */
public class DetailActivity extends Activity {

    static final String QUERY = "query D($id: ID!, $c: Country!, $l: Language!) { node(id: $id) { ... on MovieOrShow {"
            + " objectType content(country: $c, language: $l) { title shortDescription originalReleaseYear runtime"
            + " ageCertification genres { shortName } productionCountries backdrops { backdropUrl }"
            + " credits { name role characterName } scoring { imdbScore tomatoMeter certifiedFresh } externalIds { tmdbId } }"
            + " offers(country: $c, platform: WEB) { monetizationType presentationType retailPrice(language: $l) standardWebURL"
            + " package { clearName technicalName icon(profile: S166)"
            + " addonParent(country: $c, platform: WEB) { technicalName clearName icon(profile: S166) } } } }"
            + " ... on Show { totalSeasonCount seasons { totalEpisodeCount content(country: $c, language: $l) { seasonNumber }"
            + " offers(country: $c, platform: WEB, filter: {monetizationTypes: [FREE, ADS, BUY]}) { monetizationType"
            + " presentationType elementCount retailPrice(language: $l) retailPriceValue package { technicalName } } } } } }";

    /**
     * A title's details asked for ahead, while its result rested in focus (Search, or Related here):
     * by the time OK is pressed they've usually come, and its page opens whole rather than filling
     * in. Text only, a few KB a title, and no pictures opened: its logo is fetched onto the disk.
     */
    static final class Ahead {
        final long at = android.os.SystemClock.uptimeMillis();
        Future<JSONObject> node, cast, clips;
    }

    /** The last eight titles asked for ahead, each good for ten minutes. */
    static final android.util.LruCache<String, Ahead> AHEAD = new android.util.LruCache<>(8);

    /** Those lookups, at background priority (they never take the processor from drawing); gone when idle. */
    static final ExecutorService LOOKUPS;

    static {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(4, 4, 30,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), job -> new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    job.run();
                }, "ahead"));
        pool.allowCoreThreadTimeOut(true);
        LOOKUPS = pool;
    }

    /** The title's details: the ones asked for ahead while they're fresh and came, else asked for now. */
    static Ahead ahead(Context ctx, String id, int tmdb, String type) {
        Context c = ctx.getApplicationContext();
        String key = id + " " + tmdb;
        synchronized (AHEAD) {
            Ahead a = AHEAD.get(key);
            if (a != null && android.os.SystemClock.uptimeMillis() - a.at < 10 * 60_000
                    && !failed(a.node) && !failed(a.cast) && !failed(a.clips)) return a;
            a = new Ahead();
            a.node = LOOKUPS.submit(() -> {
                JSONObject vars = new JSONObject().put("id", id).put("c", "GB").put("l", "en");
                return Suggestions.post("https://apis.justwatch.com/graphql",
                        new JSONObject().put("query", QUERY).put("variables", vars).toString())
                        .getJSONObject("data").getJSONObject("node");
            });
            if (tmdb > 0 && Suggestions.hasToken(c)) {
                String kind = "tv".equals(type) ? "tv/" : "movie/";
                // TMDB's cast and crew, in billing order: JustWatch's own list for a series is in no
                // useful order (Steve Carell 45th in The Office's) and full of episode directors.
                a.cast = LOOKUPS.submit(() -> billing(Suggestions.get(c, "/" + kind + tmdb + ("tv/".equals(kind) ? "/aggregate_credits" : "/credits"))));
                // Its trailers too: TMDB says which are the official ones.
                a.clips = LOOKUPS.submit(() -> Suggestions.get(c, "/" + kind + tmdb + "/videos?include_video_language=en,null"));
                LOOKUPS.execute(() -> {
                    try {
                        String logo = Suggestions.tmdbArt(c, type, tmdb)[1];
                        if (logo != null) Suggestions.download(c, logo, "");
                    } catch (Exception e) {
                        // fetched with the page instead
                    }
                });
            }
            AHEAD.put(key, a);
            return a;
        }
    }

    /**
     * Only what the page uses of TMDB's cast and crew, kept with the title: the first twenty billed
     * and the directors. A long series' whole list (everyone in every episode) runs to megabytes.
     */
    static JSONObject billing(JSONObject credits) throws org.json.JSONException {
        List<JSONObject> cast = new ArrayList<>();
        JSONArray a = credits.optJSONArray("cast");
        for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) != null) cast.add(a.optJSONObject(i));
        cast.sort((x, y) -> Integer.compare(x.optInt("order", 999), y.optInt("order", 999)));
        JSONArray top = new JSONArray(), directors = new JSONArray();
        for (int i = 0; i < Math.min(20, cast.size()); i++) top.put(cast.get(i));
        JSONArray crew = credits.optJSONArray("crew");
        for (int i = 0; crew != null && i < crew.length(); i++) {
            JSONObject cr = crew.optJSONObject(i);
            if (cr != null && "Director".equals(cr.optString("job"))) directors.put(cr);
        }
        return new JSONObject().put("cast", top).put("crew", directors);
    }

    /** A lookup that has finished without an answer (to be asked again), rather than one still going. */
    static boolean failed(Future<?> f) {
        if (f == null || !f.isDone()) return false;
        try {
            f.get();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** The answer, or null when it failed or hasn't come within ms. */
    static JSONObject got(Future<JSONObject> f, long ms) {
        try {
            return f == null ? null : f.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** JustWatch service -> the Android TV app that plays it (the stores open in their apps too). */
    static final Map<String, String> APPS = new LinkedHashMap<>();

    static {
        APPS.put("netflix", "com.netflix.ninja");
        APPS.put("netflixbasicwithads", "com.netflix.ninja");
        APPS.put("amazonprime", "com.amazon.amazonvideo.livingroom");
        APPS.put("amazon", "com.amazon.amazonvideo.livingroom");
        APPS.put("disneyplus", "com.disney.disneyplus");
        APPS.put("appletvplus", "com.apple.atve.androidtv.appletv");
        APPS.put("itunes", "com.apple.atve.androidtv.appletv");
        APPS.put("bbc", "bbc.iplayer.android");
        APPS.put("itv", "air.ITVMobilePlayer");
        APPS.put("itvpremium", "air.ITVMobilePlayer");
        APPS.put("all4", "com.channel4.ondemand");
        APPS.put("all4plus", "com.channel4.ondemand");
        APPS.put("my5", "com.channel5.my5");
        APPS.put("nowtv", "com.bskyb.nowtv.beta");
        APPS.put("max", "com.wbd.stream");
        APPS.put("paramountplus", "com.cbs.ca");  // Paramount+ outside the US (com.cbs.ott is the US app)
        APPS.put("discoverypluseurope", "com.discovery.dplay");
        APPS.put("youtube", "com.google.android.youtube.tv");
        APPS.put("plex", "com.plexapp.android");
    }

    /**
     * A broadcaster's own ad-free tier (ITVX Premium, Channel 4 Plus): its card is the broadcaster's,
     * where the title is free with ads, so the tier never turns that free run into a sample.
     */
    static final Set<String> PREMIUM_TIERS = new HashSet<>(Arrays.asList("itvpremium", "all4plus"));

    /** Left out of How to Watch altogether (by JustWatch's name for them): Pluto TV. */
    static final Set<String> NEVER = new HashSet<>(Collections.singletonList("plutotv"));

    /** The Apple TV app's row and page scroll (its stiffness 150, damping 30 spring as a curve). */
    static final PathInterpolator SCROLL = new PathInterpolator(0.2f, 0.7f, 0.25f, 1f);
    /** Focus on tiles and cards, as the Apple TV app times it: a quick 85 ms in, 230 ms out. */
    static final AccelerateInterpolator FOCUS_IN = new AccelerateInterpolator(1f);
    static final DecelerateInterpolator FOCUS_OUT = new DecelerateInterpolator(1f);
    static final LinearInterpolator LINEAR = new LinearInterpolator();
    static final ArgbEvaluator EV = new ArgbEvaluator();

    /** How much of a series a card has free (or with ads): episodes, whole seasons, and whether any season is only partly. */
    static final class Cover {
        int eps;
        boolean partial;
        final List<Integer> seasons = new ArrayList<>();
    }

    /**
     * One card in How to Watch: a service's offers for this title, merged across quality (SD/HD/4K)
     * and tiers; or a paid channel sold through a service, which has a card of its own.
     */
    static final class Offer {
        /** The card's title ("Prime Video", "Apple TV+ on Prime Video"), and the app's own name for its button. */
        String name, service, url, pkg, icon;
        /** The whole title: with the service's own subscription, free, free with ads. */
        boolean subscription, free, ads;
        /** A broadcaster's ad-free tier (counts as Stream only when nothing there is free). */
        boolean premium;
        /** A paid add-on channel's card: the channel ("Apple TV+"), and the service's card it's sold on. */
        String addon;
        Offer home;
        /** Some of a series free: "Free episode", "Season 1 free with ads". */
        String part;
        /**
         * Films: the cheapest rental and purchase, "from" when the price differs by quality. Series:
         * the cheapest season (and which, when it's the only one for sale), or anyBuy without a price.
         */
        String rent, buy;
        boolean rentFrom, buyFrom, anyBuy;
        double buyValue;
        int buySeason, seasonsForSale;
        /** [0] free, [1] with ads. */
        final Cover[] cover = {new Cover(), new Cover()};
        int urlRank = 9, rank;
        /** Worked out off the UI thread: whether the app is here, and its banner if so. */
        boolean installed;
        Drawable art;
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    /** The details, then How to Watch's pictures. */
    final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Alongside: TMDB's artwork and the backdrop, then Related's posters. */
    final ExecutorService side = Executors.newSingleThreadExecutor();
    /**
     * Related, on a lane of its own: behind the artwork it often landed after the shelves were
     * built, and pushed the one in focus down the screen.
     */
    final ExecutorService rel = Executors.newSingleThreadExecutor();
    ImageView backdrop, logo;
    /** The title's name or logo; shown before the details came (nameEarly); the details shown. */
    View nameSlot;
    boolean nameEarly, filled;
    Pager page;
    LinearLayout content, hero, header, actions, sections;
    Row ways;
    TextView title, headerText, age, synopsis, meta, credits, waysLabel;
    String titleText, id;
    /** How many title pages deep this one is, opened from Related. */
    int depth;
    int screenW, posterW, logoW;

    int u(float units) {
        return Ui.px(this, units);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ClipAudit.watch(this);  // a development check, off unless asked for over adb
        Intent in = getIntent();
        titleText = in.getStringExtra("title");
        id = in.getStringExtra("id");
        depth = in.getIntExtra("depth", 0);
        screenW = Math.min(1920, Ui.screen(this).x);
        posterW = u(130);
        logoW = u(280);
        // No fill of its own: the window's black is already behind the backdrop.
        FrameLayout root = new FrameLayout(this);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        // Legibility: dark from the left and from the bottom, as on Apple TV product pages. The
        // shades ride on the picture and the three are kept as one ready image, so as it fades and
        // settles the TV draws a single picture each frame rather than three full-screen layers
        // (all but while a sharper copy dissolves in: showBackdrop()).
        LayerDrawable shade = new LayerDrawable(new Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF2000000, 0xB3000000, 0x33000000, 0x00000000}),
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x66000000, 0x00000000})});
        shade.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        shade.setLayerHeight(1, u(260));
        backdrop.setForeground(shade);
        // Faded as one picture, shades and all, even while it's drawn without its layer (a
        // dissolve): an ImageView otherwise fades each part on its own, and the shades thin out.
        backdrop.forceHasOverlappingRendering(true);
        backdrop.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        page = new Pager(this);
        page.setOnScrollChangeListener((v, x, y, ox, oy) -> dim(y));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        page.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // The first screen. Everything sits at the bottom left, inside tvOS's 80 / 60 px safe area,
        // leaving the top of the art clear; the next shelf's title peeks in below it.
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setClipChildren(false);
        hero.setClipToPadding(false);
        hero.setPadding(u(40), u(30), u(40), u(30));
        content.addView(hero, new LinearLayout.LayoutParams(-1, Ui.screen(this).y - u(30)));
        hero.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // The title's name, until (with a TMDB key) its logo arrives to take its place.
        FrameLayout slot = new FrameLayout(this);
        title = text(34, "display", Color.WHITE);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(u(400));
        title.setText(titleText);
        slot.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        logo = new ImageView(this);
        logo.setAdjustViewBounds(true);
        logo.setMaxWidth(logoW);
        logo.setMaxHeight(u(50));  // Apple's product-page logo fits 560 x 100 px
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setAlpha(0f);
        logo.setVisibility(View.GONE);
        slot.addView(logo, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        // It comes in with the rest of the page (show): shown at once, it sat low on the empty
        // page and jumped up as the synopsis and buttons filled in beneath it. Details slow to
        // come: it shows meanwhile, and glides up to its place when they do (nameTo).
        slot.setAlpha(0f);
        slot.forceHasOverlappingRendering(false);  // faded as its two parts, without an off-screen pass
        hero.addView(slot);
        nameSlot = slot;
        ui.postDelayed(() -> {
            if (filled || isDestroyed()) return;
            nameEarly = true;
            fadeIn(nameSlot, 0);
        }, 300);

        // "TV Show · Crime · Drama", then the age rating in an outlined box.
        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setAlpha(0f);
        header.forceHasOverlappingRendering(false);  // see fadeIn
        headerText = text(13, "medium", 0xE6FFFFFF);
        header.addView(headerText);
        age = text(11, "semibold", 0xE6FFFFFF);
        GradientDrawable box = new GradientDrawable();
        box.setStroke(Math.max(1, u(0.75f)), 0xB3FFFFFF);
        box.setCornerRadius(u(2.5f));
        age.setBackground(box);
        age.setPadding(u(4), u(0.5f), u(4), u(0.5f));
        age.setVisibility(View.GONE);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.leftMargin = u(8);
        header.addView(age, al);
        hero.addView(header, margins(u(8)));

        synopsis = text(12.5f, "regular", 0xB3FFFFFF);
        synopsis.setMaxWidth(u(360));
        synopsis.setMaxLines(4);
        synopsis.setEllipsize(TextUtils.TruncateAt.END);
        quick(synopsis);
        synopsis.setLineSpacing(0, 1.15f);
        synopsis.setAlpha(0f);
        hero.addView(synopsis, margins(u(8)));
        meta = text(12, "medium", 0xB3FFFFFF);
        meta.setAlpha(0f);
        hero.addView(meta, margins(u(8)));

        // The buttons at the left; Starring and Director at the right, level with them.
        FrameLayout row = new FrameLayout(this);
        row.setClipChildren(false);
        actions = new LinearLayout(this);
        actions.setClipChildren(false);
        // A button's height even before (or without) one, so a late one (Trailer, your library's)
        // never lifts everything above it.
        actions.setMinimumHeight(u(37));
        actions.forceHasOverlappingRendering(false);  // see fadeIn
        row.addView(actions, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.BOTTOM));
        credits = text(12, "regular", 0xE6FFFFFF);
        credits.setMaxWidth(u(300));
        credits.setMaxLines(3);
        credits.setEllipsize(TextUtils.TruncateAt.END);
        credits.setLineSpacing(0, 1.1f);
        // It sits on the artwork's light side, with no shade behind it: a soft shadow keeps it legible.
        credits.setShadowLayer(u(5), 0, u(0.5f), 0xB3000000);
        credits.setAlpha(0f);
        credits.forceHasOverlappingRendering(false);  // (its shadow made it one: see fadeIn)
        quick(credits);
        row.addView(credits, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = u(18);
        hero.addView(row, rl);

        waysLabel = text(15.5f, "semibold", 0x80FFFFFF);
        waysLabel.setText("How to Watch");
        waysLabel.setAlpha(0f);
        hero.addView(waysLabel, margins(u(24)));
        ways = new Row(u(40), u(7), u(10));
        ways.setAlpha(0f);
        ways.forceHasOverlappingRendering(false);  // see fadeIn
        // Edge to edge, so cards that run past the screen scroll under the margins rather than
        // being cut off at them.
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.leftMargin = wl.rightMargin = -u(40);
        hero.addView(ways, wl);

        sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setClipChildren(false);
        sections.setPadding(0, 0, 0, u(40));
        content.addView(sections, new LinearLayout.LayoutParams(-1, -2));

        page.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> follow(now));
        setContentView(root);
        load(in.getStringExtra("backdrop"), in.getIntExtra("tmdb", 0), in.getStringExtra("type"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Whatever is still on its way is for a page that's gone.
        ui.removeCallbacksAndMessages(null);
        page.removeCallbacks(scrollStart);
        if (scroller != null) scroller.cancel();
        if (arriving != null) arriving.cancel();
        io.shutdownNow();
        side.shutdownNow();
        rel.shutdownNow();
    }

    /** Leaving for another title page (from Related), so this one closes under it, not over it. */
    boolean replacing;

    @Override
    public void finish() {
        super.finish();
        if (!replacing) overridePendingTransition(R.anim.hold, R.anim.page_out);
    }

    LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = top;
        return lp;
    }

    /**
     * Laid out by Android's quick line breaking: the default (balanced, and hyphenated before
     * Android 13) made measuring the synopsis and credits much of the frame the page filled in.
     */
    static void quick(TextView t) {
        t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
    }

    TextView text(float size, String weight, int color) {
        TextView t = new TextView(this);
        Ui.textSize(t, size);
        t.setTypeface(Ui.font(this, weight));
        t.setTextColor(color);
        return t;
    }

    // ---------------------------------------------------------------- data

    /** The picture Search passed in, for when TMDB has nothing better. */
    String fallbackBackdrop;
    /** Showing Search's copy of the picture, until the full-size one arrives. */
    boolean carried;
    /** The artwork (TMDB's, else JustWatch's) is on its way. */
    boolean artStarted;
    /** The backdrop has finished arriving: from now on it dims as the page scrolls. */
    boolean settled;

    void load(String knownBackdrop, int tmdb, String type) {
        // The picture that was behind the result in Search, carried straight over: the page opens
        // on it rather than on black, and the full-size one replaces it when it's ready.
        Bitmap ready = knownBackdrop == null ? null : SearchActivity.AMBIENT.get(knownBackdrop);
        if (ready != null) {
            backdrop.setImageBitmap(ready);
            backdrop.setAlpha(1f);
            carried = true;
            settled = true;
        }
        fallbackBackdrop = knownBackdrop;
        boolean token = Suggestions.hasToken(this);
        // With a TMDB key and the title's TMDB id, its full-size artwork and logo are asked for now,
        // alongside the details rather than after them. With a key but no id yet, the page waits
        // for TMDB's artwork instead of showing JustWatch's first.
        if (token && tmdb > 0) art(type, tmdb, knownBackdrop);
        else if (!token && !carried && knownBackdrop != null) {
            artStarted = true;
            side.execute(() -> backdrop(knownBackdrop));
        }
        if (id == null) return;
        // The details, cast and trailers: usually asked for already, while its result had focus.
        Ahead a = ahead(this, id, tmdb, type);
        io.execute(() -> {
            try {
                JSONObject n = a.node.get();
                // Sorted out here, with each app's banner, so the page builds in one go.
                List<Offer> offers = offers(n);
                PackageManager pm = getPackageManager();
                for (Offer o : offers) {
                    o.installed = o.pkg != null && Suggestions.installed(this, o.pkg);
                    o.art = o.installed ? onGpu(banner(pm, o.pkg), u(56), u(32)) : null;
                    o.rank = (o.free || o.ads ? 0 : o.subscription ? 1 : o.addon != null || o.part != null ? 2 : 3) + (o.installed ? 0 : 1);
                }
                // Only what you could watch it with: your apps, and services you could sign up
                // to. A shop you don't have (Rakuten, Sky Store) selling it isn't a way to watch.
                int all = offers.size();
                offers.removeIf(o -> !o.installed && !o.free && !o.ads && !o.subscription);
                elsewhere = all > 0 && offers.isEmpty();
                offers.sort((x, y) -> Integer.compare(x.rank, y.rank));
                // Without them in time, JustWatch's cast list and clips.
                tmdbCast = got(a.cast, 2500);
                tmdbVideos = got(a.clips, 1000);
                ui.post(() -> show(n, offers));
            } catch (Exception e) {
                ui.post(() -> {
                    if (isDestroyed()) return;
                    meta.setText("Details aren’t available right now.");
                    filled = true;
                    if (!nameEarly) fadeIn(nameSlot, 0);
                    fadeIn(meta, 0);
                });
            }
        });
        rel.execute(() -> related(type, tmdb));
    }

    /** TMDB's artwork for the title when there's a key (else the fallback): the backdrop, then its logo. */
    void art(String type, int tmdb, String fallback) {
        artStarted = true;
        side.execute(() -> {
            String[] art = Suggestions.tmdbArt(this, type, tmdb);
            String url = art[0] != null ? art[0] : fallback;
            if (url != null) backdrop(url);
            if (art[1] != null && !gone()) {
                Bitmap lb = Suggestions.onGpu(Suggestions.image(this, art[1], "", logoW));
                if (lb != null) ui.post(() -> showLogo(lb));
            }
        });
    }

    /**
     * The page is closing or closed: the pictures still to come are dropped rather than decoded
     * while the page beneath fades back in (decoding steals from its frames on this TV).
     */
    boolean gone() {
        return isFinishing() || isDestroyed();
    }

    /** Opens the full-size picture (off the UI thread) and brings it in. */
    void backdrop(String url) {
        if (gone()) return;
        Bitmap bm = Suggestions.screenImage(this, url, screenW);
        if (bm != null) ui.post(() -> showBackdrop(bm));
    }

    void showBackdrop(Bitmap bm) {
        if (isDestroyed()) return;
        if (carried && backdrop.getDrawable() != null) {
            // Over the copy already showing: a quick dissolve, so a sharper (or a different)
            // picture never cuts in.
            carried = false;
            TransitionDrawable t = new TransitionDrawable(new Drawable[]{backdrop.getDrawable(), new BitmapDrawable(getResources(), bm)});
            t.setCrossFadeEnabled(false);
            backdrop.setImageDrawable(t);
            // Drawn straight while it dissolves: a layer whose picture changes every frame is
            // rebuilt every frame. Back to one ready image once it's done.
            backdrop.setLayerType(View.LAYER_TYPE_NONE, null);
            t.startTransition(360);
            ui.postDelayed(() -> backdrop.setLayerType(View.LAYER_TYPE_HARDWARE, null), 400);
            return;
        }
        backdrop.setImageBitmap(bm);
        backdrop.setScaleX(1.04f);
        backdrop.setScaleY(1.04f);
        backdrop.animate().scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(MainActivity.HERO_EASE).start();
        // Its fade-in and the page's dim together, so scrolling while it comes in dims it as it
        // goes rather than snapping to the dimmed value at the end.
        settled = true;
        if (arriving != null) arriving.cancel();
        arrived = 0f;
        arriving = ValueAnimator.ofFloat(0f, 1f);
        arriving.setDuration(900);
        arriving.setInterpolator(MainActivity.HERO_EASE);
        arriving.addUpdateListener(a -> {
            arrived = (float) a.getAnimatedValue();
            dim(page.getScrollY());
        });
        arriving.start();
    }

    /** How far the backdrop has faded in (1 once it has). */
    float arrived = 1f;
    ValueAnimator arriving;

    /** The title's logo takes the place of its name: the name fades out as the logo fades in. */
    void showLogo(Bitmap lb) {
        if (isDestroyed()) return;
        logo.setImageBitmap(lb);
        logo.setVisibility(View.VISIBLE);
        title.animate().alpha(0f).setDuration(150).setInterpolator(LINEAR)
                .withEndAction(() -> title.setVisibility(View.INVISIBLE)).start();
        logo.animate().alpha(1f).setDuration(240).setInterpolator(LINEAR).start();
    }

    /** The backdrop dims to half as the page scrolls down to its shelves, as on tvOS. */
    float dimmed(int y) {
        return 1f - 0.5f * Math.min(1f, y / (float) u(500));
    }

    void dim(int y) {
        if (settled) backdrop.setAlpha(arrived * dimmed(y));
    }

    /**
     * A banner drawn at its card's size (filling it, as the card crops it) and handed to the GPU
     * here, off the UI thread: as Android gives it, it was uploaded in the frames the page opened in.
     */
    Drawable onGpu(Drawable d, int w, int h) {
        if (d == null || w <= 0 || h <= 0) return d;
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        if (iw > 0 && ih > 0) {
            float s = Math.max(w / (float) iw, h / (float) ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
        } else {
            d.setBounds(0, 0, w, h);
        }
        d.draw(c);
        Bitmap g = Suggestions.onGpu(b);
        return new BitmapDrawable(getResources(), g != null ? g : b);
    }

    /** An installed app's TV banner (its icon if it has none). */
    static Drawable banner(PackageManager pm, String pkg) {
        try {
            Drawable d = pm.getApplicationBanner(pkg);
            return d != null ? d : pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The cards for apps that aren't here, waiting for JustWatch's logo of the service. */
    final Map<Offer, FrameLayout> tiles = new LinkedHashMap<>();

    /**
     * Those logos, fetched once the page is up (each is a download the first time, so the page
     * doesn't wait for them): each fades in over its initial, made into a banner like the
     * installed apps' (bannerFrom).
     */
    void logos(List<Offer> want) {
        int w = u(56), h = u(32);
        for (Offer o : want) {
            if (gone()) return;
            Bitmap icon = Suggestions.image(this, "https://images.justwatch.com" + o.icon.replace("{format}", "png"), "", w);
            Bitmap b = icon == null ? null : Suggestions.onGpu(bannerFrom(icon, w, h));
            if (b == null) continue;
            ui.post(() -> {
                FrameLayout tile = tiles.get(o);
                if (tile == null || isDestroyed()) return;
                ImageView banner = new ImageView(this);
                banner.setImageBitmap(b);
                banner.setScaleType(ImageView.ScaleType.FIT_XY);
                banner.setClipToOutline(true);
                GradientDrawable shape = new GradientDrawable();
                shape.setCornerRadius(u(7));
                banner.setBackground(shape);
                banner.setAlpha(0f);
                tile.addView(banner, new FrameLayout.LayoutParams(-1, -1));
                View initial = tile.getChildAt(0);
                // The glass tile goes once it's covered, so no grey shows at the banner's corners.
                banner.animate().alpha(1f).setDuration(240).setInterpolator(LINEAR)
                        .withEndAction(() -> tile.setBackground(null)).start();
                initial.animate().alpha(0f).setDuration(240).setInterpolator(LINEAR).start();
            });
        }
    }

    /**
     * A service's square icon (JustWatch's: its logo on its own colour) as a TV banner for a card,
     * like the installed apps' banners beside it: the colour fills the card and the logo sits
     * across the middle, rather than a small square on grey. An icon whose edge isn't one colour
     * (a picture) is shown whole, at the card's height, on its edge's average.
     */
    static Bitmap bannerFrom(Bitmap icon, int w, int h) {
        int iw = icon.getWidth(), ih = icon.getHeight();
        int[] px = new int[iw * ih];
        icon.getPixels(px, 0, iw, 0, 0, iw, ih);
        // Its colour: the edge all round (what's see-through there is the card's own grey).
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int i = 0; i < px.length; i++) {
            int x = i % iw, y = i / iw;
            if (x != 0 && y != 0 && x != iw - 1 && y != ih - 1 || Color.alpha(px[i]) < 128) continue;
            r += Color.red(px[i]);
            g += Color.green(px[i]);
            b += Color.blue(px[i]);
            n++;
        }
        int bg = n == 0 ? 0xFF3A3A3C : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
        n = Math.max(n, 1);
        // The logo: whatever differs from it. Edge pixels that differ make it a picture.
        int left = iw, top = ih, right = -1, bottom = -1, odd = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            if (Color.alpha(p) < 128) continue;
            int d = Math.max(Math.abs(Color.red(p) - Color.red(bg)),
                    Math.max(Math.abs(Color.green(p) - Color.green(bg)), Math.abs(Color.blue(p) - Color.blue(bg))));
            if (d <= 40) continue;
            int x = i % iw, y = i / iw;
            if (x == 0 || y == 0 || x == iw - 1 || y == ih - 1) odd++;
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        c.drawColor(bg);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        if (right < 0 || odd > n / 10) {
            c.drawBitmap(icon, null, new Rect((w - h) / 2, 0, (w + h) / 2, h), paint);
            return out;
        }
        // The logo as large as the installed apps' are on theirs: most of the card's height.
        Rect from = new Rect(left, top, right + 1, bottom + 1);
        float s = Math.min(w * 0.8f / from.width(), h * 0.72f / from.height());
        float dw = from.width() * s, dh = from.height() * s;
        c.drawBitmap(icon, from, new android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), paint);
        return out;
    }

    /** The name, shown before the details, glides from where it was to its place above them. */
    void nameTo(int was) {
        nameSlot.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                nameSlot.getViewTreeObserver().removeOnPreDrawListener(this);
                nameSlot.setTranslationY(nameSlot.getTranslationY() + was - nameSlot.getTop());
                nameSlot.animate().translationY(0).setDuration(420).setInterpolator(MainActivity.EASE).start();
                return true;
            }
        });
    }

    void show(JSONObject n, List<Offer> offers) {
        if (isDestroyed()) return;
        JSONObject c = n.optJSONObject("content");
        if (c == null) return;
        filled = true;
        boolean series = "SHOW".equals(n.optString("objectType"));
        title.setText(Suggestions.cleanTitle(c.optString("title", titleText == null ? "" : titleText)));
        if (!artStarted) {
            JSONArray bds = c.optJSONArray("backdrops");
            String jw = bds != null && bds.length() > 0 ? "https://images.justwatch.com" + bds.optJSONObject(0)
                    .optString("backdropUrl").replace("{profile}", "s1920").replace("{format}", "jpg") : fallbackBackdrop;
            JSONObject ext = c.optJSONObject("externalIds");
            art(series ? "tv" : "movie", ext == null ? 0 : Suggestions.parseInt(ext.optString("tmdbId")), jw);
        }

        String genres = Suggestions.jwGenres(c.optJSONArray("genres"));
        headerText.setText((series ? "Series" : "Film") + (genres.isEmpty() ? "" : " · " + genres));
        String rated = str(c, "ageCertification");
        if (!rated.isEmpty()) {
            age.setText(rated);
            age.setVisibility(View.VISIBLE);
        }
        synopsis.setText(c.optString("shortDescription", ""));

        // Year · seasons or running time · scores
        List<String> m = new ArrayList<>();
        int y = c.optInt("originalReleaseYear", 0);
        if (y > 0) m.add(String.valueOf(y));
        String length = series ? seasons(n.optInt("totalSeasonCount", 0)) : runtime(c.optInt("runtime", 0));
        if (!length.isEmpty()) m.add(length);
        String scores = scores(c.optJSONObject("scoring"));
        if (!scores.isEmpty()) m.add(scores);
        meta.setText(TextUtils.join(" · ", m));
        SearchActivity.showIcons(meta);

        // Starring … / Director …: grey labels, white names.
        List<String[]> people = tmdbCast != null ? tmdbPeople(tmdbCast, series) : people(c.optJSONArray("credits"), series);
        List<String> actors = new ArrayList<>();
        String director = null;
        for (String[] p : people) {
            if (p[2] == null && actors.size() < 3) actors.add(p[0]);
            else if (p[2] != null && director == null) director = p[0];
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!actors.isEmpty()) label(sb, "Starring ").append(TextUtils.join(", ", actors));
        if (director != null) label(sb.length() > 0 ? sb.append('\n') : sb, "Director ").append(director);
        credits.setText(sb);

        if (nameEarly) nameTo(nameSlot.getTop());
        else fadeIn(nameSlot, 0);
        fadeIn(header, 0);
        fadeIn(synopsis, 40);
        fadeIn(meta, 80);
        fadeIn(credits, 120);

        actions.removeAllViews();
        View first = primary(offers);
        ways.items.removeAllViews();
        tiles.clear();
        for (Offer o : offers) way(o);
        // The services' logos first: the trailer search below can take a while, and they're on
        // the same lane.
        if (!tiles.isEmpty()) {
            List<Offer> want = new ArrayList<>(tiles.keySet());
            io.execute(() -> logos(want));
        }
        String trailer = trailer(tmdbVideos, null, c.optString("title", ""));
        if (trailer != null) pill("Trailer", v -> playTrailer(trailer));
        else if (tmdbVideos == null) {
            // No TMDB: JustWatch's clips, which it gives only in a search (asked by id, it gives
            // none), so the title is searched for and found by id; the button fades in after.
            String name = c.optString("title", "");
            io.execute(() -> {
                String key = trailer(null, jwClips(name), name);
                if (key != null) ui.post(() -> {
                    if (isDestroyed()) return;
                    View b = pill("Trailer", v -> playTrailer(key));
                    b.setAlpha(0f);
                    b.animate().alpha(1f).setDuration(240).setInterpolator(LINEAR).start();
                    if (getCurrentFocus() == null) b.requestFocus();  // nothing else to be on
                });
            });
        }
        if (offers.isEmpty()) {
            TextView none = text(12.5f, "regular", 0x99FFFFFF);
            none.setText(elsewhere ? "Only to rent or buy in apps that aren\u2019t on this TV."
                    : "Not available to stream, rent or buy in the UK right now.");
            ways.items.addView(none);
        }
        // The main button; else the first button there is (Trailer); else the first way to watch.
        View placed = first != null ? first : actions.getChildCount() > 0 ? actions.getChildAt(0)
                : offers.isEmpty() ? null : ways.items.getChildAt(0);
        if (placed != null) placed.requestFocus();
        fadeIn(actions, 120);
        fadeIn(waysLabel, 160);
        fadeIn(ways, 200);
        reportFullyDrawn();  // the page is whole: "Fully drawn" in the system log times its opening
        // The shelves below the fold once the page has settled, so their building and pictures
        // stay out of the fade-ups, and out of the backdrop's own arrival too (a frame or two
        // apiece on this TV), though never more than a second and a half on.
        long latest = android.os.SystemClock.uptimeMillis() + 1500;
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            if (isDestroyed()) return;
            boolean moving = arriving != null && arriving.isRunning() || backdrop.getLayerType() == View.LAYER_TYPE_NONE;
            if (moving && android.os.SystemClock.uptimeMillis() < latest) ui.postDelayed(build[0], 100);
            else sections(n, c, series, people);
        };
        ui.postDelayed(build[0], 700);
    }

    /**
     * The one main button, as Apple's: Watch on the best free service you have;
     * else Open in the service or store you have (you may not subscribe, so it doesn't promise
     * to play: you buy, rent, subscribe or add the channel there); else Get the app that streams it.
     */
    /** Offers there were, but only from shops whose apps aren't on the TV. */
    volatile boolean elsewhere;

    View primary(List<Offer> offers) {
        // Ranked: anything free comes before a subscription.
        for (Offer o : offers) {
            if (o.installed && (o.free || o.ads || o.subscription)) {
                return pill((o.subscription && !o.free && !o.ads ? "Open in " : "▶  Watch on ") + o.service, v -> open(o));
            }
        }
        for (Offer o : offers) {
            if (o.installed && (o.addon != null || o.part != null || o.rent != null || o.buy != null || o.anyBuy)) {
                return pill("Open in " + o.service, v -> open(o));
            }
        }
        for (Offer o : offers) {
            if ((o.subscription || o.free || o.ads) && o.pkg != null && !o.installed) return pill("Get " + o.service, v -> open(o));
        }
        return null;
    }

    static SpannableStringBuilder label(SpannableStringBuilder sb, String s) {
        int at = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(0x80FFFFFF), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    static String seasons(int n) {
        return n > 1 ? n + " Seasons" : n == 1 ? "1 Season" : "";
    }

    /** "2 hr 49 min", "45 min". */
    static String runtime(int mins) {
        return mins >= 60 ? (mins / 60 + " hr " + (mins % 60 > 0 ? mins % 60 + " min" : "")).trim() : mins > 0 ? mins + " min" : "";
    }

    static String scores(JSONObject sc) {
        double imdb = sc == null ? 0 : sc.optDouble("imdbScore", 0);
        int tomato = sc == null || sc.isNull("tomatoMeter") ? -1 : sc.optInt("tomatoMeter", -1);
        return SearchActivity.ratings(imdb, tomato, sc != null && sc.optBoolean("certifiedFresh", false));
    }

    /** TMDB's cast and crew for the page (see load), when it came: then also the cast's photos. */
    volatile JSONObject tmdbCast;
    /** TMDB's videos for the title (see load), when they came. */
    volatile JSONObject tmdbVideos;

    /**
     * The title's trailer on YouTube (its video id), or null. TMDB's official trailer when there's
     * a key (a teaser only when there's no trailer; the first one released, a series' first
     * season's); else JustWatch's clips, whose list mixes in a sequel's trailer (Gladiator II's
     * for Gladiator), scenes and reviews: only one named for this title and a trailer will do.
     */
    static String trailer(JSONObject tmdb, JSONArray jw, String title) {
        JSONArray v = tmdb == null ? null : tmdb.optJSONArray("results");
        String best = null, bestAt = null;
        int bestScore = 0;
        for (int i = 0; v != null && i < v.length(); i++) {
            JSONObject o = v.optJSONObject(i);
            if (o == null || !"YouTube".equals(o.optString("site")) || str(o, "key").isEmpty()) continue;
            String type = o.optString("type"), name = o.optString("name").toLowerCase(Locale.ROOT);
            int sc = "Trailer".equals(type) ? 4 : "Teaser".equals(type) ? 1 : 0;
            if (sc == 0) continue;
            if (o.optBoolean("official")) sc += 2;
            if (name.contains("official trailer")) sc++;
            String at = str(o, "published_at");
            if (sc > bestScore || sc == bestScore && bestAt != null && at.compareTo(bestAt) < 0) {
                best = str(o, "key");
                bestScore = sc;
                bestAt = at;
            }
        }
        if (best != null) return best;
        String t = SearchBrain.norm(title);
        if (t.isEmpty()) return null;
        int bestJw = 0;
        for (int i = 0; jw != null && i < jw.length(); i++) {
            JSONObject o = jw.optJSONObject(i);
            if (o == null || !"YOUTUBE".equals(o.optString("provider")) || str(o, "externalId").isEmpty()) continue;
            String name = " " + SearchBrain.norm(o.optString("name")) + " ";
            int at = name.indexOf(" " + t + " ");
            if (at < 0 || !name.contains(" trailer ") || name.matches(".*\\b(reaction|review|explained|recap|breakdown)\\b.*")) continue;
            // Named for a sequel: "Gladiator II", "Dune Part Two", "Moana 2".
            String after = name.substring(at + t.length() + 2);
            if (after.matches("(\\d+|ii|iii|iv|part|chapter)\\b.*")) continue;
            int sc = name.contains(" official ") ? 2 : 1;
            if (sc > bestJw) {
                best = str(o, "externalId");
                bestJw = sc;
            }
        }
        return best;
    }

    /** JustWatch's clips for this title (by its id), from a search for its name; null if not found. */
    JSONArray jwClips(String name) {
        if (id == null || name.isEmpty()) return null;
        try {
            String q = "query T($c: Country!, $l: Language!, $f: TitleFilter) { popularTitles(country: $c, filter: $f, first: 8) {"
                    + " edges { node { id content(country: $c, language: $l) { clips { externalId provider name } } } } } }";
            JSONObject vars = new JSONObject().put("c", "GB").put("l", "en").put("f", new JSONObject().put("searchQuery", name));
            JSONArray edges = Suggestions.post("https://apis.justwatch.com/graphql",
                    new JSONObject().put("query", q).put("variables", vars).toString())
                    .getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
            for (int i = 0; i < edges.length(); i++) {
                JSONObject n = edges.getJSONObject(i).getJSONObject("node");
                if (id.equals(n.optString("id"))) return n.getJSONObject("content").optJSONArray("clips");
            }
        } catch (Exception e) {
            // no trailer, then
        }
        return null;
    }

    /**
     * Plays the trailer, full screen, coming back to this page when it ends or on Back. In YouTube's
     * app when it's here: signed in to your account, so with Premium there's no advert first (an
     * embedded player can't promise that), captions as you last left them there, and only YouTube's
     * controls. Running already, it's straight into the trailer (about 2.7 s on this TV; 4 s from
     * cold). TrailerWatch brings this page back. Without what that needs (notification access, the
     * remote's key service), YouTube's bare player, which closes by itself but starts from a copy
     * of the app that is often signed out (adverts) and takes about 8.5 s. Without YouTube, a player
     * of our own (TrailerActivity).
     */
    void playTrailer(String key) {
        if (Suggestions.installed(this, YOUTUBE)) {
            boolean follow = TrailerWatch.possible(this);
            // From the start (t=0): signed in, YouTube would go on from where a trailer was left.
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key + "&t=0"))
                    .setPackage(YOUTUBE);
            if (!follow) i.putExtra("force_fullscreen", true).putExtra("finish_on_ended", true);
            try {
                startActivity(i);
                if (follow) TrailerWatch.start(this);
                return;
            } catch (android.content.ActivityNotFoundException e) {
                // its player won't take it: ours, then
            }
        }
        startActivity(new Intent(this, TrailerActivity.class).putExtra(TrailerActivity.EXTRA_KEY, key)
                .putExtra(TrailerActivity.EXTRA_TITLE, title.getText().toString()));
        overridePendingTransition(R.anim.page_in, R.anim.hold);
    }

    static final String YOUTUBE = "com.google.android.youtube.tv";

    /**
     * Cast first, then up to two directors, ten in all: {name, character, "Director" or null}, from
     * JustWatch. A series has no director of its own (the ones listed directed an episode or two),
     * and nobody is listed twice.
     */
    static List<String[]> people(JSONArray credits, boolean series) {
        List<String[]> actors = new ArrayList<>(), directors = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; credits != null && i < credits.length(); i++) {
            JSONObject cr = credits.optJSONObject(i);
            if (cr == null) continue;
            String name = str(cr, "name");
            if (name.isEmpty() || !seen.add(SearchBrain.norm(name))) continue;
            if ("ACTOR".equals(cr.optString("role"))) actors.add(new String[]{name, str(cr, "characterName"), null});
            else if (!series && "DIRECTOR".equals(cr.optString("role")) && directors.size() < 2) directors.add(new String[]{name, "Director", "Director"});
        }
        List<String[]> out = new ArrayList<>(actors.subList(0, Math.min(actors.size(), 10 - directors.size())));
        out.addAll(directors);
        return out;
    }

    /** The same from TMDB's credits: its billing order (a series' main cast first), a film's directors. */
    static List<String[]> tmdbPeople(JSONObject credits, boolean series) {
        List<JSONObject> cast = new ArrayList<>();
        JSONArray a = credits.optJSONArray("cast");
        for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) != null) cast.add(a.optJSONObject(i));
        // In billing order; the list's own order otherwise (the sort keeps ties as they were).
        cast.sort((x, y) -> Integer.compare(x.optInt("order", 999), y.optInt("order", 999)));
        List<String[]> directors = new ArrayList<>();
        JSONArray crew = series ? null : credits.optJSONArray("crew");
        for (int i = 0; crew != null && i < crew.length() && directors.size() < 2; i++) {
            JSONObject cr = crew.optJSONObject(i);
            if (cr != null && "Director".equals(cr.optString("job"))) directors.add(new String[]{str(cr, "name"), "Director", "Director"});
        }
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JSONObject p : cast) {
            if (out.size() >= 10 - directors.size()) break;
            String name = str(p, "name");
            if (name.isEmpty() || !seen.add(SearchBrain.norm(name))) continue;
            String role = str(p, "character");
            JSONArray roles = p.optJSONArray("roles");  // a series': the part they played most
            if (role.isEmpty() && roles != null && roles.length() > 0) role = str(roles.optJSONObject(0), "character");
            out.add(new String[]{name, role, null});
        }
        out.addAll(directors);
        return out;
    }

    /** A JSON string that may be null: "" then. */
    static String str(JSONObject o, String k) {
        return o == null || o.isNull(k) ? "" : o.optString(k, "").trim();
    }

    // ---------------------------------------------------------------- How to Watch

    /** JustWatch's service names as the apps call themselves: "Disney Plus" is "Disney+", tiers go. */
    static String tidy(String clear) {
        return clear.replace(" Store", "").replace("Amazon Video", "Prime Video")
                .replace("Netflix Standard with Ads", "Netflix").replace("ITVX Premium", "ITVX")
                .replace("Channel 4 Plus", "Channel 4").replace("Now TV", "NOW")
                .replace("Disney Plus", "Disney+").replace("Paramount Plus", "Paramount+")
                .replace("Apple TV Plus", "Apple TV").replace("Amazon Prime Video", "Prime Video").replace("AcornTV", "Acorn TV")
                .replaceAll("( (Premium|Basic|Standard|Essential|Free with Ads|with Ads))+$", "").trim();
    }

    /**
     * The offers as one card per service, labelled as Apple labels them: a subscription is
     * Stream (never Free); a channel sold through another service is a card of its own, "Apple
     * TV+ on Prime Video", an add-on you pay for; Free only when the whole title is (a free first
     * episode of a series is a free episode, not a free series); a series' price is its cheapest
     * season, never every season added up. Discs and cinemas are left out. Unsorted: the caller
     * ranks them once it knows what's installed.
     */
    static List<Offer> offers(JSONObject n) {
        boolean series = "SHOW".equals(n.optString("objectType"));
        JSONArray a = n.optJSONArray("offers");
        Map<String, Offer> by = new LinkedHashMap<>();
        // Each package's card, so the season offers below go to the right one. A channel's
        // package isn't in it: its free samples are the channel's, not the service's.
        Map<String, Offer> cards = new HashMap<>();
        // Cards with something you pay for (a subscription, or a channel sold on it), and the
        // kinds of free offer each card has for the title as a whole.
        Set<Offer> paid = new HashSet<>();
        Map<Offer, Set<String>> freeKinds = new HashMap<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            JSONObject pk = o == null ? null : o.optJSONObject("package");
            if (pk == null) continue;
            String mt = o.optString("monetizationType"), pt = o.optString("presentationType");
            // Discs and cinemas aren't a way to watch on the TV; free live channels aren't the title.
            if ("CINEMA".equals(mt) || "FAST".equals(mt) || "DVD".equals(pt) || "BLURAY".equals(pt)) continue;
            String own = pk.optString("technicalName"), tech = own, clear = str(pk, "clearName"), icon = str(pk, "icon");
            // Sky Go comes with a Sky TV subscription, not one you'd take out to watch this.
            if ("skygo".equals(own) || clear.equalsIgnoreCase("Sky Go")) continue;
            // Services never suggested as a way to watch, whatever they have.
            if (NEVER.contains(own)) continue;
            JSONObject parent = pk.optJSONObject("addonParent");
            String addon = null;
            if ("amazonprimevideowithads".equals(own) || "amazonimdbtv".equals(own)) {
                // Prime Video's own tiers (with ads; free with ads, once Freevee), which JustWatch
                // lists as its add-ons: they're Prime Video itself.
                tech = "amazonprime";
                clear = "Amazon Prime Video";
                if (parent != null) icon = str(parent, "icon");
            } else if (parent == null && clear.matches("(?i).*apple tv channel$")) {
                // Channels bought in the Apple TV app: the Android TV app has no Channels.
                continue;
            } else if (parent != null || clear.matches("(?i).*amazon channels?$")) {
                // A channel sold through another service ("Apple TV Plus Amazon Channel").
                String home = parent != null ? str(parent, "clearName") : "Amazon Prime Video";
                String ch = clear.replaceAll("(?i)\\s*(amazon|apple tv|roku premium) channels?$", "");
                if (!home.isEmpty() && ch.regionMatches(true, 0, home + " ", 0, home.length() + 1)) ch = ch.substring(home.length() + 1);
                addon = tidy(ch);
                // Apple's service by the name it's sold under there, so its card can't be taken
                // for the Apple TV app's own: "Apple TV+ on Prime Video".
                if (addon.equals("Apple TV")) addon = "Apple TV+";
                tech = parent != null ? parent.optString("technicalName") : "amazonprime";
                clear = home.isEmpty() ? clear : home;
                icon = parent != null ? str(parent, "icon") : "";
            }
            String service = tidy(clear);
            Offer of = card(by, service.toLowerCase(Locale.ROOT), service, tech, icon);
            if (addon == null) {
                cards.put(own, of);
            } else {
                // The channel's own card, on the service's app; the service's card (made here if it
                // has nothing else) only so its free samples are judged as paid-for below.
                paid.add(of);
                Offer home = of;
                of = card(by, "addon:" + tech + ":" + addon.toLowerCase(Locale.ROOT), addon + " on " + service, tech, icon);
                of.service = service;
                of.home = home;
                of.addon = addon;
            }
            // The card opens its best way in: the title itself first, then the store's page for it.
            String url = str(o, "standardWebURL");
            int urlRank = "RENT".equals(mt) || "BUY".equals(mt) ? 2 : 0;
            if (!url.isEmpty() && urlRank < of.urlRank) {
                of.url = url;
                of.urlRank = urlRank;
            }
            String price = str(o, "retailPrice");
            switch (mt) {
                case "FLATRATE":
                    if (addon != null) break;  // its card says Add-on subscription
                    if (PREMIUM_TIERS.contains(own)) {
                        of.premium = true;
                    } else {
                        of.subscription = true;
                        paid.add(of);
                    }
                    break;
                case "FREE":
                case "ADS":
                    if (addon != null) break;  // a paid channel's free sample isn't the service's
                    Set<String> kinds = freeKinds.get(of);
                    if (kinds == null) freeKinds.put(of, kinds = new HashSet<>());
                    kinds.add(mt);
                    break;
                case "RENT":
                    if (series || price.isEmpty()) break;
                    if (of.rent != null && !price.equals(of.rent)) of.rentFrom = true;  // SD, HD and 4K differ
                    if (of.rent == null || cheaper(price, of.rent)) of.rent = price;
                    break;
                case "BUY":
                    // A series' price here is every season together: each season's own comes below.
                    if (series) {
                        of.anyBuy = true;
                        break;
                    }
                    if (price.isEmpty()) break;
                    if (of.buy != null && !price.equals(of.buy)) of.buyFrom = true;
                    if (of.buy == null || cheaper(price, of.buy)) of.buy = price;
                    break;
                default:
                    break;
            }
        }

        // A series, season by season: how many episodes each card has free or with ads, and its
        // cheapest season to buy.
        int total = 0;
        JSONArray seasons = series ? n.optJSONArray("seasons") : null;
        for (int s = 0; seasons != null && s < seasons.length(); s++) {
            JSONObject se = seasons.optJSONObject(s);
            if (se == null) continue;
            int count = se.optInt("totalEpisodeCount", 0);
            total += count;
            JSONObject sc = se.optJSONObject("content");
            int number = sc != null ? sc.optInt("seasonNumber", s + 1) : s + 1;
            // The most episodes each card has (across SD, HD and 4K), free [0] and with ads [1];
            // and the cards selling this season.
            Map<Offer, int[]> most = new HashMap<>();
            Set<Offer> selling = new HashSet<>();
            JSONArray so = se.optJSONArray("offers");
            for (int i = 0; so != null && i < so.length(); i++) {
                JSONObject o = so.optJSONObject(i);
                JSONObject pk = o == null ? null : o.optJSONObject("package");
                Offer of = pk == null ? null : cards.get(pk.optString("technicalName"));
                String pt = o == null ? "" : o.optString("presentationType");
                if (of == null || "DVD".equals(pt) || "BLURAY".equals(pt)) continue;
                String mt = o.optString("monetizationType");
                if ("BUY".equals(mt)) {
                    double v = o.optDouble("retailPriceValue", 0);
                    String price = str(o, "retailPrice");
                    if (v <= 0 || price.isEmpty()) continue;
                    if (selling.add(of)) of.seasonsForSale++;
                    if (of.buyValue <= 0 || v < of.buyValue) {
                        of.buy = price;
                        of.buyValue = v;
                        of.buyFrom = true;
                        of.buySeason = number;
                    }
                } else if ("FREE".equals(mt) || "ADS".equals(mt)) {
                    int[] got = most.get(of);
                    if (got == null) most.put(of, got = new int[2]);
                    int k = "FREE".equals(mt) ? 0 : 1;
                    got[k] = Math.max(got[k], Math.min(count, o.optInt("elementCount", 0)));
                }
            }
            for (Map.Entry<Offer, int[]> e : most.entrySet()) {
                for (int k = 0; k < 2; k++) {
                    int got = e.getValue()[k];
                    if (got <= 0) continue;
                    Cover cv = e.getKey().cover[k];
                    cv.eps += got;
                    if (got >= count) cv.seasons.add(number);
                    else cv.partial = true;
                }
            }
        }

        for (Offer of : by.values()) {
            Set<String> kinds = freeKinds.get(of);
            for (int k = 0; kinds != null && k < 2; k++) {
                if (!kinds.contains(k == 0 ? "FREE" : "ADS")) continue;
                Cover cv = of.cover[k];
                boolean full;
                // Free means all of it. Where you also pay, every episode; elsewhere (a free
                // service still adding a series) most of them will do.
                if (!series) full = true;
                else if (total == 0 || cv.eps == 0) full = !paid.contains(of);  // no season detail: the title's own word
                else full = cv.eps >= total || (!paid.contains(of) && cv.eps * 2 >= total);
                if (full) {
                    if (k == 0) of.free = true;
                    else of.ads = true;
                } else if (cv.eps > 0 && of.part == null) {
                    of.part = part(cv, k == 1);
                }
            }
            // A broadcaster's ad-free tier is its Stream only when nothing there is free.
            if (of.premium && !of.free && !of.ads && of.part == null) of.subscription = true;
        }
        // A service whose only offer is the free first episode of a channel it sells (Prime
        // Video's, for Slow Horses): that's the channel's sample, not the service's.
        for (Offer of : by.values()) {
            Offer h = of.home;
            if (h != null && !h.free && !h.ads && !h.subscription && h.rent == null && h.buy == null && !h.anyBuy) h.part = null;
        }
        List<Offer> out = new ArrayList<>();
        for (Offer of : by.values()) {
            if (of.addon != null || of.free || of.ads || of.subscription || of.part != null
                    || of.rent != null || of.buy != null || of.anyBuy) out.add(of);
        }
        return out;
    }

    /** The service's card, or a new one for it. */
    static Offer card(Map<String, Offer> by, String key, String name, String tech, String icon) {
        Offer of = by.get(key);
        if (of == null) {
            of = new Offer();
            of.name = name;
            of.service = name;
            by.put(key, of);
        }
        // Tiers of one service (with ads, premium) open the one app.
        if (of.pkg == null) of.pkg = APPS.get(tech);
        if (of.icon == null && !icon.isEmpty()) of.icon = icon;
        return of;
    }

    /** Some of a series free: "Free episode", "Season 1 free with ads", "Seasons 1–3 free", "12 episodes free". */
    static String part(Cover cv, boolean ads) {
        if (cv.eps == 1) return "Free episode";
        String how = ads ? " free with ads" : " free";
        List<Integer> s = cv.seasons;
        if (!cv.partial && !s.isEmpty()) {
            Collections.sort(s);
            int first = s.get(0), last = s.get(s.size() - 1);
            if (s.size() == 1) return "Season " + first + how;
            if (last - first + 1 == s.size()) return "Seasons " + first + "–" + last + how;
            return s.size() + " seasons" + how;
        }
        return cv.eps + " episodes" + how;
    }

    static boolean cheaper(String a, String b) {
        try {
            return Double.parseDouble(a.replaceAll("[^0-9.]", "")) < Double.parseDouble(b.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * The card's terms: how you watch it there, then what it costs to keep. "Free", "Free with
     * ads", "Stream", "Add-on subscription", "Free episode"; "Rent £3.49  ·  Buy £7.99" for a
     * film ("from" where SD, HD and 4K differ); "Buy from £8.99", or "Buy Season 1 £12.99" when
     * it's the only season for sale, for a series.
     */
    static String terms(Offer o) {
        List<String> p = new ArrayList<>();
        String access = o.free ? "Free" : o.ads ? "Free with ads" : o.subscription ? "Stream"
                : o.addon != null ? "Add-on subscription" : o.part;
        if (access != null) p.add(access);
        if (o.rent != null) p.add((o.rentFrom ? "Rent from " : "Rent ") + o.rent);
        if (o.buy != null) {
            p.add(o.buySeason > 0 && o.seasonsForSale == 1 ? "Buy Season " + o.buySeason + " " + o.buy
                    : (o.buyFrom ? "Buy from " : "Buy ") + o.buy);
        } else if (o.anyBuy) {
            p.add("Buy");
        }
        return TextUtils.join("  ·  ", p);
    }

    void open(Offer o) {
        if (o.pkg == null) return;
        if (!Suggestions.installed(this, o.pkg)) {
            Suggestions.start(this, new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + o.pkg)));
            return;
        }
        if (o.url != null && !o.url.isEmpty()) {
            Intent v = Suggestions.view(o.url, o.pkg);
            if (v.resolveActivity(getPackageManager()) != null && Suggestions.start(this, v)) return;
        }
        Intent launch = Suggestions.launchIntent(this, o.pkg);
        if (launch != null) Suggestions.start(this, launch);
    }

    /**
     * Rises and fades in. What fades this way is set to fade its parts (forceHasOverlappingRendering
     * false): a group (the buttons, How to Watch's row) or text with a shadow (credits) was otherwise
     * drawn off screen and faded as one picture, every frame of the fade (How to Watch's row alone
     * took 9 ms a frame on this TV as the page opened). Their parts don't overlap, so it looks the same.
     */
    void fadeIn(View v, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(u(6));
        v.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(420).setInterpolator(MainActivity.EASE).start();
    }

    // ---------------------------------------------------------------- controls

    /**
     * A button: a glass capsule that turns white when focused, its plate growing 4 px each way
     * while the words stay sharp (no scaling, no layer), on the Apple TV app's button timing.
     */
    View pill(String label, View.OnClickListener click) {
        Glass white = new Glass(u(40), u(0.6f), Glass.REGULAR);
        white.grow = u(2);
        TextView t = text(15.5f, "semibold", Color.WHITE);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(u(22), 0, u(22), 0);
        t.setBackground(white);
        t.setFocusable(true);
        t.setClickable(true);
        white.tint = f -> t.setTextColor((int) EV.evaluate(f, Color.WHITE, 0xFF111114));
        t.setOnFocusChangeListener((v, has) -> Glass.focus(white, has, 75, 150, FOCUS_IN, FOCUS_OUT));
        t.setOnClickListener(click);
        // At either end of the buttons, Left and Right stay put: there was nothing beside it, and
        // focus jumped down into Related.
        t.setOnKeyListener((v, k, e) -> e.getAction() == KeyEvent.ACTION_DOWN && rowEnd(actions, v, k));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, u(37));
        if (actions.getChildCount() > 0) lp.leftMargin = u(8);
        actions.addView(t, lp);
        return t;
    }

    /**
     * One way to watch: the service's picture, its name and its terms, on a faint platter that
     * brightens when focused (white 10% to 20%, as the Apple TV app's; the words stay white).
     */
    View way(Offer o) {
        GradientDrawable platter = new GradientDrawable();
        platter.setCornerRadius(u(15));
        platter.setColor(0x1AFFFFFF);
        // A hairline rim, as glass has: over dark artwork the platter alone melts into it, and two
        // cards side by side read as one.
        platter.setStroke(Math.max(1, u(0.6f)), 0x1FFFFFFF);
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(u(10), 0, u(16), 0);
        t.setBackground(platter);
        t.setFocusable(true);
        View pic;
        if (o.installed && o.art != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(o.art);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setClipToOutline(true);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(u(7));
            icon.setBackground(shape);
            pic = icon;
        } else {
            // Not installed: the service's initial on a glass tile, then its logo once it's here
            // (logos()), so every card has a picture, as Apple's do.
            FrameLayout tile = new FrameLayout(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0x33FFFFFF);
            g.setCornerRadius(u(7));
            tile.setBackground(g);
            TextView initial = text(15, "semibold", Color.WHITE);
            initial.setText(o.service.isEmpty() ? "" : o.service.substring(0, 1));
            tile.addView(initial, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            if (o.icon != null) tiles.put(o, tile);
            pic = tile;
        }
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(u(56), u(32));
        il.rightMargin = u(10);
        t.addView(pic, il);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(13.5f, "semibold", Color.WHITE);
        name.setText(o.name);
        name.setSingleLine(true);
        TextView sub = text(11.5f, "regular", 0x80FFFFFF);
        sub.setText(terms(o) + (o.installed || o.pkg == null ? "" : "  ·  Get the app"));
        sub.setSingleLine(true);
        texts.addView(name);
        texts.addView(sub);
        t.addView(texts);
        float[] lit = {0f};
        ValueAnimator[] running = {null};
        t.setOnFocusChangeListener((v, has) -> {
            if (running[0] != null) running[0].cancel();  // from where it is, so a quick in-out never jumps
            ValueAnimator a = ValueAnimator.ofFloat(lit[0], has ? 1f : 0f);
            a.setDuration(has ? 85 : 230);
            a.setInterpolator(has ? FOCUS_IN : FOCUS_OUT);
            a.addUpdateListener(an -> {
                lit[0] = (float) an.getAnimatedValue();
                platter.setColor((int) EV.evaluate(lit[0], 0x1AFFFFFF, 0x33FFFFFF));
            });
            running[0] = a;
            a.start();
            // It grows as its platter brightens, on the Apple TV app's card timing (no spring).
            // Drawn afresh at each size: a layer over a platter that changes every frame would be
            // redrawn anyway.
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(has ? 85 : 230)
                    .setInterpolator(has ? FOCUS_IN : FOCUS_OUT).start();
        });
        if (o.pkg != null) t.setOnClickListener(v -> open(o));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, u(54));
        if (ways.items.getChildCount() > 0) lp.leftMargin = u(10);
        ways.items.addView(t, lp);
        return t;
    }

    // ---------------------------------------------------------------- below the fold

    /** Related titles {id, objectType, title, poster, backdrop, tmdb id}, once they've arrived. */
    List<String[]> related;
    /** The shelves below the fold have been built. */
    boolean built;

    /**
     * Related titles (on `rel`), for the shelf below How to Watch: the same as Search's titles like
     * it (SearchBrain.similar), so only well-known titles of the same kind, closest first.
     */
    void related(String type, int tmdb) {
        if (id == null) return;
        SearchActivity.Result to = new SearchActivity.Result();
        to.id = id;
        to.type = "tv".equals(type) ? "tv" : "movie";
        to.tmdbId = tmdb;
        List<String[]> out = new ArrayList<>();
        for (SearchActivity.Result r : new SearchBrain(this).similar(to, this::gone)) {
            if (r.poster == null || id.equals(r.id)) continue;
            out.add(new String[]{r.id, "tv".equals(r.type) ? "SHOW" : "MOVIE", r.title, r.poster, r.backdrop, String.valueOf(r.tmdbId)});
            if (out.size() >= 12) break;
        }
        if (!out.isEmpty()) ui.post(() -> {
            if (isDestroyed()) return;
            related = out;
            if (built) relatedRow(out);
        });
    }

    void sections(JSONObject n, JSONObject c, boolean series, List<String[]> people) {
        if (isDestroyed()) return;
        built = true;
        if (related != null) relatedRow(related);
        // One shelf a frame: built together they took more than a frame between them, and the
        // backdrop still fading in above stuttered.
        JSONObject ext = c.optJSONObject("externalIds");
        ui.post(() -> {
            if (isDestroyed()) return;
            if (!people.isEmpty()) castRow(people, series, ext == null ? 0 : Suggestions.parseInt(ext.optString("tmdbId")));
            ui.post(() -> {
                if (!isDestroyed()) information(n, c, series);
            });
        });
    }

    /** A shelf below the fold: its title (grey, as the Apple TV app's), then whatever it holds. */
    LinearLayout section(String name) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setClipChildren(false);
        TextView h = text(15.5f, "semibold", 0x80FFFFFF);
        h.setText(name);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.leftMargin = u(40);
        hl.topMargin = u(10);
        hl.bottomMargin = u(7);
        s.addView(h, hl);
        s.setAlpha(0f);
        s.animate().alpha(1f).setDuration(240).setInterpolator(LINEAR).start();
        return s;
    }

    /** Related: 2:3 posters whose titles show only when focused. */
    void relatedRow(List<String[]> items) {
        LinearLayout s = section("Related");
        // Room below for the focused poster's title, which drops in under it.
        Row row = new Row(u(40), u(12), u(14));
        List<ImageView> posters = new ArrayList<>();
        for (String[] it : items) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setClipChildren(false);
            tile.setFocusable(true);
            tile.setClickable(true);
            FrameLayout frame = new FrameLayout(this);
            GradientDrawable plate = new GradientDrawable();
            plate.setColor(0x1AFFFFFF);
            plate.setCornerRadius(u(10));
            frame.setBackground(plate);
            frame.setClipToOutline(true);
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setAlpha(0f);
            frame.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            posters.add(poster);
            tile.addView(frame, new LinearLayout.LayoutParams(posterW, u(195)));
            TextView caption = text(15.5f, "medium", Color.WHITE);
            caption.setText(it[2]);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setGravity(Gravity.CENTER_HORIZONTAL);
            caption.setAlpha(0f);
            // A little wider than the poster, as the poster is when focused, so a name like
            // "Blade Runner 2049" fits; the tile keeps the poster's width.
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(posterW + u(30), -2);
            cl.leftMargin = cl.rightMargin = -u(15);
            cl.topMargin = u(8);
            tile.addView(caption, cl);
            // Focus lifts the poster by 40 px and shows its title beneath, as on tvOS.
            tile.setOnFocusChangeListener((v, has) -> {
                frame.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(has ? 85 : 230)
                        .setInterpolator(has ? FOCUS_IN : FOCUS_OUT).start();
                caption.animate().alpha(has ? 1f : 0f).translationY(has ? u(10) : 0).setDuration(has ? 85 : 230)
                        .setInterpolator(has ? FOCUS_IN : FOCUS_OUT).start();
                ui.removeCallbacks(aheadRun);
                if (has) {
                    aheadRun = () -> ahead(this, it[0], Suggestions.parseInt(it[5]), "SHOW".equals(it[1]) ? "tv" : "movie");
                    ui.postDelayed(aheadRun, AHEAD_MS);
                }
            });
            tile.setOnClickListener(v -> openRelated(it));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (row.items.getChildCount() > 0) lp.leftMargin = u(20);
            row.items.addView(tile, lp);
        }
        s.addView(row, new LinearLayout.LayoutParams(-1, -2));
        // Arriving after you've gone down to a shelf below: the page moves down with it, so the
        // shelf you're on stays where it is rather than being pushed down (or off) the screen.
        boolean below = false;
        for (View v = getCurrentFocus(); v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == sections) below = true;
        }
        sections.addView(s, 0);
        if (below) {
            page.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    page.getViewTreeObserver().removeOnPreDrawListener(this);
                    int h = s.getHeight();
                    page.removeCallbacks(scrollStart);
                    if (scroller != null) scroller.cancel();
                    scrollTarget += h;
                    page.scrollTo(0, page.getScrollY() + h);
                    return true;
                }
            });
        }
        // The posters, one by one off the UI thread, each fading in as it's ready: in full colour
        // and a size above, as they're shown 1.1 times larger when focused.
        side.execute(() -> {
            for (int i = 0; i < items.size() && !gone(); i++) {
                Bitmap bm = Suggestions.onGpu(Suggestions.image(this, items.get(i)[3], "", Math.round(posterW * 1.1f)));
                ImageView iv = posters.get(i);
                if (bm != null) ui.post(() -> {
                    if (isDestroyed()) return;
                    iv.setImageBitmap(bm);
                    iv.animate().alpha(1f).setDuration(240).setInterpolator(LINEAR).start();
                });
            }
        });
    }

    /**
     * How long a title rests in focus before its page's details are asked for (ahead): long enough
     * that moving along a row asks for none on the way.
     */
    static final long AHEAD_MS = 450;
    Runnable aheadRun = () -> { };

    /** Another title's page. Two deep at most: a third replaces the one beneath it. */
    void openRelated(String[] it) {
        startActivity(new Intent(this, DetailActivity.class)
                .putExtra("id", it[0]).putExtra("title", it[2]).putExtra("backdrop", it[4])
                .putExtra("tmdb", Suggestions.parseInt(it[5])).putExtra("type", "SHOW".equals(it[1]) ? "tv" : "movie")
                .putExtra("depth", depth + 1));
        if (depth >= 1) {
            replacing = true;
            finish();
        }
        overridePendingTransition(R.anim.page_in, R.anim.hold);
    }

    /**
     * Cast & Crew as tvOS's monograms: each person's photo in a circle (from TMDB, with a key), their
     * initials until it comes or when there's none, then the name and the character or "Director".
     */
    void castRow(List<String[]> people, boolean series, int tmdb) {
        LinearLayout s = section("Cast & Crew");
        Row row = new Row(u(40), u(10), u(4));
        Map<String, FrameLayout> circles = new HashMap<>();
        // With a TMDB key Search can find a person's films and series; without one, nothing to open.
        boolean find = Suggestions.hasToken(this);
        for (String[] p : people) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setClipChildren(false);
            tile.setFocusable(true);
            tile.setClickable(find);
            FrameLayout circle = new FrameLayout(this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF5A5A60, 0xFF2E2E33});
            g.setShape(GradientDrawable.OVAL);
            circle.setBackground(g);
            circle.setClipToOutline(true);  // the photo cut to the circle
            circles.put(SearchBrain.norm(p[0]), circle);
            TextView ini = text(30, "semibold", Color.WHITE);
            ini.setText(initials(p[0]));
            circle.addView(ini, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            tile.addView(circle, new LinearLayout.LayoutParams(u(90), u(90)));
            TextView name = text(12.5f, "medium", Color.WHITE);
            name.setText(p[0]);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(u(110), -2);
            nl.topMargin = u(10);
            tile.addView(name, nl);
            TextView role = text(11.5f, "regular", 0x80FFFFFF);
            role.setText(p[1]);
            role.setSingleLine(true);
            role.setEllipsize(TextUtils.TruncateAt.END);
            role.setGravity(Gravity.CENTER);
            tile.addView(role, new LinearLayout.LayoutParams(u(110), -2));
            tile.setOnFocusChangeListener((v, has) -> circle.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f)
                    .setDuration(has ? 85 : 230).setInterpolator(has ? FOCUS_IN : FOCUS_OUT).start());
            if (find) {
                // Search, which closes the title pages above it (it's a single task), looks them up.
                tile.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class).putExtra("q", p[0])));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (row.items.getChildCount() > 0) lp.leftMargin = u(14);
            row.items.addView(tile, lp);
        }
        s.addView(row, new LinearLayout.LayoutParams(-1, -2));
        sections.addView(s);
        if (tmdb > 0 && Suggestions.hasToken(this)) side.execute(() -> photos(series, tmdb, circles));
    }

    /** The cast's photos from TMDB, each fading into its circle as it arrives. */
    void photos(boolean series, int tmdb, Map<String, FrameLayout> circles) {
        Map<String, String> paths = new HashMap<>();
        try {
            JSONObject cr = tmdbCast != null ? tmdbCast
                    : Suggestions.get(this, "/" + (series ? "tv/" + tmdb + "/aggregate_credits" : "movie/" + tmdb + "/credits"));
            for (String part : new String[]{"cast", "crew"}) {
                JSONArray a = cr.optJSONArray(part);
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    String path = o == null ? "" : str(o, "profile_path");
                    if (!path.isEmpty()) paths.putIfAbsent(SearchBrain.norm(str(o, "name")), path);
                }
            }
        } catch (Exception e) {
            return;  // no photos: the initials stay
        }
        int size = Math.round(u(90) * 1.15f);
        for (Map.Entry<String, FrameLayout> e : circles.entrySet()) {
            String path = paths.get(e.getKey());
            if (path == null || gone()) continue;
            Bitmap b = Suggestions.onGpu(Suggestions.image(this, path, "w185", size));
            if (b == null) continue;
            FrameLayout circle = e.getValue();
            ui.post(() -> {
                if (isDestroyed()) return;
                ImageView face = new ImageView(this);
                face.setScaleType(ImageView.ScaleType.CENTER_CROP);
                face.setImageBitmap(b);
                face.setAlpha(0f);
                circle.addView(face, new FrameLayout.LayoutParams(-1, -1));
                face.animate().alpha(1f).setDuration(240).setInterpolator(LINEAR).start();
            });
        }
    }

    /** "GO" for Gary Oldman, "RD" for Robert Downey Jr. */
    static String initials(String name) {
        List<String> w = new ArrayList<>(Arrays.asList(name.trim().split("\\s+")));
        if (w.size() > 1 && w.get(w.size() - 1).matches("(?i)(jr|sr)\\.?|ii|iii|iv")) w.remove(w.size() - 1);
        String a = w.get(0).isEmpty() ? "" : w.get(0).substring(0, 1);
        String b = w.size() > 1 ? w.get(w.size() - 1).substring(0, 1) : "";
        return (a + b).toUpperCase(Locale.UK);
    }

    /** Information: one card of facts in three columns; only its platter brightens when focused. */
    void information(JSONObject n, JSONObject c, boolean series) {
        List<String[]> facts = new ArrayList<>();
        List<String> genres = new ArrayList<>();
        JSONArray g = c.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length() && genres.size() < 3; i++) {
            String name = Suggestions.JW_GENRE_NAMES.get(g.optJSONObject(i).optString("shortName"));
            if (name != null) genres.add(name);
        }
        if (!genres.isEmpty()) facts.add(new String[]{"Genre", TextUtils.join(", ", genres)});
        int y = c.optInt("originalReleaseYear", 0);
        if (y > 0) facts.add(new String[]{"Released", String.valueOf(y)});
        String length = series ? seasons(n.optInt("totalSeasonCount", 0)) : runtime(c.optInt("runtime", 0));
        if (!length.isEmpty()) facts.add(new String[]{series ? "Seasons" : "Run Time", length});
        String rated = str(c, "ageCertification");
        if (!rated.isEmpty()) facts.add(new String[]{"Rated", rated});
        List<String> places = new ArrayList<>();
        JSONArray pc = c.optJSONArray("productionCountries");
        for (int i = 0; pc != null && i < pc.length() && places.size() < 3; i++) {
            String code = pc.optString(i, "");
            if (code.length() == 2) places.add(new Locale("", code).getDisplayCountry(Locale.UK));
        }
        if (!places.isEmpty()) facts.add(new String[]{"Region of Origin", TextUtils.join(", ", places)});
        JSONObject sc = c.optJSONObject("scoring");
        List<String> ratings = new ArrayList<>();
        double imdb = sc == null ? 0 : sc.optDouble("imdbScore", 0);
        if (imdb > 0) ratings.add(String.format(Locale.UK, "IMDb %.1f", imdb));
        if (sc != null && !sc.isNull("tomatoMeter")) ratings.add("Rotten Tomatoes " + sc.optInt("tomatoMeter") + "%");
        if (!ratings.isEmpty()) facts.add(new String[]{"Ratings", TextUtils.join(", ", ratings)});
        if (facts.isEmpty()) return;

        LinearLayout s = section("Information");
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(u(20), u(14), u(20), u(4));
        card.setFocusable(true);
        GradientDrawable platter = new GradientDrawable();
        platter.setCornerRadius(u(15));
        platter.setColor(0x1AFFFFFF);
        card.setBackground(platter);
        float[] lit = {0f};
        ValueAnimator[] running = {null};
        card.setOnFocusChangeListener((v, has) -> {
            if (running[0] != null) running[0].cancel();  // from where it is, so a quick in-out never jumps
            ValueAnimator a = ValueAnimator.ofFloat(lit[0], has ? 1f : 0f);
            a.setDuration(has ? 85 : 230);
            a.setInterpolator(has ? FOCUS_IN : FOCUS_OUT);
            a.addUpdateListener(an -> {
                lit[0] = (float) an.getAnimatedValue();
                platter.setColor((int) EV.evaluate(lit[0], 0x1AFFFFFF, 0x33FFFFFF));
            });
            running[0] = a;
            a.start();
        });
        LinearLayout line = null;
        for (int i = 0; i < facts.size(); i++) {
            if (i % 3 == 0) {
                line = new LinearLayout(this);
                card.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(0, 0, u(16), u(12));
            TextView label = text(11.5f, "semibold", 0x80FFFFFF);
            label.setText(facts.get(i)[0]);
            TextView value = text(13, "regular", 0xE6FFFFFF);
            value.setText(facts.get(i)[1]);
            value.setMaxLines(2);
            value.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(label);
            cell.addView(value);
            line.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        // Keep the columns even when the last line is short.
        for (int i = facts.size() % 3; i > 0 && i < 3; i++) line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.leftMargin = cl.rightMargin = u(40);
        s.addView(card, cl);
        sections.addView(s);
    }

    // ---------------------------------------------------------------- scrolling

    int scrollTarget;
    ValueAnimator scroller;

    /**
     * The page follows focus: in the first screen it sits at the top; on a shelf below, that
     * shelf comes up to near the top. It starts four frames after focus moves, as the Apple TV
     * app does, so the tile's zoom leads and the page follows.
     */
    void follow(View f) {
        if (f == null || isDestroyed()) return;
        int target = 0;
        for (View v = f; v != null && v != content; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v.getParent() == sections) {
                target = sections.getTop() + v.getTop() - u(30);
                break;
            }
        }
        target = Math.max(0, Math.min(target, content.getHeight() - page.getHeight()));
        if (target == scrollTarget) return;
        scrollTarget = target;
        page.removeCallbacks(scrollStart);
        page.postDelayed(scrollStart, 67);
    }

    final Runnable scrollStart = () -> {
        // From wherever it is now, so a quick second press turns it rather than restarting it.
        if (scroller != null) scroller.cancel();
        scroller = ValueAnimator.ofInt(page.getScrollY(), scrollTarget);
        scroller.setDuration(700);
        scroller.setInterpolator(SCROLL);
        scroller.addUpdateListener(a -> page.scrollTo(0, (int) a.getAnimatedValue()));
        scroller.start();
    };

    /** The page's own scroll: moved only by follow(), never by Android's jump to the focused view. */
    static final class Pager extends ScrollView {
        Pager(Context c) {
            super(c);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            return 0;
        }

        @Override
        public boolean executeKeyEvent(KeyEvent e) {
            return false;  // the arrows move focus; follow() moves the page
        }
    }

    /**
     * A shelf that runs past the screen's edges: focus walks along the tiles, and the row moves
     * only when the next tile would cross the margin, by just enough to show it, on the Apple TV
     * app's row curve (Android's own would jump, or fling).
     */
    /** Left or Right with nothing more that way in this row (the focus would leave it sideways). */
    static boolean rowEnd(ViewGroup row, View from, int key) {
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        return android.view.FocusFinder.getInstance().findNextFocus(row, from,
                key == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : View.FOCUS_LEFT) == null;
    }

    final class Row extends HorizontalScrollView {
        final LinearLayout items;
        final int edge;
        ValueAnimator glide;

        Row(int edge, int top, int bottom) {
            super(DetailActivity.this);
            this.edge = edge;
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            // Room above and below for a focused tile's lift and its title, inside the tiles' own
            // row: the scroller cuts the row off at the row's edges, so room left on the scroller
            // went unused (a lifted cast circle was cut flat at the top, a title in half at the bottom).
            setClipToPadding(false);
            items = new LinearLayout(DetailActivity.this);
            items.setClipChildren(false);
            items.setClipToPadding(false);
            items.setPadding(edge, top, edge, bottom);
            addView(items);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            return 0;
        }

        @Override
        public boolean executeKeyEvent(KeyEvent e) {
            return false;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            // At the row's end, Left and Right stay put rather than leap to another row.
            View f = findFocus();
            if (e.getAction() == KeyEvent.ACTION_DOWN && f != null && rowEnd(items, f, e.getKeyCode())) return true;
            return super.dispatchKeyEvent(e);
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            View tile = focused;
            while (tile != null && tile.getParent() != items) tile = tile.getParent() instanceof View ? (View) tile.getParent() : null;
            if (tile == null || getWidth() == 0) return;
            int x = getScrollX(), to = x;
            if (tile.getLeft() - x < edge) to = tile.getLeft() - edge;
            else if (tile.getRight() - x > getWidth() - edge) to = tile.getRight() - getWidth() + edge;
            to = Math.max(0, Math.min(to, items.getWidth() - getWidth()));
            if (to == x) return;
            if (glide != null) glide.cancel();
            glide = ValueAnimator.ofInt(x, to);
            glide.setDuration(800);
            glide.setInterpolator(SCROLL);
            glide.addUpdateListener(a -> scrollTo((int) a.getAnimatedValue(), 0));
            glide.start();
        }
    }
}

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
            + " package { clearName technicalName icon(profile: S100)"
            + " addonParent(country: $c, platform: WEB) { technicalName clearName icon(profile: S100) } } } }"
            + " ... on Show { totalSeasonCount seasons { totalEpisodeCount content(country: $c, language: $l) { seasonNumber }"
            + " offers(country: $c, platform: WEB, filter: {monetizationTypes: [FREE, ADS, BUY]}) { monetizationType"
            + " presentationType elementCount retailPrice(language: $l) retailPriceValue package { technicalName } } } } } }";

    /** Related titles, asked for alongside the details (a separate, smaller answer). */
    static final String SIMILAR = "query R($id: ID!, $c: Country!, $l: Language!) { node(id: $id) { ... on MovieOrShow {"
            + " similarTitlesV2(country: $c, first: 12) { edges { node { id objectType content(country: $c, language: $l) {"
            + " title posterUrl backdrops { backdropUrl } externalIds { tmdbId } } } } } } } }";

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
        APPS.put("paramountplus", "com.cbs.ott");
        APPS.put("discoverypluseurope", "com.discovery.dplay");
        APPS.put("youtube", "com.google.android.youtube.tv");
        APPS.put("plex", "com.plexapp.android");
    }

    /**
     * A broadcaster's own ad-free tier (ITVX Premium, Channel 4 Plus): its card is the broadcaster's,
     * where the title is free with ads, so the tier never turns that free run into a sample.
     */
    static final Set<String> PREMIUM_TIERS = new HashSet<>(Arrays.asList("itvpremium", "all4plus"));

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
    Pager page;
    LinearLayout content, hero, header, actions, sections;
    Row ways;
    TextView title, headerText, age, synopsis, meta, credits, waysLabel;
    String titleText, id;
    /** How many title pages deep this one is, opened from Related. */
    int depth;
    int screenW, posterW, iconW, logoW;

    int u(float units) {
        return Ui.px(this, units);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        titleText = in.getStringExtra("title");
        id = in.getStringExtra("id");
        depth = in.getIntExtra("depth", 0);
        screenW = Math.min(1920, Ui.screen(this).x);
        posterW = u(130);
        iconW = u(28);
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
        hero.addView(slot);

        // "TV Show · Crime · Drama", then the age rating in an outlined box.
        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setAlpha(0f);
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
        row.addView(actions, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.BOTTOM));
        credits = text(12, "regular", 0xE6FFFFFF);
        credits.setMaxWidth(u(300));
        credits.setMaxLines(3);
        credits.setEllipsize(TextUtils.TruncateAt.END);
        credits.setLineSpacing(0, 1.1f);
        credits.setAlpha(0f);
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
        io.execute(() -> {
            try {
                JSONObject vars = new JSONObject().put("id", id).put("c", "GB").put("l", "en");
                JSONObject res = Suggestions.post("https://apis.justwatch.com/graphql",
                        new JSONObject().put("query", QUERY).put("variables", vars).toString());
                JSONObject n = res.getJSONObject("data").getJSONObject("node");
                // Sorted out here, with each app's banner, so the page builds in one go.
                List<Offer> offers = offers(n);
                PackageManager pm = getPackageManager();
                for (Offer o : offers) {
                    o.installed = o.pkg != null && Suggestions.installed(this, o.pkg);
                    o.art = o.installed ? banner(pm, o.pkg) : null;
                    o.rank = (o.free || o.ads ? 0 : o.subscription ? 1 : o.addon != null || o.part != null ? 2 : 3) + (o.installed ? 0 : 1);
                }
                offers.sort((x, y) -> Integer.compare(x.rank, y.rank));
                ui.post(() -> show(n, offers));
            } catch (Exception e) {
                ui.post(() -> {
                    if (isDestroyed()) return;
                    meta.setText("Details aren’t available right now.");
                    fadeIn(meta, 0);
                });
            }
        });
        rel.execute(this::related);
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
        backdrop.animate().alpha(dimmed(page.getScrollY())).scaleX(1f).scaleY(1f).setDuration(900)
                .setInterpolator(MainActivity.HERO_EASE).withEndAction(() -> {
                    settled = true;
                    dim(page.getScrollY());
                }).start();
    }

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
        if (settled) backdrop.setAlpha(dimmed(y));
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
     * doesn't wait for them): each fades in over its initial.
     */
    void logos(List<Offer> want) {
        for (Offer o : want) {
            if (gone()) return;
            Bitmap b = Suggestions.onGpu(Suggestions.image(this, "https://images.justwatch.com" + o.icon.replace("{format}", "png"), "", iconW));
            if (b == null) continue;
            ui.post(() -> {
                FrameLayout tile = tiles.get(o);
                if (tile == null || isDestroyed()) return;
                ImageView icon = new ImageView(this);
                icon.setImageBitmap(b);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setAlpha(0f);
                tile.addView(icon, new FrameLayout.LayoutParams(iconW, iconW, Gravity.CENTER));
                View initial = tile.getChildAt(0);
                icon.animate().alpha(1f).setDuration(240).setInterpolator(LINEAR).start();
                initial.animate().alpha(0f).setDuration(240).setInterpolator(LINEAR).start();
            });
        }
    }

    void show(JSONObject n, List<Offer> offers) {
        if (isDestroyed()) return;
        JSONObject c = n.optJSONObject("content");
        if (c == null) return;
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
        List<String[]> people = people(c.optJSONArray("credits"));
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

        fadeIn(header, 0);
        fadeIn(synopsis, 40);
        fadeIn(meta, 80);
        fadeIn(credits, 120);

        actions.removeAllViews();
        View first = primary(offers);
        ways.items.removeAllViews();
        tiles.clear();
        for (Offer o : offers) way(o);
        if (!tiles.isEmpty()) {
            List<Offer> want = new ArrayList<>(tiles.keySet());
            io.execute(() -> logos(want));
        }
        if (offers.isEmpty()) {
            TextView none = text(12.5f, "regular", 0x99FFFFFF);
            none.setText("Not available to stream, rent or buy in the UK right now.");
            ways.items.addView(none);
        }
        if (first != null) first.requestFocus();
        else if (!offers.isEmpty()) ways.items.getChildAt(0).requestFocus();
        fadeIn(actions, 120);
        fadeIn(waysLabel, 160);
        fadeIn(ways, 200);
        // The shelves below the fold once the page has settled, so their building and pictures
        // stay out of the fade-ups.
        ui.postDelayed(() -> sections(n, c, series, people), 700);
    }

    /**
     * The one main button, as Apple's: Watch on the best free service you have;
     * else Open in the service or store you have (you may not subscribe, so it doesn't promise
     * to play: you buy, rent, subscribe or add the channel there); else Get the app that streams it.
     */
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

    /** Cast first, then up to two directors, ten in all: {name, character, "Director" or null}. */
    static List<String[]> people(JSONArray credits) {
        List<String[]> actors = new ArrayList<>(), directors = new ArrayList<>();
        for (int i = 0; credits != null && i < credits.length(); i++) {
            JSONObject cr = credits.optJSONObject(i);
            if (cr == null) continue;
            String name = str(cr, "name");
            if (name.isEmpty()) continue;
            if ("ACTOR".equals(cr.optString("role"))) actors.add(new String[]{name, str(cr, "characterName"), null});
            else if ("DIRECTOR".equals(cr.optString("role")) && directors.size() < 2) directors.add(new String[]{name, "Director", "Director"});
        }
        List<String[]> out = new ArrayList<>(actors.subList(0, Math.min(actors.size(), 10 - directors.size())));
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

    /** Asks JustWatch for related titles (on `rel`), for the shelf below How to Watch. */
    void related() {
        if (id == null) return;
        try {
            JSONObject vars = new JSONObject().put("id", id).put("c", "GB").put("l", "en");
            JSONObject res = Suggestions.post("https://apis.justwatch.com/graphql",
                    new JSONObject().put("query", SIMILAR).put("variables", vars).toString());
            JSONObject sim = res.getJSONObject("data").getJSONObject("node").optJSONObject("similarTitlesV2");
            JSONArray edges = sim == null ? null : sim.optJSONArray("edges");
            List<String[]> out = new ArrayList<>();
            for (int i = 0; edges != null && i < edges.length(); i++) {
                JSONObject n = edges.getJSONObject(i).optJSONObject("node");
                JSONObject c = n == null ? null : n.optJSONObject("content");
                String poster = str(c, "posterUrl");
                if (c == null || poster.isEmpty()) continue;
                JSONArray bds = c.optJSONArray("backdrops");
                String bd = bds != null && bds.length() > 0 ? "https://images.justwatch.com" + bds.optJSONObject(0)
                        .optString("backdropUrl").replace("{profile}", "s1920").replace("{format}", "jpg") : null;
                JSONObject ext = c.optJSONObject("externalIds");
                out.add(new String[]{n.optString("id"), n.optString("objectType"), Suggestions.cleanTitle(str(c, "title")),
                        "https://images.justwatch.com" + poster.replace("{profile}", "s332").replace("{format}", "jpg"),
                        bd, ext == null ? "0" : str(ext, "tmdbId")});
            }
            if (!out.isEmpty()) ui.post(() -> {
                if (isDestroyed()) return;
                related = out;
                if (built) relatedRow(out);
            });
        } catch (Exception ignored) {
            // the page is complete without it
        }
    }

    void sections(JSONObject n, JSONObject c, boolean series, List<String[]> people) {
        if (isDestroyed()) return;
        built = true;
        if (related != null) relatedRow(related);
        if (!people.isEmpty()) castRow(people);
        information(n, c, series);
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
        Row row = new Row(u(40), u(12), u(4));
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
            caption.setMaxWidth(posterW);
            caption.setAlpha(0f);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(posterW, -2);
            cl.topMargin = u(8);
            tile.addView(caption, cl);
            // Focus lifts the poster by 40 px and shows its title beneath, as on tvOS.
            tile.setOnFocusChangeListener((v, has) -> {
                frame.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(has ? 85 : 230)
                        .setInterpolator(has ? FOCUS_IN : FOCUS_OUT).start();
                caption.animate().alpha(has ? 1f : 0f).translationY(has ? u(10) : 0).setDuration(has ? 85 : 230)
                        .setInterpolator(has ? FOCUS_IN : FOCUS_OUT).start();
            });
            tile.setOnClickListener(v -> openRelated(it));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (row.items.getChildCount() > 0) lp.leftMargin = u(20);
            row.items.addView(tile, lp);
        }
        s.addView(row, new LinearLayout.LayoutParams(-1, -2));
        sections.addView(s, 0);
        // The posters, one by one off the UI thread, each fading in as it's ready.
        side.execute(() -> {
            for (int i = 0; i < items.size() && !gone(); i++) {
                Bitmap bm = Suggestions.screenImage(this, items.get(i)[3], posterW);
                ImageView iv = posters.get(i);
                if (bm != null) ui.post(() -> {
                    if (isDestroyed()) return;
                    iv.setImageBitmap(bm);
                    iv.animate().alpha(1f).setDuration(240).setInterpolator(LINEAR).start();
                });
            }
        });
    }

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

    /** Cast & Crew: initials in a circle (no photos), the name, then the character or "Director". */
    void castRow(List<String[]> people) {
        LinearLayout s = section("Cast & Crew");
        Row row = new Row(u(40), u(8), u(4));
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
    final class Row extends HorizontalScrollView {
        final LinearLayout items;
        final int edge;
        ValueAnimator glide;

        Row(int edge, int top, int bottom) {
            super(DetailActivity.this);
            this.edge = edge;
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            // Room above and below for a focused tile's lift, inside the row's own clip.
            setPadding(0, top, 0, bottom);
            setClipToPadding(false);
            items = new LinearLayout(DetailActivity.this);
            items.setClipChildren(false);
            items.setClipToPadding(false);
            items.setPadding(edge, 0, edge, 0);
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

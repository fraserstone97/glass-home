package app.glasshome.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A title's page, opened from Search: the backdrop, title, year, length or seasons, age rating,
 * IMDb score, synopsis and cast, a Watch button for the best place to stream it, and How to
 * Watch: every UK service that streams it or sells or rents it, with the price. Installed apps
 * open straight to the title. Data from JustWatch, like the rest of tvOS.
 */
public class DetailActivity extends Activity {

    static final String QUERY = "query D($id: ID!, $c: Country!, $l: Language!) { node(id: $id) { ... on MovieOrShow {"
            + " objectType content(country: $c, language: $l) { title shortDescription originalReleaseYear runtime"
            + " ageCertification genres { shortName } backdrops { backdropUrl } credits { name role } scoring { imdbScore }"
            + " externalIds { tmdbId } }"
            + " offers(country: $c, platform: WEB) { monetizationType retailPrice(language: $l) standardWebURL"
            + " package { clearName technicalName } } } ... on Show { totalSeasonCount } } }";

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

    /** One service's offer for this title, merged across quality (SD/HD/4K). */
    static final class Offer {
        String name, tech, url, pkg;
        boolean subscription, free, ads;
        String rent, buy;
        int rank;
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    ImageView backdrop;
    LinearLayout column, actions, ways;
    TextView title, meta, synopsis, cast, waysLabel;
    String titleText;

    int u(float units) {
        return Ui.px(this, units);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        titleText = getIntent().getStringExtra("title");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        // Legibility: dark from the left and from the bottom, as on Apple TV product pages.
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2000000, 0xB3000000, 0x33000000, 0x00000000}));
        root.addView(left, new FrameLayout.LayoutParams(-1, -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x66000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, u(260), Gravity.BOTTOM));

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(u(48), u(56), u(48), u(36));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        title = text(34, "display", Color.WHITE);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(u(520));
        title.setText(titleText);
        column.addView(title);
        meta = text(12, "medium", 0xB3FFFFFF);
        column.addView(meta, margins(u(8)));
        synopsis = text(13, "regular", 0xE6FFFFFF);
        synopsis.setMaxWidth(u(470));
        synopsis.setMaxLines(4);
        synopsis.setEllipsize(TextUtils.TruncateAt.END);
        synopsis.setLineSpacing(0, 1.15f);
        column.addView(synopsis, margins(u(14)));
        cast = text(11, "regular", 0x99FFFFFF);
        cast.setMaxWidth(u(470));
        cast.setSingleLine(true);
        cast.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(cast, margins(u(10)));

        actions = new LinearLayout(this);
        actions.setClipChildren(false);
        column.addView(actions, margins(u(20)));

        column.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        waysLabel = text(12, "semibold", 0xB3FFFFFF);
        waysLabel.setText("How to Watch");
        waysLabel.setAlpha(0f);
        column.addView(waysLabel);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(u(4), u(10), u(48), u(10));
        ways = new LinearLayout(this);
        ways.setClipChildren(false);
        scroll.addView(ways);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.leftMargin = -u(4);
        column.addView(scroll, sl);

        setContentView(root);
        column.setAlpha(0f);
        column.setTranslationY(u(10));
        column.animate().alpha(1f).translationY(0).setDuration(480).setInterpolator(MainActivity.EASE).start();
        load(getIntent().getStringExtra("id"), getIntent().getStringExtra("backdrop"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
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

    void load(String id, String knownBackdrop) {
        // With a TMDB key the page waits for TMDB's full-size artwork instead of showing JustWatch's first.
        if (knownBackdrop != null && !Suggestions.hasToken(this)) loadBackdrop(knownBackdrop);
        fallbackBackdrop = knownBackdrop;
        if (id == null) return;
        io.execute(() -> {
            try {
                JSONObject vars = new JSONObject().put("id", id).put("c", "GB").put("l", "en");
                JSONObject res = Suggestions.post("https://apis.justwatch.com/graphql",
                        new JSONObject().put("query", QUERY).put("variables", vars).toString());
                JSONObject n = res.getJSONObject("data").getJSONObject("node");
                ui.post(() -> show(n));
            } catch (Exception e) {
                ui.post(() -> {
                    meta.setText("Details aren’t available right now.");
                    fadeIn(meta, 0);
                });
            }
        });
    }

    /** The picture Search passed in, for when TMDB has nothing better. */
    String fallbackBackdrop;

    void loadBackdrop(String url) {
        io.execute(() -> {
            Bitmap bm = Suggestions.image(this, url, "", Math.min(1920, Ui.screen(this).x));
            if (bm != null) ui.post(() -> {
                backdrop.setImageBitmap(bm);
                backdrop.setScaleX(1.04f);
                backdrop.setScaleY(1.04f);
                backdrop.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(MainActivity.HERO_EASE).start();
            });
        });
    }

    void show(JSONObject n) {
        JSONObject c = n.optJSONObject("content");
        if (c == null) return;
        boolean series = "SHOW".equals(n.optString("objectType"));
        String t = Suggestions.cleanTitle(c.optString("title", titleText == null ? "" : titleText));
        title.setText(t);
        if (backdrop.getDrawable() == null) {
            JSONArray bds = c.optJSONArray("backdrops");
            String jw = bds != null && bds.length() > 0 ? "https://images.justwatch.com" + bds.optJSONObject(0)
                    .optString("backdropUrl").replace("{profile}", "s1920").replace("{format}", "jpg") : fallbackBackdrop;
            JSONObject ext = c.optJSONObject("externalIds");
            int tmdb = ext == null ? 0 : Suggestions.parseInt(ext.optString("tmdbId"));
            String type = series ? "tv" : "movie";
            io.execute(() -> {
                String art = Suggestions.tmdbBackdrop(this, type, tmdb);
                String url = art != null ? art : jw;
                if (url != null) ui.post(() -> loadBackdrop(url));
            });
        }

        // Year · Series / Film · seasons or running time · age rating · genres · IMDb
        List<String> m = new ArrayList<>();
        int y = c.optInt("originalReleaseYear", 0);
        if (y > 0) m.add(String.valueOf(y));
        int seasons = n.optInt("totalSeasonCount", 0);
        if (series) m.add(seasons > 1 ? seasons + " Seasons" : seasons == 1 ? "1 Season" : "Series");
        else {
            int mins = c.optInt("runtime", 0);
            m.add(mins >= 60 ? mins / 60 + " hr " + (mins % 60 > 0 ? mins % 60 + " min" : "").trim() : mins > 0 ? mins + " min" : "Film");
        }
        String age = c.optString("ageCertification", "");
        if (!age.isEmpty() && !"null".equals(age)) m.add(age);
        String genres = Suggestions.jwGenres(c.optJSONArray("genres"));
        if (!genres.isEmpty()) m.add(genres);
        JSONObject sc = c.optJSONObject("scoring");
        double imdb = sc == null ? 0 : sc.optDouble("imdbScore", 0);
        if (imdb > 0) m.add(String.format(Locale.UK, "IMDb %.1f", imdb));
        meta.setText(TextUtils.join("  ·  ", m));
        synopsis.setText(c.optString("shortDescription", ""));
        JSONArray credits = c.optJSONArray("credits");
        List<String> actors = new ArrayList<>();
        for (int i = 0; credits != null && i < credits.length() && actors.size() < 4; i++) {
            JSONObject cr = credits.optJSONObject(i);
            if ("ACTOR".equals(cr.optString("role"))) actors.add(cr.optString("name"));
        }
        cast.setText(actors.isEmpty() ? "" : "Starring " + TextUtils.join(", ", actors));
        fadeIn(meta, 0);
        fadeIn(synopsis, 60);
        fadeIn(cast, 120);

        List<Offer> offers = offers(n.optJSONArray("offers"), t);
        Offer best = null;
        for (Offer o : offers) {
            if ((o.subscription || o.free || o.ads) && o.pkg != null && Suggestions.installed(this, o.pkg)) {
                best = o;
                break;
            }
        }
        actions.removeAllViews();
        if (best != null) {
            Offer go = best;
            View watch = pill(actions, "▶  Watch on " + go.name, true, v -> open(go));
            watch.requestFocus();
        }
        ways.removeAllViews();
        for (Offer o : offers) {
            View w = way(o);
            if (best == null && ways.getChildCount() == 1) w.requestFocus();
        }
        if (offers.isEmpty()) {
            TextView none = text(12, "regular", 0x99FFFFFF);
            none.setText("Not available to stream, rent or buy in the UK right now.");
            ways.addView(none);
        }
        fadeIn(waysLabel, 160);
        fadeIn(ways, 200);
        fadeIn(actions, 120);
    }

    /** Offers merged per service: streaming first (subscription, free), then rent and buy, cheapest first. */
    List<Offer> offers(JSONArray a, String t) {
        Map<String, Offer> by = new LinkedHashMap<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            JSONObject pk = o.optJSONObject("package");
            if (pk == null) continue;
            String tech = pk.optString("technicalName");
            String clear = pk.optString("clearName");
            // Add-on channels sold through another store repeat the service itself; leave them out.
            if (clear.matches("(?i).*(amazon|apple tv|roku premium) channels?$")) continue;
            String name = clear.replace(" Store", "").replace("Amazon Video", "Prime Video")
                    .replace("Netflix Standard with Ads", "Netflix").replace("ITVX Premium", "ITVX")
                    .replace("Channel 4 Plus", "Channel 4").replace("Now TV", "NOW")
                    .replace("Disney Plus", "Disney+").replace("Paramount Plus", "Paramount+")
                    .replace("Apple TV Plus", "Apple TV").replace("Amazon Prime Video", "Prime Video")
                    .replaceAll("( (Premium|Basic|Standard|Essential|with Ads))+$", "");
            // Tiers of one service (with ads, premium) become one row.
            String key = name.toLowerCase(java.util.Locale.ROOT);
            String app = APPS.get(tech.equals("netflixbasicwithads") ? "netflix" : tech.equals("itvpremium") ? "itv"
                    : tech.equals("all4plus") ? "all4" : tech);
            Offer of = by.get(key);
            if (of == null) {
                of = new Offer();
                of.tech = tech;
                of.name = name;
                of.url = o.optString("standardWebURL");
                by.put(key, of);
            }
            if (of.pkg == null) of.pkg = app;
            String mt = o.optString("monetizationType");
            String price = o.optString("retailPrice", "");
            if ("null".equals(price)) price = "";
            switch (mt) {
                case "FLATRATE": of.subscription = true; break;
                case "FREE": of.free = true; break;
                case "ADS": of.ads = true; break;
                case "RENT": if (!price.isEmpty() && (of.rent == null || cheaper(price, of.rent))) of.rent = price; break;
                case "BUY": if (!price.isEmpty() && (of.buy == null || cheaper(price, of.buy))) of.buy = price; break;
                default: break;
            }
        }
        List<Offer> out = new ArrayList<>(by.values());
        for (Offer o : out) {
            boolean installed = o.pkg != null && Suggestions.installed(this, o.pkg);
            o.rank = (o.free || o.ads ? 0 : o.subscription ? 1 : 3) + (installed ? 0 : 1);
        }
        out.sort((x, y) -> Integer.compare(x.rank, y.rank));
        return out;
    }

    static boolean cheaper(String a, String b) {
        try {
            return Double.parseDouble(a.replaceAll("[^0-9.]", "")) < Double.parseDouble(b.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** "Free", "Free with ads", "Subscription", "Rent £3.49 · Buy £7.99" */
    static String terms(Offer o) {
        if (o.free) return "Free";
        if (o.ads) return "Free with ads";
        if (o.subscription) return "Subscription";
        List<String> p = new ArrayList<>();
        if (o.rent != null) p.add("Rent " + o.rent);
        if (o.buy != null) p.add("Buy " + o.buy);
        return p.isEmpty() ? "Available" : TextUtils.join("  ·  ", p);
    }

    void open(Offer o) {
        if (o.pkg == null) return;
        if (!Suggestions.installed(this, o.pkg)) {
            Suggestions.start(this, new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + o.pkg)));
            return;
        }
        PackageManager pm = getPackageManager();
        if (o.url != null && !o.url.isEmpty()) {
            Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse(o.url)).setPackage(o.pkg);
            if (v.resolveActivity(pm) != null && Suggestions.start(this, v)) return;
        }
        Intent launch = pm.getLeanbackLaunchIntentForPackage(o.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(o.pkg);
        if (launch != null) Suggestions.start(this, launch);
    }

    void fadeIn(View v, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(u(6));
        v.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(420).setInterpolator(MainActivity.EASE).start();
    }

    // ---------------------------------------------------------------- controls

    /** The Watch button: a white pill when focused, glass otherwise, like tvOS. */
    View pill(LinearLayout parent, String label, boolean primary, View.OnClickListener click) {
        Glass white = glass(u(20));
        TextView t = text(13, "semibold", Color.WHITE);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(u(22), 0, u(22), 0);
        t.setBackground(white);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((v, has) -> {
            Glass.focus(white, has);
            t.setTextColor(has ? 0xFF111114 : Color.WHITE);
            lift(v, has, 1.06f);
        });
        t.setOnClickListener(click);
        parent.addView(t, new LinearLayout.LayoutParams(-2, u(40)));
        return t;
    }

    /** One way to watch: the app's icon (if installed), its name and its terms. */
    View way(Offer o) {
        Glass white = glass(u(16));
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(u(10), 0, u(16), 0);
        t.setBackground(white);
        t.setFocusable(true);
        t.setClickable(o.pkg != null);
        boolean installed = o.pkg != null && Suggestions.installed(this, o.pkg);
        if (installed) {
            ImageView icon = new ImageView(this);
            try {
                Drawable d = getPackageManager().getApplicationBanner(o.pkg);
                icon.setImageDrawable(d != null ? d : getPackageManager().getApplicationIcon(o.pkg));
            } catch (PackageManager.NameNotFoundException ignored) {
                // no icon
            }
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setClipToOutline(true);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(u(6));
            icon.setBackground(shape);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(u(48), u(28));
            il.rightMargin = u(10);
            t.addView(icon, il);
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(12, "semibold", Color.WHITE);
        name.setText(o.name);
        name.setSingleLine(true);
        TextView sub = text(10.5f, "regular", 0xA6FFFFFF);
        sub.setText(terms(o) + (installed || o.pkg == null ? "" : "  ·  Get the app"));
        sub.setSingleLine(true);
        texts.addView(name);
        texts.addView(sub);
        t.addView(texts);
        t.setOnFocusChangeListener((v, has) -> {
            Glass.focus(white, has);
            name.setTextColor(has ? 0xFF111114 : Color.WHITE);
            sub.setTextColor(has ? 0x99111114 : 0xA6FFFFFF);
            lift(v, has, 1.05f);
        });
        t.setOnClickListener(v -> open(o));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, u(48));
        if (ways.getChildCount() > 0) lp.leftMargin = u(10);
        ways.addView(t, lp);
        return t;
    }

    Glass glass(float radius) {
        return new Glass(radius, u(0.6f), Glass.REGULAR);
    }

    void lift(View v, boolean has, float scale) {
        v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).translationZ(has ? u(8) : 0)
                .setDuration(has ? 380 : 260).setInterpolator(has ? MainActivity.LIFT : MainActivity.EASE).start();
    }
}

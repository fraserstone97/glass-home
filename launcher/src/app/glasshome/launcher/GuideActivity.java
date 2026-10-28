package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Guide: what's on, as Freely's TV guide shows it (channels down the side, time across, a
 * week back and a week ahead, catch-up marked), in tvOS's look: the focused programme large at the
 * top over its picture, glass cells, a white plate for focus. OK on something on now opens the
 * channel live in its broadcaster's app; on something earlier, that programme in the app's
 * catch-up. Back returns to now, then to Home.
 */
public class GuideActivity extends Activity {

    /** The hours across the screen, as Freely's guide shows. */
    static final long SPAN = 2 * GuideData.HOUR, HALF_HOUR = 30 * GuideData.MIN;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService work = Executors.newSingleThreadExecutor();
    final ExecutorService images = Executors.newFixedThreadPool(2);

    float unit;
    int screenW, screenH;
    Grid grid;
    TextView channelLine, title, meta, subtitle, action, liveBadge;
    ImageView artFront, artBack;
    View actionPill;
    final List<GuideData.Channel> channels = new ArrayList<>();
    final Set<Long> loaded = new HashSet<>(), loading = new HashSet<>();
    final Map<String, Bitmap> logos = new HashMap<>();
    final Set<String> logosAsked = new HashSet<>();
    String artUrl;
    boolean failed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ClipAudit.watch(this);  // a development check, off unless asked for over adb
        unit = Ui.unit(this);
        android.graphics.Point p = Ui.screen(this);
        screenW = p.x;
        screenH = p.y;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        buildHeader(root);
        grid = new Grid(this);
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        grid.setFocusable(true);
        grid.requestFocus();
        showLoading();
        work.execute(this::start);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }

    /** Every half minute: the now line moves, and what's live changes. */
    final Runnable tick = new Runnable() {
        @Override public void run() {
            grid.invalidate();
            if (grid.focus != null) describe(grid.focus, grid.focusChannel());
            ui.postDelayed(this, 30_000);
        }
    };

    int u(float units) {
        return Math.round(units * unit);
    }

    // ---------------------------------------------------------------- header

    void buildHeader(FrameLayout root) {
        // The focused programme's picture, top right, fading into the black around it (as tvOS's
        // title pages): two layers for a crossfade.
        int artW = Math.round(screenW * 0.64f), artH = u(204);
        FrameLayout art = new FrameLayout(this);
        artBack = new ImageView(this);
        artFront = new ImageView(this);
        for (ImageView v : new ImageView[]{artBack, artFront}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            art.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF000000, 0xB3000000, 0x33000000, 0x00000000}));
        art.addView(left, new FrameLayout.LayoutParams(Math.round(artW * 0.55f), -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF000000, 0xCC000000, 0x4D000000, 0x00000000}));
        art.addView(bottom, new FrameLayout.LayoutParams(-1, Math.round(artH * 0.6f), Gravity.BOTTOM));
        root.addView(art, new FrameLayout.LayoutParams(artW, artH, Gravity.END | Gravity.TOP));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        channelLine = label(10, "semibold", 0x99FFFFFF, 1);
        channelLine.setLetterSpacing(0.06f);
        text.addView(channelLine);
        title = label(27, "display", Color.WHITE, 1);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = u(4);
        text.addView(title, tl);
        // The times, after a red LIVE badge when it's on now (as the Apple TV app marks live).
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        liveBadge = label(8.5f, "semibold", Color.WHITE, 1);
        liveBadge.setText("LIVE");
        liveBadge.setLetterSpacing(0.08f);
        liveBadge.setPadding(u(5), u(2), u(5), u(2));
        GradientDrawable red = new GradientDrawable();
        red.setColor(0xFFFF453A);
        red.setCornerRadius(u(4));
        liveBadge.setBackground(red);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.rightMargin = u(7);
        metaRow.addView(liveBadge, bl);
        meta = label(11, "medium", 0xCCFFFFFF, 1);
        metaRow.addView(meta);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = u(7);
        text.addView(metaRow, ml);
        subtitle = label(11, "regular", 0x99FFFFFF, 2);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = u(4);
        text.addView(subtitle, sl);
        // The words stay 440 wide; the box reaches 6 further, so a long title leaning at the wall
        // (a programme not on yet, Glass.bump) isn't cut along its right edge.
        text.setPadding(0, 0, u(6), 0);
        text.setClipToPadding(false);
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(u(446), -2, Gravity.TOP | Gravity.START);
        textLp.leftMargin = u(40);
        textLp.topMargin = u(34);
        root.addView(text, textLp);

        // What OK does, in a glass capsule under the words (it isn't a button: OK is pressed on the grid).
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(u(10), 0, u(12), 0);
        pill.setBackground(new Glass(u(12), Math.max(1f, unit * 0.6f), Glass.REGULAR));
        ImageView play = new ImageView(this);
        play.setImageBitmap(Glyphs.get("play", u(10), Color.WHITE));
        pill.addView(play, new LinearLayout.LayoutParams(u(10), u(10)));
        action = label(10.5f, "medium", Color.WHITE, 1);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.leftMargin = u(6);
        pill.addView(action, al);
        actionPill = pill;
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, u(24), Gravity.TOP | Gravity.START);
        pl.leftMargin = u(40);
        pl.topMargin = u(140);
        root.addView(pill, pl);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTimeZone(GuideData.LONDON.getID());
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Ui.font(this, "semibold"));
        Ui.textSize(clock, 13);
        clock.setShadowLayer(u(6), 0, 0, 0x80000000);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cl.rightMargin = u(40);
        cl.topMargin = u(26);
        root.addView(clock, cl);
    }

    TextView label(float size, String weight, int color, int lines) {
        TextView t = new TextView(this);
        t.setTypeface(Ui.font(this, weight));
        Ui.textSize(t, size);
        t.setTextColor(color);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    void showLoading() {
        channelLine.setText("TV GUIDE");
        title.setText("What's On");
        meta.setText("Loading the guide\u2026");
        liveBadge.setVisibility(View.GONE);
        subtitle.setText("");
        actionPill.setVisibility(View.INVISIBLE);
    }

    /** The header for the programme (or, with nothing listed, the channel) in focus. */
    void describe(GuideData.Show s, GuideData.Channel ch) {
        if (ch == null) return;
        long now = System.currentTimeMillis();
        channelLine.setText((ch.number + "  " + ch.name).toUpperCase(Locale.UK));
        if (s == null) {
            title.setText(ch.name);
            meta.setText("No listings");
            liveBadge.setVisibility(View.GONE);
            subtitle.setText("");
            setAction(installed(ch) ? "Watch on " + ch.app : "Get " + ch.app);
            showArt(null);
            return;
        }
        title.setText(s.title);
        StringBuilder m = new StringBuilder();
        m.append(dayWord(s.start, now)).append("  ").append(time(s.start)).append(" \u2013 ").append(time(s.end));
        liveBadge.setVisibility(s.onNow(now) ? View.VISIBLE : View.GONE);
        if (s.onNow(now)) {
            long left = (s.end - now + 59_999) / 60_000;
            m.append("  \u00b7  ").append(minutes(left)).append(" left");
        } else {
            m.append("  \u00b7  ").append(minutes((s.end - s.start) / 60_000));
        }
        if (s.end <= now && s.watchable(now)) m.append("  \u00b7  Catch Up");
        meta.setText(m);
        subtitle.setText(s.subtitle == null ? "" : s.subtitle);
        // What OK will really do: My5 can't be sent to a channel or a programme, only opened or searched.
        boolean my5 = "com.channel5.my5".equals(ch.pkg);
        if (!installed(ch)) setAction("Get " + ch.app);
        else if (s.onNow(now)) setAction(my5 ? "Open My5 for Live TV" : "Watch Live on " + ch.app);
        else if (s.end <= now) setAction(s.watchable(now) ? (my5 ? "Find on My5" : "Watch on " + ch.app) : "Find on " + ch.app);
        else setAction(null);
        showArt(s.image);
    }

    void setAction(String text) {
        actionPill.setVisibility(text == null ? View.INVISIBLE : View.VISIBLE);
        if (text != null) action.setText(text);
    }

    static String minutes(long min) {
        if (min < 60) return min + " min";
        long h = min / 60, m = min % 60;
        return m == 0 ? h + (h == 1 ? " hr" : " hrs") : h + " hr " + m + " min";
    }

    static String time(long t) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.UK);
        f.setTimeZone(GuideData.LONDON);
        return f.format(new Date(t));
    }

    /** Today, Yesterday, Tomorrow, or the day's name and date. */
    static String dayWord(long t, long now) {
        long day = GuideData.dayStart(t), today = GuideData.dayStart(now);
        if (day == today) return "Today";
        if (day == GuideData.addDays(today, -1)) return "Yesterday";
        if (day == GuideData.addDays(today, 1)) return "Tomorrow";
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", Locale.UK);
        f.setTimeZone(GuideData.LONDON);
        return f.format(new Date(t));
    }

    /** The picture behind the header, a moment after focus settles (not for every programme passed). */
    void showArt(String url) {
        if (url == null ? artUrl == null : url.equals(artUrl)) return;
        artUrl = url;
        ui.removeCallbacks(artLoad);
        if (url == null) {
            artFront.animate().alpha(0f).setDuration(300).start();
            return;
        }
        ui.postDelayed(artLoad, 220);
    }

    final Runnable artLoad = () -> {
        String url = artUrl;
        if (url == null) return;
        images.execute(() -> {
            Bitmap b = Suggestions.screenImage(this, GuideData.sized(url, 800), Math.round(screenW * 0.64f));
            ui.post(() -> {
                if (b == null || !url.equals(artUrl) || isFinishing()) return;
                // The new picture fades in over the one shown, which then becomes the back layer.
                ImageView old = artFront;
                artFront = artBack;
                artBack = old;
                artFront.bringToFront();
                ((View) artFront.getParent()).invalidate();
                artFront.setImageBitmap(b);
                artFront.setAlpha(0f);
                artFront.animate().alpha(1f).setDuration(350).setInterpolator(MainActivity.EASE)
                        .withEndAction(() -> artBack.setAlpha(0f)).start();
                // The shades stay on top of both pictures.
                FrameLayout box = (FrameLayout) artFront.getParent();
                for (int i = 0; i < box.getChildCount(); i++) {
                    View v = box.getChildAt(i);
                    if (v != artFront && v != artBack) v.bringToFront();
                }
            });
        });
    };

    // ---------------------------------------------------------------- data

    void start() {
        try {
            List<GuideData.Channel> list = GuideData.channels(this);
            long today = GuideData.dayStart(System.currentTimeMillis());
            java.util.Map<String, List<GuideData.Show>> day = GuideData.day(this, today);
            GuideData.merge(list, day);
            ui.post(() -> {
                channels.clear();
                channels.addAll(list);
                loaded.add(today);
                grid.home(false);
                // The days either side, for scrolling there without a wait.
                need(GuideData.addDays(today, -1));
                need(GuideData.addDays(today, 1));
            });
            GuideData.prune(this);
        } catch (Exception e) {
            ui.post(() -> {
                failed = true;
                channelLine.setText("TV GUIDE");
                title.setText("What's On");
                meta.setText("Can\u2019t load the guide. Check your internet connection.");
                liveBadge.setVisibility(View.GONE);
            });
        }
    }

    /** The days the window shows (and a little either side) are fetched, if they aren't yet. */
    void needAround(long from, long to) {
        for (long d = GuideData.dayStart(from - 3 * GuideData.HOUR); d <= to + 3 * GuideData.HOUR; d = GuideData.addDays(d, 1)) need(d);
    }

    void need(long midnight) {
        long today = GuideData.dayStart(System.currentTimeMillis());
        if (midnight < GuideData.addDays(today, -GuideData.DAYS_BACK) || midnight > GuideData.addDays(today, GuideData.DAYS_AHEAD)) return;
        if (loaded.contains(midnight) || loading.contains(midnight) || channels.isEmpty()) return;
        loading.add(midnight);
        List<GuideData.Channel> list = new ArrayList<>(channels);
        work.execute(() -> {
            java.util.Map<String, List<GuideData.Show>> day;
            try {
                day = GuideData.day(this, midnight);
            } catch (Exception e) {
                ui.post(() -> loading.remove(midnight));  // asked for again when next needed
                return;
            }
            ui.post(() -> {
                // Merged here, on the screen's thread, so a frame never draws a list mid-change.
                GuideData.merge(list, day);
                loading.remove(midnight);
                loaded.add(midnight);
                grid.landed();
            });
        });
    }

    Bitmap logo(GuideData.Channel ch) {
        Bitmap b = logos.get(ch.id);
        if (b == null && ch.logo != null && logosAsked.add(ch.id)) {
            images.execute(() -> {
                Bitmap got = Suggestions.image(this, GuideData.sized(ch.logo, 400), "", 400);
                if (got == null) return;
                ui.post(() -> {
                    logos.put(ch.id, got);
                    grid.invalidate();
                });
            });
        }
        return b;
    }

    // ---------------------------------------------------------------- opening

    boolean installed(GuideData.Channel ch) {
        return Suggestions.installed(this, ch.pkg);
    }

    /**
     * OK: on now, the channel live in its app; earlier, the programme in the app's catch-up; later,
     * nothing to open yet. The links are the ones each app answers on this TV: Freely's own TV-app
     * links where an app takes them (iPlayer, ITVX's catch-up), ITVX's channel links, Channel 4's web pages, and My5 (whose links go
     * through its server, and show an error) opened at its start, or searched, instead.
     */
    void open(GuideData.Show s, GuideData.Channel ch) {
        if (ch == null) return;
        if (!installed(ch)) {
            Suggestions.start(this, new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ch.pkg)));
            return;
        }
        long now = System.currentTimeMillis();
        if (s != null && s.start > now) {
            Glass.bump(actionPill.getVisibility() == View.VISIBLE ? actionPill : title, 1, 0);
            return;
        }
        boolean my5 = "com.channel5.my5".equals(ch.pkg);
        List<Intent> tries = new ArrayList<>();
        if (s == null || s.onNow(now)) {
            String live = liveLink(ch);
            if (live != null && !my5) tries.add(Suggestions.view(live, ch.pkg));
        } else {
            String link = catchUpLink(s);
            if (link != null && !my5) tries.add(Suggestions.view(link, ch.pkg));
            tries.add(new Intent(Intent.ACTION_SEARCH).setPackage(ch.pkg).putExtra(SearchManager.QUERY, s.title));
        }
        android.content.pm.PackageManager pm = getPackageManager();
        for (Intent i : tries) {
            // Started afresh: an app already open (ITVX) only comes to the front with what it last
            // showed, and drops the link.
            if (Intent.ACTION_VIEW.equals(i.getAction())) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (i.resolveActivity(pm) != null && Suggestions.start(this, i)) return;
        }
        Intent launch = Suggestions.launchIntent(this, ch.pkg);
        if (launch != null) Suggestions.start(this, launch);
    }

    /** iPlayer's channel ids, by the channel's page on bbc.co.uk (what Freely lists). */
    static final Map<String, String> IPLAYER = new HashMap<>();

    static {
        String[][] ids = {{"bbcone", "bbc_one_london"}, {"bbctwo", "bbc_two_england"}, {"bbcthree", "bbc_three"},
                {"bbcfour", "bbc_four"}, {"bbcnews", "bbc_news24"}, {"bbcparliament", "bbc_parliament"},
                {"cbbc", "cbbc"}, {"cbeebies", "cbeebies"}, {"bbcscotland", "bbc_scotland"}, {"bbcalba", "bbc_alba"}};
        for (String[] i : ids) IPLAYER.put(i[0], i[1]);
    }

    static final String IPLAYER_TV = "https://www.live.bbctvapps.co.uk/tap/iplayer/ait/launch/iplayer.aitx?deeplink=tv/playback/";

    /** The channel live: iPlayer's TV link (its web page doesn't open the app), else the channel's page. */
    static String liveLink(GuideData.Channel ch) {
        if (ch.live == null) return null;
        if ("bbc.iplayer.android".equals(ch.pkg)) {
            String slug = Uri.parse(ch.live).getLastPathSegment();
            String id = slug == null ? null : IPLAYER.get(slug);
            return id == null ? null : IPLAYER_TV + id;
        }
        if ("air.ITVMobilePlayer".equals(ch.pkg)) {
            // ITVX opens a channel live for any itv.com link with ?channel= (its name, as Freely's
            // hub/itv, hub/itv2 ... end); the hub link itself only opens the app.
            String name = Uri.parse(ch.live).getLastPathSegment();
            return name == null ? null : "https://www.itv.com/watch?channel=" + name;
        }
        return ch.live;
    }

    /** The programme's catch-up: Freely's link, or for Channel 4 (whose app takes only its web pages) the page. */
    static String catchUpLink(GuideData.Show s) {
        if (s.catchUp == null) return null;
        Uri u = Uri.parse(s.catchUp);
        if ("ait.channel4.com".equals(u.getHost())) {
            String brand = u.getQueryParameter("brand"), programme = u.getQueryParameter("programme");
            return brand == null || programme == null ? null
                    : "https://www.channel4.com/programmes/" + brand + "/on-demand/" + programme;
        }
        return s.catchUp;
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        MainActivity.navSound(this, ev, false);
        return super.dispatchKeyEvent(ev);
    }

    @Override
    public void onBackPressed() {
        // Away from now: back to it first, as Freely's guide does; at now, back to Home.
        if (!channels.isEmpty() && (grid.onDay || !grid.atHome())) {
            grid.dayFocus(false);
            grid.home(true);
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- the grid

    /**
     * Channels down, time across, drawn in one view: a TV draws the whole guide in a pass, where
     * a view for every programme (hundreds) made each scroll a re-layout.
     */
    final class Grid extends View {
        // Layout, in design units (960 x 540).
        final float rulerY = 180, rowsY = 200, rowH = 38, pitch = 43, chanX = 40, chanW = 100, gridX = 148, right = 920;
        final int visibleRows = 7;

        /** The window: its first moment (left edge) and first row, where they're heading, and now. */
        long start, startTarget;
        float top, topTarget, startF;
        int row;
        GuideData.Show focus, lastFocus;
        /** The time up and down keep to (Freely's: the programme you moved to across). */
        long anchor;
        float focusIn = 1f, focusOut;
        /** Focus on the day, over the channels (up from the first one), as Freely's day strip. */
        boolean onDay;
        float dayFocus;
        ValueAnimator dayFade;
        ValueAnimator scroll, fade;
        long lastKey;

        android.graphics.Shader bottomFade, backing;
        float backingTop;
        int fadeH;
        final Paint shade = new Paint(), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
                small = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
                bitmaps = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final RectF r = new RectF();
        final Path tri = new Path();
        final Typeface medium, semibold;

        Grid(Context c) {
            super(c);
            medium = Ui.font(c, "medium");
            semibold = Ui.font(c, "semibold");
            text.setTypeface(medium);
            text.setTextSize(11 * unit);
            small.setTypeface(medium);
            small.setTextSize(9 * unit);
            fill.setStyle(Paint.Style.FILL);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, unit * 0.5f));
        }

        float px(float units) {
            return units * unit;
        }

        float pxPerMs() {
            return (right - gridX) * unit / SPAN;
        }

        float x(long t) {
            return px(gridX) + (t - startF) * pxPerMs();
        }

        GuideData.Channel focusChannel() {
            return row >= 0 && row < channels.size() ? channels.get(row) : null;
        }

        long earliest() {
            return GuideData.addDays(GuideData.dayStart(System.currentTimeMillis()), -GuideData.DAYS_BACK);
        }

        long latest() {
            return GuideData.addDays(GuideData.dayStart(System.currentTimeMillis()), GuideData.DAYS_AHEAD + 1) - SPAN;
        }

        long floor30(long t) {
            return t - Math.floorMod(t, HALF_HOUR);
        }

        /** The window at now: the half hour now is in at the left, focus on what's on. */
        void home(boolean animate) {
            long now = System.currentTimeMillis();
            long s = floor30(now);
            if (!animate) {
                start = startTarget = s;
                startF = s;
            }
            anchor = now;
            if (row >= channels.size()) row = 0;
            GuideData.Show at = showAt(row, now);
            go(row, at, s, animate);
        }

        boolean atHome() {
            long now = System.currentTimeMillis();
            GuideData.Show at = showAt(row, now);
            return startTarget == floor30(now) && (focus == at);
        }

        /** A day has arrived: what was empty may now have programmes, focus among them. */
        void landed() {
            if (focus == null) {
                GuideData.Show at = showAt(row, anchor);
                if (at != null) setFocus(at);
            }
            invalidate();
        }

        /** The programme on the row at time t, or the nearest one to it. */
        GuideData.Show showAt(int rowIndex, long t) {
            if (rowIndex < 0 || rowIndex >= channels.size()) return null;
            GuideData.Show best = null;
            long bestD = Long.MAX_VALUE;
            for (GuideData.Show s : channels.get(rowIndex).shows) {
                if (s.start <= t && t < s.end) return s;
                long d = Math.min(Math.abs(s.start - t), Math.abs(s.end - t));
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
            // Only a near one: far away (a day not fetched yet) is no answer.
            return bestD <= 3 * GuideData.HOUR ? best : null;
        }

        void setFocus(GuideData.Show s) {
            if (s == focus) return;
            lastFocus = focus;
            focus = s;
            if (fade != null) fade.cancel();
            focusOut = lastFocus == null ? 0f : 1f;
            focusIn = 0f;
            fade = ValueAnimator.ofFloat(0f, 1f);
            fade.setDuration(170);
            fade.setInterpolator(MainActivity.EASE);
            fade.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                focusIn = f;
                focusOut = 1f - Math.min(1f, f * 170f / 180f);
                invalidate();
            });
            fade.start();
            describe(s, focusChannel());
        }

        /** Focus to show s on row r, the window moved to startAt (and the row brought into view). */
        void go(int r, GuideData.Show s, long startAt, boolean animate) {
            row = r;
            startAt = Math.max(earliest(), Math.min(latest(), startAt));
            float topAt = topTarget;
            if (r < topAt) topAt = r;
            if (r > topAt + visibleRows - 1) topAt = r - visibleRows + 1;
            topAt = Math.max(0, Math.min(Math.max(0, channels.size() - visibleRows), topAt));
            if (focus == s && s != null) describe(s, focusChannel());
            if (s == null) {
                lastFocus = focus;
                focus = null;
                describe(null, focusChannel());
            } else {
                setFocus(s);
            }
            scrollTo(startAt, topAt, animate);
            needAround(startAt, startAt + SPAN);
        }

        void scrollTo(long s, float t, boolean animate) {
            startTarget = s;
            topTarget = t;
            if (scroll != null) scroll.cancel();
            if (!animate) {
                startF = s;
                start = s;
                top = t;
                invalidate();
                return;
            }
            float fromS = startF, fromT = top;
            scroll = ValueAnimator.ofFloat(0f, 1f);
            scroll.setDuration(320);
            scroll.setInterpolator(MainActivity.EASE);
            scroll.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                startF = fromS + (s - fromS) * f;
                start = (long) startF;
                top = fromT + (t - fromT) * f;
                invalidate();
            });
            scroll.start();
        }

        // ------------------------------------------------------------ moving

        @Override
        public boolean onKeyDown(int k, KeyEvent ev) {
            if (channels.isEmpty()) return super.onKeyDown(k, ev);
            // Held: one move each 90 ms, after tvOS's 0.3 s wait.
            if (ev.getRepeatCount() > 0) {
                long t = ev.getEventTime();
                if (t - ev.getDownTime() < 300 || t - lastKey < 90) return true;
            }
            lastKey = ev.getEventTime();
            boolean fast = ev.getRepeatCount() > 0;
            if (onDay) {
                switch (k) {
                    case KeyEvent.KEYCODE_DPAD_LEFT: return days(-1);
                    case KeyEvent.KEYCODE_DPAD_RIGHT: return days(1);
                    case KeyEvent.KEYCODE_DPAD_UP: return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        dayFocus(false);
                        Clicks.move(getContext(), 0.5f, false);
                        return true;
                    default: break;
                }
            }
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP: return rows(-1, fast);
                case KeyEvent.KEYCODE_DPAD_DOWN: return rows(1, fast);
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_UP: return rows(-visibleRows, fast);
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN: return rows(visibleRows, fast);
                case KeyEvent.KEYCODE_DPAD_LEFT: return across(-1, fast);
                case KeyEvent.KEYCODE_DPAD_RIGHT: return across(1, fast);
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_NEXT: return days(1);
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return days(-1);
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (ev.getRepeatCount() == 0) open(focus, focusChannel());
                    return true;
                default: return super.onKeyDown(k, ev);
            }
        }

        boolean rows(int d, boolean fast) {
            int to = Math.max(0, Math.min(channels.size() - 1, row + d));
            if (to == row && d < 0 && !fast) {
                dayFocus(true);
                Clicks.move(getContext(), 0.5f, false);
                return true;
            }
            if (to == row) {
                Glass.dirY = d < 0 ? -1 : 1;
                return true;
            }
            go(to, showAt(to, anchor), startTarget, true);
            Clicks.move(getContext(), 0.5f, fast);
            return true;
        }

        boolean across(int d, boolean fast) {
            List<GuideData.Show> shows = focusChannel() == null ? null : focusChannel().shows;
            if (shows == null || shows.isEmpty()) return true;
            GuideData.Show next;
            if (focus == null) {
                next = showAt(row, anchor);
            } else {
                int i = shows.indexOf(focus) + d;
                next = i >= 0 && i < shows.size() ? shows.get(i) : null;
            }
            // The end of what's fetched: the window moves on and the next day comes in.
            if (next == null || next.start < earliest() || next.start >= latest() + SPAN) {
                long s = Math.max(earliest(), Math.min(latest(), startTarget + d * HALF_HOUR));
                if (s != startTarget) scrollTo(s, topTarget, true);
                needAround(s, s + SPAN);
                return true;
            }
            long s = startTarget;
            if (d < 0 && next.start < s && next.end - s < 45 * GuideData.MIN) s = floor30(next.start);
            else if (d < 0 && next.end <= s) s = floor30(next.start);
            if (d > 0 && next.start >= s + SPAN - 40 * GuideData.MIN) s = floor30(next.start) - HALF_HOUR;
            s = Math.max(earliest(), Math.min(latest(), s));
            anchor = Math.max(next.start, s) + GuideData.MIN;
            go(row, next, s, true);
            Clicks.move(getContext(), 0.5f, fast);
            return true;
        }

        void dayFocus(boolean on) {
            if (onDay == on) return;
            onDay = on;
            if (dayFade != null) dayFade.cancel();
            dayFade = ValueAnimator.ofFloat(dayFocus, on ? 1f : 0f);
            dayFade.setDuration(on ? 170 : 180);
            dayFade.setInterpolator(MainActivity.EASE);
            dayFade.addUpdateListener(a -> {
                dayFocus = (float) a.getAnimatedValue();
                invalidate();
            });
            dayFade.start();
            // The programme stays lit beneath, as where Down goes back to; dimmed while the day has focus.
            invalidate();
        }

        boolean days(int d) {
            long s = Math.max(earliest(), Math.min(latest(), startTarget + d * GuideData.DAY));
            if (s == startTarget) return true;
            Clicks.move(getContext(), 0.5f, false);
            anchor = Math.max(earliest(), anchor + d * GuideData.DAY);
            go(row, showAt(row, anchor), s, true);
            return true;
        }

        // ------------------------------------------------------------ drawing

        @Override
        protected void onDraw(Canvas c) {
            if (channels.isEmpty()) return;
            long now = System.currentTimeMillis();
            long end = (long) (startF + SPAN);
            float gx = px(gridX), gr = px(right);
            // The grid on black, the header's picture fading into it just above the times.
            if (backing == null || backingTop != px(rulerY)) {
                backingTop = px(rulerY);
                backing = new android.graphics.LinearGradient(0, backingTop - px(26), 0, backingTop - px(4), 0x00000000, 0xFF000000,
                        android.graphics.Shader.TileMode.CLAMP);
            }
            shade.setShader(backing);
            c.drawRect(0, backingTop - px(26), getWidth(), getHeight(), shade);

            // The day, over the channels; the times, over the grid.
            small.setTypeface(semibold);
            small.setTextSize(9.5f * unit);
            String day = dayWord(startTarget + SPAN / 2, now);
            float dw = small.measureText(day) + px(20);
            r.set(px(chanX), px(rulerY) - px(2), px(chanX) + Math.max(dw, px(chanW)), px(rulerY) + px(16));
            c.save();
            float ds = 1f + 0.06f * dayFocus;
            c.scale(ds, ds, r.centerX(), r.centerY());
            fill.setColor(0x24FFFFFF);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            if (dayFocus > 0f) {
                fill.setColor(Color.argb(Math.round(255 * dayFocus), 255, 255, 255));
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            }
            small.setColor(blend(Color.WHITE, 0xFF111114, dayFocus));
            small.setTextAlign(Paint.Align.CENTER);
            c.drawText(day, r.centerX(), r.centerY() + small.getTextSize() * 0.36f, small);
            if (dayFocus > 0f) {
                // Left and right change the day: chevrons either side say so.
                small.setColor(blend(0x00111114, 0x99111114, dayFocus));
                c.drawText("\u2039", r.left + px(8), r.centerY() + small.getTextSize() * 0.36f, small);
                c.drawText("\u203a", r.right - px(8), r.centerY() + small.getTextSize() * 0.36f, small);
            }
            c.restore();
            small.setTextAlign(Paint.Align.LEFT);
            small.setTypeface(medium);
            small.setTextSize(9 * unit);
            float nx = x(now);
            boolean nowShown = nx >= gx && nx <= gr;
            String nowText = time(now);
            float nowHalf = (small.measureText(nowText) + px(12)) / 2f;
            line.setColor(0x33FFFFFF);
            line.setStrokeWidth(Math.max(1f, px(0.6f)));
            for (long t = floor30((long) startF); t < end; t += HALF_HOUR) {
                float tx = x(t);
                if (tx < gx - px(1)) continue;
                // Each half hour: a short tick down to the rows, its time beside it (not under now's).
                c.drawLine(tx, px(rulerY) + px(9), tx, px(rulerY) + px(15), line);
                String label = time(t);
                float lw = small.measureText(label);
                if (nowShown && tx + px(4) < nx + nowHalf + px(4) && tx + px(4) + lw > nx - nowHalf - px(4)) continue;
                small.setColor(0x8CFFFFFF);
                c.drawText(label, tx + px(4), px(rulerY) + px(12), small);
            }

            // The rows, from just under the times to the bottom of the screen.
            c.save();
            c.clipRect(0, px(rowsY) - px(4), getWidth(), getHeight());
            int first = Math.max(0, (int) Math.floor(top)), last = Math.min(channels.size() - 1, (int) Math.ceil(top) + visibleRows + 1);
            for (int i = first; i <= last; i++) {
                GuideData.Channel ch = channels.get(i);
                float y = px(rowsY) + (i - top) * px(pitch);
                channelCard(c, ch, y, i == row);
                c.save();
                // Room for the programme focus just left, still lifted and shadowed as it settles
                // (3 px wider, 2 taller and its shadow 4 lower): the focused one's own clip.
                c.clipRect(gx - px(2), y - px(4), gr + px(8), y + px(rowH) + px(8));
                for (GuideData.Show s : ch.shows) {
                    if (s.end <= startF || s.start >= end) continue;
                    if (s == focus) continue;  // drawn last, over its neighbours
                    float f = s == lastFocus ? focusOut : 0f;
                    cell(c, s, y, f, now, gx);
                }
                c.restore();
            }
            if (focus != null && focus.channel != null) {
                int fi = channels.indexOf(focus.channel);
                if (fi >= 0) {
                    float y = px(rowsY) + (fi - top) * px(pitch);
                    c.save();
                    // Its row's right edge too: a programme running on past the window stops there
                    // with the rest, not at the screen's edge.
                    c.clipRect(gx - px(2), px(rowsY) - px(4), gr + px(8), getHeight());
                    cell(c, focus, y, focusIn * (1f - 0.75f * dayFocus), now, gx);
                    c.restore();
                }
            }
            c.restore();
            // The rows fade out at the bottom of the screen rather than stop at its edge.
            if (bottomFade == null || fadeH != getHeight()) {
                fadeH = getHeight();
                bottomFade = new android.graphics.LinearGradient(0, fadeH - px(46), 0, fadeH, 0x00000000, 0xE6000000,
                        android.graphics.Shader.TileMode.CLAMP);
            }
            shade.setShader(bottomFade);
            c.drawRect(0, fadeH - px(46), getWidth(), fadeH, shade);
            // Now, on the time row: the time in a red capsule, as the Apple TV app marks live, with a
            // short stem down to the rows. No line through them: it struck through every title it met;
            // what's on now shows how far through it is instead (cell).
            if (nowShown) {
                line.setColor(0xFFFF453A);
                line.setStrokeWidth(Math.max(2f, px(1f)));
                c.drawLine(nx, px(rulerY) + px(14), nx, px(rowsY) - px(2), line);
                r.set(nx - nowHalf, px(rulerY) - px(1), nx + nowHalf, px(rulerY) + px(15));
                fill.setColor(0xFFFF453A);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
                small.setTypeface(semibold);
                small.setColor(Color.WHITE);
                small.setTextAlign(Paint.Align.CENTER);
                c.drawText(nowText, nx, r.centerY() + small.getTextSize() * 0.36f, small);
                small.setTextAlign(Paint.Align.LEFT);
            }
        }

        void channelCard(Canvas c, GuideData.Channel ch, float y, boolean current) {
            r.set(px(chanX), y, px(chanX + chanW), y + px(rowH));
            if (current) {
                fill.setColor(0x1FFFFFFF);
                c.drawRoundRect(r, px(8), px(8), fill);
            }
            small.setTypeface(semibold);
            small.setTextSize(9 * unit);
            small.setColor(current ? 0xE6FFFFFF : 0x73FFFFFF);
            String n = String.valueOf(ch.number);
            c.drawText(n, r.left + px(9), r.centerY() + small.getTextSize() * 0.36f, small);
            Bitmap b = logo(ch);
            float lx = r.left + px(30), lw = r.right - px(8) - lx, lh = px(26);
            if (b != null) {
                float scale = Math.min(lw / b.getWidth(), lh / b.getHeight());
                float w = b.getWidth() * scale, h = b.getHeight() * scale;
                float bx = lx + (lw - w) / 2f, by = r.centerY() - h / 2f;
                bitmaps.setAlpha(current ? 255 : 190);
                c.drawBitmap(b, null, new RectF(bx, by, bx + w, by + h), bitmaps);
            } else {
                text.setTypeface(medium);
                text.setTextSize(9 * unit);
                text.setColor(0xB3FFFFFF);
                c.drawText(fit(ch.name, text, lw), lx, r.centerY() + text.getTextSize() * 0.36f, text);
            }
        }

        /** One programme: glass, its title and times; f (0..1) is focus, a white plate and a lift. */
        void cell(Canvas c, GuideData.Show s, float y, float f, long now, float gx) {
            float x0 = x(s.start) + px(2), x1 = x(s.end) - px(2);
            if (x1 - x0 < px(2)) return;
            boolean past = s.end <= now, live = s.onNow(now);
            c.save();
            r.set(x0, y, x1, y + px(rowH));
            // Lifted: the plate grows by a few pixels all round, as the Glass pills do. Scaled
            // instead, a film's ends grew past the grid's edge and were cut flat there.
            if (f > 0f) r.inset(-px(3) * f, -px(2) * f);
            if (f > 0f) {
                fill.setColor(Color.argb(Math.round(70 * f), 0, 0, 0));
                RectF sh = new RectF(r);
                sh.offset(0, px(4) * f);
                c.drawRoundRect(sh, px(8), px(8), fill);
            }
            float rad = px(8);
            fill.setColor(past ? 0x0DFFFFFF : live ? 0x26FFFFFF : 0x17FFFFFF);
            c.drawRoundRect(r, rad, rad, fill);
            // A hairline edge, lighter on what's on now: glass, not grey blocks.
            float hw = rim.getStrokeWidth() / 2f;
            rim.setColor(past ? 0x0FFFFFFF : live ? 0x2EFFFFFF : 0x1AFFFFFF);
            c.drawRoundRect(r.left + hw, r.top + hw, r.right - hw, r.bottom - hw, rad - hw, rad - hw, rim);
            if (live) {
                // What has played of it: a lighter tint up to now.
                c.save();
                c.clipRect(r.left, r.top, Math.min(r.right, x(now)), r.bottom);
                fill.setColor(0x1AFFFFFF);
                c.drawRoundRect(r, rad, rad, fill);
                c.restore();
            }
            if (f > 0f) {
                fill.setColor(Color.argb(Math.round(255 * f), 255, 255, 255));
                c.drawRoundRect(r, rad, rad, fill);
            }
            if (live) {
                // How far through it is: a thin bar along the bottom.
                float p = (now - s.start) / (float) (s.end - s.start);
                float bx0 = r.left + px(9), bx1 = r.right - px(9);
                fill.setColor(blend(0x33FFFFFF, 0x26000000, f));
                c.drawRoundRect(bx0, r.bottom - px(5), bx1, r.bottom - px(3), px(1), px(1), fill);
                fill.setColor(blend(0xCCFFFFFF, 0xB3000000, f));
                c.drawRoundRect(bx0, r.bottom - px(5), bx0 + (bx1 - bx0) * p, r.bottom - px(3), px(1), px(1), fill);
            }
            // The words start just inside the grid's edge when the programme began before it. A
            // slot too narrow for a word shows none, rather than a letter and dots.
            float tx = Math.max(x0, gx) + px(9), room = x1 - px(9) - tx;
            if (room > px(26)) {
                text.setTypeface(medium);
                text.setTextSize(11 * unit);
                text.setColor(blend(past ? 0x99FFFFFF : 0xFFFFFFFF, 0xFF111114, f));
                c.drawText(fit(s.title, text, room), tx, y + px(16), text);
                small.setTypeface(medium);
                small.setTextSize(8.5f * unit);
                small.setColor(blend(0x80FFFFFF, 0xA6111114, f));
                float sx = tx;
                if (past && s.watchable(now)) {
                    // Catch-up: a small play mark, as Freely's.
                    float h = px(6.5f), by = y + px(29.5f) - h * 0.9f;
                    tri.reset();
                    tri.moveTo(sx, by);
                    tri.lineTo(sx + h * 0.87f, by + h / 2f);
                    tri.lineTo(sx, by + h);
                    tri.close();
                    fill.setColor(small.getColor());
                    c.drawPath(tri, fill);
                    sx += px(10);
                }
                // Both times when they fit, else when it starts, else nothing: never a time cut short.
                String when = time(s.start) + " \u2013 " + time(s.end);
                float left = room - (sx - tx);
                if (small.measureText(when) > left) when = time(s.start);
                if (small.measureText(when) <= left) c.drawText(when, sx, y + px(29.5f), small);
            }
            c.restore();
        }

        int blend(int a, int b, float f) {
            if (f <= 0f) return a;
            if (f >= 1f) return b;
            int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
            return Color.argb(Math.round(aa + (ba - aa) * f), Math.round(ar + (br - ar) * f),
                    Math.round(ag + (bg - ag) * f), Math.round(ab + (bb - ab) * f));
        }

        /** The words cut to the room there is, with an ellipsis. */
        String fit(String s, Paint p, float w) {
            if (s == null) return "";
            if (p.measureText(s) <= w) return s;
            float dots = p.measureText("\u2026");
            int n = p.breakText(s, true, Math.max(0f, w - dots), null);
            return n <= 0 ? "" : s.substring(0, n).trim() + "\u2026";
        }
    }
}

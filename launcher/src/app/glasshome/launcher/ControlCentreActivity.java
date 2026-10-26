package app.glasshome.launcher;

import android.animation.ArgbEvaluator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Control Centre: a glass panel in the top-right corner of the screen, over Home or any app.
 * Opened by the gear on Home and by the remote's Quick Settings button (KeysService); a second
 * press, Back, or opening anything closes it. It holds what a TV needs at hand, like the quick
 * panels on Google TV, LG and Samsung sets: power, Home and settings beside the time, what's
 * playing, then inputs, picture, sound, network, Bluetooth, sleep timer and screen saver.
 */
public class ControlCentreActivity extends Activity {

    static final int COLS = 2;
    /** Layout in design units (1/960 of the screen): tile size, the gap between tiles, panel padding. */
    static final float TILE_W = 134, TILE_H = 62, GAP = 11, PAD = 22;
    /** How far a focused control lifts: tvOS's 1.08, after a quick pop to about 1.12. */
    static final float LIFT = 1.08f;
    /**
     * The only colours in the panel, for state, as on tvOS: a blue Wi-Fi glyph on a network, a cyan
     * timer while it runs (a deeper cyan on the white plate, where the light one would wash out).
     */
    static final int BLUE = 0xFF0A84FF, CYAN = 0xFF64D2FF, CYAN_ON_WHITE = 0xFF0A8FB8, INK = 0xFF1C1C1E;
    /** What the Wi-Fi tile says when there's no Wi-Fi network to name. */
    static final String NOT_CONNECTED = "Not Connected", ETHERNET = "Ethernet", CONNECTED = "Connected";
    /** The Sleep Timer's choices, as tvOS's: 2 hours down to 15 minutes. */
    static final int[] SLEEP_MINUTES = {120, 60, 30, 15};
    static final String[] SLEEP_NAMES = {"2 Hours", "1 Hour", "30 Minutes", "15 Minutes"};
    static final ArgbEvaluator EV = new ArgbEvaluator();

    FrameLayout root;
    /** The sheet is the dock's glass: it redraws as it moves, so the picture in it stays put. */
    boolean liquid;
    LinearLayout sheet, header, buttons, body;
    View shade;
    TextClock clock, dateLine;
    TextView heading;
    boolean closing, onSubpage;
    /** Opened by Home's Inputs tile: just the inputs, and Back closes. */
    boolean inputsOnly;
    /** While the panel is being built to appear: its first control is simply shown focused. */
    boolean opening;
    /** The tile the main page focuses when it comes back: the one its sub-page was opened from. */
    String returnTo;

    int dp(float v) {
        return Ui.px(this, v);
    }

    int sheetWidth() {
        return dp(2 * TILE_W + GAP + 2 * PAD);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        // Everything behind dims evenly to 70%, with no blur, as it does behind tvOS's Control Centre.
        shade = new View(this);
        shade.setBackgroundColor(0x4D000000);
        // One flat fill: its fade needs no full-screen off-screen pass, which this GPU would
        // otherwise allocate and fill as the panel opens and closes.
        shade.forceHasOverlappingRendering(false);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);
        sheet.setPadding(dp(PAD), dp(PAD), dp(PAD), dp(PAD));
        sheet.setBackground(glassSheet());
        sheet.forceHasOverlappingRendering(false);  // fades as one alpha, without an off-screen pass
        // No elevation: a 16 dp shadow over the live Home behind costs this GPU more than the rim is worth.
        // Inside the TV safe area (Apple: 60 pt top and bottom, 80 pt sides), so overscan never trims
        // it, and only as tall as what's in it, like tvOS's.
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(sheetWidth(), -2, Gravity.END | Gravity.TOP);
        slp.topMargin = dp(30);
        slp.rightMargin = dp(40);
        root.addView(sheet, slp);

        // The time (or the page's name) and the date, with the round buttons beside them on the
        // main page, as along the top of tvOS's Control Centre. The time ticks while the panel is
        // open, in the TV's 12- or 24-hour style, as Home's clock does.
        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        header.forceHasOverlappingRendering(false);  // fades as one alpha, without an off-screen pass
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        buttons = new LinearLayout(this);
        buttons.setClipChildren(false);
        header.addView(buttons, new LinearLayout.LayoutParams(-2, -2));
        sheet.addView(header, new LinearLayout.LayoutParams(-1, -2));

        clock = new TextClock(this);
        clock.setFormat24Hour("H:mm");
        clock.setFormat12Hour("h:mm a");
        clock.setAllCaps(true);
        titles.addView(title(clock));
        heading = title(new TextView(this));
        titles.addView(heading);
        dateLine = new TextClock(this);
        dateLine.setFormat24Hour("EEEE d MMMM");
        dateLine.setFormat12Hour("EEEE d MMMM");
        dateLine.setTextColor(0x99FFFFFF);
        dateLine.setTypeface(Ui.font(this, "medium"));
        Ui.textSize(dateLine, 11.5f);
        dateLine.setSingleLine(true);  // beside the buttons: one line, whatever the date
        dateLine.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(dateLine);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        // Pages fade tile by tile rather than as one image, which would be cut to the page's
        // edges (and slice the corner off a button lifting past them), and costs a pass each frame.
        body.forceHasOverlappingRendering(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(16);
        sheet.addView(body, blp);

        setContentView(root);
        inputsOnly = getIntent().getBooleanExtra("inputs", false);
        opening = true;
        if (inputsOnly) showInputsFirst();
        else showMain(0);
        opening = false;
        // On Home, its own clock and gear step aside while the panel is open.
        MainActivity home = MainActivity.live.get();
        if (home != null) home.statusRow(false);

        // Appears in its corner, the glass growing a little out of it as it fades up (tvOS 26's
        // materialise, not a slide), a frame after the window's first draw so the setup work
        // doesn't eat its opening frames.
        shade.setAlpha(0f);
        sheet.setPivotX(sheetWidth());
        sheet.setPivotY(0);
        sheet.setScaleX(0.94f);
        sheet.setScaleY(0.94f);
        sheet.setAlpha(0f);
        root.post(() -> root.postOnAnimation(() -> {
            // Closed before it began (a second press in its first frames): fading up now would
            // cancel the close, and its finish with it, leaving the panel up for good.
            if (closing) return;
            if (liquid) {
                // The dock's glass samples Home's picture where the panel comes to rest, not where its
                // opening scale puts it, so the panel can grow in as one cached picture (slide).
                int[] at = new int[2];
                root.getLocationOnScreen(at);
                ((MainActivity.LiquidGlass) sheet.getBackground()).fixedLoc = new int[]{at[0] + sheet.getLeft(), at[1] + sheet.getTop()};
            }
            shade.animate().alpha(1f).setDuration(280).setInterpolator(MainActivity.EASE).start();
            slide(sheet.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(MainActivity.EASE)).start();
        }));
    }

    /** The time and the pages' names: the same large type. */
    <T extends TextView> T title(T t) {
        t.setTextColor(Color.WHITE);
        t.setTypeface(Ui.font(this, "display"));
        Ui.textSize(t, 24);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing()) finish();  // anything opened from here replaces the panel
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        close();  // Quick Settings pressed again
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        int dx = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = k == KeyEvent.KEYCODE_DPAD_UP ? -1 : k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        View f = getCurrentFocus();
        if ((dx != 0 || dy != 0) && e.getAction() == KeyEvent.ACTION_DOWN && f != null
                && f.focusSearch(dx < 0 ? View.FOCUS_LEFT : dx > 0 ? View.FOCUS_RIGHT : dy < 0 ? View.FOCUS_UP : View.FOCUS_DOWN) == null) {
            // Nowhere to go that way: the control leans towards the press and springs back, and
            // there's no tick, as tvOS plays none for a move that can't happen.
            if (e.getRepeatCount() == 0) Glass.bump(f, dx, dy);
            return super.dispatchKeyEvent(e);
        }
        MainActivity.navSound(this, e);  // the same ticks as everywhere else
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (onSubpage && !inputsOnly) showMain(-1);
            else close();
            return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unwatch();
    }

    @Override
    public void finish() {
        // Closing already brought Home's clock back as the panel went; doing it again restarted its fade.
        MainActivity home = MainActivity.live.get();
        if (home != null && !closing) home.statusRow(true);
        super.finish();
        overridePendingTransition(0, 0);
    }

    /** The glass goes back into its corner as it fades, a little quicker than it came. */
    void close() {
        if (closing) return;
        closing = true;
        MainActivity home = MainActivity.live.get();
        if (home != null) home.statusRow(true);
        shade.animate().alpha(0f).setDuration(200).setInterpolator(MainActivity.EASE).start();
        slide(sheet.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(200).setInterpolator(MainActivity.EASE))
                .withEndAction(this::finish).start();
    }

    // ---------------------------------------------------------------- pages

    /**
     * The panel grows in and fades out as one cached layer: redrawn at a new scale every frame, its
     * glass, buttons and words took this GPU 21-25 ms a frame. The dock's glass is pinned to where
     * the panel rests, so the layer holds the same picture it settles on.
     */
    android.view.ViewPropertyAnimator slide(android.view.ViewPropertyAnimator a) {
        return a.withLayer();
    }

    /**
     * Swaps the panel's page in the same place, as tvOS's Control Centre opens a control's choices:
     * the pressed control swells as the page fades, and the new page grows out of where it was
     * (dir 1 = deeper, -1 = back, from the middle; 0 = first page, at once). A null title is the
     * main page, headed by the time and date.
     */
    void page(int dir, String title, View from, Runnable build) {
        Runnable in = () -> {
            // Where the new page grows from, found before the old page goes.
            float px = body.getWidth() / 2f, py = body.getHeight() / 2f;
            if (from != null && from.isAttachedToWindow()) {
                Rect r = new Rect();
                from.getDrawingRect(r);
                body.offsetDescendantRectToMyCoords(from, r);
                px = r.exactCenterX();
                py = r.exactCenterY();
            }
            clock.setVisibility(title == null ? View.VISIBLE : View.GONE);
            dateLine.setVisibility(title == null ? View.VISIBLE : View.GONE);
            heading.setVisibility(title == null ? View.GONE : View.VISIBLE);
            heading.setText(title);
            body.removeAllViews();
            build.run();
            if (dir != 0) {
                float s = dir > 0 ? 0.96f : 1.04f;
                body.setPivotX(px);
                body.setPivotY(py);
                body.setScaleX(s);
                body.setScaleY(s);
                body.setAlpha(0f);
                header.setAlpha(0f);
                body.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(MainActivity.EASE).start();
                header.animate().alpha(1f).setDuration(240).setInterpolator(MainActivity.EASE).start();
            }
        };
        if (dir == 0 || body.getChildCount() == 0) {
            in.run();
            return;
        }
        header.animate().alpha(0f).setDuration(120).setInterpolator(MainActivity.EASE).start();
        if (from != null) from.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).setInterpolator(MainActivity.EASE).start();
        body.animate().alpha(0f).setDuration(120).setInterpolator(MainActivity.EASE).withEndAction(in).start();
    }

    void showMain(int dir) {
        onSubpage = false;
        page(dir, null, null, () -> {
            buttons.removeAllViews();
            button("power", "Turn Off", v -> {
                if (SleepReceiver.sleepNow(this)) finish();
                else Perms.requestAdmin(this);
            });
            button("house", "Home", v -> open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)));
            button("gear", "Settings", v -> open(new Intent(Settings.ACTION_SETTINGS)));
            android.media.session.MediaController mc = nowPlaying();
            if (mc != null) nowPlayingRow(mc);
            Tiles g = new Tiles(dp(TILE_W), dp(TILE_H), dp(GAP));
            if (Inputs.any(this)) g.add("hdmi", "Inputs", null, this::showInputs);
            g.add("picture", "Picture", null,
                    v -> open(tvSettings(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)));
            g.add("audio", "Sound", this::audioOutput,
                    v -> open(tvSettings("com.android.tv.action.VIEW_SOUND_SETTINGS", "android.settings.SOUND_SETTINGS")));
            g.add("wifi", "Wi-Fi", this::wifiName, v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
            g.add("bluetooth", "Bluetooth", null,
                    v -> open(tvSettings("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES", Settings.ACTION_BLUETOOTH_SETTINGS)));
            g.add("timer", "Sleep Timer", this::timerState, this::showSleep);
            g.add("sparkles", "Screen Saver", null, v -> open(screenSaver(this)));
            g.add("shelf", "Home Screen", null, v -> openSettings());
            View back = g.titled(returnTo);
            returnTo = null;
            if (back != null) back.requestFocus();
            else if (g.first != null) g.first.requestFocus();
        });
    }

    /** A control's own page in the same panel (it keeps its size, as tvOS's does). Back returns. */
    void subpage(String title, View from, Runnable build) {
        onSubpage = true;
        returnTo = title;
        sheet.setMinimumHeight(sheet.getHeight());
        page(1, title, from, build);
    }

    void showInputsFirst() {
        onSubpage = true;
        page(0, "Inputs", null, this::inputTiles);
    }

    /** The TV's inputs (HDMI, AV, a USB drive) as tiles in the same panel. */
    void showInputs(View from) {
        subpage("Inputs", from, this::inputTiles);
    }

    void inputTiles() {
        buttons.removeAllViews();
        unwatch();
        Tiles g = new Tiles(dp(TILE_W), dp(TILE_H), dp(GAP));
        for (Inputs.Input in : Inputs.list(this)) {
            g.add(Inputs.glyph(in), in.label, null, v -> {
                if (Inputs.open(this, in)) finish();
                else Toast.makeText(this, in.label + " isn’t available.", Toast.LENGTH_SHORT).show();
            });
        }
        if (g.first != null) g.first.requestFocus();
    }

    /** The Sleep Timer's choices, as tvOS lists them in its Control Centre; Turn Off while one runs. */
    void showSleep(View from) {
        if (!SleepReceiver.canSleep(this)) {
            Perms.requestAdmin(this);
            return;
        }
        subpage("Sleep Timer", from, this::sleepTiles);
    }

    void sleepTiles() {
        buttons.removeAllViews();
        unwatch();
        Tiles g = new Tiles(dp(TILE_W), dp(TILE_H), dp(GAP));
        int running = SleepReceiver.timerChoice(this);
        if (SleepReceiver.timerLeftMs(this) > 0) g.add(null, "Turn Off", null, v -> setSleep(0));
        View focus = null;
        for (int i = 0; i < SLEEP_MINUTES.length; i++) {
            int m = SLEEP_MINUTES[i];
            View t = g.add(null, SLEEP_NAMES[i], null, v -> setSleep(m));
            if (m == running) focus = t;
        }
        (focus != null ? focus : g.first).requestFocus();
    }

    void setSleep(int minutes) {
        SleepReceiver.setTimer(this, minutes);
        showMain(-1);
    }

    /** A round glass button beside the time. */
    void button(String glyph, String name, View.OnClickListener click) {
        if (buttons.getChildCount() > 0) space(buttons, dp(10));
        circle(buttons, dp(40), glyph, click).setContentDescription(name);
    }

    /** Lays tiles out two to a row in the panel body. */
    final class Tiles {
        final int w, h, gap;
        LinearLayout row;
        View first;
        int n;

        Tiles(int w, int h, int gap) {
            this.w = w;
            this.h = h;
            this.gap = gap;
        }

        View add(String glyph, String title, Status status, View.OnClickListener click) {
            if (n % COLS == 0) {
                row = new LinearLayout(ControlCentreActivity.this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.bottomMargin = gap;
                body.addView(row, lp);
            } else {
                space(row, gap);
            }
            View t = tile(row, w, h, glyph, title, status, click);
            t.setTag(title);
            if (first == null) first = t;
            n++;
            return t;
        }

        /** The tile with this title, or null. */
        View titled(String title) {
            return title == null ? null : body.findViewWithTag(title);
        }
    }

    void space(LinearLayout r, int w) {
        r.addView(new View(this), new LinearLayout.LayoutParams(w, 1));
    }

    void open(Intent i) {
        Suggestions.start(this, i);
        finish();
    }

    /**
     * Glass Home's settings, opened by Home itself as this panel goes: Settings belongs with Home,
     * and opened straight from here (another task) this TV hid it for half a second and then cut
     * to it, where from Home it fades up as a page.
     */
    void openSettings() {
        MainActivity.settingsNext = true;
        open(new Intent(this, MainActivity.class));
    }

    /**
     * The screen saver straight away. Glass Aerials' own player when it's the chosen screen saver
     * (or none is chosen): it turns the TV off after Sleep After, as the dream would, and Android's
     * "start screen saver" (Somnambulator) does nothing on this TCL. For another chosen screen
     * saver, that, which plays it on TVs where it works.
     */
    static Intent screenSaver(Context c) {
        Intent aerials = new Intent().setClassName("app.glasshome.aerials", "app.glasshome.aerials.AerialActivity");
        Intent dream = new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
        String chosen = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
        boolean ours = chosen == null || chosen.isEmpty() || chosen.startsWith("app.glasshome.aerials/")
                || "0".equals(Settings.Secure.getString(c.getContentResolver(), "screensaver_enabled"));
        PackageManager pm = c.getPackageManager();
        if ((ours || dream.resolveActivity(pm) == null) && aerials.resolveActivity(pm) != null) return aerials;
        return dream;
    }

    /** The TV's own screen for an action, or a general one when this TV has no such screen. */
    Intent tvSettings(String action, String fallback) {
        Intent i = new Intent(action);
        return i.resolveActivity(getPackageManager()) != null ? i : new Intent(fallback);
    }

    // ---------------------------------------------------------------- glass

    /**
     * The panel's glass. Over Home it is the dock's own Liquid Glass, drawn from the same blurred
     * picture, so the two read as one material. Over an app, where there's no picture to borrow,
     * a grey frost of about the same weight. Its corners are concentric with the round buttons
     * inset in them, as tvOS 26 nests its shapes.
     */
    Drawable glassSheet() {
        float r = dp(42);
        MainActivity home = MainActivity.live.get();
        if (home != null && home.glassBg != null && home.getWindow().getDecorView().getVisibility() == View.VISIBLE) {
            // The same glass as Home's weather, clock and gear, a bigger piece of it.
            // The shade dims Home to 0.7; the glass shows the picture at that brightness too.
            MainActivity.LiquidGlass g = home.controlGlass(r).dimmed(0.7f);
            g.lens = home.dp(12);
            liquid = true;
            return g;
        }
        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xA6303036, 0xB8202026});
        body.setCornerRadius(r);
        body.setStroke(Math.max(1, dp(0.6f)), 0x3DFFFFFF);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x24FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        LayerDrawable l = new LayerDrawable(new Drawable[]{body, sheen});
        l.setLayerInsetBottom(1, dp(160));
        return l;
    }

    /** A control's glass, with a white plate over it that fades in on focus. */
    Glass glass(float radius) {
        return new Glass(radius, dp(0.6f), Glass.DARK);
    }

    interface Status {
        String get();
    }

    /**
     * A control, as tvOS 26's Control Centre draws them: a glass capsule with its glyph on the left,
     * then its name with its state below. Text only (no glyph) for a sub-page's choices.
     */
    View tile(LinearLayout parent, int w, int h, String glyph, String title, Status status, View.OnClickListener click) {
        Glass white = glass(h / 2f);  // a capsule
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackground(white);
        t.setPadding(dp(14), 0, dp(12), 0);
        t.setFocusable(true);
        t.setClickable(true);

        String now = status == null ? null : status.get();
        // The glyph's colour at rest and on the white plate: white turning dark like the text, or a
        // state colour (Wi-Fi on a named network, a running timer).
        boolean timing = "timer".equals(glyph) && SleepReceiver.timerLeftMs(this) > 0;
        int[] tone = "wifi".equals(glyph) && named(now) ? new int[]{BLUE, BLUE}
                : timing ? new int[]{CYAN, CYAN_ON_WHITE} : new int[]{Color.WHITE, INK};
        ImageView gi = null;
        if (glyph != null) {
            gi = new ImageView(this);
            gi.setImageBitmap(Glyphs.get(glyph, dp(16), Color.WHITE));
            gi.setColorFilter(tone[0]);
            t.addView(gi, new LinearLayout.LayoutParams(dp(16), dp(16)));
        }
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (gi != null) wp.leftMargin = dp(8);
        t.addView(words, wp);
        TextView tt = label(12f, "semibold", title);
        words.addView(tt);
        TextView sub = status == null ? null : label(10.5f, "regular", now);
        if (sub != null) {
            sub.setTextColor(0xA6FFFFFF);
            words.addView(sub);
        }
        // Text and glyph change colour with the plate as it fades, frame by frame, so nothing flips
        // ahead of it (white text on a still-white plate, a dark glyph on dark glass).
        ImageView icon = gi;
        white.tint = f -> {
            tt.setTextColor((int) EV.evaluate(f, Color.WHITE, INK));
            if (sub != null) sub.setTextColor((int) EV.evaluate(f, 0xA6FFFFFF, 0x991C1C1E));
            if (icon != null) icon.setColorFilter((int) EV.evaluate(f, tone[0], tone[1]));
        };
        if (timing && sub != null) {
            // The time left, live while the panel is open, as tvOS's "29:59", each tick landing
            // just after a whole second so none is skipped. When it runs out the glyph goes white.
            Runnable tick = new Runnable() {
                @Override
                public void run() {
                    long left = SleepReceiver.timerLeftMs(ControlCentreActivity.this);
                    sub.setText(timerState(left));
                    if (left > 0) {
                        t.postDelayed(this, left % 1000 + 1);
                    } else {
                        tone[0] = Color.WHITE;
                        tone[1] = INK;
                        white.setFocus(white.getFocus());
                    }
                }
            };
            t.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    tick.run();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    v.removeCallbacks(tick);
                }
            });
        }
        t.setOnFocusChangeListener((v, has) -> focusOn(v, white, has, LIFT));
        t.setOnClickListener(v -> {
            click.onClick(v);
            if (sub != null && !isFinishing()) sub.setText(status.get());
        });
        parent.addView(t, new LinearLayout.LayoutParams(w, h));
        return t;
    }

    View circle(LinearLayout parent, int s, String glyph, View.OnClickListener click) {
        Glass white = glass(s / 2f);
        ImageView t = new ImageView(this);
        t.setBackground(white);
        t.setScaleType(ImageView.ScaleType.CENTER);
        t.setImageBitmap(Glyphs.get(glyph, Math.round(s * 0.48f), Color.WHITE));
        t.setFocusable(true);
        t.setClickable(true);
        white.tint = f -> t.setColorFilter((int) EV.evaluate(f, Color.WHITE, INK));
        t.setOnFocusChangeListener((v, has) -> focusOn(v, white, has, LIFT));
        t.setOnClickListener(click);
        parent.addView(t, new LinearLayout.LayoutParams(s, s));
        return t;
    }

    TextView label(float size, String weight, String text) {
        TextView t = new TextView(this);
        Ui.textSize(t, size);
        t.setTypeface(Ui.font(this, weight));
        t.setTextColor(Color.WHITE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setText(text);
        return t;
    }

    /**
     * Focus arriving or leaving: the white plate fades in as the control pops up, tvOS's quick
     * 1.12 settling on 1.08. While the panel is still appearing it's simply shown focused, so
     * nothing in the panel animates under its fade.
     */
    void focusOn(View v, Glass white, boolean has, float scale) {
        if (opening) {
            white.setFocus(has ? 1f : 0f);
            v.setScaleX(has ? scale : 1f);
            v.setScaleY(has ? scale : 1f);
            return;
        }
        Glass.focus(white, has);
        Glass.lift(v, has, scale, 0, 1f, true, 330, Glass.POP);
    }

    // ---------------------------------------------------------------- now playing

    /**
     * What's playing, else what's paused; null when nothing is, or notification access is off.
     * Some players (BBC iPlayer) keep a session open but never say what's on or whether it's
     * playing: over an app, the most recent such session stands in, shown by the app's name.
     */
    android.media.session.MediaController nowPlaying() {
        try {
            android.media.session.MediaSessionManager m = getSystemService(android.media.session.MediaSessionManager.class);
            List<android.media.session.MediaController> all =
                    m.getActiveSessions(new android.content.ComponentName(this, MediaListener.class));
            android.media.session.MediaController paused = null;
            for (android.media.session.MediaController c : all) {
                int st = state(c);
                if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) return c;
                if (st == PlaybackState.STATE_PAUSED && paused == null) paused = c;
            }
            if (paused != null || all.isEmpty() || MainActivity.onScreen) return paused;
            return all.get(0);  // sessions come most recently active first
        } catch (SecurityException e) {
            return null;
        }
    }

    static int state(android.media.session.MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s == null ? PlaybackState.STATE_NONE : s.getState();
    }

    /** The player being followed while the panel is open, and what it calls when it changes. */
    android.media.session.MediaController watched;
    android.media.session.MediaController.Callback watcher;

    void unwatch() {
        if (watched != null) watched.unregisterCallback(watcher);
        watched = null;
    }

    /**
     * Now Playing, as in tvOS's Control Centre: the artwork with the title and who it's by (or the
     * app), which opens the app, and play/pause beside it. Both follow the player while it's open.
     */
    void nowPlayingRow(android.media.session.MediaController mc) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(GAP);
        body.addView(row, lp);

        Glass white = glass(dp(TILE_H) / 2f);
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(white);
        card.setPadding(dp(8), dp(8), dp(18), dp(8));
        card.setFocusable(true);
        card.setClickable(true);
        int side = dp(TILE_H - 16);
        ImageView art = new ImageView(this);
        GradientDrawable well = new GradientDrawable();
        well.setCornerRadius(dp(10));
        well.setColor(0x29FFFFFF);
        art.setBackground(well);
        art.setClipToOutline(true);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new LinearLayout.LayoutParams(side, side));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.leftMargin = dp(10);
        card.addView(words, wp);
        TextView title = label(12f, "semibold", "");
        TextView by = label(10.5f, "regular", "");
        by.setTextColor(0xA6FFFFFF);
        words.addView(title);
        words.addView(by);
        white.tint = f -> {
            title.setTextColor((int) EV.evaluate(f, Color.WHITE, INK));
            by.setTextColor((int) EV.evaluate(f, 0xA6FFFFFF, 0x991C1C1E));
        };
        // A smaller lift than the tiles': at 1.12 this wide card's pop would touch play/pause.
        card.setOnFocusChangeListener((v, has) -> focusOn(v, white, has, 1.04f));
        card.setOnClickListener(v -> {
            Intent i = Suggestions.launchIntent(this, mc.getPackageName());
            if (i != null) open(i);
        });
        row.addView(card, new LinearLayout.LayoutParams(0, dp(TILE_H), 1f));
        space(row, dp(GAP));
        int button = dp(46);
        ImageView toggle = (ImageView) circle(row, button, "pause", v -> {
            int st = state(mc);
            if (st == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause();
            else if (st == PlaybackState.STATE_PAUSED) mc.getTransportControls().play();
            else {
                // A player that doesn't say: the remote's own Play/Pause button, which it does answer.
                mc.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                mc.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            }
        });
        toggle.setContentDescription("Play or Pause");

        Runnable showState = () -> {
            int st = state(mc);
            String g = st == PlaybackState.STATE_PLAYING ? "pause" : st == PlaybackState.STATE_PAUSED ? "play" : "playpause";
            toggle.setImageBitmap(Glyphs.get(g, Math.round(button * 0.48f), Color.WHITE));
        };
        Runnable showTrack = () -> {
            String app = mc.getPackageName();
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                app = pm.getApplicationLabel(pm.getApplicationInfo(app, 0)).toString();
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // the package name will do
            }
            android.media.MediaMetadata md = mc.getMetadata();
            android.media.MediaDescription d = md == null ? null : md.getDescription();
            String t = d == null || d.getTitle() == null ? "" : d.getTitle().toString();
            String s = d == null || d.getSubtitle() == null ? "" : d.getSubtitle().toString();
            title.setText(t.isEmpty() ? app : t);
            by.setText(t.isEmpty() ? "" : s.isEmpty() ? app : s);
            Bitmap pic = d == null ? null : d.getIconBitmap();
            if (pic == null) {
                art.setScaleType(ImageView.ScaleType.FIT_CENTER);
                art.setPadding(dp(8), dp(8), dp(8), dp(8));
                try {
                    art.setImageDrawable(getPackageManager().getApplicationIcon(mc.getPackageName()));
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                    art.setImageDrawable(null);
                }
                return;
            }
            // Shrunk to the card's size off the main thread: players hand over full-size artwork.
            int px = Math.round(side * 1.05f);
            new Thread(() -> {
                Bitmap small = square(pic, px);
                art.post(() -> {
                    if (!art.isAttachedToWindow()) return;
                    art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    art.setPadding(0, 0, 0, 0);
                    art.setImageBitmap(small);
                });
            }).start();
        };
        showState.run();
        showTrack.run();
        unwatch();
        watcher = new android.media.session.MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState s) {
                showState.run();
            }

            @Override
            public void onMetadataChanged(android.media.MediaMetadata m) {
                showTrack.run();
            }
        };
        watched = mc;
        mc.registerCallback(watcher);
    }

    /** The middle square of a picture at s x s, halved in steps first so it shrinks cleanly. */
    static Bitmap square(Bitmap src, int s) {
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap cur = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        while (cur.getWidth() > s * 2) {
            Bitmap half = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            if (cur != src) cur.recycle();
            cur = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(cur, s, s, true);
        if (cur != src && cur != out) cur.recycle();
        return out;
    }

    // ---------------------------------------------------------------- states

    String wifiName() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) return NOT_CONNECTED;
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return ETHERNET;
            if (!nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return CONNECTED;
            android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
            String ssid = wm.getConnectionInfo().getSSID();
            if (ssid == null || ssid.contains("unknown ssid")) return CONNECTED;
            return ssid.replace("\"", "");
        } catch (Exception e) {
            return CONNECTED;
        }
    }

    /** A Wi-Fi network's name, rather than one of wifiName()'s stand-ins. */
    static boolean named(String wifi) {
        return wifi != null && !wifi.equals(NOT_CONNECTED) && !wifi.equals(ETHERNET) && !wifi.equals(CONNECTED);
    }

    String audioOutput() {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return d.getProductName() + "";
                case android.media.AudioDeviceInfo.TYPE_HDMI_ARC:
                case android.media.AudioDeviceInfo.TYPE_HDMI_EARC: return "Soundbar";
                default: break;
            }
        }
        return "TV Speakers";
    }

    String timerState() {
        return timerState(SleepReceiver.timerLeftMs(this));
    }

    /** "Off", or the time left as tvOS shows it: 29:59, or 1:59:59 over an hour. */
    static String timerState(long ms) {
        if (ms <= 0) return "Off";
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.UK, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.UK, "%d:%02d", s / 60, s % 60);
    }
}

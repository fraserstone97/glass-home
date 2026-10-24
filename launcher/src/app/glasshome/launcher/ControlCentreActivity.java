package app.glasshome.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Control Centre: a translucent glass panel docked to the right of the screen, over Home or any
 * app. Opened by the gear on Home and by the remote's Quick Settings button (KeysService); a
 * second press, Back, or opening anything closes it. It holds what a TV needs at hand, like the
 * quick panels on Google TV, LG and Samsung sets: inputs, picture, sound, network, Bluetooth,
 * sleep timer, screen saver, and power and settings.
 */
public class ControlCentreActivity extends Activity {

    static final int COLS = 2;
    /** Layout in design units (1/960 of the screen): tile size, the gap between tiles, panel padding. */
    static final float TILE_W = 134, TILE_H = 62, GAP = 11, PAD = 22;

    FrameLayout root;
    /** The sheet is the dock's glass: it redraws as it slides, so the picture in it stays put. */
    boolean liquid;
    LinearLayout sheet, body;
    View shade;
    TextView heading, dateLine;
    boolean closing, onInputs;
    /** Opened by Home's Inputs tile: just the inputs, and Back closes. */
    boolean inputsOnly;

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
        // A soft darkening towards the panel, so the picture stays visible on the left.
        shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0xA6000000, 0x52000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);
        sheet.setPadding(dp(PAD), dp(PAD), dp(PAD), dp(PAD));
        sheet.setBackground(glassSheet());
        // No elevation: a 16 dp shadow over the live Home behind costs this GPU more than the rim is worth.
        // Inside the TV safe area (Apple: 60 pt top and bottom, 80 pt sides), so overscan never trims it.
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(sheetWidth(), -1, Gravity.END);
        slp.topMargin = dp(30);
        slp.bottomMargin = dp(30);
        slp.rightMargin = dp(40);
        root.addView(sheet, slp);

        heading = new TextView(this);
        heading.setTextColor(Color.WHITE);
        heading.setTypeface(Ui.font(this, "display"));
        Ui.textSize(heading, 24);
        heading.setLetterSpacing(-0.01f);
        sheet.addView(heading);
        dateLine = new TextView(this);
        dateLine.setTextColor(0x99FFFFFF);
        dateLine.setTypeface(Ui.font(this, "medium"));
        Ui.textSize(dateLine, 11.5f);
        dateLine.setText(new java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.UK).format(new java.util.Date()));
        sheet.addView(dateLine);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(16);
        sheet.addView(body, blp);

        setContentView(root);
        inputsOnly = getIntent().getBooleanExtra("inputs", false);
        if (inputsOnly) showInputsFirst();
        else showMain(0);
        // On Home, its own clock and gear step aside while the panel is open.
        MainActivity home = MainActivity.live.get();
        if (home != null) home.statusRow(false);

        // Slides in from the right edge while the shade fades up, starting a frame after the window's
        // first draw so the setup work doesn't eat the opening frames of the slide.
        shade.setAlpha(0f);
        sheet.setTranslationX(sheetWidth() + dp(40));
        root.post(() -> root.postOnAnimation(() -> {
            shade.animate().alpha(1f).setDuration(360).setInterpolator(MainActivity.EASE).start();
            slide(sheet.animate().translationX(0).setDuration(480).setInterpolator(MainActivity.EASE)).start();
        }));
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
        MainActivity.navSound(this, e);  // the same ticks as everywhere else
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (onInputs && !inputsOnly) showMain(-1);
            else close();
            return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override
    public void finish() {
        MainActivity home = MainActivity.live.get();
        if (home != null) home.statusRow(true);
        super.finish();
        overridePendingTransition(0, 0);
    }

    void close() {
        if (closing) return;
        closing = true;
        MainActivity home = MainActivity.live.get();
        if (home != null) home.statusRow(true);
        shade.animate().alpha(0f).setDuration(260).setInterpolator(MainActivity.EASE).start();
        slide(sheet.animate().translationX(sheetWidth() + dp(40)).setDuration(320).setInterpolator(MainActivity.EASE))
                .withEndAction(this::finish).start();
    }

    // ---------------------------------------------------------------- pages

    /**
     * Swaps the panel's page like tvOS menus: the old page slides out one way while the new one
     * slides in from the other (dir 1 = deeper, -1 = back, 0 = first page, no slide).
     */
    /** Plain glass slides as one cached layer; the dock's glass redraws each frame instead. */
    android.view.ViewPropertyAnimator slide(android.view.ViewPropertyAnimator a) {
        if (liquid) return a.setUpdateListener(v -> sheet.invalidate());
        return a.withLayer();
    }

    void page(int dir, String title, boolean date, Runnable build) {
        Runnable in = () -> {
            heading.setText(title);
            dateLine.setVisibility(date ? View.VISIBLE : View.GONE);
            body.removeAllViews();
            build.run();
            if (dir != 0) {
                body.setTranslationX(dp(36) * dir);
                body.setAlpha(0f);
                heading.setAlpha(0f);
                body.animate().translationX(0).alpha(1f).setDuration(380).setInterpolator(MainActivity.EASE).withLayer().start();
                heading.animate().alpha(1f).setDuration(380).setInterpolator(MainActivity.EASE).start();
            }
        };
        if (dir == 0 || body.getChildCount() == 0) {
            in.run();
            return;
        }
        heading.animate().alpha(0f).setDuration(160).setInterpolator(MainActivity.EASE).start();
        body.animate().translationX(-dp(36) * dir).alpha(0f).setDuration(170).setInterpolator(MainActivity.EASE)
                .withLayer().withEndAction(in).start();
    }

    void showMain(int dir) {
        onInputs = false;
        String now = new java.text.SimpleDateFormat("H:mm", java.util.Locale.UK).format(new java.util.Date());
        page(dir, now, true, () -> {
            Tiles g = new Tiles(dp(TILE_W), dp(TILE_H), dp(GAP));
            if (!Inputs.list(this).isEmpty()) g.add("hdmi", "Inputs", null, v -> showInputs());
            g.add("picture", "Picture", null,
                    v -> open(tvSettings(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)));
            g.add("audio", "Sound", this::audioOutput,
                    v -> open(tvSettings("com.android.tv.action.VIEW_SOUND_SETTINGS", "android.settings.SOUND_SETTINGS")));
            g.add("wifi", "Wi-Fi", this::wifiName, v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
            g.add("bluetooth", "Bluetooth", null,
                    v -> open(tvSettings("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES", Settings.ACTION_BLUETOOTH_SETTINGS)));
            g.add("timer", "Sleep Timer", this::timerState, v -> cycleSleepTimer());
            g.add("sparkles", "Screen Saver", null, v -> open(screenSaver()));
            g.add("shelf", "Home Screen", null, v -> open(new Intent(this, SettingsActivity.class)));

            // Pinned to the bottom, under a hairline: three evenly spaced round buttons.
            body.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
            View rule = new View(this);
            rule.setBackgroundColor(0x1FFFFFFF);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
            rl.bottomMargin = dp(11);
            body.addView(rule, rl);
            LinearLayout bottom = new LinearLayout(this);
            bottom.setClipChildren(false);
            body.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
            action(bottom, "power", "Turn Off", v -> {
                if (SleepReceiver.sleepNow(this)) finish();
                else Perms.requestAdmin(this);
            });
            action(bottom, "house", "Home", v -> {
                open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
                finish();
            });
            action(bottom, "gear", "Settings", v -> open(new Intent(Settings.ACTION_SETTINGS)));
            if (g.first != null) g.first.requestFocus();
        });
    }

    void showInputsFirst() {
        onInputs = true;
        page(0, "Inputs", false, this::inputTiles);
    }

    /** HDMI ports, AV and Live TV as tiles in the same panel. Back returns. */
    void showInputs() {
        onInputs = true;
        page(1, "Inputs", false, this::inputTiles);
    }

    void inputTiles() {
        {
            Tiles g = new Tiles(dp(TILE_W), dp(TILE_H), dp(GAP));
            for (Inputs.Input in : Inputs.list(this)) {
                g.add(Inputs.glyph(in), in.label, null, v -> {
                    if (Inputs.open(this, in)) finish();
                    else Toast.makeText(this, in.label + " isn\u2019t available.", Toast.LENGTH_SHORT).show();
                });
            }
            if (g.first != null) g.first.requestFocus();
        }
    }

    /** A round glass button centred in one third of the row, so the three sit evenly spaced. */
    void action(LinearLayout row, String glyph, String name, View.OnClickListener click) {
        FrameLayout cell = new FrameLayout(this);
        cell.setClipChildren(false);
        LinearLayout holder = new LinearLayout(this);
        holder.setClipChildren(false);
        View b = circle(holder, dp(46), glyph, click);
        b.setContentDescription(name);
        cell.addView(holder, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        row.addView(cell, new LinearLayout.LayoutParams(0, dp(52), 1f));
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

        void add(String glyph, String title, Status status, View.OnClickListener click) {
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
            if (first == null) first = t;
            n++;
        }
    }


    void space(LinearLayout r, int w) {
        r.addView(new View(this), new LinearLayout.LayoutParams(w, 1));
    }

    void open(Intent i) {
        Suggestions.start(this, i);
        finish();
    }

    /** The aerials straight away (tvOS Aerials), or Android's own "start screen saver" when it isn't installed. */
    Intent screenSaver() {
        Intent aerials = new Intent().setClassName("app.glasshome.aerials", "app.glasshome.aerials.AerialActivity");
        if (aerials.resolveActivity(getPackageManager()) != null) return aerials;
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_DESK_DOCK)
                .setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
    }

    /** The TV's own screen for an action, or a general one when this TV has no such screen. */
    Intent tvSettings(String action, String fallback) {
        Intent i = new Intent(action);
        return i.resolveActivity(getPackageManager()) != null ? i : new Intent(fallback);
    }

    // ---------------------------------------------------------------- glass

    /** The panel: dark translucent glass, lighter at the top, with a thin light rim. */
    /**
     * The panel's glass. Over Home it is the dock's own Liquid Glass, drawn from the same blurred
     * picture, so the two read as one material. Over an app, where there's no picture to borrow,
     * a grey frost of about the same weight.
     */
    Drawable glassSheet() {
        float r = dp(28);
        MainActivity home = MainActivity.live.get();
        if (home != null && home.glassBg != null && home.getWindow().getDecorView().getVisibility() == View.VISIBLE) {
            // The same glass as Home's weather, clock and gear, a bigger piece of it.
            MainActivity.LiquidGlass g = home.controlGlass(r);
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

    /** A tile's glass with a white plate over it that fades in on focus. */
    Glass glass(float radius) {
        return new Glass(radius, dp(0.6f), Glass.DARK);
    }

    interface Status {
        String get();
    }

    /** Apple TV Control Centre tile: glyph top left, title and status bottom left. */
    View tile(LinearLayout parent, int w, int h, String glyph, String title, Status status, View.OnClickListener click) {
        Glass white = glass(dp(20));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(white);
        t.setPadding(dp(12), dp(8), dp(10), dp(5));
        t.setFocusable(true);
        t.setClickable(true);

        int tint = glyph.equals("wifi") ? 0xFF0A84FF : Color.WHITE;
        ImageView gi = new ImageView(this);
        gi.setImageBitmap(Glyphs.get(glyph, dp(16), tint));
        t.addView(gi, new LinearLayout.LayoutParams(dp(16), dp(16)));
        t.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        TextView tt = label(12f, "semibold", title);
        t.addView(tt);
        TextView sub = status == null ? null : label(10.5f, "regular", status.get());
        if (sub != null) {
            sub.setTextColor(0xA6FFFFFF);
            t.addView(sub);
        }
        // Text and glyph change colour with the plate as it fades, frame by frame, so nothing flips
        // ahead of it (white text on a still-white plate, a dark glyph on dark glass).
        android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        white.tint = f -> {
            tt.setTextColor((int) ev.evaluate(f, Color.WHITE, 0xFF1C1C1E));
            if (sub != null) sub.setTextColor((int) ev.evaluate(f, 0xA6FFFFFF, 0x991C1C1E));
            if (tint == Color.WHITE) gi.setColorFilter((int) ev.evaluate(f, Color.WHITE, 0xFF1C1C1E));
        };
        t.setOnFocusChangeListener((v, has) -> {
            Glass.focus(white, has);
            lift(v, has);
        });
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
        t.setImageBitmap(Glyphs.get(glyph, dp(22), Color.WHITE));
        t.setFocusable(true);
        t.setClickable(true);
        android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        white.tint = f -> t.setColorFilter((int) ev.evaluate(f, Color.WHITE, 0xFF1C1C1E));
        t.setOnFocusChangeListener((v, has) -> {
            Glass.focus(white, has);
            lift(v, has);
        });
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

    void lift(View v, boolean has) {
        Glass.lift(v, has, 1.05f, 0);
    }

    // ---------------------------------------------------------------- states

    String wifiName() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) return "Not Connected";
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (!nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "Connected";
            android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
            String ssid = wm.getConnectionInfo().getSSID();
            if (ssid == null || ssid.contains("unknown ssid")) return "Connected";
            return ssid.replace("\"", "");
        } catch (Exception e) {
            return "Connected";
        }
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
        int left = SleepReceiver.timerLeft(this);
        return left > 0 ? left + " min" : "Off";
    }

    /** Off, 15, 30, 60, 90, Off. */
    void cycleSleepTimer() {
        if (!SleepReceiver.canSleep(this)) {
            Perms.requestAdmin(this);
            return;
        }
        int left = SleepReceiver.timerLeft(this);
        int[] steps = {15, 30, 60, 90};
        int next = steps[0];
        if (left > 0) {
            next = 0;
            for (int st : steps) if (st > left + 1) { next = st; break; }
        }
        SleepReceiver.setTimer(this, next);
    }
}

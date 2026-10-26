package app.glasshome.launcher;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Permissions, asked for on screen wherever the TV allows it; adb is only the last resort.
 * - Location (Wi-Fi name): the normal runtime prompt.
 * - Device admin (Turn Off, Sleep Timer): Android's "Activate device admin app?" screen.
 * Some TVs (e.g. Android 9 builds) have no screen for device admin; then Glass Home's
 * accessibility switch does the same job.
 * Anything to explain appears in a tvOS alert on the page itself (alert()), which Settings and
 * Setup use for their own questions too.
 */
final class Perms {
    static final int REQ_LOCATION = 41;

    private Perms() {
    }

    static boolean hasLocation(Activity a) {
        return a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Turn Off is possible via device admin, or via Glass Home's accessibility service (any TV with that screen). */
    static boolean adminSupported(Activity a) {
        return adminWorks(a) || accessibilityScreen(a) != null;
    }

    static Intent accessibilityScreen(Activity a) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        ComponentName cn = i.resolveActivity(a.getPackageManager());
        return cn == null || cn.getClassName().contains("Stub") ? null : i;
    }

    /** Device admin actually works only when Android's device-policy service is on. */
    static boolean adminWorks(Activity a) {
        return a.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN);
    }

    /** Fine and coarse together: asked for alone, fine gets no prompt at all on Android 12+. */
    static void requestLocation(Activity a) {
        if (!hasLocation(a)) a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    static void requestAdmin(Activity a) {
        if (SleepReceiver.canSleep(a)) return;
        if (!adminSupported(a)) {
            alert(a, "Not Available", "This TV doesn’t allow it. Use the power button on your remote.");
            return;
        }
        if (!adminWorks(a)) {
            // Device admin is switched off on this TV: use Glass Home's accessibility switch instead.
            Intent acc = accessibilityScreen(a);
            alert(a, "Turn On Sleep Timer", "Choose “" + a.getString(R.string.label_power) + "” and turn it on.",
                    new String[]{"Open Accessibility", "Cancel"}, -1, w -> {
                        if (w == 0) tryStart(a, acc);
                    });
            return;
        }
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, SleepReceiver.admin(a))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Lets " + a.getString(R.string.app_name) + " turn off the TV. Nothing else.");
        if (tryStart(a, i)) return;
        lastResort(a, "Sleep Timer",
                "adb shell dpm set-active-admin app.glasshome.launcher/.SleepReceiver\\$Admin");
    }

    /** Starts a system screen if the TV really has it (many TVs map settings actions to empty stubs). */
    private static boolean tryStart(Activity a, Intent i) {
        ComponentName cn = i.resolveActivity(a.getPackageManager());
        if (cn == null || cn.getClassName().contains("Stub")) return false;
        try {
            a.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- default home screen

    static boolean isDefaultHome(Activity a) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo ri = a.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && a.getPackageName().equals(ri.activityInfo.packageName);
    }

    /**
     * Become the home screen: Android 10+'s "Set as default home app?" prompt, else the TV's Home
     * app settings (when it is a real screen), else Android's chooser, and only then the command.
     */
    static void requestDefaultHome(Activity a) {
        if (isDefaultHome(a)) return;
        if (Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = a.getSystemService(android.app.role.RoleManager.class);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                try {
                    a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 42);
                    return;
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        if (tryStart(a, new Intent(Settings.ACTION_HOME_SETTINGS))) return;
        // With no default chosen yet, asking for Home shows Android's chooser ("Always" makes it stick).
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo def = a.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        boolean noDefault = def == null || def.activityInfo == null || "android".equals(def.activityInfo.packageName);
        if (noDefault && tryStart(a, home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        lastResort(a, "Home Screen",
                "adb shell cmd package set-home-activity app.glasshome.launcher/.MainActivity\n"
                        + "(on Google TV also: adb shell pm disable-user --user 0 com.google.android.tvlauncher)");
    }

    /** Only when the TV has no screen for it: explain plainly, with the command for whoever installs the TV. */
    private static void lastResort(Activity a, String what, String cmd) {
        alert(a, what, "This TV’s settings have no switch for this permission. Whoever set up the TV can turn it on "
                        + "once from a computer on the same network:\n\n" + cmd,
                new String[]{"OK", "App Info"}, -1, w -> {
                    if (w == 1) tryStart(a, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + a.getPackageName())));
                });
    }

    // ---------------------------------------------------------------- alerts

    /** What was chosen in an alert: the button's index, or -1 for Back. */
    interface Answer {
        void chose(int which);
    }

    /** An alert with just an OK. */
    static void alert(Activity a, String title, String message) {
        alert(a, title, message, new String[]{"OK"}, -1, null);
    }

    /**
     * A tvOS 26 alert over the current page, in place of Android's dialog: a glass card in the
     * middle of a dimmed screen, the title and message ranged left, then the buttons as capsules.
     * buttons[0] is the main one and has focus: with two, it sits on the right; with more, they
     * stack in order (Cancel last). `red` marks a destructive button (-1 for none). Back closes it
     * as Cancel would. It opens as the Apple TV app's do: the card grows from half size as it
     * fades in (250 ms), over a 30 % dim that fades in (400 ms); it closes the same way in 100 ms.
     */
    static void alert(Activity a, String title, String message, String[] buttons, int red, Answer answer) {
        if (a.isFinishing() || a.isDestroyed()) return;
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content != null) new Alert(a, content, title, message, buttons, red, answer);
    }

    static final class Alert extends FrameLayout {
        static final ArgbEvaluator EV = new ArgbEvaluator();
        static final LinearInterpolator LINEAR = new LinearInterpolator();
        static final AccelerateInterpolator IN = new AccelerateInterpolator(1f);
        static final DecelerateInterpolator OUT = new DecelerateInterpolator(1f);

        final ViewGroup content;
        final Answer answer;
        final View before, dim;
        final LinearLayout card;
        /** The page's own views can't take focus while the alert is up; what they allowed before. */
        final int[] blocked;
        /** Opened (focus changes animate from here on), closing (presses no longer count). */
        boolean open, closing;

        Alert(Activity a, ViewGroup content, String title, String message, String[] buttons, int red, Answer answer) {
            super(a);
            this.content = content;
            this.answer = answer;
            before = a.getCurrentFocus();
            blocked = new int[content.getChildCount()];
            for (int i = 0; i < blocked.length; i++) {
                View v = content.getChildAt(i);
                if (!(v instanceof ViewGroup)) continue;
                blocked[i] = ((ViewGroup) v).getDescendantFocusability();
                ((ViewGroup) v).setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            }

            dim = new View(a);
            dim.setBackgroundColor(0x4D000000);
            addView(dim, new LayoutParams(-1, -1));

            card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(u(14), u(22), u(14), u(14));
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(0xF21C1C1F);
            fill.setCornerRadius(u(24));
            card.setBackground(new LayerDrawable(new Drawable[]{fill, new Glass(u(24), u(0.5f), Glass.DARK)}));
            card.setElevation(u(16));
            TextView t = text(a, title, 17, "semibold", Color.WHITE);
            card.addView(t, words());
            if (message != null && !message.isEmpty()) {
                TextView m = text(a, message, 13.5f, "regular", 0x8CFFFFFF);
                m.setLineSpacing(0, 1.12f);
                LinearLayout.LayoutParams ml = words();
                ml.topMargin = u(6);
                card.addView(m, ml);
            }

            // Two side by side, the main one on the right; otherwise a stack, the main one on top.
            boolean pair = buttons.length == 2;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(pair ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            View first = null;
            for (int k = 0; k < buttons.length; k++) {
                int i = pair ? 1 - k : k;
                View b = button(a, buttons[i], i == red, i);
                if (i == 0) first = b;
                LinearLayout.LayoutParams bl = pair ? new LinearLayout.LayoutParams(0, u(37), 1f) : new LinearLayout.LayoutParams(-1, u(37));
                if (k > 0) {
                    if (pair) bl.leftMargin = u(8);
                    else bl.topMargin = u(4);
                }
                row.addView(b, bl);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = u(18);
            card.addView(row, rl);
            addView(card, new LayoutParams(u(288), -2, Gravity.CENTER));
            content.addView(this, new ViewGroup.LayoutParams(-1, -1));
            if (first != null) first.requestFocus();
            open = true;

            dim.setAlpha(0f);
            dim.animate().alpha(1f).setDuration(400).setInterpolator(LINEAR).start();
            card.setAlpha(0f);
            card.setScaleX(0.5f);
            card.setScaleY(0.5f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).setInterpolator(LINEAR).withLayer().start();
        }

        int u(float units) {
            return Ui.px(getContext(), units);
        }

        /** Title and message sit in from the card's edge further than the buttons do. */
        LinearLayout.LayoutParams words() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.leftMargin = lp.rightMargin = u(10);
            return lp;
        }

        static TextView text(Activity a, String s, float size, String weight, int color) {
            TextView t = new TextView(a);
            t.setText(s);
            Ui.textSize(t, size);
            t.setTypeface(Ui.font(a, weight));
            t.setTextColor(color);
            return t;
        }

        /** A capsule button: its plate grows and turns white on focus, the words staying sharp. */
        View button(Activity a, String label, boolean destructive, int which) {
            int rest = destructive ? 0xFFFF453A : Color.WHITE, lit = destructive ? 0xFFFF3B30 : 0xFF111114;
            TextView b = text(a, label, 15.5f, "semibold", rest);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            Glass g = new Glass(u(40), u(0.5f), Glass.DARK);
            g.grow = u(2);
            g.tint = f -> b.setTextColor((int) EV.evaluate(f, rest, lit));
            b.setBackground(g);
            b.setFocusable(true);
            b.setClickable(true);
            b.setOnFocusChangeListener((v, has) -> {
                // The first focus arrives with the card itself, already lit.
                if (!open) g.setFocus(has ? 1f : 0f);
                else Glass.focus(g, has, 100, 200, IN, OUT);  // alert buttons: the Apple TV app's 100 / 200 ms
            });
            b.setOnClickListener(v -> close(which));
            return b;
        }

        void close(int which) {
            if (closing) return;
            closing = true;
            dim.animate().alpha(0f).setDuration(100).setInterpolator(LINEAR).start();
            // The way it came, reversed: back towards half size as it fades.
            card.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(100).setInterpolator(LINEAR).withLayer().withEndAction(() -> {
                content.removeView(this);
                for (int i = 0; i < blocked.length && i < content.getChildCount(); i++) {
                    View v = content.getChildAt(i);
                    if (v instanceof ViewGroup) ((ViewGroup) v).setDescendantFocusability(blocked[i]);
                }
                // Back to what had focus; if the page was re-drawn underneath, to the top-most
                // thing left (the page, or an alert still beneath this one), on its first row.
                boolean back = before != null && before.isAttachedToWindow() && before.requestFocus();
                if (!back && content.getChildCount() > 0) content.getChildAt(content.getChildCount() - 1).requestFocus();
                if (answer != null) answer.chose(which);
            }).start();
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) close(-1);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }
}

package app.glasshome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remote buttons that only the stock Google TV home answered (its GlobalKeyListener receiver), so
 * they do nothing once it is disabled: the app buttons open their apps again, and the Quick
 * Settings button opens Control Centre. Every other key passes straight through untouched.
 * Turned on over ADB (enabled_accessibility_services); it reads nothing on screen.
 *
 * Trade-off on this TCL (G08, Android 14): while any key-filtering service is bound, the firmware
 * handles volume before the service sees it and drops the held-key repeat, so holding volume
 * moves one step per press. The volume keys never reach this service, so it cannot ramp them.
 */
public class KeysService extends AccessibilityService {
    static final String TAG = "GlassHomeKeys";
    static final String CONTROL_CENTRE = "control_centre";

    /** Keycode -> package to open, or CONTROL_CENTRE. Read with `logcat -s tvOSKeys` on the real remote. */
    static final SparseArray<String> KEYS = new SparseArray<>();

    static {
        KEYS.put(KeyEvent.KEYCODE_BUTTON_3, "com.google.android.youtube.tv");
        KEYS.put(4062, "com.netflix.ninja");                    // TCL vendor keycode, 55C6K-UK remote
        KEYS.put(4099, "com.amazon.amazonvideo.livingroom");    // TCL vendor keycode, 55C6K-UK remote
        KEYS.put(KeyEvent.KEYCODE_FEATURED_APP_1, "com.netflix.ninja");
        KEYS.put(KeyEvent.KEYCODE_MACRO_1, "com.amazon.amazonvideo.livingroom");
        KEYS.put(KeyEvent.KEYCODE_NOTIFICATION, CONTROL_CENTRE);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && !isCommon(k)) {
            Log.i(TAG, "key " + KeyEvent.keyCodeToString(k) + " (" + k + ") scan=" + e.getScanCode());
        }
        if (k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST) {
            // Hold to talk: Search opens listening on the press and searches what you said on
            // release, like the Siri button.
            Log.i(TAG, "mic " + (e.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up") + " repeat=" + e.getRepeatCount()
                    + " held=" + (e.getEventTime() - e.getDownTime()) + "ms");
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && SearchActivity.stopIfListening()) {
                return true;  // a second press while listening stops it
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                SearchActivity.pressedAt = android.os.SystemClock.uptimeMillis();
                startActivity(new Intent(this, SearchActivity.class).setAction(Intent.ACTION_VOICE_COMMAND)
                        .putExtra("hold", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } else if (e.getAction() == KeyEvent.ACTION_UP && e.getEventTime() - e.getDownTime() > 450) {
                // A real hold being let go. Some remotes report "up" straight after "down" however
                // long the button is held; that's treated as a tap, and listening ends when you stop talking.
                SearchActivity.released();
            }
            return true;
        }
        String target = KEYS.get(k);
        if (target == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) open(target);
        return true;
    }

    /** D-pad, volume, media and the like: not worth a log line on every press. */
    static boolean isCommon(int k) {
        return (k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_CENTER)
                || k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN
                || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_ENTER;
    }

    void open(String target) {
        if (CONTROL_CENTRE.equals(target)) {
            // Over whatever is on screen, Home included; a second press closes it.
            startActivity(new Intent(this, ControlCentreActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return;
        }
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(target);
        if (i == null) i = pm.getLaunchIntentForPackage(target);
        if (i != null) Suggestions.start(this, i);
    }

    /**
     * A force stop (a cleaner app, Settings > Apps) switches accessibility helpers off for good.
     * Home calls this when it opens: if the helper was on before and isn't now, it goes back on.
     * Needs WRITE_SECURE_SETTINGS (build-and-install.sh grants it); without it, nothing happens.
     */
    static void ensureOn(android.content.Context c) {
        String me = c.getPackageName() + "/" + KeysService.class.getName();
        android.content.ContentResolver cr = c.getContentResolver();
        String on = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services");
        android.content.SharedPreferences p = c.getSharedPreferences("keys", android.content.Context.MODE_PRIVATE);
        if (on != null && on.contains(me)) {
            p.edit().putBoolean("wanted", true).apply();
            return;
        }
        if (!p.getBoolean("wanted", false)) return;  // never switched on here: leave the choice alone
        try {
            String list = on == null || on.isEmpty() ? me : on + ":" + me;
            android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", list);
            android.provider.Settings.Secure.putString(cr, "accessibility_enabled", "1");
        } catch (SecurityException ignored) {
            // permission not granted on this TV
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
    }

    @Override
    public void onInterrupt() {
    }
}

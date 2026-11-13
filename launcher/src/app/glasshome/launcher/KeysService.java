package app.glasshome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Remote buttons that only the stock Google TV home answered (its GlobalKeyListener receiver), so
 * they do nothing once it is disabled: the app buttons open their apps again, and the Quick
 * Settings button opens Control Centre. The mic button opens Search, listening while it's held,
 * and Home pressed twice opens the App Switcher. Every other key passes straight through untouched.
 * Turned on over ADB (enabled_accessibility_services). It reads the screen only for a few seconds
 * after the Guide opens a BBC channel live, and only iPlayer's (pickIPlayerProfile).
 *
 * Trade-off on this TCL (G08, Android 14): while any key-filtering service is bound, the firmware
 * handles volume before the service sees it and drops the held-key repeat, so holding volume
 * moves one step per press. The volume keys never reach this service, so it cannot ramp them.
 */
public class KeysService extends AccessibilityService {
    static final String CONTROL_CENTRE = "control_centre";

    /** Keycode -> package to open, or CONTROL_CENTRE. */
    static final SparseArray<String> KEYS = new SparseArray<>();

    static {
        KEYS.put(KeyEvent.KEYCODE_BUTTON_3, "com.google.android.youtube.tv");
        KEYS.put(4062, "com.netflix.ninja");                    // TCL vendor keycode, 55C6K-UK remote
        KEYS.put(4099, "com.amazon.amazonvideo.livingroom");    // TCL vendor keycode, 55C6K-UK remote
        KEYS.put(KeyEvent.KEYCODE_FEATURED_APP_1, "com.netflix.ninja");
        KEYS.put(KeyEvent.KEYCODE_MACRO_1, "com.amazon.amazonvideo.livingroom");
        KEYS.put(KeyEvent.KEYCODE_NOTIFICATION, CONTROL_CENTRE);
    }

    /** Running (bound by the system), so remote keys reach it: a trailer can count on Back (TrailerWatch). */
    static volatile boolean bound;
    /** The service while it runs, for the Guide (the same process and thread). */
    static KeysService running;

    @Override
    protected void onServiceConnected() {
        bound = true;
        running = this;
    }

    @Override
    public boolean onUnbind(Intent i) {
        bound = false;
        running = null;
        stopWatching();
        return super.onUnbind(i);
    }

    /** Back went down during a trailer, so its release is the trailer's too. */
    boolean backTaken;

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        // A button pressed while iPlayer asks who's watching: the choice is yours.
        if (watching && e.getAction() == KeyEvent.ACTION_DOWN) stopWatching();
        if (k == KeyEvent.KEYCODE_BACK) {
            // A trailer playing in YouTube from a title page: Back is back to the page, as from
            // Apple's player, rather than into YouTube's own pages.
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) backTaken = TrailerWatch.playing();
            if (!backTaken) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) {
                backTaken = false;
                TrailerWatch.back();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST) {
            // Search opens listening on the press. A remote that reports the button being held
            // searches what was said when it's let go, like the Siri button. This TV's remote
            // (TCL RC833) doesn't: it sends its press and release together the moment it's
            // pressed, and nothing when let go or pressed again mid-sentence, because Google's
            // remote service tells only Google Assistant about holds. There, listening ends at
            // the first pause after you speak (SearchActivity).
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && SearchActivity.stopIfListening()) {
                return true;  // a second press while listening stops it
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                SearchActivity.pressedAt = android.os.SystemClock.uptimeMillis();
                startActivity(new Intent(this, SearchActivity.class).setAction(Intent.ACTION_VOICE_COMMAND)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } else if (e.getAction() == KeyEvent.ACTION_UP && e.getEventTime() - e.getDownTime() > 450) {
                // A real hold being let go. Some remotes report "up" straight after "down" however
                // long the button is held; that's treated as a tap, and listening ends when you stop talking.
                SearchActivity.released();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_HOME) return home(e);
        String target = KEYS.get(k);
        if (target == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) open(target);
        return true;
    }

    /** How soon a second Home press has to follow the first to count as pressing it twice. */
    static final long TWICE_MS = 400;
    /** When Home was last let go. */
    long homeUp;
    /** The second press of a double press: kept from the system, along with its release. */
    boolean homeTwice;

    /**
     * Home pressed twice quickly opens the App Switcher, as on Apple TV. The first press is Home as
     * always, never held back waiting for a second, so Home stays instant; only the second is taken.
     */
    boolean home(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0 && e.getDownTime() - homeUp < TWICE_MS) {
                homeTwice = true;
                startActivity(new Intent(this, MainActivity.class).putExtra("panel", "switcher")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return homeTwice;
        }
        if (homeTwice) {
            homeTwice = false;
            homeUp = 0;  // a third press is Home again, which closes it
            return true;
        }
        homeUp = e.getEventTime();
        return false;
    }

    void open(String target) {
        if (CONTROL_CENTRE.equals(target)) {
            // Over whatever is on screen, Home included; a second press closes it.
            startActivity(new Intent(this, ControlCentreActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return;
        }
        TrailerWatch.stop();  // another app, chosen: a trailer playing now no longer comes back
        Intent i = Suggestions.launchIntent(this, target);
        if (i != null) Suggestions.start(this, i);
    }

    /**
     * A force stop (a cleaner app, Settings > Apps) switches accessibility helpers off for good.
     * Home calls this when it opens: if the helper was on before and isn't now, it goes back on.
     * Needs WRITE_SECURE_SETTINGS (install.sh grants it); without it, nothing happens.
     */
    static void ensureOn(android.content.Context c) {
        String me = c.getPackageName() + "/" + KeysService.class.getName();
        android.content.ContentResolver cr = c.getContentResolver();
        String on = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services");
        android.content.SharedPreferences p = c.getSharedPreferences("keys", android.content.Context.MODE_PRIVATE);
        if (on != null && on.contains(me)) {
            if (!p.getBoolean("wanted", false)) p.edit().putBoolean("wanted", true).apply();  // once, not on every return to Home
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

    // ---------------------------------------------------------------- iPlayer's profiles

    static final String IPLAYER = "bbc.iplayer.android";
    /** How long after the Guide opens iPlayer it may still ask who's watching (a cold start takes a few seconds). */
    static final long WATCH_MS = 20_000;
    /** How often iPlayer's screen is looked at meanwhile: its web page sends no events until it's read. */
    static final long LOOK_MS = 400;

    final Handler ui = new Handler(Looper.getMainLooper());
    final Runnable watchOver = this::stopWatching;
    boolean watching;

    /**
     * The Guide is opening a BBC channel live, and iPlayer starts at "Select who's watching": the
     * profile it has picked out (the last one used) is chosen for you, as OK would. Only then is
     * this service shown what's on screen, and only until it's done, a button is pressed, or
     * WATCH_MS. Watch live or from start, which comes next, is left to you.
     */
    static void pickIPlayerProfile() {
        KeysService s = running;
        if (s != null) s.watch();
    }

    void watch() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        watching = true;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;  // the page's element ids
        setServiceInfo(info);
        ui.removeCallbacks(watchOver);
        ui.removeCallbacks(look);
        ui.postDelayed(watchOver, WATCH_MS);
        ui.postDelayed(look, LOOK_MS);
    }

    void stopWatching() {
        ui.removeCallbacks(watchOver);
        ui.removeCallbacks(look);
        if (!watching) return;
        watching = false;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        info.flags &= ~AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
    }

    final Runnable look = new Runnable() {
        @Override public void run() {
            if (!watching) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo profile = root == null || !IPLAYER.contentEquals(String.valueOf(root.getPackageName()))
                    ? null : focusedProfile(root);
            if (profile != null && profile.performAction(AccessibilityNodeInfo.ACTION_CLICK)) stopWatching();
            else ui.postDelayed(this, LOOK_MS);
        }
    };

    /**
     * The profile in focus on "Select who's watching": a button with the id avatar-<profile> (its
     * name's is avatar-label-, adding one is avatar-add-). Null on any other screen.
     */
    static AccessibilityNodeInfo focusedProfile(AccessibilityNodeInfo root) {
        java.util.ArrayDeque<AccessibilityNodeInfo> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        for (int seen = 0; !todo.isEmpty() && seen < 600; seen++) {
            AccessibilityNodeInfo n = todo.poll();
            String id = n.getViewIdResourceName();
            if (id != null && id.startsWith("avatar-") && !id.startsWith("avatar-label-") && !id.startsWith("avatar-add-")
                    && n.isFocused()) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) todo.add(c);
            }
        }
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
    }

    @Override
    public void onInterrupt() {
    }
}

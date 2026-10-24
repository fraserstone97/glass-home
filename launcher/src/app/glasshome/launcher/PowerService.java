package app.glasshome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Glass Home's accessibility switch, turned on by the user in the TV's Accessibility settings. It
 * does one thing, only when asked from Glass Home: put the TV to sleep (Power Off, Sleep Timer) on TVs where
 * device admin is switched off. It reads nothing on screen.
 */
public class PowerService extends AccessibilityService {
    static PowerService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /** Screen off / sleep. Returns false when the service isn't on. */
    static boolean sleep() {
        PowerService s = instance;
        return s != null && android.os.Build.VERSION.SDK_INT >= 28 && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }
}

package app.glasshome.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

/**
 * Keeps the TV's built-in Chromecast (Google's mediashell) able to answer phones. With Google TV's
 * own home switched off, nothing else stays connected to it. Android then counts it as an idle
 * background app and freezes it. Phones' searches for something to cast to pile up unanswered,
 * and the TV drops out of every cast list, screen mirroring included. Home stays connected to its
 * receiver service for as long as Home runs. That lends the service Home's standing: it is never
 * frozen, and it's started again if Android ever closes it for memory.
 */
final class CastAwake {
    static final ComponentName RECEIVER = new ComponentName("com.google.android.apps.mediashell",
            "com.google.android.apps.mediashell.CastReceiverService");

    private static Context app;
    private static boolean held;

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
        }

        @Override public void onServiceDisconnected(ComponentName n) {
            // Its process ended. The connection stays, and Android starts it again.
        }

        @Override public void onNullBinding(ComponentName n) {
            // It hands out nothing to Home, which is fine: being connected is all that's needed.
        }

        @Override public void onBindingDied(ComponentName n) {
            // Chromecast was updated or switched off and on: connect afresh.
            release();
            hold(app);
        }
    };

    /** Connects once per process. Calling it again does nothing. */
    static void hold(Context c) {
        if (held || c == null) return;
        app = c.getApplicationContext();
        try {
            // NOT_FOREGROUND: kept out of the freezer and away from memory clean-ups, without
            // competing with what's on screen for the processor.
            held = app.bindService(new Intent().setComponent(RECEIVER), CONNECTION,
                    Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND);
        } catch (SecurityException e) {
            held = false;
        }
        if (!held) release();  // no Chromecast on this TV: nothing to keep
    }

    private static void release() {
        held = false;
        try {
            app.unbindService(CONNECTION);
        } catch (IllegalArgumentException ignored) {
            // wasn't connected
        }
    }
}

package app.glasshome.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Sleep support for Control Centre: Turn Off and the Sleep Timer put the TV to sleep through
 * DevicePolicyManager.lockNow(), which needs this app to be a (force-lock only) device admin:
 *   adb shell dpm set-active-admin app.glasshome.launcher/.SleepReceiver$Admin
 */
public class SleepReceiver extends BroadcastReceiver {

    /** Device-admin component (force-lock only). */
    public static class Admin extends DeviceAdminReceiver { }

    static ComponentName admin(Context c) {
        return new ComponentName(c, Admin.class);
    }

    static boolean canSleep(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        return (dpm != null && dpm.isAdminActive(admin(c))) || PowerService.instance != null;
    }

    /** Device admin where available; otherwise Glass Home's accessibility service (screen off / sleep). */
    static boolean sleepNow(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        if (dpm != null && dpm.isAdminActive(admin(c))) {
            dpm.lockNow();
            return true;
        }
        return PowerService.sleep();
    }

    static PendingIntent pending(Context c, int flags) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, SleepReceiver.class), PendingIntent.FLAG_IMMUTABLE | flags);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("controls", Context.MODE_PRIVATE);
    }

    /** Schedules sleep in `minutes` (0 cancels). Survives the launcher being closed. */
    static void setTimer(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = pending(c, PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        long at = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        prefs(c).edit().putLong("sleep_at", at).putInt("sleep_for", minutes).apply();
        if (minutes <= 0) {
            pi.cancel();
            return;
        }
        // Exact, so it goes off on the second, as tvOS's counts down: an ordinary alarm may come
        // up to three-quarters of the wait late. USE_EXACT_ALARM allows it; if not, within a minute.
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi);
        }
    }

    /**
     * Milliseconds left on the sleep timer, or 0 when off. A restart (or a force stop) drops the
     * alarm but not the time it was due, so with no alarm still waiting there's no timer either, and
     * the stale time is cleared.
     */
    static long timerLeftMs(Context c) {
        long at = prefs(c).getLong("sleep_at", 0);
        if (at == 0) return 0;
        long left = at - System.currentTimeMillis();
        if (left > 0 && pending(c, PendingIntent.FLAG_NO_CREATE) != null) return left;
        prefs(c).edit().putLong("sleep_at", 0).apply();
        return 0;
    }

    /** Minutes left on the sleep timer, or 0 when off. */
    static int timerLeft(Context c) {
        return (int) Math.ceil(timerLeftMs(c) / 60_000.0);
    }

    /** How long the running timer was set for (minutes), or 0 when off. */
    static int timerChoice(Context c) {
        return timerLeftMs(c) > 0 ? prefs(c).getInt("sleep_for", 0) : 0;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        prefs(c).edit().putLong("sleep_at", 0).apply();
        sleepNow(c);
    }
}

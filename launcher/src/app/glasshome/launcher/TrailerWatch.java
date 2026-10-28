package app.glasshome.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * A trailer playing in YouTube's own app, followed so that it comes back to its title page as from
 * Apple's player: when it ends, and on Back (KeysService hands Back here while it plays).
 *
 * Why the app itself and not its bare player (the finish_on_ended extra, which closes by itself):
 * the bare player starts each time from a copy of the app's saved state, made in a folder of its
 * own and thrown away afterwards. Once the app has been used since it last started, that copy comes
 * up signed out, so adverts play before the trailer, and its CC button changes only the copy, so
 * captions can't be turned off there. The app proper is signed in to your account (no adverts with
 * Premium, Lite included) and its CC button is the one that sticks. It can't be told to close when
 * the video ends (it drops finish_on_ended from the link), so its media session is watched instead:
 * Android shares sessions with Glass Home's notification listener (MediaListener).
 */
final class TrailerWatch {
    private TrailerWatch() {
    }

    static final String TAG = "GlassTrailer";

    /** Long enough for YouTube to start from cold (about 4 s on this TV) on a slow network. */
    static final long START_MS = 30_000;
    /** A next video starting within this of the trailer's end is YouTube going on to it, not you. */
    static final long END_MS = 8_000;
    /** Until it has played this long, a new title is the trailer's own arriving (see next). */
    static final long SETTLE_MS = 4_000;

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static boolean on;
    private static Application application;
    private static MediaSessionManager sessions;
    private static ComponentName listener;
    /** The title page's task: brought back to the front, the page and everything under it as left. */
    private static int task;
    /** When the trailer was asked for, so a session YouTube already had counts only once it plays it. */
    private static long askedAt;
    /** YouTube's session, once it's playing the trailer, and what it calls it. */
    private static MediaController yt;
    private static String title;
    private static long duration;
    private static PlaybackState last;
    /**
     * How far it has got: each state YouTube gives carried on to when the next came. The next
     * video's first state can come before its title, so the latest state alone can't say.
     */
    private static long reached;

    /** Whether trailers can be followed: notification access for the session, KeysService for Back. */
    static boolean possible(Context c) {
        if (!KeysService.bound) return false;
        try {
            c.getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(c, MediaListener.class));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Follows the trailer just asked of YouTube from this title page. */
    static void start(Activity page) {
        stop();
        application = page.getApplication();
        sessions = application.getSystemService(MediaSessionManager.class);
        listener = new ComponentName(application, MediaListener.class);
        task = page.getTaskId();
        askedAt = SystemClock.elapsedRealtime();
        on = true;
        try {
            sessions.addOnActiveSessionsChangedListener(changed, listener, ui);
        } catch (SecurityException e) {
            stop();
            return;
        }
        application.registerActivityLifecycleCallbacks(ours);
        ui.postDelayed(giveUp, START_MS);
        ui.post(waiting);
        Log.i(TAG, "following, task " + task);
    }

    /** Playing (or about to) in front, so Back belongs to it. */
    static boolean playing() {
        if (!on) return false;
        boolean p = youTubeInFront();
        Log.i(TAG, "Back: " + (p ? "the title page" : "not in front"));
        return p;
    }

    /** Back while it plays: paused where it is, and the title page again. */
    static void back() {
        if (yt != null) yt.getTransportControls().pause();
        comeBack();
    }

    static void stop() {
        if (!on) return;
        on = false;
        Log.i(TAG, "stopped");
        ui.removeCallbacks(giveUp);
        ui.removeCallbacks(waiting);
        try {
            sessions.removeOnActiveSessionsChangedListener(changed);
        } catch (RuntimeException ignored) {
            // already gone
        }
        if (yt != null) yt.unregisterCallback(follow);
        yt = null;
        title = null;
        last = null;
        duration = 0;
        reached = 0;
        application.unregisterActivityLifecycleCallbacks(ours);
    }

    private static final Runnable giveUp = () -> {
        if (yt == null) stop();  // it never started: whatever is on screen now isn't the trailer
    };

    /** Until it plays: a session YouTube already had, for a video paused there, changes rather than appears. */
    private static final Runnable waiting = new Runnable() {
        @Override
        public void run() {
            if (!on || yt != null) return;
            look();
            if (on && yt == null) ui.postDelayed(this, 400);
        }
    };

    private static final MediaSessionManager.OnActiveSessionsChangedListener changed = list -> look();

    /** YouTube's session as it is now: the trailer starting, or (once it has) gone. */
    private static void look() {
        if (!on) return;
        MediaController now = null;
        try {
            List<MediaController> all = sessions.getActiveSessions(listener);
            for (MediaController c : all) {
                if (DetailActivity.YOUTUBE.equals(c.getPackageName())) {
                    now = c;
                    break;
                }
            }
        } catch (SecurityException e) {
            stop();
            return;
        }
        if (yt == null) {
            PlaybackState s = now == null ? null : now.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() >= askedAt) {
                yt = now;
                yt.registerCallback(follow, ui);
                Log.i(TAG, "playing");
                ui.removeCallbacks(giveUp);
                check();
            }
        } else if (now == null) {
            if (settling()) rewait();
            else ended("its session went");  // with the trailer: it's over
        } else if (!now.getSessionToken().equals(yt.getSessionToken())) {
            next(now);  // a new session: another video
        }
    }

    /**
     * Another video. In the trailer's first seconds it's the trailer itself: YouTube names it a
     * moment after it starts, under the last video's title until then. Going on to the next at the
     * trailer's end comes back, paused there; one you chose part-way through is yours.
     */
    private static void next(MediaController c) {
        if (settling()) {
            if (c != yt) {
                yt.unregisterCallback(follow);
                yt = c;
                yt.registerCallback(follow, ui);
            }
            title = null;
            duration = 0;
            check();
            return;
        }
        if (duration <= 0 || reached >= duration - END_MS) {
            c.getTransportControls().pause();
            ended("next video");
        } else {
            stop();
        }
    }

    private static final MediaController.Callback follow = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            check();
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            check();
        }

        @Override
        public void onSessionDestroyed() {
            if (settling()) rewait();
            else ended("session closed");
        }
    };

    /**
     * In the trailer's first seconds, when YouTube is still settling into it (a state between
     * videos, a session swapped for another): what looks like an end there isn't one.
     */
    private static boolean settling() {
        reached = Math.max(reached, at(last));
        return reached < SETTLE_MS;
    }

    /** Back to waiting for it to play, as just after it was asked for. */
    private static void rewait() {
        Log.i(TAG, "settling: waiting again");
        yt.unregisterCallback(follow);
        yt = null;
        title = null;
        last = null;
        duration = 0;
        reached = 0;
        ui.removeCallbacks(waiting);
        ui.post(waiting);
        ui.removeCallbacks(giveUp);
        ui.postDelayed(giveUp, START_MS);
    }

    /** Still the trailer: or stopped, or YouTube going on to its next video. */
    private static void check() {
        if (!on || yt == null) return;
        PlaybackState s = yt.getPlaybackState();
        int st = s == null ? PlaybackState.STATE_NONE : s.getState();
        if (st == PlaybackState.STATE_STOPPED || st == PlaybackState.STATE_NONE || st == PlaybackState.STATE_ERROR) {
            if (st == PlaybackState.STATE_ERROR || !settling()) ended("state " + st);
            return;
        }
        MediaMetadata m = yt.getMetadata();
        String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t != null && t.isEmpty()) t = null;  // named a moment after it starts
        if (t != null && title != null && !t.equals(title)) {
            next(yt);
            return;
        }
        reached = Math.max(reached, at(last));
        long d = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (t != null && title == null) Log.i(TAG, "the trailer: " + t + ", " + d + " ms");
        if (t != null) title = t;
        if (d > 0) duration = d;
        last = s;
    }

    /** Where playback has got to by now, from the last state YouTube gave. */
    static long at(PlaybackState s) {
        if (s == null) return 0;
        long p = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            p += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return p;
    }

    /** The trailer is over: the title page again, unless you've gone on to something else meanwhile. */
    private static void ended(String why) {
        Log.i(TAG, "ended (" + why + ") at " + reached + " of " + duration);
        if (youTubeInFront()) comeBack();
        else stop();
    }

    private static void comeBack() {
        if (!on) return;
        Context c = application;
        int t = task;
        stop();
        try {
            for (ActivityManager.AppTask a : c.getSystemService(ActivityManager.class).getAppTasks()) {
                ActivityManager.RecentTaskInfo i = a.getTaskInfo();
                if ((Build.VERSION.SDK_INT >= 29 ? i.taskId : i.id) == t) {
                    a.moveToFront();
                    Log.i(TAG, "back to the title page");
                    return;
                }
            }
        } catch (RuntimeException e) {
            // the page has gone: YouTube stays
        }
    }

    /** YouTube last came to the front, by Android's usage history (Home's App Switcher reads the same). */
    static boolean youTubeInFront() {
        UsageStatsManager um = application.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events = um == null ? null : um.queryEvents(now - 6 * 3600_000L, now);
        if (events == null) return true;
        String front = null;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) front = e.getPackageName();
        }
        return front == null || DetailActivity.YOUTUBE.equals(front);  // no history to go by: as it was asked
    }

    /**
     * Any page of Glass Home's coming up ends it (Home, Search, the title page itself), except
     * Control Centre, which opens over the trailer and goes again.
     */
    private static final Application.ActivityLifecycleCallbacks ours = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityResumed(Activity a) {
            if (!(a instanceof ControlCentreActivity)) stop();
        }

        @Override
        public void onActivityCreated(Activity a, Bundle b) {
        }

        @Override
        public void onActivityStarted(Activity a) {
        }

        @Override
        public void onActivityPaused(Activity a) {
        }

        @Override
        public void onActivityStopped(Activity a) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity a, Bundle b) {
        }

        @Override
        public void onActivityDestroyed(Activity a) {
        }
    };
}

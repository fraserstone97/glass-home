package app.glasshome.aerials;

import android.service.dreams.DreamService;

/**
 * Apple TV aerials as the TV's screen saver: Android starts it after the usual idle time, and
 * never while a video plays. Up shows where you are; Right moves to the next; any other button
 * closes it. A dream, not an activity, so the TV's own Turn Off TV still applies, as Apple TV's
 * Sleep After does.
 */
public class AerialDream extends DreamService {

    AerialPlayer player;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Interactive, so Up and Right do their own thing instead of closing it.
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        player = new AerialPlayer(this);
        setContentView(player.build());
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (player != null && player.key(e)) finish();
        return true;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        player.start();
    }

    @Override
    public void onDreamingStopped() {
        // The picture holds still through the system's close fade; the players go with the window.
        player.pause();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (player != null) player.stop();
        super.onDetachedFromWindow();
    }
}

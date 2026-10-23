package app.glasshome.aerials;

import android.service.dreams.DreamService;

/**
 * Apple TV aerials as the TV's screen saver: Android starts it after the usual idle time, and
 * never while a video plays. Up shows where you are; any other button closes it.
 */
public class AerialDream extends DreamService {

    AerialPlayer player;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Interactive, so Up can show the place name instead of closing it.
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
        player.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (player != null) player.stop();
        super.onDetachedFromWindow();
    }
}

package app.glasshome.aerials;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The aerials on demand: Settings' Preview, and the screen saver straight away (Back on the first
 * app, Control Centre) when the TV's own one can't be started. The same player as the screen
 * saver, full screen: Up shows where you are, Right moves to the next, and any other button
 * returns to where you were.
 */
public class AerialActivity extends Activity {

    AerialPlayer player;
    final Handler handler = new Handler(Looper.getMainLooper());
    /** When the TV turns itself off (Sleep After, in Settings > Screen Saver), or -1 for never. */
    int sleepAfter = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            sleepAfter = Settings.Secure.getInt(getContentResolver(), "sleep_timeout", -1);
        } catch (RuntimeException e) {
            // not readable on this TV: it stays on until a button is pressed
        }
        player = new AerialPlayer(this);
        setContentView(player.build());
    }

    /**
     * Keeping the screen on holds off the TV's own Turn Off TV, so this keeps its time instead, as
     * Apple TV's Sleep After still applies to the screen saver: that long after the last button,
     * the screen is let go and the TV switches itself off.
     */
    final Runnable sleep = () -> {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finish();
    };

    void sleepLater() {
        handler.removeCallbacks(sleep);
        // Not once closing: a key during the close would keep the finished activity for hours.
        if (sleepAfter > 0 && !isFinishing()) handler.postDelayed(sleep, sleepAfter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        player.start();
        sleepLater();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(sleep);
        // The picture holds still while the window closes; the players go in onStop.
        player.pause();
        super.onPause();
        finish();
    }

    @Override
    protected void onStop() {
        player.stop();
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        sleepLater();
        if (player.key(e)) finish();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        // A short fade back to where you were, as the screen saver closes (300 ms).
        overridePendingTransition(0, R.anim.aerial_exit);
    }
}

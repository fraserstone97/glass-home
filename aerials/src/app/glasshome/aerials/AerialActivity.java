package app.glasshome.aerials;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The aerials on demand (Control Centre's Screen Saver button): the same player as the screen
 * saver, full screen: Up shows where you are, and any other button returns to where you were.
 */
public class AerialActivity extends Activity {

    AerialPlayer player;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        player = new AerialPlayer(this);
        setContentView(player.build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        player.start();
    }

    @Override
    protected void onPause() {
        player.stop();
        super.onPause();
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (player.key(e)) finish();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}

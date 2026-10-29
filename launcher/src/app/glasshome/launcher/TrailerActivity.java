package app.glasshome.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * A title's trailer, played here when YouTube's app isn't on the TV (DetailActivity.playTrailer
 * prefers YouTube's app, signed in, where a Premium account means no adverts): full screen on
 * black, and when it ends (or on Back) the title page is there again, with Search under it. The
 * video is YouTube's embedded player in a page of our own. The remote drives it as Apple's player:
 * OK or play/pause pauses, Left and Right skip ten seconds, Back closes. While paused, the title
 * and a thin progress line show; never while playing, when YouTube shows its own pause button.
 * A video whose owner won't let it play outside YouTube opens in YouTube's own player instead.
 *
 * In a process of its own: the web engine it needs (tens of megabytes) never stays in Home's.
 */
public class TrailerActivity extends Activity {
    static final String EXTRA_KEY = "key", EXTRA_TITLE = "title";
    /** The address the player is told it's embedded in: YouTube refuses an embed with none. */
    static final String ORIGIN = "https://app.glasshome.launcher";
    static final long SKIP_S = 10;

    final Handler ui = new Handler(Looper.getMainLooper());
    WebView web;
    View chrome;
    Progress progress;
    TextView elapsed, remaining;
    String key, title;
    boolean playing, failed;
    double at, length;

    int u(float units) {
        return Ui.px(this, units);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ClipAudit.watch(this);  // a development check, off unless asked for over adb
        open++;
        key = getIntent().getStringExtra(EXTRA_KEY);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (key == null || !key.matches("[A-Za-z0-9_-]{6,20}")) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // A trailer is watched, not idled over: no screen saver or sleep while it's on.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        // The remote is ours, not the page's: the player is never focused or navigated.
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);  // it starts at once, with sound
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                return true;  // a click through to YouTube's site goes nowhere
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "GH");
        web.loadDataWithBaseURL(ORIGIN + "/", page(key), "text/html", "utf-8", null);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        // The title at the top and the progress line at the bottom, each over a soft shade.
        FrameLayout over = new FrameLayout(this);
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x00000000}));
        over.addView(top, new FrameLayout.LayoutParams(-1, u(110), Gravity.TOP));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));
        over.addView(bottom, new FrameLayout.LayoutParams(-1, u(130), Gravity.BOTTOM));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        TextView kind = text(11.5f, "semibold", 0x99FFFFFF);
        kind.setText("TRAILER");
        kind.setLetterSpacing(0.08f);
        head.addView(kind);
        TextView name = text(22, "display", Color.WHITE);
        name.setText(title == null ? "" : title);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(name);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hl.leftMargin = u(40);
        hl.topMargin = u(30);
        over.addView(head, hl);

        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        elapsed = text(11, "medium", 0xCCFFFFFF);
        remaining = text(11, "medium", 0xCCFFFFFF);
        progress = new Progress();
        foot.addView(elapsed, new LinearLayout.LayoutParams(u(48), -2));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, u(10), 1f);
        pl.leftMargin = pl.rightMargin = u(10);
        foot.addView(progress, pl);
        remaining.setGravity(Gravity.END);
        foot.addView(remaining, new LinearLayout.LayoutParams(u(48), -2));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        fl.leftMargin = fl.rightMargin = u(40);
        fl.bottomMargin = u(30);
        over.addView(foot, fl);

        // Shown only while paused (show), when YouTube's player shows nothing over the picture:
        // never beside its own pause button, which it shows as playing starts and resumes.
        chrome = over;
        over.setAlpha(0f);
        root.addView(over, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    TextView text(float size, String weight, int colour) {
        TextView t = new TextView(this);
        Ui.textSize(t, size);
        t.setTypeface(Ui.font(this, weight));
        t.setTextColor(colour);
        t.setShadowLayer(u(4), 0, 0, 0x80000000);
        return t;
    }

    /**
     * The player page: YouTube's embedded player, with its controls, related videos and
     * annotations off. Its title bar (at the start, and when paused) and "More videos" can't be
     * turned off, so the player is made taller than the screen: the picture, fitted to the width,
     * fills the screen exactly, and those bars sit in the strips above and below it, off screen.
     * Nothing can point at it either, so its pause button never shows over the picture. It reports
     * back through GH (Bridge).
     */
    static String page(String key) {
        return "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;left:0;width:100%;top:-120px;height:calc(100% + 240px);pointer-events:none}</style>"
                + "</head><body><div id=p></div><script>"
                + "var player;"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:'" + key + "',"
                + "host:'https://www.youtube-nocookie.com',"
                + "playerVars:{autoplay:1,controls:0,rel:0,iv_load_policy:3,playsinline:1,fs:0,disablekb:1,modestbranding:1,origin:'" + ORIGIN + "'},"
                + "events:{onReady:function(e){e.target.playVideo();},"
                + "onStateChange:function(e){GH.state(e.data);},"
                + "onError:function(e){GH.error(e.data);}}});}"
                + "setInterval(function(){if(player&&player.getCurrentTime){GH.time(player.getCurrentTime(),player.getDuration()||0);}},250);"
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
    }

    /** What the player says, on its own thread: handed to the UI thread. */
    final class Bridge {
        @JavascriptInterface public void state(int s) {
            ui.post(() -> stateChanged(s));
        }

        @JavascriptInterface public void time(double now, double of) {
            ui.post(() -> {
                at = now;
                length = of;
                progress.invalidate();
                elapsed.setText(clock(now));
                remaining.setText(of > 0 ? "−" + clock(Math.max(0, of - now)) : "");
            });
        }

        @JavascriptInterface public void error(int code) {
            ui.post(() -> failedToPlay());
        }
    }

    /** YouTube's states: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued. */
    void stateChanged(int s) {
        if (isFinishing()) return;
        if (s == 1) {
            // Playing: ours goes at once, before YouTube's pause button can appear beside it.
            playing = true;
            chrome.animate().cancel();
            chrome.setAlpha(0f);
        } else if (s == 2) {
            // Paused: the title and where it's got to, until it plays again, as on tvOS.
            playing = false;
            chrome.animate().cancel();
            chrome.animate().alpha(1f).setDuration(200).start();
        } else if (s == 0) {
            close();
        }
    }

    /**
     * The video can't be played here (its owner allows it only on YouTube, or it's gone): YouTube's
     * own bare player instead, which comes back to the title page when it's over.
     */
    void failedToPlay() {
        if (failed || isFinishing()) return;
        failed = true;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                .putExtra("force_fullscreen", true).putExtra("finish_on_ended", true);
        if (Suggestions.installed(this, DetailActivity.YOUTUBE)) i.setPackage(DetailActivity.YOUTUBE);
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            // nothing on this TV plays it
        }
        finish();
        overridePendingTransition(0, 0);
    }

    void js(String code) {
        if (web != null) web.evaluateJavascript("if(player&&player.getPlayerState){" + code + "}", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return isOurs(k) || super.dispatchKeyEvent(e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() == 0) js("player.getPlayerState()==1?player.pauseVideo():player.playVideo();");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                js("player.playVideo();");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                js("player.pauseVideo();");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                skip(-SKIP_S);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                skip(SKIP_S);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;  // nothing to move to
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    static boolean isOurs(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE
                || k == KeyEvent.KEYCODE_MEDIA_REWIND || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
    }

    /** Ten seconds on or back, shown on the line straight away rather than when the player catches up. */
    void skip(long by) {
        double to = Math.max(0, length > 0 ? Math.min(length - 1, at + by) : at + by);
        at = to;
        progress.invalidate();
        elapsed.setText(clock(to));
        js(String.format(Locale.ROOT, "player.seekTo(%.1f,true);", to));
    }

    /** Back to the title page, the player fading out rather than cutting away. */
    void close() {
        if (isFinishing()) return;
        if (web != null) web.evaluateJavascript("if(player&&player.pauseVideo)player.pauseVideo();", null);
        finish();
        overridePendingTransition(0, R.anim.page_out);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving (Home, another app): the trailer stops, and isn't waiting when Search comes back.
        // Not on pause: Control Centre opens over it (it plays on beneath) and closes again.
        if (web != null) web.onPause();
        if (!isFinishing()) finish();
    }

    /** Trailers open now (in this process). */
    static int open;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        // The web engine is a hundred megabytes or so: once no trailer is open, its process ends
        // rather than waiting for Android to want the memory back.
        if (--open <= 0) new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (open <= 0) android.os.Process.killProcess(android.os.Process.myPid());
        }, 600);
    }

    /** "1:05". */
    static String clock(double s) {
        int t = (int) Math.round(s);
        return String.format(Locale.ROOT, "%d:%02d", t / 60, t % 60);
    }

    /** Apple's thin progress line: a faint track, the part played in white. */
    final class Progress extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        Progress() {
            super(TrailerActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = u(3), y = (getHeight() - h) / 2f, rad = h / 2f;
            r.set(0, y, getWidth(), y + h);
            paint.setColor(0x4DFFFFFF);
            c.drawRoundRect(r, rad, rad, paint);
            if (length > 0) {
                r.right = (float) (getWidth() * Math.min(1, at / length));
                paint.setColor(Color.WHITE);
                c.drawRoundRect(r, rad, rad, paint);
            }
        }
    }
}

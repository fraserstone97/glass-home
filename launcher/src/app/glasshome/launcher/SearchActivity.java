package app.glasshome.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * tvOS-style Search: a glass search field, voice dictation and a grid of posters for what's on the
 * UK services this TV has (JustWatch, no key). Dictation uses a speech recogniser other than the
 * Google/Gemini voice app, so the mic button searches content instead of opening an assistant.
 * Started by the remote's mic button (ASSIST / VOICE_ASSIST / SEARCH intents) and from Control Center.
 */
public class SearchActivity extends Activity {

    static final String KATNISS = "com.google.android.katniss";
    static final int COLS = 6, REQ_MIC = 7;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Each search on its own thread: a new one never waits for one that's been typed past. */
    final ExecutorService searches = Executors.newCachedThreadPool();
    EditText field;
    TextView status;
    MicView mic;
    GradientDrawable barRim;
    android.animation.ValueAnimator barAnim;
    float barGlow;
    LinearLayout grid;
    /** Popular Now from the last visit, kept while tvOS is running. */
    static List<SearchBrain.Shelf> popular;
    SearchBrain brain;
    ScrollView scroll;
    /** Where the shelves start: below the field and status line. */
    int HEADER;
    SpeechRecognizer recognizer;
    boolean listening;
    int generation;
    final Runnable debounced = () -> search(field.getText().toString().trim());

    /** One search result: a title and the UK services (installed on this TV) that stream it. */
    static final class Result {
        String title, year, type, poster, id, backdrop;
        /** JustWatch's own name for it ("The Office (UK)"), to tell same-named programmes apart. */
        String raw = "";
        /** Another result has the same name: the card shows its year to tell them apart. */
        boolean dupe;
        double imdb;
        int tomato = -1;
        boolean certified, rentBuy;
        /** Its poster on screen, for filling in when the image arrives. */
        transient ImageView art;
        /** Where it was made (ISO codes), for keeping loose matches to what UK viewers watch. */
        final List<String> countries = new ArrayList<>();
        int tmdbId, votes;
        /** JustWatch's genre codes, main genre first. */
        final List<String> genres = new ArrayList<>();
        /** For an actor's or director's work: the part they played. */
        String role;
        final List<Suggestions.Item> offers = new ArrayList<>();

        boolean on(int providerId) {
            for (Suggestions.Item it : offers) if (it.providerId == providerId) return true;
            return false;
        }
        Intent appLaunch;
        Drawable appBanner;
    }

    /** Stand-ins in the text for Rotten Tomatoes' icons; showIcons() draws them. */
    static final char FRESH = '\uE000', ROTTEN = '\uE001', CERTIFIED = '\uE002';

    /**
     * "IMDb 8.4 · [tomato] 97%": whichever of the two scores the title has. As on Rotten Tomatoes,
     * 60% and up is a red tomato (fresh), below that a green splat (rotten), and the badge for
     * films and series Rotten Tomatoes has certified fresh.
     */
    static String ratings(double imdb, int tomato, boolean certified) {
        StringBuilder b = new StringBuilder();
        if (imdb > 0) b.append(String.format(java.util.Locale.UK, "IMDb %.1f", imdb));
        if (tomato >= 0) b.append(b.length() > 0 ? " \u00b7 " : "").append(certified && tomato >= 60 ? CERTIFIED : tomato >= 60 ? FRESH : ROTTEN)
                .append(' ').append(tomato).append('%');
        return b.toString();
    }

    /** Swaps the stand-ins in a TextView's text for the drawn icons, sized to its text. */
    static void showIcons(TextView t) {
        CharSequence s = t.getText();
        if (s == null) return;
        String str = s.toString();
        if (str.indexOf(FRESH) < 0 && str.indexOf(ROTTEN) < 0 && str.indexOf(CERTIFIED) < 0) return;
        android.text.SpannableString sp = new android.text.SpannableString(s);
        // About the height of the text's capitals, centred on the line: visible, never bigger
        // than the words beside it. The same rule in Search and on title pages.
        int size = Math.round(t.getTextSize() * 0.82f);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != FRESH && ch != ROTTEN && ch != CERTIFIED) continue;
            android.graphics.drawable.Drawable d = icon(t.getContext(), ch, size);
            d.setBounds(0, 0, size, size);
            sp.setSpan(new android.text.style.ImageSpan(d, android.os.Build.VERSION.SDK_INT >= 29
                    ? android.text.style.DynamicDrawableSpan.ALIGN_CENTER : android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                    i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        t.setText(sp);
    }

    /**
     * The icon artwork in res/drawable-nodpi (rt_fresh, rt_certified, rt_rotten) when it's there;
     * otherwise a drawn tomato or splat. Certified falls back to the fresh tomato.
     */
    static android.graphics.drawable.Drawable icon(android.content.Context c, char kind, int size) {
        // Each kind and size is made once: a page of results asks for dozens, and decoding the
        // picture for every card cost a results page a sixth of its build time.
        String key = kind + ":" + size;
        android.graphics.Bitmap made = ICONS.get(key);
        if (made == null) ICONS.put(key, made = iconBitmap(c, kind, size));
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), made);
    }

    static final java.util.Map<String, android.graphics.Bitmap> ICONS = new java.util.HashMap<>();

    static android.graphics.Bitmap iconBitmap(android.content.Context c, char kind, int size) {
        String[] names = kind == CERTIFIED ? new String[]{"rt_certified", "rt_fresh"}
                : kind == FRESH ? new String[]{"rt_fresh"} : new String[]{"rt_rotten"};
        for (String n : names) {
            int id = c.getResources().getIdentifier(n, "drawable", c.getPackageName());
            if (id == 0) continue;
            // Shrunk in halves to its exact size (a 168 px picture squeezed to 14 px in one go
            // looks jagged), so the edges stay smooth.
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeResource(c.getResources(), id);
            if (b == null) continue;
            while (b.getWidth() / 2 >= size) {
                android.graphics.Bitmap h = android.graphics.Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
                if (h != b) b.recycle();
                b = h;
            }
            return android.graphics.Bitmap.createScaledBitmap(b, size, size, true);
        }
        return tomatoIcon(kind != ROTTEN, size * 2);
    }

    /** A red tomato with a green crown (fresh), or a green splat (rotten), drawn at s px. */
    static android.graphics.Bitmap tomatoIcon(boolean fresh, int s) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float m = s / 2f;
        if (fresh) {
            p.setShader(new android.graphics.RadialGradient(s * 0.38f, s * 0.42f, s * 0.55f,
                    0xFFFF5A3C, 0xFFD8200F, android.graphics.Shader.TileMode.CLAMP));
            c.drawOval(s * 0.06f, s * 0.2f, s * 0.94f, s * 0.96f, p);
            p.setShader(null);
            p.setColor(0xFF2FA84F);
            android.graphics.Path leaf = new android.graphics.Path();
            for (int i = 0; i < 5; i++) {  // a five-pointed crown of leaves
                double a = Math.toRadians(-90 + i * 72);
                float x = m + (float) Math.cos(a) * s * 0.2f, y = s * 0.24f + (float) Math.sin(a) * s * 0.1f;
                leaf.addOval(Math.min(m, x) - s * 0.04f, Math.min(s * 0.24f, y) - s * 0.035f,
                        Math.max(m, x) + s * 0.04f, Math.max(s * 0.24f, y) + s * 0.035f, android.graphics.Path.Direction.CW);
            }
            c.drawPath(leaf, p);
        } else {
            p.setColor(0xFF4CB03A);
            android.graphics.Path splat = new android.graphics.Path();
            int n = 9;
            for (int i = 0; i <= n * 2; i++) {  // a blob with uneven drips round its edge
                double a = Math.toRadians(i * 180.0 / n);
                float r = s * (i % 2 == 0 ? 0.47f - (i % 4 == 0 ? 0.04f : 0f) : 0.3f);
                float x = m + (float) Math.cos(a) * r, y = m + (float) Math.sin(a) * r;
                if (i == 0) splat.moveTo(x, y);
                else splat.lineTo(x, y);
            }
            splat.close();
            p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.12f));
            c.drawPath(splat, p);
            p.setPathEffect(null);
            c.drawCircle(m, m, s * 0.3f, p);
        }
        return b;
    }

    int u(float units) {
        return Ui.px(this, units);
    }

    long createdAt;
    /** Opened over Home (not over another app). */
    boolean overHome;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        createdAt = android.os.SystemClock.uptimeMillis();
        overHome = MainActivity.onScreen;
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF040405, 0xFF050506, 0xFF000000});
        root.setBackground(bg);

        HEADER = u(40) + u(48) + u(8) + u(30) + u(10);
        // Behind everything, as on tvOS: the focused title's artwork, soft and dim.
        ambient = new ImageView(this);
        ambient.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ambient.setAlpha(0f);
        // Its dark shade rides on it, so with no picture there's nothing extra to draw, and it's
        // kept as one ready image so fading it costs nothing.
        ambient.setForeground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x8C000000, 0xE6000000}));
        ambient.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(ambient, new FrameLayout.LayoutParams(-1, -1));

        // The header (field and status) floats over the shelves on a soft dark fade, and the shelves
        // glide under it: no hard edge to slice a poster that's lifting as the page scrolls.
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, u(40), 0, u(18));
        col.setClipChildren(false);
        col.setClipToPadding(false);  // the listening glow reaches above the field
        col.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF0000000, 0xD9000000, 0x00000000}));

        // Glass search field: mic button + text.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(u(4), 0, u(16), 0);  // the mic sits centred in the capsule's round end
        bar.setBackground(glassBar());
        // Centred, about half the screen wide, like tvOS's search field.
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(Ui.screen(this).x * 56 / 100, u(48));
        barLp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(bar, barLp);

        mic = new MicView();
        mic.setFocusable(true);
        mic.setOnClickListener(v -> toggleListening());
        mic.setOnFocusChangeListener((v, f) -> mic.animateFocus(f));
        bar.addView(mic, new LinearLayout.LayoutParams(u(40), u(40)));

        field = new EditText(this);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(0x80FFFFFF);
        field.setHint("Shows, films and apps");
        field.setTypeface(Ui.font(this, "medium"));
        Ui.textSize(field, 17);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setOnEditorActionListener((v, a, e) -> {
            ui.removeCallbacks(debounced);
            search(field.getText().toString().trim());
            return false;
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) {
                ui.removeCallbacks(debounced);
                ui.postDelayed(debounced, 450);
            }
        });
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(0, -1, 1f);
        fLp.leftMargin = u(10);
        // The text and, while listening, a waveform in its place (as tvOS dictation does).
        FrameLayout fieldBox = new FrameLayout(this);
        fieldBox.addView(field, new FrameLayout.LayoutParams(-1, -1));
        wave = new WaveView();
        wave.setAlpha(0f);
        FrameLayout.LayoutParams wLp = new FrameLayout.LayoutParams(-1, u(26), Gravity.CENTER_VERTICAL);
        wLp.rightMargin = u(10);
        fieldBox.addView(wave, wLp);
        bar.addView(fieldBox, fLp);

        status = new TextView(this);
        // Just under the field, centred: what's happening (Listening, Searching, no results).
        // The shelves carry their own headings.
        status.setTextColor(0xB3FFFFFF);
        status.setTypeface(Ui.font(this, "medium"));
        Ui.textSize(status, 13);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, u(30));
        sLp.topMargin = u(8);
        col.addView(status, sLp);

        // The shelves scroll under the search field, never over it: the scroll clips at its own top,
        // with padding inside it so a lifted poster still has room. Scrolling follows focus by
        // whole shelves (keepInView), not by Android's own nudging.
        scroll = new ScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
                return 0;
            }

            @Override protected float getBottomFadingEdgeStrength() {
                return 0f;  // the soft fade is only for shelves leaving under the field
            }
        };
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        // Starts a little under the field and fades out at its top edge, so a shelf leaving
        // upwards (or a poster lifting as the page scrolls) softens away instead of being cut.
        scroll.setPadding(0, u(26), 0, u(40));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(u(30));
        scroll.setVerticalScrollBarEnabled(false);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        scroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams scLp = new FrameLayout.LayoutParams(-1, -1);
        scLp.topMargin = HEADER - u(26);
        root.addView(scroll, scLp);
        root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        setContentView(root);
        enter(root, col);
        brain = new SearchBrain(this);
        setupRecognizer();
        // A query handed over (adb: --es q "films with tom hardy"), as if it had been typed.
        String given = getIntent().getStringExtra("q");
        if (given != null) field.setText(given);
        ui.removeCallbacks(debounced);
        search(given == null ? "" : given);
        live = new java.lang.ref.WeakReference<>(this);
        if (isVoiceIntent(getIntent())) listenSoon();
        else mic.requestFocus();
    }

    /**
     * The tvOS-style arrival: the page fades up over Home while the search field drops softly into
     * place on a spring and the shelves rise in after it (show() staggers them).
     */
    void enter(View root, View header) {
        root.setAlpha(0f);
        header.setTranslationY(-u(30));
        header.setScaleX(0.96f);
        header.setScaleY(0.96f);
        // Results that arrive while the page is arriving wait for it (show), so building their
        // rows never takes frames from the entrance.
        root.post(() -> entering = true);
        ui.postDelayed(() -> entering = false, 660);
        root.animate().alpha(1f).setDuration(300).setInterpolator(MainActivity.EASE).withEndAction(() -> {
            // Faded in over Home: from now on the page is solid, so the TV draws only it, not Home
            // underneath as well. (Over another app it stays see-through, so that app isn't stopped.)
            if (overHome && android.os.Build.VERSION.SDK_INT >= 30 && !isFinishing()) setTranslucent(false);
        }).start();
        // As one image while it moves (like the lifting pills): no text re-drawn at each size.
        header.animate().translationY(0).scaleX(1f).scaleY(1f).setStartDelay(40).setDuration(620)
                .setInterpolator(Glass.SNAP).withLayer().start();
    }

    /** Leaving for Home or another app: next time Search starts fresh (a title page keeps it). */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        stopListening();
        finish();
        overridePendingTransition(0, R.anim.page_out);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(0, R.anim.page_out);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (isVoiceIntent(i)) listenSoon();
        live = new java.lang.ref.WeakReference<>(this);
    }

    static boolean isVoiceIntent(Intent i) {
        String a = i == null ? null : i.getAction();
        return a != null && !a.equals(Intent.ACTION_MAIN) && !a.equals(Intent.ACTION_SEARCH)
                && !i.getBooleanExtra("keyboard", false);
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST) {
            if (e.getRepeatCount() == 0) startListening();
            return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListening();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.destroy();
        io.shutdownNow();
        searches.shutdownNow();
        ambientIo.shutdownNow();
        ambientNow.shutdownNow();
    }

    // ---------------------------------------------------------------- voice

    /** A speech recogniser that is not the Google/Gemini voice app (e.g. Google's speech services). */
    ComponentName recognitionService() {
        List<ResolveInfo> l = getPackageManager().queryIntentServices(
                new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        for (ResolveInfo r : l) {
            if (!KATNISS.equals(r.serviceInfo.packageName)) {
                return new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
            }
        }
        return null;
    }

    void setupRecognizer() {
        ComponentName cn = recognitionService();
        if (cn == null) {
            mic.setVisibility(View.GONE);
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, cn);
        checkLanguage();
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {
                setListening(true, "Listening…");
                quietEnd = false;
                ui.removeCallbacks(quietStop);
                ui.postDelayed(quietStop, QUIET_MS);
                ui.removeCallbacks(maxStop);
                ui.postDelayed(maxStop, 12000);
            }
            // Sound started: give words time to come back before the silence timer can end it.
            @Override public void onBeginningOfSpeech() {
                ui.removeCallbacks(quietStop);
                ui.postDelayed(quietStop, QUIET_MS + 2500);
            }
            @Override public void onRmsChanged(float db) {
                // Loudness over the room's own hum: the floor follows the quietest moments, so the
                // wave stays low in silence and rises when you speak.
                if (rmsCount++ == 0 || db < noiseFloor) noiseFloor = db;
                else noiseFloor += (db - noiseFloor) * 0.004f;
                if (wave != null) wave.level = Math.max(0f, Math.min(1f, (db - noiseFloor - 1f) / 6f));
            }
            @Override public void onBufferReceived(byte[] buf) { }
            @Override public void onEndOfSpeech() { setListening(false, "Searching…"); }
            @Override public void onError(int err) {
                ui.removeCallbacks(quietStop);
                if (!listening) return;  // stopped on purpose (a second press)
                if (quietEnd && (err == SpeechRecognizer.ERROR_NO_MATCH || err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    quietEnd = false;
                    setListening(false, "");
                    return;
                }
                setListening(false, err == SpeechRecognizer.ERROR_NO_MATCH || err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? "Didn\u2019t catch that. Try again."
                        : err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "Microphone access is off."
                        : "Voice isn\u2019t available. Type instead.");
            }
            @Override public void onResults(Bundle r) {
                setListening(false, null);
                List<String> words = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (words == null || words.isEmpty()) return;
                float[] sure = r.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                spoken = true;
                field.setText(words.get(0));
                field.setSelection(field.length());
                ui.removeCallbacks(debounced);
                if (words.size() == 1) {
                    search(words.get(0));
                    return;
                }
                // Several things it might have heard ("June", "Dune"): the one that names a real,
                // well-known title is searched.
                setStatus("Searching\u2026");
                int gen = generation;
                searches.execute(() -> {
                    String best = brain.bestSpoken(words, sure);
                    android.util.Log.i("SearchVoice", "heard " + words + " \u2192 " + best);
                    ui.post(() -> {
                        if (gen != generation) return;
                        if (!best.equals(words.get(0))) {
                            field.setText(best);
                            field.setSelection(field.length());
                            ui.removeCallbacks(debounced);
                        }
                        search(best);
                    });
                });
            }
            @Override public void onPartialResults(Bundle r) {
                List<String> words = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (words != null && !words.isEmpty() && !words.get(0).isEmpty()) {
                    ui.removeCallbacks(quietStop);
                    if (wave != null) wave.kick();
                    ui.removeCallbacks(maxStop);
                    ui.postDelayed(maxStop, 8000);  // talking: up to 8 more seconds
                    status.setText("“" + words.get(0) + "”");  // live words: no fade
                }
            }
            @Override public void onEvent(int t, Bundle p) { }
        });
    }

    /**
     * British English, on the TV itself: checks the speech service has its UK model and asks it to
     * fetch it if not, so it isn't quietly listening for American English instead.
     */
    void checkLanguage() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB");
        try {
            recognizer.checkRecognitionSupport(i, getMainExecutor(), new android.speech.RecognitionSupportCallback() {
                @Override public void onSupportResult(android.speech.RecognitionSupport s) {
                    android.util.Log.i("SearchVoice", "en-GB installed " + s.getInstalledOnDeviceLanguages()
                            + " pending " + s.getPendingOnDeviceLanguages() + " on device " + s.getSupportedOnDeviceLanguages()
                            + " online " + s.getOnlineLanguages());
                    boolean have = false, can = false;
                    for (String l : s.getInstalledOnDeviceLanguages()) have |= l.equalsIgnoreCase("en-GB");
                    for (String l : s.getPendingOnDeviceLanguages()) have |= l.equalsIgnoreCase("en-GB");
                    for (String l : s.getSupportedOnDeviceLanguages()) can |= l.equalsIgnoreCase("en-GB");
                    if (!have && can && recognizer != null) {
                        android.util.Log.i("SearchVoice", "fetching the en-GB speech model");
                        recognizer.triggerModelDownload(i);
                    }
                }
                @Override public void onError(int error) {
                    android.util.Log.i("SearchVoice", "language check unavailable: " + error);
                }
            });
        } catch (Exception e) {
            android.util.Log.i("SearchVoice", "language check failed: " + e);
        }
    }

    void toggleListening() {
        if (listening) stopListening();
        else startListening();
    }

    void startListening() {
        if (recognizer == null) {
            setStatus("Voice isn\u2019t available. Type instead.");
            field.requestFocus();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        recognizer.cancel();
        rmsCount = 0;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-GB")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Lean towards what's on TV now and what you've searched before.
            java.util.ArrayList<String> likely = new java.util.ArrayList<>(recents());
            if (popular != null) for (SearchBrain.Shelf sh : popular) for (Result r : sh.items) likely.add(r.title);
            if (likely.size() > 60) likely = new java.util.ArrayList<>(likely.subList(0, 60));
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, likely);
        }
        mic.requestFocus();
        setListening(true, "Listening…");
        recognizer.startListening(i);
    }

    void stopListening() {
        ui.removeCallbacks(quietStop);
        ui.removeCallbacks(maxStop);
        if (recognizer != null && listening) recognizer.cancel();
        setListening(false, null);
    }

    /** When the remote's mic button was pressed (uptime ms), 0 when Search was opened another way. */
    static volatile long pressedAt;

    /**
     * The TV's own voice system opens and then closes the built-in microphone for about half a
     * second after the remote's mic button is pressed; listening starts once it has let go, or the
     * close cuts our recording off. The page shows it's listening straight away.
     */
    static final long MIC_SETTLE = 150;

    void listenSoon() {
        long wait = pressedAt == 0 ? 0 : pressedAt + MIC_SETTLE - android.os.SystemClock.uptimeMillis();
        pressedAt = 0;
        if (wait <= 0) {
            startListening();
            return;
        }
        mic.requestFocus();
        setListening(true, "Listening\u2026");
        ui.removeCallbacks(listenRun);
        ui.postDelayed(listenRun, wait);
    }

    final Runnable listenRun = this::startListening;

    /** Nothing said this long after the microphone opened: stop quietly, as Siri does. */
    static final long QUIET_MS = 2500;
    final Runnable quietStop = () -> {
        if (!listening || recognizer == null) return;
        // End the recording rather than throw it away: anything heard is still searched, and if
        // nothing was said the recogniser reports no match, which is shown quietly.
        quietEnd = true;
        recognizer.stopListening();
    };

    /** The silence timer ended listening: a "nothing heard" result isn't an error to show. */
    boolean quietEnd;

    /** Never listens for more than this, whatever the room sounds like: then searches what it heard. */
    final Runnable maxStop = () -> {
        if (listening && recognizer != null) recognizer.stopListening();
    };

    /** The mic button pressed while listening: stop. Called by the remote-buttons helper. */
    static boolean stopIfListening() {
        SearchActivity a = live.get();
        if (a == null || !a.listening) return false;
        a.ui.post(() -> {
            a.ui.removeCallbacks(a.listenRun);
            a.stopListening();
            a.setStatus("");
        });
        return true;
    }
    static java.lang.ref.WeakReference<SearchActivity> live = new java.lang.ref.WeakReference<>(null);

    /** The mic button was let go: stop listening and search what was said so far. */
    static void released() {
        SearchActivity a = live.get();
        if (a != null) a.ui.post(a::finishSpeech);
    }

    void finishSpeech() {
        if (recognizer != null && listening) {
            recognizer.stopListening();  // ends the audio; the recogniser then delivers its results
            setStatus("Searching\u2026");
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_MIC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startListening();
        else if (req == REQ_MIC) setStatus("You can still type to search.");
    }

    void setListening(boolean on, String msg) {
        if (listening != on) {
            listening = on;
            mic.animateListening(on);
            animateBar(on ? 1f : 0f);
        }
        if (msg != null) setStatus(msg);
    }

    /** Status line crossfades instead of jumping. */
    void setStatus(String text) {
        if (text.contentEquals(status.getText())) return;
        status.animate().cancel();
        status.animate().alpha(0f).setDuration(120).setInterpolator(MainActivity.EASE).withEndAction(() -> {
            status.setText(text);
            status.animate().alpha(1f).setDuration(220).setInterpolator(MainActivity.EASE).start();
        }).start();
    }

    /**
     * Liquid Glass search field: a translucent body brighter at the top and a specular rim; while
     * listening it brightens slightly all over (barBody) and the rim lifts.
     */
    android.graphics.drawable.LayerDrawable glassBar() {
        float r = u(24);  // half the field's height: a true capsule, as on tvOS
        // Subtle, as the App Store's tab bar: a faint capsule with a hairline edge.
        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1CFFFFFF, 0x0FFFFFFF, 0x14FFFFFF});
        body.setCornerRadius(r);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x12FFFFFF, 0x00FFFFFF});
        // Only the top half: rounded on top with the bar's own radius, square below, so its
        // corners follow the bar's curve exactly instead of poking out.
        sheen.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        barBody = new GradientDrawable();
        barBody.setCornerRadius(r);
        barBody.setColor(0x00FFFFFF);
        barRim = new GradientDrawable();
        barRim.setCornerRadius(r);
        barRim.setColor(0);
        barRim.setStroke(Math.max(1, u(0.6f)), 0x2EFFFFFF);
        glow = new ListenGlow();
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(
                new Drawable[]{body, barBody, sheen, barRim, glow});
        l.setLayerInsetBottom(2, u(24));
        return l;
    }

    GradientDrawable barBody;
    ListenGlow glow;
    android.animation.ValueAnimator glowFlow;

    /** Cool blues and violets, as Siri's light on tvOS (never red). */
    static final int[] GLOW = {0xFF64D2FF, 0xFF7D7AFF, 0xFFBF5AF2, 0xFF7D7AFF, 0xFF64D2FF};
    static final int[] WAVE = {0xFFC4EEFF, 0xFFFFFFFF, 0xFFDCCBFF, 0xFFFFFFFF, 0xFFC4EEFF};

    /**
     * While listening, light flows slowly round the field's rim: a bright line on it and a soft
     * halo just outside. Drawn only while it shows.
     */
    static final int HALO = 6;

    final class ListenGlow extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rr = new RectF();
        final android.graphics.Matrix m = new android.graphics.Matrix();
        android.graphics.LinearGradient shader;
        int shaderW;
        float level, flow;

        ListenGlow() {
            p.setStyle(Paint.Style.STROKE);
        }

        /** A stroke centred `out` outside the field's edge (inside when negative), on its exact curve. */
        void ring(Canvas c, android.graphics.Rect b, float width, float out, int alpha) {
            float half = b.height() / 2f;
            p.setStrokeWidth(width);
            p.setAlpha(alpha);
            rr.set(b);
            rr.inset(-out, -out);
            c.drawRoundRect(rr, half + out, half + out, p);
        }

        @Override public void draw(Canvas c) {
            if (level <= 0.002f) return;
            android.graphics.Rect b = getBounds();
            if (shader == null || shaderW != b.width()) {
                shaderW = b.width();
                shader = new android.graphics.LinearGradient(0, 0, shaderW * 0.55f, 0, GLOW, null,
                        android.graphics.Shader.TileMode.MIRROR);
            }
            m.setTranslate(flow * shaderW * 1.1f, 0);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            // Light, not a shadow: three soft rings fading outwards, then the line on the rim itself
            // (just inside it, following the capsule's curve exactly).
            float line = u(1.5f), step = u(1.1f);
            for (int k = HALO - 1; k >= 0; k--) {
                float fall = 1f - k / (float) HALO;
                ring(c, b, step, step * (k + 0.5f), Math.round(74 * level * fall * fall));
            }
            ring(c, b, line, -line / 2f, Math.round(245 * level));
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    void animateBar(float to) {
        if (barAnim != null) barAnim.cancel();
        if (to > 0f && (glowFlow == null || !glowFlow.isRunning())) {
            glowFlow = android.animation.ValueAnimator.ofFloat(0f, 1f);
            glowFlow.setDuration(5200);
            glowFlow.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            glowFlow.setInterpolator(new android.view.animation.LinearInterpolator());
            glowFlow.addUpdateListener(a -> {
                glow.flow = (float) a.getAnimatedValue();
                glow.invalidateSelf();
            });
            glowFlow.start();
        }
        barAnim = android.animation.ValueAnimator.ofFloat(barGlow, to);
        barAnim.setDuration(420);
        barAnim.setInterpolator(MainActivity.EASE);
        android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        barAnim.addUpdateListener(a -> {
            barGlow = (float) a.getAnimatedValue();
            // The glass rim gives way to the coloured light, so there's one clean edge.
            barRim.setStroke(Math.max(1, u(0.6f)), (int) ev.evaluate(barGlow, 0x2EFFFFFF, 0x00FFFFFF));
            if (wave != null) {
                wave.setAlpha(barGlow);
                if (barGlow > 0f) wave.postInvalidateOnAnimation();
            }
            field.setAlpha(1f - barGlow);
            barBody.setColor((int) ev.evaluate(barGlow, 0x00FFFFFF, 0x1AFFFFFF));
            glow.level = barGlow;
            glow.invalidateSelf();
        });
        barAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (barGlow <= 0f && glowFlow != null) glowFlow.cancel();
            }
        });
        barAnim.start();
    }

    WaveView wave;

    /**
     * A tvOS-style dictation waveform across the field: thin rounded bars that rise and fall with
     * your voice. It moves gently on its own while listening (this TV's speech service doesn't
     * always report loudness), louder when it hears you, and each recognised word gives it a lift.
     */
    final class WaveView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Matrix m = new android.graphics.Matrix();
        android.graphics.LinearGradient shader;
        float[] h = new float[0];
        float level, shown, kick, t;
        long last;

        WaveView() {
            super(SearchActivity.this);
        }

        void kick() {
            kick = 1f;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), ht = getHeight();
            if (w <= 0) return;
            // Timed by the clock, not by frames: the same pace however busy the TV is.
            long now = android.os.SystemClock.uptimeMillis();
            float dt = last == 0 || now - last > 200 ? 1 / 60f : (now - last) / 1000f;
            last = now;
            t += dt;
            kick *= (float) Math.pow(0.03, dt);              // a word's lift fades in about a second
            shown += (level - shown) * Math.min(1f, dt * 9f);
            float energy = Math.min(1f, 0.16f + shown * 0.95f + kick * 0.45f);
            float bw = Math.max(2f, u(2.2f)), gap = bw * 1.7f, span = w * 0.86f;
            int n = Math.max(8, (int) ((span + gap) / (bw + gap)));
            if (h.length != n) h = new float[n];
            float x0 = (w - (n * bw + (n - 1) * gap)) / 2f;
            if (shader == null) {
                shader = new android.graphics.LinearGradient(0, 0, w * 0.6f, 0, WAVE, null,
                        android.graphics.Shader.TileMode.MIRROR);
            }
            m.setTranslate(t * w * 0.12f, 0);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            // The loudest part drifts along the field, as a voice rises and falls.
            float centre = 0.5f + 0.16f * (float) Math.sin(t * 0.8f) + 0.06f * (float) Math.sin(t * 2.1f);
            for (int i = 0; i < n; i++) {
                float pos = i / (float) (n - 1);
                float d = (pos - centre) / 0.36f;
                float env = (float) Math.exp(-d * d) * (float) Math.sin(Math.PI * pos);
                float wob = 0.55f + 0.45f * (float) Math.sin(t * 5.2f + i * 0.6f) * (float) Math.cos(t * 2.3f - i * 0.23f);
                float target = bw + (ht - bw) * env * energy * wob;
                h[i] += (target - h[i]) * Math.min(1f, dt * 16f);
                float x = x0 + i * (bw + gap), hh = Math.max(bw, h[i]);
                // Quiet ends fade away, so it's a voice in the middle of the field, not a dotted line.
                float edge = (float) Math.sin(Math.PI * pos);
                p.setAlpha(Math.round(255 * Math.min(1f, 0.15f + edge * 1.3f)));
                c.drawRoundRect(x, (ht - hh) / 2f, x + bw, (ht + hh) / 2f, bw / 2f, bw / 2f, p);
            }
            if (getAlpha() > 0f) postInvalidateOnAnimation();
            else last = 0;
        }
    }

    /**
     * The round mic button: focus and listening ease its colours in with Apple's curve. It never
     * grows past its own bounds, so nothing overlaps the field's edge.
     */
    final class MicView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        final RectF rect = new RectF();
        float focusT, listenT;
        android.animation.ValueAnimator focusAnim, listenAnim;

        MicView() {
            super(SearchActivity.this);
        }

        void animateFocus(boolean on) {
            if (focusAnim != null) focusAnim.cancel();
            focusAnim = android.animation.ValueAnimator.ofFloat(focusT, on ? 1f : 0f);
            focusAnim.setDuration(on ? 300 : 220);
            focusAnim.setInterpolator(MainActivity.EASE);
            focusAnim.addUpdateListener(a -> {
                focusT = (float) a.getAnimatedValue();
                invalidate();
            });
            focusAnim.start();
        }

        void animateListening(boolean on) {
            if (listenAnim != null) listenAnim.cancel();
            listenAnim = android.animation.ValueAnimator.ofFloat(listenT, on ? 1f : 0f);
            listenAnim.setDuration(380);
            listenAnim.setInterpolator(MainActivity.EASE);
            listenAnim.addUpdateListener(a -> {
                listenT = (float) a.getAnimatedValue();
                invalidate();
            });
            listenAnim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, max = Math.min(cx, cy);
            float base = max * 0.76f;
            int idle = 0x24FFFFFF;
            int fill = (int) ev.evaluate(focusT, idle, Color.WHITE);
            fill = (int) ev.evaluate(listenT, fill, 0xF2FFFFFF);
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            c.drawCircle(cx, cy, base, p);
            if (focusT < 1f && listenT < 1f) {  // glass rim on the resting button
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, u(0.6f)));
                p.setColor(Color.argb(Math.round(70 * (1f - Math.max(focusT, listenT))), 255, 255, 255));
                c.drawCircle(cx, cy, base, p);
            }
            int ink = (int) ev.evaluate(focusT, Color.WHITE, 0xFF111114);
            ink = (int) ev.evaluate(listenT, ink, 0xFF111114);
            p.setColor(ink);
            p.setStyle(Paint.Style.FILL);
            float w = base * 0.36f, h = base * 0.66f, top = cy - base * 0.56f;
            rect.set(cx - w / 2, top, cx + w / 2, top + h);
            c.drawRoundRect(rect, w / 2, w / 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(base * 0.11f);
            p.setStrokeCap(Paint.Cap.ROUND);
            float r = base * 0.34f;
            rect.set(cx - r, top + h - r * 1.35f, cx + r, top + h + r * 0.65f);
            c.drawArc(rect, 0, 180, false, p);
            c.drawLine(cx, top + h + r * 0.65f, cx, cy + base * 0.58f, p);
        }
    }

    // ---------------------------------------------------------------- search

    /** What is being searched for now ("" while the field is empty). */
    String lastQuery = "";

    void search(String q) {
        lastQuery = q;
        int gen = ++generation;
        // Popular Now from the last visit, at once; it's refreshed quietly behind.
        if (q.isEmpty() && popular != null) show(popular);
        if (!listening) setStatus(q.isEmpty() ? "" : "Searching\u2026");
        searches.execute(() -> {
            List<SearchBrain.Shelf> shelves = new ArrayList<>();
            String err = null;
            try {
                if (q.isEmpty()) {
                    List<Result> all = brain.popularNow(), films = new ArrayList<>(), series = new ArrayList<>();
                    for (Result r : all) ("tv".equals(r.type) ? series : films).add(r);
                    if (!series.isEmpty()) shelves.add(new SearchBrain.Shelf("Popular Series", series));
                    if (!films.isEmpty()) shelves.add(new SearchBrain.Shelf("Popular Films", films));
                } else {
                    // The top results go up as soon as they're known; More Like This and the rest
                    // slide in under them when they arrive.
                    shelves.addAll(brain.answer(q, early -> {
                        if (gen != generation) return;
                        List<SearchBrain.Shelf> first = withApps(q, early);
                        ui.post(() -> {
                            if (gen != generation) return;
                            if (!listening && !first.isEmpty()) setStatus("");
                            show(first);
                        });
                        searches.execute(() -> posters(gen, first));
                    }));
                }
            } catch (Exception e) {
                err = "Can\u2019t connect. Check your internet connection.";
            }
            if (gen != generation) return;
            if (!q.isEmpty()) shelves = withApps(q, shelves);
            if (gen != generation) return;
            boolean shownFromMemory = q.isEmpty() && popular != null;
            if (q.isEmpty() && !shelves.isEmpty()) popular = shelves;
            String msg = err;
            List<SearchBrain.Shelf> all = shelves;
            if (!shownFromMemory) {
                ui.post(() -> {
                    if (gen != generation) return;
                    if (msg != null && all.isEmpty()) setStatus(msg);
                    else if (!listening) setStatus(all.isEmpty() ? "No results for \u201c" + q + "\u201d" : "");
                    show(all);
                });
            }
            posters(gen, all);
        });
    }

    /** The apps that match, placed among the shelves: first when one is what was asked for. */
    List<SearchBrain.Shelf> withApps(String q, List<SearchBrain.Shelf> in) {
        List<SearchBrain.Shelf> shelves = SearchBrain.copy(in);
        String text = SearchBrain.read(q).text, key = text.isEmpty() ? q : text;
        List<Result> apps = matchApps(key);
        if (!apps.isEmpty()) {
            // "netflix", "bbc": the app is what's wanted; otherwise apps come last.
            boolean exact = SearchBrain.matchScore(key, apps.get(0).title) >= 85;
            shelves.add(exact ? 0 : shelves.size(), new SearchBrain.Shelf("Apps", apps));
        }
        return shelves;
    }

    /** Posters fill in as they arrive, in order, each fading into its placeholder. */
    void posters(int gen, List<SearchBrain.Shelf> shelves) {
        for (SearchBrain.Shelf sh : shelves) {
            for (Result r : sh.items) {
                if (gen != generation) return;
                if (r.appBanner != null || r.poster == null) continue;
                Bitmap bm = Suggestions.image(this, r.poster, "", u(150));
                if (bm == null || r.appBanner != null) continue;
                r.appBanner = new android.graphics.drawable.BitmapDrawable(getResources(), bm);
                ui.post(() -> {
                    if (r.art == null) return;
                    r.art.setImageDrawable(r.appBanner);
                    r.art.setImageAlpha(0);
                    android.animation.ObjectAnimator.ofInt(r.art, "imageAlpha", 0, 255).setDuration(260).start();
                });
            }
        }
    }

    /** Installed apps whose name contains the query. */
    List<Result> matchApps(String q) {
        List<Result> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        String lq = q.toLowerCase(Locale.UK);
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            String label = String.valueOf(r.loadLabel(pm));
            boolean hit = label.toLowerCase(Locale.UK).contains(lq) || SearchBrain.matchScore(q, label) >= 85;
            if (!hit || r.activityInfo.packageName.equals(getPackageName())) continue;
            Result a = new Result();
            a.title = label;
            a.type = "app";
            a.appLaunch = pm.getLeanbackLaunchIntentForPackage(r.activityInfo.packageName);
            a.appBanner = r.activityInfo.loadBanner(pm);
            if (a.appBanner == null) a.appBanner = r.loadIcon(pm);
            if (a.appLaunch != null && out.size() < COLS) out.add(a);
        }
        return out;
    }

    // ---------------------------------------------------------------- results grid

    /** The shelves on screen now. */
    List<SearchBrain.Shelf> showing = new ArrayList<>();

    static boolean same(SearchBrain.Shelf a, SearchBrain.Shelf b) {
        if (!a.heading.equals(b.heading) || a.items.size() != b.items.size()) return false;
        for (int k = 0; k < a.items.size(); k++) if (!a.items.get(k).title.equals(b.items.get(k).title)) return false;
        return true;
    }

    /** The new shelves hold every one showing, in the same order, and more: only those need adding. */
    boolean grows(List<SearchBrain.Shelf> next) {
        if (showing.isEmpty() || next.size() <= showing.size() || lastQuery.isEmpty()) return false;
        int p = 0;
        for (SearchBrain.Shelf sh : next) if (p < showing.size() && same(showing.get(p), sh)) p++;
        return p == showing.size();
    }

    /**
     * Results as tvOS shelves: a heading over a row that scrolls sideways. Top Results, More
     * Like This and Apps for a search; Popular Films and Popular Series before one.
     */
    boolean entering;
    Runnable pendingShow;

    void show(List<SearchBrain.Shelf> shelves) {
        ui.removeCallbacks(pendingShow);
        if (entering && !shelves.isEmpty()) {
            pendingShow = () -> show(shelves);
            ui.postDelayed(pendingShow, Math.max(0, createdAt + 660 - android.os.SystemClock.uptimeMillis()));
            return;
        }
        if (!lastQuery.isEmpty() && shelves.size() == showing.size() && !shelves.isEmpty()) {
            boolean all = true;
            for (int i = 0; i < shelves.size(); i++) all &= same(showing.get(i), shelves.get(i));
            if (all) return;  // already showing exactly these
        }
        if (grows(shelves)) {
            // More Like This arriving under Top Results: the shelves already there stay as they
            // are (nothing jumps or blinks) and the new ones slide in where they belong.
            int[] delay = {0};
            int at = 0, p = 0;
            List<Result> added = new ArrayList<>();
            for (SearchBrain.Shelf sh : shelves) {
                if (p < showing.size() && same(showing.get(p), sh)) {
                    p++;
                    at++;
                    continue;
                }
                markDupes(sh);
                shelf(sh.heading, sh.items, delay, at++);
                added.addAll(sh.items);
            }
            showing = new ArrayList<>(shelves);
            prepareAmbient(added);
            return;
        }
        showing = new ArrayList<>(shelves);
        grid.removeAllViews();
        grid.setLayoutTransition(null);
        firstCard = null;
        if (lastQuery.isEmpty()) recentRow();
        int[] delay = {0};
        List<Result> all = new ArrayList<>();
        for (SearchBrain.Shelf sh : shelves) {
            markDupes(sh);
            shelf(sh.heading, sh.items, delay, -1);
            all.addAll(sh.items);
        }
        scroll.scrollTo(0, 0);
        // Backgrounds are got ready for results; before a search they load as you move to one.
        if (!lastQuery.isEmpty()) prepareAmbient(all);
        if (!all.isEmpty() && !listening) setStatus("");
        // The background follows the top result straight away (focus may still be on the mic after
        // a voice search), and clears when there's nothing to show.
        Result first = null;
        for (Result r : all) if (!"app".equals(r.type)) { first = r; break; }
        if (first != null && !lastQuery.isEmpty()) {
            ambientFor(first);
            // A spoken search: straight to its top result, ready to open with OK.
            if (spoken && firstCard != null) firstCard.requestFocus();
        } else {
            ambientFor = null;
            ambient.animate().cancel();
            ambient.animate().alpha(0f).setDuration(300).setInterpolator(MainActivity.EASE).start();
        }
        spoken = false;
    }

    /** Titles sharing a name in a shelf show their year (The Office, 2001 and 2005). */
    static void markDupes(SearchBrain.Shelf sh) {
        java.util.Map<String, Integer> n = new java.util.HashMap<>();
        for (Result r : sh.items) n.merge(SearchBrain.norm(r.title), 1, Integer::sum);
        for (Result r : sh.items) r.dupe = n.get(SearchBrain.norm(r.title)) > 1;
    }

    /** The search came from the microphone (results take focus). */
    boolean spoken;
    int rmsCount;
    float noiseFloor;
    View firstCard;

    void shelf(String heading, List<Result> items, int[] delay, int index) {
        if (items.isEmpty()) return;
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setClipChildren(false);
        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextColor(0xE6FFFFFF);
        h.setTypeface(Ui.font(this, "semibold"));
        Ui.textSize(h, 15);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.leftMargin = u(48);
        section.addView(h, hl);

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(u(48), u(16), u(48), 0);  // room for the lift at either end and on top
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        hs.addView(row);
        int gap = u(22);
        int w = (Ui.screen(this).x - 2 * u(48) - (COLS - 1) * gap) / COLS;
        // The cards on screen now; the rest follow a few at a time over the next frames, so a
        // page of results never stalls a frame on building eighty cards at once.
        int now = Math.min(items.size(), COLS + 1);
        for (int i = 0; i < now; i++) {
            View card = card(items.get(i), w);
            if (firstCard == null || grid.getChildCount() == 0 && i == 0) firstCard = ((ViewGroup) card).getChildAt(0);
            row.addView(card, cardParams(w, gap, i));
        }
        for (int from = now; from < items.size(); from += COLS) {
            int a = from, b = Math.min(items.size(), from + COLS);
            ui.post(() -> {
                if (!row.isAttachedToWindow()) return;  // replaced by newer results meanwhile
                for (int i = a; i < b; i++) row.addView(card(items.get(i), w), cardParams(w, gap, i));
            });
        }
        section.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.bottomMargin = u(18);
        grid.addView(section, index < 0 ? grid.getChildCount() : index, sl);
        // New results rise in shelf by shelf, each moving as one picture. While the page itself is
        // still fading in they're simply there, so its arrival stays smooth.
        if (android.os.SystemClock.uptimeMillis() - createdAt > 700) {
            section.setAlpha(0f);
            section.setTranslationY(u(14));
            section.animate().alpha(1f).translationY(0).setStartDelay(Math.min(delay[0]++, 4) * 70L).setDuration(460)
                    .setInterpolator(MainActivity.EASE).withLayer().start();
        }
    }

    static LinearLayout.LayoutParams cardParams(int w, int gap, int i) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
        if (i > 0) lp.leftMargin = gap;
        return lp;
    }

    /** Scrolls so the whole shelf holding v (heading to the title under the poster) is on screen. */
    void keepInView(View v) {
        View section = v;
        while (section != null && section.getParent() != grid) {
            section = section.getParent() instanceof View ? (View) section.getParent() : null;
        }
        if (section == null) return;
        int visible = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
        int top = section.getTop() - u(6), bottom = section.getBottom();
        int y = scroll.getScrollY();
        if (bottom - y > visible) y = bottom - visible;
        if (top < y) y = top;
        if (y != scroll.getScrollY()) scroll.smoothScrollTo(0, Math.max(0, y));
    }

    View card(Result r, int w) {
        boolean app = "app".equals(r.type);
        int h = app ? w * 3 / 5 : w * 3 / 2;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);

        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setFocusable(true);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0xFF2A2A30);
        ph.setCornerRadius(u(10));
        art.setBackground(ph);
        if (r.appBanner != null) art.setImageDrawable(r.appBanner);
        r.art = art;
        int radius = u(10);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        art.setClipToOutline(true);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0AFFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(radius);
        sheen.setStroke(Math.max(1, u(0.6f)), 0x55FFFFFF);
        sheen.setAlpha(0);
        art.setForeground(sheen);
        box.addView(art, new LinearLayout.LayoutParams(w, h));

        TextView name = new TextView(this);
        name.setText(r.title);
        name.setTextColor(Color.WHITE);
        name.setTypeface(Ui.font(this, "medium"));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.textSize(name, 11);
        name.setAlpha(0f);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(w, -2);
        nl.topMargin = u(12);
        box.addView(name, nl);

        TextView sub = new TextView(this);
        StringBuilder sb = new StringBuilder();
        if (app) sb.append("App");
        else if (r.role != null) {
            // An actor's or director's work: when, and who they played.
            sb.append(r.year == null ? "" : r.year);
            if (!r.role.isEmpty() && !"Director".equals(r.role)) sb.append(sb.length() > 0 ? " \u00b7 " : "").append("as ").append(r.role);
        } else {
            if (r.dupe && r.year != null && !r.year.isEmpty()) sb.append(r.year);
            String sc = ratings(r.imdb, r.tomato, r.certified);
            if (!sc.isEmpty()) sb.append(sb.length() > 0 ? " \u00b7 " : "").append(sc);
            for (Suggestions.Item it : r.offers) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(it.provider().name);
            }
            if (r.offers.isEmpty() ) sb.append(sb.length() > 0 ? " · " : "").append(r.rentBuy ? "Rent or Buy" : "Not Streaming");
        }
        sub.setText(sb);
        showIcons(sub);
        sub.setTextColor(0x8CFFFFFF);
        sub.setTypeface(Ui.font(this, "regular"));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.textSize(sub, 9);
        sub.setAlpha(0f);
        box.addView(sub, new LinearLayout.LayoutParams(w, -2));

        art.setOnFocusChangeListener((v, f) -> {
            float s = f ? 1.1f : 1f;
            art.setPivotX(w / 2f);
            art.setPivotY(h / 2f);
            art.animate().scaleX(s).scaleY(s).translationZ(f ? u(14) : 0).setDuration(f ? 340 : 260)
                    .setInterpolator(f ? MainActivity.LIFT : MainActivity.EASE).start();
            // Glass sheen across the focused poster, fading rather than switching.
            android.animation.ObjectAnimator.ofInt(sheen, "alpha", sheen.getAlpha(), f ? 255 : 0)
                    .setDuration(f ? 300 : 220).start();
            if (f) {
                ambientFor(r);
                keepInView(box);
            }
            name.animate().alpha(f ? 1f : 0f).translationY(f ? u(3) : 0).setDuration(260)
                    .setInterpolator(MainActivity.EASE).start();
            sub.animate().alpha(f ? 1f : 0f).translationY(f ? u(3) : 0).setDuration(260)
                    .setInterpolator(MainActivity.EASE).start();
        });
        art.setOnClickListener(v -> open(r));
        return box;
    }

    ImageView ambient;
    volatile String ambientFor;

    /**
     * Backgrounds ready to show, by picture address, prepared as soon as results appear: under
     * 2 MB each, so ten stay light on the TV's memory.
     */
    static final android.util.LruCache<String, Bitmap> AMBIENT = new android.util.LruCache<>(10);
    final ExecutorService ambientIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** The focused title's picture, never queued behind the ones being got ready. */
    final ExecutorService ambientNow = java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * The picture behind a result: the studio's full-size key art (TMDB) when there is one, else
     * JustWatch's 1920 px backdrop, at 1280 px with a light blur, so it's smooth, never blocky.
     */
    Bitmap ambientArt(Result r, String url) {
        Bitmap b = AMBIENT.get(url);
        if (b != null) return b;
        long t0 = android.os.SystemClock.uptimeMillis();
        String art = r == null || r.tmdbId <= 0 ? null : Suggestions.tmdbBackdrop(this, r.type, r.tmdbId);
        // Two thirds of the screen's width: drawn full screen, the smooth scaling gives it the
        // lightest softness, with no blurring to do and half the memory.
        int full = Ui.screen(this).x * 2 / 3;
        Bitmap src = art == null ? null : Suggestions.screenImage(this, art, full);
        if (src == null) src = Suggestions.screenImage(this, url, full);
        if (src == null) return null;
        AMBIENT.put(url, src);
        android.util.Log.i("SearchBg", (art != null ? "tmdb " : "jw ") + r.title + " " + src.getWidth() + "px in "
                + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
        return src;
    }

    /** Gets every shown result's background ready, first ones first. */
    void prepareAmbient(List<Result> rs) {
        List<Result> todo = new ArrayList<>();
        for (Result r : rs) {
            String u = r.backdrop != null ? r.backdrop : r.poster;
            if (u != null && AMBIENT.get(u) == null && todo.size() < 10) todo.add(r);
        }
        int gen = generation;
        ambientIo.execute(() -> {
            for (Result r : todo) {
                if (gen != generation) return;
                String u = r.backdrop != null ? r.backdrop : r.poster;
                if (u.equals(ambientFor)) continue;  // the one showing is being got by ambientNow
                ambientArt(r, u);
            }
        });
    }

    void ambientFor(Result r) {
        String url = r.backdrop != null ? r.backdrop : r.poster;
        if (url == null || url.equals(ambientFor)) return;
        ambientFor = url;
        ui.removeCallbacks(ambientRun);
        Bitmap ready = AMBIENT.get(url);
        if (ready != null) {
            // Ready: straight in, with a quick cross-fade.
            ambient.animate().cancel();
            ambient.setImageBitmap(ready);
            ambient.setAlpha(0.35f);
            ambient.animate().alpha(1f).setDuration(260).setInterpolator(MainActivity.EASE).start();
            return;
        }
        ambientRun = () -> ambientNow.execute(() -> {
            if (!url.equals(ambientFor)) return;  // focus has moved on
            // The studio's full-size key art (TMDB) when there is one, else JustWatch's 1920 px
            // picture: crisp at the screen's own width, under the page's dark shade.
            Bitmap bm = ambientArt(r, url);
            ui.post(() -> {
                if (bm == null || !url.equals(ambientFor)) return;
                ambient.animate().cancel();
                ambient.animate().alpha(0f).setDuration(180).setInterpolator(MainActivity.EASE).withEndAction(() -> {
                    ambient.setImageBitmap(bm);
                    ambient.animate().alpha(1f).setDuration(700).setInterpolator(MainActivity.EASE).start();
                }).start();
            });
        });
        ui.post(ambientRun);
    }

    Runnable ambientRun = () -> { };

    // ---------------------------------------------------------------- recent searches

    static final int RECENT = 6;

    /** Searches that led somewhere, newest first, kept on this TV only. */
    List<String> recents() {
        List<String> out = new ArrayList<>();
        for (String q : getSharedPreferences("search", MODE_PRIVATE).getString("recent", "").split("\n")) {
            if (!q.trim().isEmpty()) out.add(q);
        }
        return out;
    }

    void remember(String q) {
        if (q == null || q.trim().isEmpty()) return;
        List<String> l = recents();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).equalsIgnoreCase(q.trim())) l.remove(i);
        l.add(0, q.trim());
        while (l.size() > RECENT) l.remove(l.size() - 1);
        getSharedPreferences("search", MODE_PRIVATE).edit().putString("recent", String.join("\n", l)).apply();
    }

    /** Recent searches as glass pills above Popular Now: one press searches again. */
    void recentRow() {
        List<String> l = recents();
        if (l.isEmpty()) return;
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("Recent Searches");
        label.setTextColor(0x99FFFFFF);
        label.setTypeface(Ui.font(this, "semibold"));
        Ui.textSize(label, 12);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.rightMargin = u(14);
        row.addView(label, ll);
        for (String q : l) {
            Glass white = new Glass(u(16), u(0.6f), Glass.REGULAR);
            TextView pill = new TextView(this);
            pill.setText(q);
            pill.setTextColor(Color.WHITE);
            pill.setTypeface(Ui.font(this, "medium"));
            Ui.textSize(pill, 12);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(u(16), 0, u(16), 0);
            pill.setBackground(white);
            pill.setFocusable(true);
            // The text darkens with the plate as it fades, not ahead of it.
            android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
            white.tint = f -> pill.setTextColor((int) ev.evaluate(f, Color.WHITE, 0xFF111114));
            pill.setOnFocusChangeListener((v, has) -> {
                Glass.focus(white, has);
                Glass.lift(v, has, 1.08f, u(6));
                if (has) keepInView(v);
            });
            pill.setOnClickListener(v -> {
                field.setText(q);
                field.setSelection(field.length());
                ui.removeCallbacks(debounced);
                search(q);
            });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, u(32));
            pl.rightMargin = u(10);
            row.addView(pill, pl);
        }
        row.setPadding(u(48), u(4), u(48), u(4));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = u(22);
        grid.addView(row, rl);
    }

    /** An app opens; a show or film opens its page, with the synopsis and every way to watch it. */
    void open(Result r) {
        remember(lastQuery);
        if (r.appLaunch != null) {
            Suggestions.start(this, r.appLaunch);
            return;
        }
        if (r.id == null && r.tmdbId > 0) {
            // An actor's film from TMDB: find it on JustWatch for its page (where to watch it).
            setStatus("Opening\u2026");
            io.execute(() -> {
                Result found = brain.resolve(r);
                ui.post(() -> {
                    setStatus("");
                    if (found != null) {
                        found.backdrop = r.backdrop != null ? r.backdrop : found.backdrop;
                        open(found);
                    } else {
                        field.setText(r.title);
                        ui.removeCallbacks(debounced);
                        search(r.title);
                    }
                });
            });
            return;
        }
        startActivity(new Intent(this, DetailActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra("id", r.id).putExtra("title", r.title)
                .putExtra("backdrop", r.backdrop));
        overridePendingTransition(R.anim.page_in, R.anim.hold);
    }
}

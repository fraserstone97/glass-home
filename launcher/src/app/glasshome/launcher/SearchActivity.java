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
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

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
    static final int COLS = 6, MAX = 18, REQ_MIC = 7;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    EditText field;
    TextView status;
    MicView mic;
    GradientDrawable barRim;
    android.animation.ValueAnimator barAnim;
    float barGlow;
    LinearLayout grid;
    SpeechRecognizer recognizer;
    boolean listening;
    int generation;
    final Runnable debounced = () -> search(field.getText().toString().trim());

    /** One search result: a title and the UK services (installed on this TV) that stream it. */
    static final class Result {
        String title, year, type, poster, id, backdrop;
        double imdb;
        int tomato = -1;
        boolean certified, rentBuy;
        final List<Suggestions.Item> offers = new ArrayList<>();
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
        int size = Math.round(t.getTextSize() * 1.05f);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != FRESH && ch != ROTTEN && ch != CERTIFIED) continue;
            android.graphics.drawable.Drawable d = icon(t.getContext(), ch, size);
            d.setBounds(0, 0, size, size);
            sp.setSpan(new android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                    i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        t.setText(sp);
    }

    /**
     * The icon artwork in res/drawable-nodpi (rt_fresh, rt_certified, rt_rotten) when it's there;
     * otherwise a drawn tomato or splat. Certified falls back to the fresh tomato.
     */
    static android.graphics.drawable.Drawable icon(android.content.Context c, char kind, int size) {
        String[] names = kind == CERTIFIED ? new String[]{"rt_certified", "rt_fresh"}
                : kind == FRESH ? new String[]{"rt_fresh"} : new String[]{"rt_rotten"};
        for (String n : names) {
            int id = c.getResources().getIdentifier(n, "drawable", c.getPackageName());
            if (id != 0) return c.getDrawable(id);
        }
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), tomatoIcon(kind != ROTTEN, size * 2));
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

    LinearLayout.LayoutParams sideMargins(LinearLayout.LayoutParams lp) {
        lp.leftMargin = u(48);
        lp.rightMargin = u(48);
        return lp;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF15151B, 0xFF000000});
        root.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, u(34), 0, 0);
        col.setClipChildren(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("Search");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Ui.font(this, "display"));
        Ui.textSize(title, 30);
        col.addView(title, sideMargins(new LinearLayout.LayoutParams(-2, -2)));

        // Glass search field: mic button + text.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(u(2), 0, u(16), 0);
        bar.setBackground(glassBar());
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, u(44));
        barLp.topMargin = u(14);
        col.addView(bar, sideMargins(barLp));

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
        bar.addView(field, fLp);

        status = new TextView(this);
        status.setTextColor(0x99FFFFFF);
        status.setTypeface(Ui.font(this, "semibold"));
        Ui.textSize(status, 13);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-2, -2);
        sLp.topMargin = u(18);
        sLp.bottomMargin = u(6);
        col.addView(status, sideMargins(sLp));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        // Full screen width with the page margin as padding, so a lifted poster at either end of a
        // row draws into the margin instead of being clipped.
        scroll.setPadding(u(48), u(18), u(48), u(40));
        scroll.setVerticalScrollBarEnabled(false);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        scroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        setupRecognizer();
        search("");
        if (isVoiceIntent(getIntent())) ui.postDelayed(this::startListening, 250);
        else mic.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (isVoiceIntent(i)) startListening();
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
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { setListening(true, "Listening…"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float db) {
                mic.level = Math.max(0f, Math.min(1f, (db + 2f) / 12f));
            }
            @Override public void onBufferReceived(byte[] buf) { }
            @Override public void onEndOfSpeech() { setListening(false, "Searching…"); }
            @Override public void onError(int err) {
                setListening(false, err == SpeechRecognizer.ERROR_NO_MATCH || err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? "Didn\u2019t catch that. Try again."
                        : err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "Microphone access is off."
                        : "Voice isn\u2019t available. Type instead.");
            }
            @Override public void onResults(Bundle r) {
                setListening(false, null);
                List<String> words = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (words != null && !words.isEmpty()) {
                    field.setText(words.get(0));
                    field.setSelection(field.length());
                    ui.removeCallbacks(debounced);
                    search(words.get(0));
                }
            }
            @Override public void onPartialResults(Bundle r) {
                List<String> words = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (words != null && !words.isEmpty() && !words.get(0).isEmpty()) {
                    status.setText("“" + words.get(0) + "”");  // live words: no fade
                }
            }
            @Override public void onEvent(int t, Bundle p) { }
        });
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
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        mic.requestFocus();
        setListening(true, "Listening…");
        recognizer.startListening(i);
    }

    void stopListening() {
        if (recognizer != null && listening) recognizer.cancel();
        setListening(false, null);
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
     * Liquid Glass search field: a translucent body brighter at the top, a specular rim, and a
     * soft red rim that fades in while listening.
     */
    android.graphics.drawable.LayerDrawable glassBar() {
        float r = u(22);
        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x30FFFFFF, 0x1AFFFFFF, 0x22FFFFFF});
        body.setCornerRadius(r);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26FFFFFF, 0x00FFFFFF});
        // Only the top half: rounded on top with the bar's own radius, square below, so its
        // corners follow the bar's curve exactly instead of poking out.
        sheen.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        barRim = new GradientDrawable();
        barRim.setCornerRadius(r);
        barRim.setColor(0);
        barRim.setStroke(Math.max(1, u(0.75f)), 0x40FFFFFF);
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(
                new Drawable[]{body, sheen, barRim});
        l.setLayerInsetBottom(1, u(22));
        return l;
    }

    void animateBar(float to) {
        if (barAnim != null) barAnim.cancel();
        barAnim = android.animation.ValueAnimator.ofFloat(barGlow, to);
        barAnim.setDuration(420);
        barAnim.setInterpolator(MainActivity.EASE);
        android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        barAnim.addUpdateListener(a -> {
            barGlow = (float) a.getAnimatedValue();
            barRim.setStroke(Math.max(1, Math.round(u(0.75f) + barGlow * u(0.75f))),
                    (int) ev.evaluate(barGlow, 0x40FFFFFF, 0x99FF6B60));
        });
        barAnim.start();
    }

    static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Round mic button drawn every frame while it animates: focus and listening ease in with
     * Apple's curve, and the voice level drives a soft halo that is low-pass filtered so it
     * breathes instead of jittering. It never grows past its own bounds, so nothing overlaps.
     */
    final class MicView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        final RectF rect = new RectF();
        float focusT, listenT, level, shown, phase;
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
            if (!on) level = 0f;
            listenAnim = android.animation.ValueAnimator.ofFloat(listenT, on ? 1f : 0f);
            listenAnim.setDuration(380);
            listenAnim.setInterpolator(MainActivity.EASE);
            listenAnim.addUpdateListener(a -> {
                listenT = (float) a.getAnimatedValue();
                invalidate();
            });
            listenAnim.start();
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, max = Math.min(cx, cy);
            float base = max * 0.76f;
            // Ease the measured level towards its target, plus a slow idle breath while listening.
            shown += (level - shown) * 0.12f;
            phase += 0.045f;
            float breath = listening ? 0.5f + 0.5f * (float) Math.sin(phase) : 0f;
            float halo = listenT * (0.35f + 0.65f * Math.max(shown, breath * 0.25f));
            if (halo > 0.01f) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(Math.round(70 * halo), 255, 69, 58));
                c.drawCircle(cx, cy, base + (max - base) * halo, p);
            }
            int idle = 0x2EFFFFFF;
            int fill = (int) ev.evaluate(focusT, idle, Color.WHITE);
            fill = (int) ev.evaluate(listenT, fill, 0xFFFF453A);
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            c.drawCircle(cx, cy, base * mix(1f, 1.04f, focusT), p);
            if (focusT < 1f && listenT < 1f) {  // glass rim on the resting button
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, u(0.6f)));
                p.setColor(Color.argb(Math.round(70 * (1f - Math.max(focusT, listenT))), 255, 255, 255));
                c.drawCircle(cx, cy, base, p);
            }
            int ink = (int) ev.evaluate(focusT, Color.WHITE, 0xFF111114);
            ink = (int) ev.evaluate(listenT, ink, Color.WHITE);
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
            if (listening || Math.abs(level - shown) > 0.003f || (listenT > 0f && listenT < 1f)) {
                postInvalidateOnAnimation();
            }
        }
    }

    // ---------------------------------------------------------------- search

    static final String JW_SEARCH = "query S($country: Country!, $language: Language!, $first: Int!, $filter: TitleFilter, $sort: PopularTitlesSorting!) {"
            + " popularTitles(country: $country, filter: $filter, first: $first, sortBy: $sort) {"
            + " edges { node { id objectType content(country: $country, language: $language) {"
            + " title originalReleaseYear posterUrl backdrops { backdropUrl } scoring { imdbScore tomatoMeter certifiedFresh } }"
            + " offers(country: $country, platform: WEB) { monetizationType standardWebURL package { technicalName } } } } } }";

    /** What is being searched for now ("" while the field is empty). */
    String lastQuery = "";

    void search(String q) {
        lastQuery = q;
        int gen = ++generation;
        if (!listening) setStatus(q.isEmpty() ? "Popular Now" : "Searching…");
        List<Result> apps = q.isEmpty() ? new ArrayList<>() : matchApps(q);
        io.execute(() -> {
            List<Result> titles = new ArrayList<>();
            String err = null;
            try {
                titles = fetch(q);
            } catch (Exception e) {
                err = "Can\u2019t connect. Check your internet connection.";
            }
            List<Result> all = new ArrayList<>(apps);
            all.addAll(titles);
            for (Result r : titles) {
                Bitmap bm = Suggestions.image(this, r.poster, "", u(150));
                if (gen != generation) return;
                if (bm != null) r.appBanner = new android.graphics.drawable.BitmapDrawable(getResources(), bm);
            }
            String msg = err;
            ui.post(() -> {
                if (gen != generation) return;
                if (msg != null && all.isEmpty()) setStatus(msg);
                else if (!listening) setStatus(all.isEmpty() ? "No results for “" + q + "”"
                        : q.isEmpty() ? "Popular Now" : "Results for “" + q + "”");
                show(all);
            });
        });
    }

    /** Installed apps whose name contains the query. */
    List<Result> matchApps(String q) {
        List<Result> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        String lq = q.toLowerCase(Locale.UK);
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            String label = String.valueOf(r.loadLabel(pm));
            if (!label.toLowerCase(Locale.UK).contains(lq) || r.activityInfo.packageName.equals(getPackageName())) continue;
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

    /** Popular titles on your UK services, or every title matching the query. */
    List<Result> fetch(String q) throws Exception {
        List<Suggestions.Provider> mine = new ArrayList<>();
        JSONArray pkgs = new JSONArray();
        for (Suggestions.Provider p : Suggestions.PROVIDERS) {
            if (Suggestions.installed(this, p.pkg)) {
                mine.add(p);
                pkgs.put(p.jwShort);
            }
        }
        JSONObject filter = new JSONObject();
        if (!q.isEmpty()) filter.put("searchQuery", q);
        else {
            // Popular Now follows the carousel's rules: trending, recent, well rated, not for children.
            int thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            filter.put("packages", pkgs).put("monetizationTypes", new JSONArray().put("FLATRATE").put("FREE").put("ADS"))
                    .put("releaseYear", new JSONObject().put("min", thisYear - 2))
                    .put("imdbScore", new JSONObject().put("min", Suggestions.MIN_SCORE))
                    .put("excludeGenres", new JSONArray().put("fml").put("ani"))
                    // The carousel's audience rule too (Suggestions.NOT_UK_AUDIENCE).
                    .put("excludeProductionCountries", Suggestions.NOT_UK_AUDIENCE);
        }
        JSONObject vars = new JSONObject().put("country", "GB").put("language", "en")
                .put("first", q.isEmpty() ? MAX : 30).put("filter", filter).put("sort", q.isEmpty() ? "TRENDING" : "POPULAR");
        JSONObject res = Suggestions.post("https://apis.justwatch.com/graphql",
                new JSONObject().put("query", JW_SEARCH).put("variables", vars).toString());
        JSONArray edges = res.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < edges.length() && out.size() < MAX; i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            JSONObject ct = n.getJSONObject("content");
            Result r = new Result();
            r.title = Suggestions.cleanTitle(ct.optString("title"));
            r.id = n.optString("id");
            JSONArray bds = ct.optJSONArray("backdrops");
            if (bds != null && bds.length() > 0) {
                r.backdrop = "https://images.justwatch.com" + bds.getJSONObject(0).optString("backdropUrl")
                        .replace("{profile}", "s1920").replace("{format}", "jpg");
            }
            int y = ct.optInt("originalReleaseYear", 0);
            r.year = y > 0 ? String.valueOf(y) : "";
            JSONObject sc = ct.optJSONObject("scoring");
            if (sc != null) {
                r.imdb = sc.optDouble("imdbScore", 0);
                if (!sc.isNull("tomatoMeter")) r.tomato = sc.optInt("tomatoMeter", -1);
                r.certified = sc.optBoolean("certifiedFresh", false);
            }
            r.type = "SHOW".equals(n.optString("objectType")) ? "tv" : "movie";
            String poster = ct.optString("posterUrl", "");
            r.poster = poster.isEmpty() ? null
                    : "https://images.justwatch.com" + poster.replace("{profile}", "s332").replace("{format}", "jpg");
            JSONArray offers = n.optJSONArray("offers");
            for (Suggestions.Provider p : mine) {
                for (int k = 0; offers != null && k < offers.length(); k++) {
                    JSONObject o = offers.getJSONObject(k);
                    String m = o.optString("monetizationType");
                    if (!p.jwTech.equals(o.getJSONObject("package").optString("technicalName"))) continue;
                    if (!m.equals("FLATRATE") && !m.equals("FREE") && !m.equals("ADS")) continue;
                    Suggestions.Item it = new Suggestions.Item();
                    it.title = r.title;
                    it.type = r.type;
                    it.providerId = p.id;
                    it.link = o.optString("standardWebURL");
                    r.offers.add(it);
                    break;
                }
            }
            for (int k = 0; offers != null && k < offers.length(); k++) {
                String m = offers.getJSONObject(k).optString("monetizationType");
                if (m.equals("RENT") || m.equals("BUY")) r.rentBuy = true;
            }
            // Popular Now is only what you can watch on your services; a search finds everything
            // (Morbius, rent or buy only), and its title page lists where to rent or buy it.
            if (r.offers.isEmpty() && q.isEmpty()) continue;
            // JustWatch lists each run of a programme separately (Doctor Who 1963, 2005, 2023):
            // show it once, with every service that has any of them.
            String key = norm(r.title);
            Result same = null;
            for (Result o : out) if (norm(o.title).equals(key)) same = o;
            if (same == null) {
                out.add(r);
                continue;
            }
            for (Suggestions.Item it : r.offers) {
                boolean has = false;
                for (Suggestions.Item o : same.offers) has |= o.providerId == it.providerId;
                if (!has) same.offers.add(it);
            }
        }
        if (q.isEmpty()) return out;
        // Titles that contain what was asked for come first; loose matches (Doctor Sleep for
        // "doctor who") only fill in when there are few real ones.
        String nq = norm(q);
        List<Result> close = new ArrayList<>(), loose = new ArrayList<>();
        for (Result r : out) {
            String t = norm(r.title);
            boolean all = true;
            for (String word : nq.split(" ")) all &= t.contains(word);
            (t.equals(nq) || t.startsWith(nq) || all ? close : loose).add(r);
        }
        if (close.size() < COLS) close.addAll(loose.subList(0, Math.min(loose.size(), COLS - close.size())));
        return close;
    }

    /** Lower case, no punctuation, a leading "the" and trailing year dropped: for matching titles. */
    static String norm(String t) {
        String s = t.toLowerCase(Locale.UK).replaceAll("\\(\\d{4}\\)", " ").replace("&", " and ")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return s.startsWith("the ") ? s.substring(4) : s;
    }

    // ---------------------------------------------------------------- results grid

    void show(List<Result> results) {
        grid.removeAllViews();
        grid.setLayoutTransition(null);
        if (lastQuery.isEmpty()) recentRow();
        int gap = u(22);
        int w = (Ui.screen(this).x - 2 * u(48) - (COLS - 1) * gap) / COLS;
        LinearLayout row = null;
        for (int i = 0; i < results.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.bottomMargin = u(12);
                grid.addView(row, rl);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
            if (i % COLS != 0) lp.leftMargin = gap;
            View card = card(results.get(i), w);
            card.setAlpha(0f);
            card.setTranslationY(u(10));
            card.animate().alpha(1f).translationY(0).setStartDelay(Math.min(i, 12) * 28L).setDuration(420)
                    .setInterpolator(MainActivity.EASE).withLayer().start();
            row.addView(card, lp);
        }
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
        else {
            sb.append(ratings(r.imdb, r.tomato, r.certified));
            for (Suggestions.Item it : r.offers) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(it.provider().name);
            }
            if (r.offers.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(r.rentBuy ? "Rent or Buy" : "Not Streaming");
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
            name.animate().alpha(f ? 1f : 0f).translationY(f ? u(3) : 0).setDuration(260)
                    .setInterpolator(MainActivity.EASE).start();
            sub.animate().alpha(f ? 1f : 0f).translationY(f ? u(3) : 0).setDuration(260)
                    .setInterpolator(MainActivity.EASE).start();
        });
        art.setOnClickListener(v -> open(r));
        return box;
    }

    /** An app opens; a show or film opens its page, with the synopsis and every way to watch it. */
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
        label.setText("Recent");
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
            pill.setOnFocusChangeListener((v, has) -> {
                Glass.focus(white, has);
                pill.setTextColor(has ? 0xFF111114 : Color.WHITE);
                Glass.lift(v, has, 1.08f, u(6));
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
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = u(20);
        grid.addView(row, rl);
    }

    void open(Result r) {
        remember(lastQuery);
        if (r.appLaunch != null) {
            Suggestions.start(this, r.appLaunch);
            return;
        }
        startActivity(new Intent(this, DetailActivity.class).putExtra("id", r.id).putExtra("title", r.title)
                .putExtra("backdrop", r.backdrop));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}

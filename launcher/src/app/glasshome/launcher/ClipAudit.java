package app.glasshome.launcher;

import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A development check for one kind of glitch: something that fits inside its containers at rest
 * but is cut off while it moves (a focus lift, a lean at a wall, a pop, a lifted shadow, a view
 * drawn into a layer that's smaller than it).
 *
 * Off unless asked for with `adb shell setprop log.tag.GlassClips DEBUG` (then reopen the page):
 * every frame, each view that has moved is followed up through every container that clips it
 * (a parent that clips its children, padding, an outline, clip bounds, a layer, a fade drawn
 * through its own buffer, the window). An
 * edge that crosses one by more than a pixel, where it doesn't at rest, is logged under GlassClips
 * once it fits again (or goes), with how far it went, the tightest clip, and what had focus: CLIP
 * inside the screen, EDGE at the screen's own edge (mostly things sliding in from off it). A view
 * whose parent is cut on the same side by the same clip isn't logged again. Focus moves are logged
 * too, so a script pressing the remote can tell where it is.
 */
final class ClipAudit {
    static final String TAG = "GlassClips";
    /** Past this (px) an edge counts as cut; within NEAR it fits. */
    static final float OVER = 1.5f, NEAR = 0.5f;
    static final String[] SIDES = {"left", "top", "right", "bottom"};

    static void watch(Activity a) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return;
        View decor = a.getWindow().getDecorView();
        ClipAudit audit = new ClipAudit(a.getClass().getSimpleName(), decor);
        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            audit.frame();
            return true;
        });
        decor.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (now != null) Log.d(TAG, "FOCUS " + audit.page + " " + describe(now));
        });
        Log.d(TAG, "WATCH " + audit.page);
    }

    /** One edge of one view past a clip, while it lasts. */
    static final class Cut {
        View clip;
        String kind, focus;
        float most;
        long frame;
        boolean inherited;
    }

    final String page;
    final View decor;
    final float density;
    /** Each view's cut edges, by side (0 left, 1 top, 2 right, 3 bottom). */
    final Map<View, Cut[]> cuts = new IdentityHashMap<>();
    final RectF now = new RectF(), rest = new RectF();
    final Rect bounds = new Rect();
    final Outline outline = new Outline();
    /** This frame's worst crossing on each side, and where. */
    final float[] over = new float[4];
    final View[] overClip = new View[4];
    final String[] overKind = new String[4];
    long frames;

    ClipAudit(String page, View decor) {
        this.page = page;
        this.decor = decor;
        density = decor.getResources().getDisplayMetrics().density;
    }

    void frame() {
        frames++;
        visit(decor);
        // Anything that stopped being drawn while cut (closed, hidden, gone) is reported as it was.
        if (frames % 30 == 0) {
            for (Iterator<Map.Entry<View, Cut[]>> it = cuts.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<View, Cut[]> e = it.next();
                boolean any = false;
                for (int s = 0; s < 4; s++) {
                    Cut c = e.getValue()[s];
                    if (c == null) continue;
                    if (frames - c.frame > 30) {
                        report(e.getKey(), s, c, "until it went");
                        e.getValue()[s] = null;
                    } else {
                        any = true;
                    }
                }
                if (!any) it.remove();
            }
        }
    }

    void visit(View v) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.02f) return;
        if (v != decor && v.getWidth() > 0 && v.getHeight() > 0) check(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) visit(g.getChildAt(i));
        }
    }

    /** Follows v's drawn rectangle, as it is now and as it would be at rest, up to the window. */
    void check(View v) {
        now.set(0, 0, v.getWidth(), v.getHeight());
        float z = v.getZ();
        boolean moved = false;
        if (z >= density && casts(v)) {
            // A lifted shadow spreads about z each side and a little further below.
            now.inset(-z, -z);
            now.bottom += z / 2;
            moved = true;
        }
        rest.set(0, 0, v.getWidth(), v.getHeight());
        Cut[] mine = cuts.get(v);
        for (int s = 0; s < 4; s++) {
            over[s] = -1;
            overClip[s] = null;
        }
        View c = v;
        ViewParent p = v.getParent();
        while (p instanceof View) {
            View a = (View) p;
            Matrix m = c.getMatrix();
            if (!m.isIdentity()) {
                m.mapRect(now);
                moved = true;
            }
            float dx = c.getLeft() - a.getScrollX(), dy = c.getTop() - a.getScrollY();
            now.offset(dx, dy);
            rest.offset(dx, dy);
            if (moved || mine != null) {
                ViewParent up = a.getParent();
                // A fading view that overlaps itself is drawn into a buffer its own size first.
                boolean clipsSelf = !(up instanceof ViewGroup) || ((ViewGroup) up).getClipChildren()
                        || a.getClipToOutline() || a.getLayerType() != View.LAYER_TYPE_NONE
                        || (a.getAlpha() < 1f && a.hasOverlappingRendering());
                if (clipsSelf) test(a, "bounds", 0, 0, a.getWidth(), a.getHeight());
                if (a instanceof ViewGroup && ((ViewGroup) a).getClipToPadding()
                        && (a.getPaddingLeft() | a.getPaddingTop() | a.getPaddingRight() | a.getPaddingBottom()) != 0) {
                    test(a, "padding", a.getPaddingLeft(), a.getPaddingTop(),
                            a.getWidth() - a.getPaddingRight(), a.getHeight() - a.getPaddingBottom());
                }
                if (a.getClipBounds(bounds)) test(a, "clip bounds", bounds.left, bounds.top, bounds.right, bounds.bottom);
            }
            c = a;
            p = a.getParent();
        }
        if (moved || mine != null) settle(v, mine);
    }

    /** Whether v casts a shadow at all: only with an outline to cast it from (glass has none). */
    boolean casts(View v) {
        if (v.getOutlineProvider() == null) return false;
        outline.setEmpty();
        v.getOutlineProvider().getOutline(v, outline);
        return !outline.isEmpty() && outline.getAlpha() > 0f;
    }

    void test(View a, String kind, float l, float t, float r, float b) {
        side(0, a, kind, l - now.left, l - rest.left);
        side(1, a, kind, t - now.top, t - rest.top);
        side(2, a, kind, now.right - r, rest.right - r);
        side(3, a, kind, now.bottom - b, rest.bottom - b);
    }

    void side(int s, View a, String kind, float by, float byAtRest) {
        if (byAtRest > NEAR) return;  // cut at rest too: scrolled out of view, or meant to bleed
        if (by > over[s]) {
            over[s] = by;
            overClip[s] = a;
            overKind[s] = kind;
        }
    }

    /** This frame's crossings against what was already cut: a new cut, a deeper one, or the end. */
    void settle(View v, Cut[] mine) {
        for (int s = 0; s < 4; s++) {
            Cut cut = mine == null ? null : mine[s];
            if (over[s] > OVER) {
                if (cut == null) {
                    if (mine == null) cuts.put(v, mine = new Cut[4]);
                    mine[s] = cut = new Cut();
                    cut.clip = overClip[s];
                    cut.kind = overKind[s];
                    // Its parent cut the same way by the same clip already says it.
                    Cut[] up = v.getParent() instanceof View ? cuts.get((View) v.getParent()) : null;
                    cut.inherited = up != null && up[s] != null && up[s].clip == cut.clip;
                }
                cut.frame = frames;
                if (over[s] > cut.most) {
                    cut.most = over[s];
                    View f = decor.findFocus();
                    cut.focus = f == null ? "nothing" : f == v ? "itself" : describe(f);
                }
            } else if (cut != null) {
                cut.frame = frames;
                if (over[s] <= NEAR) {
                    report(v, s, cut, "and fitted again");
                    mine[s] = null;
                }
            }
        }
        if (mine != null && mine[0] == null && mine[1] == null && mine[2] == null && mine[3] == null) cuts.remove(v);
    }

    void report(View v, int s, Cut c, String how) {
        if (c.inherited) return;
        // At the screen's own edge (the clip's edge on that side is the screen's): something
        // coming on or going off, as a row scrolling a card in from past the edge.
        int[] at = new int[2];
        c.clip.getLocationOnScreen(at);
        boolean pad = c.kind.equals("padding");
        float l = at[0] + (pad ? c.clip.getPaddingLeft() : 0), t = at[1] + (pad ? c.clip.getPaddingTop() : 0);
        float r = at[0] + c.clip.getWidth() - (pad ? c.clip.getPaddingRight() : 0);
        float b = at[1] + c.clip.getHeight() - (pad ? c.clip.getPaddingBottom() : 0);
        boolean edge = !c.kind.equals("clip bounds") && (s == 0 ? l <= 0 : s == 1 ? t <= 0
                : s == 2 ? r >= decor.getWidth() : b >= decor.getHeight());
        Log.d(TAG, (edge ? "EDGE " : "CLIP ") + page + " " + describe(v) + " | " + SIDES[s] + " edge cut by "
                + Math.round(c.most) + " px (" + String.format(java.util.Locale.UK, "%.1f", c.most / density)
                + " dp) at the " + c.kind + " of " + describe(c.clip) + " " + how + " | focus: " + c.focus);
    }

    /** A view in a line: its kind, its words if any, where it is on screen, and its parents. */
    static String describe(View v) {
        StringBuilder s = new StringBuilder(v.getClass().getSimpleName());
        CharSequence words = v instanceof TextView ? ((TextView) v).getText() : v.getContentDescription();
        if (words != null && words.length() > 0) {
            String w = words.toString().replace('\n', ' ');
            s.append(" \"").append(w.length() > 24 ? w.substring(0, 24) + "…" : w).append('"');
        }
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        s.append(" @").append(at[0]).append(',').append(at[1]).append(' ')
                .append(v.getWidth()).append('x').append(v.getHeight());
        ArrayList<String> path = new ArrayList<>();
        View c = v;
        for (int i = 0; i < 4 && c.getParent() instanceof ViewGroup; i++) {
            ViewGroup g = (ViewGroup) c.getParent();
            path.add(0, g.getClass().getSimpleName() + "[" + g.indexOfChild(c) + "]");
            c = g;
        }
        s.append(" in ").append(String.join(">", path));
        return s.toString();
    }
}

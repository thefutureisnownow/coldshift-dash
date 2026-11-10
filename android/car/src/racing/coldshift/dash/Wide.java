package racing.coldshift.dash;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;

/**
 * Pages laid out across the whole width of a screen wider than 16:9 (the Galaxy A22's 20:9 is 1600 wide
 * in layout pixels, the G7 and Y625 1280). A page is drawn for 1280 and then widened: what sits at the
 * right (buttons at a panel's right edge) moves right by the extra width, what spans (a panel, a list) gets
 * wider, and the rest stays. Draw code says which with rx() and sx(); the pages' fixed rectangles are made
 * with right() and stretch(), and are moved here when the width changes (one window, so one width).
 */
final class Wide {
    static final float BASE = 1280;

    /** The layout width now (BASE on a 16:9 screen). */
    static float W = BASE;

    private static final List<RectF[]> RIGHT = new ArrayList<RectF[]>(), STRETCH = new ArrayList<RectF[]>(),
        CENTRE = new ArrayList<RectF[]>();

    private Wide() {}

    /** The extra width over BASE. */
    static float d() { return W - BASE; }

    /** A right-anchored x, given for a 1280-wide layout. */
    static float rx(float x) { return x + d(); }

    /** The middle of a span from l to a right-anchored r (centring something in a widened panel). */
    static float mid(float l, float r) { return (l + rx(r)) / 2; }

    /** A rectangle at the right: it moves with the right edge. */
    static RectF right(RectF r) {
        RIGHT.add(new RectF[] {r, new RectF(r)});
        place();
        return r;
    }

    /** A rectangle that spans to the right edge: it widens. */
    static RectF stretch(RectF r) {
        STRETCH.add(new RectF[] {r, new RectF(r)});
        place();
        return r;
    }

    /** A rectangle centred on the page: it moves by half the extra width. */
    static RectF centre(RectF r) {
        CENTRE.add(new RectF[] {r, new RectF(r)});
        place();
        return r;
    }

    /** Called by UI with the width the page now has. */
    static void setWidth(float w) {
        if (w == W) return;
        W = w;
        place();
    }

    private static void place() {
        float d = d();
        for (RectF[] p : RIGHT) p[0].set(p[1].left + d, p[1].top, p[1].right + d, p[1].bottom);
        for (RectF[] p : STRETCH) p[0].set(p[1].left, p[1].top, p[1].right + d, p[1].bottom);
        for (RectF[] p : CENTRE) p[0].set(p[1].left + d / 2, p[1].top, p[1].right + d / 2, p[1].bottom);
    }
}

package racing.coldshift.dash;

import android.graphics.Canvas;
import android.view.View;

/**
 * The 1280x720 layout every page and overlay draws in, fitted to the screen without stretching: scaled by
 * the same amount both ways and centred. A 16:9 screen (the G7 at 1280x720, the Y625 at 854x480) is filled
 * exactly; a wider one (the Galaxy A22's 1600x720, 20:9) gets margins at the sides, which the background,
 * title bar and tab bar run into (extra()).
 */
final class Screen {
    static final float W = 1280, H = 720;

    private Screen() {}

    /** Screen pixels per layout pixel. */
    static float k(View v) {
        return Math.min(Math.max(1, v.getWidth()) / W, Math.max(1, v.getHeight()) / H);
    }

    /** Where the layout's left edge is on the view, in its pixels. */
    static float ox(View v) { return (v.getWidth() - W * k(v)) / 2; }

    static float oy(View v) { return (v.getHeight() - H * k(v)) / 2; }

    /** The margin at each side, in layout pixels (0 on a 16:9 screen): x from -extra() to W + extra() shows. */
    static float extra(View v) { return ox(v) / k(v); }

    /** Draw in layout pixels from here on (inside a save/restore). */
    static void begin(Canvas c, View v) {
        c.translate(ox(v), oy(v));
        float k = k(v);
        c.scale(k, k);
    }

    /** A touch on the view, in layout pixels. */
    static float x(View v, float px) { return (px - ox(v)) / k(v); }

    static float y(View v, float py) { return (py - oy(v)) / k(v); }
}

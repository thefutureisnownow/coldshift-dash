package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.SystemClock;

/**
 * How every list in the app scrolls, in one place: a finger drags it, a flick carries it on and it slows,
 * it stops at its ends, and a finger that hardly moved was a tap. A page keeps one of these per list:
 *
 *   draw:   scroll.range(contentHeight, viewHeight); scroll.step(); then draw the rows at top - scroll.pos
 *   touch:  down(y) when the finger lands in the list, move(y) while it is down, and up() when it lifts,
 *           which says whether that was a tap (then the row at y + scroll.pos was meant)
 *
 * All in layout pixels (Screen). The page is redrawn by itself while a flick runs.
 */
final class Scroll {
    private static final float SLOP = 12;           // further than this and it is a drag
    private static final float MIN_FLICK = 250;     // px/s: slower than this just stops
    private static final float SLOWING = 0.996f;    // of the speed kept each millisecond

    private final Main act;
    /** How far down the list is scrolled, px. */
    float pos;
    private float max, downY, lastY, vel;
    private long lastAt, stepAt;
    private boolean down, moved;

    Scroll(Main a) { act = a; }

    /** The list's size and its window's, each draw: keeps the position inside them. */
    void range(float content, float view) {
        max = Math.max(0, content - view);
        pos = Math.max(0, Math.min(max, pos));
    }

    /** Back to the top (another folder, another tab). */
    void reset() {
        pos = 0;
        vel = 0;
        down = moved = false;
    }

    void down(float y) {
        down = true;
        moved = false;
        vel = 0;                                    // a touch stops a list that is still moving
        downY = lastY = y;
        lastAt = SystemClock.uptimeMillis();
    }

    void move(float y) {
        if (!down) return;
        if (Math.abs(y - downY) > SLOP) moved = true;
        if (moved) pos = Math.max(0, Math.min(max, pos - (y - lastY)));
        long now = SystemClock.uptimeMillis();
        if (now > lastAt) vel = 0.5f * vel + 0.5f * (-(y - lastY) * 1000f / (now - lastAt));
        lastAt = now;
        lastY = y;
    }

    /** The finger lifted. True when it was a tap: it never moved further than a tap may. */
    boolean up() {
        boolean tap = down && !moved;
        if (!moved || Math.abs(vel) < MIN_FLICK || SystemClock.uptimeMillis() - lastAt > 120) vel = 0;
        down = false;
        stepAt = SystemClock.uptimeMillis();
        return tap;
    }

    /** The touch was taken away (another view took it): no tap, no flick. */
    void cancel() {
        down = false;
        vel = 0;
    }

    /** A finger is on the list. */
    boolean held() { return down; }

    /** The finger on the list has dragged it (so what it lifts from is not a tap). */
    boolean dragged() { return down && moved; }

    /** Carries a flick on; call each draw. */
    void step() {
        if (down || vel == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(50, now - stepAt);
        stepAt = now;
        float to = Math.max(0, Math.min(max, pos + vel * dt / 1000f));
        boolean stopped = to == pos && dt > 0;
        pos = to;
        vel *= (float) Math.pow(SLOWING, dt);
        if (stopped || Math.abs(vel) < 40) vel = 0;
        else if (act.ui() != null) act.ui().postInvalidateDelayed(16);
    }

    /** Where in the list this is: a thin bar at x, down the window from top to bottom, when there is more than fits. */
    void bar(Canvas c, float x, float top, float bottom) {
        if (max <= 0) return;
        float view = bottom - top, h = Math.max(36, view * view / (max + view)), y = top + (view - h) * pos / max;
        Theme.P.setShader(null);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        Theme.P.setColor(Theme.accent(0x66));
        c.drawRoundRect(new RectF(x, y, x + 4, y + h), 2, 2, Theme.P);
    }
}

package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * The pressed look and the tap buzz for every button a view draws, without each page keeping its own
 * pressed state. While a view draws (between begin and end), Theme.button and Theme.chip tell it where
 * each button is; a finger going down on one of them buzzes (Haptics) and that button is drawn lit until
 * shortly after the finger lifts, or until it moves off as a drag.
 */
final class Press {
    private static final long LINGER_MS = 140;       // a quick tap still shows
    private static final float DRAG_PX = 18;         // screen pixels (times the view's scale)

    private static Press cur;                        // the view drawing now (the UI thread only)
    private static final Matrix M = new Matrix();
    private static final RectF T = new RectF();

    private final View view;
    private final List<RectF> seen = new ArrayList<RectF>();     // the buttons of the last frame, in view pixels
    private int count;
    private float downX, downY;
    private boolean down;
    private long upAt;
    private final Runnable redraw = new Runnable() {
        public void run() { view.invalidate(); }
    };

    Press(View v) { view = v; }

    /** At the start of the view's onDraw. */
    void begin() {
        cur = this;
        count = 0;
    }

    /** At the end of the view's onDraw. */
    void end() {
        while (seen.size() > count) seen.remove(seen.size() - 1);
        cur = null;
    }

    private boolean lit() { return down || SystemClock.uptimeMillis() - upAt < LINGER_MS; }

    /**
     * A button at `r` (in the canvas's own coordinates) is being drawn: remembers it, and returns whether
     * the finger is on it. False outside begin and end.
     */
    static boolean on(Canvas c, RectF r) {
        Press p = cur;
        if (p == null) return false;
        c.getMatrix(M);
        M.mapRect(T, r);
        if (p.count < p.seen.size()) p.seen.get(p.count).set(T);
        else p.seen.add(new RectF(T));
        p.count++;
        return p.lit() && T.contains(p.downX, p.downY);
    }

    /** Every touch on the view, before it is handled. */
    void touch(MotionEvent e) {
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
            down = false;
            upAt = 0;
            for (int i = 0; i < seen.size(); i++) {
                if (seen.get(i).contains(downX, downY)) {
                    down = true;
                    Haptics.tap(view.getContext());
                    view.invalidate();
                    break;
                }
            }
        } else if (a == MotionEvent.ACTION_MOVE) {
            float k = DRAG_PX * Screen.k(view);
            if (down && (Math.abs(e.getX() - downX) > k || Math.abs(e.getY() - downY) > k)) {
                down = false;
                view.invalidate();
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (down) {
                down = false;
                upAt = SystemClock.uptimeMillis();
                view.invalidate();
                view.postDelayed(redraw, LINGER_MS + 20);
            }
        }
    }
}

package racing.coldshift.dash;

import android.graphics.Canvas;
import android.view.MotionEvent;

abstract class Page {
    final Main act;
    Page(Main a) { act = a; }
    abstract String label();
    /** Darkness of the photo behind the page, 0 = clear, 1 = black. */
    float overlay() { return 0.66f; }
    void onShow() {}
    void onHide() {}
    /** Called once a second while the page is showing. */
    void tick() {}
    /** How often the UI should redraw and tick this page, in ms. */
    int intervalMs() { return 1000; }
    abstract void draw(Canvas c);
    boolean touch(MotionEvent e) { return false; }
    /** Laid out for any width (Wide); else drawn 1280 wide in the middle of a wider screen. */
    boolean wide() { return false; }
    /** Something waits on this page (a dot on its tab). */
    boolean attention() { return false; }
}

package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import java.util.List;

/**
 * The pages that are not on the tab bar, as large tiles: the bar keeps to the few a driver reaches for on
 * the move (UI.bar) and everything else is one tap further, here. A tile carries the gold dot when something
 * waits on its page.
 */
class MorePage extends Page {
    private final UI ui;
    private float downX, downY;

    MorePage(Main a, UI ui) { super(a); this.ui = ui; }

    String label() { return "More"; }
    @Override String icon() { return "more"; }

    @Override boolean wide() { return true; }

    private static String about(Page p) {
        if (p instanceof TrackPage) return "Timed runs";
        if (p instanceof SensorsPage) return "Engine, tyres, sound";
        if (p instanceof LogPage) return "Trips, fuel and service";
        if (p instanceof ConvoyPage) return "Drive together";
        if (p instanceof SettingsPage) return "Display, devices, sound";
        if (p instanceof DashcamPage) return "Clips, marks and the camera";
        if (p instanceof MediaPage) return "Music from the card";
        if (p instanceof MapsPage) return "Map and navigation";
        return "";
    }

    /** Up to three tiles in a row, in as many rows as it takes, filling the page. */
    private RectF tile(int i, int n) {
        int cols = n <= 4 ? 2 : 3, rows = (n + cols - 1) / cols;
        float gap = 14, l = 14, t = 68, w = (Wide.W - 2 * l - gap * (cols - 1)) / cols, h = (625 - t - gap * (rows - 1)) / rows;
        float x = l + (i % cols) * (w + gap), y = t + (i / cols) * (h + gap);
        return new RectF(x, y, x + w, y + h);
    }

    void draw(Canvas c) {
        List<Page> behind = ui.behind();
        for (int i = 0; i < behind.size(); i++) {
            Page p = behind.get(i);
            RectF r = tile(i, behind.size());
            Theme.button(c, r, false, 16);
            float x = r.left + 30;
            if (Icons.draw(c, act, p.icon(), x + 48, r.centerY(), 96, Theme.BLUE_HI)) x += 96 + 26;
            Theme.text(c, p.label(), x, r.centerY() - 2, 38, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, Theme.fit(about(p), r.right - x - 24, 22, Theme.sans), x, r.centerY() + 38, 22, Theme.SUB,
                Theme.sans, Theme.LEFT);
            if (p.attention()) {
                Theme.P.setShader(null);
                Theme.P.setStyle(android.graphics.Paint.Style.FILL);
                Theme.P.setColor(Theme.GOLD);
                c.drawCircle(r.right - 30, r.top + 30, 9, Theme.P);
            }
        }
    }

    @Override boolean touch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getAction() == MotionEvent.ACTION_DOWN) { downX = x; downY = y; return true; }
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        List<Page> behind = ui.behind();
        for (int i = 0; i < behind.size(); i++) {
            RectF r = tile(i, behind.size());
            if (r.contains(x, y) && r.contains(downX, downY)) { ui.show(behind.get(i)); return true; }
        }
        return true;
    }
}

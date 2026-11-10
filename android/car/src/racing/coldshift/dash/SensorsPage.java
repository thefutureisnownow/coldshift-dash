package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * Sensors: the engine (OBD2), its diagnostics, the tyres (TPMS) and the sound recorder as one tab, with a switch
 * between them at the top. The one last shown comes back (preferences).
 */
class SensorsPage extends Page {
    private static final RectF[] CHIPS = {Wide.centre(new RectF(328, 62, 476, 102)), Wide.centre(new RectF(484, 62, 632, 102)),
        Wide.centre(new RectF(640, 62, 788, 102)), Wide.centre(new RectF(796, 62, 944, 102))};
    private static final String[] NAMES = {"Engine", "Diagnostics", "Tyres", "Sound"};

    private final Page[] parts;
    private int cur;

    SensorsPage(Main a) {
        super(a);
        final ObdPage engine = new ObdPage(a);
        parts = new Page[] {engine, new DiagPage(a, new Runnable() {
            public void run() {
                show(0);
                engine.showCodes();
            }
        }), new TpmsPage(a), new SoundPage(a)};
        int was = Prefs.sp(a).getInt("sensors_part", 0);
        if (!Prefs.sp(a).getBoolean("sensors_diag", false)) {         // the parts before Diagnostics came in: Tyres was 1, Sound 2
            if (was >= 1) was++;
            Prefs.sp(a).edit().putBoolean("sensors_diag", true).putInt("sensors_part", was).apply();
        }
        cur = Math.max(0, Math.min(parts.length - 1, was));
    }

    String label() { return "Sensors"; }

    @Override boolean wide() { return true; }

    @Override float overlay() { return parts[cur].overlay(); }
    @Override int intervalMs() { return parts[cur].intervalMs(); }
    @Override void onShow() { parts[cur].onShow(); }
    @Override void onHide() { parts[cur].onHide(); }
    @Override void tick() { parts[cur].tick(); }
    @Override boolean attention() {
        for (Page p : parts) if (p.attention()) return true;
        return false;
    }

    void draw(Canvas c) {
        parts[cur].draw(c);
        for (int i = 0; i < CHIPS.length; i++) {
            RectF r = CHIPS[i];
            Theme.chip(c, r.left, r.top, r.right, r.bottom, NAMES[i], i == cur, 0);
        }
    }

    @Override boolean touch(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            for (int i = 0; i < CHIPS.length; i++) {
                if (CHIPS[i].contains(e.getX(), e.getY())) {
                    show(i);
                    return true;
                }
            }
        }
        return parts[cur].touch(e);
    }

    private void show(int i) {
        if (i == cur) return;
        parts[cur].onHide();
        cur = i;
        Prefs.sp(act).edit().putInt("sensors_part", i).apply();
        parts[cur].onShow();
    }
}

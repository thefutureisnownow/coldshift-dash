package racing.coldshift.dash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

class TpmsPage extends Page {
    private static final String[] NAME = {"FRONT LEFT", "FRONT RIGHT", "REAR LEFT", "REAR RIGHT"};
    private static final float[][] POS = {{124, 103}, {906, 103}, {124, 486}, {906, 486}};

    TpmsPage(Main a) { super(a); }

    String label() { return "TPMS"; }
    @Override float overlay() { return 0.25f; }

    void draw(Canvas c) {
        Bitmap car = Profile.car(act);
        if (car != null) {
            float cx = Wide.W / 2;
            RectF r = new RectF(cx - 252, 350 - 252, cx + 252, 350 + 252);
            c.drawBitmap(car, null, r, Theme.BMP);
        }
        Theme.text(c, "TYRE PRESSURE", 24, 90, 30, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, "Sensors offline", Wide.rx(1256), 84, 18, Theme.SUB, Theme.sans, Theme.RIGHT);
        for (int i = 0; i < 4; i++) {
            float l = POS[i][0] + (i % 2 == 1 ? Wide.d() : 0), t = POS[i][1];   // the right tyres at the right
            Theme.panel(c, l, t, l + 250, t + 116);
            Theme.spaced(c, NAME[i], l + 17, t + 31, 12, Theme.SUB, Theme.bold, 2f);
            Theme.P.setColor(0xff4a5a70);
            c.drawCircle(l + 233, t + 26, 5, Theme.P);
            Theme.text(c, "--", l + 17, t + 80, 44, 0xffe8eef7, Theme.bold, Theme.LEFT);
            Theme.text(c, "psi", l + 17 + Theme.width("--", 44, Theme.bold) + 8, t + 80, 14, Theme.DIM, Theme.sans, Theme.LEFT);
            Theme.text(c, "-- \u00b0C", l + 17, t + 100, 13, Theme.SUB, Theme.sans, Theme.LEFT);
        }
        Theme.text(c, "No TPMS receiver paired. Live pressures will appear once a sensor module is connected.",
            Wide.W / 2, 622, 15, Theme.SUB, Theme.sans, Theme.CENTER);
    }
}

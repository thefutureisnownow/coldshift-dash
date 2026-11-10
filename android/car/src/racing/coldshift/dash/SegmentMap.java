package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * A segment drawn as a track map (north up), as lap timers show it: its outline, the part driven so far
 * in this run in the accent colour, the start line in green, the finish as a chequered bar, and the car
 * as a white dot (kept on the map's edge, on the side it is, while it is still away from the segment).
 */
final class SegmentMap {
    private SegmentMap() {}

    static void draw(Canvas c, RectF box, TrackTimer.Segment s, double along, double meLat, double meLon, boolean haveMe) {
        int n = s.lat.length;
        double lat0 = s.lat[0], cos = Math.cos(Math.toRadians(lat0));
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = s.lon[i] * cos;
            ys[i] = -s.lat[i];
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        float pad = 22;
        double w = Math.max(maxX - minX, 1e-9), h = Math.max(maxY - minY, 1e-9);
        double k = Math.min((box.width() - 2 * pad) / w, (box.height() - 2 * pad) / h);
        float ox = (float) (box.centerX() - (minX + w / 2) * k), oy = (float) (box.centerY() - (minY + h / 2) * k);

        Path all = new Path(), done = new Path();
        boolean doneStarted = false;
        for (int i = 0; i < n; i++) {
            float x = (float) (xs[i] * k) + ox, y = (float) (ys[i] * k) + oy;
            if (i == 0) all.moveTo(x, y);
            else all.lineTo(x, y);
            if (along > 0 && s.cum[i] <= along) {
                if (!doneStarted) {
                    done.moveTo(x, y);
                    doneStarted = true;
                } else {
                    done.lineTo(x, y);
                }
            } else if (along > 0 && doneStarted && i > 0 && s.cum[i - 1] < along) {
                double f = (along - s.cum[i - 1]) / (s.cum[i] - s.cum[i - 1]);
                done.lineTo((float) ((xs[i - 1] + f * (xs[i] - xs[i - 1])) * k) + ox,
                    (float) ((ys[i - 1] + f * (ys[i] - ys[i - 1])) * k) + oy);
            }
        }
        Paint p = Theme.P;
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(9);
        p.setColor(0x40ffffff);
        c.drawPath(all, p);
        p.setStrokeWidth(5);
        p.setColor(0xa0dfe8f7);
        c.drawPath(all, p);
        if (doneStarted) {
            p.setStrokeWidth(7);
            p.setColor(Theme.BLUE);
            c.drawPath(done, p);
        }
        // the lines: across the path at right angles
        line(c, s.start, xs[0] * k + ox, ys[0] * k + oy, s.lap, true);
        if (!s.lap) line(c, s.finish, xs[n - 1] * k + ox, ys[n - 1] * k + oy, false, false);
        // the car
        if (haveMe) {
            float x = (float) (meLon * cos * k) + ox, y = (float) (-meLat * k) + oy;
            boolean inside = box.contains(x, y);
            x = Math.max(box.left + 8, Math.min(box.right - 8, x));
            y = Math.max(box.top + 8, Math.min(box.bottom - 8, y));
            p.setStyle(Paint.Style.FILL);
            p.setColor(inside ? Theme.BLUE : 0x80ffffff);
            c.drawCircle(x, y, 10, p);
            p.setColor(0xffffffff);
            c.drawCircle(x, y, 6, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    /** A start line (green), a finish (chequered) or, for a lap, both in one. */
    private static void line(Canvas c, TrackTimer.Gate g, double x, double y, boolean both, boolean start) {
        double h = Math.toRadians(g.heading);
        float ax = (float) Math.cos(h), ay = (float) Math.sin(h);   // across the direction of travel, on screen
        float half = 16;
        Paint p = Theme.P;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeWidth(7);
        if (start && !both) {
            p.setColor(Theme.GREEN);
            c.drawLine((float) x - ax * half, (float) y - ay * half, (float) x + ax * half, (float) y + ay * half, p);
            return;
        }
        // chequered: alternating white and black blocks
        for (int i = 0; i < 4; i++) {
            float a = -half + i * half / 2, b = a + half / 2;
            p.setColor(i % 2 == 0 ? 0xffffffff : 0xff111111);
            c.drawLine((float) x + ax * a, (float) y + ay * a, (float) x + ax * b, (float) y + ay * b, p);
        }
        if (both) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.GREEN);
            c.drawCircle((float) x, (float) y, 4, p);
        }
    }
}

package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Vector icons for the media controls, drawn with Canvas because the ROM has no icon theme. */
final class Icons {
    private Icons() {}

    // ---- the pictures (assets/icons, cut from generated sheets by tools/cut_icons.py): white with the
    // glyph as alpha, drawn in whatever colour is asked for ----

    private static final java.util.Map<String, android.graphics.Bitmap> PICS = new java.util.HashMap<String, android.graphics.Bitmap>();
    private static final Paint TINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final android.graphics.RectF BOX = new android.graphics.RectF();

    /** Draws the icon `name` centred on (cx, cy), `size` across, in `col`. Returns false when there is none. */
    static boolean draw(Canvas c, android.content.Context ctx, String name, float cx, float cy, float size, int col) {
        if (name == null) return false;
        android.graphics.Bitmap b = PICS.get(name);
        if (b == null && !PICS.containsKey(name)) {
            try {
                java.io.InputStream in = ctx.getAssets().open("icons/" + name + ".png");
                b = android.graphics.BitmapFactory.decodeStream(in);
                in.close();
            } catch (Exception e) { /* no such picture */ }
            PICS.put(name, b);
        }
        if (b == null) return false;
        TINT.setColorFilter(new android.graphics.PorterDuffColorFilter(col, android.graphics.PorterDuff.Mode.SRC_IN));
        BOX.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        c.drawBitmap(b, null, BOX, TINT);
        return true;
    }

    private static void fill(Canvas c, Path p, int col) {
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(col);
        c.drawPath(p, Theme.P);
    }

    private static void stroke(Canvas c, Path p, int col, float w) {
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.STROKE);
        Theme.P.setStrokeWidth(w);
        Theme.P.setColor(col);
        Theme.P.setStrokeCap(Paint.Cap.ROUND);
        Theme.P.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(p, Theme.P);
        Theme.P.setStyle(Paint.Style.FILL);
    }

    static void play(Canvas c, float cx, float cy, float s, int col) {
        Path p = new Path();
        p.moveTo(cx - s * 0.6f, cy - s); p.lineTo(cx + s, cy); p.lineTo(cx - s * 0.6f, cy + s); p.close();
        fill(c, p, col);
    }

    static void pause(Canvas c, float cx, float cy, float s, int col) {
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(col);
        c.drawRect(cx - s * 0.8f, cy - s, cx - s * 0.2f, cy + s, Theme.P);
        c.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.8f, cy + s, Theme.P);
    }

    static void skip(Canvas c, float cx, float cy, boolean prev, int col) {
        Path p = new Path();
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(col);
        if (prev) {
            p.moveTo(cx + 16, cy - 20); p.lineTo(cx - 12, cy); p.lineTo(cx + 16, cy + 20); p.close();
            c.drawRect(cx - 24, cy - 20, cx - 17, cy + 20, Theme.P);
        } else {
            p.moveTo(cx - 16, cy - 20); p.lineTo(cx + 12, cy); p.lineTo(cx - 16, cy + 20); p.close();
            c.drawRect(cx + 17, cy - 20, cx + 24, cy + 20, Theme.P);
        }
        fill(c, p, col);
    }

    static void shuffle(Canvas c, float cx, float cy, float k, int col) {
        Path p = new Path();
        p.moveTo(cx - 24 * k, cy - 12 * k); p.lineTo(cx - 10 * k, cy - 12 * k);
        p.lineTo(cx + 8 * k, cy + 12 * k); p.lineTo(cx + 18 * k, cy + 12 * k);
        p.moveTo(cx - 24 * k, cy + 12 * k); p.lineTo(cx - 10 * k, cy + 12 * k); p.lineTo(cx - 3 * k, cy + 3 * k);
        p.moveTo(cx + 3 * k, cy - 3 * k); p.lineTo(cx + 8 * k, cy - 12 * k); p.lineTo(cx + 18 * k, cy - 12 * k);
        stroke(c, p, col, 4 * k);
        Path h = new Path();
        h.moveTo(cx + 16 * k, cy - 20 * k); h.lineTo(cx + 26 * k, cy - 12 * k); h.lineTo(cx + 16 * k, cy - 4 * k); h.close();
        fill(c, h, col);
        Path h2 = new Path();
        h2.moveTo(cx + 16 * k, cy + 4 * k); h2.lineTo(cx + 26 * k, cy + 12 * k); h2.lineTo(cx + 16 * k, cy + 20 * k); h2.close();
        fill(c, h2, col);
    }

    static void repeat(Canvas c, float cx, float cy, boolean one, int col) {
        Path p = new Path();
        p.moveTo(cx - 14, cy - 14); p.lineTo(cx + 12, cy - 14);
        p.arcTo(new RectF(cx + 2, cy - 14, cx + 26, cy + 14), -90, 180);
        p.lineTo(cx - 12, cy + 14);
        p.arcTo(new RectF(cx - 26, cy - 14, cx - 2, cy + 14), 90, 180);
        stroke(c, p, col, 4);
        Path h = new Path();
        h.moveTo(cx + 4, cy - 23); h.lineTo(cx + 14, cy - 14); h.lineTo(cx + 4, cy - 5); h.close();
        fill(c, h, col);
        if (one) Theme.text(c, "1", cx, cy + 7, 20, col, Theme.bold, Theme.CENTER);
    }

    static void speaker(Canvas c, float cx, float cy, int col) {
        Path p = new Path();
        p.moveTo(cx - 10, cy - 4); p.lineTo(cx - 4, cy - 4); p.lineTo(cx + 3, cy - 10);
        p.lineTo(cx + 3, cy + 10); p.lineTo(cx - 4, cy + 4); p.lineTo(cx - 10, cy + 4); p.close();
        fill(c, p, col);
    }

    static void chevron(Canvas c, float cx, float cy, boolean left, int col) {
        Path p = new Path();
        float d = left ? 1 : -1;
        p.moveTo(cx + 8 * d * -1, cy - 14); p.lineTo(cx - 8 * d * -1, cy); p.lineTo(cx + 8 * d * -1, cy + 14);
        if (left) {
            p.reset();
            p.moveTo(cx + 7, cy - 15); p.lineTo(cx - 7, cy); p.lineTo(cx + 7, cy + 15);
        } else {
            p.reset();
            p.moveTo(cx - 5, cy - 11); p.lineTo(cx + 5, cy); p.lineTo(cx - 5, cy + 11);
        }
        stroke(c, p, col, left ? 4.5f : 3);
    }
}

package racing.coldshift.dash;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Boot splash from the vehicle's profile: its splash background and logo, with an effect on the logo
 * (shine: a band of light sweeping across it; glow: a soft, blurred light around it, gently breathing;
 * rise: it fades in and grows into place; none), fading in and out. A tap after the first moment skips it.
 */
class Splash extends View {
    static boolean shown;

    private static final int FADE_IN = 450, FADE_OUT = 450, HOLD = 2600, FPS = 25;
    private static final RectF LEFT = new RectF(110, 65, 530, 305), CENTRE = new RectF(430, 240, 850, 480);

    private final Bitmap bg, logo;
    private Bitmap halo, haloNear;              // glow: a wide soft blur and a tighter, brighter one
    private final int[] haloOff = new int[2], nearOff = new int[2];
    private final String effect;
    private final RectF box = new RectF(), dst = new RectF();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long t0 = SystemClock.uptimeMillis();
    private long skipAt = -1;
    private boolean done;

    Splash(Main a) {
        super(a);
        Profile.Splash s = Profile.splash(a);
        bg = s.background;
        logo = s.logo;
        effect = s.effect;
        RectF area = "centre".equals(s.position) ? CENTRE : LEFT;
        if (logo != null) {
            // the logo within its area, its own shape kept
            float k = Math.min(area.width() / logo.getWidth(), area.height() / logo.getHeight());
            float w = logo.getWidth() * k, h = logo.getHeight() * k;
            box.set(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2);
            if ("glow".equals(effect)) {
                // the logo's shape blurred; extractAlpha grows the bitmap by the blur and says by how much
                Paint p = new Paint();
                p.setMaskFilter(new BlurMaskFilter(Math.max(16, logo.getHeight() / 5f), BlurMaskFilter.Blur.NORMAL));
                halo = logo.extractAlpha(p, haloOff);
                p.setMaskFilter(new BlurMaskFilter(Math.max(6, logo.getHeight() / 16f), BlurMaskFilter.Blur.NORMAL));
                haloNear = logo.extractAlpha(p, nearOff);
            }
        }
        band.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        shown = true;
        setClickable(true);
        setLayerType(LAYER_TYPE_SOFTWARE, null);      // the shine draws into an offscreen layer
    }

    @Override protected void onDraw(Canvas c) {
        if (done) return;
        long t = SystemClock.uptimeMillis() - t0;
        long end = skipAt >= 0 ? skipAt + FADE_OUT : FADE_IN + HOLD + FADE_OUT;
        long fadeStart = skipAt >= 0 ? skipAt : FADE_IN + HOLD;
        if (t >= end) { finish(); return; }

        c.save();
        Screen.begin(c, this);
        c.drawColor(Color.BLACK);
        float e = Screen.extra(this);
        if (bg != null) {
            dst.set(-e, 0, 1280 + e, 720);
            c.drawBitmap(bg, UI.cover(bg, dst), dst, paint);
        }
        if (logo != null) drawLogo(c, t);
        if (t < FADE_IN) {
            paint.setColor(Color.argb((int) (255 * (1 - t / (float) FADE_IN)), 0, 0, 0));
            c.drawRect(-e, 0, 1280 + e, 720, paint);
            paint.setColor(Color.WHITE);
        }
        c.restore();
        if (t >= fadeStart) setAlpha(1f - (t - fadeStart) / (float) FADE_OUT);
        postInvalidateDelayed(1000 / FPS);
    }

    private void drawLogo(Canvas c, long t) {
        dst.set(box);
        paint.setAlpha(255);
        if ("rise".equals(effect)) {
            float f = Math.min(1f, t / 1100f);
            float e = 1 - (1 - f) * (1 - f) * (1 - f);              // ease out
            float k = 0.9f + 0.1f * e;
            dst.set(box.centerX() - box.width() * k / 2, box.centerY() - box.height() * k / 2 + 24 * (1 - e),
                box.centerX() + box.width() * k / 2, box.centerY() + box.height() * k / 2 + 24 * (1 - e));
            paint.setAlpha((int) (255 * e));
        } else if ("glow".equals(effect) && halo != null) {
            // light around the logo, breathing slowly
            float breath = 0.5f + 0.5f * (float) Math.sin(2 * Math.PI * t / 2400.0);
            drawHalo(c, halo, haloOff, (int) (120 + 90 * breath));
            drawHalo(c, haloNear, nearOff, (int) (90 + 70 * breath));
        }
        if (!"shine".equals(effect)) {
            c.drawBitmap(logo, null, dst, paint);
            return;
        }
        // a slanted band of light crossing the logo once every 2.4 s, only where the logo is
        int layer = c.saveLayer(dst, null, Canvas.ALL_SAVE_FLAG);
        c.drawBitmap(logo, null, dst, paint);
        float p = (t % 2400) / 1200f;
        if (p < 1) {
            float bw = dst.width() * 0.22f;
            float x = dst.left - bw * 2 + (dst.width() + bw * 4) * p;
            band.setShader(new LinearGradient(x - bw, dst.top, x + bw, dst.top + dst.height() * 0.4f,
                new int[] {0x00ffffff, 0x88ffffff, 0x00ffffff}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(dst, band);
        }
        c.restoreToCount(layer);
    }

    /** A blurred halo where the blur put it: offset and grown in logo pixels, scaled like the logo. */
    private void drawHalo(Canvas c, Bitmap h, int[] off, int alpha) {
        float k = box.width() / logo.getWidth();
        RectF r = new RectF(box.left + off[0] * k, box.top + off[1] * k,
            box.left + (off[0] + h.getWidth()) * k, box.top + (off[1] + h.getHeight()) * k);
        Paint g = new Paint(Paint.FILTER_BITMAP_FLAG);
        g.setColor(Color.argb(alpha, 236, 244, 255));
        c.drawBitmap(h, null, r, g);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && skipAt < 0
            && SystemClock.uptimeMillis() - t0 > 700) {
            skipAt = SystemClock.uptimeMillis() - t0;
        }
        return true;
    }

    private void finish() {
        done = true;
        post(new Remover(this));
    }

    private static class Remover implements Runnable {
        private final Splash s;
        Remover(Splash s) { this.s = s; }
        public void run() {
            if (s.getParent() instanceof ViewGroup) ((ViewGroup) s.getParent()).removeView(s);
            if (s.logo != null) s.logo.recycle();
            if (s.bg != null) s.bg.recycle();
            if (s.halo != null) s.halo.recycle();
            if (s.haloNear != null) s.haloNear.recycle();
        }
    }
}

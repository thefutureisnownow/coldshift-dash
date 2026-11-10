package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.media.ToneGenerator;
import android.view.MotionEvent;
import android.view.View;

/**
 * A message from the convoy over whatever is showing (the map included): its sender and text near the
 * top for BANNER_MS, with a chime. With the screen off it turns it on for WOKEN_MS (Settings: Convoy
 * messages while the screen is off), else it only chimes. Tapping it opens the Convoy page; elsewhere
 * touches go through. A private message ("Malva Pudding → you") is drawn in the private colour, not gold. It grows with the convoy's message size (Prefs.convoyText), staying above the tab bar.
 */
final class Banner extends View {
    static final long BANNER_MS = 8000, WOKEN_MS = 15000;     // longer when it turned the screen on

    /** Where it sits, taller (and its text bigger) by the message size's factor s. */
    private static RectF box(float s) { return new RectF(170, 62, 1110, 62 + 66 * s); }

    private final Main act;
    private String who = "", text = "";
    private boolean priv;                   // a private message: in the colour of the Convoy page's private things
    private long until;

    Banner(Main a) {
        super(a);
        act = a;
    }

    void show(String from, String message, boolean isPrivate) {
        who = from;
        text = message;
        priv = isPrivate;
        boolean woke = Prefs.convoyWake(act) && act.ui().wake(WOKEN_MS);
        final long ms = woke ? WOKEN_MS : BANNER_MS;
        until = System.currentTimeMillis() + ms;
        Audio.get(act).tone(Audio.VOICE_OUT, ToneGenerator.TONE_PROP_ACK, 400);
        invalidate();
        postDelayed(new Runnable() {
            public void run() { invalidate(); }
        }, ms + 50);
    }

    private boolean showing() { return System.currentTimeMillis() < until; }

    @Override protected void onDraw(Canvas c) {
        if (!showing()) return;
        c.save();
        Screen.begin(c, this);
        float s = Prefs.convoyTextScale(act);
        RectF box = box(s);
        Theme.P.setShader(null);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        Theme.P.setColor(0xf2070e1b);
        c.drawRoundRect(box, 14, 14, Theme.P);
        Theme.P.setStyle(android.graphics.Paint.Style.STROKE);
        Theme.P.setStrokeWidth(2f);
        Theme.P.setColor(priv ? ConvoyPage.PRIVATE : Theme.GOLD);
        c.drawRoundRect(box, 14, 14, Theme.P);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        float x = box.left + 22;
        String from = Theme.fit(who, box.width() / 3, 21 * s, Theme.bold);
        Theme.text(c, from, x, box.centerY() + 8 * s, 21 * s, priv ? ConvoyPage.PRIVATE : Theme.GOLD, Theme.bold, Theme.LEFT);
        x += Theme.width(from, 21 * s, Theme.bold) + 16;
        Theme.text(c, Theme.fit(text, box.right - 22 - x, 22 * s, Theme.bold), x, box.centerY() + 8 * s, 22 * s, 0xffffffff,
            Theme.bold, Theme.LEFT);
        c.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!showing() || act.ui().blank()) return false;     // a tap on a dark screen only wakes it (UI)
        float x = Screen.x(this, e.getX()), y = Screen.y(this, e.getY());
        if (!box(Prefs.convoyTextScale(act)).contains(x, y)) return false;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            until = 0;
            invalidate();
            act.ui().show("convoy");
        }
        return true;
    }
}

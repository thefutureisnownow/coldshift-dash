package racing.coldshift.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Controls drawn above the map, and while navigating the next-turn banner and the trip strip.
 * Only taps on a control are consumed; everything else reaches the map.
 */
final class MapOverlay extends View {
    interface Listener {
        void onMode();
        void onFollow();
        void onCompass();
        void onZoom(int delta);
        void onGoTo();
        void onPickAction(int action);      // PICK_GO, PICK_HOME, PICK_WORK, PICK_CLOSE, PICK_SAVE
        void onEndNav();
    }

    static final int PICK_GO = 0, PICK_HOME = 1, PICK_WORK = 2, PICK_CLOSE = 3, PICK_SAVE = 4;

    /** What the banner shows; built by MapsPage from Nav on every fix. */
    static final class NavInfo {
        int cmd, exit, thenCmd = -1, thenExit;
        String distance = "", instruction = "", remaining = "", time = "", eta = "", status = "";
    }

    // as tall as the speed box, so they are easy to hit while driving
    private static final RectF MODE = new RectF(28, 78, 158, 142);
    private static final RectF GOTO = new RectF(170, 78, 360, 142);
    private static final RectF MODE_GPS = Wide.right(new RectF(1120, 78, 1250, 142));
    private static final RectF COMPASS = Wide.right(new RectF(1186, 330, 1250, 386));
    private static final RectF FOLLOW = Wide.right(new RectF(1186, 400, 1250, 456));
    private static final RectF ZIN = Wide.right(new RectF(1186, 470, 1250, 526));
    private static final RectF ZOUT = Wide.right(new RectF(1186, 540, 1250, 596));
    private static final RectF SPEED = Wide.right(new RectF(1040, 78, 1250, 142));
    // the card for a place picked by holding the map or found by the search
    private static final RectF CARD = Wide.centre(new RectF(300, 462, 980, 606));
    private static final RectF CARD_GO = Wide.centre(new RectF(316, 540, 466, 592));
    private static final RectF CARD_SAVE = Wide.centre(new RectF(478, 540, 628, 592));
    private static final RectF CARD_HOME = Wide.centre(new RectF(640, 540, 802, 592));
    private static final RectF CARD_WORK = Wide.centre(new RectF(814, 540, 964, 592));
    private static final RectF CARD_CLOSE = Wide.centre(new RectF(928, 470, 972, 514));
    // navigation
    private static final RectF BANNER = new RectF(28, 154, 600, 288);
    private static final RectF STRIP = Wide.centre(new RectF(330, 552, 950, 612));
    private static final RectF END = Wide.centre(new RectF(860, 560, 940, 604));
    // in a convoy: push to talk (Talk), and Cancel under it while a tapped one records
    private static final RectF TALK = new RectF(28, 372, 168, 472), TALK_CANCEL = new RectF(28, 482, 168, 530);
    private boolean holding;            // Talk is down

    private Listener listener;
    boolean gpsMode, follow = true, hasMap, headingUp;
    float north;                     // where north points on screen, degrees clockwise from up
    float speedKmh = -1;
    String note = "";
    String pickTitle;                // non-null while the place card is shown
    String pickSub = "";
    boolean pickSaved;
    NavInfo nav;                     // non-null while navigating

    MapOverlay(Context c) {
        super(c);
        Talk.get(c).addListener(new Talk.Listener() {
            public void talkChanged() { postInvalidate(); }
        });
    }

    private boolean talkShown() { return !gpsMode && Convoy.get(getContext()).inConvoy(); }

    void setListener(Listener l) { listener = l; }

    @Override protected void onDraw(Canvas c) {
        c.save();
        Screen.begin(c, this);
        c.translate(-Screen.extra(this), 0);                // the overlay spans the screen, as the Maps page (Wide)
        if (gpsMode) {
            bigButton(c, MODE_GPS, "Map");
            c.restore();
            return;
        }
        bigButton(c, MODE, "GPS");
        if (hasMap) {
            if (nav == null) bigButton(c, GOTO, "Go to");
            RectF f = FOLLOW;
            Theme.button(c, f, follow, 12);
            float cx = f.centerX(), cy = f.centerY();
            Theme.P.setShader(null);
            Theme.P.setStyle(Paint.Style.STROKE);
            Theme.P.setStrokeWidth(3f);
            Theme.P.setColor(follow ? 0xffffffff : 0xffdfe8f7);
            c.drawCircle(cx, cy, 11, Theme.P);
            c.drawLine(cx - 19, cy, cx - 6, cy, Theme.P);
            c.drawLine(cx + 6, cy, cx + 19, cy, Theme.P);
            c.drawLine(cx, cy - 19, cx, cy - 6, Theme.P);
            c.drawLine(cx, cy + 6, cx, cy + 19, Theme.P);
            Theme.P.setStyle(Paint.Style.FILL);
            compass(c);
            zoomButton(c, ZIN, true);
            zoomButton(c, ZOUT, false);
        }
        Theme.panel(c, SPEED.left, SPEED.top, SPEED.right, SPEED.bottom);
        String s = speedKmh < 0 ? "--" : String.valueOf(Math.round(speedKmh));
        Theme.text(c, s, SPEED.left + 28, SPEED.top + 48, 40, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, "km/h", SPEED.right - 20, SPEED.top + 48, 17, Theme.SUB, Theme.sans, Theme.RIGHT);
        if (nav != null) {
            banner(c);
            strip(c);
        } else if (pickTitle != null) {
            card(c);
        }
        if (talkShown()) {
            ConvoyPage.talkButton(c, TALK, getContext());
            if (Talk.get(getContext()).latched()) {
                Theme.button(c, TALK_CANCEL, false, 12);
                Theme.text(c, "Cancel", TALK_CANCEL.centerX(), TALK_CANCEL.centerY() + 7, 19, Theme.BAD, Theme.bold, Theme.CENTER);
            }
            if (Talk.get(getContext()).recording()) postInvalidateDelayed(250);     // the clock
        }
        if (!note.isEmpty()) {
            float y = nav != null || pickTitle != null ? 446 : 612;
            Theme.text(c, note, 640, y, 16, Theme.GOLD, Theme.bold, Theme.CENTER);
        }
        Theme.text(c, "© OpenStreetMap contributors", Wide.rx(1250), 618, 11, 0xaaffffff, Theme.sans, Theme.RIGHT);
        c.restore();
    }

    // ---- navigation ----

    private void banner(Canvas c) {
        RectF b = BANNER;
        Theme.panel(c, b.left, b.top, b.right, b.bottom);
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(Theme.BLUE);
        c.drawRoundRect(new RectF(b.left + 10, b.top + 10, b.left + 124, b.bottom - 10), 12, 12, Theme.P);
        turnIcon(c, b.left + 67, b.top + 67, 34, nav.cmd, nav.exit, 0xffffffff, 8);
        Theme.text(c, nav.distance, b.left + 142, b.top + 54, 40, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, nav.instruction, b.left + 142, b.top + 88, 20, Theme.TEXT, Theme.sans, Theme.LEFT);
        if (nav.thenCmd >= 0) {
            turnIcon(c, b.left + 152, b.top + 112, 10, nav.thenCmd, nav.thenExit, Theme.SUB, 3);
            Theme.text(c, "then " + lower(Nav.instruction(nav.thenCmd, nav.thenExit)), b.left + 170, b.top + 118,
                15, Theme.SUB, Theme.sans, Theme.LEFT);
        } else if (!nav.status.isEmpty()) {
            Theme.text(c, nav.status, b.left + 142, b.top + 118, 15, Theme.GOLD, Theme.bold, Theme.LEFT);
        }
    }

    private static String lower(String s) { return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1); }

    private void strip(Canvas c) {
        RectF r = STRIP;
        Theme.panel(c, r.left, r.top, r.right, r.bottom);
        Theme.text(c, nav.remaining, r.left + 20, r.top + 39, 24, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, nav.time, r.left + 190, r.top + 39, 24, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, "arrive " + nav.eta, r.left + 360, r.top + 39, 20, Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.button(c, END, false, 10);
        Theme.text(c, "End", END.centerX(), END.centerY() + 7, 18, Theme.BAD, Theme.bold, Theme.CENTER);
    }

    /**
     * A turn arrow centred on (cx, cy), `s` its half size: a stem from below and a head pointing the
     * way to go; a ring with the exit number for roundabouts; a pin for the destination.
     */
    static void turnIcon(Canvas c, float cx, float cy, float s, int cmd, int exit, int color, float w) {
        Paint p = Theme.P;
        p.setShader(null);
        p.setColor(color);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        if (cmd == Route.END) {
            p.setStyle(Paint.Style.FILL);
            Path pin = new Path();
            pin.moveTo(cx, cy + s);
            pin.lineTo(cx - s * 0.55f, cy - s * 0.1f);
            pin.lineTo(cx + s * 0.55f, cy - s * 0.1f);
            pin.close();
            c.drawPath(pin, p);
            c.drawCircle(cx, cy - s * 0.35f, s * 0.6f, p);
            p.setColor(Theme.BLUE);
            c.drawCircle(cx, cy - s * 0.35f, s * 0.25f, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            return;
        }
        p.setStyle(Paint.Style.STROKE);
        if (cmd == Route.RNDB || cmd == Route.RNLB) {
            c.drawLine(cx, cy + s, cx, cy + s * 0.55f, p);
            c.drawCircle(cx, cy, s * 0.55f, p);
            p.setStyle(Paint.Style.FILL);
            if (exit > 0) Theme.text(c, String.valueOf(exit), cx, cy + s * 0.25f, s * 0.75f, color, Theme.bold, Theme.CENTER);
            p.setStrokeCap(Paint.Cap.BUTT);
            return;
        }
        if (cmd == Route.TLU || cmd == Route.TRU || cmd == Route.TU) {
            float dir = cmd == Route.TRU ? 1 : -1;
            Path u = new Path();
            u.moveTo(cx - dir * s * 0.35f, cy + s);
            u.lineTo(cx - dir * s * 0.35f, cy - s * 0.2f);
            u.cubicTo(cx - dir * s * 0.35f, cy - s, cx + dir * s * 0.55f, cy - s, cx + dir * s * 0.55f, cy - s * 0.2f);
            u.lineTo(cx + dir * s * 0.55f, cy + s * 0.3f);
            c.drawPath(u, p);
            head(c, cx + dir * s * 0.55f, cy + s * 0.55f, 180, s, p);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.BUTT);
            return;
        }
        float a;
        switch (cmd) {
            case Route.TL: a = -90; break;
            case Route.TSLL: a = -45; break;
            case Route.TSHL: a = -135; break;
            case Route.TR: a = 90; break;
            case Route.TSLR: a = 45; break;
            case Route.TSHR: a = 135; break;
            case Route.KL: case Route.EL: a = -35; break;
            case Route.KR: case Route.ER: a = 35; break;
            default: a = 0;
        }
        float mx = cx, my = cy + s * 0.1f;
        double r = Math.toRadians(a);
        float tx = mx + (float) Math.sin(r) * s * 0.8f, ty = my - (float) Math.cos(r) * s * 0.8f;
        if (cmd == Route.KL || cmd == Route.KR || cmd == Route.EL || cmd == Route.ER) {
            // the branch not taken, faint
            int keep = p.getColor();
            p.setColor((keep & 0x00ffffff) | 0x60000000);
            c.drawLine(mx, my, mx - (float) Math.sin(r) * s * 0.7f, my - (float) Math.cos(r) * s * 0.7f, p);
            p.setColor(keep);
        }
        c.drawLine(mx, cy + s, mx, my, p);
        c.drawLine(mx, my, tx, ty, p);
        head(c, tx, ty, a, s, p);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private static void head(Canvas c, float x, float y, float deg, float s, Paint p) {
        Path h = new Path();
        float k = s * 0.42f;
        double r = Math.toRadians(deg);
        float fx = (float) Math.sin(r), fy = (float) -Math.cos(r);       // forward
        float sx = -fy, sy = fx;                                          // sideways
        h.moveTo(x + fx * k, y + fy * k);
        h.lineTo(x - fx * k * 0.3f + sx * k, y - fy * k * 0.3f + sy * k);
        h.lineTo(x - fx * k * 0.3f - sx * k, y - fy * k * 0.3f - sy * k);
        h.close();
        Paint.Style st = p.getStyle();
        p.setStyle(Paint.Style.FILL);
        c.drawPath(h, p);
        p.setStyle(st);
    }

    // ---- destination ----

    private void card(Canvas c) {
        RectF r = CARD;
        Theme.panel(c, r.left, r.top, r.right, r.bottom);
        Theme.text(c, Theme.fit(pickTitle, r.width() - 90, 20, Theme.bold), r.left + 18, r.top + 34, 20, Theme.TEXT,
            Theme.bold, Theme.LEFT);
        Theme.text(c, Theme.fit(pickSub, r.width() - 36, 14, Theme.sans), r.left + 18, r.top + 60, 14, Theme.SUB,
            Theme.sans, Theme.LEFT);
        Theme.button(c, CARD_GO, true, 10);
        Theme.text(c, "Go", CARD_GO.centerX(), CARD_GO.centerY() + 7, 19, 0xffffffff, Theme.bold, Theme.CENTER);
        Theme.button(c, CARD_SAVE, pickSaved, 10);
        Theme.text(c, pickSaved ? "\u2605 Saved" : "\u2606 Save", CARD_SAVE.centerX(), CARD_SAVE.centerY() + 6, 16,
            pickSaved ? 0xffffffff : Theme.TEXT, Theme.sans, Theme.CENTER);
        Theme.button(c, CARD_HOME, false, 10);
        Theme.text(c, "Set as Home", CARD_HOME.centerX(), CARD_HOME.centerY() + 6, 16, Theme.TEXT, Theme.sans, Theme.CENTER);
        Theme.button(c, CARD_WORK, false, 10);
        Theme.text(c, "Set as Work", CARD_WORK.centerX(), CARD_WORK.centerY() + 6, 16, Theme.TEXT, Theme.sans, Theme.CENTER);
        Theme.text(c, "×", CARD_CLOSE.centerX(), CARD_CLOSE.centerY() + 10, 30, Theme.SUB, Theme.sans, Theme.CENTER);
    }

    // ---- controls ----

    private static void bigButton(Canvas c, RectF r, String label) {
        Theme.button(c, r, false, 12);
        Theme.text(c, label, r.centerX(), r.centerY() + 8, 23, Theme.TEXT, Theme.bold, Theme.CENTER);
    }

    /** North-up / heading-up switch; the needle shows where north is. */
    private void compass(Canvas c) {
        RectF r = COMPASS;
        Theme.button(c, r, headingUp, 12);
        float cx = r.centerX(), cy = r.centerY();
        c.save();
        c.rotate(north, cx, cy);
        Path n = new Path();
        n.moveTo(cx, cy - 20);
        n.lineTo(cx + 8, cy);
        n.lineTo(cx - 8, cy);
        n.close();
        Path s = new Path();
        s.moveTo(cx, cy + 20);
        s.lineTo(cx + 8, cy);
        s.lineTo(cx - 8, cy);
        s.close();
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(0xffff4d4d);
        c.drawPath(n, Theme.P);
        Theme.P.setColor(0xffdfe8f7);
        c.drawPath(s, Theme.P);
        c.restore();
    }

    private void zoomButton(Canvas c, RectF r, boolean plus) {
        Theme.button(c, r, false, 12);
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.STROKE);
        Theme.P.setStrokeWidth(4f);
        Theme.P.setColor(0xffdfe8f7);
        float cx = r.centerX(), cy = r.centerY();
        c.drawLine(cx - 14, cy, cx + 14, cy, Theme.P);
        if (plus) c.drawLine(cx, cy - 14, cx, cy + 14, Theme.P);
        Theme.P.setStyle(Paint.Style.FILL);
    }

    /** Which control a tap at (x, y) in 1280x720 coordinates hits, as a code; 0 for none. */
    private int hit(float x, float y) {
        if (gpsMode) return MODE_GPS.contains(x, y) ? 1 : 0;
        if (MODE.contains(x, y)) return 1;
        if (!hasMap) return 0;
        if (nav != null && END.contains(x, y)) return 20;
        if (nav != null && (BANNER.contains(x, y) || STRIP.contains(x, y))) return 99;   // eat it
        if (nav == null && GOTO.contains(x, y)) return 10;
        if (pickTitle != null && nav == null) {
            if (CARD_GO.contains(x, y)) return 30;
            if (CARD_SAVE.contains(x, y)) return 34;
            if (CARD_HOME.contains(x, y)) return 31;
            if (CARD_WORK.contains(x, y)) return 32;
            if (CARD_CLOSE.contains(x, y)) return 33;
            if (CARD.contains(x, y)) return 99;
        }
        if (COMPASS.contains(x, y)) return 2;
        if (FOLLOW.contains(x, y)) return 3;
        if (ZIN.contains(x, y)) return 4;
        if (ZOUT.contains(x, y)) return 5;
        return 0;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (listener == null) return false;
        float x = Screen.x(this, e.getX()) + Screen.extra(this), y = Screen.y(this, e.getY());
        int a = e.getAction();
        if (talkShown() && a == MotionEvent.ACTION_DOWN && TALK.contains(x, y)) {
            holding = true;
            Talk.get(getContext()).press();
            return true;
        }
        if (holding) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                holding = false;
                Talk.get(getContext()).release();
            }
            return true;
        }
        if (talkShown() && Talk.get(getContext()).latched() && TALK_CANCEL.contains(x, y)) {
            if (a == MotionEvent.ACTION_UP) Talk.get(getContext()).cancel();
            return true;
        }
        int h = hit(x, y);
        if (a == MotionEvent.ACTION_DOWN) return h != 0;
        if (a == MotionEvent.ACTION_UP && h != 0) {
            switch (h) {
                case 1: listener.onMode(); break;
                case 2: listener.onCompass(); break;
                case 3: listener.onFollow(); break;
                case 4: listener.onZoom(1); break;
                case 5: listener.onZoom(-1); break;
                case 10: listener.onGoTo(); break;
                case 20: listener.onEndNav(); break;
                case 30: listener.onPickAction(PICK_GO); break;
                case 31: listener.onPickAction(PICK_HOME); break;
                case 32: listener.onPickAction(PICK_WORK); break;
                case 33: listener.onPickAction(PICK_CLOSE); break;
                case 34: listener.onPickAction(PICK_SAVE); break;
                default: break;
            }
        }
        return true;
    }
}

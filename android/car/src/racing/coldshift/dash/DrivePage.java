package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.Location;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.util.Locale;

/**
 * What a driver glances at, on one page: the speed, the next turn, what is playing and whether the dashcam
 * records, each with the one or two buttons that matter on the move. Everything else is a tap away: a tap
 * on a panel (off its buttons) opens that panel's own tab. The first tab, always there.
 *
 * Nothing is read from the engine unless it is connected, when the revs and the coolant sit under the speed.
 */
class DrivePage extends Page implements Gps.Listener {
    // the panels (in a 1280 layout; the three at the right widen with the screen)
    private static final float L = 488, NAV_T = 68, NAV_B = 300, MUS_T = 312, MUS_B = 462, CAM_T = 474, CAM_B = 625;
    private static final RectF END = Wide.right(new RectF(1116, 212, 1250, 284));
    private static final RectF GOTO = new RectF(512, 176, 792, 276), HOME = new RectF(808, 176, 1088, 276);
    private static final RectF PREV = Wide.right(new RectF(950, 340, 1042, 434)), PLAY = Wide.right(new RectF(1054, 340, 1146, 434)),
        NEXT = Wide.right(new RectF(1158, 340, 1250, 434));
    private static final RectF REC = Wide.right(new RectF(1010, 502, 1250, 598)), MARK = Wide.right(new RectF(810, 502, 998, 598));

    private final Dashcam dash;
    private final Player player;
    private Location last;
    private long lastAt;
    private float downX, downY;

    DrivePage(Main a) {
        super(a);
        dash = Dashcam.get(a);
        player = Player.get(a);
    }

    String label() { return "Drive"; }

    @Override boolean wide() { return true; }
    @Override int intervalMs() { return 500; }

    @Override void onShow() {
        last = Gps.get(act).last();
        Gps.get(act).add(this);
    }

    @Override void onHide() { Gps.get(act).remove(this); }

    public void onFix(Location l) {
        last = l;
        lastAt = SystemClock.elapsedRealtime();
        act.ui().postInvalidate();
    }

    private boolean fix() { return last != null && lastAt != 0 && SystemClock.elapsedRealtime() - lastAt < 10000; }

    // ---- drawing ----

    void draw(Canvas c) {
        speed(c);
        nav(c);
        music(c);
        camera(c);
    }

    private void speed(Canvas c) {
        Theme.panel(c, 14, 68, 474, 625);
        boolean fix = fix();
        Obd o = Obd.get(act);
        boolean engine = o.connected() && !Float.isNaN(o.value(Obd.RPM));
        float mid = engine ? 290 : 350;
        String s = fix && last.hasSpeed() ? String.valueOf(Math.round(last.getSpeed() * 3.6f)) : "--";
        Theme.text(c, s, 244, mid, 190, fix ? 0xffffffff : Theme.DIMMER, Theme.bold, Theme.CENTER);
        Theme.text(c, "km/h", 244, mid + 50, 28, Theme.SUB, Theme.sans, Theme.CENTER);
        if (!fix) Theme.text(c, "Waiting for GPS", 244, mid + 96, 22, Theme.GOLD, Theme.bold, Theme.CENTER);
        if (!engine) return;
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(Theme.EDGE);
        c.drawRect(38, 470, 450, 471, Theme.P);
        float t = o.value(Obd.COOLANT);
        cell(c, 130, "RPM", String.valueOf(Math.round(o.value(Obd.RPM))), 0xffffffff);
        cell(c, 358, "COOLANT", Float.isNaN(t) ? "--" : Math.round(t) + "°", !Float.isNaN(t) && t >= 105 ? Theme.BAD : 0xffffffff);
    }

    private void cell(Canvas c, float cx, String name, String value, int col) {
        Theme.spaced(c, name, cx - Theme.width(name, 18, Theme.bold) / 2 - name.length(), 512, 18, Theme.SUB, Theme.bold, 2f);
        Theme.text(c, value, cx, 580, 56, col, Theme.bold, Theme.CENTER);
    }

    private void nav(Canvas c) {
        float r = Wide.rx(1266);
        Theme.panel(c, L, NAV_T, r, NAV_B);
        MapsPage maps = act.ui().mapsPage();
        MapOverlay.NavInfo n = maps.navInfo();
        String pending = maps.pending();
        if (n != null) {
            Theme.P.setShader(null);
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(Theme.BLUE);
            c.drawRoundRect(new RectF(L + 16, NAV_T + 16, L + 196, NAV_B - 16), 14, 14, Theme.P);
            MapOverlay.turnIcon(c, L + 106, NAV_T + 116, 56, n.cmd, n.exit, 0xffffffff, 13);
            float x = L + 222, w = END.left - x - 16;
            Theme.text(c, n.distance, x, NAV_T + 84, 68, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, Theme.fit(n.instruction, r - x - 20, 32, Theme.sans), x, NAV_T + 130, 32, Theme.TEXT, Theme.sans, Theme.LEFT);
            if (!n.status.isEmpty())
                Theme.text(c, Theme.fit(n.status, w, 22, Theme.bold), x, NAV_T + 168, 22, Theme.GOLD, Theme.bold, Theme.LEFT);
            else if (n.thenCmd >= 0) {
                MapOverlay.turnIcon(c, x + 14, NAV_T + 160, 13, n.thenCmd, n.thenExit, Theme.SUB, 4);
                Theme.text(c, Theme.fit("then " + lower(Nav.instruction(n.thenCmd, n.thenExit)), w - 40, 22, Theme.sans), x + 38, NAV_T + 168,
                    22, Theme.SUB, Theme.sans, Theme.LEFT);
            }
            Theme.text(c, Theme.fit(n.remaining + "  ·  " + n.time + "  ·  arrive " + n.eta, w, 24, Theme.sans), x, NAV_B - 26,
                24, Theme.SUB, Theme.sans, Theme.LEFT);
            button(c, END, "End", false, Theme.BAD);
        } else if (pending != null) {
            Theme.text(c, Theme.fit(pending, END.left - L - 48, 28, Theme.bold), L + 24, NAV_T + 84, 28, Theme.GOLD, Theme.bold, Theme.LEFT);
            button(c, END, "Cancel", false, Theme.BAD);
        } else {
            Theme.text(c, "No destination", L + 24, NAV_T + 70, 30, 0xffffffff, Theme.bold, Theme.LEFT);
            button(c, GOTO, "Go to…", true, 0xffffffff);
            boolean home = Places.get(act).find(Places.HOME) != null;
            button(c, HOME, "Home", false, home ? Theme.TEXT : Theme.DIMMER);
        }
    }

    private static String lower(String s) { return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1); }

    private void music(Canvas c) {
        float r = Wide.rx(1266);
        Theme.panel(c, L, MUS_T, r, MUS_B);
        Track t = player.current();
        float w = PREV.left - L - 48;
        if (t == null) {
            Theme.text(c, "Nothing playing", L + 24, MUS_T + 70, 30, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, "Tap here to pick music", L + 24, MUS_T + 106, 22, Theme.SUB, Theme.sans, Theme.LEFT);
        } else {
            Theme.text(c, Theme.fit(t.displayTitle(), w, 30, Theme.bold), L + 24, MUS_T + 70, 30, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, Theme.fit(t.artist.isEmpty() ? new java.io.File(t.folder).getName() : t.artist, w, 22, Theme.sans), L + 24,
                MUS_T + 106, 22, Theme.SUB, Theme.sans, Theme.LEFT);
        }
        int ink = t == null ? Theme.DIMMER : 0xffdfe8f7;
        Theme.button(c, PREV, false, 16);
        Icons.skip(c, PREV.centerX(), PREV.centerY(), true, ink);
        Theme.button(c, PLAY, t != null, 16);
        if (player.isPlaying()) Icons.pause(c, PLAY.centerX(), PLAY.centerY(), 22, ink);
        else Icons.play(c, PLAY.centerX(), PLAY.centerY(), 24, ink);
        Theme.button(c, NEXT, false, 16);
        Icons.skip(c, NEXT.centerX(), NEXT.centerY(), false, ink);
    }

    private void camera(Canvas c) {
        float r = Wide.rx(1266);
        Theme.panel(c, L, CAM_T, r, CAM_B);
        boolean rec = dash.recording;
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        boolean blink = rec && (SystemClock.elapsedRealtime() / 500) % 2 == 0;
        Theme.P.setColor(rec ? (blink ? 0xffe4002b : 0xff8a1426) : 0xff4a5a70);
        c.drawCircle(L + 36, CAM_T + 60, 11, Theme.P);
        Theme.text(c, rec ? "Recording" : "Dashcam off", L + 60, CAM_T + 70, 30, 0xffffffff, Theme.bold, Theme.LEFT);
        String sub = dash.status;
        if (sub.equalsIgnoreCase(rec ? "Recording" : "Stopped")) sub = "";
        if (rec && sub.isEmpty()) {
            long s = (SystemClock.elapsedRealtime() - dash.segStartReal) / 1000;
            sub = String.format(Locale.US, "This clip %d:%02d", s / 60, s % 60) + (dash.lockPending ? "  ·  will be locked" : "");
        }
        float w = (rec ? MARK.left : REC.left) - L - 48;
        Theme.text(c, Theme.fit(sub, w, 22, Theme.sans), L + 24, CAM_T + 106, 22, Theme.SUB, Theme.sans, Theme.LEFT);
        if (rec) {
            Theme.button(c, MARK, false, 16);
            if (dash.marking) {
                Theme.P.setShader(null);
                Theme.P.setStyle(Paint.Style.FILL);
                Theme.P.setColor(Theme.GOLD);
                c.drawRoundRect(MARK, 16, 16, Theme.P);
            }
            Theme.text(c, dash.marking ? "End mark" : "Mark", MARK.centerX(), MARK.centerY() + 9, 24, dash.marking ? 0xff05070d : Theme.GOLD,
                Theme.bold, Theme.CENTER);
        }
        Theme.button(c, REC, !rec, 16);
        if (rec) {
            Theme.P.setShader(null);
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(0xffa3233a);
            c.drawRoundRect(REC, 16, 16, Theme.P);
        }
        Theme.text(c, rec ? "Stop" : "Record", REC.centerX(), REC.centerY() + 9, 26, 0xffffffff, Theme.bold, Theme.CENTER);
    }

    private static void button(Canvas c, RectF r, String label, boolean accent, int ink) {
        Theme.button(c, r, accent, 16);
        Theme.text(c, label, r.centerX(), r.centerY() + 9, 26, ink, Theme.bold, Theme.CENTER);
    }

    // ---- touch: a tap is a finger that goes down and comes up in the same place ----

    private boolean tapped(RectF r, float x, float y) { return r.contains(x, y) && r.contains(downX, downY); }

    @Override boolean touch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getAction() == MotionEvent.ACTION_DOWN) { downX = x; downY = y; return true; }
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        if (Math.abs(x - downX) > 30 || Math.abs(y - downY) > 30) return true;
        UI ui = act.ui();
        MapsPage maps = ui.mapsPage();
        if (y >= NAV_T && y <= NAV_B && x >= L) {
            if (maps.navigating()) {
                if (tapped(END, x, y)) maps.onEndNav(); else ui.show("maps");
            } else if (tapped(GOTO, x, y)) {
                ui.show("maps");
                maps.onGoTo();
            } else if (tapped(HOME, x, y)) {
                if (Places.get(act).find(Places.HOME) != null) { ui.show("maps"); maps.goHome(); ui.show("drive"); }
            } else ui.show("maps");
        } else if (y >= MUS_T && y <= MUS_B && x >= L) {
            if (tapped(PREV, x, y)) { if (player.current() != null) player.prev(); }
            else if (tapped(PLAY, x, y)) { if (player.current() != null) player.toggle(); else ui.show("media"); }
            else if (tapped(NEXT, x, y)) { if (player.current() != null) player.next(); }
            else ui.show("media");
        } else if (y >= CAM_T && y <= CAM_B && x >= L) {
            if (tapped(REC, x, y)) { if (dash.recording) dash.stop(); else dash.start(); }
            else if (dash.recording && tapped(MARK, x, y)) dash.toggleMark();
            else ui.show("dashcam");
        } else if (x < 474 && y >= 68 && y <= 625 && Obd.get(act).connected()) {
            ui.show("sensors");
        }
        return true;
    }
}

package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Engine view: what the car says through the OBD adapter (Obd). Three views, the live readings, the
 * fault codes and the conversation with the adapter (the log, for finding out why a car will not talk).
 *
 * Live: eight panels, the eight most useful readings the car offers. The usual eight are rpm, speed, coolant,
 * throttle, load, intake air, timing and barometer; boost (manifold pressure less barometric) takes the
 * barometer's place when the car has a manifold pressure sensor, and the voltage at the OBD port takes the
 * place of any reading the car does not have (timing first), then fuel level, air flow and the trims. A tap on
 * the boost panel switches it between bar and kPa. Above the panels, the state and, once connected, the
 * protocol and how many readings a second the adapter manages (a K-line car gives a few; the page shows what
 * comes rather than looking broken).
 *
 * Fault codes: read, listed with plain words for the common ones, and Clear after a confirmation that says
 * what it does. Nothing here locks while the car moves: a passenger may use it.
 *
 * Without an Engine device chosen the page says so and has a button to Settings, Devices.
 */
class ObdPage extends Page {
    private static final int LIVE = 0, CODES = 1, LOG = 2;
    private static final RectF FAULTS = Wide.right(new RectF(1096, 62, 1256, 102)), LOG_CHIP = Wide.right(new RectF(1030, 62, 1088, 102)),
        BACK = Wide.right(new RectF(1146, 62, 1256, 102)), READ = Wide.right(new RectF(1010, 62, 1138, 102)),
        RETRY = Wide.right(new RectF(1126, 106, 1256, 134)), CHOOSE = Wide.centre(new RectF(480, 330, 800, 382)),
        CLEAR = new RectF(14, 548, 190, 592), SURE = Wide.centre(new RectF(640 - 190, 394, 640 - 10, 440)),
        CANCEL = Wide.centre(new RectF(640 + 10, 394, 640 + 190, 440));
    private static final float GRID_T = 140, PANEL_H = 214, GAP = 12, LIST_T = 140, LIST_B = 536, ROW = 54, TAP_SLOP = 14;
    private static final int[] USUAL = {Obd.RPM, Obd.SPEED, Obd.COOLANT, Obd.THROTTLE, Obd.LOAD, Obd.INTAKE, Obd.TIMING, Obd.BARO};
    private static final int[] SPARE = {Obd.VOLT, Obd.FUEL, Obd.MAF, Obd.STFT, Obd.LTFT, Obd.BARO, Obd.TIMING};
    /** The second page: the mixture, the pedal and the rest. */
    private static final int[] MORE = {Obd.FUEL, Obd.OUTSIDE, Obd.PEDAL, Obd.VOLT, Obd.LAMBDA, Obd.LAMBDA_TARGET, Obd.CAT, Obd.MAF};
    private static final int[] SPARE_MORE = {Obd.STFT, Obd.LTFT, Obd.BARO, Obd.TIMING, Obd.INTAKE, Obd.LOAD};
    private static final int PAGES = 2;
    private static final float DOTS_Y = 594, SWIPE = 80;
    private static final String PAGE_KEY = "obd_live_page";
    private static final android.graphics.Typeface MONO = android.graphics.Typeface.MONOSPACE;
    private static final String KPA = "obd_boost_kpa";

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private int view = LIVE;
    private boolean confirming, armed, dragged;
    private float scroll, downX, downY, dragFrom, maxScroll;

    private int livePage;
    private boolean shown;

    ObdPage(Main a) {
        super(a);
        livePage = Math.max(0, Math.min(PAGES - 1, Prefs.sp(a).getInt(PAGE_KEY, 0)));
    }

    String label() { return "OBD2"; }

    @Override int intervalMs() { return 250; }

    @Override void onShow() {
        shown = true;
        Obd.get(act).viewing(true);
        mixture();
    }

    @Override void onHide() {
        shown = false;
        Obd.get(act).viewing(false);
        mixture();
        confirming = false;
    }

    /** The fault codes, opened from elsewhere (Diagnostics). */
    void showCodes() {
        Obd o = Obd.get(act);
        view = CODES;
        scroll = 0;
        if (o.connected()) o.readCodes();
    }

    private void mixture() { Obd.get(act).watchingMixture(shown && view == LIVE && livePage == 1); }

    private void turnTo(int p) {
        p = (p + PAGES) % PAGES;
        if (p == livePage) return;
        livePage = p;
        Prefs.sp(act).edit().putInt(PAGE_KEY, p).apply();
        mixture();
    }

    /** The check-engine light is on (a dot on the Sensors tab). */
    @Override boolean attention() { Obd o = Obd.get(act); return o.connected() && o.milOn(); }

    void draw(Canvas c) {
        mixture();
        Obd o = Obd.get(act);
        if (view == CODES && o.state() != Obd.NONE) { codes(c, o); return; }
        if (view == LOG) { log(c, o); return; }
        view = LIVE;
        live(c, o);
    }

    // ---- live ----

    private void header(Canvas c, Obd o, String title) {
        Theme.text(c, title, 24, 100, 30, 0xffffffff, Theme.bold, Theme.LEFT);
    }

    private int stateColour(Obd o) {
        switch (o.state()) {
            case Obd.CONNECTED: return Theme.GREEN;
            case Obd.NO_CAR: case Obd.IDLE: return Theme.GOLD;
            case Obd.LOST: return Theme.BAD;
            default: return Theme.SUB;
        }
    }

    private boolean canRetry(Obd o) {
        return o.state() == Obd.IDLE || o.state() == Obd.LOST || o.state() == Obd.NO_CAR || o.state() == Obd.CONNECTING && o.retryIn() > 0;
    }

    /** The status line under the title, and what the adapter last complained of. */
    private void statusLine(Canvas c, Obd o) {
        float room = RETRY.left - 24 - 16;
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        Theme.P.setColor(stateColour(o));
        c.drawCircle(32, 118, 6, Theme.P);
        Theme.text(c, Theme.fit(o.status(), room - 20, 16, Theme.sans), 46, 124, 16, stateColour(o) == Theme.SUB ? Theme.TEXT : stateColour(o), Theme.sans, Theme.LEFT);
        if (canRetry(o)) Theme.chip(c, RETRY.left, RETRY.top, RETRY.right, RETRY.bottom, o.state() == Obd.IDLE ? "Try again" : "Retry now", false, 0);
    }

    private void live(Canvas c, Obd o) {
        header(c, o, livePage == 0 || o.state() == Obd.NONE ? "ENGINE" : "ENGINE · MORE");
        if (o.state() == Obd.NONE) {
            none(c);
            return;
        }
        Theme.chip(c, LOG_CHIP.left, LOG_CHIP.top, LOG_CHIP.right, LOG_CHIP.bottom, "Log", false, 0);
        String f = o.milCount() > 0 ? "Fault codes (" + o.milCount() + ")" : "Fault codes";
        Theme.chip(c, FAULTS.left, FAULTS.top, FAULTS.right, FAULTS.bottom, f, false, o.connected() && o.milOn() ? Theme.GOLD : 0);
        statusLine(c, o);
        int[] slots = choose(o, livePage);
        for (int i = 0; i < 8; i++) {
            RectF r = panel(i);
            Theme.panel(c, r.left, r.top, r.right, r.bottom);
            if (slots[i] < 0) continue;
            reading(c, o, slots[i], r);
        }
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        for (int i = 0; i < PAGES; i++) {
            Theme.P.setColor(i == livePage ? 0xffffffff : Theme.DIMMER);
            c.drawCircle(Wide.W / 2 + (i - (PAGES - 1) / 2f) * 22, DOTS_Y, i == livePage ? 5 : 4, Theme.P);
        }
        String foot = "";
        int col = Theme.DIM;
        if (!o.lastError().isEmpty() && (o.state() == Obd.CONNECTING || o.state() == Obd.LOST)) {
            foot = "Last try: " + o.lastError();
            col = Theme.BAD;
        } else if (o.connected() || o.state() == Obd.NO_CAR) {
            boolean wifi = o.how().startsWith("TCP");
            foot = (o.device().isEmpty() || wifi ? "" : o.device() + " \u00b7 ") + (o.adapter().isEmpty() ? "" : o.adapter() + " \u00b7 ") + o.how();
        } else if (o.state() == Obd.IDLE) {
            foot = "The adapter stays powered but quiet, so the car's battery is spared. Tap Try again once the ignition is on.";
        }
        if (!foot.isEmpty()) Theme.text(c, Theme.fit(foot, Wide.W - 48, 14, Theme.sans), Wide.W / 2, 618, 14, col, Theme.sans, Theme.CENTER);
    }

    private RectF panel(int i) {
        float w = (Wide.W - 14 * 2 - 15 * 3) / 4f;
        float l = 14 + (i % 4) * (w + 15), t = GRID_T + (i / 4) * (PANEL_H + GAP);
        return new RectF(l, t, l + w, t + PANEL_H);
    }

    /**
     * The eight readings on a page. The first: the usual ones the car has, boost for barometer, the spares where
     * there is a gap. The second: the same way from MORE, leaving out what the first already shows.
     */
    private int[] choose(Obd o, int page) {
        boolean[] used = new boolean[Obd.COUNT];
        int[] first = choose(o, USUAL, SPARE, used);
        if (page == 0) return first;
        return choose(o, MORE, SPARE_MORE, used);
    }

    private int[] choose(Obd o, int[] usual, int[] spare, boolean[] used) {
        int[] out = new int[8];
        boolean known = o.known();
        for (int i = 0; i < 8; i++) {
            int k = usual[i];
            if (k >= 0 && used[k]) k = -1;
            if (known && k >= 0) {
                if (k == Obd.BARO && o.supports(Obd.MAP)) k = Obd.BOOST;
                if (!o.supports(k)) k = -1;
            }
            out[i] = k;
            if (k >= 0) used[k] = true;
        }
        if (known) {
            for (int i = 0; i < 8; i++) {
                if (out[i] >= 0) continue;
                for (int k : spare) {
                    if (!used[k] && o.supports(k)) {
                        out[i] = k;
                        used[k] = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    private boolean kpa() { return Prefs.sp(act).getBoolean(KPA, false); }

    private void reading(Canvas c, Obd o, int k, RectF r) {
        float v = o.value(k);
        boolean has = !Float.isNaN(v);
        String name = Obd.NAME[k], unit = Obd.UNIT[k], text = "--";
        if (k == Obd.BOOST) {
            boolean kp = kpa();
            if (has && v < -0.02f) name = "VACUUM";
            unit = kp ? "kPa" : "bar";
            if (has) text = kp ? String.format(Locale.US, "%.0f", v * 100) : String.format(Locale.US, "%.2f", v);
        } else if (has) {
            boolean lambda = k == Obd.LAMBDA || k == Obd.LAMBDA_TARGET;
            text = String.format(Locale.US, lambda ? "%.2f" : k == Obd.TIMING || k == Obd.MAF || k == Obd.STFT || k == Obd.LTFT || k == Obd.VOLT ? "%.1f" : "%.0f", v);
            if (lambda) unit = String.format(Locale.US, "\u03bb  \u00b7  AFR %.1f", v * 14.7f);
        }
        if (k == Obd.LAMBDA) {                                 // whether the ECU is going by this sensor now
            float fs = o.value(Obd.FUEL_SYS);
            String sys = Float.isNaN(fs) ? "" : ObdCodec.fuelSystem((int) fs);
            if (!sys.isEmpty()) unit += "  \u00b7  " + sys;
        }
        unit = Theme.fit(unit, r.width() - 40, 17, Theme.sans);
        Theme.spaced(c, name, r.left + 20, r.top + 38, 13, Theme.SUB, Theme.bold, 2f);
        int col = !has ? Theme.DIMMER : k == Obd.COOLANT && v >= 110 ? Theme.BAD : k == Obd.VOLT && v < 11.5f ? Theme.BAD
            : k == Obd.CAT && v >= 900 ? Theme.BAD : Theme.GOLD;
        Theme.text(c, text, r.left + 20, r.top + 122, text.length() > 5 ? 52 : 64, col, Theme.bold, Theme.LEFT);
        Theme.text(c, unit, r.left + 20, r.top + 164, 17, Theme.DIM, Theme.sans, Theme.LEFT);
        float full = k == Obd.RPM ? 7000 : k == Obd.THROTTLE || k == Obd.LOAD || k == Obd.FUEL || k == Obd.PEDAL ? 100 : 0;
        if (full > 0) {                                        // a bar for those that run from nothing to full
            float x0 = r.left + 20, x1 = r.right - 20, y = r.bottom - 22;
            Theme.P.setStyle(android.graphics.Paint.Style.FILL);
            Theme.P.setColor(Theme.accent(0x38));
            c.drawRoundRect(new RectF(x0, y, x1, y + 8), 4, 4, Theme.P);
            if (has) {
                Theme.P.setColor(Theme.BLUE);
                c.drawRoundRect(new RectF(x0, y, x0 + (x1 - x0) * Math.max(0, Math.min(1, v / full)), y + 8), 4, 4, Theme.P);
            }
        }
    }

    /** No adapter chosen. */
    private void none(Canvas c) {
        float cx = Wide.W / 2;
        Theme.panel(c, 14, GRID_T, Wide.W - 14, 580);
        Theme.text(c, "No OBD adapter chosen", cx, 270, 30, 0xffffffff, Theme.bold, Theme.CENTER);
        Theme.text(c, "Choose your OBD adapter in Settings, Devices.", cx, 308, 16, Theme.SUB, Theme.sans, Theme.CENTER);
        Theme.button(c, CHOOSE, true, 12);
        Theme.text(c, "Open Settings, Devices", (CHOOSE.left + CHOOSE.right) / 2, CHOOSE.centerY() + 6, 18, 0xffffffff, Theme.sans, Theme.CENTER);
        Theme.text(c, "The engine view connects to it by itself and shows the readings as soon as the car is on.", cx, 424, 16, Theme.SUB, Theme.sans, Theme.CENTER);
    }

    // ---- fault codes ----

    private void codes(Canvas c, Obd o) {
        header(c, o, "FAULT CODES");
        Theme.chip(c, BACK.left, BACK.top, BACK.right, BACK.bottom, "Back", false, 0);
        boolean busy = o.codesState() == Obd.CODES_READING || o.clearState() == Obd.CLEAR_CLEARING;
        Theme.chip(c, READ.left, READ.top, READ.right, READ.bottom, "Read again", false, busy ? Theme.DIMMER : 0);
        // the light, the count, and when it was read
        String line;
        int col = Theme.TEXT;
        if (!o.connected()) {
            line = o.status();
            col = Theme.GOLD;
        } else if (o.clearState() == Obd.CLEAR_CLEARING) {
            line = "Clearing\u2026";
        } else if (o.clearState() == Obd.CLEAR_FAILED && !o.clearMessage().isEmpty()) {
            line = o.clearMessage();
            col = Theme.BAD;
        } else if (o.codesState() == Obd.CODES_READING) {
            line = "Reading the codes\u2026";
        } else if (o.codesState() == Obd.CODES_FAILED) {
            line = o.codesMessage();
            col = Theme.BAD;
        } else {
            String light = o.milCount() < 0 ? "" : o.milOn() ? "Check-engine light is ON" : "Check-engine light is off";
            String at = o.codesAt() == 0 ? "" : "read at " + clock.format(new Date(o.codesAt()));
            line = light + (light.isEmpty() || at.isEmpty() ? "" : " \u00b7 ") + at;
            if (o.clearState() == Obd.CLEAR_DONE) line = "Cleared \u00b7 " + line;
            if (o.milOn()) col = Theme.GOLD;
        }
        Theme.text(c, Theme.fit(line, READ.left - 40, 16, Theme.sans), 24, 124, 16, col, Theme.sans, Theme.LEFT);

        float right = Wide.W - 14;
        Theme.panel(c, 14, LIST_T, right, LIST_B);
        List<Obd.Code> list = o.codes();
        boolean read = o.codesState() == Obd.CODES_DONE;
        c.save();
        c.clipRect(16, LIST_T + 2, right - 2, LIST_B - 2);
        if (read && list.isEmpty()) {
            Theme.text(c, "No fault codes", Wide.W / 2, 330, 32, Theme.GREEN, Theme.bold, Theme.CENTER);
            Theme.text(c, "Stored and pending, as the car reports them", Wide.W / 2, 364, 16, Theme.SUB, Theme.sans, Theme.CENTER);
        } else if (!read && list.isEmpty()) {
            Theme.text(c, o.codesState() == Obd.CODES_FAILED ? "Could not read the codes" : o.codesState() == Obd.CODES_READING ? "Reading\u2026" : "Tap Read again",
                Wide.W / 2, 340, 24, Theme.SUB, Theme.sans, Theme.CENTER);
        }
        maxScroll = Math.max(0, list.size() * ROW - (LIST_B - LIST_T - 8));
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        for (int i = 0; i < list.size(); i++) {
            float y = LIST_T + 4 + i * ROW - scroll;
            if (y + ROW < LIST_T || y > LIST_B) continue;
            Obd.Code k = list.get(i);
            Theme.text(c, k.code, 36, y + 35, 26, Theme.GOLD, Theme.bold, Theme.LEFT);
            Theme.text(c, k.pending ? "PENDING" : "STORED", 140, y + 33, 12, k.pending ? Theme.AMBER : Theme.SUB, Theme.bold, Theme.LEFT);
            String words = k.describe();
            Theme.text(c, Theme.fit(words.isEmpty() ? "No description built in: look the code up for this make" : words, right - 240 - 20, 17, Theme.sans),
                240, y + 33, 17, words.isEmpty() ? Theme.DIM : Theme.TEXT, Theme.sans, Theme.LEFT);
            Theme.P.setStyle(android.graphics.Paint.Style.STROKE);
            Theme.P.setStrokeWidth(1f);
            Theme.P.setColor(Theme.EDGE);
            c.drawLine(30, y + ROW - 1, right - 16, y + ROW - 1, Theme.P);
            Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        }
        c.restore();
        Theme.chip(c, CLEAR.left, CLEAR.top, CLEAR.right, CLEAR.bottom, "Clear codes", false, o.connected() && !busy ? Theme.BAD : Theme.DIMMER);
        Theme.text(c, "Clearing turns off the check-engine light and resets the car's readiness monitors.", CLEAR.right + 20, CLEAR.centerY() + 5, 14,
            Theme.DIM, Theme.sans, Theme.LEFT);
        if (confirming) confirm(c);
    }

    private void confirm(Canvas c) {
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        Theme.P.setColor(0xb0000000);
        c.drawRect(-Wide.d(), 0, Wide.W + Wide.d(), 720, Theme.P);
        float cx = Wide.W / 2, l = cx - 330, r = cx + 330;
        Theme.P.setColor(0xff000000 | Theme.PANEL);                     // the page behind must not show through
        c.drawRoundRect(new RectF(l, 210, r, 468), 14, 14, Theme.P);
        Theme.panel(c, l, 210, r, 468);
        Theme.text(c, "Clear the fault codes?", cx, 256, 26, 0xffffffff, Theme.bold, Theme.CENTER);
        String body = "This turns off the check-engine light, erases the codes and the data stored with them, and resets the readiness monitors. "
            + "The car may fail an emissions test until it has been driven for a while. If the fault is still there, the light comes back.";
        float y = 292;
        for (String s : wrap(body, r - l - 60, 16)) {
            Theme.text(c, s, cx, y, 16, Theme.TEXT, Theme.sans, Theme.CENTER);
            y += 24;
        }
        Theme.button(c, SURE, false, 12);
        Theme.text(c, "Clear codes", SURE.centerX(), SURE.centerY() + 6, 18, Theme.BAD, Theme.bold, Theme.CENTER);
        Theme.button(c, CANCEL, true, 12);
        Theme.text(c, "Cancel", CANCEL.centerX(), CANCEL.centerY() + 6, 18, 0xffffffff, Theme.sans, Theme.CENTER);
    }

    private static List<String> wrap(String s, float w, float size) {
        List<String> out = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (line.length() > 0 && Theme.width(line + " " + word, size, Theme.sans) > w) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    // ---- the conversation ----

    private void log(Canvas c, Obd o) {
        header(c, o, "OBD LOG");
        Theme.chip(c, BACK.left, BACK.top, BACK.right, BACK.bottom, "Back", false, 0);
        Theme.text(c, Theme.fit(o.status() + (o.lastError().isEmpty() ? "" : " \u00b7 " + o.lastError()), BACK.left - 50, 16, Theme.sans), 24, 124, 16,
            Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.panel(c, 14, LIST_T, Wide.W - 14, 596);
        List<String> lines = o.trace();
        int rows = 27, from = Math.max(0, lines.size() - rows);
        c.save();
        c.clipRect(18, LIST_T + 2, Wide.W - 18, 594);
        for (int i = from; i < lines.size(); i++) {
            String s = lines.get(i);
            boolean sent = s.length() > 8 && s.charAt(8) == '>';
            int col = sent ? Theme.GOLD : s.length() > 8 && s.charAt(8) == '(' ? Theme.BAD : Theme.TEXT;
            Theme.text(c, Theme.fit(s, Wide.W - 70, 13, MONO), 30, LIST_T + 22 + (i - from) * 16.5f, 13, col, MONO, Theme.LEFT);
        }
        c.restore();
    }

    // ---- touch ----

    @Override boolean touch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            armed = true;
            dragged = false;
            downX = x;
            downY = y;
            dragFrom = scroll;
            return true;
        }
        if (!armed) return false;
        if (a == MotionEvent.ACTION_MOVE) {
            if (view == CODES && !confirming && downY > LIST_T && downY < LIST_B) {
                if (Math.abs(y - downY) > TAP_SLOP) dragged = true;
                if (dragged) scroll = Math.max(0, Math.min(maxScroll, dragFrom - (y - downY)));
            }
            return true;
        }
        armed = false;
        if (a != MotionEvent.ACTION_UP || dragged) return true;
        float dx = x - downX;
        if (view == LIVE && !confirming && Obd.get(act).state() != Obd.NONE && Math.abs(dx) > SWIPE && Math.abs(dx) > 1.5f * Math.abs(y - downY)) {
            turnTo(livePage + (dx < 0 ? 1 : -1));            // a swipe to the left shows the next page
            return true;
        }
        tap(x, y);
        return true;
    }

    private void tap(float x, float y) {
        Obd o = Obd.get(act);
        if (confirming) {
            if (SURE.contains(x, y)) {
                confirming = false;
                o.clearCodes();
            } else if (CANCEL.contains(x, y) || Math.abs(x - Wide.W / 2) > 330 || y < 210 || y > 468) {
                confirming = false;
            }
            return;
        }
        if (view == LIVE) {
            if (o.state() == Obd.NONE) {
                if (CHOOSE.contains(x, y)) act.ui().openDevices();
                return;
            }
            if (FAULTS.contains(x, y)) {
                view = CODES;
                scroll = 0;
                if (o.connected()) o.readCodes();
            } else if (LOG_CHIP.contains(x, y)) {
                view = LOG;
            } else if (canRetry(o) && RETRY.contains(x, y)) {
                o.retry();
            } else if (y > DOTS_Y - 14 && y < DOTS_Y + 14 && Math.abs(x - Wide.W / 2) < 60) {
                turnTo(livePage + 1);
            } else {
                int[] slots = choose(o, livePage);
                for (int i = 0; i < 8; i++) {
                    if (slots[i] == Obd.BOOST && panel(i).contains(x, y)) {
                        Prefs.sp(act).edit().putBoolean(KPA, !kpa()).apply();
                    }
                }
            }
        } else if (view == CODES) {
            boolean busy = o.codesState() == Obd.CODES_READING || o.clearState() == Obd.CLEAR_CLEARING;
            if (BACK.contains(x, y)) view = LIVE;
            else if (READ.contains(x, y) && !busy) o.readCodes();
            else if (CLEAR.contains(x, y) && o.connected() && !busy) confirming = true;
        } else if (BACK.contains(x, y)) {
            view = LIVE;
        }
    }
}

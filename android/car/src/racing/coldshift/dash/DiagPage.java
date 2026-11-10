package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostics: what the car says about its emissions tests and itself, beside the Engine view (Sensors).
 *
 * Three panels. The check-engine light, how far and how long the car has been driven with it on, and since
 * the codes were last cleared (a recent clear shows as few warm-ups and kilometres: worth knowing when buying
 * a car), with a button to the fault codes. The readiness monitors: each test the car runs on itself, done or
 * not, since the codes were cleared and in this drive cycle; one not done can fail an emissions test. The
 * car: VIN, calibration IDs and their check numbers (CVN), ECU name, the OBD standard, fuel, O2 sensors, the
 * protocol and adapter, the ECU's supply voltage, how long the engine has run.
 *
 * Read when the page shows and every 30 s while it does, between two readings (Obd.readDiag); the VIN and
 * the rest of mode 09 once a connection. The last read stays on the page when the car goes away.
 */
class DiagPage extends Page {
    private static final RectF REFRESH = Wide.right(new RectF(1126, 60, 1266, 116));
    private static final float TOP = 146, BOTTOM = 596, ROW = 32;
    private static final long EVERY_MS = 30000;

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final Runnable openCodes;
    private final RectF codesButton = new RectF();
    private boolean armed;

    DiagPage(Main a, Runnable openCodes) {
        super(a);
        this.openCodes = openCodes;
    }

    String label() { return "Diagnostics"; }

    @Override int intervalMs() { return 500; }

    @Override void onShow() {
        Obd o = Obd.get(act);
        o.viewing(true);
        o.readDiag();
    }

    @Override void onHide() { Obd.get(act).viewing(false); }

    @Override void tick() {
        Obd o = Obd.get(act);
        Obd.Diag d = o.diag();
        if (o.connected() && !o.diagReading() && (d == null || System.currentTimeMillis() - d.at > EVERY_MS)) o.readDiag();
    }

    void draw(Canvas c) {
        Obd o = Obd.get(act);
        Obd.Diag d = o.diag();
        if (o.state() == Obd.NONE) {
            Theme.panel(c, 14, TOP, Wide.W - 14, 580);
            Theme.text(c, "No OBD adapter chosen", Wide.W / 2, 330, 30, 0xffffffff, Theme.bold, Theme.CENTER);
            Theme.text(c, "Choose your OBD adapter in Settings, Devices.", Wide.W / 2, 368, 18, Theme.SUB, Theme.sans, Theme.CENTER);
            return;
        }
        Theme.chip(c, REFRESH.left, REFRESH.top, REFRESH.right, REFRESH.bottom, "Refresh", false, o.connected() && !o.diagReading() ? 0 : Theme.DIMMER);
        String line;
        int col = Theme.TEXT;
        if (o.diagReading()) line = "Reading…";
        else if (!o.connected()) {
            line = o.status() + (d == null ? "" : " · last read at " + clock.format(new Date(d.at)));
            col = Theme.GOLD;
        } else line = d == null ? "" : "Read at " + clock.format(new Date(d.at));
        Theme.text(c, Theme.fit(line, Wide.W - 48, 18, Theme.sans), 24, 137, 18, col, Theme.sans, Theme.LEFT);

        float gap = 12, w = (Wide.W - 28 - 2 * gap) / 3;
        float l1 = 14, l2 = l1 + w + gap, l3 = l2 + w + gap;
        for (float l : new float[] {l1, l2, l3}) Theme.panel(c, l, TOP, l + w, BOTTOM);
        if (d == null) {
            Theme.text(c, o.connected() ? "Reading the car…" : "Connect to the car to read its diagnostics", Wide.W / 2, 360, 22,
                Theme.SUB, Theme.sans, Theme.CENTER);
            return;
        }
        light(c, o, d, l1, w);
        readiness(c, d, l2, w);
        car(c, o, d, l3, w);
    }

    // ---- the check-engine light ----

    private void light(Canvas c, Obd o, Obd.Diag d, float l, float w) {
        float x = l + 20, y = TOP + 38;
        Theme.spaced(c, "CHECK-ENGINE LIGHT", x, y, 18, Theme.SUB, Theme.bold, 2f);
        boolean on = o.milOn();
        int n = o.milCount();
        Theme.text(c, n < 0 ? "--" : on ? "ON" : "OFF", x, y + 62, 52, n < 0 ? Theme.DIMMER : on ? Theme.GOLD : Theme.GREEN, Theme.bold, Theme.LEFT);
        Theme.text(c, n < 0 ? "" : n == 0 ? "No stored codes" : n == 1 ? "1 stored code" : n + " stored codes", x, y + 92, 18, Theme.TEXT, Theme.sans, Theme.LEFT);
        codesButton.set(l + w - 20 - 150, y + 30, l + w - 20, y + 74);
        Theme.button(c, codesButton, true, 12);
        Theme.text(c, "Fault codes", codesButton.centerX(), codesButton.centerY() + 6, 18, 0xffffffff, Theme.sans, Theme.CENTER);

        y += 150;
        Theme.spaced(c, "DRIVEN WITH THE LIGHT ON", x, y, 18, Theme.SUB, Theme.bold, 2f);
        Theme.text(c, both(km(d.distMil), mins(d.minMil)), x, y + 32, 20, Theme.TEXT, Theme.sans, Theme.LEFT);

        y += 84;
        Theme.spaced(c, "SINCE THE CODES WERE CLEARED", x, y, 18, Theme.SUB, Theme.bold, 2f);
        float yy = y + 32;
        yy = pair(c, "Time", mins(d.minClear), x, yy, w);
        yy = pair(c, "Distance", km(d.distClear), x, yy, w);
        pair(c, "Warm-ups", d.warmups < 0 ? "" : String.valueOf(d.warmups), x, yy, w);
        boolean recent = d.warmups >= 0 && d.warmups < 3 || d.distClear >= 0 && d.distClear < 50;
        if (recent) {
            Theme.text(c, Theme.fit("Cleared recently, or the ECU lost power: some tests may not have run yet", w - 40, 18, Theme.sans),
                x, BOTTOM - 20, 18, Theme.GOLD, Theme.sans, Theme.LEFT);
        }
    }

    // ---- readiness ----

    private void readiness(Canvas c, Obd.Diag d, float l, float w) {
        float x = l + 20, y = TOP + 38, right = l + w - 20;
        Theme.spaced(c, "READINESS MONITORS", x, y, 18, Theme.SUB, Theme.bold, 2f);
        List<ObdCodec.Monitor> since = ObdCodec.monitors(d.since), cycle = ObdCodec.monitors(d.cycle);
        if (since.isEmpty()) {
            Theme.text(c, "The car does not report them", x, y + 40, 18, Theme.DIM, Theme.sans, Theme.LEFT);
            return;
        }
        float cSince = right - 150, cCycle = right - 46;
        Theme.text(c, "Since clear", cSince, y + 30, 18, Theme.DIM, Theme.sans, Theme.CENTER);
        Theme.text(c, "This drive", cCycle, y + 30, 18, Theme.DIM, Theme.sans, Theme.CENTER);
        int notDone = 0;
        float yy = y + 30 + ROW;
        for (ObdCodec.Monitor m : since) {
            if (!m.done) notDone++;
            Theme.text(c, Theme.fit(m.name, cSince - 60 - x, 18, Theme.sans), x, yy, 18, Theme.TEXT, Theme.sans, Theme.LEFT);
            Theme.text(c, m.done ? "Ready" : "Not ready", cSince, yy, 18, m.done ? Theme.GREEN : Theme.GOLD, Theme.bold, Theme.CENTER);
            ObdCodec.Monitor now = null;
            for (ObdCodec.Monitor k : cycle) if (k.name.equals(m.name)) now = k;
            Theme.text(c, now == null ? "--" : now.done ? "Done" : "Running", cCycle, yy, 18, now == null ? Theme.DIMMER : now.done ? Theme.GREEN : Theme.SUB,
                Theme.sans, Theme.CENTER);
            yy += ROW;
        }
        String sum = notDone == 0 ? "All ready" : notDone == 1 ? "1 not ready: an emissions test may fail" : notDone + " not ready: an emissions test may fail";
        Theme.text(c, Theme.fit(sum, w - 40, 18, Theme.sans), x, BOTTOM - 20, 18, notDone == 0 ? Theme.GREEN : Theme.GOLD, Theme.bold, Theme.LEFT);
    }

    // ---- the car ----

    private void car(Canvas c, Obd o, Obd.Diag d, float l, float w) {
        float x = l + 20, y = TOP + 38;
        Theme.spaced(c, "THE CAR", x, y, 18, Theme.SUB, Theme.bold, 2f);
        String vin = d.ident[Obd.VIN];
        float yy = y + 32;
        yy = pair(c, "VIN", vin == null ? "" : vin.isEmpty() ? "Not stored in the ECU" : vin, x, yy, w);
        yy = pair(c, "Calibration", d.ident[Obd.CAL_ID], x, yy, w);
        yy = pair(c, "Check (CVN)", d.ident[Obd.CVN], x, yy, w);
        yy = pair(c, "ECU", d.ident[Obd.ECU_NAME], x, yy, w);
        yy = pair(c, "Standard", d.standard < 0 ? "" : ObdCodec.standard(d.standard), x, yy, w);
        yy = pair(c, "Fuel", d.fuelType < 0 ? "" : ObdCodec.fuelType(d.fuelType), x, yy, w);
        yy = pair(c, "O2 sensors", d.o2 < 0 ? "" : ObdCodec.o2Sensors(d.o2), x, yy, w);
        yy = pair(c, "Protocol", o.protocolName(), x, yy, w);
        yy = pair(c, "Adapter", o.adapter(), x, yy, w);
        yy = pair(c, "ECU supply", Float.isNaN(d.ecuVolt) ? "" : String.format(Locale.US, "%.1f V", d.ecuVolt), x, yy, w);
        yy = pair(c, "Engine on for", d.runSec < 0 ? "" : d.runSec == 0 ? "Not running" : mins(d.runSec / 60) + (d.runSec < 600 ? " " + d.runSec % 60 + " s" : ""), x, yy, w);
        pair(c, "Readings", d.readings + " offered", x, yy, w);
    }

    // ---- bits ----

    /** A label and its value on one row; a value the car does not give shows as "--". Returns the next row's y. */
    private float pair(Canvas c, String label, String value, float x, float y, float w) {
        Theme.text(c, label, x, y, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        boolean has = value != null && !value.isEmpty();
        Theme.text(c, has ? Theme.fit(value, w - 40 - 130, 18, Theme.sans) : "--", x + 130, y, 18, has ? Theme.TEXT : Theme.DIMMER, Theme.sans, Theme.LEFT);
        return y + ROW;
    }

    private static String km(int v) { return v < 0 ? "" : v + " km"; }

    private static String mins(int v) {
        if (v < 0) return "";
        if (v < 60) return v + " min";
        if (v < 48 * 60) return v / 60 + " h " + v % 60 + " min";
        return v / (24 * 60) + " days";
    }

    private static String both(String a, String b) {
        if (a.isEmpty()) return b.isEmpty() ? "--" : b;
        return b.isEmpty() ? a : a + "  ·  " + b;
    }

    // ---- touch ----

    @Override boolean touch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            armed = true;
            return true;
        }
        if (e.getAction() != MotionEvent.ACTION_UP || !armed) return true;
        armed = false;
        Obd o = Obd.get(act);
        if (REFRESH.contains(x, y) && o.connected()) o.readDiag();
        else if (codesButton.contains(x, y) && o.diag() != null) openCodes.run();
        return true;
    }
}

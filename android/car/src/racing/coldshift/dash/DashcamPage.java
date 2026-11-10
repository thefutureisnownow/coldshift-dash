package racing.coldshift.dash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.location.Location;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

class DashcamPage extends Page implements SensorEventListener {
    private static final int TAB_CLIPS = 0, TAB_MARKS = 1, TAB_TRIPS = 2, TAB_PREVIEW = 3, TAB_SETTINGS = 4, TAB_INTERIOR = 5;
    private static final float LIST_T = 136, LIST_B = 618, ROW_H = 62, SET_H = 56;

    private static final float[][] TABS = {{571, 637}, {645, 717}, {725, 791}, {799, 885}, {893, 983}, {991, 1077}};

    private final Dashcam dash;
    private final SimpleDateFormat when = new SimpleDateFormat("EEE d MMM  HH:mm:ss", Locale.US);
    private int tab;
    private List<Clip> clips = new ArrayList<Clip>();
    private List<JSONObject> trips = new ArrayList<JSONObject>();
    private List<MarkInfo> marks = new ArrayList<MarkInfo>();
    private long used, free;
    private int refreshTicks;
    private float scroll;
    private boolean dragging, moved;
    private float downX, downY, lastY;
    private String armed;
    private long armedAt;

    DashcamPage(Main a) {
        super(a);
        dash = Dashcam.get(a);
    }

    String label() { return "Dashcam"; }

    @Override int intervalMs() {
        if (tab == TAB_PREVIEW) return 110;
        if (tab == TAB_INTERIOR && (dash.shotAt != 0 || dash.shooting)) return 200;
        return dragging ? 33 : dash.recording ? 500 : 1000;
    }

    @Override void onShow() {
        refreshData();
        if (tab == TAB_PREVIEW) startPreview();
    }

    @Override void onHide() { stopPreview(); }

    private void setTab(int i) {
        if (i == tab) return;
        if (tab == TAB_PREVIEW) stopPreview();
        tab = i;
        scroll = 0;
        armed = null;
        if (tab == TAB_PREVIEW) startPreview();
    }

    private Bitmap still;
    private String stillPath = "";
    private long stillTaken;
    private boolean stillLoading;

    private static class StillJob implements Runnable {
        private final DashcamPage page; private final File clip;
        StillJob(DashcamPage page, File clip) { this.page = page; this.clip = clip; }
        public void run() {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            try {
                r.setDataSource(clip.getPath());
                String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                long us = Math.max(0, (d == null ? 0 : Long.parseLong(d)) - 1500) * 1000;
                Bitmap f = r.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (f != null) {
                    page.still = Bitmap.createScaledBitmap(f, 688, 387, true);
                    page.stillTaken = clip.lastModified();
                    page.stillPath = clip.getPath();
                    if (f != page.still) f.recycle();
                }
            } catch (Throwable t) {
                page.stillPath = clip.getPath();
            } finally {
                try { r.release(); } catch (Throwable t) { /* ignore */ }
                page.stillLoading = false;
            }
        }
    }

    private void maybeLoadStill() {
        if (stillLoading) return;
        for (Clip k : clips) {
            if (dash.recording && k.file.getName().equals(dash.clipName)) continue;
            if (k.file.getPath().equals(stillPath)) return;
            stillLoading = true;
            new Thread(new StillJob(this, k.file)).start();
            return;
        }
    }

    private boolean sensing;
    private float roll, elev;
    private boolean haveTilt;

    private void startPreview() {
        dash.setPreviewTarget(act.ui());
        dash.setPreview(true);
        if (sensing) return;
        SensorManager sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) { sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI); sensing = true; }
    }

    private void stopPreview() {
        dash.setPreview(false);
        if (!sensing) return;
        ((SensorManager) act.getSystemService(Context.SENSOR_SERVICE)).unregisterListener(this);
        sensing = false;
        haveTilt = false;
    }

    public void onAccuracyChanged(Sensor s, int a) {}

    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        float gx, gy, gz = z;
        int rot = DashPrefs.rotation(act);
        if (rot == 1) { gx = y; gy = -x; }
        else if (rot == 3) { gx = -y; gy = x; }
        else { gx = x; gy = y; }
        double g = Math.sqrt(x * x + y * y + z * z);
        if (g < 1) return;
        float r = (float) Math.toDegrees(Math.atan2(gx, -gy));
        float el = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -gz / g))));
        roll = haveTilt ? roll * 0.8f + r * 0.2f : r;
        elev = haveTilt ? elev * 0.8f + el * 0.2f : el;
        haveTilt = true;
    }

    @Override void tick() {
        if (++refreshTicks % 2 == 0 || refreshTicks == 1) refreshData();
        if (armed != null && SystemClock.elapsedRealtime() - armedAt > 3000) armed = null;
        if (tab == TAB_PREVIEW && dash.recording) maybeLoadStill();
    }

    private void refreshData() {
        List<Clip> l = dash.clips();
        Collections.reverse(l);
        clips = l;
        long u = 0;
        for (Clip c : l) u += c.size;
        used = u;
        free = dash.freeBytes();
        JSONArray a = dash.trips();
        List<JSONObject> t = new ArrayList<JSONObject>();
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optDouble("dist") >= 100) t.add(o);
        }
        trips = t;
        marks = dash.marks();
    }

    private static String size(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024));
        return (b / (1024 * 1024)) + " MB";
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** " (this camera: 720p, 3.6 Mbps)" when the camera cannot do the chosen quality's size. */
    private String recordsAt() {
        String got = dash.videoSize();
        int q = DashPrefs.qualityIdx(act);
        if (got.isEmpty() || got.equals(DashPrefs.Q_W[q] + "x" + DashPrefs.Q_H[q])) return "";
        return String.format(java.util.Locale.US, " (this camera: %sp, %.1f Mbps)", got.substring(got.indexOf('x') + 1),
            dash.kbps(q) / 1000.0);
    }

    // ---- drawing ----

    @Override boolean wide() { return true; }

    void draw(Canvas c) {
        status(c);
        right(c);
    }

    private void status(Canvas c) {
        Theme.panel(c, 14, 68, 540, 625);
        boolean rec = dash.recording;
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        boolean blink = rec && (SystemClock.elapsedRealtime() / 500) % 2 == 0;
        Theme.P.setColor(rec ? (blink ? 0xffe4002b : 0xff8a1426) : 0xff4a5a70);
        c.drawCircle(46, 102, 10, Theme.P);
        Theme.text(c, rec ? "RECORDING" : "STOPPED", 68, 112, 28, 0xffffffff, Theme.bold, Theme.LEFT);
        long elapsed = rec ? SystemClock.elapsedRealtime() - dash.segStartReal : 0;
        if (rec) Theme.text(c, clock(elapsed) + " / " + clock(dash.segLenMs), 522, 110, 19, Theme.SUB, Theme.sans, Theme.RIGHT);
        float frac = rec && dash.segLenMs > 0 ? Math.min(1f, elapsed / (float) dash.segLenMs) : 0;
        Theme.P.setColor(Theme.accent(0x38));
        c.drawRoundRect(new RectF(32, 128, 522, 134), 3, 3, Theme.P);
        Theme.P.setColor(0xffe4002b);
        c.drawRoundRect(new RectF(32, 128, 32 + 490 * frac, 134), 3, 3, Theme.P);
        String st = dash.status;
        int stc = st.startsWith("Recording") ? Theme.GREEN : (st.startsWith("Stopped") ? Theme.DIM : Theme.GOLD);
        Theme.text(c, Theme.fit(st + (dash.lockPending ? "  \u00b7  clip will be locked" : ""), 490, 15, Theme.sans),
            32, 160, 15, stc, Theme.sans, Theme.LEFT);

        long cap = DashPrefs.capBytes(act);
        String where = dash.onCard() ? "SD card" : "Phone storage";
        String limit = cap == 0 ? "" : " of " + size(cap);
        int locked = 0;
        for (Clip k : clips) if (k.locked) locked++;
        Location l = dash.lastLoc;
        boolean fix = l != null && SystemClock.elapsedRealtime() - dash.lastLocAt < 5000;
        String power;
        int pc = Theme.TEXT;
        if (dash.plugged) power = "Car power connected" + (dash.battery >= 0 ? "  \u00b7  battery " + dash.battery + "%" : "");
        else if (rec && dash.powerLostAt > 0) {
            long left = Math.max(0, (DashPrefs.graceMs(act) - (SystemClock.elapsedRealtime() - dash.powerLostAt)) / 1000);
            power = "No power: stopping in " + left + " s";
            pc = Theme.BAD;
        } else { power = "No external power  \u00b7  battery " + dash.battery + "%"; pc = Theme.AMBER; }
        String trip = rec ? String.format(Locale.US, "%.1f km  \u00b7  max %.0f km/h", dash.tripMeters / 1000.0, dash.tripMaxMs * 3.6f) : "-";
        String[][] rows = {
            {"Storage", where + "  \u00b7  " + size(used) + " used" + limit + "  \u00b7  " + size(free) + " free"},
            {"Clips", clips.size() + " clips  \u00b7  " + locked + " locked  \u00b7  " + marks.size() + " marks"},
            {"GPS", fix ? String.format(Locale.US, "Fix  \u00b7  %.0f km/h", l.getSpeed() * 3.6f) : "Searching for satellites"},
            {"Power", power},
            {"This trip", trip}};
        float y = 196;
        for (int i = 0; i < rows.length; i++) {
            Theme.text(c, rows[i][0], 32, y, 13, Theme.DIM, Theme.sans, Theme.LEFT);
            Theme.text(c, Theme.fit(rows[i][1], 490, 18, Theme.bold), 32, y + 24, 18,
                i == 3 ? pc : 0xffffffff, Theme.bold, Theme.LEFT);
            y += 52;
        }
        String note = Exporter.busy || !Exporter.status.isEmpty() ? Exporter.status : dash.markNote;
        if (!note.isEmpty()) Theme.text(c, Theme.fit(note, 490, 13, Theme.sans), 32, 450, 13, Theme.GOLD, Theme.sans, Theme.LEFT);
        RectF bm = new RectF(32, 464, 522, 526);
        Theme.button(c, bm, false, 14);
        Theme.P.setShader(null);
        if (dash.marking) {
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(Theme.GOLD);
            c.drawRoundRect(bm, 14, 14, Theme.P);
            long ms = SystemClock.elapsedRealtime() - dash.markStartReal;
            Theme.text(c, "End mark   " + clock(ms), 277, 504, 21, 0xff05070d, Theme.bold, Theme.CENTER);
        } else {
            Theme.P.setStyle(Paint.Style.STROKE);
            Theme.P.setStrokeWidth(1.6f);
            Theme.P.setColor(rec ? 0xaae3b341 : 0x44e3b341);
            c.drawRoundRect(bm, 14, 14, Theme.P);
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.text(c, "Start mark", 277, 504, 21, rec ? Theme.GOLD : 0xff5d7090, Theme.bold, Theme.CENTER);
        }

        RectF b1 = new RectF(32, 548, 282, 612);
        Theme.button(c, b1, !rec, 14);
        if (rec) {
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(0xffa3233a);
            c.drawRoundRect(b1, 14, 14, Theme.P);
        }
        Theme.text(c, rec ? "Stop recording" : "Start recording", 157, 589, 21, 0xffffffff, Theme.bold, Theme.CENTER);
        RectF b2 = new RectF(298, 548, 522, 612);
        Theme.button(c, b2, false, 14);
        Theme.P.setStyle(Paint.Style.STROKE);
        Theme.P.setStrokeWidth(1.6f);
        Theme.P.setColor(rec ? 0xaae3b341 : 0x44e3b341);
        c.drawRoundRect(b2, 14, 14, Theme.P);
        Theme.P.setStyle(Paint.Style.FILL);
        padlock(c, 340, 580, true, rec ? Theme.GOLD : 0xff5d7090);
        Theme.text(c, "Lock clip", 440, 588, 20, rec ? Theme.GOLD : 0xff5d7090, Theme.sans, Theme.CENTER);
    }

    private void right(Canvas c) {
        Theme.panel(c, 555, 68, Wide.rx(1266), 625);
        String[] names = {"Clips", "Marks", "Trips", "Preview", "Settings", "Interior"};
        for (int i = 0; i < TABS.length; i++) Theme.chip(c, TABS[i][0], 82, TABS[i][1], 122, names[i], tab == i, 0);
        if (tab == TAB_SETTINGS) { settings(c); return; }
        if (tab == TAB_PREVIEW || tab == TAB_INTERIOR) {
            c.save();
            c.translate(Wide.d() / 2, 0);                   // the preview keeps its shape, centred in the panel
            if (tab == TAB_PREVIEW) previewTab(c); else interiorTab(c);
            c.restore();
            return;
        }
        c.save();
        c.clipRect(561, LIST_T, Wide.rx(1260), LIST_B);
        if (tab == TAB_CLIPS) clipsList(c); else if (tab == TAB_MARKS) marksList(c); else tripsList(c);
        c.restore();
    }

    private void clipsList(Canvas c) {
        if (clips.isEmpty()) {
            Theme.text(c, "No recordings yet", Wide.mid(561, 1260), 380, 17, Theme.SUB, Theme.sans, Theme.CENTER);
            return;
        }
        float y = LIST_T - scroll;
        for (Clip k : clips) {
            if (y + ROW_H > LIST_T && y < LIST_B) clipRow(c, k, y);
            y += ROW_H;
        }
    }

    private void clipRow(Canvas c, Clip k, float y) {
        boolean cur = dash.recording && k.file.getName().equals(dash.clipName);
        Theme.text(c, when.format(new Date(k.startMs)), 575, y + 27, 17, cur ? Theme.GREEN : 0xffe8eef7, Theme.sans, Theme.LEFT);
        Theme.text(c, cur ? "recording now" : size(k.size) + (k.locked ? "  \u00b7  locked" : ""), 575, y + 47, 13,
            k.locked ? Theme.GOLD : Theme.DIM, Theme.sans, Theme.LEFT);
        if (cur) return;
        float e = Wide.d();                                 // the row's buttons sit at the panel's right edge
        RectF p = new RectF(1062 + e, y + 10, 1108 + e, y + 52), l = new RectF(1118 + e, y + 10, 1164 + e, y + 52);
        RectF d = new RectF(1174 + e, y + 10, 1220 + e, y + 52);
        Theme.button(c, p, false, 10);
        Icons.play(c, 1086 + e, y + 31, 10, 0xffdfe8f7);
        Theme.button(c, l, false, 10);
        padlock(c, 1141 + e, y + 33, k.locked, k.locked ? Theme.GOLD : 0xff8fa3bd);
        boolean arm = k.file.getPath().equals(armed);
        Theme.button(c, d, false, 10);
        if (arm) {
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(0xffa3233a);
            c.drawRoundRect(d, 10, 10, Theme.P);
        }
        trash(c, 1197 + e, y + 31, arm ? 0xffffffff : 0xff8fa3bd);
    }

    private void marksList(Canvas c) {
        if (marks.isEmpty()) {
            Theme.text(c, "No marks yet. Tap MARK while recording to log a stretch.", Wide.mid(561, 1260), 380, 17, Theme.SUB, Theme.sans, Theme.CENTER);
            return;
        }
        float y = LIST_T - scroll;
        for (MarkInfo m : marks) {
            if (y + ROW_H > LIST_T && y < LIST_B) markRow(c, m, y);
            y += ROW_H;
        }
    }

    private void markRow(Canvas c, MarkInfo m, float y) {
        Theme.text(c, m.label + "   \u00b7   " + when.format(new Date(m.start)), 575, y + 27, 17, 0xffe8eef7, Theme.sans, Theme.LEFT);
        String sub = m.open ? "recording this mark\u2026"
            : clock(m.durationMs) + "  \u00b7  " + String.format(Locale.US, "%.1f km", m.distM / 1000.0)
                + "  \u00b7  max " + Math.round(m.maxKmh) + " km/h  \u00b7  " + m.spans.length + (m.spans.length == 1 ? " clip" : " clips")
                + (m.gpx != null && m.gpx.exists() ? "  \u00b7  GPX" : "  \u00b7  no GPS");
        Theme.text(c, sub, 575, y + 47, 13, Theme.DIM, Theme.sans, Theme.LEFT);
        if (m.open) return;
        boolean busy = Exporter.busy && Exporter.busyId == m.id;
        String lab = m.exported() ? "Exported" : busy ? "Working\u2026" : "Export";
        Theme.chip(c, Wide.rx(1060), y + 12, Wide.rx(1172), y + 50, lab, false, m.exported() ? Theme.GOLD : 0);
        RectF d = new RectF(Wide.rx(1182), y + 10, Wide.rx(1228), y + 52);
        boolean arm = m.json.getPath().equals(armed);
        Theme.button(c, d, false, 10);
        if (arm) {
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(0xffa3233a);
            c.drawRoundRect(d, 10, 10, Theme.P);
        }
        trash(c, Wide.rx(1205), y + 31, arm ? 0xffffffff : 0xff8fa3bd);
    }

    private void tripsList(Canvas c) {
        if (trips.isEmpty()) {
            Theme.text(c, "No trips yet. A trip is a recording session.", Wide.mid(561, 1260), 380, 17, Theme.SUB, Theme.sans, Theme.CENTER);
            return;
        }
        float y = LIST_T - scroll;
        for (JSONObject t : trips) {
            if (y + ROW_H > LIST_T && y < LIST_B) {
                long dur = t.optLong("dur");
                double km = t.optDouble("dist") / 1000.0;
                double avg = dur > 0 ? km / (dur / 3600000.0) : 0;
                Theme.text(c, when.format(new Date(t.optLong("start"))), 575, y + 27, 17, 0xffe8eef7, Theme.sans, Theme.LEFT);
                Theme.text(c, String.format(Locale.US, "%.1f km  \u00b7  %d min  \u00b7  max %.0f km/h  \u00b7  avg %.0f km/h",
                    km, dur / 60000, t.optDouble("max") * 3.6, avg), 575, y + 48, 13, Theme.DIM, Theme.sans, Theme.LEFT);
            }
            y += ROW_H;
        }
    }

    private String[][] settingRows() {
        int cap = DashPrefs.capIdx(act);
        return new String[][] {
            {"Segment length", DashPrefs.SEG_MIN[DashPrefs.segIdx(act)] + " min"},
            {"Video quality", DashPrefs.QUALITY[DashPrefs.qualityIdx(act)] + recordsAt()},
            {"Record audio", DashPrefs.audio(act) ? "On" : "Off"},
            {"Storage limit", cap == 0 ? "Auto, keep 1 GB free" : DashPrefs.CAP_GB[cap] + " GB"},
            {"When storage is full", DashPrefs.deleteOldest(act) ? "Delete oldest" : "Stop recording"},
            {"After power loss", "Stop after " + (DashPrefs.GRACE_SEC[DashPrefs.graceIdx(act)] >= 60
                ? DashPrefs.GRACE_SEC[DashPrefs.graceIdx(act)] / 60 + " min" : DashPrefs.GRACE_SEC[DashPrefs.graceIdx(act)] + " s")},
            {"Start when power connects", DashPrefs.autoStart(act) ? "On" : "Off"}};
    }

    private void previewTab(Canvas c) {
        long now = SystemClock.elapsedRealtime();
        Bitmap b = dash.preview;
        boolean fresh = b != null && now - dash.previewAt < 2500;
        RectF r = new RectF(566, 138, 1254, 525);
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(0xff03060c);
        c.drawRect(r, Theme.P);
        if (fresh) {
            c.drawBitmap(b, null, r, Theme.BMP);
            Theme.P.setStyle(Paint.Style.STROKE);
            Theme.P.setStrokeWidth(1.2f);
            Theme.P.setColor(0x66ffffff);
            for (int i = 1; i < 3; i++) {
                c.drawLine(r.left + r.width() * i / 3f, r.top, r.left + r.width() * i / 3f, r.bottom, Theme.P);
                c.drawLine(r.left, r.top + r.height() * i / 3f, r.right, r.top + r.height() * i / 3f, Theme.P);
            }
            Theme.P.setColor(0xaaffffff);
            Theme.P.setStrokeWidth(2f);
            float cx = r.centerX(), cy = r.centerY();
            c.drawLine(cx - 22, cy, cx + 22, cy, Theme.P);
            c.drawLine(cx, cy - 22, cx, cy + 22, Theme.P);
            Theme.P.setStyle(Paint.Style.FILL);
        } else if (dash.recording && still != null) {
            c.drawBitmap(still, null, r, Theme.BMP);
            long ago = Math.max(0, (System.currentTimeMillis() - stillTaken) / 1000);
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(0xb0000000);
            c.drawRoundRect(new RectF(r.left + 12, r.top + 12, r.left + 470, r.top + 46), 8, 8, Theme.P);
            Theme.text(c, "Still from the last finished clip, " + clock(ago * 1000) + " ago", r.left + 24, r.top + 35, 15, Theme.GOLD, Theme.sans, Theme.LEFT);
        } else {
            String st = dash.previewState;
            String msg = st.isEmpty() || st.equals("Live") ? "Starting the camera\u2026" : st;
            if (dash.recording) msg = "Live view is not available while recording on this phone";
            Theme.text(c, msg, r.centerX(), r.centerY() - 8, 18, Theme.SUB, Theme.sans, Theme.CENTER);
            if (dash.recording)
                Theme.text(c, "A still from the last finished clip appears here once the first clip ends", r.centerX(), r.centerY() + 20, 14, Theme.DIM, Theme.sans, Theme.CENTER);
        }
        Theme.P.setStyle(Paint.Style.STROKE);
        Theme.P.setStrokeWidth(1.2f);
        Theme.P.setColor(Theme.accent(0x4d));
        c.drawRect(r, Theme.P);
        Theme.P.setStyle(Paint.Style.FILL);

        float y = 556;
        Theme.text(c, "Roll", 575, y, 13, Theme.DIM, Theme.sans, Theme.LEFT);
        Theme.text(c, "Camera pitch", 800, y, 13, Theme.DIM, Theme.sans, Theme.LEFT);
        String rs = haveTilt ? String.format(Locale.US, "%+.1f\u00b0", roll) : "-";
        String ps = haveTilt ? String.format(Locale.US, "%+.1f\u00b0", elev) : "-";
        int rc = haveTilt && Math.abs(roll) < 1.5f ? Theme.GREEN : Theme.TEXT;
        int pcol = haveTilt && Math.abs(elev) < 4f ? Theme.GREEN : Theme.TEXT;
        Theme.text(c, rs, 575, y + 28, 24, rc, Theme.bold, Theme.LEFT);
        Theme.text(c, ps, 800, y + 28, 24, pcol, Theme.bold, Theme.LEFT);
        float bx0 = 990, bx1 = 1240, by = y + 16;
        Theme.P.setColor(Theme.accent(0x38));
        c.drawRoundRect(new RectF(bx0, by - 3, bx1, by + 3), 3, 3, Theme.P);
        Theme.P.setColor(0x88ffffff);
        c.drawRect((bx0 + bx1) / 2 - 1, by - 9, (bx0 + bx1) / 2 + 1, by + 9, Theme.P);
        if (haveTilt) {
            float k = Math.max(-15, Math.min(15, roll)) / 15f;
            Theme.P.setColor(Math.abs(roll) < 1.5f ? 0xff46d17a : 0xffe3b341);
            c.drawCircle((bx0 + bx1) / 2 + k * (bx1 - bx0) / 2, by, 8, Theme.P);
        }
        Theme.text(c, "Live view for aiming only; it is not saved. Roll + means the right side is low. Pitch 0 is level; + is looking up.",
            575, 606, 12, Theme.DIM, Theme.sans, Theme.LEFT);
    }

    // ---- the interior shot ----

    private static final RectF SHOT_BTN = new RectF(575, 542, 905, 604);
    private Bitmap shot;
    private File shotFile;
    private boolean shotLoading;

    /** The newest interior shot, scaled to the frame and turned upright (the camera may only say so in EXIF). */
    private static class ShotJob implements Runnable {
        private final DashcamPage page; private final File f;
        ShotJob(DashcamPage page, File f) { this.page = page; this.f = f; }
        public void run() {
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / 1000);
                o.inJustDecodeBounds = false;
                Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                int turn = 0;
                switch (new android.media.ExifInterface(f.getPath()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    case android.media.ExifInterface.ORIENTATION_ROTATE_90: turn = 90; break;
                    case android.media.ExifInterface.ORIENTATION_ROTATE_180: turn = 180; break;
                    case android.media.ExifInterface.ORIENTATION_ROTATE_270: turn = 270; break;
                }
                if (b != null && turn != 0) {
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postRotate(turn);
                    Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                    if (r != b) b.recycle();
                    b = r;
                }
                page.shot = b;
            } catch (Throwable t) {
                page.shot = null;
            } finally {
                page.shotFile = f;
                page.shotLoading = false;
            }
        }
    }

    private void interiorTab(Canvas c) {
        RectF r = new RectF(566, 138, 1254, 525);
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(0xff03060c);
        c.drawRect(r, Theme.P);
        List<File> shots = dash.interiorShots();
        File newest = shots.isEmpty() ? null : shots.get(0);
        if (newest != null && !newest.equals(shotFile) && !shotLoading) {
            shotLoading = true;
            new Thread(new ShotJob(this, newest)).start();
        }
        long left = dash.shotAt == 0 ? 0 : dash.shotAt - SystemClock.elapsedRealtime();
        if (dash.shotAt != 0) {
            Theme.text(c, String.valueOf(Math.max(1, (left + 999) / 1000)), r.centerX(), r.centerY() + 40, 150, 0xffffffff, Theme.bold, Theme.CENTER);
            Theme.text(c, "Get out and close the door: the front camera takes the photo at zero",
                r.centerX(), r.bottom - 30, 16, Theme.SUB, Theme.sans, Theme.CENTER);
        } else if (dash.shooting) {
            Theme.text(c, "Taking the photo\u2026", r.centerX(), r.centerY(), 24, Theme.GOLD, Theme.bold, Theme.CENTER);
        } else if (shot != null && newest != null && newest.equals(shotFile)) {
            float k = Math.min(r.width() / shot.getWidth(), r.height() / shot.getHeight());
            float w = shot.getWidth() * k, hh = shot.getHeight() * k;
            c.drawBitmap(shot, null, new RectF(r.centerX() - w / 2, r.centerY() - hh / 2, r.centerX() + w / 2, r.centerY() + hh / 2), Theme.BMP);
            Theme.P.setColor(0xb0000000);
            c.drawRoundRect(new RectF(r.left + 12, r.top + 12, r.left + 420, r.top + 46), 8, 8, Theme.P);
            Theme.text(c, Theme.fit(when.format(new Date(newest.lastModified())), 390, 15, Theme.sans),
                r.left + 24, r.top + 35, 15, Theme.GOLD, Theme.sans, Theme.LEFT);
        } else {
            Theme.text(c, newest == null ? "No interior shot yet" : "Loading\u2026", r.centerX(), r.centerY() - 8, 18, Theme.SUB, Theme.sans, Theme.CENTER);
            if (newest == null)
                Theme.text(c, "A clean view of the inside, taken with the front camera, for videos later", r.centerX(), r.centerY() + 20, 14, Theme.DIM, Theme.sans, Theme.CENTER);
        }
        Theme.P.setStyle(Paint.Style.STROKE);
        Theme.P.setStrokeWidth(1.2f);
        Theme.P.setColor(Theme.accent(0x4d));
        c.drawRect(r, Theme.P);
        Theme.P.setStyle(Paint.Style.FILL);

        boolean counting = dash.shotAt != 0;
        Theme.button(c, SHOT_BTN, !counting && !dash.shooting, 14);
        Theme.text(c, counting ? "Cancel" : dash.shooting ? "Taking\u2026" : "Take interior shot",
            SHOT_BTN.centerX(), SHOT_BTN.centerY() + 8, 21, 0xffffffff, Theme.bold, Theme.CENTER);
        String st = dash.shotStatus;
        Theme.text(c, Theme.fit(st.isEmpty() ? Dashcam.SHOT_DELAY_MS / 1000 + " s to get out, beeps for the last 3" : st,
            320, 15, Theme.sans), 925, 568, 15, st.startsWith("Saved") ? Theme.GREEN : st.isEmpty() ? Theme.SUB : Theme.GOLD, Theme.sans, Theme.LEFT);
        Theme.text(c, Theme.fit(shots.size() + (shots.size() == 1 ? " shot" : " shots") + "  \u00b7  recording pauses for it", 320, 13, Theme.sans),
            925, 592, 13, Theme.DIM, Theme.sans, Theme.LEFT);
    }

    private void settings(Canvas c) {
        String[][] rows = settingRows();
        float y = 138;
        for (int i = 0; i < rows.length; i++) {
            Theme.text(c, rows[i][0], 575, y + 34, 17, 0xffe8eef7, Theme.sans, Theme.LEFT);
            Theme.chip(c, Wide.rx(940), y + 8, Wide.rx(1246), y + 48, rows[i][1], false, 0);
            y += SET_H;
        }
        File d = dash.dir();
        Theme.text(c, "Clips are saved to " + (d == null ? "(no storage)" : d.getPath().replace("/storage/", "")), 575, 548, 12, Theme.DIM, Theme.sans, Theme.LEFT);
        Theme.text(c, "Locked clips are never deleted automatically. Subtitle files (.srt) carry time, speed and position.",
            575, 568, 12, Theme.DIM, Theme.sans, Theme.LEFT);
        Theme.text(c, "Turn on 'Start when power connects' once the phone is in the car: it will record, including audio.",
            575, 588, 12, Theme.DIM, Theme.sans, Theme.LEFT);
    }

    private void padlock(Canvas c, float cx, float cy, boolean closed, int col) {
        Path sh = new Path();
        sh.addArc(new RectF(cx - 8, cy - 22, cx + 8, cy - 2), 180, 180);
        sh.lineTo(cx + 8, cy - 8);
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.STROKE);
        Theme.P.setStrokeWidth(3);
        Theme.P.setColor(col);
        if (closed) {
            c.drawPath(sh, Theme.P);
        } else {
            Path open = new Path();
            open.addArc(new RectF(cx - 8, cy - 26, cx + 8, cy - 6), 180, 180);
            c.drawPath(open, Theme.P);
        }
        Theme.P.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(cx - 12, cy - 6, cx + 12, cy + 12), 4, 4, Theme.P);
    }

    private void trash(Canvas c, float cx, float cy, int col) {
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(col);
        c.drawRect(cx - 9, cy - 7, cx + 9, cy + 14, Theme.P);
        c.drawRect(cx - 12, cy - 12, cx + 12, cy - 9, Theme.P);
        c.drawRect(cx - 4, cy - 15, cx + 4, cy - 12, Theme.P);
    }

    // ---- touch ----

    private static boolean in(float x, float y, float l, float t, float r, float b) {
        return x >= l && x <= r && y >= t && y <= b;
    }

    private float contentH() {
        return tab == TAB_CLIPS ? clips.size() * ROW_H : tab == TAB_MARKS ? marks.size() * ROW_H
            : tab == TAB_TRIPS ? trips.size() * ROW_H : 0;
    }

    private float clampScroll(float s) {
        return Math.max(0, Math.min(s, Math.max(0, contentH() - (LIST_B - LIST_T))));
    }

    @Override boolean touch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = x; downY = y; lastY = y; moved = false;
            dragging = tab != TAB_SETTINGS && tab != TAB_PREVIEW && tab != TAB_INTERIOR && in(x, y, 561, LIST_T, Wide.rx(1260), LIST_B);
            return true;
        }
        if (a == MotionEvent.ACTION_MOVE && dragging) {
            if (Math.abs(y - downY) > 12) moved = true;
            if (moved) scroll = clampScroll(scroll - (y - lastY));
            lastY = y;
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            boolean wasDrag = dragging;
            dragging = false;
            if (a == MotionEvent.ACTION_UP && !moved) tap(x, y, wasDrag);
        }
        return true;
    }

    private void tap(float x, float y, boolean inList) {
        if (in(x, y, 32, 548, 282, 612)) {
            if (dash.recording) dash.stop(); else dash.start();
            return;
        }
        if (in(x, y, 298, 548, 522, 612)) { dash.lock(); return; }
        if (in(x, y, 32, 464, 522, 526)) { dash.toggleMark(); refreshTicks = 0; return; }
        if (y >= 82 && y <= 122) {
            for (int i = 0; i < TABS.length; i++) if (x >= TABS[i][0] && x <= TABS[i][1]) { setTab(i); }
            return;
        }
        if (tab == TAB_SETTINGS) { settingTap(x, y); return; }
        if (tab == TAB_INTERIOR) {
            float dx = Wide.d() / 2;
            if (in(x, y, SHOT_BTN.left + dx, SHOT_BTN.top, SHOT_BTN.right + dx, SHOT_BTN.bottom)) {
                if (dash.shotAt != 0) dash.cancelShot(); else if (!dash.shooting) dash.interiorShot();
            }
            return;
        }
        if (tab == TAB_MARKS && inList) {
            int idx = (int) ((y - LIST_T + scroll) / ROW_H);
            if (idx < 0 || idx >= marks.size()) return;
            MarkInfo m = marks.get(idx);
            if (m.open) return;
            if (x >= Wide.rx(1060) && x <= Wide.rx(1172) && !m.exported()) Exporter.start(dash, m);
            else if (x >= Wide.rx(1182) && x <= Wide.rx(1228)) {
                if (m.json.getPath().equals(armed)) { dash.deleteMark(m); armed = null; refreshTicks = 0; }
                else { armed = m.json.getPath(); armedAt = SystemClock.elapsedRealtime(); }
            }
            return;
        }
        if (tab == TAB_CLIPS && inList) {
            int idx = (int) ((y - LIST_T + scroll) / ROW_H);
            if (idx < 0 || idx >= clips.size()) return;
            Clip k = clips.get(idx);
            if (dash.recording && k.file.getName().equals(dash.clipName)) return;
            if (x >= Wide.rx(1118) && x <= Wide.rx(1164)) { dash.toggleLock(k); refreshTicks = 0; }
            else if (x >= Wide.rx(1174) && x <= Wide.rx(1220)) {
                if (k.file.getPath().equals(armed)) { dash.deleteClip(k); armed = null; refreshTicks = 0; }
                else { armed = k.file.getPath(); armedAt = SystemClock.elapsedRealtime(); }
            } else open(k);
        }
    }

    /** Plays the clip in the app's own player (an outside player would leave the kiosk). */
    private void open(Clip k) { ClipPlayer.open(act, k.file); }

    private void settingTap(float x, float y) {
        if (x < Wide.rx(940) || x > Wide.rx(1246)) return;
        int i = (int) ((y - 138) / SET_H);
        switch (i) {
            case 0: DashPrefs.cycleSeg(act); break;
            case 1: DashPrefs.cycleQuality(act); break;
            case 2: DashPrefs.toggleAudio(act); break;
            case 3: DashPrefs.cycleCap(act); break;
            case 4: DashPrefs.toggleDeleteOldest(act); break;
            case 5: DashPrefs.cycleGrace(act); break;
            case 6: DashPrefs.toggleAuto(act); break;
            default: break;
        }
    }
}

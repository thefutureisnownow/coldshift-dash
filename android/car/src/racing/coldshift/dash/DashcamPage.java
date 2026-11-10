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
    private static final int TAB_CLIPS = 0, TAB_MARKS = 1, TAB_TRIPS = 2, TAB_PREVIEW = 3, TAB_SETTINGS = 4, TAB_INTERIOR = 5, TAB_CAMERA = 6;
    // rows and their buttons are sized for a finger in a car: 56 px and up, with room between
    private static final float LIST_T = 148, LIST_B = 618, ROW_H = 78, SET_H = 64;
    private static final String[] TAB_NAMES = {"Clips", "Marks", "Trips", "Preview", "Settings", "Interior", "Camera"};
    private static final float CAM_T = 216;

    /** The tabs share the panel's width. */
    private static RectF tabRect(int i) {
        float l = 569, w = (Wide.rx(1252) - l - 6 * (TAB_NAMES.length - 1)) / TAB_NAMES.length;
        return new RectF(l + i * (w + 6), 80, l + i * (w + 6) + w, 136);
    }

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
    @Override String icon() { return "dashcam"; }

    @Override int intervalMs() {
        if (tab == TAB_PREVIEW) return 110;
        if (tab == TAB_INTERIOR && (dash.shotAt != 0 || dash.shooting)) return 200;
        return dragging ? 33 : dash.recording ? 500 : 1000;
    }

    @Override void onShow() {
        shown = true;
        refreshData();
        if (tab == TAB_PREVIEW) startPreview();
        if (tab == TAB_CAMERA) dash.loadCameraOptions();
    }

    @Override void onHide() { stopPreview(); shown = false; syncLive(); }

    // ---- the live view while recording ----

    // While it records, the camera gives the app no frames to draw (startPreview's way), but it still draws
    // its preview into a texture (Dashcam.liveTexture). A TextureView over the Preview tab's picture shows
    // that: put there while the tab shows and it records, taken away otherwise. The texture is the
    // dashcam's and outlives the view.
    private android.view.TextureView live;
    private boolean shown;

    private void syncLive() {
        android.graphics.SurfaceTexture t = shown && tab == TAB_PREVIEW && !act.ui().blank() ? dash.liveTexture() : null;
        if (t == null) {
            if (live != null) { act.root().removeView(live); live = null; }
            return;
        }
        UI ui = act.ui();
        float k = Screen.k(ui);
        int l = Math.round(Screen.ox(ui) + 566 * k), tp = Math.round(Screen.oy(ui) + 146 * k);
        int w = Math.round(688 * k), hgt = Math.round(387 * k);
        if (live != null && live.getSurfaceTexture() == t) return;
        if (live != null) act.root().removeView(live);
        try {
            live = new android.view.TextureView(act);
            live.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture s, int a, int b) {}
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture s, int a, int b) {}
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture s) { return false; }   // the dashcam keeps it
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture s) {}
            });
            live.setSurfaceTexture(t);
            if (dash.flipped()) live.setRotation(180);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(w, hgt,
                android.view.Gravity.TOP | android.view.Gravity.LEFT);
            lp.leftMargin = l;
            lp.topMargin = tp;
            act.root().addView(live, 1, lp);                   // over the page, under messages, players and keyboards
        } catch (Throwable e) {
            android.util.Log.w("DashcamPage", "no live view", e);
            if (live != null) act.root().removeView(live);
            live = null;
        }
    }

    private void setTab(int i) {
        if (i == tab) return;
        if (tab == TAB_PREVIEW) stopPreview();
        tab = i;
        scroll = 0;
        armed = null;
        if (tab == TAB_PREVIEW) startPreview();
        if (tab == TAB_CAMERA) dash.loadCameraOptions();
        syncLive();
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
        syncLive();
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

    // a list let go while moving carries on and slows (a fling)
    private float vel;                  // layout px per second, down the list
    private long velAt, flingAt;

    private void fling() {
        if (dragging || vel == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(50, now - flingAt);
        flingAt = now;
        float to = clampScroll(scroll + vel * dt / 1000f);
        boolean stopped = to == scroll;
        scroll = to;
        vel *= (float) Math.pow(0.996, dt);
        if (stopped || Math.abs(vel) < 40) vel = 0;
        else act.ui().postInvalidateDelayed(16);
    }

    void draw(Canvas c) {
        fling();
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
        if (rec) Theme.text(c, clock(elapsed) + " / " + clock(dash.segLenMs), 522, 110, 20, Theme.SUB, Theme.sans, Theme.RIGHT);
        float frac = rec && dash.segLenMs > 0 ? Math.min(1f, elapsed / (float) dash.segLenMs) : 0;
        Theme.P.setColor(Theme.accent(0x38));
        c.drawRoundRect(new RectF(32, 128, 522, 134), 3, 3, Theme.P);
        Theme.P.setColor(0xffe4002b);
        c.drawRoundRect(new RectF(32, 128, 32 + 490 * frac, 134), 3, 3, Theme.P);
        String st = dash.status;
        int stc = st.startsWith("Recording") ? Theme.GREEN : (st.startsWith("Stopped") ? Theme.DIM : Theme.GOLD);
        // the heading already says Recording or Stopped: this line is for anything more
        if (st.equalsIgnoreCase(rec ? "Recording" : "Stopped")) st = "";
        if (dash.lockPending) st = st.isEmpty() ? "This clip will be locked" : st + "  \u00b7  clip will be locked";
        Theme.text(c, Theme.fit(st, 490, 18, Theme.sans), 32, 160, 18, stc, Theme.sans, Theme.LEFT);

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
            Theme.text(c, rows[i][0], 32, y, 18, Theme.DIM, Theme.sans, Theme.LEFT);
            Theme.text(c, Theme.fit(rows[i][1], 490, 20, Theme.bold), 32, y + 25, 20,
                i == 3 ? pc : 0xffffffff, Theme.bold, Theme.LEFT);
            y += 52;
        }
        String note = Exporter.busy || !Exporter.status.isEmpty() ? Exporter.status : dash.markNote;
        if (!note.isEmpty()) Theme.text(c, Theme.fit(note, 490, 18, Theme.sans), 32, 450, 18, Theme.GOLD, Theme.sans, Theme.LEFT);
        RectF bm = new RectF(32, 464, 522, 526);
        Theme.button(c, bm, false, 14);
        Theme.P.setShader(null);
        if (dash.marking) {
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(Theme.GOLD);
            c.drawRoundRect(bm, 14, 14, Theme.P);
            long ms = SystemClock.elapsedRealtime() - dash.markStartReal;
            Theme.text(c, "End mark   " + clock(ms), 277, 504, 22, 0xff05070d, Theme.bold, Theme.CENTER);
        } else {
            Theme.P.setStyle(Paint.Style.STROKE);
            Theme.P.setStrokeWidth(1.6f);
            Theme.P.setColor(rec ? 0xaae3b341 : 0x44e3b341);
            c.drawRoundRect(bm, 14, 14, Theme.P);
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.text(c, "Start mark", 277, 504, 22, rec ? Theme.GOLD : 0xff5d7090, Theme.bold, Theme.CENTER);
        }

        RectF b1 = new RectF(32, 548, 282, 612);
        Theme.button(c, b1, !rec, 14);
        if (rec) {
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(0xffa3233a);
            c.drawRoundRect(b1, 14, 14, Theme.P);
        }
        Theme.text(c, rec ? "Stop recording" : "Start recording", 157, 589, 22, 0xffffffff, Theme.bold, Theme.CENTER);
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
        for (int i = 0; i < TAB_NAMES.length; i++) {
            RectF t = tabRect(i);
            Theme.chip(c, t.left, t.top, t.right, t.bottom, TAB_NAMES[i], tab == i, 0);
        }
        if (tab == TAB_SETTINGS) { settings(c); return; }
        if (tab == TAB_CAMERA) { cameraTab(c); return; }
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
            Theme.text(c, "No recordings yet", Wide.mid(561, 1260), 380, 18, Theme.SUB, Theme.sans, Theme.CENTER);
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
        Theme.text(c, when.format(new Date(k.startMs)), 575, y + 33, 20, cur ? Theme.GREEN : 0xffe8eef7, Theme.sans, Theme.LEFT);
        Theme.text(c, cur ? "recording now" : size(k.size) + (k.locked ? "  \u00b7  locked" : ""), 575, y + 59, 18,
            k.locked ? Theme.GOLD : Theme.DIM, Theme.sans, Theme.LEFT);
        if (cur) return;
        float e = Wide.d();                                 // the row's buttons sit at the panel's right edge
        RectF p = new RectF(PLAY_L + e, y + 9, PLAY_L + 60 + e, y + 69), l = new RectF(LOCK_L + e, y + 9, LOCK_L + 60 + e, y + 69);
        RectF d = new RectF(DEL_L + e, y + 9, DEL_L + 60 + e, y + 69);
        Theme.button(c, p, false, 10);
        Icons.play(c, PLAY_L + 31 + e, y + 39, 13, 0xffdfe8f7);
        Theme.button(c, l, false, 10);
        padlock(c, LOCK_L + 30 + e, y + 43, k.locked, k.locked ? Theme.GOLD : 0xff8fa3bd);
        boolean arm = k.file.getPath().equals(armed);
        Theme.button(c, d, false, 10);
        if (arm) {
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(0xffa3233a);
            c.drawRoundRect(d, 10, 10, Theme.P);
        }
        trash(c, DEL_L + 30 + e, y + 39, arm ? 0xffffffff : 0xff8fa3bd);
        if (arm) Theme.text(c, "Tap again to delete", PLAY_L - 14 + e, y + 46, 18, 0xffff8fa3, Theme.bold, Theme.RIGHT);
    }

    // a list row's buttons, 60 wide with 14 between (in a 1280 layout; they move with the right edge)
    private static final float PLAY_L = 1044, LOCK_L = 1118, DEL_L = 1192, EXPORT_L = 1040;

    private void marksList(Canvas c) {
        if (marks.isEmpty()) {
            Theme.text(c, "No marks yet. Tap MARK while recording to log a stretch.", Wide.mid(561, 1260), 380, 18, Theme.SUB, Theme.sans, Theme.CENTER);
            return;
        }
        float y = LIST_T - scroll;
        for (MarkInfo m : marks) {
            if (y + ROW_H > LIST_T && y < LIST_B) markRow(c, m, y);
            y += ROW_H;
        }
    }

    private void markRow(Canvas c, MarkInfo m, float y) {
        Theme.text(c, m.label + "   \u00b7   " + when.format(new Date(m.start)), 575, y + 33, 20, 0xffe8eef7, Theme.sans, Theme.LEFT);
        String sub = m.open ? "recording this mark\u2026"
            : clock(m.durationMs) + "  \u00b7  " + String.format(Locale.US, "%.1f km", m.distM / 1000.0)
                + "  \u00b7  max " + Math.round(m.maxKmh) + " km/h  \u00b7  " + m.spans.length + (m.spans.length == 1 ? " clip" : " clips")
                + (m.gpx != null && m.gpx.exists() ? "  \u00b7  GPX" : "  \u00b7  no GPS");
        Theme.text(c, Theme.fit(sub, Wide.rx(EXPORT_L) - 14 - 575, 18, Theme.sans), 575, y + 59, 18, Theme.DIM, Theme.sans, Theme.LEFT);
        if (m.open) return;
        boolean busy = Exporter.busy && Exporter.busyId == m.id;
        String lab = m.exported() ? "Exported" : busy ? "Working\u2026" : "Export";
        Theme.chip(c, Wide.rx(EXPORT_L), y + 11, Wide.rx(DEL_L) - 14, y + 67, lab, false, m.exported() ? Theme.GOLD : 0);
        RectF d = new RectF(Wide.rx(DEL_L), y + 9, Wide.rx(DEL_L) + 60, y + 69);
        boolean arm = m.json.getPath().equals(armed);
        Theme.button(c, d, false, 10);
        if (arm) {
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(0xffa3233a);
            c.drawRoundRect(d, 10, 10, Theme.P);
        }
        trash(c, Wide.rx(DEL_L) + 30, y + 39, arm ? 0xffffffff : 0xff8fa3bd);
    }

    private void tripsList(Canvas c) {
        if (trips.isEmpty()) {
            Theme.text(c, "No trips yet. A trip is a recording session.", Wide.mid(561, 1260), 380, 18, Theme.SUB, Theme.sans, Theme.CENTER);
            return;
        }
        float y = LIST_T - scroll;
        for (JSONObject t : trips) {
            if (y + ROW_H > LIST_T && y < LIST_B) {
                long dur = t.optLong("dur");
                double km = t.optDouble("dist") / 1000.0;
                double avg = dur > 0 ? km / (dur / 3600000.0) : 0;
                Theme.text(c, when.format(new Date(t.optLong("start"))), 575, y + 33, 20, 0xffe8eef7, Theme.sans, Theme.LEFT);
                Theme.text(c, String.format(Locale.US, "%.1f km  \u00b7  %d min  \u00b7  max %.0f km/h  \u00b7  avg %.0f km/h",
                    km, dur / 60000, t.optDouble("max") * 3.6, avg), 575, y + 59, 18, Theme.DIM, Theme.sans, Theme.LEFT);
            }
            y += ROW_H;
        }
    }

    private static final float SET_L = 900, TEST_L = 790;       // where a settings row's value (and Test) starts

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
        RectF r = new RectF(566, 146, 1254, 533);
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
            Theme.text(c, "Still from the last finished clip, " + clock(ago * 1000) + " ago", r.left + 24, r.top + 35, 18, Theme.GOLD, Theme.sans, Theme.LEFT);
        } else {
            String st = dash.previewState;
            String msg = st.isEmpty() || st.equals("Live") ? "Starting the camera\u2026" : st;
            if (dash.recording) msg = "Live view is not available while recording on this phone";
            Theme.text(c, msg, r.centerX(), r.centerY() - 8, 18, Theme.SUB, Theme.sans, Theme.CENTER);
            if (dash.recording)
                Theme.text(c, "A still from the last finished clip appears here once the first clip ends", r.centerX(), r.centerY() + 20, 18, Theme.DIM, Theme.sans, Theme.CENTER);
        }
        Theme.P.setStyle(Paint.Style.STROKE);
        Theme.P.setStrokeWidth(1.2f);
        Theme.P.setColor(Theme.accent(0x4d));
        c.drawRect(r, Theme.P);
        Theme.P.setStyle(Paint.Style.FILL);

        float y = 556;
        Theme.text(c, "Roll", 575, y, 18, Theme.DIM, Theme.sans, Theme.LEFT);
        Theme.text(c, "Camera pitch", 800, y, 18, Theme.DIM, Theme.sans, Theme.LEFT);
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
        Theme.text(c, "For aiming only. Roll + is right side low; pitch + is looking up.", 575, 610, 18, Theme.DIM, Theme.sans, Theme.LEFT);
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
        RectF r = new RectF(566, 146, 1254, 533);
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
                r.centerX(), r.bottom - 30, 18, Theme.SUB, Theme.sans, Theme.CENTER);
        } else if (dash.shooting) {
            Theme.text(c, "Taking the photo\u2026", r.centerX(), r.centerY(), 24, Theme.GOLD, Theme.bold, Theme.CENTER);
        } else if (shot != null && newest != null && newest.equals(shotFile)) {
            float k = Math.min(r.width() / shot.getWidth(), r.height() / shot.getHeight());
            float w = shot.getWidth() * k, hh = shot.getHeight() * k;
            c.drawBitmap(shot, null, new RectF(r.centerX() - w / 2, r.centerY() - hh / 2, r.centerX() + w / 2, r.centerY() + hh / 2), Theme.BMP);
            Theme.P.setColor(0xb0000000);
            c.drawRoundRect(new RectF(r.left + 12, r.top + 12, r.left + 420, r.top + 46), 8, 8, Theme.P);
            Theme.text(c, Theme.fit(when.format(new Date(newest.lastModified())), 390, 18, Theme.sans),
                r.left + 24, r.top + 35, 18, Theme.GOLD, Theme.sans, Theme.LEFT);
        } else {
            Theme.text(c, newest == null ? "No interior shot yet" : "Loading\u2026", r.centerX(), r.centerY() - 8, 18, Theme.SUB, Theme.sans, Theme.CENTER);
            if (newest == null)
                Theme.text(c, "A clean view of the inside, taken with the front camera, for videos later", r.centerX(), r.centerY() + 20, 18, Theme.DIM, Theme.sans, Theme.CENTER);
        }
        Theme.P.setStyle(Paint.Style.STROKE);
        Theme.P.setStrokeWidth(1.2f);
        Theme.P.setColor(Theme.accent(0x4d));
        c.drawRect(r, Theme.P);
        Theme.P.setStyle(Paint.Style.FILL);

        boolean counting = dash.shotAt != 0;
        Theme.button(c, SHOT_BTN, !counting && !dash.shooting, 14);
        Theme.text(c, counting ? "Cancel" : dash.shooting ? "Taking\u2026" : "Take interior shot",
            SHOT_BTN.centerX(), SHOT_BTN.centerY() + 8, 22, 0xffffffff, Theme.bold, Theme.CENTER);
        String st = dash.shotStatus;
        Theme.text(c, Theme.fit(st.isEmpty() ? Dashcam.SHOT_DELAY_MS / 1000 + " s to get out, beeps for the last 3" : st,
            320, 18, Theme.sans), 925, 568, 18, st.startsWith("Saved") ? Theme.GREEN : st.isEmpty() ? Theme.SUB : Theme.GOLD, Theme.sans, Theme.LEFT);
        Theme.text(c, Theme.fit(shots.size() + (shots.size() == 1 ? " shot" : " shots") + "  \u00b7  recording pauses for it", 320, 18, Theme.sans),
            925, 592, 18, Theme.DIM, Theme.sans, Theme.LEFT);
    }

    private void settings(Canvas c) {
        String[][] rows = settingRows();
        float y = LIST_T;
        for (int i = 0; i < rows.length; i++) {
            Theme.text(c, rows[i][0], 575, y + 39, 20, 0xffe8eef7, Theme.sans, Theme.LEFT);
            Theme.chip(c, Wide.rx(SET_L), y + 4, Wide.rx(1246), y + 60, Theme.fit(rows[i][1], 1246 - SET_L - 20, 20, Theme.sans), false, 0);
            y += SET_H;
        }
        File d = dash.dir();
        Theme.text(c, Theme.fit("Saved to " + (d == null ? "(no storage)" : d.getPath().replace("/storage/", ""))
            + ". Locked clips are never deleted automatically.", Wide.rx(1246) - 575, 16, Theme.sans), 575, 614, 16, Theme.DIM, Theme.sans, Theme.LEFT);
    }

    // ---- what the camera offers ----

    private void cameraTab(Canvas c) {
        List<CameraOptions.Opt> all = dash.cameraOptions;
        int changes = CameraOptions.variants(act, all).size() - 1;
        Theme.text(c, "Test drive", 575, 186, 20, 0xffe8eef7, Theme.sans, Theme.LEFT);
        Theme.chip(c, 686, 152, 796, 208, DashPrefs.testMinutes(act) + " min", false, 0);
        Theme.chip(c, 806, 152, 956, 208, dash.testing ? "Stop test" : "Start test", dash.testing, 0);
        String note = !dash.cameraRejected.isEmpty() ? "Camera refused: " + dash.cameraRejected
            : changes + (changes == 1 ? " change" : " changes") + ", 20 s each, back to base between";
        Theme.text(c, Theme.fit(note, Wide.rx(1246) - 972, 18, Theme.sans), 972, 186, 18, dash.cameraRejected.isEmpty() ? Theme.DIM : Theme.GOLD, Theme.sans, Theme.LEFT);
        c.save();
        c.clipRect(561, CAM_T, Wide.rx(1260), LIST_B);
        if (all.isEmpty()) Theme.text(c, "Reading the camera\u2026", Wide.mid(561, 1260), 380, 18, Theme.SUB, Theme.sans, Theme.CENTER);
        float y = CAM_T - scroll;
        for (CameraOptions.Opt o : all) {
            if (y + SET_H > CAM_T && y < LIST_B) {
                Theme.text(c, Theme.fit(o.label, Wide.rx(TEST_L) - 10 - 575, 20, Theme.sans), 575, y + 39, 20, 0xffe8eef7, Theme.sans, Theme.LEFT);
                Theme.chip(c, Wide.rx(TEST_L), y + 4, Wide.rx(SET_L) - 10, y + 60, "Test", CameraOptions.inTest(act, o), 0);
                Theme.chip(c, Wide.rx(SET_L), y + 4, Wide.rx(1246), y + 60, Theme.fit(o.name(CameraOptions.value(act, o)), 1246 - SET_L - 20, 20, Theme.sans), false, 0);
            }
            y += SET_H;
        }
        c.restore();
    }

    private void cameraTap(float x, float y) {
        if (in(x, y, 686, 152, 796, 208)) { DashPrefs.cycleTestMinutes(act); return; }
        if (in(x, y, 806, 152, 956, 208)) {
            if (dash.testing) dash.stop(); else dash.startTest(DashPrefs.testMinutes(act));
            return;
        }
        if (y < CAM_T || y > LIST_B) return;
        List<CameraOptions.Opt> all = dash.cameraOptions;
        int i = (int) ((y - CAM_T + scroll) / SET_H);
        if (i < 0 || i >= all.size()) return;
        if (x >= Wide.rx(TEST_L) && x < Wide.rx(SET_L) - 5) CameraOptions.toggleTest(act, all, all.get(i));
        else if (x >= Wide.rx(SET_L) - 5 && x <= Wide.rx(1246)) CameraOptions.cycle(act, all.get(i));
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
            : tab == TAB_TRIPS ? trips.size() * ROW_H
            : tab == TAB_CAMERA ? dash.cameraOptions.size() * SET_H + (CAM_T - LIST_T) : 0;
    }

    private float clampScroll(float s) {
        return Math.max(0, Math.min(s, Math.max(0, contentH() - (LIST_B - LIST_T))));
    }

    @Override boolean touch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = x; downY = y; lastY = y; moved = false;
            vel = 0;                                            // a touch stops a list that is still moving
            velAt = SystemClock.uptimeMillis();
            dragging = tab != TAB_SETTINGS && tab != TAB_PREVIEW && tab != TAB_INTERIOR && in(x, y, 561, LIST_T, Wide.rx(1260), LIST_B);
            return true;
        }
        if (a == MotionEvent.ACTION_MOVE && dragging) {
            if (Math.abs(y - downY) > 12) moved = true;
            if (moved) scroll = clampScroll(scroll - (y - lastY));
            long now = SystemClock.uptimeMillis();
            if (now > velAt) vel = 0.5f * vel + 0.5f * (-(y - lastY) * 1000f / (now - velAt));
            velAt = now;
            lastY = y;
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            boolean wasDrag = dragging;
            dragging = false;
            if (!moved || Math.abs(vel) < 250 || SystemClock.uptimeMillis() - velAt > 120) vel = 0;
            flingAt = SystemClock.uptimeMillis();
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
        if (y >= 76 && y <= 140) {
            for (int i = 0; i < TAB_NAMES.length; i++) if (x >= tabRect(i).left - 3 && x <= tabRect(i).right + 3) { setTab(i); }
            return;
        }
        if (tab == TAB_SETTINGS) { settingTap(x, y); return; }
        if (tab == TAB_CAMERA) { cameraTap(x, y); return; }
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
            if (x >= Wide.rx(EXPORT_L) && x < Wide.rx(DEL_L) - 7 && !m.exported()) Exporter.start(dash, m);
            else if (x >= Wide.rx(DEL_L) - 7 && x <= Wide.rx(DEL_L) + 66) {
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
            if (x >= Wide.rx(LOCK_L) - 7 && x < Wide.rx(DEL_L) - 7) { dash.toggleLock(k); refreshTicks = 0; }
            else if (x >= Wide.rx(DEL_L) - 7 && x <= Wide.rx(DEL_L) + 66) {
                if (k.file.getPath().equals(armed)) { dash.deleteClip(k); clips.remove(idx); armed = null; refreshTicks = 0; }
                else { armed = k.file.getPath(); armedAt = SystemClock.elapsedRealtime(); }
            } else open(k);
        }
    }

    /** Plays the clip in the app's own player (an outside player would leave the kiosk). */
    private void open(Clip k) { ClipPlayer.open(act, k.file); }

    private void settingTap(float x, float y) {
        if (x < Wide.rx(SET_L) || x > Wide.rx(1246) || y < LIST_T) return;
        int i = (int) ((y - LIST_T) / SET_H);
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

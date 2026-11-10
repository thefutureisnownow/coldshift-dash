package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.location.Location;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Track days: segments defined by driving them (Record segment, then Finish at the end; ending where it
 * began makes a lap), and track mode on one of them: a timer from the start line to the finish line (or
 * lap after lap), the delta against the best run at the same distance, and every run kept (TrackStore).
 * With the dashcam recording, a run is also a mark: a lossless clip from just before the start line to
 * just after the finish (for laps, one mark for the session). Timing keeps going on other pages.
 */
class TrackPage extends Page implements Gps.Listener {
    private static final RectF RECORD = new RectF(30, 545, 454, 607);
    private static final RectF FINISH = new RectF(30, 545, 290, 607), CANCEL = new RectF(302, 545, 454, 607);
    private static final RectF START = Wide.right(new RectF(930, 545, 1250, 607)), DELETE = new RectF(500, 545, 760, 607);
    private static final RectF STOP = Wide.right(new RectF(1010, 545, 1250, 607));
    private static final RectF PREVIEW = Wide.right(new RectF(990, 92, 1250, 352));     // the segment's map
    private static final RectF MAP_RUN = Wide.right(new RectF(800, 130, 1250, 525));
    private static final RectF MAP_LAP = Wide.right(new RectF(720, 130, 990, 525));
    private static final float ROWS_TOP = 112, ROW_H = 70;
    private static final int VISIBLE_ROWS = 6;

    private final TrackTimer timer = new TrackTimer();
    private final TrackStore store;
    private List<TrackTimer.Segment> segments = new ArrayList<TrackTimer.Segment>();
    private List<TrackStore.RunInfo> runs = new ArrayList<TrackStore.RunInfo>();
    private final List<TrackTimer.Run> session = new ArrayList<TrackTimer.Run>();   // runs since track mode began
    private TrackTimer.Segment selected;
    private long bestMs;
    private boolean shown, listening, confirmDelete;
    private long recStartReal, lastFixReal;
    private int markId;
    private String note = "";
    private Location last;

    TrackPage(Main a) {
        super(a);
        store = new TrackStore(a);
    }

    String label() { return "Track"; }

    @Override boolean wide() { return true; }

    @Override float overlay() { return 0.72f; }

    @Override int intervalMs() { return timer.running() ? 100 : 1000; }

    @Override void onShow() {
        shown = true;
        listen(true);
        reload();
    }

    @Override void onHide() {
        shown = false;
        if (timer.state == TrackTimer.State.IDLE) listen(false);     // timing goes on behind other pages
    }

    private void listen(boolean on) {
        if (on && !listening) Gps.get(act).add(this);
        if (!on && listening) Gps.get(act).remove(this);
        listening = on;
    }

    /** New segments from the desktop (Main's SYNCED "track"). */
    void refresh() {
        if (timer.state == TrackTimer.State.IDLE) reload();
    }

    private void reload() {
        segments = store.segments();
        String want = Prefs.sp(act).getString("track_segment", "");
        selected = null;
        for (TrackTimer.Segment s : segments) if (s.id.equals(want)) selected = s;
        if (selected == null && !segments.isEmpty()) selected = segments.get(0);
        loadRuns();
    }

    private void loadRuns() {
        runs = selected == null ? new ArrayList<TrackStore.RunInfo>() : store.runs(selected.id);
        bestMs = 0;
        for (TrackStore.RunInfo r : runs) if (bestMs == 0 || r.timeMs < bestMs) bestMs = r.timeMs;
    }

    private void select(TrackTimer.Segment s) {
        selected = s;
        confirmDelete = false;
        Prefs.sp(act).edit().putString("track_segment", s.id).apply();
        loadRuns();
    }

    // ---- fixes ----

    public void onFix(Location l) {
        last = l;
        lastFixReal = SystemClock.elapsedRealtime();
        TrackTimer.Event e = timer.onFix(l.getLatitude(), l.getLongitude(), l.getTime(), l.hasSpeed() ? l.getSpeed() : 0);
        if (e == TrackTimer.Event.STARTED) started();
        else if (e == TrackTimer.Event.FINISHED) finished(timer.finished);
        else if (e == TrackTimer.Event.ABANDONED) {
            note = "Run stopped: you left the segment (more than " + Math.round(TrackTimer.OFF_M) + " m off it).";
            if (!selected.lap) endMark(0);
        }
    }

    private void started() {
        note = "";
        if (markId == 0) {
            String label = selected.name + (selected.lap ? " · laps" : "");
            markId = Dashcam.get(act).startTrackMark(label, 3000);
        }
    }

    private void finished(TrackTimer.Run r) {
        store.saveRun(r, markId);
        session.add(0, r);
        boolean best = bestMs == 0 || r.timeMs < bestMs;
        note = (selected.lap ? "Lap " + r.lap + ": " : "Run: ") + TrackTimer.time(r.timeMs / 1000.0)
            + (best ? "  — best" : "  (" + delta(r.timeMs - bestMs) + ")");
        if (!selected.lap) endMark(3000);
        loadRuns();
    }

    private void endMark(int afterMs) {
        if (markId != 0) Dashcam.get(act).endTrackMark(markId, afterMs);
        markId = 0;
    }

    private static String delta(long ms) {
        return (ms < 0 ? "−" : "+") + String.format(Locale.US, "%.1f", Math.abs(ms) / 1000.0);
    }

    // ---- drawing ----

    void draw(Canvas c) {
        if (timer.state == TrackTimer.State.ARMED || timer.state == TrackTimer.State.RUNNING) {
            trackMode(c);
            return;
        }
        Theme.panel(c, 14, 68, 470, 625);
        Theme.text(c, "Segments", 31, 100, 22, 0xffffffff, Theme.bold, Theme.LEFT);
        if (timer.state == TrackTimer.State.RECORDING) {
            Theme.button(c, FINISH, true, 12);
            Theme.text(c, "Finish", FINISH.centerX(), FINISH.centerY() + 8, 22, 0xffffffff, Theme.bold, Theme.CENTER);
            Theme.button(c, CANCEL, false, 12);
            Theme.text(c, "Cancel", CANCEL.centerX(), CANCEL.centerY() + 8, 22, Theme.TEXT, Theme.sans, Theme.CENTER);
        } else {
            Theme.button(c, RECORD, false, 12);
            Theme.text(c, "●  Record segment", RECORD.centerX(), RECORD.centerY() + 8, 22, Theme.TEXT, Theme.bold,
                Theme.CENTER);
        }
        for (int i = 0; i < Math.min(segments.size(), VISIBLE_ROWS); i++) {
            TrackTimer.Segment s = segments.get(i);
            RectF r = new RectF(26, ROWS_TOP + i * ROW_H, 458, ROWS_TOP + i * ROW_H + ROW_H - 8);
            Theme.button(c, r, s == selected, 12);
            Theme.text(c, Theme.fit(s.name, 400, 20, Theme.bold), r.left + 16, r.top + 28, 20, 0xffffffff, Theme.bold,
                Theme.LEFT);
            Theme.text(c, (s.lap ? "Lap" : "Point to point") + " · " + km(s.length()), r.left + 16, r.top + 52, 18,
                Theme.SUB, Theme.sans, Theme.LEFT);
        }
        if (segments.isEmpty() && timer.state != TrackTimer.State.RECORDING) {
            Theme.text(c, "No segments yet.", 31, 140, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        }

        Theme.panel(c, 484, 68, Wide.rx(1266), 625);
        if (timer.state == TrackTimer.State.RECORDING) recording(c);
        else if (selected == null) intro(c);
        else details(c);
    }

    private static String km(double m) {
        return m < 1000 ? Math.round(m) + " m" : String.format(Locale.US, "%.2f km", m / 1000);
    }

    private void intro(Canvas c) {
        Theme.text(c, "Track days", 510, 116, 30, 0xffffffff, Theme.bold, Theme.LEFT);
        String[] lines = {
            "Drive to where a segment starts and press Record segment.",
            "Drive it, then press Finish where it ends. End where you began for a lap.",
            "Then pick it and press Start track mode: the timer starts on the start line,",
            "stops on the finish line and shows how you compare with your best run.",
            "",
            "With the dashcam recording, each run is also saved as a mark (a clip).",
            "For closed tracks only."};
        for (int i = 0; i < lines.length; i++)
            Theme.text(c, lines[i], 510, 166 + i * 30, 18, i == lines.length - 1 ? Theme.GOLD : Theme.TEXT, Theme.sans, Theme.LEFT);
    }

    private void recording(Canvas c) {
        Theme.text(c, "Recording a segment", 510, 116, 30, 0xffffffff, Theme.bold, Theme.LEFT);
        long s = (SystemClock.elapsedRealtime() - recStartReal) / 1000;
        Theme.text(c, km(timer.recordedMetres()), 510, 230, 72, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, s / 60 + ":" + String.format(Locale.US, "%02d", s % 60), 510, 290, 34, Theme.SUB, Theme.bold, Theme.LEFT);
        Theme.text(c, "Drive the segment, then press Finish where it ends.", 510, 360, 18, Theme.TEXT, Theme.sans, Theme.LEFT);
        Theme.text(c, "End within 50 m of where you began to make it a lap.", 510, 390, 18, Theme.TEXT, Theme.sans, Theme.LEFT);
        if (last == null) Theme.text(c, "Waiting for GPS…", 510, 440, 18, Theme.GOLD, Theme.bold, Theme.LEFT);
    }

    private void details(Canvas c) {
        TrackTimer.Segment s = selected;
        Theme.text(c, Theme.fit(s.name, 740, 30, Theme.bold), 510, 116, 30, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, (s.lap ? "Lap" : "Point to point") + " · " + km(s.length()), 510, 146, 18, Theme.SUB,
            Theme.sans, Theme.LEFT);
        Theme.text(c, bestMs > 0 ? TrackTimer.time(bestMs / 1000.0) : "No runs yet", 510, 230, 64, 0xffffffff,
            Theme.bold, Theme.LEFT);
        if (bestMs > 0) Theme.text(c, "best of " + runs.size() + (runs.size() == 1 ? " run" : " runs"), 510, 262, 18,
            Theme.SUB, Theme.sans, Theme.LEFT);
        SimpleDateFormat when = new SimpleDateFormat("d MMM  HH:mm", Locale.US);
        for (int i = 0; i < Math.min(runs.size(), 6); i++) {
            TrackStore.RunInfo r = runs.get(i);
            float y = 312 + i * 36;
            Theme.text(c, when.format(new Date(r.start)) + (r.lap > 0 ? "  · lap " + r.lap : ""), 510, y, 18,
                Theme.SUB, Theme.sans, Theme.LEFT);
            Theme.text(c, TrackTimer.time(r.timeMs / 1000.0), 790, y, 20, r.timeMs == bestMs ? Theme.GOLD : 0xffffffff,
                Theme.bold, Theme.RIGHT);
            if (r.timeMs != bestMs) Theme.text(c, delta(r.timeMs - bestMs), 870, y, 18, Theme.BAD, Theme.sans, Theme.RIGHT);
            if (r.mark > 0) Theme.text(c, "clip " + r.mark, 890, y, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        }
        map(c, PREVIEW, s, 0);
        Theme.button(c, START, true, 12);
        Theme.text(c, "Start track mode", START.centerX(), START.centerY() + 8, 22, 0xffffffff, Theme.bold, Theme.CENTER);
        Theme.button(c, DELETE, false, 12);
        Theme.text(c, confirmDelete ? "Tap again to delete" : "Delete segment", DELETE.centerX(), DELETE.centerY() + 7, 18,
            confirmDelete ? Theme.BAD : Theme.SUB, Theme.sans, Theme.CENTER);
        if (!note.isEmpty()) Theme.text(c, note, 510, 530, 18, Theme.GOLD, Theme.bold, Theme.LEFT);
    }

    private void trackMode(Canvas c) {
        Theme.panel(c, 14, 68, Wide.rx(1266), 625);
        TrackTimer.Segment s = timer.seg;
        boolean run = timer.running();
        Theme.text(c, Theme.fit(s.name, 700, 24, Theme.bold) + (s.lap && timer.lap() > 0 ? "  ·  lap " + timer.lap() : ""),
            34, 108, 24, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, "Track mode · closed tracks only", Wide.rx(1246), 108, 18, Theme.GOLD, Theme.sans, Theme.RIGHT);
        float cx = (s.lap ? 380 : 430) + Wide.d() / 2;         // the timer in the middle of what the map leaves
        map(c, s.lap ? MAP_LAP : MAP_RUN, s, timer.along());
        if (run) {
            // between fixes the clock runs on from the last one, so it moves smoothly
            double t = timer.elapsed + (SystemClock.elapsedRealtime() - lastFixReal) / 1000.0;
            Theme.text(c, TrackTimer.time(t), cx, 330, 150, 0xffffffff, Theme.bold, Theme.CENTER);
            if (!Double.isNaN(timer.delta)) {
                double d = timer.delta;
                Theme.text(c, (d < 0 ? "−" : "+") + String.format(Locale.US, "%.1f", Math.abs(d)), cx, 440, 80,
                    d < 0 ? Theme.GREEN : Theme.BAD, Theme.bold, Theme.CENTER);
            } else {
                Theme.text(c, "first run: no best to compare with yet", cx, 420, 22, Theme.SUB, Theme.sans, Theme.CENTER);
            }
        } else {
            Theme.text(c, "Drive to the start line", cx, 280, 48, 0xffffffff, Theme.bold, Theme.CENTER);
            String d = Double.isNaN(timer.toStart) ? (last == null ? "Waiting for GPS…" : "")
                : km(timer.toStart) + " away";
            Theme.text(c, d, cx, 350, 36, Theme.SUB, Theme.bold, Theme.CENTER);
            Theme.text(c, "The timer starts when you cross it.", cx, 410, 20, Theme.SUB, Theme.sans, Theme.CENTER);
        }
        Theme.text(c, last == null ? "-- km/h" : Math.round(last.getSpeed() * 3.6f) + " km/h", 34, 600, 30, Theme.TEXT,
            Theme.bold, Theme.LEFT);
        if (!note.isEmpty()) Theme.text(c, note, cx, 520, 24, Theme.GOLD, Theme.bold, Theme.CENTER);
        if (s.lap) laps(c);
        Theme.button(c, STOP, false, 12);
        Theme.text(c, "Stop", STOP.centerX(), STOP.centerY() + 8, 22, Theme.BAD, Theme.bold, Theme.CENTER);
    }

    private void map(Canvas c, RectF box, TrackTimer.Segment s, double along) {
        Theme.P.setShader(null);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        Theme.P.setColor(0x66000000);
        c.drawRoundRect(box, 12, 12, Theme.P);
        SegmentMap.draw(c, box, s, along, last == null ? 0 : last.getLatitude(), last == null ? 0 : last.getLongitude(),
            last != null);
    }

    private void laps(Canvas c) {
        Theme.panel(c, Wide.rx(1000), 130, Wide.rx(1250), 525);
        Theme.text(c, "Laps", Wide.rx(1018), 164, 20, 0xffffffff, Theme.bold, Theme.LEFT);
        long best = 0;
        for (TrackTimer.Run r : session) if (best == 0 || r.timeMs < best) best = r.timeMs;
        for (int i = 0; i < Math.min(session.size(), 9); i++) {
            TrackTimer.Run r = session.get(i);
            float y = 204 + i * 36;
            Theme.text(c, String.valueOf(r.lap), Wide.rx(1018), y, 18, Theme.SUB, Theme.sans, Theme.LEFT);
            Theme.text(c, TrackTimer.time(r.timeMs / 1000.0), Wide.rx(1232), y, 20, r.timeMs == best ? Theme.GOLD : 0xffffffff,
                Theme.bold, Theme.RIGHT);
        }
        if (session.isEmpty()) Theme.text(c, "Times appear here", Wide.rx(1018), 204, 18, Theme.SUB, Theme.sans, Theme.LEFT);
    }

    // ---- touch ----

    @Override boolean touch(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        float x = e.getX(), y = e.getY();
        TrackTimer.State st = timer.state;
        if (st == TrackTimer.State.ARMED || st == TrackTimer.State.RUNNING) {
            if (STOP.contains(x, y)) stopTrackMode();
            return true;
        }
        if (st == TrackTimer.State.RECORDING) {
            if (FINISH.contains(x, y)) finishRecording();
            else if (CANCEL.contains(x, y)) {
                timer.stop();
                note = "";
            }
            return true;
        }
        if (RECORD.contains(x, y)) {
            timer.startRecording();
            recStartReal = SystemClock.elapsedRealtime();
            listen(true);
            return true;
        }
        for (int i = 0; i < Math.min(segments.size(), VISIBLE_ROWS); i++) {
            if (x >= 26 && x <= 458 && y >= ROWS_TOP + i * ROW_H && y <= ROWS_TOP + i * ROW_H + ROW_H - 8) {
                select(segments.get(i));
                return true;
            }
        }
        if (selected != null && START.contains(x, y)) {
            session.clear();
            note = "";
            timer.arm(selected, store.best(selected.id));
            listen(true);
        } else if (selected != null && DELETE.contains(x, y)) {
            if (confirmDelete) {
                store.delete(selected);
                confirmDelete = false;
                reload();
            } else {
                confirmDelete = true;
            }
        }
        return true;
    }

    private void stopTrackMode() {
        timer.stop();
        endMark(1000);
        note = session.isEmpty() ? "" : session.size() + (session.size() == 1 ? " run" : " runs") + " saved.";
        if (!shown) listen(false);
        loadRuns();
    }

    private void finishRecording() {
        final TrackTimer.Segment s = timer.finishRecording();
        if (s == null) {
            note = "Too short for a segment (at least " + Math.round(TrackTimer.MIN_LENGTH_M) + " m).";
            return;
        }
        s.name = "Segment " + (segments.size() + 1);
        save(s);
        // a better name from the map, when there is one: the street for a run, the area for a lap
        new Thread(new Runnable() {
            public void run() {
                try {
                    Geocoder.Place p = new Geocoder().reverse(s.lat[0], s.lon[0], 0);
                    String area = p.sub.contains(", ") ? p.sub.substring(0, p.sub.indexOf(", ")) : p.sub;
                    String name = s.lap ? (area.isEmpty() ? p.title : area) + " lap" : p.title;
                    if (name.trim().length() > 4) {
                        s.name = name.trim();
                        act.runOnUiThread(new Runnable() { public void run() { save(s); } });
                    }
                } catch (Throwable t) {
                    android.util.Log.w("TrackPage", "no name from the map", t);
                }
            }
        }).start();
    }

    private void save(TrackTimer.Segment s) {
        try {
            store.save(s);
            note = "Saved " + s.name + " (" + (s.lap ? "lap" : "point to point") + ", " + km(s.length()) + ").";
        } catch (Exception e) {
            note = "Could not save the segment: " + e.getMessage();
        }
        reload();
        select(s);
    }
}

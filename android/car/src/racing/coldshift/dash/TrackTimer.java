package racing.coldshift.dash;

import java.util.ArrayList;
import java.util.List;

/**
 * Timing over a segment for track days, fed one GPS fix at a time. Plain Java (no Android), like Nav, so
 * it can be driven on a computer.
 *
 * A segment is a path someone drove: from its start line to its finish line, or a lap when it ends where
 * it began (the finish line is then the start line). A line (Gate) is GATE_W wide, across the path at
 * right angles; it is crossed going from behind it to in front of it within its width, and the moment is
 * found between the two fixes either side, which gets about a tenth of a second from a once-a-second GPS.
 *
 * While a run is on, each fix is matched to the segment's path for the distance covered, and the time is
 * compared with the best run's time at that distance (the delta). Leaving the path (more than OFF_M for
 * OFF_FIXES fixes) abandons the run. A lap finishes only once most of it (LAP_SHARE) has been covered, so
 * crossing the line to start cannot end it.
 */
final class TrackTimer {
    static final double GATE_W = 40, OFF_M = 80, LAP_SHARE = 0.7, LAP_JOIN_M = 50, MIN_LENGTH_M = 150;
    static final int OFF_FIXES = 5;
    private static final double R = 6371000;

    /** A line across the path: its middle and the direction of travel through it. */
    static final class Gate {
        final double lat, lon, heading;          // heading in degrees, clockwise from north
        Gate(double lat, double lon, double heading) {
            this.lat = lat;
            this.lon = lon;
            this.heading = heading;
        }
    }

    static final class Segment {
        String id = "", name = "";
        boolean lap;
        double[] lat, lon, cum;                  // the path and the metres along it at each point
        Gate start, finish;

        double length() { return cum[cum.length - 1]; }
    }

    /** A finished run (or lap): its time and, at each fix, the time and distance from the start. */
    static final class Run {
        String segment = "";
        long startEpoch, timeMs;
        int lap;                                 // 1, 2, ... in a lap session; 0 for a point-to-point run
        double[] t, d, speed;                    // seconds and metres from the start, m/s
    }

    enum State { IDLE, RECORDING, ARMED, RUNNING }

    State state = State.IDLE;
    Segment seg;
    private Run best;                            // to compare with (the delta)
    // recording
    private final List<double[]> rec = new ArrayList<double[]>();
    // a run
    private double pLat, pLon;
    private long pT;
    private boolean havePrev;
    private long runStart;                       // ms, the moment the start line was crossed
    private long runStartEpoch;
    private int idx, offCount, lapNo;
    private double along;
    private final List<double[]> trace = new ArrayList<double[]>();
    // shown on the page
    double elapsed, delta = Double.NaN, speed, toStart = Double.NaN, offBy;

    // ---- geometry ----

    static double dist(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * R;
    }

    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double y = Math.sin(Math.toRadians(lon2 - lon1)) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
            - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(Math.toRadians(lon2 - lon1));
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** {along the heading, across it} in metres from the gate's middle. */
    private static double[] local(Gate g, double lat, double lon) {
        double e = Math.toRadians(lon - g.lon) * Math.cos(Math.toRadians(g.lat)) * R;
        double n = Math.toRadians(lat - g.lat) * R;
        double h = Math.toRadians(g.heading);
        return new double[] {e * Math.sin(h) + n * Math.cos(h), e * Math.cos(h) - n * Math.sin(h)};
    }

    /** Where between two fixes (0..1) the gate was crossed going forwards, or -1. */
    static double crossing(Gate g, double lat1, double lon1, double lat2, double lon2) {
        double[] a = local(g, lat1, lon1), b = local(g, lat2, lon2);
        if (!(a[0] < 0 && b[0] >= 0)) return -1;
        double f = -a[0] / (b[0] - a[0]);
        double across = a[1] + f * (b[1] - a[1]);
        return Math.abs(across) <= GATE_W / 2 ? f : -1;
    }

    /** The direction of the path around point i (over about 20 m), for a gate there. */
    private static double headingAt(double[] lat, double[] lon, double[] cum, int i) {
        int a = i, b = i;
        while (a > 0 && cum[i] - cum[a] < 10) a--;
        while (b < cum.length - 1 && cum[b] - cum[i] < 10) b++;
        if (a == b) b = Math.min(cum.length - 1, a + 1);
        return bearing(lat[a], lon[a], lat[b], lon[b]);
    }

    // ---- recording a segment ----

    void startRecording() {
        rec.clear();
        state = State.RECORDING;
    }

    double recordedMetres() {
        double m = 0;
        for (int i = 1; i < rec.size(); i++) m += dist(rec.get(i - 1)[0], rec.get(i - 1)[1], rec.get(i)[0], rec.get(i)[1]);
        return m;
    }

    int recordedFixes() { return rec.size(); }

    /** The segment just driven, or null when it is too short; a lap when it ends near where it began. */
    Segment finishRecording() {
        state = State.IDLE;
        if (rec.size() < 3 || recordedMetres() < MIN_LENGTH_M) return null;
        double[] lat = new double[rec.size()], lon = new double[rec.size()];
        for (int i = 0; i < lat.length; i++) {
            lat[i] = rec.get(i)[0];
            lon[i] = rec.get(i)[1];
        }
        return make(lat, lon);
    }

    /** A segment from its path: the distances along it, whether it is a lap, and its lines. */
    static Segment make(double[] lat, double[] lon) {
        Segment s = new Segment();
        int n = lat.length;
        s.lat = lat;
        s.lon = lon;
        s.cum = new double[n];
        for (int i = 1; i < n; i++) s.cum[i] = s.cum[i - 1] + dist(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        s.lap = dist(lat[0], lon[0], lat[n - 1], lon[n - 1]) < LAP_JOIN_M && s.length() > 4 * LAP_JOIN_M;
        s.start = new Gate(lat[0], lon[0], headingAt(lat, lon, s.cum, 0));
        s.finish = s.lap ? s.start : new Gate(lat[n - 1], lon[n - 1], headingAt(lat, lon, s.cum, n - 1));
        return s;
    }

    // ---- timing ----

    /** Waits for the start line of `s`; `best` (or null) is what the delta compares with. */
    void arm(Segment s, Run best) {
        seg = s;
        this.best = best;
        state = State.ARMED;
        havePrev = false;
        lapNo = 0;
        delta = Double.NaN;
        elapsed = 0;
    }

    void stop() {
        state = State.IDLE;
        seg = null;
    }

    boolean running() { return state == State.RUNNING; }

    int lap() { return lapNo; }

    /** Metres along the segment covered in this run (0 when not running). */
    double along() { return state == State.RUNNING ? along : 0; }

    /** What a fix did: nothing, crossed the start, finished a run (`finished` then holds it), or abandoned it. */
    enum Event { NONE, STARTED, FINISHED, ABANDONED }

    Run finished;

    Event onFix(double lat, double lon, long tMs, double speedMs) {
        speed = speedMs;
        if (state == State.RECORDING) {
            rec.add(new double[] {lat, lon});
            return Event.NONE;
        }
        if (seg == null || state == State.IDLE) return Event.NONE;
        Event ev = Event.NONE;
        if (havePrev) {
            if (state == State.ARMED) {
                double f = crossing(seg.start, pLat, pLon, lat, lon);
                if (f >= 0) {
                    begin(pT + Math.round(f * (tMs - pT)), tMs);
                    ev = Event.STARTED;
                }
            } else {
                double f = crossing(seg.finish, pLat, pLon, lat, lon);
                if (f >= 0 && along >= seg.length() * (seg.lap ? LAP_SHARE : 0.5)) {
                    long at = pT + Math.round(f * (tMs - pT));
                    finished = finish(at);
                    ev = Event.FINISHED;
                    if (seg.lap) begin(at, tMs);            // the next lap starts on the same line
                    else state = State.ARMED;
                }
            }
        }
        if (state == State.RUNNING) {               // a finished point-to-point run is back to ARMED
            follow(lat, lon, tMs);
            if (offCount >= OFF_FIXES) {
                state = State.ARMED;
                ev = Event.ABANDONED;
            }
        }
        toStart = state == State.ARMED ? dist(lat, lon, seg.start.lat, seg.start.lon) : Double.NaN;
        pLat = lat;
        pLon = lon;
        pT = tMs;
        havePrev = true;
        return ev;
    }

    private void begin(long at, long now) {
        state = State.RUNNING;
        runStart = at;
        runStartEpoch = System.currentTimeMillis() - (now - at);
        idx = 0;
        along = 0;
        offCount = 0;
        lapNo = seg.lap ? lapNo + 1 : 0;
        trace.clear();
        trace.add(new double[] {0, 0, speed});
        delta = Double.NaN;
    }

    /** Matches a fix to the path: the distance covered, the time and the delta. */
    private void follow(double lat, double lon, long tMs) {
        int n = seg.lat.length;
        int lo = Math.max(0, idx - 2), hi = Math.min(n - 2, idx + 60);
        double best = Double.MAX_VALUE, bestAlong = along;
        int bestI = idx;
        double cos = Math.cos(Math.toRadians(lat));
        for (int i = lo; i <= hi; i++) {
            double ax = (seg.lon[i] - lon) * cos, ay = seg.lat[i] - lat;
            double bx = (seg.lon[i + 1] - lon) * cos, by = seg.lat[i + 1] - lat;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double px = ax + t * dx, py = ay + t * dy;
            double d = Math.toRadians(Math.sqrt(px * px + py * py)) * R;
            if (d < best) {
                best = d;
                bestI = i;
                bestAlong = seg.cum[i] + t * (seg.cum[i + 1] - seg.cum[i]);
            }
        }
        offBy = best;
        offCount = best > OFF_M ? offCount + 1 : 0;
        if (bestAlong >= along - 5) {            // GPS noise may step back a little; never far
            idx = bestI;
            along = Math.max(along, bestAlong);
        }
        elapsed = (tMs - runStart) / 1000.0;
        trace.add(new double[] {elapsed, along, speed});
        delta = this.best == null ? Double.NaN : elapsed - timeAt(this.best, along);
    }

    /** The best run's time at a distance (NaN beyond its end). */
    static double timeAt(Run r, double metres) {
        if (r.d == null || r.d.length < 2 || metres > r.d[r.d.length - 1]) return Double.NaN;
        for (int i = 1; i < r.d.length; i++) {
            if (r.d[i] >= metres) {
                double span = r.d[i] - r.d[i - 1];
                double f = span <= 0 ? 0 : (metres - r.d[i - 1]) / span;
                return r.t[i - 1] + f * (r.t[i] - r.t[i - 1]);
            }
        }
        return r.t[r.t.length - 1];
    }

    private Run finish(long at) {
        Run r = new Run();
        r.segment = seg.id;
        r.startEpoch = runStartEpoch;
        r.timeMs = at - runStart;
        r.lap = lapNo;
        double total = r.timeMs / 1000.0;
        trace.add(new double[] {total, seg.length(), speed});
        int n = trace.size();
        r.t = new double[n];
        r.d = new double[n];
        r.speed = new double[n];
        for (int i = 0; i < n; i++) {
            r.t[i] = Math.min(trace.get(i)[0], total);
            r.d[i] = Math.min(trace.get(i)[1], seg.length());
            r.speed[i] = trace.get(i)[2];
        }
        elapsed = total;
        if (best == null || r.timeMs < best.timeMs) best = r;      // the next lap compares with this one
        return r;
    }

    static String time(double s) {
        if (Double.isNaN(s)) return "-";
        int m = (int) (s / 60);
        double rest = s - m * 60;
        return m > 0 ? String.format(java.util.Locale.US, "%d:%04.1f", m, rest) : String.format(java.util.Locale.US, "%.1f", rest);
    }
}

package racing.coldshift.dash;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Track segments and their runs, as JSON in the app's folder on the card (beside places.json, where the
 * desktop tool can copy them), else in internal storage:
 *
 *     track/segments/<id>.json   {"version": 1, "id", "name", "created", "points": [[lat, lon], ...]}
 *     track/runs/<id>/<start>-<lap>.json
 *                                {"version": 1, "segment", "start", "time_ms", "lap", "mark",
 *                                 "t": [s...], "d": [m...], "speed": [m/s...]}   one entry per fix
 *
 * A segment's lines and whether it is a lap are worked out again from its points (TrackTimer.make).
 *
 * The desktop tool sends segments (renamed, or imported from someone else) to
 * <volume>/coldshift.dash/track/segments/; they are taken in here (new ones copied, names updated),
 * except those deleted on the phone.
 */
final class TrackStore {
    static final class RunInfo {
        long start, timeMs;
        int lap, mark;
        File file;
    }

    private final File root;
    private final android.content.SharedPreferences prefs;

    TrackStore(Context c) {
        prefs = Prefs.sp(c);
        File dir = Storage.appDir(c);                       // the card's, when there is one
        root = new File(dir != null ? dir : c.getFilesDir(), "track");
    }

    private File segDir() { return new File(root, "segments"); }

    private File runDir(String id) { return new File(new File(root, "runs"), id); }

    /** Takes in the segments the desktop put on the card. */
    private void takeIn() {
        java.util.Set<String> deleted = prefs.getStringSet("track_deleted", new java.util.HashSet<String>());
        for (String v : Storage.volumes()) {
            File[] fs = new File(v, Storage.DIR + "/track/segments").listFiles();
            for (File f : fs == null ? new File[0] : fs) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    JSONObject o = read(f);
                    String id = o.getString("id");
                    if (deleted.contains(id)) continue;
                    File mine = new File(segDir(), id + ".json");
                    if (!mine.exists()) {
                        write(mine, o);
                    } else {
                        JSONObject m = read(mine);
                        if (!m.optString("name").equals(o.optString("name"))) {
                            m.put("name", o.optString("name"));
                            write(mine, m);
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("TrackStore", "cannot take in " + f, e);
                }
            }
        }
    }

    List<TrackTimer.Segment> segments() {
        takeIn();
        List<TrackTimer.Segment> out = new ArrayList<TrackTimer.Segment>();
        final java.util.Map<TrackTimer.Segment, Long> made = new java.util.HashMap<TrackTimer.Segment, Long>();
        File[] fs = segDir().listFiles();
        for (File f : fs == null ? new File[0] : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject o = read(f);
                JSONArray pts = o.getJSONArray("points");
                double[] lat = new double[pts.length()], lon = new double[pts.length()];
                for (int i = 0; i < lat.length; i++) {
                    lat[i] = pts.getJSONArray(i).getDouble(0);
                    lon[i] = pts.getJSONArray(i).getDouble(1);
                }
                if (lat.length < 3) continue;
                TrackTimer.Segment s = TrackTimer.make(lat, lon);
                s.id = o.getString("id");
                s.name = o.optString("name", s.id);
                made.put(s, o.optLong("created", 0));
                out.add(s);
            } catch (Exception e) {
                android.util.Log.w("TrackStore", "cannot read " + f, e);
            }
        }
        Collections.sort(out, new Comparator<TrackTimer.Segment>() {
            public int compare(TrackTimer.Segment a, TrackTimer.Segment b) { return Long.compare(made.get(b), made.get(a)); }
        });
        return out;
    }

    void save(TrackTimer.Segment s) throws Exception {
        if (s.id.isEmpty()) s.id = "seg-" + System.currentTimeMillis();
        JSONArray pts = new JSONArray();
        for (int i = 0; i < s.lat.length; i++) {
            JSONArray p = new JSONArray();
            p.put(Math.round(s.lat[i] * 1e6) / 1e6);
            p.put(Math.round(s.lon[i] * 1e6) / 1e6);
            pts.put(p);
        }
        JSONObject o = new JSONObject();
        o.put("version", 1);
        o.put("id", s.id);
        o.put("name", s.name);
        o.put("created", System.currentTimeMillis());
        o.put("points", pts);
        write(new File(segDir(), s.id + ".json"), o);
    }

    void delete(TrackTimer.Segment s) {
        java.util.Set<String> deleted = new java.util.HashSet<String>(
            prefs.getStringSet("track_deleted", new java.util.HashSet<String>()));
        deleted.add(s.id);                       // a copy the desktop put on the card must not bring it back
        prefs.edit().putStringSet("track_deleted", deleted).apply();
        new File(segDir(), s.id + ".json").delete();
        File[] fs = runDir(s.id).listFiles();
        for (File f : fs == null ? new File[0] : fs) f.delete();
        runDir(s.id).delete();
    }

    void saveRun(TrackTimer.Run r, int mark) {
        try {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            o.put("segment", r.segment);
            o.put("start", r.startEpoch);
            o.put("time_ms", r.timeMs);
            o.put("lap", r.lap);
            if (mark > 0) o.put("mark", mark);
            JSONArray t = new JSONArray(), d = new JSONArray(), v = new JSONArray();
            for (int i = 0; i < r.t.length; i++) {
                t.put(Math.round(r.t[i] * 100) / 100.0);
                d.put(Math.round(r.d[i] * 10) / 10.0);
                v.put(Math.round(r.speed[i] * 10) / 10.0);
            }
            o.put("t", t);
            o.put("d", d);
            o.put("speed", v);
            write(new File(runDir(r.segment), r.startEpoch + "-" + r.lap + ".json"), o);
        } catch (Exception e) {
            android.util.Log.w("TrackStore", "cannot save a run", e);
        }
    }

    /** The segment's runs, newest first (summaries only). */
    List<RunInfo> runs(String id) {
        List<RunInfo> out = new ArrayList<RunInfo>();
        File[] fs = runDir(id).listFiles();
        for (File f : fs == null ? new File[0] : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject o = read(f);
                RunInfo r = new RunInfo();
                r.start = o.getLong("start");
                r.timeMs = o.getLong("time_ms");
                r.lap = o.optInt("lap", 0);
                r.mark = o.optInt("mark", 0);
                r.file = f;
                out.add(r);
            } catch (Exception e) {
                android.util.Log.w("TrackStore", "cannot read " + f, e);
            }
        }
        Collections.sort(out, new Comparator<RunInfo>() {
            public int compare(RunInfo a, RunInfo b) {
                return a.start != b.start ? Long.compare(b.start, a.start) : b.lap - a.lap;
            }
        });
        return out;
    }

    /** The fastest run with its trace, for the delta; null without one. */
    TrackTimer.Run best(String id) {
        RunInfo fastest = null;
        for (RunInfo r : runs(id)) if (fastest == null || r.timeMs < fastest.timeMs) fastest = r;
        if (fastest == null) return null;
        try {
            JSONObject o = read(fastest.file);
            TrackTimer.Run r = new TrackTimer.Run();
            r.segment = id;
            r.startEpoch = o.getLong("start");
            r.timeMs = o.getLong("time_ms");
            JSONArray t = o.getJSONArray("t"), d = o.getJSONArray("d"), v = o.getJSONArray("speed");
            r.t = new double[t.length()];
            r.d = new double[t.length()];
            r.speed = new double[t.length()];
            for (int i = 0; i < r.t.length; i++) {
                r.t[i] = t.getDouble(i);
                r.d[i] = d.getDouble(i);
                r.speed[i] = v.optDouble(i, 0);
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) break;
            n += r;
        }
        in.close();
        return new JSONObject(new String(b, 0, n, "UTF-8"));
    }

    private static void write(File f, JSONObject o) throws Exception {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(o.toString().getBytes("UTF-8"));
        out.getFD().sync();
        out.close();
        if (!tmp.renameTo(f)) throw new java.io.IOException("cannot replace " + f);
    }
}

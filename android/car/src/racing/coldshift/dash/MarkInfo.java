package racing.coldshift.dash;

import java.io.File;
import java.io.FileReader;
import java.util.Comparator;
import org.json.JSONArray;
import org.json.JSONObject;

/** A mark as read back from its JSON file. */
final class MarkInfo {
    File json, gpx, mp4;
    int id;
    String label;
    long start, end, durationMs;
    double distM, maxKmh;
    boolean open;
    int[][] spans = new int[0][];   // {seq, inMs, outMs}

    boolean exported() { return mp4 != null && mp4.exists(); }

    static MarkInfo load(File f) {
        try {
            FileReader r = new FileReader(f);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            MarkInfo m = new MarkInfo();
            m.json = f;
            m.id = o.optInt("id");
            m.label = o.optString("label", "Mark");
            m.start = o.optLong("start");
            m.end = o.optLong("end");
            m.durationMs = o.optLong("durationMs");
            m.distM = o.optDouble("distanceM", 0);
            m.maxKmh = o.optDouble("maxKmh", 0);
            m.open = o.optBoolean("open");
            String g = o.optString("gpx");
            String base = f.getName().substring(0, f.getName().length() - 5);
            if (!g.isEmpty()) m.gpx = new File(f.getParentFile(), g);
            m.mp4 = new File(f.getParentFile(), base + ".mp4");
            JSONArray sp = o.optJSONArray("spans");
            if (sp != null) {
                m.spans = new int[sp.length()][3];
                for (int i = 0; i < sp.length(); i++) {
                    JSONObject s = sp.getJSONObject(i);
                    m.spans[i][0] = s.getInt("seq");
                    m.spans[i][1] = s.getInt("in");
                    m.spans[i][2] = s.getInt("out");
                }
            }
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    void deleteFiles() {
        json.delete();
        if (gpx != null) gpx.delete();
        if (mp4 != null) mp4.delete();
    }

    static class Newest implements Comparator<MarkInfo> {
        public int compare(MarkInfo a, MarkInfo b) { return b.id - a.id; }
    }
}

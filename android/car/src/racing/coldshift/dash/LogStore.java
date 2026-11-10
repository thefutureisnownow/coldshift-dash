package racing.coldshift.dash;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The log book: one JSON file per entry in the app's folder on the card (beside track/, where the desktop
 * tool copies them), else in internal storage:
 *
 *     log/entries/<id>.json   {"version": 1, "id", "type", "time", "updated", "deleted"?, ...}
 *
 *     trip      "end", "dist_m" (GPS), "max" (m/s), "from"/"to" {"name", "lat", "lon"}, "business",
 *               "purpose", "odo_start"/"odo_end" (km, estimated when it ended)
 *     fuel      "odometer" (km), "litres", "price" (per litre), "total", "grade", "full", "missed",
 *               "station", "lat", "lon"
 *     odometer  "odometer"
 *     service   "odometer", "what", "cost"
 *     reminder  "what", "every_km", "every_months" (0: not by that), "from_odometer" (with "time": when it
 *               was last done, until a service of the same name is logged)
 *
 *     log/events.txt             what the trip logger saw (trips, stops, power, fuel stations near stops)
 *     log/routes/<trip id>.csv  a trip's route: "time_ms,lat,lon,speed" lines (current.csv while it runs)
 *
 * An entry is never removed, only marked deleted, so a copy elsewhere cannot bring it back; the copy
 * with the later "updated" wins. The desktop tool sends its entries in one file,
 * <volume>/coldshift.dash/log/desktop.json ({"entries": [...]}), taken in here.
 *
 * The odometer between readings (fill-ups, readings, services) is the last reading plus the GPS distance
 * of the trips since, times a factor learned from readings far enough apart (GPS reads a little short);
 * fuel use is worked out from one full tank to the next.
 */
final class LogStore {
    static final String[] GRADES = {"95", "93", "Diesel 50", "Diesel 500"};

    private static LogStore instance;
    private final File root;
    private List<JSONObject> cache;
    private long takenIn;

    static synchronized LogStore get(Context c) {
        if (instance == null) instance = new LogStore(c.getApplicationContext());
        return instance;
    }

    private LogStore(Context c) {
        File dir = Storage.appDir(c);                       // the card's, when there is one
        root = new File(dir != null ? dir : c.getFilesDir(), "log");
    }

    private File entries() { return new File(root, "entries"); }

    static String newId(String type) {
        return type + "-" + System.currentTimeMillis() + "-" + Integer.toHexString((int) (Math.random() * 0xffff));
    }

    static JSONObject make(String type, long time) {
        JSONObject o = new JSONObject();
        try {
            o.put("version", 1);
            o.put("id", newId(type));
            o.put("type", type);
            o.put("time", time);
        } catch (Exception e) { /* cannot happen */ }
        return o;
    }

    /** Takes in what the desktop put on the card (newer copies only). */
    private void takeIn() {
        for (String v : Storage.volumes()) {
            File f = new File(v, Storage.DIR + "/log/desktop.json");
            if (!f.exists() || f.lastModified() <= takenIn) continue;
            takenIn = f.lastModified();
            try {
                JSONArray a = read(f).getJSONArray("entries");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    File mine = new File(entries(), o.getString("id") + ".json");
                    if (mine.exists() && read(mine).optLong("updated") >= o.optLong("updated")) continue;
                    write(mine, o);
                }
            } catch (Exception e) {
                android.util.Log.w("LogStore", "cannot take in " + f, e);
            }
        }
    }

    /** Every entry not deleted, newest first. */
    synchronized List<JSONObject> all() {
        if (cache != null) return cache;
        takeIn();
        List<JSONObject> out = new ArrayList<JSONObject>();
        File[] fs = entries().listFiles();
        for (File f : fs == null ? new File[0] : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject o = read(f);
                if (!o.optBoolean("deleted")) out.add(o);
            } catch (Exception e) {
                android.util.Log.w("LogStore", "cannot read " + f, e);
            }
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) { return Long.compare(b.optLong("time"), a.optLong("time")); }
        });
        cache = out;
        return out;
    }

    /** After a sync: read the files again. */
    synchronized void reload() {
        cache = null;
        takenIn = 0;
    }

    synchronized void put(JSONObject o) {
        try {
            o.put("updated", System.currentTimeMillis());
            write(new File(entries(), o.getString("id") + ".json"), o);
        } catch (Exception e) {
            android.util.Log.w("LogStore", "cannot save an entry", e);
        }
        cache = null;
    }

    synchronized void delete(JSONObject o) {
        try {
            o.put("deleted", true);
        } catch (Exception e) { /* cannot happen */ }
        put(o);
    }

    // ---- what the trip logger saw, for tuning it after real drives ----

    /** Adds a line to log/events.txt (the previous 256 KB kept as events.old.txt). */
    synchronized void event(long at, String what) {
        try {
            File f = new File(root, "events.txt");
            f.getParentFile().mkdirs();
            if (f.length() > 256 * 1024) f.renameTo(new File(root, "events.old.txt"));
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date(at))
                + "  " + what + "\n");
            w.close();
        } catch (Exception e) {
            android.util.Log.w("LogStore", "cannot note an event", e);
        }
    }

    // ---- routes ----

    private File route(String name) { return new File(new File(root, "routes"), name + ".csv"); }

    /** A new trip: its route starts empty. */
    synchronized void routeStart() { route("current").delete(); }

    synchronized void routePoint(long t, double lat, double lon, float speed) {
        try {
            File f = route("current");
            f.getParentFile().mkdirs();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(String.format(java.util.Locale.US, "%d,%.6f,%.6f,%.1f\n", t, lat, lon, speed));
            w.close();
        } catch (Exception e) {
            android.util.Log.w("LogStore", "cannot add to the route", e);
        }
    }

    /** The trip ended: its route up to `end` is kept under its id, or dropped with a null id. */
    synchronized void routeEnd(String id, long end) {
        File cur = route("current");
        if (id == null || !cur.exists()) {
            cur.delete();
            return;
        }
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(cur));
            StringBuilder kept = new StringBuilder();
            for (String line; (line = r.readLine()) != null; ) {
                int comma = line.indexOf(',');
                if (comma > 0 && Long.parseLong(line.substring(0, comma)) <= end) kept.append(line).append('\n');
            }
            r.close();
            File f = route(id), tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(kept.toString().getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            if (!tmp.renameTo(f)) throw new java.io.IOException("cannot write " + f);
            cur.delete();
        } catch (Exception e) {
            android.util.Log.w("LogStore", "cannot keep the route", e);
        }
    }

    // ---- the odometer ----

    /** Readings (time, km) oldest first. */
    private List<double[]> readings() {
        List<double[]> out = new ArrayList<double[]>();
        for (JSONObject o : all()) {
            double km = o.optDouble("odometer", 0);
            if (km > 0 && !o.optString("type").equals("trip")) out.add(new double[] {o.optLong("time"), km});
        }
        Collections.sort(out, new Comparator<double[]>() {
            public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
        });
        return out;
    }

    /** GPS metres driven between two moments: whole trips, and the share by time of one that spans either. */
    private double gpsMetres(double from, double to) {
        double m = 0;
        for (JSONObject o : all()) {
            if (!o.optString("type").equals("trip")) continue;
            double s = o.optLong("time"), e = Math.max(s, o.optLong("end")), d = o.optDouble("dist_m", 0);
            double in = Math.min(e, to) - Math.max(s, from);
            if (e == s) { if (s >= from && s <= to) m += d; }
            else if (in > 0) m += d * in / (e - s);
        }
        return m;
    }

    /**
     * Odometer km per GPS km, from pairs of readings with at least 30 km of trips between them. A pair
     * far off (driving that was not logged, a mistyped reading) is left out.
     */
    synchronized double factor() {
        List<double[]> r = readings();
        double odo = 0, gps = 0;
        for (int i = 0; i + 1 < r.size(); i++) {
            double g = gpsMetres(r.get(i)[0], r.get(i + 1)[0]) / 1000, d = r.get(i + 1)[1] - r.get(i)[1];
            if (g < 30 || d <= 0) continue;
            double k = d / g;
            if (k < 0.9 || k > 1.15) continue;
            odo += d;
            gps += g;
        }
        return gps > 0 ? odo / gps : 1.0;
    }

    /** The odometer at a moment, with `extraM` GPS metres not yet in a trip; NaN before any reading. */
    synchronized double odometer(long at, double extraM) {
        double[] last = null;
        for (double[] x : readings()) if (x[0] <= at) last = x;
        if (last == null) return Double.NaN;
        return last[1] + factor() * (gpsMetres(last[0], at) + extraM) / 1000;
    }

    /** When the last reading was typed, 0 without one. */
    synchronized long lastReadingTime() {
        List<double[]> r = readings();
        return r.isEmpty() ? 0 : (long) r.get(r.size() - 1)[0];
    }

    // ---- fuel ----

    /** Fill-ups oldest first. */
    private List<JSONObject> fills() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject o : all()) if (o.optString("type").equals("fuel")) out.add(0, o);
        return out;
    }

    /** {time, L/100 km, km} for each full tank after another full tank, oldest first. */
    synchronized List<double[]> consumption() {
        List<double[]> out = new ArrayList<double[]>();
        JSONObject full = null;
        double litres = 0;
        for (JSONObject f : fills()) {
            if (f.optBoolean("missed")) {
                full = f.optBoolean("full", true) ? f : null;
                litres = 0;
                continue;
            }
            litres += f.optDouble("litres", 0);
            if (!f.optBoolean("full", true)) continue;
            double km = full == null ? 0 : f.optDouble("odometer", 0) - full.optDouble("odometer", 0);
            if (full != null && km > 0 && full.optDouble("odometer", 0) > 0) {
                out.add(new double[] {f.optLong("time"), litres / km * 100, km});
            }
            full = f;
            litres = 0;
        }
        return out;
    }

    /** The average over every full-to-full stretch, NaN without one. */
    synchronized double averageL100() {
        double l = 0, km = 0;
        for (double[] c : consumption()) {
            l += c[1] * c[2] / 100;
            km += c[2];
        }
        return km > 0 ? l / km * 100 : Double.NaN;
    }

    synchronized double lastPrice(String grade) {
        List<JSONObject> f = fills();
        for (int i = f.size() - 1; i >= 0; i--)
            if (f.get(i).optString("grade").equals(grade) && f.get(i).optDouble("price", 0) > 0) return f.get(i).optDouble("price");
        return 0;
    }

    synchronized String lastGrade() {
        List<JSONObject> f = fills();
        return f.isEmpty() ? GRADES[0] : f.get(f.size() - 1).optString("grade", GRADES[0]);
    }

    // ---- service reminders ----

    static final double SOON_KM = 1000;
    static final long SOON_DAYS = 30, DAY = 86400000L;

    static final class Due {
        JSONObject reminder;
        String what;
        double kmLeft = Double.NaN;              // NaN: not by distance (or no odometer yet)
        long daysLeft = Long.MAX_VALUE;          // MAX_VALUE: not by time
        long lastTime, dueTime;
        double lastOdo;
        double urgency;                          // the share of the interval left, the smaller of the two
        boolean overdue, soon;

        /** "in 850 km", "in 12 days", "120 km overdue"...: by whichever comes first. */
        String when() {
            boolean byKm = !Double.isNaN(kmLeft) && (daysLeft == Long.MAX_VALUE || kmUrgency <= dayUrgency);
            if (byKm) {
                long k = Math.round(Math.abs(kmLeft));
                return kmLeft <= 0 ? LogPage.km(k) + " overdue" : "in " + LogPage.km(k);
            }
            long d = Math.abs(daysLeft);
            if (d == 0) return "today";
            String t = d < 60 ? d + (d == 1 ? " day" : " days") : (d / 30) + " months";
            return daysLeft < 0 ? t + " overdue" : "in " + t;
        }

        private double kmUrgency = Double.MAX_VALUE, dayUrgency = Double.MAX_VALUE;
    }

    /** Every reminder with what is left until it is due, the most urgent first. */
    synchronized List<Due> reminders(double odoNow) {
        List<Due> out = new ArrayList<Due>();
        long now = System.currentTimeMillis();
        for (JSONObject r : all()) {
            if (!r.optString("type").equals("reminder")) continue;
            Due d = new Due();
            d.reminder = r;
            d.what = r.optString("what");
            d.lastTime = r.optLong("time");
            d.lastOdo = r.optDouble("from_odometer", 0);
            for (JSONObject s : all()) {         // newest first: the last service of that name
                if (s.optString("type").equals("service") && s.optString("what").trim().equalsIgnoreCase(d.what.trim())
                    && s.optLong("time") >= d.lastTime) {
                    d.lastTime = s.optLong("time");
                    d.lastOdo = s.optDouble("odometer", 0);
                    break;
                }
            }
            if (d.lastOdo <= 0) d.lastOdo = odometer(d.lastTime, 0);
            double everyKm = r.optDouble("every_km", 0);
            int months = r.optInt("every_months", 0);
            if (everyKm > 0 && !Double.isNaN(d.lastOdo) && !Double.isNaN(odoNow)) {
                d.kmLeft = d.lastOdo + everyKm - odoNow;
                d.kmUrgency = d.kmLeft / everyKm;
            }
            if (months > 0) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(d.lastTime);
                c.add(Calendar.MONTH, months);
                d.daysLeft = (c.getTimeInMillis() - now) / DAY;            // whole days, either way
                d.dueTime = c.getTimeInMillis();
                d.dayUrgency = (c.getTimeInMillis() - now) / (double) (c.getTimeInMillis() - d.lastTime);
            }
            d.urgency = Math.min(d.kmUrgency, d.dayUrgency);
            if (d.urgency == Double.MAX_VALUE) continue;          // no interval it can be measured by yet
            d.overdue = (!Double.isNaN(d.kmLeft) && d.kmLeft <= 0) || (months > 0 && d.dueTime <= now);
            d.soon = d.overdue || (!Double.isNaN(d.kmLeft) && d.kmLeft <= SOON_KM) || d.daysLeft <= SOON_DAYS;
            out.add(d);
        }
        Collections.sort(out, new Comparator<Due>() {
            public int compare(Due a, Due b) { return Double.compare(a.urgency, b.urgency); }
        });
        return out;
    }

    // ---- this month ----

    static final class Month {
        double fuelCost, tripKm, businessKm;
        int fills;
    }

    synchronized Month month() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        long from = c.getTimeInMillis();
        double k = factor();
        Month m = new Month();
        for (JSONObject o : all()) {
            if (o.optLong("time") < from) continue;
            String t = o.optString("type");
            if (t.equals("fuel")) {
                m.fuelCost += o.optDouble("total", 0);
                m.fills++;
            } else if (t.equals("trip")) {
                double km = o.optDouble("dist_m", 0) * k / 1000;
                m.tripKm += km;
                if (o.optBoolean("business")) m.businessKm += km;
            }
        }
        return m;
    }

    /** Purposes of earlier business trips, most used first (for tapping instead of typing). */
    synchronized List<String> purposes() {
        final Map<String, Integer> n = new HashMap<String, Integer>();
        for (JSONObject o : all()) {
            String p = o.optString("purpose").trim();
            if (o.optString("type").equals("trip") && !p.isEmpty()) n.put(p, n.containsKey(p) ? n.get(p) + 1 : 1);
        }
        List<String> out = new ArrayList<String>(n.keySet());
        Collections.sort(out, new Comparator<String>() {
            public int compare(String a, String b) { return n.get(b) - n.get(a); }
        });
        return out;
    }

    // ---- files ----

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

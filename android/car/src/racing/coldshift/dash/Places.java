package racing.coldshift.dash;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home, Work, saved places and recent destinations, kept in places.json in the app's files folder and
 * copied to its folder on the card (Storage.appDir), where the desktop tool
 * backs it up. To restore, the tool writes <card>/coldshift.dash/places.json and sends SYNCED with
 * "places"; its places are merged in (its Home and Work win), once per version of that file.
 *
 *     {"version": 1, "places": [{"kind": "home", "name": "Home", "sub": "York Street, George",
 *                               "lat": -33.92, "lon": 18.42, "time": 1761980000000}, ...]}
 */
final class Places {
    static final String HOME = "home", WORK = "work", SAVED = "saved", RECENT = "recent";
    private static final int MAX_RECENT = 12;
    private static final double SAME_M = 30;

    static final class Place {
        String kind, name, sub = "";
        double lat, lon;
        long time;

        Place(String kind, String name, String sub, double lat, double lon) {
            this.kind = kind;
            this.name = name;
            this.sub = sub == null ? "" : sub;
            this.lat = lat;
            this.lon = lon;
            this.time = System.currentTimeMillis();
        }
    }

    private static Places instance;
    private final Context ctx;
    private final List<Place> list = new ArrayList<Place>();

    static synchronized Places get(Context c) {
        if (instance == null) instance = new Places(c.getApplicationContext());
        return instance;
    }

    private Places(Context c) {
        ctx = c;
        File f = file();
        if (!f.exists() && cardFile() != null && cardFile().exists()) f = cardFile();    // reinstalled
        if (f.exists()) read(f, list);
        if (find(HOME) == null && Prefs.place(c, HOME) != null) {                         // before places.json
            double[] p = Prefs.place(c, HOME);
            list.add(new Place(HOME, "Home", "", p[0], p[1]));
        }
        if (find(WORK) == null && Prefs.place(c, WORK) != null) {
            double[] p = Prefs.place(c, WORK);
            list.add(new Place(WORK, "Work", "", p[0], p[1]));
        }
        restore();
    }

    private File file() { return new File(ctx.getFilesDir(), "places.json"); }

    /** The copy on the card the desktop backs up (the card's app folder, else internal storage's). */
    private File cardFile() {
        File dir = Storage.appDir(ctx);                     // the card's, when there is one
        return dir == null ? null : new File(dir, "places.json");
    }

    synchronized Place find(String kind) {
        for (Place p : list) if (p.kind.equals(kind)) return p;
        return null;
    }

    synchronized List<Place> all(String kind) {
        List<Place> out = new ArrayList<Place>();
        for (Place p : list) if (p.kind.equals(kind)) out.add(p);
        if (kind.equals(RECENT) || kind.equals(SAVED)) {
            java.util.Collections.sort(out, new java.util.Comparator<Place>() {
                public int compare(Place a, Place b) { return Long.compare(b.time, a.time); }
            });
        }
        return out;
    }

    synchronized void setHomeOrWork(String kind, String sub, double lat, double lon) {
        Place p = find(kind);
        if (p != null) list.remove(p);
        list.add(new Place(kind, kind.equals(HOME) ? "Home" : "Work", sub, lat, lon));
        save();
    }

    synchronized boolean isSaved(double lat, double lon) { return near(SAVED, lat, lon) != null; }

    synchronized void save(String name, String sub, double lat, double lon) {
        if (near(SAVED, lat, lon) != null) return;
        list.add(new Place(SAVED, name, sub, lat, lon));
        save();
    }

    synchronized void remove(Place p) {
        list.remove(p);
        save();
    }

    /** A destination navigated to goes to the top of the recent list (not Home or Work themselves). */
    synchronized void visited(String name, String sub, double lat, double lon) {
        Place h = find(HOME), w = find(WORK);
        if (h != null && Nav.dist(h.lat, h.lon, lat, lon) < SAME_M) return;
        if (w != null && Nav.dist(w.lat, w.lon, lat, lon) < SAME_M) return;
        Place old = near(RECENT, lat, lon);
        if (old != null) list.remove(old);
        list.add(new Place(RECENT, name, sub, lat, lon));
        List<Place> recent = all(RECENT);
        for (int i = MAX_RECENT; i < recent.size(); i++) list.remove(recent.get(i));
        save();
    }

    private Place near(String kind, double lat, double lon) {
        for (Place p : list) if (p.kind.equals(kind) && Nav.dist(p.lat, p.lon, lat, lon) < SAME_M) return p;
        return null;
    }

    /** Merges <card>/coldshift.dash/places.json from the desktop, once per version of it. Returns how many came in. */
    synchronized int restore() {
        File f = null;
        for (String v : Storage.volumes()) {
            File c = new File(v, Storage.DIR + "/places.json");
            if (c.exists()) { f = c; break; }
        }
        if (f == null) return 0;
        String stamp = f.lastModified() + ":" + f.length();
        if (stamp.equals(Prefs.sp(ctx).getString("places_restored", ""))) return 0;
        List<Place> in = new ArrayList<Place>();
        if (!read(f, in)) return 0;
        int added = 0;
        for (Place p : in) {
            if (p.kind.equals(HOME) || p.kind.equals(WORK)) {
                Place old = find(p.kind);
                if (old != null) list.remove(old);
                list.add(p);
                added++;
            } else if (near(p.kind, p.lat, p.lon) == null) {
                list.add(p);
                added++;
            }
        }
        Prefs.sp(ctx).edit().putString("places_restored", stamp).apply();
        save();
        android.util.Log.i("Places", "restored " + added + " of " + in.size() + " from " + f);
        return added;
    }

    private static boolean read(File f, List<Place> into) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            in.close();
            JSONArray a = new JSONObject(new String(b, 0, n, "UTF-8")).getJSONArray("places");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Place p = new Place(o.getString("kind"), o.getString("name"), o.optString("sub", ""),
                    o.getDouble("lat"), o.getDouble("lon"));
                p.time = o.optLong("time", 0);
                into.add(p);
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("Places", "cannot read " + f, e);
            return false;
        }
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Place p : list) {
                JSONObject o = new JSONObject();
                o.put("kind", p.kind);
                o.put("name", p.name);
                o.put("sub", p.sub);
                o.put("lat", p.lat);
                o.put("lon", p.lon);
                o.put("time", p.time);
                a.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("places", a);
            byte[] data = root.toString(1).getBytes("UTF-8");
            write(file(), data);
            File card = cardFile();
            if (card != null) write(card, data);
        } catch (Exception e) {
            android.util.Log.w("Places", "cannot save places", e);
        }
    }

    private static void write(File f, byte[] data) throws java.io.IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(data);
        out.getFD().sync();
        out.close();
        if (!tmp.renameTo(f)) throw new java.io.IOException("cannot replace " + f);
    }
}

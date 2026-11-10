package racing.coldshift.dash;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Offline place search in the file the desktop tool builds from OpenStreetMap (dashsync/search.py):
 * <volume>/coldshift.dash/search/<region>.db. Words are matched as prefixes, the first in the name
 * ("york st geo" finds York Street in George), then ranked by kind, how well the name matches and
 * distance from the car. The desktop's search.query does the same, for checking a build.
 */
final class Search {
    static final int CITY = 1, TOWN = 2, VILLAGE = 3, SUBURB = 4, STREET = 5, POI = 6, ADDRESS = 7;
    /** How many letters before searching; and how many of the nearest and best-ranked matches are scored. */
    static final int MIN_CHARS = 3, NEAR = 150, BEST = 150;

    static final class Result {
        int kind;
        String name, folded, detail, area;
        double lat, lon, km = -1;
        double score;

        /** The line under the name: what it is and where. */
        String sub() {
            if (detail == null || detail.isEmpty()) return area;
            return area == null || area.isEmpty() ? detail : detail + " · " + area;
        }
    }

    private SQLiteDatabase db;
    private File opened;

    static File findFile() {
        for (String v : Storage.volumes()) {
            File[] fs = new File(v, Storage.DIR + "/search").listFiles();
            if (fs == null) continue;
            java.util.Arrays.sort(fs);
            for (File f : fs) if (f.getName().endsWith(".db") && !f.getName().startsWith(".")) return f;
        }
        return null;
    }

    /** Lower-case ASCII words, as the desktop folds the names: "Église St-Pierre" -> "eglise st pierre". */
    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.US);
        StringBuilder b = new StringBuilder();
        boolean space = true;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                b.append(c);
                space = false;
            } else if (!space) {
                b.append(' ');
                space = true;
            }
        }
        return b.toString().trim();
    }

    synchronized boolean available() { return open() != null; }

    synchronized void close() {
        if (db != null) db.close();
        db = null;
        opened = null;
    }

    private SQLiteDatabase open() {
        File f = findFile();
        if (f == null) {
            close();
            return null;
        }
        if (db == null || !f.equals(opened)) {
            close();
            try {
                db = SQLiteDatabase.openDatabase(f.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                opened = f;
            } catch (Throwable t) {
                android.util.Log.w("Search", "cannot open " + f, t);
                db = null;
            }
        }
        return db;
    }


    /**
     * The best `limit` places for the text. The first word is looked up in the name (or what the place
     * is) with FTS, the others are checked against the row's words with LIKE: an FTS AND with a short
     * word like "st" reads that word's list for the whole country, over a second here. SQLite then picks
     * the NEAR nearest matches (when there is a fix) and the BEST best-ranked; only those are scored here.
     */
    synchronized List<Result> find(String text, double lat, double lon, boolean haveFix, int limit) {
        List<Result> out = new ArrayList<Result>();
        String q = fold(text);
        SQLiteDatabase d = open();
        if (d == null || q.length() < MIN_CHARS) return out;
        String[] words = q.split(" ");
        String[] args = new String[words.length];
        StringBuilder cond = new StringBuilder("words MATCH ?");
        args[0] = words[0] + "*";
        for (int i = 1; i < words.length; i++) {
            cond.append(" AND p.keys LIKE ?");
            args[i] = "% " + words[i] + "%";
        }
        String match = q;
        long t0 = System.currentTimeMillis();
        java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
        String base = "SELECT p.id, p.kind, p.name, p.folded, p.detail, p.area, p.lat, p.lon, p.rank FROM words w"
            + " JOIN place p ON p.id = w.docid WHERE " + cond;
        double cos = Math.cos(Math.toRadians(lat));
        boolean number = words[0].matches("[0-9]+");
        try {
            collect(d.rawQuery(base + " ORDER BY p.rank DESC LIMIT " + BEST, args), out, seen);
            if (haveFix) {
                long la = Math.round(lat * 1e6), lo = Math.round(lon * 1e6), k = Math.round(cos * cos * 1000);
                collect(d.rawQuery(base + " ORDER BY (p.lat - " + la + ") * (p.lat - " + la + ") * 1000 + (p.lon - " + lo
                    + ") * (p.lon - " + lo + ") * " + k + " LIMIT " + NEAR, args), out, seen);
            }
        } catch (Throwable t) {
            android.util.Log.w("Search", "search failed for " + match, t);
        }
        for (Result r : out) {
            double s = r.score;
            if (r.folded.equals(q)) s += 40;
            else if (r.folded.startsWith(q)) s += 25;
            else if (r.folded.startsWith(words[0])) s += 15;
            if (number && !(r.folded + " ").startsWith(words[0] + " ")) s -= 30;      // 12 York Street, not 124
            if (haveFix) {
                r.km = Math.hypot((r.lat - lat) * 111.2, (r.lon - lon) * 111.2 * cos);
                s -= 12 * Math.log10(1 + r.km);
            }
            r.score = s;
        }
        android.util.Log.i("Search", "\"" + match + "\": " + out.size() + " scored in " + (System.currentTimeMillis() - t0) + " ms");
        Collections.sort(out, new Comparator<Result>() {
            public int compare(Result a, Result b) { return Double.compare(b.score, a.score); }
        });
        if (out.size() > limit) out = new ArrayList<Result>(out.subList(0, limit));
        if (number && words.length > 1) {
            boolean exact = false;
            for (Result r : out) if ((r.folded + " ").startsWith(words[0] + " ")) exact = true;
            Result guess = exact ? null : estimate(d, Integer.parseInt(words[0]), q.substring(words[0].length() + 1), lat, lon, haveFix);
            if (guess != null) {
                if (haveFix) guess.km = Math.hypot((guess.lat - lat) * 111.2, (guess.lon - lon) * 111.2 * cos);
                out.add(0, guess);
                if (out.size() > limit) out.remove(out.size() - 1);
            }
        }
        return out;
    }

    /**
     * A house number that is not mapped ("32 york street george"): the street found for the rest, and the
     * spot between the nearest mapped numbers on the same side of it, else at the nearest mapped number.
     * Without mapped numbers on that street, the street itself. As search.estimate on the desktop.
     */
    private Result estimate(SQLiteDatabase d, int n, String rest, double lat, double lon, boolean haveFix) {
        Result st = null;
        for (Result r : find(rest, lat, lon, haveFix, 8)) if (r.kind == STREET) { st = r; break; }
        if (st == null) return null;
        String street = st.folded, town = st.area.substring(st.area.lastIndexOf(", ") + 1).trim();
        List<double[]> mapped = new ArrayList<double[]>();       // {number, lat, lon}
        List<String> areas = new ArrayList<String>();
        Cursor c = d.rawQuery("SELECT p.folded, p.area, p.lat, p.lon FROM words w JOIN place p ON p.id = w.docid"
            + " WHERE words MATCH ? AND p.kind = " + ADDRESS + " AND p.folded LIKE ? AND p.area LIKE ?",
            new String[] {street.split(" ")[0] + "*", "% " + street, "%" + town});
        try {
            while (c.moveToNext()) {
                String f = c.getString(0);
                int sp = f.indexOf(' ');
                if (sp <= 0 || !f.substring(sp + 1).equals(street) || !f.substring(0, sp).matches("[0-9]+")) continue;
                mapped.add(new double[] {Integer.parseInt(f.substring(0, sp)), c.getInt(2) / 1e6, c.getInt(3) / 1e6});
                areas.add(c.getString(1));
            }
        } finally {
            c.close();
        }
        // between numbers on the same side of the street; failing that, the nearest number on either side
        int[] b = bracket(mapped, n, true);
        if (b[0] < 0 || b[1] < 0) {
            b = bracket(mapped, n, false);
            if (b[0] >= 0 && b[1] >= 0) {
                if (n - mapped.get(b[0])[0] <= mapped.get(b[1])[0] - n) b[1] = -1;
                else b[0] = -1;
            }
        }
        Result g = new Result();
        g.kind = ADDRESS;
        g.name = n + " " + st.name;
        g.folded = n + " " + street;
        if (b[0] >= 0 && b[1] >= 0) {
            double[] lo = mapped.get(b[0]), hi = mapped.get(b[1]);
            double t = (n - lo[0]) / (hi[0] - lo[0]);
            g.lat = lo[1] + t * (hi[1] - lo[1]);
            g.lon = lo[2] + t * (hi[2] - lo[2]);
            g.detail = "Estimated, between " + (int) lo[0] + " and " + (int) hi[0];
            g.area = areas.get(b[0]);
        } else if (b[0] >= 0 || b[1] >= 0) {
            int i = b[0] >= 0 ? b[0] : b[1];
            g.lat = mapped.get(i)[1];
            g.lon = mapped.get(i)[2];
            g.detail = "Estimated, near " + (int) mapped.get(i)[0];
            g.area = areas.get(i);
        } else {
            st.detail = "Number " + n + " is not mapped";
            return st;
        }
        return g;
    }

    /** Indexes of the nearest mapped numbers below and above n (-1 for none), optionally same side only. */
    private static int[] bracket(List<double[]> mapped, int n, boolean sameSide) {
        int below = -1, above = -1;
        for (int i = 0; i < mapped.size(); i++) {
            int m = (int) mapped.get(i)[0];
            if (sameSide && m % 2 != n % 2) continue;
            if (m < n && (below < 0 || m > mapped.get(below)[0])) below = i;
            if (m > n && (above < 0 || m < mapped.get(above)[0])) above = i;
        }
        return new int[] {below, above};
    }

    private static void collect(Cursor c, List<Result> out, java.util.Set<Long> seen) {
        try {
            while (c.moveToNext()) {
                if (!seen.add(c.getLong(0))) continue;
                Result r = new Result();
                r.kind = c.getInt(1);
                r.name = c.getString(2);
                r.folded = c.getString(3);
                r.detail = c.getString(4);
                r.area = c.getString(5);
                r.lat = c.getInt(6) / 1e6;
                r.lon = c.getInt(7) / 1e6;
                r.score = c.getInt(8);
                out.add(r);
            }
        } finally {
            c.close();
        }
    }
}

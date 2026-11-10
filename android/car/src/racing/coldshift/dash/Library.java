package racing.coldshift.dash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** The music library: scans the card, caches tags and cover art, indexes by folder. */
final class Library implements Runnable {
    private static Library inst;
    static final String[] EXT = {".mp3", ".flac", ".ogg", ".opus", ".m4a", ".aac", ".wav"};

    static synchronized Library get(Context c) {
        if (inst == null) inst = new Library(c.getApplicationContext());
        return inst;
    }

    static class Entry {
        boolean dir;
        String name, path;
        int count;
        Track track;
    }

    private final Context ctx;
    private final File cacheFile, artDir;
    private volatile List<Track> tracks = new ArrayList<Track>();
    private volatile Map<String, Track> byPath = new HashMap<String, Track>();
    private volatile Map<String, List<Entry>> index = new HashMap<String, List<Entry>>();
    volatile int scanned, total, readCount;
    volatile boolean scanning;
    volatile String root = "";
    private Thread thread;

    private Library(Context c) {
        ctx = c;
        cacheFile = new File(c.getFilesDir(), "library.json");
        artDir = new File(c.getCacheDir(), "art");
        artDir.mkdirs();
        root = findRoot();
        loadCache();
        loadSyncedIndex();
        rebuildIndex();
    }

    File artFile(String key) {
        File onCard = new File(syncDir(), "art/" + key + ".jpg");
        return onCard.exists() ? onCard : new File(artDir, key + ".jpg");
    }

    /** Where the desktop tool puts index.json and covers. */
    File syncDir() { return Storage.libraryDir(); }

    List<Track> all() { return tracks; }
    Track byPath(String p) { return byPath.get(p); }

    /** Like byPath, but also finds a track saved when the music was in a plain Music folder. */
    Track resolve(String p) {
        Track t = byPath.get(p);
        if (t != null) return t;
        String[] old = {"/storage/sdcard1/Music/", "/storage/sdcard0/Music/", "/storage/emulated/0/Music/", "/sdcard/Music/"};
        for (String prefix : old) {
            if (p.startsWith(prefix)) {
                t = byPath.get(root + "/" + p.substring(prefix.length()));
                if (t != null) return t;
            }
        }
        return null;
    }
    List<Entry> entries(String folder) { List<Entry> l = index.get(folder); return l == null ? new ArrayList<Entry>() : l; }

    int countIn(String folder) {
        int n = 0;
        String prefix = folder.endsWith("/") ? folder : folder + "/";
        for (Track t : tracks) if (t.path.startsWith(prefix)) n++;
        return n;
    }

    String findRoot() { return Storage.musicRoot().getPath(); }

    String rootLabel() { return Storage.onCard(root) ? "SD card" : "Phone"; }
    boolean rootAvailable() { return new File(root).isDirectory(); }

    /** After the desktop tool changed the music or index.json: take the new index, then rescan. */
    void reload() {
        new Thread("library-reload") {
            @Override public void run() {
                synchronized (Library.this) {
                    root = findRoot();
                    loadSyncedIndex();
                    rebuildIndex();
                }
                scan();
            }
        }.start();
    }

    synchronized void scan() {
        if (scanning) return;
        scanning = true;
        scanned = 0;
        total = 0;
        readCount = 0;
        thread = new Thread(this);
        thread.start();
    }

    // ---- scanning ----

    private static boolean isAudio(String name) {
        String n = name.toLowerCase();
        for (String e : EXT) if (n.endsWith(e)) return true;
        return false;
    }

    private void walk(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName();
            if (n.startsWith(".") || n.startsWith("._")) continue;
            if (f.isDirectory()) walk(f, out);
            else if (isAudio(n)) out.add(f);
        }
    }

    public void run() {
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            root = findRoot();
            List<File> files = new ArrayList<File>();
            walk(new File(root), files);
            total = files.size();
            Map<String, Track> old = byPath;
            Map<String, Track> nu = new HashMap<String, Track>();
            List<Track> list = new ArrayList<Track>();
            for (File f : files) {
                Track t = old.get(f.getPath());
                if (t != null && t.size == f.length() && t.mtime == 0) t.mtime = f.lastModified();
                if (t == null || t.mtime != f.lastModified() || t.size != f.length()) {
                    if (readCount < 12) android.util.Log.i("Library", "reading tags: " + f.getPath()
                        + (t == null ? " (not in index)" : " (size " + t.size + " vs " + f.length() + ")"));
                    t = read(f);
                    readCount++;
                }
                nu.put(t.path, t);
                list.add(t);
                scanned++;
                if (scanned % 20 == 0) publish(list, nu);
            }
            publish(list, nu);
            saveCache();
            int gone = 0;
            for (Map.Entry<String, Track> e : old.entrySet()) {
                if (!nu.containsKey(e.getKey())) {
                    if (gone++ < 8) android.util.Log.i("Library", "no longer on card: " + e.getKey());
                }
            }
            android.util.Log.i("Library", "scan done: " + list.size() + " tracks, " + readCount
                + " read from tags, " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        } catch (Throwable t) {
            android.util.Log.w("Library", "scan failed: " + t);
        } finally {
            scanning = false;
        }
    }

    private synchronized void publish(List<Track> list, Map<String, Track> map) {
        List<Track> copy = new ArrayList<Track>(list);
        Collections.sort(copy, new TrackOrder());
        tracks = copy;
        byPath = new HashMap<String, Track>(map);
        rebuildIndex();
    }

    private Track read(File f) {
        Track t = new Track();
        t.path = f.getPath();
        t.folder = f.getParent();
        t.mtime = f.lastModified();
        t.size = f.length();
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(t.path);
            t.title = nz(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            t.artist = nz(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            if (t.artist.isEmpty()) t.artist = nz(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            t.album = nz(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            String d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) t.durationMs = Integer.parseInt(d);
            String tn = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
            if (tn != null) {
                int slash = tn.indexOf('/');
                try { t.trackNo = Integer.parseInt(slash > 0 ? tn.substring(0, slash) : tn); } catch (Exception e) { /* none */ }
            }
            byte[] pic = m.getEmbeddedPicture();
            if (pic != null && pic.length > 0) t.artKey = storeArt(pic);
        } catch (Throwable e) {
            // unreadable tags: the file name is used for the title
        } finally {
            try { m.release(); } catch (Throwable e) { /* ignore */ }
        }
        return t;
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private String storeArt(byte[] pic) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(pic);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            String key = sb.toString();
            File out = artFile(key);
            if (out.exists()) return key;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 320 && o.outHeight / (s * 2) >= 320) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            if (b == null) return "";
            int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            Bitmap sc = Bitmap.createScaledBitmap(sq, 320, 320, true);
            FileOutputStream fo = new FileOutputStream(out);
            sc.compress(Bitmap.CompressFormat.JPEG, 86, fo);
            fo.close();
            if (sc != sq) sc.recycle();
            if (sq != b) sq.recycle();
            b.recycle();
            return key;
        } catch (Throwable e) {
            return "";
        }
    }

    // ---- ordering and index ----

    /**
     * Folder, then the numbered tracks by number, then the unnumbered ones, each by name. (Comparing
     * numbers only when both had one was not an order: a #1, an unnumbered and a #2 could each come
     * before the next, and Java's sort threw "Comparison method violates its general contract".)
     */
    static class TrackOrder implements Comparator<Track> {
        public int compare(Track a, Track b) {
            int c = a.folder.compareToIgnoreCase(b.folder);
            if (c != 0) return c;
            boolean an = a.trackNo > 0, bn = b.trackNo > 0;
            if (an != bn) return an ? -1 : 1;
            if (an && a.trackNo != b.trackNo) return a.trackNo < b.trackNo ? -1 : 1;
            c = a.path.compareToIgnoreCase(b.path);
            return c != 0 ? c : a.path.compareTo(b.path);
        }
    }

    static class EntryOrder implements Comparator<Entry> {
        public int compare(Entry a, Entry b) {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            if (!a.dir) return new TrackOrder().compare(a.track, b.track);
            return a.name.compareToIgnoreCase(b.name);
        }
    }

    private void rebuildIndex() {
        Map<String, List<Entry>> idx = new HashMap<String, List<Entry>>();
        Map<String, Entry> dirs = new HashMap<String, Entry>();
        for (Track t : tracks) {
            Entry te = new Entry();
            te.name = t.displayTitle();
            te.path = t.path;
            te.track = t;
            add(idx, t.folder, te);
            String p = t.folder;
            while (p != null && p.length() > root.length() && p.startsWith(root)) {
                Entry d = dirs.get(p);
                if (d == null) {
                    d = new Entry();
                    d.dir = true;
                    d.path = p;
                    d.name = new File(p).getName();
                    dirs.put(p, d);
                    add(idx, new File(p).getParent(), d);
                }
                d.count++;
                p = new File(p).getParent();
            }
        }
        for (List<Entry> l : idx.values()) Collections.sort(l, new EntryOrder());
        index = idx;
    }

    private static void add(Map<String, List<Entry>> idx, String folder, Entry e) {
        List<Entry> l = idx.get(folder);
        if (l == null) { l = new ArrayList<Entry>(); idx.put(folder, l); }
        l.add(e);
    }

    /** Every track at or under a folder, in play order. */
    List<Track> under(String folder) {
        List<Track> out = new ArrayList<Track>();
        String prefix = folder.endsWith("/") ? folder : folder + "/";
        for (Track t : tracks) if (t.path.startsWith(prefix)) out.add(t);
        return out;
    }


    // ---- index written by the desktop tool ----

    /**
     * Seeds the library from coldshift.dash/library/index.json when it is new to this install, so
     * start-up needs no tag reading. Entries are matched to files by size only (mtime = 0): file
     * times differ between the desktop and the card. The scan that follows fills in the real mtime
     * and reads tags only for files that are not in the index or whose size changed.
     */
    private void loadSyncedIndex() {
        File f = new File(syncDir(), "index.json");
        if (!f.exists()) return;
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            FileReader r = new FileReader(f);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            JSONObject doc = new JSONObject(sb.toString());
            if (doc.optInt("version") != 1) return;
            long gen = doc.optLong("generated");
            JSONArray a = doc.getJSONArray("tracks");
            // taken already, unless the library has since lost tracks (a scan that failed part way)
            if (Prefs.sp(ctx).getLong("index_generated", 0) == gen && tracks.size() >= a.length()) return;
            Map<String, Track> map = new HashMap<String, Track>(byPath);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Track t = new Track();
                t.path = root + "/" + o.getString("p");
                t.folder = new File(t.path).getParent();
                t.title = o.optString("t");
                t.artist = o.optString("a");
                t.album = o.optString("b");
                t.artKey = o.optString("k");
                t.durationMs = o.optInt("d");
                t.trackNo = o.optInt("n");
                t.size = o.optLong("s");
                map.put(t.path, t);
            }
            List<Track> list = new ArrayList<Track>(map.values());
            Collections.sort(list, new TrackOrder());
            tracks = list;
            byPath = map;
            Prefs.sp(ctx).edit().putLong("index_generated", gen).apply();
            android.util.Log.i("Library", "synced index: " + a.length() + " tracks in "
                + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        } catch (Throwable e) {
            android.util.Log.w("Library", "synced index unreadable: " + e);
        }
    }

    // ---- cache ----

    private void loadCache() {
        if (!cacheFile.exists()) return;
        try {
            FileReader r = new FileReader(cacheFile);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            JSONArray a = new JSONArray(sb.toString());
            List<Track> list = new ArrayList<Track>();
            Map<String, Track> map = new HashMap<String, Track>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Track t = new Track();
                t.path = o.getString("p");
                if (!t.path.startsWith(root + "/")) continue;     // the music folder moved: rescan
                t.folder = new File(t.path).getParent();
                t.title = o.optString("t");
                t.artist = o.optString("a");
                t.album = o.optString("b");
                t.artKey = o.optString("k");
                t.durationMs = o.optInt("d");
                t.trackNo = o.optInt("n");
                t.mtime = o.optLong("m");
                t.size = o.optLong("s");
                list.add(t);
                map.put(t.path, t);
            }
            Collections.sort(list, new TrackOrder());
            tracks = list;
            byPath = map;
        } catch (Throwable e) {
            android.util.Log.w("Library", "cache unreadable: " + e);
        }
    }

    private void saveCache() {
        try {
            JSONArray a = new JSONArray();
            for (Track t : tracks) {
                JSONObject o = new JSONObject();
                o.put("p", t.path); o.put("t", t.title); o.put("a", t.artist); o.put("b", t.album);
                o.put("k", t.artKey); o.put("d", t.durationMs); o.put("n", t.trackNo);
                o.put("m", t.mtime); o.put("s", t.size);
                a.put(o);
            }
            FileWriter w = new FileWriter(cacheFile);
            w.write(a.toString());
            w.close();
        } catch (Throwable e) {
            android.util.Log.w("Library", "cache not saved: " + e);
        }
    }
}

package racing.coldshift.dash;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the app finds the things the desktop tool puts on the card. Everything lives in one folder,
 * <volume>/coldshift.dash/ (the SD card first, then internal storage):
 *
 *     Music/     the music, any folder layout
 *     maps/      Mapsforge .map files
 *     library/   index.json and art/ written by the desktop tool
 *     routing/   BRouter routing tiles (*.rd5), for RouterService
 *
 * A plain <volume>/Music folder is also accepted, so music dropped on a card by hand still works.
 * The app's own files (the dashcam and the rest) go to appDir: Android/data/<package>/ on the card on
 * Android 4.4, which only lets an app write there, and coldshift.dash/phone/ from Android 11.
 *
 * Which volume is the card differs by phone: the G7 has it at /storage/sdcard1, the Y625 (set to keep
 * things on the card) at /storage/sdcard0, with internal storage at sdcard1. The card is found from
 * /proc/mounts: it is the one vold mounts from /dev/block/vold onto /mnt/media_rw/<name>.
 */
final class Storage {
    static final String DIR = "coldshift.dash";
    private static final String[] KNOWN = {"/storage/sdcard1", "/storage/sdcard0", "/storage/emulated/0"};
    private static String[] volumes;

    private Storage() {}

    /** The SD card's volume ("/storage/sdcard1"), or null without one. */
    static String card() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/mounts"));
            try {
                for (String line; (line = r.readLine()) != null; ) {
                    String[] f = line.split(" ");
                    if (f.length > 1 && f[0].startsWith("/dev/block/vold/") && f[1].startsWith("/mnt/media_rw/")) {
                        File v = new File("/storage/" + f[1].substring("/mnt/media_rw/".length()));
                        if (v.isDirectory()) return v.getPath();
                    }
                }
            } finally {
                r.close();
            }
        } catch (java.io.IOException e) { /* no card */ }
        return null;
    }

    /** Where to look for coldshift.dash/: the card first, then the phone's other storage. */
    static synchronized String[] volumes() {
        if (volumes != null) return volumes;
        List<String> out = new ArrayList<String>();
        String card = card();
        if (card != null) out.add(card);
        for (String v : KNOWN) if (!out.contains(v)) out.add(v);
        volumes = out.toArray(new String[0]);
        return volumes;
    }

    /** After a card went in or out. */
    static synchronized void refresh() { volumes = null; }

    /** True when the path is on the SD card. */
    static boolean onCard(String path) {
        String card = card();
        return card != null && path.startsWith(card + "/");
    }

    private static boolean moved;

    /**
     * The app's own folder: its files there (the dashcam, places, settings, the log book, track) are what the desktop
     * tool copies to the computer. Android 4.4 only lets an app write inside Android/data/<package>/files on the card,
     * so that is the folder there. From Android 11 adb cannot see into that folder, so with all-files access the app
     * keeps them in <card>/coldshift.dash/phone instead, moving what the old folder held the first time.
     */
    static File appDir(android.content.Context c) {
        File own = ownDir(c);
        String card = card();
        if (Api.SDK < 30 || card == null || !android.os.Environment.isExternalStorageManager()) return own;
        File phone = new File(card, DIR + "/phone");
        if (!phone.isDirectory() && !phone.mkdirs() || !phone.canWrite()) return own;
        synchronized (Storage.class) {
            if (!moved) {
                moved = true;
                if (own != null && !own.equals(phone)) moveInto(own, phone);
            }
        }
        return phone;
    }

    /** Moves everything in `from` into `to`, merging folders; a file `to` already has stays where it was. */
    private static void moveInto(File from, File to) {
        File[] fs = from.listFiles();
        if (fs == null) return;
        to.mkdirs();
        for (File f : fs) {
            File dst = new File(to, f.getName());
            if (f.isDirectory()) {
                moveInto(f, dst);
                f.delete();                                     // only goes when emptied
            } else if (!dst.exists() && !f.renameTo(dst)) {
                if (copy(f, dst)) f.delete();                   // another volume, as Android sees it: copy instead
            }
        }
    }

    private static boolean copy(File from, File to) {
        File part = new File(to.getPath() + ".part");
        try {
            java.io.InputStream in = new java.io.FileInputStream(from);
            java.io.OutputStream out = new java.io.FileOutputStream(part);
            try {
                byte[] b = new byte[1 << 16];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            } finally {
                in.close();
                out.close();
            }
            return part.length() == from.length() && part.renameTo(to);
        } catch (java.io.IOException e) {
            android.util.Log.w("Storage", "cannot copy " + from, e);
            part.delete();
            return false;
        }
    }

    /** The app's own folder on the card (Android/data/<package>/files), else the last one Android offers. */
    private static File ownDir(android.content.Context c) {
        File[] dirs = c.getExternalFilesDirs(null);
        File last = null;
        for (File d : dirs == null ? new File[0] : dirs) {
            if (d == null) continue;
            if (onCard(d.getPath())) return d;
            last = d;
        }
        return last;
    }

    static File musicRoot() {
        for (String v : volumes()) {
            File f = new File(v, DIR + "/Music");
            if (f.isDirectory()) return f;
        }
        for (String v : volumes()) {
            File f = new File(v, "Music");
            if (f.isDirectory()) return f;
        }
        return new File(volumes()[0], DIR + "/Music");
    }

    static List<File> mapsDirs() {
        List<File> out = new ArrayList<File>();
        for (String v : volumes()) out.add(new File(v, DIR + "/maps"));
        return out;
    }

    static File routingDir() {
        for (String v : volumes()) {
            File f = new File(v, DIR + "/routing");
            if (f.isDirectory()) return f;
        }
        return new File(volumes()[0], DIR + "/routing");
    }

    static File libraryDir() {
        for (String v : volumes()) {
            File f = new File(v, DIR + "/library");
            if (new File(f, "index.json").exists()) return f;
        }
        return new File(volumes()[0], DIR + "/library");
    }
}

package racing.coldshift.dash;

import android.content.Context;
import android.content.SharedPreferences;

/** Dashcam settings. Each option list is cycled by tapping its row. */
final class DashPrefs {
    private DashPrefs() {}

    static final int[] SEG_MIN = {1, 3, 5, 10};
    static final String[] QUALITY = {"1080p, 8 Mbps", "1080p, 5 Mbps", "720p, 4 Mbps"};
    static final int[] Q_W = {1920, 1920, 1280}, Q_H = {1080, 1080, 720}, Q_KBPS = {8000, 5000, 4000};
    static final int[] CAP_GB = {0, 2, 4, 8, 16};
    static final int[] GRACE_SEC = {30, 60, 120};

    private static SharedPreferences sp(Context c) { return Prefs.sp(c); }

    static int segIdx(Context c) { return sp(c).getInt("dc_seg", 1); }
    static int segMs(Context c) { return SEG_MIN[segIdx(c)] * 60 * 1000; }
    static int qualityIdx(Context c) { return sp(c).getInt("dc_quality", 0); }
    static boolean audio(Context c) { return sp(c).getBoolean("dc_audio", true); }
    static int capIdx(Context c) { return sp(c).getInt("dc_cap", 0); }
    static long capBytes(Context c) { return CAP_GB[capIdx(c)] * 1024L * 1024L * 1024L; }
    static boolean deleteOldest(Context c) { return sp(c).getBoolean("dc_delete_oldest", true); }
    static int graceIdx(Context c) { return sp(c).getInt("dc_grace", 1); }
    static int graceMs(Context c) { return GRACE_SEC[graceIdx(c)] * 1000; }
    static boolean autoStart(Context c) { return sp(c).getBoolean("dc_auto", false); }

    static void cycleSeg(Context c) { sp(c).edit().putInt("dc_seg", (segIdx(c) + 1) % SEG_MIN.length).apply(); }
    static void cycleQuality(Context c) { sp(c).edit().putInt("dc_quality", (qualityIdx(c) + 1) % QUALITY.length).apply(); }
    static void toggleAudio(Context c) { sp(c).edit().putBoolean("dc_audio", !audio(c)).apply(); }
    static void cycleCap(Context c) { sp(c).edit().putInt("dc_cap", (capIdx(c) + 1) % CAP_GB.length).apply(); }
    static void toggleDeleteOldest(Context c) { sp(c).edit().putBoolean("dc_delete_oldest", !deleteOldest(c)).apply(); }
    static void cycleGrace(Context c) { sp(c).edit().putInt("dc_grace", (graceIdx(c) + 1) % GRACE_SEC.length).apply(); }
    static void toggleAuto(Context c) { sp(c).edit().putBoolean("dc_auto", !autoStart(c)).apply(); }

    static int rotation(Context c) { return sp(c).getInt("dc_rotation", 1); }
    static void setRotation(Context c, int r) { sp(c).edit().putInt("dc_rotation", r).apply(); }
    static int nextMarkId(Context c) {
        int n = sp(c).getInt("dc_mark", 0) + 1;
        sp(c).edit().putInt("dc_mark", n).apply();
        return n;
    }
    static int nextSeq(Context c) {
        int n = sp(c).getInt("dc_seq", 0) + 1;
        sp(c).edit().putInt("dc_seq", n).apply();
        return n;
    }
}

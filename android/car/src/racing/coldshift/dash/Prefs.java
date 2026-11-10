package racing.coldshift.dash;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import org.json.JSONObject;

/**
 * The app's settings. The ones a person sets (BACKED_UP) are also written as settings.json to the app's
 * folder on the card, beside places.json, where the desktop tool backs them up; to restore, the tool
 * writes <card>/coldshift.dash/settings.json and the app takes it in once per version of that file.
 */
final class Prefs {
    private Prefs() {}

    static final String[] BACKED_UP = {"brightness", "idle_minutes", "haptics", "sounds", "heading_up", "voice", "convoy_wake", "convoy_autoplay", "convoy_text", "convoy_server", "convoy_start_keys", "kiosk", "devices", "audio_routes", "sound_channels"};
    private static SharedPreferences.OnSharedPreferenceChangeListener watcher;   // held: prefs keep it weakly

    /** Keeps settings.json on the card current. */
    static void watch(final Context c) {
        watcher = new SharedPreferences.OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                for (String k : BACKED_UP) if (k.equals(key)) { export(c); return; }
            }
        };
        sp(c).registerOnSharedPreferenceChangeListener(watcher);
        export(c);
    }

    static void export(Context c) {
        try {
            // the values in effect, defaults too, so a restore brings back the whole setup
            JSONObject o = new JSONObject();
            o.put("brightness", brightness(c));
            o.put("idle_minutes", idleMinutes(c));
            o.put("haptics", haptics(c));
            o.put("sounds", sounds(c));
            o.put("heading_up", headingUp(c));
            o.put("voice", sp(c).getString("voice", Voice.BUILT_IN));
            o.put("convoy_wake", convoyWake(c));
            o.put("convoy_autoplay", convoyAutoplay(c));
            o.put("convoy_text", convoyText(c));
            o.put("convoy_server", sp(c).getString("convoy_server", ""));
            o.put("convoy_start_keys", sp(c).getString("convoy_start_keys", "{}"));
            o.put("kiosk", kiosk(c));
            o.put("devices", devices(c));
            o.put("audio_routes", audioRoutes(c));
            o.put("sound_channels", sp(c).getString("sound_channels", "[]"));
            File dir = Storage.appDir(c);                   // the card's, when there is one
            if (dir == null) return;
            if (!dir.isDirectory()) dir.mkdirs();
            File tmp = new File(dir, "settings.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(o.toString(1).getBytes("UTF-8"));
            out.close();
            tmp.renameTo(new File(dir, "settings.json"));
        } catch (Exception e) {
            android.util.Log.w("Prefs", "cannot write settings.json", e);
        }
    }

    /** Takes in <card>/coldshift.dash/settings.json from the desktop, once per version. Returns whether it did. */
    static boolean restore(Context c) {
        for (String v : Storage.volumes()) {
            File f = new File(v, Storage.DIR + "/settings.json");
            if (!f.exists()) continue;
            String stamp = f.lastModified() + ":" + f.length();
            if (stamp.equals(sp(c).getString("settings_restored", ""))) return false;
            try {
                FileInputStream in = new FileInputStream(f);
                byte[] b = new byte[(int) Math.min(f.length(), 65536)];
                int n = in.read(b);
                in.close();
                JSONObject o = new JSONObject(new String(b, 0, Math.max(0, n), "UTF-8"));
                SharedPreferences.Editor e = sp(c).edit();
                if (o.has("brightness")) e.putInt("brightness", o.getInt("brightness"));
                if (o.has("idle_minutes")) e.putInt("idle_minutes", o.getInt("idle_minutes"));
                if (o.has("haptics")) e.putBoolean("haptics", o.getBoolean("haptics"));
                if (o.has("sounds")) e.putBoolean("sounds", o.getBoolean("sounds"));
                if (o.has("heading_up")) e.putBoolean("heading_up", o.getBoolean("heading_up"));
                if (o.has("voice")) e.putString("voice", o.getString("voice"));
                if (o.has("convoy_wake")) e.putBoolean("convoy_wake", o.getBoolean("convoy_wake"));
                if (o.has("convoy_autoplay")) e.putBoolean("convoy_autoplay", o.getBoolean("convoy_autoplay"));
                if (o.has("convoy_text")) e.putInt("convoy_text", o.getInt("convoy_text"));
                if (o.has("convoy_server")) e.putString("convoy_server", o.getString("convoy_server"));
                if (o.has("convoy_start_keys")) e.putString("convoy_start_keys", o.getString("convoy_start_keys"));
                if (o.has("kiosk")) e.putBoolean("kiosk", o.getBoolean("kiosk"));
                if (o.has("devices")) e.putString("devices", o.getString("devices"));
                if (o.has("audio_routes")) e.putString("audio_routes", o.getString("audio_routes"));
                if (o.has("sound_channels")) e.putString("sound_channels", o.getString("sound_channels"));
                e.putString("settings_restored", stamp).apply();
                android.util.Log.i("Prefs", "restored settings from " + f);
                return true;
            } catch (Exception ex) {
                android.util.Log.w("Prefs", "cannot read " + f, ex);
                return false;
            }
        }
        return false;
    }

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("carui", Context.MODE_PRIVATE); }

    static int brightness(Context c) { return sp(c).getInt("brightness", 140); }
    static void setBrightness(Context c, int v) { sp(c).edit().putInt("brightness", v).apply(); }

    static boolean headingUp(Context c) { return sp(c).getBoolean("heading_up", true); }
    static void setHeadingUp(Context c, boolean v) { sp(c).edit().putBoolean("heading_up", v).apply(); }

    /** Home or Work as {lat, lon} from before places.json (Places moves them over), or null. */
    static double[] place(Context c, String key) {
        String v = sp(c).getString("place_" + key, null);
        if (v == null) return null;
        String[] p = v.split(",");
        return new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1])};
    }

    /**
     * Kiosk mode: the app starts with the phone, keeps itself in front, shows over the lock screen and Back
     * does nothing. On by default on the car units' old Android (4.4); off on a phone that is also someone's
     * own (newer Android, which no longer lets an app start itself at boot or see other apps in front), where
     * the app is an ordinary app.
     */
    static boolean kiosk(Context c) {
        return sp(c).getBoolean("kiosk", android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP);
    }

    static void setKiosk(Context c, boolean v) { sp(c).edit().putBoolean("kiosk", v).apply(); }

    /** Whether a convoy message turns the screen on when it is off (else it only chimes). */
    static boolean convoyWake(Context c) { return sp(c).getBoolean("convoy_wake", true); }
    static void setConvoyWake(Context c, boolean v) { sp(c).edit().putBoolean("convoy_wake", v).apply(); }

    /** Whether the convoy's voice notes play as they arrive (else they wait, marked, to be tapped). */
    static boolean convoyAutoplay(Context c) { return sp(c).getBoolean("convoy_autoplay", true); }
    static void setConvoyAutoplay(Context c, boolean v) { sp(c).edit().putBoolean("convoy_autoplay", v).apply(); }

    /** How big the convoy's messages (the Convoy page's list, the banner) are drawn: Normal, Large, Extra large. */
    static final String[] CONVOY_TEXT = {"Normal", "Large", "Extra large"};
    static final float[] CONVOY_TEXT_SCALE = {1f, 1.35f, 1.7f};
    static int convoyText(Context c) { return Math.max(0, Math.min(CONVOY_TEXT.length - 1, sp(c).getInt("convoy_text", 0))); }
    static void setConvoyText(Context c, int v) { sp(c).edit().putInt("convoy_text", v).apply(); }
    /** The factor the messages are drawn at (1 for Normal). */
    static float convoyTextScale(Context c) { return CONVOY_TEXT_SCALE[convoyText(c)]; }

    /**
     * The Bluetooth devices chosen for this car's roles (Devices): a JSON string, "" for none. Devices reads
     * and writes it; it is backed up in settings.json with the other settings.
     */
    static String devices(Context c) { return sp(c).getString("devices", ""); }
    static void setDevices(Context c, String v) { sp(c).edit().putString("devices", v).apply(); }

    /** Which sound device does which job (Audio), as JSON: {"<job>": "<kind>:<name>"}. */
    static String audioRoutes(Context c) { return sp(c).getString("audio_routes", "{}"); }
    static void setAudioRoutes(Context c, String v) { sp(c).edit().putString("audio_routes", v).apply(); }

    /** What is on each of the sound recorder's channels ("Engine", "Exhaust"), "" where it has no name. */
    static String soundChannel(Context c, int i) {
        try {
            return new org.json.JSONArray(sp(c).getString("sound_channels", "[]")).optString(i, "");
        } catch (Exception e) {
            return "";
        }
    }

    static void setSoundChannel(Context c, int i, String name) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(sp(c).getString("sound_channels", "[]"));
            while (a.length() <= i) a.put("");
            a.put(i, name == null ? "" : name.trim());
            sp(c).edit().putString("sound_channels", a.toString()).apply();
        } catch (Exception e) { /* only strings */ }
    }

    /** Whether a pressed button buzzes (Haptics). */
    static boolean haptics(Context c) { return sp(c).getBoolean("haptics", true); }
    static void setHaptics(Context c, boolean v) { sp(c).edit().putBoolean("haptics", v).apply(); }

    /** Whether the app's own sounds play (Cues): recording started and stopped, marks, warnings. */
    static boolean sounds(Context c) { return sp(c).getBoolean("sounds", true); }
    static void setSounds(Context c, boolean v) { sp(c).edit().putBoolean("sounds", v).apply(); }

    static int idleMinutes(Context c) { return sp(c).getInt("idle_minutes", 5); }
    static void setIdleMinutes(Context c, int m) { sp(c).edit().putInt("idle_minutes", m).apply(); }
}

package racing.coldshift.dash;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Which sound device does which job: a Bluetooth headset for Talk and the voice notes, a USB interface for the
 * sound recorder, the car's speakers for the music. Settings → Sound chooses them (SoundSettings).
 *
 * Android 6 lets a recorder or player ask for a device (`setPreferredDevice` on AudioRecord and AudioTrack; on
 * MediaPlayer and MediaRecorder from Android 9). A device gets a new id each time it connects, so a choice is kept
 * as its kind and product name ("usb:UMC202HD"), per car in the preference `audio_routes` (backed up with the
 * settings). A job whose device is not connected, or has none chosen, goes where Android sends it; on Android 4.4
 * that is every job.
 *
 * A Bluetooth headset's microphone only works over the phone-call link (SCO): `startInput` brings it up for Talk
 * (setCommunicationDevice from Android 12, startBluetoothSco before) and `endInput` takes it down again.
 */
final class Audio {
    static final int TALK_IN = 0, VOICE_OUT = 1, PROMPTS_OUT = 2, MUSIC_OUT = 3, RECORD_IN = 4, DASHCAM_IN = 5;
    static final int JOBS = 6;
    static final String[] JOB_NAME = {"Talk", "Voice notes", "Navigation", "Music", "Sound recorder", "Dashcam sound"};
    static final String[] JOB_ABOUT = {"Records your voice notes", "Voice notes and the convoy chime", "Spoken turns",
        "The music player", "Sensors → Sound", "The dashcam clips' soundtrack"};
    static final boolean[] INPUT = {true, false, false, false, true, true};

    /** A device connected now. */
    static final class Dev {
        final AudioDeviceInfo info;
        final String key, label;
        Dev(AudioDeviceInfo info, String key, String label) { this.info = info; this.key = key; this.label = label; }
    }

    private static Audio inst;
    private final Context ctx;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<Runnable>();
    private JSONObject routes;
    private boolean scoUp;

    static synchronized Audio get(Context c) {
        if (inst == null) inst = new Audio(c.getApplicationContext());
        return inst;
    }

    private Audio(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        try {
            routes = new JSONObject(Prefs.audioRoutes(c));
        } catch (Exception e) {
            routes = new JSONObject();
        }
        if (supported()) {
            am.registerAudioDeviceCallback(new android.media.AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { changed(); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { changed(); }
            }, main);
        }
    }

    /** Whether this Android lets the app choose devices at all (6 and later). */
    static boolean supported() { return Api.SDK >= 23; }

    /** Runs on the UI thread when a device comes or goes, or a choice changes. */
    void addListener(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    void removeListener(Runnable r) { listeners.remove(r); }

    private void changed() { for (Runnable r : new ArrayList<Runnable>(listeners)) r.run(); }

    // ---- the devices ----

    /** The devices connected now that can do the job, Android's own first. */
    List<Dev> devices(int job) {
        List<Dev> out = new ArrayList<Dev>();
        if (!supported()) return out;
        boolean in = INPUT[job];
        for (AudioDeviceInfo d : am.getDevices(in ? AudioManager.GET_DEVICES_INPUTS : AudioManager.GET_DEVICES_OUTPUTS)) {
            String kind = kind(d.getType());
            if (kind == null) continue;
            // the call link's 16 kHz is for speech: not for the engine, the dashcam or music
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && job != TALK_IN && job != VOICE_OUT && job != PROMPTS_OUT) continue;
            String name = String.valueOf(d.getProductName()).trim();
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC || d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) name = "";
            String key = kind + ":" + name;
            boolean dup = false;
            for (Dev o : out) if (o.key.equals(key)) dup = true;       // a device can list itself once per address
            if (!dup) out.add(new Dev(d, key, label(kind, name)));
        }
        return out;
    }

    /** The short kind for a device type, or null for one the car has no use for (the earpiece, telephony, HDMI). */
    private static String kind(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "mic";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "wired";
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_AUX_LINE: return "line";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "usb";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "bt";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "btcall";
            default:
                if (Api.SDK >= 31 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) return "ble";
                return null;
        }
    }

    private static String label(String kind, String name) {
        String k = kind.equals("mic") ? "The phone's microphone" : kind.equals("speaker") ? "The phone's speaker"
            : kind.equals("wired") ? "Headphone jack" : kind.equals("line") ? "Line out"
            : kind.equals("usb") ? "USB" : kind.equals("bt") ? "Bluetooth" : kind.equals("btcall") ? "Bluetooth (call)"
            : kind.equals("ble") ? "Bluetooth LE" : kind;
        return name.isEmpty() ? k : k + ": " + name;
    }

    // ---- the choices ----

    /** The key of the device chosen for the job ("" for Android's default). */
    String chosen(int job) { return routes.optString(String.valueOf(job), ""); }

    /** A label for the device chosen, whether connected or not. */
    String chosenLabel(int job) {
        String k = chosen(job);
        if (k.isEmpty()) return "Android's default";
        int c = k.indexOf(':');
        return c < 0 ? k : label(k.substring(0, c), k.substring(c + 1));
    }

    /** The chosen device when it is connected; null for Android's default (none chosen, or not connected). */
    AudioDeviceInfo device(int job) {
        String k = chosen(job);
        if (k.isEmpty()) return null;
        for (Dev d : devices(job)) if (d.key.equals(k)) return d.info;
        return null;
    }

    /**
     * Whether the job's input is a device of its own (not the phone's microphone) that offers two channels: a USB
     * interface, or a wireless mic receiver in stereo mode (engine left, exhaust right). A device that lists no
     * channel counts takes any, so it counts; the caller falls back to one channel if recording refuses.
     */
    boolean twoChannels(int job) {
        AudioDeviceInfo d = device(job);
        if (d == null || d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return false;
        int[] counts = d.getChannelCounts();
        if (counts == null || counts.length == 0) return true;
        for (int n : counts) if (n >= 2) return true;
        return false;
    }

    /** A device is chosen but not connected. */
    boolean missing(int job) { return !chosen(job).isEmpty() && device(job) == null; }

    void choose(int job, String key) {
        try {
            if (key == null || key.isEmpty()) routes.remove(String.valueOf(job));
            else routes.put(String.valueOf(job), key);
        } catch (Exception e) { /* only strings */ }
        Prefs.setAudioRoutes(ctx, routes.toString());
        changed();
    }

    // ---- sending sound where it was chosen ----

    void apply(MediaPlayer mp, int job) {
        if (Api.SDK >= 28 && mp != null) {
            try { mp.setPreferredDevice(device(job)); } catch (Throwable t) { /* Android's choice */ }
        }
    }

    void apply(MediaRecorder r, int job) {
        if (Api.SDK >= 28 && r != null) {
            try { r.setPreferredDevice(device(job)); } catch (Throwable t) { /* Android's choice */ }
        }
    }

    void apply(AudioRecord r, int job) {
        if (supported() && r != null) {
            try { r.setPreferredDevice(device(job)); } catch (Throwable t) { /* Android's choice */ }
        }
    }

    void apply(AudioTrack t, int job) {
        if (supported() && t != null) {
            try { t.setPreferredDevice(device(job)); } catch (Throwable x) { /* Android's choice */ }
        }
    }

    /** Before recording for the job: a Bluetooth headset's microphone needs the call link up. */
    void startInput(int job) {
        AudioDeviceInfo d = device(job);
        if (d == null || d.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return;
        try {
            if (Api.SDK >= 31) am.setCommunicationDevice(d);
            else {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
            }
            scoUp = true;
        } catch (Throwable t) {
            android.util.Log.w("Audio", "cannot bring up the headset's microphone", t);
        }
    }

    /** After recording: the call link goes down, so the headset is back to ordinary sound. */
    void endInput() {
        if (!scoUp) return;
        scoUp = false;
        try {
            if (Api.SDK >= 31) am.clearCommunicationDevice();
            else {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
            }
        } catch (Throwable t) { /* gone already */ }
    }

    /** Whether the job's input is a Bluetooth headset (the call link takes a moment to come up). */
    boolean headsetInput(int job) {
        AudioDeviceInfo d = device(job);
        return d != null && d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
    }

    // ---- tones ----

    /**
     * A tone for the job (`which` as ToneGenerator's): Android's own tone when the job has no device of its own,
     * else the same pattern drawn as a sine wave and played on the chosen device.
     */
    void tone(int job, int which, int ms) {
        if (device(job) == null) {
            try {
                final ToneGenerator t = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
                t.startTone(which, ms);
                main.postDelayed(new Runnable() {
                    public void run() { t.release(); }
                }, ms + 200);
            } catch (RuntimeException e) { /* no tone */ }
            return;
        }
        // an acknowledgement is two quick notes; anything else one
        int[][] notes = which == ToneGenerator.TONE_PROP_ACK ? new int[][] {{1200, 120}, {0, 60}, {1500, 160}}
            : new int[][] {{1000, Math.min(ms, 300)}};
        play(job, notes);
    }

    /** Plays notes ({hz, ms}; 0 Hz is a rest) on the job's device. */
    void play(int job, int[][] notes) {
        final int rate = 44100;
        int frames = 0;
        for (int[] n : notes) frames += rate * n[1] / 1000;
        short[] pcm = new short[frames];
        int at = 0;
        for (int[] n : notes) {
            int len = rate * n[1] / 1000, fade = Math.min(len / 4, rate / 200);
            for (int i = 0; i < len; i++) {
                double env = Math.min(1, Math.min(i, len - 1 - i) / (double) Math.max(1, fade));
                pcm[at + i] = n[0] == 0 ? 0 : (short) (Math.sin(2 * Math.PI * n[0] * i / rate) * 0.4 * env * Short.MAX_VALUE);
            }
            at += len;
        }
        try {
            @SuppressWarnings("deprecation")
            final AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
            t.write(pcm, 0, pcm.length);
            apply(t, job);
            t.play();
            main.postDelayed(new Runnable() {
                public void run() { try { t.release(); } catch (Throwable x) { /* gone */ } }
            }, frames * 1000L / rate + 300);
        } catch (Throwable e) {
            android.util.Log.w("Audio", "cannot play a tone", e);
        }
    }
}

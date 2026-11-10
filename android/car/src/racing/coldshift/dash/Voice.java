package racing.coldshift.dash;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Speaks navigation prompts from a voice pack: short recorded lines played one after another
 * ("in_200m", "turn_left", "then", "turn_right"). Packs are folders with a voice.json and one MP3 per
 * line (dashsync/voice.py makes them): Angie is built in (assets/voice/angie/), others come from
 * <volume>/coldshift.dash/voice/<id>/ and replace a built-in pack with the same id. The music ducks
 * while a prompt plays.
 */
final class Voice implements MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {
    static final String BUILT_IN = "angie", OFF = "off";

    static final class Pack {
        String id, name;
        File dir;                         // null: in the app's assets
        final Map<String, String> lines = new HashMap<String, String>();
    }

    private static Voice instance;
    private final Context ctx;
    private final AudioManager am;
    private List<Pack> packs;
    private MediaPlayer mp;
    private final List<String> queue = new ArrayList<String>();
    private Pack speaking;
    private String[] waiting;             // the newest prompt that came while another was being said

    static synchronized Voice get(Context c) {
        if (instance == null) instance = new Voice(c.getApplicationContext());
        return instance;
    }

    private Voice(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    /** The built-in pack and those on the card, by name. */
    synchronized List<Pack> packs() {
        if (packs != null) return packs;
        Map<String, Pack> byId = new java.util.LinkedHashMap<String, Pack>();
        // the packs that come with the app (assets/voice/<id>), the default one first
        java.util.List<String> built = new ArrayList<String>();
        built.add(BUILT_IN);
        try {
            String[] dirs = ctx.getAssets().list("voice");
            if (dirs != null) { java.util.Arrays.sort(dirs); for (String d : dirs) if (!built.contains(d)) built.add(d); }
        } catch (Exception e) { /* only the default then */ }
        for (String id : built) {
            try {
                Pack p = read(ctx.getAssets().open("voice/" + id + "/voice.json"), null);
                if (p != null) byId.put(p.id, p);
            } catch (Exception e) {
                android.util.Log.w("Voice", "no built-in voice " + id, e);
            }
        }
        for (String v : Storage.volumes()) {
            File[] dirs = new File(v, Storage.DIR + "/voice").listFiles();
            if (dirs == null) continue;
            java.util.Arrays.sort(dirs);
            for (File d : dirs) {
                File json = new File(d, "voice.json");
                if (!json.exists()) continue;
                try {
                    Pack p = read(new FileInputStream(json), d);
                    if (p != null) byId.put(p.id, p);
                } catch (Exception e) {
                    android.util.Log.w("Voice", "cannot read " + json, e);
                }
            }
        }
        packs = new ArrayList<Pack>(byId.values());
        return packs;
    }

    private static Pack read(InputStream in, File dir) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n = 0, r;
        while ((r = in.read(buf, n, buf.length - n)) > 0) n += r;
        in.close();
        JSONObject o = new JSONObject(new String(buf, 0, n, "UTF-8"));
        Pack p = new Pack();
        p.id = o.getString("id");
        p.name = o.getString("name");
        p.dir = dir;
        JSONObject lines = o.getJSONObject("lines");
        java.util.Iterator<String> keys = lines.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            p.lines.put(k, lines.getString(k));
        }
        return p;
    }

    /** After a sync: read the card's packs again. */
    synchronized void reload() { packs = null; }

    String chosen() { return Prefs.sp(ctx).getString("voice", BUILT_IN); }

    void choose(String id) { Prefs.sp(ctx).edit().putString("voice", id).apply(); }

    private Pack pack() {
        String id = chosen();
        if (id.equals(OFF)) return null;
        Pack fallback = null;
        for (Pack p : packs()) {
            if (p.id.equals(id)) return p;
            if (p.id.equals(BUILT_IN)) fallback = p;
        }
        return fallback;                  // the chosen pack is gone from the card
    }

    /**
     * Speaks the lines in order. A prompt that comes while another is being said waits for it to end
     * (a newer one replaces it while it waits). Lines the pack lacks are skipped.
     */
    synchronized void say(String... keys) {
        if (mp != null) {
            waiting = keys;
            return;
        }
        Pack p = pack();
        if (p == null) return;
        android.util.Log.i("Voice", "say " + java.util.Arrays.toString(keys));
        queue.clear();
        for (String k : keys) if (k != null && p.lines.containsKey(k)) queue.add(k);
        if (queue.isEmpty()) return;
        speaking = p;
        am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        next();
    }

    private void next() {
        while (!queue.isEmpty()) {
            String file = speaking.lines.get(queue.remove(0));
            try {
                mp = new MediaPlayer();
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                Audio.get(ctx).apply(mp, Audio.PROMPTS_OUT);
                if (speaking.dir == null) {
                    AssetFileDescriptor fd = ctx.getAssets().openFd("voice/" + speaking.id + "/" + file);
                    mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                    fd.close();
                } else {
                    mp.setDataSource(new File(speaking.dir, file).getPath());
                }
                mp.setOnCompletionListener(this);
                mp.setOnErrorListener(this);
                mp.prepare();
                mp.start();
                return;
            } catch (Exception e) {
                android.util.Log.w("Voice", "cannot play " + file, e);
                stopPlayer();
            }
        }
        if (waiting != null) {
            String[] w = waiting;
            waiting = null;
            say(w);
            if (mp != null) return;
        }
        am.abandonAudioFocus(this);
    }

    private void stopPlayer() {
        if (mp == null) return;
        try { mp.release(); } catch (Throwable t) { /* gone */ }
        mp = null;
    }

    public synchronized void onCompletion(MediaPlayer m) {
        stopPlayer();
        next();
    }

    public synchronized boolean onError(MediaPlayer m, int what, int extra) {
        android.util.Log.w("Voice", "player error " + what + "/" + extra);
        stopPlayer();
        next();
        return true;
    }

    public void onAudioFocusChange(int change) { /* prompts are short; nothing to do */ }
}

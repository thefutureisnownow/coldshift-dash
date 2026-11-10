package racing.coldshift.dash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.PowerManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;

/** Queue, shuffle/repeat and playback. One instance for the whole process. */
final class Player implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener, Runnable {
    static final int REPEAT_ALL = 0, REPEAT_ONE = 1, REPEAT_OFF = 2;
    private static Player inst;

    static synchronized Player get(Context c) {
        if (inst == null) {
            inst = new Player(c.getApplicationContext());
            inst.restore();
        }
        return inst;
    }

    private static class Noisy extends BroadcastReceiver {
        private final Player p;
        Noisy(Player p) { this.p = p; }
        @Override public void onReceive(Context c, Intent i) { p.pause(); }
    }

    private final Context ctx;
    private final AudioManager am;
    private final Library lib;
    private final Handler handler = new Handler();
    private final Random rnd = new Random();
    private MediaPlayer mp;
    private boolean prepared, playing, resumeOnGain, wantPlay;
    private int savedPos, errors;
    private final List<Track> queue = new ArrayList<Track>();
    private int[] order = new int[0];
    private int pos = -1;
    boolean shuffle;
    int repeat = REPEAT_ALL;
    String browse = "";

    private Player(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        lib = Library.get(c);
        c.registerReceiver(new Noisy(this), new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    // ---- state ----

    Track current() { return pos >= 0 && pos < order.length ? queue.get(order[pos]) : null; }
    boolean isPlaying() { return playing; }
    int queueSize() { return queue.size(); }
    int queuePos() { return pos + 1; }

    int position() {
        if (mp != null && prepared) { try { return mp.getCurrentPosition(); } catch (Throwable t) { return savedPos; } }
        return savedPos;
    }

    int duration() {
        Track t = current();
        if (mp != null && prepared) { try { return mp.getDuration(); } catch (Throwable e) { /* fall through */ } }
        return t == null ? 0 : t.durationMs;
    }

    float volume() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max == 0 ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    void setVolume(float f) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Math.max(0, Math.min(1, f)) * max), 0);
    }

    // ---- control ----

    void play(List<Track> tracks, int start, boolean shuf) {
        if (tracks.isEmpty()) return;
        queue.clear();
        queue.addAll(tracks);
        shuffle = shuf;
        buildOrder(Math.max(0, Math.min(start, queue.size() - 1)));
        load(0, true);
    }

    private void buildOrder(int first) {
        order = new int[queue.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        if (shuffle) {
            for (int i = order.length - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = order[i]; order[i] = order[j]; order[j] = t;
            }
            for (int i = 0; i < order.length; i++) {
                if (order[i] == first) { int t = order[0]; order[0] = order[i]; order[i] = t; break; }
            }
            pos = 0;
        } else {
            pos = first;
        }
    }

    void toggleShuffle() {
        Track cur = current();
        shuffle = !shuffle;
        if (cur != null) buildOrder(queue.indexOf(cur));
        save();
    }

    void cycleRepeat() { repeat = (repeat + 1) % 3; save(); }

    void toggle() {
        if (current() == null) return;
        if (playing) pause();
        else if (mp == null) load(savedPos, true);
        else startPlayback();
    }

    void pause() {
        wantPlay = false;
        if (mp != null && prepared && playing) {
            try { mp.pause(); savedPos = mp.getCurrentPosition(); } catch (Throwable t) { /* ignore */ }
        }
        playing = false;
        PlayerService.update(ctx, current(), false);
        save();
    }

    void next() {
        if (order.length == 0) return;
        pos++;
        if (pos >= order.length) {
            pos = 0;
            if (shuffle) buildOrder(order[order.length - 1]);
        }
        load(0, true);
    }

    void prev() {
        if (order.length == 0) return;
        if (position() > 3000) { seekTo(0); return; }
        pos = pos <= 0 ? order.length - 1 : pos - 1;
        load(0, true);
    }

    void seekTo(int ms) {
        savedPos = ms;
        if (mp != null && prepared) { try { mp.seekTo(ms); } catch (Throwable t) { /* ignore */ } }
        save();
    }

    private void load(int seek, boolean autoplay) {
        release();
        Track t = current();
        if (t == null) return;
        if (!new File(t.path).exists()) { playing = false; PlayerService.update(ctx, t, false); return; }
        savedPos = seek;
        wantPlay = autoplay;
        try {
            mp = new MediaPlayer();
            mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            Audio.get(ctx).apply(mp, Audio.MUSIC_OUT);
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.setDataSource(t.path);
            mp.prepareAsync();
        } catch (Throwable e) {
            onError(mp, 0, 0);
        }
        save();
    }

    private void release() {
        playing = false;
        prepared = false;
        if (mp != null) {
            try { mp.reset(); mp.release(); } catch (Throwable t) { /* ignore */ }
            mp = null;
        }
    }

    private void startPlayback() {
        if (mp == null || !prepared) { wantPlay = true; return; }
        if (am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
        try {
            Audio.get(ctx).apply(mp, Audio.MUSIC_OUT);          // the device may have come back since
            mp.setVolume(1f, 1f);
            mp.start();
            playing = true;
            errors = 0;
            PlayerService.update(ctx, current(), true);
            handler.removeCallbacks(this);
            handler.postDelayed(this, 15000);
        } catch (Throwable t) { playing = false; }
    }

    // ---- MediaPlayer callbacks ----

    public void onPrepared(MediaPlayer m) {
        if (m != mp) return;
        prepared = true;
        if (savedPos > 0) { try { m.seekTo(savedPos); } catch (Throwable t) { /* ignore */ } }
        if (wantPlay) startPlayback();
    }

    public void onCompletion(MediaPlayer m) {
        if (m != mp) return;
        savedPos = 0;
        if (repeat == REPEAT_ONE) { load(0, true); return; }
        if (repeat == REPEAT_OFF && pos >= order.length - 1) {
            playing = false;
            PlayerService.update(ctx, current(), false);
            return;
        }
        next();
    }

    public boolean onError(MediaPlayer m, int what, int extra) {
        if (m != null && m != mp) return true;
        playing = false;
        if (++errors < 5 && order.length > 1) next();
        else PlayerService.update(ctx, current(), false);
        return true;
    }

    public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            pause();
            am.abandonAudioFocus(this);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (playing) { resumeOnGain = true; pause(); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (mp != null && playing) { try { mp.setVolume(0.25f, 0.25f); } catch (Throwable t) { /* ignore */ } }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeOnGain) { resumeOnGain = false; toggle(); }
            else if (mp != null && playing) { try { mp.setVolume(1f, 1f); } catch (Throwable t) { /* ignore */ } }
        }
    }

    // ---- persistence ----

    public void run() {
        if (playing) {
            save();
            handler.postDelayed(this, 15000);
        }
    }

    void save() {
        try {
            JSONArray q = new JSONArray();
            for (Track t : queue) q.put(t.path);
            JSONArray o = new JSONArray();
            for (int i : order) o.put(i);
            SharedPreferences.Editor e = Prefs.sp(ctx).edit();
            e.putString("pl_queue", q.toString());
            e.putString("pl_order", o.toString());
            e.putInt("pl_pos", pos);
            e.putInt("pl_ms", position());
            e.putBoolean("pl_shuffle", shuffle);
            e.putInt("pl_repeat", repeat);
            e.putString("pl_browse", browse);
            e.apply();
        } catch (Throwable t) { /* best effort */ }
    }

    private void restore() {
        SharedPreferences sp = Prefs.sp(ctx);
        browse = sp.getString("pl_browse", "");
        shuffle = sp.getBoolean("pl_shuffle", false);
        repeat = sp.getInt("pl_repeat", REPEAT_ALL);
        try {
            JSONArray q = new JSONArray(sp.getString("pl_queue", "[]"));
            JSONArray o = new JSONArray(sp.getString("pl_order", "[]"));
            List<Integer> keep = new ArrayList<Integer>();
            for (int i = 0; i < q.length(); i++) {
                Track t = lib.resolve(q.getString(i));
                if (t != null) { keep.add(queue.size()); queue.add(t); } else keep.add(-1);
            }
            List<Integer> ord = new ArrayList<Integer>();
            for (int i = 0; i < o.length(); i++) {
                int idx = keep.get(o.getInt(i));
                if (idx >= 0) ord.add(idx);
            }
            order = new int[ord.size()];
            for (int i = 0; i < order.length; i++) order[i] = ord.get(i);
            pos = sp.getInt("pl_pos", 0);
            if (pos >= order.length) pos = order.length - 1;
            savedPos = sp.getInt("pl_ms", 0);
        } catch (Throwable t) {
            queue.clear();
            order = new int[0];
            pos = -1;
        }
    }
}

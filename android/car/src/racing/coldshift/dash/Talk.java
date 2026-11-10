package racing.coldshift.dash;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Push to talk in a convoy: voice notes recorded here (AAC in MPEG-4, 16 kHz mono at 24 kbit/s, MAX_MS at
 * most, about 90 KB) go to the others through the convoy server (Convoy.sendVoice), and theirs play as they
 * arrive (Settings: Play voice notes; else they wait, marked, for a tap), one after another. The music
 * pauses while one records or plays and carries on after (Player).
 *
 * The Talk button (the Convoy page, the map) works both ways: held, it sends when let go; tapped, it
 * records until tapped again (Cancel drops it). The map's button talks to the whole convoy; the Convoy page's
 * to the car chosen there, if one is (press(to)), and the buttons then say so. A tone marks the start and the sending, for a driver not
 * looking. Without a connection it still records: the note waits in the convoy's outbox and goes when the
 * connection is back (Convoy). Nothing plays while recording: what arrives meanwhile waits its turn. Android 4.4 lets one
 * recording have the microphone, so while the dashcam records with sound it goes on without for the note
 * (Dashcam.talk: a new clip each way).
 */
final class Talk {
    interface Listener {
        void talkChanged();
    }

    static final int MAX_MS = 30000, MIN_MS = 700, HOLD_MS = 450;
    private static final int IDLE = 0, STARTING = 1, RECORDING = 2;

    private static Talk instance;
    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final List<Convoy.Message> queue = new ArrayList<Convoy.Message>();

    private int state = IDLE;
    private boolean latched;                // tapped, not held: records until tapped again
    private boolean borrowed;               // the dashcam lent its microphone
    private String to = "", toName = "";    // who the note being recorded goes to: a member's id and name ("" for the convoy)
    private long downAt, startedAt;
    private MediaRecorder rec;
    private File out;
    private String note = "";

    private MediaPlayer mp;
    private Convoy.Message playing, fetching;
    private boolean musicPaused;            // the music was playing and was paused here

    static synchronized Talk get(Context c) {
        if (instance == null) instance = new Talk(c.getApplicationContext());
        return instance;
    }

    private Talk(Context c) {
        ctx = c;
        File[] old = new File(c.getCacheDir(), "voice").listFiles();     // the last run's
        if (old != null) for (File f : old) f.delete();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    private void changed() { for (Listener l : new ArrayList<Listener>(listeners)) l.talkChanged(); }

    // ---- what the buttons show ----

    boolean recording() { return state != IDLE; }

    /** Tapped rather than held: the button now sends, and Cancel shows. */
    boolean latched() { return state != IDLE && latched; }

    /** The id of the member the note being recorded goes to, "" for the whole convoy (or when not recording). */
    String recipient() { return state != IDLE ? to : ""; }

    /** That member's name, as it was when recording began. */
    String recipientName() { return state != IDLE ? toName : ""; }

    /** How long this one has been recording, in ms. */
    long recordedMs() { return state == RECORDING ? System.currentTimeMillis() - startedAt : 0; }

    /** The voice note playing (or being fetched to play), or null. */
    Convoy.Message playing() { return playing != null ? playing : fetching; }

    /** Why the last try did not go (too short, no connection, the microphone busy), once; "" otherwise. */
    String takeNote() {
        String n = note;
        note = "";
        return n;
    }

    /** "0:07 / 0:30", for the button. */
    String clock() {
        long s = recordedMs() / 1000;
        return String.format(java.util.Locale.US, "0:%02d / 0:%02d", s, MAX_MS / 1000);
    }

    // ---- the Talk button ----

    /** The button went down: a note for the whole convoy (the map's Talk button). */
    void press() { press("", ""); }

    /**
     * The button went down: start (held or tapped), or send one that was tapped into recording. The note goes
     * to the member `toId` (named `toWho`) when that is given, else to the whole convoy; the one that began
     * a recording decides, whatever button sends it.
     */
    void press(String toId, String toWho) {
        if (state != IDLE && latched) {
            stop(true);
            return;
        }
        if (state != IDLE) return;
        if (!Convoy.get(ctx).inConvoy()) return;     // offline is fine: it goes when the connection is back
        to = toId == null ? "" : toId;
        toName = toWho == null ? "" : toWho;
        downAt = System.currentTimeMillis();
        latched = false;
        begin();
    }

    /** The button came up: a hold sends, a tap keeps recording until the next tap. */
    void release() {
        if (state == IDLE || latched) return;
        if (System.currentTimeMillis() - downAt >= HOLD_MS) stop(true);
        else {
            latched = true;
            changed();
        }
    }

    void cancel() { if (state != IDLE) stop(false); }

    // ---- recording ----

    private void begin() {
        state = STARTING;
        stopPlaying(true);                  // what was playing plays again after
        pauseMusic();
        tone(ToneGenerator.TONE_PROP_BEEP, 150);
        Dashcam dc = Dashcam.get(ctx);
        borrowed = dc.usesMic();
        final Runnable go = new Runnable() {
            public void run() { startRecorder(); }
        };
        Audio au = Audio.get(ctx);
        au.startInput(Audio.TALK_IN);
        // a Bluetooth headset's microphone takes a moment: its call link comes up first
        final long wait = au.headsetInput(Audio.TALK_IN) ? 800 : 0;
        if (borrowed) dc.talk(true, new Runnable() {
            public void run() { h.postDelayed(go, wait); }
        });
        else h.postDelayed(go, Math.max(200, wait));        // after the tone, so it is not in the note
        h.post(ticker);
        changed();
    }

    private void startRecorder() {
        if (state != STARTING) return;      // cancelled meanwhile
        File dir = new File(ctx.getCacheDir(), "voice");
        dir.mkdirs();
        out = new File(dir, "mine-" + System.currentTimeMillis() + ".m4a");     // kept for playing here
        try {
            rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioChannels(1);
            rec.setAudioSamplingRate(16000);
            rec.setAudioEncodingBitRate(24000);
            rec.setMaxDuration(MAX_MS);
            rec.setOutputFile(out.getPath());
            Audio.get(ctx).apply(rec, Audio.TALK_IN);
            rec.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                public void onInfo(MediaRecorder r, int what, int extra) {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop(true);
                }
            });
            rec.prepare();
            rec.start();
            startedAt = System.currentTimeMillis();
            state = RECORDING;
        } catch (Exception e) {
            android.util.Log.w("Talk", "cannot record", e);
            releaseRecorder();
            note = "The microphone is busy: nothing was recorded.";
            finish();
        }
        changed();
    }

    private void stop(boolean send) {
        if (state == STARTING) {
            state = IDLE;
            note = send ? "Hold Talk while speaking, or tap it, speak, and tap again." : "";
            finish();
            return;
        }
        int ms = (int) recordedMs();
        boolean ok = true;
        try {
            rec.stop();
        } catch (RuntimeException e) {
            ok = false;                     // nothing recorded (let go at once)
        }
        releaseRecorder();
        byte[] sound = send && ok && ms >= MIN_MS ? read(out) : null;
        if (sound != null) {
            Convoy.get(ctx).sendVoice(sound, Math.min(ms, MAX_MS), out, to);
            tone(ToneGenerator.TONE_PROP_ACK, 300);
        } else {
            if (send) note = "Too short: hold Talk while speaking, or tap it, speak, and tap again.";
            if (out != null) out.delete();
        }
        finish();
    }

    /** After recording: the dashcam gets its sound back, and what waits plays (or the music carries on). */
    private void finish() {
        state = IDLE;
        latched = false;
        if (borrowed) Dashcam.get(ctx).talk(false, null);
        borrowed = false;
        Audio.get(ctx).endInput();
        h.removeCallbacks(ticker);
        h.postDelayed(new Runnable() {
            public void run() { playNext(); }
        }, 400);                            // after the tone
        changed();
    }

    private void releaseRecorder() {
        if (rec == null) return;
        try { rec.release(); } catch (RuntimeException e) { /* gone */ }
        rec = null;
    }

    /** The buttons' clock moves while recording. */
    private final Runnable ticker = new Runnable() {
        public void run() {
            if (state == IDLE) return;
            changed();
            h.postDelayed(this, 250);
        }
    };

    private static byte[] read(File f) {
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
            return n == b.length ? b : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- playing ----

    /** Someone's voice note came: it plays when its turn comes, if voice notes play as they arrive. */
    void arrived(Convoy.Message m) {
        if (!Prefs.convoyAutoplay(ctx)) return;
        queue.add(m);
        h.postDelayed(new Runnable() {
            public void run() { playNext(); }
        }, 700);                            // after the banner's chime
    }

    /** Tapped: plays it now (or stops it, when it is the one playing). */
    void toggle(Convoy.Message m) {
        if (m == playing()) {
            stopPlaying(false);
            playNext();
            return;
        }
        stopPlaying(false);
        queue.remove(m);
        queue.add(0, m);
        playNext();
    }

    private void playNext() {
        if (state != IDLE || mp != null || fetching != null) return;
        if (queue.isEmpty()) {
            resumeMusic();
            return;
        }
        final Convoy.Message m = queue.remove(0);
        fetching = m;
        changed();
        new Thread(new Runnable() {
            public void run() {
                final File f = Convoy.get(ctx).fetchVoice(m);
                h.post(new Runnable() {
                    public void run() {
                        if (fetching != m) return;      // stopped meanwhile
                        fetching = null;
                        if (f == null) {
                            note = "That voice note is no longer on the convoy host.";
                            m.heard = true;
                            playNext();
                        } else {
                            start(m, f);
                        }
                        changed();
                    }
                });
            }
        }, "voice-note").start();
    }

    private void start(Convoy.Message m, File f) {
        if (state != IDLE) {                // talking began while it was fetched: it waits
            queue.add(0, m);
            return;
        }
        pauseMusic();
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            Audio.get(ctx).apply(mp, Audio.VOICE_OUT);
            mp.setDataSource(f.getPath());
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer p) {
                    stopPlaying(false);
                    playNext();
                }
            });
            mp.prepare();
            mp.start();
            playing = m;
            m.heard = true;
        } catch (Exception e) {
            android.util.Log.w("Talk", "cannot play " + f, e);
            stopPlaying(false);
            playNext();
        }
    }

    /** Stops what plays; `again`: it goes back to the front of the queue (interrupted by talking). */
    private void stopPlaying(boolean again) {
        Convoy.Message m = playing();
        if (again && m != null) queue.add(0, m);
        fetching = null;
        playing = null;
        if (mp != null) {
            try { mp.release(); } catch (RuntimeException e) { /* gone */ }
            mp = null;
        }
        changed();
    }

    /** Leaving the convoy: nothing more plays. */
    void clear() {
        queue.clear();
        cancel();
        stopPlaying(false);
        resumeMusic();
    }

    // ---- the music, and tones ----

    private void pauseMusic() {
        Player p = Player.get(ctx);
        if (p.isPlaying()) {
            p.pause();
            musicPaused = true;
        }
    }

    private void resumeMusic() {
        if (!musicPaused) return;
        musicPaused = false;
        Player p = Player.get(ctx);
        if (!p.isPlaying()) p.toggle();
    }

    private void tone(int which, int ms) { Audio.get(ctx).tone(Audio.VOICE_OUT, which, ms); }
}

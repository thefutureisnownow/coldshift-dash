package racing.coldshift.dash;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;

/**
 * The app's own short sounds, for what a driver cannot see without looking: recording started (two notes
 * up) and stopped (two notes down), a mark begun (a blip) and ended (two), a clip locked, a warning (three
 * beeps) and a gentle notice (a bell). They are assets/sounds/NAME.wav, built by tools/make_cues.py.
 *
 * A warning or a notice can be followed by a spoken line in the navigation voice (Voice), which says what
 * it is about; with the voice off, or a pack that lacks the line, the chime stands alone. Music dips while
 * a cue sounds. Off in Settings, Display ("Sounds").
 */
final class Cues implements AudioManager.OnAudioFocusChangeListener {
    static final String REC_START = "rec_start", REC_STOP = "rec_stop", MARK_ON = "mark_on", MARK_OFF = "mark_off",
        LOCK = "lock", WARN = "warn", NOTICE = "notice";
    private static final String[] ALL = {REC_START, REC_STOP, MARK_ON, MARK_OFF, LOCK, WARN, NOTICE};
    private static final int DIP_MS = 900, SPEAK_AFTER_MS = 800;

    private static Cues instance;
    private final Context ctx;
    private final AudioManager am;
    private final SoundPool pool;
    private final Map<String, Integer> ids = new HashMap<String, Integer>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable undip = new Runnable() {
        public void run() { am.abandonAudioFocus(Cues.this); }
    };

    /** Loads the sounds; call once early (Main), so the first cue is not late. */
    static synchronized Cues get(Context c) {
        if (instance == null) instance = new Cues(c.getApplicationContext());
        return instance;
    }

    @SuppressWarnings("deprecation")
    private Cues(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        pool = new SoundPool(3, AudioManager.STREAM_MUSIC, 0);
        for (String n : ALL) {
            try {
                AssetFileDescriptor fd = c.getAssets().openFd("sounds/" + n + ".wav");
                ids.put(n, pool.load(fd, 1));
                fd.close();
            } catch (Exception e) {
                android.util.Log.w("Cues", "no sound " + n, e);
            }
        }
    }

    /** Plays a cue (from any thread). */
    static void play(Context c, String name) { get(c).sound(name); }

    /** The warning chime, then the line `say` in the navigation voice (null for none). */
    static void warn(Context c, String say) { get(c).then(WARN, say); }

    /** The gentle bell, then the line `say` in the navigation voice (null for none). */
    static void notice(Context c, String say) { get(c).then(NOTICE, say); }

    private void then(String cue, final String say) {
        if (!sound(cue) || say == null) return;
        main.postDelayed(new Runnable() {
            public void run() { Voice.get(ctx).say(say); }
        }, SPEAK_AFTER_MS);
    }

    private boolean sound(String name) {
        if (!Prefs.sounds(ctx)) return false;
        Integer id = ids.get(name);
        if (id == null) return false;
        android.util.Log.i("Cues", name);
        // the music dips for it (Player ducks on this), and comes back after
        am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        main.removeCallbacks(undip);
        main.postDelayed(undip, DIP_MS);
        pool.play(id, 1f, 1f, 1, 0, 1f);
        return true;
    }

    public void onAudioFocusChange(int change) { /* cues are short; nothing to do */ }
}

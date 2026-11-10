package racing.coldshift.dash;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * The buzz under a finger for every button pressed (Press), as Coldshift Racing's haptics found: a phone's
 * motor hardly registers a short, weak pulse, and an Android phone's cannot tap faintly at all, so a tap
 * is 40 ms at full strength. Off in Settings, Display ("Vibrate on tap").
 */
final class Haptics {
    private static final long TAP_MS = 40, SAMPLE_MS = 120;

    private Haptics() {}

    /** A button was pressed. */
    static void tap(Context c) { if (Prefs.haptics(c)) buzz(c, TAP_MS); }

    /** The setting was just switched on: what it feels like. */
    static void sample(Context c) { buzz(c, SAMPLE_MS); }

    private static void buzz(Context c, long ms) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Api.SDK >= 26) v.vibrate(VibrationEffect.createOneShot(ms, 255));
            else v.vibrate(ms);
        } catch (Throwable t) { /* no motor, or not allowed: silent */ }
    }
}

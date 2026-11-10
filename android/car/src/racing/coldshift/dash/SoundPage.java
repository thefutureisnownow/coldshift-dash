package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * Sensors → Sound: the sound recorder (Sound). The input it uses and its format, a level meter for each channel
 * (for setting the interface's gain), Listen and Record, the engine's readings that go into the CSV beside the
 * sound, and the recordings so far.
 */
class SoundPage extends Page {
    private static final RectF LISTEN = new RectF(32, 540, 222, 600), RECORD = new RectF(236, 540, 470, 600);
    private static final float BAR_L = 66, BAR_R = 600;
    private static final long CLIP_HOLD = 3000;
    private static final float METER_Y = 250, METER_STEP = 70;
    private static final String[] NAMES = {"Engine", "Exhaust", "Intake", "Turbo", "Cabin", "Engine bay", "Tailpipe"};

    private final Sound sound;
    private final SimpleDateFormat fileFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private final SimpleDateFormat showFmt = new SimpleDateFormat("EEE d MMM  HH:mm:ss", Locale.US);

    SoundPage(Main a) {
        super(a);
        sound = Sound.get(a);
    }

    String label() { return "Sound"; }

    @Override boolean wide() { return true; }
    @Override int intervalMs() { return sound.listening ? 100 : 1000; }
    @Override void onHide() { sound.listen(false); }               // a recording carries on

    void draw(Canvas c) {
        String st = sound.status;
        Theme.text(c, Theme.fit(!st.isEmpty() ? st : sound.listening ? sound.format : "", 560, 18, Theme.sans),
            Wide.rx(1256), 96, 18, st.isEmpty() ? Theme.SUB : Theme.GOLD, Theme.sans, Theme.RIGHT);
        left(c);
        right(c);
    }

    private void left(Canvas c) {
        Theme.panel(c, 14, 120, 700, 612);
        Theme.text(c, "Input", 36, 158, 18, Theme.DIM, Theme.sans, Theme.LEFT);
        String in = sound.listening && !sound.input.isEmpty() ? sound.input
            : Audio.supported() ? Audio.get(act).chosenLabel(Audio.RECORD_IN) : "The phone's microphone";
        Theme.text(c, Theme.fit(in, 640, 22, Theme.bold), 36, 186, 22, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, Theme.fit(Audio.supported() ? "Chosen in Settings → Sound (Sound recorder)" : "Android 4.4: the phone's own input only",
            640, 18, Theme.sans), 36, 208, 18, Theme.DIM, Theme.sans, Theme.LEFT);

        int ch = channels();
        for (int i = 0; i < ch; i++) meter(c, i, ch, METER_Y + i * METER_STEP);
        float scaleY = METER_Y + ch * METER_STEP - 2;
        int[] marks = {-60, -40, -20, -12, -6, -3, 0};
        for (int m : marks) {
            float x = BAR_L + (BAR_R - BAR_L) * (m + 60) / 60f;
            Theme.text(c, String.valueOf(m), x, scaleY, 18, Theme.DIM, Theme.sans, Theme.CENTER);
        }
        Theme.text(c, "dBFS", BAR_R + 40, scaleY, 18, Theme.DIM, Theme.sans, Theme.CENTER);
        Theme.text(c, Theme.fit(sound.listening ? "Set the gain so the loudest revs peak around -6, never in the red."
            : "Tap a channel's name to change it.", 640, 18, Theme.sans), 36, 470, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.text(c, Theme.fit("The dashcam's clips have no sound while this listens or records.", 640, 18, Theme.sans),
            36, 496, 18, Theme.DIM, Theme.sans, Theme.LEFT);

        Theme.button(c, LISTEN, sound.listening && !sound.recording, 14);
        Theme.text(c, sound.listening && !sound.recording ? "Stop listening" : "Listen", LISTEN.centerX(), LISTEN.centerY() + 8, 22,
            0xffffffff, Theme.bold, Theme.CENTER);
        boolean rec = sound.recording;
        Theme.button(c, RECORD, !rec, 14);
        if (rec) {
            Theme.P.setShader(null);
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(0xffa3233a);
            c.drawRoundRect(RECORD, 14, 14, Theme.P);
        }
        Theme.text(c, rec ? "Stop recording" : "Record", RECORD.centerX(), RECORD.centerY() + 8, 22, 0xffffffff, Theme.bold, Theme.CENTER);
        if (rec) {
            long s = (SystemClock.elapsedRealtime() - sound.recStartReal) / 1000;
            boolean blink = (SystemClock.elapsedRealtime() / 500) % 2 == 0;
            Theme.P.setColor(blink ? 0xffe4002b : 0xff8a1426);
            c.drawCircle(496, 562, 8, Theme.P);
            Theme.text(c, String.format(Locale.US, "%d:%02d", s / 60, s % 60), 512, 569, 22, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, String.format(Locale.US, "%.1f MB", sound.recBytes / 1048576f), 512, 592, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        }
    }

    /** One channel: the peak as a bar, the loudness (RMS) as a white tick, and a clip lamp that stays lit a few seconds. */
    private void meter(Canvas c, int i, int ch, float y) {
        float pk = sound.listening ? sound.peak[i] : -120, rm = sound.listening ? sound.rms[i] : -120;
        boolean clip = SystemClock.elapsedRealtime() - sound.clipAt[i] < CLIP_HOLD && sound.clipAt[i] > 0;
        Theme.text(c, ch == 1 ? "" : i == 0 ? "L" : "R", 44, y + 26, 18, Theme.SUB, Theme.bold, Theme.CENTER);
        String name = Prefs.soundChannel(act, i);
        String side = ch == 1 ? "Mono" : i == 0 ? "Left, input 1" : "Right, input 2";
        Theme.text(c, Theme.fit(name.isEmpty() ? side + "  ·  tap to name it" : name, 360, 18, Theme.bold), BAR_L, y - 8, 18,
            name.isEmpty() ? Theme.DIM : 0xffffffff, Theme.bold, Theme.LEFT);
        if (!name.isEmpty()) Theme.text(c, side, BAR_R, y - 8, 18, Theme.DIM, Theme.sans, Theme.RIGHT);
        RectF track = new RectF(BAR_L, y, BAR_R, y + 34);
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(Theme.accent(0x30));
        c.drawRoundRect(track, 6, 6, Theme.P);
        // the zones, drawn up to the peak
        float[] from = {-60, -12, -3}, to = {-12, -3, 0};
        int[] col = {Theme.GREEN, Theme.AMBER, Theme.RED};
        for (int z = 0; z < 3; z++) {
            float end = Math.min(to[z], pk);
            if (end <= from[z]) continue;
            Theme.P.setColor(col[z]);
            c.drawRect(x(from[z]), y + 3, x(end), y + 31, Theme.P);
        }
        if (rm > -60) {
            Theme.P.setColor(0xddffffff);
            c.drawRect(x(rm) - 1.5f, y - 2, x(rm) + 1.5f, y + 36, Theme.P);
        }
        Theme.P.setColor(clip ? 0xffe4002b : Theme.accent(0x40));
        c.drawCircle(BAR_R + 40, y + 17, 13, Theme.P);
        Theme.text(c, clip ? "CLIP" : pk <= -119 ? "-" : String.format(Locale.US, "%.0f", pk), BAR_R + 40, y + 22, clip ? 14 : 16,
            0xffffffff, Theme.bold, Theme.CENTER);
    }

    private static float x(float db) { return BAR_L + (BAR_R - BAR_L) * (Math.max(-60, Math.min(0, db)) + 60) / 60f; }

    private void right(Canvas c) {
        float l = 716, r = Wide.rx(1266);
        Theme.panel(c, l, 120, r, 612);
        Theme.text(c, "Engine, written beside the sound", l + 22, 158, 18, Theme.DIM, Theme.sans, Theme.LEFT);
        Obd o = Obd.get(act);
        float rpm = o.value(Obd.RPM), kmh = o.value(Obd.SPEED), thr = o.value(Obd.THROTTLE);
        if (Float.isNaN(rpm) && Float.isNaN(kmh)) {
            Theme.text(c, "Not connected", l + 22, 196, 24, Theme.SUB, Theme.bold, Theme.LEFT);
            Theme.text(c, Theme.fit("The readings in the CSV stay blank until the Engine view is talking to the car.", r - l - 44, 18, Theme.sans),
                l + 22, 220, 18, Theme.DIM, Theme.sans, Theme.LEFT);
        } else {
            String[][] v = {{num(rpm), "rpm"}, {num(kmh), "km/h"}, {num(thr), "% throttle"}};
            float x = l + 22;
            for (String[] p : v) {
                Theme.text(c, p[0], x, 204, 36, 0xffffffff, Theme.bold, Theme.LEFT);
                Theme.text(c, p[1], x, 226, 18, Theme.SUB, Theme.sans, Theme.LEFT);
                x += (r - l - 44) / 3;
            }
        }

        List<File> recs = sound.recordings();
        Theme.text(c, "Recordings  ·  " + recs.size(), l + 22, 278, 18, Theme.DIM, Theme.sans, Theme.LEFT);
        float y = 312;
        for (int i = 0; i < Math.min(6, recs.size()); i++) {
            File f = recs.get(i);
            boolean now = sound.recording && f.equals(sound.file);
            Theme.text(c, when(f) + (now ? "  ·  recording" : ""), l + 22, y, 18, now ? Theme.GOLD : 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, String.format(Locale.US, "%.1f MB", f.length() / 1048576f), r - 22, y, 18, Theme.SUB, Theme.sans, Theme.RIGHT);
            y += 38;
        }
        if (recs.isEmpty()) Theme.text(c, "None yet", l + 22, y, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.text(c, Theme.fit("In Dashcam/Sound, a CSV of readings beside each WAV. The desktop tool copies them with the recordings.",
            r - l - 44, 18, Theme.sans), l + 22, 594, 18, Theme.DIM, Theme.sans, Theme.LEFT);
    }

    private static String num(float v) { return Float.isNaN(v) ? "-" : String.format(Locale.US, "%.0f", v); }

    private String when(File f) {
        String n = f.getName();
        try {
            return showFmt.format(fileFmt.parse(n.substring(6, 21)));
        } catch (Exception e) {
            return n;
        }
    }

    /** The channels on the meters: as the input gives them while listening, else two. */
    private int channels() { return sound.listening ? Math.max(1, Math.min(2, sound.channels)) : 2; }

    private void rename(final int i, int ch) {
        String side = ch == 1 ? "the channel" : i == 0 ? "the left channel (input 1)" : "the right channel (input 2)";
        Keypad.text(act, "What is on " + side, Prefs.soundChannel(act, i), java.util.Arrays.asList(NAMES), new Keypad.Done() {
            public void done(String value) {
                if (value != null) Prefs.setSoundChannel(act, i, value);
            }
        });
    }

    @Override boolean touch(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
        float x = e.getX(), y = e.getY();
        int ch = channels();
        for (int i = 0; i < ch; i++) {
            float my = METER_Y + i * METER_STEP;
            if (x >= 30 && x <= BAR_R + 60 && y >= my - 30 && y <= my + 36) { rename(i, ch); return true; }
        }
        if (LISTEN.contains(x, y) && !sound.recording) sound.listen(!sound.listening);
        else if (RECORD.contains(x, y)) sound.record(!sound.recording && !sound.recordWanted());
        return true;
    }
}

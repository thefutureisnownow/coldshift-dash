package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.view.MotionEvent;
import java.util.List;

/**
 * Settings → Sound: one row for each sound job (Audio) with the device chosen for it, a list of the devices
 * connected now to choose from, and a test: a few notes for an output, a live level for an input.
 */
final class SoundSettings {
    private static final float LEFT = 63, Y0 = 252, STEP = 56, H = 50;
    private static final float GRID_T = 262, GRID_ROW = 60;
    private static final int TEST_MS = 8000;

    private final Main act;
    private int choosing = -1;                  // the job whose device is being chosen, or -1
    private int testing = -1;                   // the job under test, or -1
    private long testUntil;
    private volatile float level = -120;        // the input test's peak, dBFS
    private volatile boolean clipped;
    private volatile String testNote = "";
    private Thread meter;
    private final Runnable redraw = new Runnable() {
        public void run() { if (act.ui() != null) act.ui().invalidate(); }
    };

    SoundSettings(Main a) { act = a; }

    void enter() {
        choosing = -1;
        Audio.get(act).addListener(redraw);
    }

    void leave() {
        stopTest();
        Audio.get(act).removeListener(redraw);
    }

    int intervalMs() { return testing >= 0 ? 100 : 1000; }

    private static RectF row(int job) { return new RectF(LEFT, Y0 + job * STEP, Wide.rx(1216), Y0 + job * STEP + H); }

    private static RectF button(RectF row, int fromRight, float w) {
        float r = row.right - 8 - fromRight;
        return new RectF(r - w, row.top + 7, r, row.bottom - 7);
    }

    // Default shows only once a device is chosen; Choose and Test close up to the right edge without it
    private static RectF defaultButton(RectF row) { return button(row, 0, 96); }
    private static RectF chooseButton(RectF row, boolean chosen) { return button(row, chosen ? 104 : 0, 104); }
    private static RectF testButton(RectF row, boolean chosen) { return button(row, chosen ? 216 : 112, 80); }
    private static RectF backButton() { return new RectF(Wide.rx(1216) - 96, 211, Wide.rx(1216), 247); }

    private static void chip(Canvas c, RectF r, String label, boolean on) { Theme.chip(c, r.left, r.top, r.right, r.bottom, label, on, 0); }

    // ---- drawing ----

    void draw(Canvas c) {
        if (testing >= 0 && System.currentTimeMillis() > testUntil) stopTest();
        if (choosing >= 0) { chooseView(c); return; }
        Audio a = Audio.get(act);
        String strip = !Audio.supported()
            ? "Android " + android.os.Build.VERSION.RELEASE + " sends every sound where it chooses: picking a device needs Android 6."
            : "Each job uses the device you choose; one that is not connected leaves the job to Android until it is.";
        Theme.text(c, Theme.fit(strip, Wide.rx(1216) - LEFT, 16, Theme.sans), LEFT, 238, 16, Theme.SUB, Theme.sans, Theme.LEFT);
        for (int job = 0; job < Audio.JOBS; job++) jobRow(c, a, job);
    }

    private void jobRow(Canvas c, Audio a, int job) {
        RectF r = row(job);
        Theme.button(c, r, false, 10);
        Theme.text(c, Audio.JOB_NAME[job], r.left + 16, r.top + 22, 18, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, Audio.JOB_ABOUT[job], r.left + 16, r.top + 40, 12, Theme.DIM, Theme.sans, Theme.LEFT);
        boolean chosen = !a.chosen(job).isEmpty(), missing = a.missing(job);
        float left = r.left + 230, right = (Audio.supported() ? testButton(r, chosen) : r).left - 12;
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(!chosen ? Theme.DIMMER : missing ? Theme.AMBER : Theme.GREEN);
        c.drawCircle(r.left + 212, r.centerY(), 6, Theme.P);
        Theme.text(c, Theme.fit(a.chosenLabel(job), right - left, 17, Theme.bold), left, r.top + 22, 17,
            chosen ? 0xffffffff : Theme.SUB, Theme.bold, Theme.LEFT);
        if (testing == job && Audio.INPUT[job] && testNote.isEmpty()) {
            meter(c, left, r.top + 32, right, r.top + 42);
        } else {
            String info = (Audio.INPUT[job] ? "in" : "out") + "  ·  " + (testing == job ? testNote
                : !chosen ? "wherever Android sends it" : missing ? "not connected: Android's default until it is" : "connected");
            Theme.text(c, Theme.fit(info, right - left, 12, Theme.sans), left, r.top + 40, 12,
                testing == job ? Theme.GOLD : missing ? Theme.AMBER : Theme.DIM, Theme.sans, Theme.LEFT);
        }
        if (!Audio.supported()) return;
        chip(c, testButton(r, chosen), testing == job ? "Stop" : "Test", testing == job);
        chip(c, chooseButton(r, chosen), chosen ? "Change" : "Choose", false);
        if (chosen) chip(c, defaultButton(r), "Default", false);
    }

    /** The input test's level: green to -12 dBFS, gold to -3, red above; red at the end once it clipped. */
    private void meter(Canvas c, float l, float t, float r, float b) {
        float frac = Math.max(0, Math.min(1, (level + 60) / 60));
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(Theme.accent(0x38));
        c.drawRoundRect(new RectF(l, t, r - 70, b), 3, 3, Theme.P);
        Theme.P.setColor(level > -3 ? Theme.BAD : level > -12 ? Theme.GOLD : Theme.GREEN);
        c.drawRoundRect(new RectF(l, t, l + (r - 70 - l) * frac, b), 3, 3, Theme.P);
        Theme.text(c, level <= -119 ? "silent" : String.format(java.util.Locale.US, "%.0f dB", level), r, b, 12,
            clipped ? Theme.BAD : Theme.SUB, Theme.sans, Theme.RIGHT);
    }

    private RectF cell(int i) {
        float w = (Wide.rx(1216) - LEFT - 10) / 2;
        float x = LEFT + (i % 2) * (w + 10), y = GRID_T + (i / 2) * GRID_ROW;
        return new RectF(x, y, x + w, y + GRID_ROW - 8);
    }

    private void chooseView(Canvas c) {
        Audio a = Audio.get(act);
        Theme.text(c, Audio.JOB_NAME[choosing] + ": choose its " + (Audio.INPUT[choosing] ? "input" : "output"),
            LEFT, 241, 20, 0xffffffff, Theme.bold, Theme.LEFT);
        chip(c, backButton(), "Back", false);
        List<Audio.Dev> list = a.devices(choosing);
        String cur = a.chosen(choosing);
        for (int i = 0; i <= list.size() && i < 10; i++) {
            RectF r = cell(i);
            String key = i == 0 ? "" : list.get(i - 1).key;
            boolean on = key.equals(cur);
            Theme.button(c, r, on, 10);
            Theme.text(c, Theme.fit(i == 0 ? "Android's default" : list.get(i - 1).label, r.width() - 130, 18, Theme.bold),
                r.left + 16, r.top + 24, 18, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, i == 0 ? "Wherever Android sends it, as without this app" : "Connected now", r.left + 16, r.top + 42, 12,
                on ? 0xffffffff : Theme.DIM, Theme.sans, Theme.LEFT);
            Theme.text(c, on ? "Chosen ✓" : "Choose", r.right - 16, r.centerY() + 7, 17, on ? 0xffffffff : Theme.BLUE_HI, Theme.bold, Theme.RIGHT);
        }
        if (list.isEmpty()) {
            Theme.text(c, "Nothing else is connected. Plug in the USB interface or connect the headset, and it shows here.",
                LEFT, 590, 14, Theme.DIM, Theme.sans, Theme.LEFT);
        }
    }

    // ---- touch ----

    void touch(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) return;
        float x = e.getX(), y = e.getY();
        Audio a = Audio.get(act);
        if (choosing >= 0) {
            if (backButton().contains(x, y)) { choosing = -1; return; }
            List<Audio.Dev> list = a.devices(choosing);
            for (int i = 0; i <= list.size() && i < 10; i++) {
                if (!cell(i).contains(x, y)) continue;
                a.choose(choosing, i == 0 ? "" : list.get(i - 1).key);
                choosing = -1;
                return;
            }
            return;
        }
        if (!Audio.supported()) return;
        for (int job = 0; job < Audio.JOBS; job++) {
            RectF r = row(job);
            boolean chosen = !a.chosen(job).isEmpty();
            if (testButton(r, chosen).contains(x, y)) {
                boolean again = testing != job;
                stopTest();
                if (again) startTest(job);
            } else if (chooseButton(r, chosen).contains(x, y)) {
                stopTest();
                choosing = job;
            } else if (chosen && defaultButton(r).contains(x, y)) {
                a.choose(job, "");
            }
        }
    }

    // ---- the test ----

    private void startTest(final int job) {
        testing = job;
        testUntil = System.currentTimeMillis() + (Audio.INPUT[job] ? TEST_MS : 2500);
        final Audio a = Audio.get(act);
        if (!Audio.INPUT[job]) {
            testNote = "playing a few notes";
            a.play(job, new int[][] {{523, 180}, {0, 40}, {659, 180}, {0, 40}, {784, 180}, {0, 40}, {1047, 360}});
            return;
        }
        level = -120;
        clipped = false;
        testNote = "";
        meter = new Thread(new Runnable() {
            public void run() { listen(a, job); }
        }, "sound-test");
        meter.start();
    }

    /** Reads the input for the test, keeping its peak level (falling back slowly, as a meter does). */
    private void listen(Audio a, int job) {
        AudioRecord r = null;
        try {
            a.startInput(job);
            if (a.headsetInput(job)) Thread.sleep(800);           // the headset's call link coming up
            int rate = 48000;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            r = new AudioRecord(job == Audio.TALK_IN ? MediaRecorder.AudioSource.MIC : MediaRecorder.AudioSource.CAMCORDER,
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 5));
            a.apply(r, job);
            r.startRecording();
            short[] buf = new short[rate / 20];
            while (testing == job && System.currentTimeMillis() < testUntil) {
                int n = r.read(buf, 0, buf.length);
                if (n <= 0) continue;
                int peak = 0;
                for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs((int) buf[i]));
                float db = peak == 0 ? -120 : (float) (20 * Math.log10(peak / 32768.0));
                if (peak >= 32700) clipped = true;
                level = Math.max(db, level - 1.5f);
            }
        } catch (Throwable t) {
            testNote = "could not open it: " + t.getMessage();
            level = -120;
            testUntil = System.currentTimeMillis() + 4000;
        } finally {
            if (r != null) {
                try { r.stop(); } catch (Throwable t) { /* not started */ }
                r.release();
            }
            a.endInput();
        }
    }

    private void stopTest() {
        testing = -1;
        Thread m = meter;
        meter = null;
        if (m != null) {
            try { m.join(500); } catch (InterruptedException e) { /* on */ }
        }
    }
}

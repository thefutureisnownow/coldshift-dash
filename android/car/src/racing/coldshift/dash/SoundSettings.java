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
    private static final float LEFT = 36, RIGHT = 1244;
    private static final float GRID_T = 230, GRID_ROW = 76;
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

    /** Six rows under the tabs; on an Android that cannot choose devices, a line above them says so. */
    private static RectF row(int job) {
        float y0 = Audio.supported() ? 164 : 200, step = Audio.supported() ? 76 : 70;
        return new RectF(LEFT, y0 + job * step, Wide.rx(RIGHT), y0 + job * step + step - 8);
    }

    private static RectF button(RectF row, int fromRight, float w) {
        float r = row.right - 8 - fromRight;
        return new RectF(r - w, row.top + 8, r, row.bottom - 8);
    }

    // Default shows only once a device is chosen; Choose and Test close up to the right edge without it
    private static RectF defaultButton(RectF row) { return button(row, 0, 114); }
    private static RectF chooseButton(RectF row, boolean chosen) { return button(row, chosen ? 122 : 0, 120); }
    private static RectF testButton(RectF row, boolean chosen) { return button(row, chosen ? 250 : 128, 96); }
    private static RectF backButton() { return new RectF(Wide.rx(RIGHT) - 110, 162, Wide.rx(RIGHT), 214); }

    private static void chip(Canvas c, RectF r, String label, boolean on) { Theme.chip(c, r.left, r.top, r.right, r.bottom, label, on, 0); }

    // ---- drawing ----

    void draw(Canvas c) {
        if (testing >= 0 && System.currentTimeMillis() > testUntil) stopTest();
        if (choosing >= 0) { chooseView(c); return; }
        Audio a = Audio.get(act);
        if (!Audio.supported()) {
            String strip = "Android " + android.os.Build.VERSION.RELEASE + " sends every sound where it chooses: picking a device needs Android 6.";
            Theme.text(c, Theme.fit(strip, Wide.rx(RIGHT) - LEFT, 18, Theme.sans), LEFT, 186, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        }
        for (int job = 0; job < Audio.JOBS; job++) jobRow(c, a, job);
    }

    private void jobRow(Canvas c, Audio a, int job) {
        RectF r = row(job);
        Theme.button(c, r, false, 10);
        float mid = r.centerY();
        Theme.text(c, Audio.JOB_NAME[job], r.left + 16, mid - 4, 20, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, Theme.fit(Audio.JOB_ABOUT[job], 270, 18, Theme.sans), r.left + 16, mid + 20, 18, Theme.DIM, Theme.sans, Theme.LEFT);
        boolean chosen = !a.chosen(job).isEmpty(), missing = a.missing(job);
        float left = r.left + 326, right = Audio.supported() ? testButton(r, chosen).left - 12 : r.right - 16;
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        Theme.P.setColor(!chosen ? Theme.DIMMER : missing ? Theme.AMBER : Theme.GREEN);
        c.drawCircle(r.left + 304, r.centerY(), 7, Theme.P);
        Theme.text(c, Theme.fit(a.chosenLabel(job), right - left, 20, Theme.bold), left, mid - 4, 20,
            chosen ? 0xffffffff : Theme.SUB, Theme.bold, Theme.LEFT);
        if (testing == job && Audio.INPUT[job] && testNote.isEmpty()) {
            meter(c, left, mid + 8, right, mid + 20);
        } else {
            String info = (Audio.INPUT[job] ? "in" : "out") + "  ·  " + (testing == job ? testNote
                : !chosen ? "wherever Android sends it" : missing ? "not connected: Android's default until it is" : "connected");
            Theme.text(c, Theme.fit(info, right - left, 18, Theme.sans), left, mid + 20, 18,
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
        c.drawRoundRect(new RectF(l, t, r - 84, b), 3, 3, Theme.P);
        Theme.P.setColor(level > -3 ? Theme.BAD : level > -12 ? Theme.GOLD : Theme.GREEN);
        c.drawRoundRect(new RectF(l, t, l + (r - 84 - l) * frac, b), 3, 3, Theme.P);
        Theme.text(c, level <= -119 ? "silent" : String.format(java.util.Locale.US, "%.0f dB", level), r, b, 18,
            clipped ? Theme.BAD : Theme.SUB, Theme.sans, Theme.RIGHT);
    }

    private RectF cell(int i) {
        float w = (Wide.rx(RIGHT) - LEFT - 10) / 2;
        float x = LEFT + (i % 2) * (w + 10), y = GRID_T + (i / 2) * GRID_ROW;
        return new RectF(x, y, x + w, y + GRID_ROW - 8);
    }

    private void chooseView(Canvas c) {
        Audio a = Audio.get(act);
        Theme.text(c, Audio.JOB_NAME[choosing] + ": choose its " + (Audio.INPUT[choosing] ? "input" : "output"),
            LEFT, 196, 22, 0xffffffff, Theme.bold, Theme.LEFT);
        chip(c, backButton(), "Back", false);
        List<Audio.Dev> list = a.devices(choosing);
        String cur = a.chosen(choosing);
        for (int i = 0; i <= list.size() && i < 10; i++) {
            RectF r = cell(i);
            String key = i == 0 ? "" : list.get(i - 1).key;
            boolean on = key.equals(cur);
            Theme.button(c, r, on, 10);
            Theme.text(c, Theme.fit(i == 0 ? "Android's default" : list.get(i - 1).label, r.width() - 150, 20, Theme.bold),
                r.left + 16, r.top + 29, 20, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, i == 0 ? "Wherever Android sends it" : "Connected now", r.left + 16, r.top + 53, 18,
                on ? 0xffffffff : Theme.DIM, Theme.sans, Theme.LEFT);
            Theme.text(c, on ? "Chosen ✓" : "Choose", r.right - 16, r.centerY() + 7, 20, on ? 0xffffffff : Theme.BLUE_HI, Theme.bold, Theme.RIGHT);
        }
        if (list.isEmpty()) {
            Theme.text(c, "Nothing else is connected. Plug in the USB interface or connect the headset, and it shows here.",
                LEFT, 340, 18, Theme.DIM, Theme.sans, Theme.LEFT);
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

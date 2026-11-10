package racing.coldshift.dash;

import android.content.Context;
import android.location.Location;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The sound recorder: engine and exhaust through the input chosen for it (Audio.RECORD_IN, a USB interface
 * best), as 16-bit WAV with the engine's readings beside it. Sensors → Sound (SoundPage) is its face.
 *
 * - **Listening** runs the input for the level meters without keeping anything; **recording** also writes
 *   `Dashcam/Sound/sound_<time>.wav`, split every SPLIT_S seconds. Either takes the microphone from the
 *   dashcam, whose clips go on without sound meanwhile (Dashcam.lendMic).
 * - **As unprocessed as the phone allows:** the UNPROCESSED source where Android says the phone has it (7 and
 *   later), else VOICE_RECOGNITION, which skips most of the gain control and noise suppression. 48 kHz, stereo when
 *   the input gives two channels.
 * - **Survives a power cut:** the header's sizes are rewritten every couple of seconds, so a cut leaves a file that
 *   plays to within a few seconds of the end.
 * - **The readings:** `sound_<time>.csv` beside it, a row every 100 ms of the sound
 *   (`ms,rpm,kmh,throttle,load,boost_kpa,coolant,gps_kmh,lat,lon`); ms counts samples, not the clock, so the
 *   rows stay on the sound. Its first line is a comment with the wall-clock time of the first sample, its second
 *   what is on each channel ("# channels: left = Engine; right = Exhaust", named on the Sound page).
 */
final class Sound implements Gps.Listener {
    static final int SPLIT_S = 600;
    private static final long MIN_FREE = 150L * 1024 * 1024;

    private static Sound inst;
    private final Context ctx;
    private final SimpleDateFormat nameFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    // ---- read by the page ----
    volatile boolean listening, recording;
    volatile String status = "", input = "", format = "";
    volatile int channels;
    volatile long recStartReal, recBytes;
    final float[] peak = {-120, -120}, rms = {-120, -120};      // dBFS, the peak falling back slowly
    final long[] clipAt = {0, 0};
    volatile File file;

    private Thread thread;
    private volatile boolean recordWanted, listenWanted;
    private volatile Location fix;
    private volatile long fixAt;

    static synchronized Sound get(Context c) {
        if (inst == null) inst = new Sound(c.getApplicationContext());
        return inst;
    }

    private Sound(Context c) { ctx = c; }

    // ---- control (on the UI thread) ----

    /** The input runs for the meters (the page is open, or recording). */
    void listen(boolean on) {
        listenWanted = on;
        if (on) start();
        else if (!recording && !recordWanted) stopAll();
    }

    boolean recordWanted() { return recordWanted; }

    void record(boolean on) {
        if (on == recordWanted) return;
        recordWanted = on;
        if (on) {
            Gps.get(ctx).add(this);
            start();
        } else {
            Gps.get(ctx).remove(this);
            if (!listenWanted) stopAll();
        }
    }

    private void start() {
        if (thread != null) return;
        listening = true;
        Dashcam.get(ctx).lendMic(true);
        thread = new Thread(new Runnable() {
            public void run() { capture(); }
        }, "sound");
        thread.start();
    }

    private void stopAll() {
        listening = false;
        recordWanted = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(1500); } catch (InterruptedException e) { /* on */ }
        }
        Dashcam.get(ctx).lendMic(false);
    }

    public void onFix(Location l) {
        fix = l;
        fixAt = SystemClock.elapsedRealtime();
    }

    File dir() {
        File d = Dashcam.get(ctx).dir();
        if (d == null) return null;
        File out = new File(d, "Sound");
        out.mkdirs();
        return out.isDirectory() ? out : null;
    }

    /** The recordings, newest first. */
    List<File> recordings() {
        List<File> out = new ArrayList<File>();
        File d = dir();
        File[] fs = d == null ? null : d.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".wav")) out.add(f);
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }

    // ---- the capture thread ----

    private void capture() {
        Audio a = Audio.get(ctx);
        AudioRecord r = null;
        Writer w = null;
        try {
            a.startInput(Audio.RECORD_IN);
            if (Dashcam.get(ctx).recording) Thread.sleep(700);     // the clip under way lets go of the microphone first
            r = open(a);
            if (r == null) { status = "The input would not open"; return; }
            r.startRecording();
            int rate = r.getSampleRate();
            channels = r.getChannelCount();
            input = routed(r, a);
            status = "";
            short[] buf = new short[rate / 20 * channels];          // 50 ms
            while (thread == Thread.currentThread() || recording) {
                boolean want = recordWanted && thread == Thread.currentThread();
                if (want && w == null) {
                    w = open(rate, channels);
                    if (w == null) { recordWanted = false; continue; }
                    recording = true;
                    recStartReal = SystemClock.elapsedRealtime();
                    recBytes = 0;
                }
                if (!want && w != null) {
                    w.close();
                    w = null;
                    recording = false;
                    if (!listening) break;
                    continue;
                }
                int n = r.read(buf, 0, buf.length);
                if (n <= 0) continue;
                levels(buf, n);
                if (w == null) continue;
                w.write(buf, n);
                recBytes = w.bytes;
                if (w.frames >= (long) SPLIT_S * rate) {             // the next file goes on from here
                    w.close();
                    w = open(rate, channels);
                    if (w == null) { recordWanted = false; recording = false; }
                } else if (w.free() < MIN_FREE) {
                    status = "Storage full: recording stopped";
                    recordWanted = false;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("Sound", "capture", t);
            status = "Stopped: " + t.getMessage();
        } finally {
            if (w != null) w.close();
            recording = false;
            listening = false;
            if (r != null) {
                try { r.stop(); } catch (Throwable t) { /* not started */ }
                r.release();
            }
            a.endInput();
            Arrays.fill(peak, -120);
            Arrays.fill(rms, -120);
        }
    }

    /** The recorder: the least processed source, 48 kHz (else 44.1), stereo when the input has it. */
    private AudioRecord open(Audio a) {
        boolean raw = false;
        if (Api.SDK >= 24) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            raw = "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        }
        int src = raw ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;
        int[] rates = {48000, 44100};
        int[] masks = {AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO};
        for (int mask : masks) {
            for (int rate : rates) {
                int min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                AudioRecord r = null;
                try {
                    r = new AudioRecord(src, rate, mask, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, rate));
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        a.apply(r, Audio.RECORD_IN);
                        format = rate / 1000 + (rate % 1000 == 0 ? "" : "." + rate % 1000 / 100) + " kHz "
                            + (mask == AudioFormat.CHANNEL_IN_STEREO ? "stereo" : "mono") + "  ·  16-bit  ·  "
                            + (raw ? "unprocessed" : "little processing");
                        return r;
                    }
                } catch (Throwable t) { /* the next way */ }
                if (r != null) r.release();
            }
        }
        return null;
    }

    /** What the recorder is actually using, as Android reports it once started. */
    private static String routed(AudioRecord r, Audio a) {
        if (Api.SDK >= 24) {
            try {
                AudioDeviceInfo d = r.getRoutedDevice();
                if (d != null) {
                    String name = String.valueOf(d.getProductName()).trim();
                    return d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "The phone's microphone"
                        : d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET ? "USB: " + name
                        : d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET ? "Headphone jack: " + name : name;
                }
            } catch (Throwable t) { /* not reported */ }
        }
        return Audio.supported() ? a.chosenLabel(Audio.RECORD_IN) : "The phone's microphone";
    }

    private void levels(short[] buf, int n) {
        int ch = Math.max(1, channels);
        for (int c = 0; c < Math.min(2, ch); c++) {
            int pk = 0;
            double sum = 0;
            int count = 0;
            for (int i = c; i < n; i += ch) {
                int v = Math.abs((int) buf[i]);
                if (v > pk) pk = v;
                sum += (double) buf[i] * buf[i];
                count++;
            }
            float p = pk == 0 ? -120 : (float) (20 * Math.log10(pk / 32768.0));
            float q = sum == 0 ? -120 : (float) (10 * Math.log10(sum / Math.max(1, count) / (32768.0 * 32768.0)));
            if (pk >= 32700) clipAt[c] = SystemClock.elapsedRealtime();
            peak[c] = Math.max(p, peak[c] - 1.2f);          // about 24 dB a second back down
            rms[c] = q;
        }
    }

    /** What each channel is, as the CSV's second line gives it: "left = Engine; right = Exhaust" (names kept as typed). */
    String channelNames(int ch) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ch; i++) {
            String n = Prefs.soundChannel(ctx, i).replace(';', ',').replace('\n', ' ');
            if (b.length() > 0) b.append("; ");
            b.append(ch == 1 ? "mono" : i == 0 ? "left" : i == 1 ? "right" : "channel " + (i + 1)).append(" = ").append(n);
        }
        return b.toString();
    }

    private Writer open(int rate, int ch) {
        File d = dir();
        if (d == null) { status = "No storage for the recording"; return null; }
        String base = "sound_" + nameFmt.format(new Date());
        try {
            Writer w = new Writer(new File(d, base + ".wav"), new File(d, base + ".csv"), rate, ch);
            file = w.wav;
            return w;
        } catch (IOException e) {
            status = "Could not start the file: " + e.getMessage();
            return null;
        }
    }

    // ---- the files ----

    /** One WAV and its CSV. */
    private final class Writer {
        final File wav;
        private final RandomAccessFile raf;
        private final FileWriter csv;
        private final int rate, ch;
        long frames, bytes;
        private long nextRow, nextPatch;
        private byte[] out = new byte[0];

        Writer(File wav, File csvFile, int rate, int ch) throws IOException {
            this.wav = wav;
            this.rate = rate;
            this.ch = ch;
            raf = new RandomAccessFile(wav, "rw");
            raf.setLength(0);
            raf.write(header(0));
            csv = new FileWriter(csvFile);
            csv.write("# started " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date())
                + ", " + rate + " Hz, " + ch + " channel" + (ch == 1 ? "" : "s") + "\n");
            csv.write("# channels: " + channelNames(ch) + "\n");
            csv.write("ms,rpm,kmh,throttle,load,boost_kpa,coolant,gps_kmh,lat,lon\n");
        }

        void write(short[] buf, int n) throws IOException {
            if (out.length < n * 2) out = new byte[n * 2];
            for (int i = 0; i < n; i++) {                       // WAV is little-endian
                out[2 * i] = (byte) buf[i];
                out[2 * i + 1] = (byte) (buf[i] >> 8);
            }
            raf.write(out, 0, n * 2);
            bytes += n * 2;
            frames += n / ch;
            long ms = frames * 1000 / rate;
            while (nextRow <= ms) {
                csv.write(row(nextRow));
                nextRow += 100;
            }
            if (ms >= nextPatch) {
                patch();
                csv.flush();
                nextPatch = ms + 2000;
            }
        }

        private String row(long ms) {
            Obd o = Obd.get(ctx);
            Location l = fix;
            boolean fresh = l != null && SystemClock.elapsedRealtime() - fixAt < 3000;
            float boost = o.value(Obd.BOOST);
            return ms + "," + num(o.value(Obd.RPM), "%.0f") + "," + num(o.value(Obd.SPEED), "%.0f") + ","
                + num(o.value(Obd.THROTTLE), "%.0f") + "," + num(o.value(Obd.LOAD), "%.0f") + ","
                + num(Float.isNaN(boost) ? boost : boost * 100, "%.0f") + "," + num(o.value(Obd.COOLANT), "%.0f") + ","
                + (fresh ? String.format(Locale.US, "%.1f,%.6f,%.6f", l.getSpeed() * 3.6f, l.getLatitude(), l.getLongitude()) : ",,")
                + "\n";
        }

        private String num(float v, String f) { return Float.isNaN(v) ? "" : String.format(Locale.US, f, v); }

        long free() { return wav.getParentFile().getUsableSpace(); }

        /** The sizes in the header, as they stand. */
        private void patch() throws IOException {
            long at = raf.getFilePointer();
            raf.seek(0);
            raf.write(header(bytes));
            raf.seek(at);
        }

        void close() {
            try {
                patch();
                raf.close();
            } catch (IOException e) { /* as it is */ }
            try { csv.close(); } catch (IOException e) { /* as it is */ }
        }

        private byte[] header(long data) {
            byte[] h = new byte[44];
            put(h, 0, "RIFF");
            le(h, 4, 36 + data, 4);
            put(h, 8, "WAVEfmt ");
            le(h, 16, 16, 4);
            le(h, 20, 1, 2);                                    // PCM
            le(h, 22, ch, 2);
            le(h, 24, rate, 4);
            le(h, 28, (long) rate * ch * 2, 4);
            le(h, 32, ch * 2, 2);
            le(h, 34, 16, 2);
            put(h, 36, "data");
            le(h, 40, data, 4);
            return h;
        }

        private void put(byte[] h, int at, String s) { for (int i = 0; i < s.length(); i++) h[at + i] = (byte) s.charAt(i); }

        private void le(byte[] h, int at, long v, int n) { for (int i = 0; i < n; i++) h[at + i] = (byte) (v >> (8 * i)); }
    }
}

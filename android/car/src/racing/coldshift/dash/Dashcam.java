package racing.coldshift.dash;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loop recorder. Everything that touches the camera runs on one background thread; the UI
 * only reads the volatile fields below. MediaRecorder cannot chain files, so there is a gap
 * of about a second between segments.
 */
final class Dashcam implements Runnable, LocationListener, Camera.PreviewCallback {
    private static Dashcam inst;

    static synchronized Dashcam get(Context c) {
        if (inst == null) inst = new Dashcam(c.getApplicationContext());
        return inst;
    }

    // ---- state read by the UI ----
    volatile boolean recording;
    volatile String status = "Stopped";
    volatile long segStartReal, segLenMs;
    volatile String clipName = "";
    volatile long powerLostAt;
    volatile boolean plugged = true;
    volatile int battery = -1;
    volatile boolean lockPending;
    volatile Location lastLoc;
    volatile long lastLocAt;
    volatile double tripMeters;
    volatile float tripMaxMs;
    volatile long tripStartReal;
    volatile boolean marking;
    volatile long markStartReal;
    volatile int markId;
    volatile String markNote = "";
    volatile Bitmap preview;
    volatile long previewAt;
    volatile String previewState = "";
    volatile View previewTarget;

    private final Context ctx;
    private final HandlerThread thread;
    private final Handler h;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Camera cam;
    // What the camera draws its preview into. One for the app's whole life: made detached from any GL
    // context, so the Dashcam page can show it in a TextureView (the live view while recording) and let
    // go of it again, across clips and across the camera closing and reopening.
    private volatile SurfaceTexture st;
    private volatile boolean stShowable;
    private MediaRecorder rec;
    private File curFile;
    private FileWriter srt;
    private final GyroLog gyro;
    private int cue;
    private boolean userStopped, stopping, locationOn, blocked, prevPlugged = true;
    private long nextAutoTry;
    private int curSeq;
    private boolean previewWanted, previewOnly, previewPending;
    private int pw, ph, pstep;
    private int[] ppix;
    private Bitmap[] pbm = new Bitmap[2];
    private int pflip;
    private long lastConv;
    private Mark mark;

    private static class Span { int seq, inMs, outMs; }
    private static class Mark {
        int id;
        String base, label;
        long startWall, endWall, startReal;
        List<Span> spans = new ArrayList<Span>();
        Location a, b, prev;
        double dist;
        float max;
        FileWriter gpx;
        File gpxFile;
        boolean clockFromGps;
        int points;
    }
    private int retries;
    /** Two-channel sound failed to start since recording began: the clips go on in mono. */
    private boolean monoOnly;
    private long gpsOffset;
    private Location prevTrip;
    private FileWriter csv;
    private long tripStartWall;
    private final SimpleDateFormat nameFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private final SimpleDateFormat srtFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    // ---- what the camera offers, and the test drive that tries it ----

    /** How long each setting is filmed for in a test drive. */
    private static final int TEST_HOLD_MS = 20000;
    /** The camera's choices, from the last time it was open (CameraOptions). */
    volatile List<CameraOptions.Opt> cameraOptions = new ArrayList<CameraOptions.Opt>();
    /** Settings the camera refused when last opened, or "". */
    volatile String cameraRejected = "";
    volatile boolean testing;
    private boolean optionsDumped;

    private static final class Test {
        List<CameraOptions.Variant> variants;
        int idx, minutes;
        long endAt;
        File file;
        JSONArray clips = new JSONArray();
        long startWall;

        /** The saved settings between every change, so each is seen against the same road just before it. */
        CameraOptions.Variant current() {
            return idx % 2 == 0 ? variants.get(0) : variants.get(1 + (idx / 2) % (variants.size() - 1));
        }
    }
    private Test test;

    private Dashcam(Context c) {
        ctx = c;
        gyro = new GyroLog(c);
        thread = new HandlerThread("dashcam");
        thread.start();
        h = new Handler(thread.getLooper());
        h.postDelayed(this, 1000);
    }

    // ---- public control ----

    void start() { userStopped = false; blocked = false; h.post(new StartJob(this)); }
    /** After the camera permission was granted (Permissions): it may start on its own again. */
    void permitted() {
        h.post(new Runnable() {
            public void run() { blocked = false; }
        });
    }
    void stop() { userStopped = true; h.post(new StopJob(this, "Stopped")); }
    void lock() { h.post(new LockJob(this)); }
    void maybeAutoStart() { /* the 1 Hz tick starts recording when power is present */ }

    private static class StartJob implements Runnable {
        private final Dashcam d;
        StartJob(Dashcam d) { this.d = d; }
        public void run() { d.doStart(); }
    }
    private static class StopJob implements Runnable {
        private final Dashcam d; private final String why;
        StopJob(Dashcam d, String why) { this.d = d; this.why = why; }
        public void run() { d.doStop(why); }
    }
    private static class LockJob implements Runnable {
        private final Dashcam d;
        LockJob(Dashcam d) { this.d = d; }
        public void run() { d.doLock(); }
    }
    private static class RolloverJob implements Runnable {
        private final Dashcam d;
        RolloverJob(Dashcam d) { this.d = d; }
        public void run() { d.rollover(); }
    }
    private final RolloverJob rolloverJob = new RolloverJob(this);

    // ---- time ----

    long wallNow() { return System.currentTimeMillis() + gpsOffset; }

    // ---- storage ----

    File dir() {
        File base = Storage.appDir(ctx);
        if (base == null) return null;
        File out = new File(base, "Dashcam");
        out.mkdirs();
        return out.isDirectory() ? out : null;
    }

    boolean onCard() {
        File d = dir();
        return d != null && Storage.onCard(d.getPath());
    }

    List<Clip> clips() {
        List<Clip> out = new ArrayList<Clip>();
        File d = dir();
        File[] fs = d == null ? null : d.listFiles();
        if (fs != null) for (File f : fs) { Clip c = Clip.parse(f); if (c != null) out.add(c); }
        Collections.sort(out, new BySeq());
        return out;
    }

    static class BySeq implements Comparator<Clip> {
        public int compare(Clip a, Clip b) { return a.seq - b.seq; }
    }

    long freeBytes() {
        File d = dir();
        if (d == null) return 0;
        try { StatFs s = new StatFs(d.getPath()); return s.getAvailableBlocks() * (long) s.getBlockSize(); }
        catch (Throwable t) { return 0; }
    }

    long usedBytes() {
        long n = 0;
        for (Clip c : clips()) n += c.size;
        return n;
    }

    long estimateSegmentBytes() {
        int kbps = DashPrefs.Q_KBPS[DashPrefs.qualityIdx(ctx)] + (DashPrefs.audio(ctx) ? 128 : 0);
        return (long) (kbps * 1000L / 8 * (DashPrefs.segMs(ctx) / 1000) * 1.1);
    }

    /** Makes room for one more segment. Returns false if it cannot (and says why in status). */
    private boolean makeRoom() {
        long est = estimateSegmentBytes();
        long cap = DashPrefs.capBytes(ctx);
        long minFree = cap == 0 ? 1024L * 1024 * 1024 : 400L * 1024 * 1024;
        boolean oldest = DashPrefs.deleteOldest(ctx);
        for (int guard = 0; guard < 500; guard++) {
            List<Clip> list = clips();
            long total = 0;
            for (Clip c : list) total += c.size;
            boolean over = (cap > 0 && total + est > cap) || freeBytes() - est < minFree;
            if (!over) return true;
            if (!oldest) { status = "Storage full: recording stopped"; return false; }
            Clip victim = null;
            for (Clip c : list) if (!c.locked && (curFile == null || !c.file.equals(curFile))) { victim = c; break; }
            if (victim == null) { status = "Storage full of locked clips"; return false; }
            delete(victim);
        }
        return false;
    }

    void delete(Clip c) {
        c.file.delete();
        c.sidecar().delete();
        c.gyroLog().delete();
    }

    void deleteClip(final Clip c) { h.post(new DeleteJob(this, c)); }
    private static class DeleteJob implements Runnable {
        private final Dashcam d; private final Clip c;
        DeleteJob(Dashcam d, Clip c) { this.d = d; this.c = c; }
        public void run() { if (d.curFile == null || !d.curFile.equals(c.file)) d.delete(c); }
    }

    void toggleLock(final Clip c) { h.post(new LockToggle(this, c)); }
    private static class LockToggle implements Runnable {
        private final Dashcam d; private final Clip c;
        LockToggle(Dashcam d, Clip c) { this.d = d; this.c = c; }
        public void run() {
            if (d.curFile != null && d.curFile.equals(c.file)) { d.lockPending = !d.lockPending; return; }
            d.rename(c.file, !c.locked);
        }
    }

    private File rename(File f, boolean lock) {
        String n = f.getName();
        boolean isLocked = n.endsWith("_L.mp4");
        if (lock == isLocked) return f;
        String base = n.substring(0, n.length() - (isLocked ? 6 : 4));
        File nf = new File(f.getParentFile(), base + (lock ? "_L.mp4" : ".mp4"));
        if (f.renameTo(nf)) {
            for (String ext : new String[] {".srt", ".gcsv"}) {
                File old = new File(f.getParentFile(), n.substring(0, n.length() - 4) + ext);
                if (old.exists()) old.renameTo(new File(f.getParentFile(), nf.getName().substring(0, nf.getName().length() - 4) + ext));
            }
            return nf;
        }
        return f;
    }

    // ---- camera ----

    /**
     * The largest size the camera can both preview and record, no bigger than the chosen quality, wide
     * (16:9) first: the G7 does 1920x1080, the Y625 at most 1280x720 (asking it for 1080p fails).
     */
    private static Camera.Size bestSize(Camera.Parameters p, int maxW, int maxH) {
        List<Camera.Size> video = p.getSupportedVideoSizes();
        List<Camera.Size> preview = p.getSupportedPreviewSizes();
        Camera.Size best = null;
        for (Camera.Size s : video != null ? video : preview) {
            if (s.width > maxW || s.height > maxH || !contains(preview, s)) continue;
            boolean wide = s.width * 9 == s.height * 16, bestWide = best != null && best.width * 9 == best.height * 16;
            if (best == null || (wide && !bestWide) || (wide == bestWide && s.width * s.height > best.width * best.height))
                best = s;
        }
        return best != null ? best : preview.get(0);
    }

    private static boolean contains(List<Camera.Size> sizes, Camera.Size s) {
        for (Camera.Size x : sizes) if (x.width == s.width && x.height == s.height) return true;
        return false;
    }

    /**
     * The chosen quality's bit rate for the size the camera records at: 1080p's rate scaled by the picture
     * area when the camera tops out lower (the Y625's 720p at "1080p, 8 Mbps" gets 3.6 Mbps, not 8).
     */
    int kbps(int q) {
        long want = (long) DashPrefs.Q_W[q] * DashPrefs.Q_H[q], got = (long) Math.max(1, pw) * Math.max(1, ph);
        return got >= want ? DashPrefs.Q_KBPS[q] : (int) Math.max(2000, DashPrefs.Q_KBPS[q] * got / want);
    }

    /** The size it records at, once the camera has been opened ("1280x720"). */
    String videoSize() { return pw > 0 ? pw + "x" + ph : ""; }

    private boolean openCamera() {
        if (cam != null) return true;
        try {
            cam = Camera.open(0);
            int q = DashPrefs.qualityIdx(ctx);
            Camera.Parameters p = cam.getParameters();
            Camera.Size s = bestSize(p, DashPrefs.Q_W[q], DashPrefs.Q_H[q]);
            pw = s.width;
            ph = s.height;
            p.setPreviewSize(pw, ph);
            p.setRecordingHint(true);
            if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            cam.setParameters(p);
            applyCameraOptions();
            readCameraOptions();
            if (st == null) st = previewTexture();
            cam.setPreviewTexture(st);
            cam.startPreview();
            return true;
        } catch (Throwable t) {
            android.util.Log.w("Dashcam", "camera failed", t);
            status = "Camera unavailable";
            closeCamera();
            return false;
        }
    }

    /** One at a time, so a setting the camera refuses doesn't take the others with it. */
    private void applyCameraOptions() {
        Map<String, String> want = CameraOptions.saved(ctx);
        if (test != null) want.putAll(test.current().settings);
        StringBuilder refused = new StringBuilder();
        for (Map.Entry<String, String> e : want.entrySet()) {
            try {
                Camera.Parameters p = cam.getParameters();
                p.set(e.getKey(), e.getValue());
                cam.setParameters(p);
            } catch (RuntimeException x) {
                refused.append(refused.length() == 0 ? "" : ", ").append(e.getKey());
            }
        }
        cameraRejected = refused.toString();
    }

    private void readCameraOptions() {
        try {
            String flat = cam.getParameters().flatten();
            cameraOptions = CameraOptions.parse(flat);
            File d = dir();
            if (!optionsDumped && d != null) {         // the camera's whole list, for looking at from the computer
                optionsDumped = true;
                FileWriter w = new FileWriter(new File(d, "camera_options_" + android.os.Build.MODEL.replaceAll("[^A-Za-z0-9]", "_") + ".txt"));
                w.write(flat.replace(";", "\n"));
                w.close();
            }
        } catch (Exception e) { /* the last list stands */ }
    }

    /** For the Camera tab when nothing has opened the camera yet. Leaves it as it was. */
    void loadCameraOptions() {
        h.post(new Runnable() {
            public void run() {
                if (recording || rec != null || scanning || shooting) return;
                boolean wasShut = cam == null;
                if (wasShut && !openCamera()) return;
                readCameraOptions();
                if (wasShut) closeCamera();
            }
        });
    }

    /** Starts a test drive of what's ticked on the Camera tab. */
    void startTest(final int minutes) {
        h.post(new Runnable() {
            public void run() {
                if (recording) doStop("Stopped");
                List<CameraOptions.Variant> v = CameraOptions.variants(ctx, cameraOptions);
                if (v.size() < 2) { status = "Tick something to test on the Camera tab"; return; }
                File d = dir();
                if (d == null) { status = "No storage available"; return; }
                Test t = new Test();
                t.variants = v;
                t.minutes = minutes;
                t.startWall = wallNow();
                t.endAt = SystemClock.elapsedRealtime() + minutes * 60000L;
                File tests = new File(d, "tests");
                tests.mkdirs();
                t.file = new File(tests, "test_" + nameFmt.format(new Date(t.startWall)) + ".json");
                test = t;
                testing = true;
                userStopped = false;
                blocked = false;
                doStart();
                if (!recording) endTest();
            }
        });
    }

    private void endTest() {
        if (test != null) writeTest();
        test = null;
        testing = false;
    }

    private void writeTest() {
        Test t = test;
        try {
            JSONObject o = new JSONObject();
            o.put("model", android.os.Build.MODEL);
            o.put("started", t.startWall);
            o.put("minutes", t.minutes);
            o.put("hold_s", TEST_HOLD_MS / 1000);
            o.put("clips", t.clips);
            FileWriter w = new FileWriter(t.file);
            w.write(o.toString(1));
            w.close();
        } catch (Exception e) { android.util.Log.w("Dashcam", "test log: " + e); }
    }

    /** The next setting. False when the drive is over or the camera won't open. */
    private boolean nextTestClip() {
        if (SystemClock.elapsedRealtime() >= test.endAt) { doStop("Test drive finished"); return false; }
        test.idx++;
        closeCamera();                                  // a stabilisation setting only takes before the preview starts
        if (openCamera()) return true;
        doStop("Camera unavailable");
        return false;
    }

    private void closeCamera() {
        if (cam != null) {
            try { cam.stopPreview(); } catch (Throwable t) { /* ignore */ }
            try { cam.release(); } catch (Throwable t) { /* ignore */ }
            cam = null;
        }
        // st stays: a view may be showing it, and the next open uses it again
    }

    /**
     * The preview's texture, detached so a TextureView can take it. Android 8 can make one that way; before
     * that it is made on a throwaway GL context and detached from it. Where that fails the texture is as it
     * always was (never shown), and there is no live view while recording.
     */
    private SurfaceTexture previewTexture() {
        try {
            SurfaceTexture s = Api.SDK >= 26 ? new SurfaceTexture(false) : detachedByGl();
            stShowable = true;
            return s;
        } catch (Throwable t) {
            android.util.Log.w("Dashcam", "no detached preview texture: no live view while recording", t);
            stShowable = false;
            return new SurfaceTexture(10);
        }
    }

    private static SurfaceTexture detachedByGl() {
        android.opengl.EGLDisplay d = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!android.opengl.EGL14.eglInitialize(d, v, 0, v, 1)) throw new RuntimeException("no EGL");
        android.opengl.EGLConfig[] cfg = new android.opengl.EGLConfig[1];
        int[] n = new int[1];
        android.opengl.EGL14.eglChooseConfig(d, new int[] {android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
            android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT, android.opengl.EGL14.EGL_NONE}, 0, cfg, 0, 1, n, 0);
        if (n[0] < 1) throw new RuntimeException("no EGL config");
        android.opengl.EGLContext ctx = android.opengl.EGL14.eglCreateContext(d, cfg[0], android.opengl.EGL14.EGL_NO_CONTEXT,
            new int[] {android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE}, 0);
        android.opengl.EGLSurface pb = android.opengl.EGL14.eglCreatePbufferSurface(d, cfg[0],
            new int[] {android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE}, 0);
        try {
            if (!android.opengl.EGL14.eglMakeCurrent(d, pb, pb, ctx)) throw new RuntimeException("no GL context");
            int[] tex = new int[1];
            android.opengl.GLES20.glGenTextures(1, tex, 0);
            SurfaceTexture s = new SurfaceTexture(tex[0]);
            s.detachFromGLContext();
            android.opengl.GLES20.glDeleteTextures(1, tex, 0);
            return s;
        } finally {
            // the display is shared with the app's own drawing: only this context and surface go
            android.opengl.EGL14.eglMakeCurrent(d, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE,
                android.opengl.EGL14.EGL_NO_CONTEXT);
            android.opengl.EGL14.eglDestroySurface(d, pb);
            android.opengl.EGL14.eglDestroyContext(d, ctx);
        }
    }

    /** The camera's picture as it records, for a TextureView to show; null when this phone cannot. */
    SurfaceTexture liveTexture() { return recording && cam != null && stShowable ? st : null; }

    /** Whether the picture is upside down for the way the phone is mounted (and the view must turn it). */
    boolean flipped() { return orientationHint() == 180; }

    private int orientationHint() {
        Camera.CameraInfo ci = new Camera.CameraInfo();
        Camera.getCameraInfo(0, ci);
        int rot = DashPrefs.rotation(ctx);
        return (ci.orientation - rot * 90 + 360) % 360;
    }

    // ---- recording ----

    private void doStart() {
        if (recording) return;
        stopping = false;
        retries = 0;
        monoOnly = false;
        if (previewOnly) { closeCamera(); previewOnly = false; }
        if (!openCamera()) return;
        startLocation();
        beginTrip();
        gyro.on();
        recording = true;
        status = "Starting\u2026";
        DashcamService.update(ctx, true);
        startSegment();
    }

    private void startSegment() {
        if (!recording) return;
        File d = dir();
        if (d == null) { doStop("No storage available"); return; }
        if (!makeRoom()) { doStop(status); return; }
        int q = DashPrefs.qualityIdx(ctx);
        int seq = DashPrefs.nextSeq(ctx);
        curSeq = seq;
        curFile = new File(d, String.format(Locale.US, "%06d_%s.mp4", seq, nameFmt.format(new Date(wallNow()))));
        // a USB interface or a wireless mic receiver in stereo keeps its two channels (engine and exhaust), to be
        // mixed when the video is made; the phone's own microphone stays mono
        boolean stereo = withSound() && !monoOnly && Audio.get(ctx).twoChannels(Audio.DASHCAM_IN);
        try {
            cam.unlock();
            rec = new MediaRecorder();
            rec.setCamera(cam);
            if (withSound()) {
                rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
                Audio.get(ctx).apply(rec, Audio.DASHCAM_IN);
            }
            rec.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (withSound()) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                rec.setAudioChannels(stereo ? 2 : 1);
                rec.setAudioEncodingBitRate(stereo ? 192000 : 128000);
                rec.setAudioSamplingRate(44100);
            }
            rec.setVideoSize(pw, ph);                  // the camera's best up to the chosen quality (bestSize)
            rec.setVideoFrameRate(30);
            rec.setVideoEncodingBitRate(kbps(q) * 1000);
            rec.setOrientationHint(orientationHint());
            rec.setOutputFile(curFile.getPath());
            rec.setPreviewDisplay(new Surface(st));
            rec.prepare();
            gyro.begin(new File(d, curFile.getName().replace(".mp4", ".gcsv")), DashPrefs.rotation(ctx), wallNow());
            rec.start();
            gyro.started();
        } catch (Throwable t) {
            android.util.Log.w("Dashcam", "segment failed to start: " + t);
            gyro.abort();
            discardRecorder(true);
            if (stereo && recording) {
                monoOnly = true;                       // this phone or input won't: mono, at once and uncounted
                h.post(new RetryJob(this));
                return;
            }
            if (test != null && recording) {           // this setting won't record: note it and go on to the next
                noteTestClip("(would not record)");
                if (nextTestClip()) h.post(new RetryJob(this));
                return;
            }
            if (++retries <= 3 && recording) {
                status = "Recorder error, retrying\u2026";
                h.postDelayed(new RetryJob(this), 3000);
            } else {
                doStop("Recorder error");
            }
            return;
        }
        retries = 0;
        segStartReal = SystemClock.elapsedRealtime();
        segLenMs = test != null ? TEST_HOLD_MS : DashPrefs.segMs(ctx);
        clipName = curFile.getName();
        status = "Recording";
        if (test != null) {
            noteTestClip(clipName);
            status = "Test: " + test.current().label;
        }
        if (mark != null) {
            Span sp = new Span();
            sp.seq = seq;
            sp.inMs = 0;
            mark.spans.add(sp);
            lockPending = true;
        }
        try {
            srt = new FileWriter(new File(d, clipName.substring(0, clipName.length() - 4) + ".srt"));
        } catch (Throwable t) { srt = null; }
        cue = 0;
        h.postDelayed(rolloverJob, segLenMs);
    }

    private static class RetryJob implements Runnable {
        private final Dashcam d;
        RetryJob(Dashcam d) { this.d = d; }
        public void run() { d.startSegment(); }
    }

    private void discardRecorder(boolean deleteFile) {
        if (rec != null) {
            try { rec.reset(); } catch (Throwable t) { /* ignore */ }
            try { rec.release(); } catch (Throwable t) { /* ignore */ }
            rec = null;
        }
        try { if (cam != null) cam.lock(); } catch (Throwable t) { /* ignore */ }
        if (deleteFile && curFile != null) curFile.delete();
    }

    private void finishSegment() {
        h.removeCallbacks(rolloverJob);
        if (rec == null) return;
        boolean ok = true;
        if (mark != null && !mark.spans.isEmpty())
            mark.spans.get(mark.spans.size() - 1).outMs = (int) (SystemClock.elapsedRealtime() - segStartReal);
        try { rec.stop(); } catch (RuntimeException e) { ok = false; }
        if (ok) gyro.end(); else gyro.abort();
        discardRecorder(!ok);
        if (srt != null) { try { srt.close(); } catch (Throwable t) { /* ignore */ } srt = null; }
        if (ok && curFile != null) {
            if (lockPending) { curFile = rename(curFile, true); lockPending = false; }
        } else if (curFile != null) {
            new File(curFile.getParentFile(), curFile.getName().replace(".mp4", ".srt")).delete();
        }
        clipName = "";
        curFile = null;           // no longer being written: it can be deleted or locked like any other
        exportPending();          // a finished clip: a track mark that ended in it can be cut out now
    }

    private volatile boolean talking;

    private volatile boolean lent;

    /** Whether clips record sound: the setting, unless a voice note (talk()) or the sound recorder (lendMic()) has the microphone. */
    private boolean withSound() { return DashPrefs.audio(ctx) && !talking && !lent; }

    /** The sound recorder takes the microphone, or gives it back: the clip under way ends and the next goes on without sound, or with it again. */
    void lendMic(final boolean on) {
        h.post(new Runnable() {
            public void run() {
                boolean was = withSound();
                lent = on;
                if (rec != null && was != withSound()) rollover();
            }
        });
    }

    /** Whether recording now holds the microphone (a voice note must borrow it: Android 4.4 lets one have it). */
    boolean usesMic() { return recording && withSound(); }

    /**
     * A convoy voice note wants the microphone (Talk), or is done with it: the clip under way ends and the
     * next goes on without sound, or with it again. `then` runs on the UI thread once the microphone is free.
     */
    void talk(final boolean on, final Runnable then) {
        h.post(new Runnable() {
            public void run() {
                boolean was = withSound();
                talking = on;
                if (rec != null && was != withSound()) rollover();
                if (then != null) new Handler(android.os.Looper.getMainLooper()).post(then);
            }
        });
    }

    private void noteTestClip(String clip) {
        try {
            CameraOptions.Variant v = test.current();
            JSONObject o = new JSONObject();
            o.put("clip", clip);
            o.put("label", v.label);
            o.put("settings", new JSONObject(v.settings));
            o.put("refused", cameraRejected);
            test.clips.put(o);
            writeTest();
        } catch (Exception e) { /* the clip is what matters */ }
    }

    private void rollover() {
        if (!recording) return;
        finishSegment();
        if (test != null && !nextTestClip()) return;
        startSegment();
    }

    private void doStop(String why) {
        if (why.startsWith("Storage") || why.startsWith("Recorder") || why.startsWith("Camera")
                || why.startsWith("No storage")) blocked = true;
        if (!recording && rec == null && cam == null) { status = why; return; }
        recording = false;
        finishSegment();
        endTest();
        gyro.off();
        if (mark != null) endMark(false);
        closeCamera();
        endTrip();
        stopLocation();
        lockPending = false;
        status = why;
        powerLostAt = 0;
        DashcamService.update(ctx, false);
        if (previewWanted && openCamera()) { previewOnly = true; attachPreview(); }
    }

    private void doLock() {
        if (!recording) return;
        lockPending = true;
        List<Clip> list = clips();
        for (int i = list.size() - 1; i >= 0; i--) {
            Clip c = list.get(i);
            if (curFile != null && c.file.equals(curFile)) continue;
            if (!c.locked) rename(c.file, true);
            break;
        }
    }

    // ---- 1 Hz tick: power policy, storage guard, subtitles ----

    public void run() {
        try {
            tick();
        } catch (Throwable t) {
            android.util.Log.w("Dashcam", "tick: " + t);
        }
        h.postDelayed(this, 1000);
    }

    private void tick() {
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            plugged = b.getIntExtra("plugged", 0) != 0;
            int level = b.getIntExtra("level", -1), scale = b.getIntExtra("scale", 100);
            battery = level < 0 ? -1 : level * 100 / scale;
        }
        long now = SystemClock.elapsedRealtime();
        if (plugged && !prevPlugged) blocked = false;
        prevPlugged = plugged;
        if (recording) {
            if (plugged) {
                powerLostAt = 0;
            } else {
                if (powerLostAt == 0) powerLostAt = now;
                if (now - powerLostAt >= DashPrefs.graceMs(ctx)) { doStop("Car power lost: stopped cleanly"); return; }
            }
            if (!plugged && battery >= 0 && battery < 8) { doStop("Battery low: stopped cleanly"); return; }
            if (freeBytes() < 150L * 1024 * 1024) { doStop("Storage full: recording stopped"); return; }
            writeCue();
        } else if (plugged && !userStopped && !blocked && !scanning && !shooting && DashPrefs.autoStart(ctx) && (cam == null || previewOnly)
                && now >= nextAutoTry) {
            nextAutoTry = now + 15000;
            doStart();
        }
    }

    private void writeCue() {
        if (srt == null || rec == null) return;
        try {
            Location l = lastLoc;
            boolean fresh = l != null && SystemClock.elapsedRealtime() - lastLocAt < 5000;
            int s = (int) ((SystemClock.elapsedRealtime() - segStartReal) / 1000);
            String t0 = String.format(Locale.US, "%02d:%02d:%02d,000", s / 3600, (s / 60) % 60, s % 60);
            String t1 = String.format(Locale.US, "%02d:%02d:%02d,950", s / 3600, (s / 60) % 60, s % 60);
            String when = srtFmt.format(new Date(wallNow()));
            String where = fresh
                ? String.format(Locale.US, "%.0f km/h  %.5f, %.5f", l.getSpeed() * 3.6f, l.getLatitude(), l.getLongitude())
                : "no GPS fix";
            srt.write((++cue) + "\n" + t0 + " --> " + t1 + "\n" + when + "  " + where + "\n\n");
            srt.flush();
        } catch (Throwable t) { /* subtitles are best effort */ }
    }

    // ---- GPS and trips ----

    private void startLocation() {
        if (locationOn) return;
        main.post(new LocJob(this, true));
        locationOn = true;
    }

    private void stopLocation() {
        if (!locationOn) return;
        main.post(new LocJob(this, false));
        locationOn = false;
    }

    private static class LocJob implements Runnable {
        private final Dashcam d; private final boolean on;
        LocJob(Dashcam d, boolean on) { this.d = d; this.on = on; }
        public void run() {
            LocationManager lm = (LocationManager) d.ctx.getSystemService(Context.LOCATION_SERVICE);
            try {
                if (on) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, d);
                else lm.removeUpdates(d);
            } catch (Throwable t) { /* no provider */ }
        }
    }

    public void onLocationChanged(Location l) {
        lastLoc = l;
        lastLocAt = SystemClock.elapsedRealtime();
        if (l.getTime() > 0) gpsOffset = l.getTime() - System.currentTimeMillis();
        h.post(new LocPoint(this, l));
    }
    public void onStatusChanged(String p, int s, Bundle e) {}
    public void onProviderEnabled(String p) {}
    public void onProviderDisabled(String p) {}

    private static class LocPoint implements Runnable {
        private final Dashcam d; private final Location l;
        LocPoint(Dashcam d, Location l) { this.d = d; this.l = l; }
        public void run() { d.tripPoint(l); }
    }

    private File tripsDir() {
        File d = dir();
        if (d == null) return null;
        File t = new File(d, "trips");
        t.mkdirs();
        return t;
    }

    private void beginTrip() {
        tripMeters = 0;
        tripMaxMs = 0;
        prevTrip = null;
        tripStartWall = wallNow();
        tripStartReal = SystemClock.elapsedRealtime();
        try {
            File t = tripsDir();
            if (t != null) csv = new FileWriter(new File(t, "trip_" + nameFmt.format(new Date(tripStartWall)) + ".csv"));
            if (csv != null) csv.write("time_ms,lat,lon,speed_ms,bearing,alt_m,acc_m" + Obd.csvHeader() + "\n");   // then what the car says (Obd), blank without
        } catch (Throwable t) { csv = null; }
    }

    private void tripPoint(Location l) {
        if (!recording) return;
        try {
            if (csv != null) {
                csv.write(wallNow() + "," + l.getLatitude() + "," + l.getLongitude() + "," + l.getSpeed() + ","
                    + l.getBearing() + "," + l.getAltitude() + "," + l.getAccuracy() + Obd.get(ctx).csvTail() + "\n");
                csv.flush();
            }
        } catch (Throwable t) { /* ignore */ }
        if (l.getSpeed() > tripMaxMs && l.getAccuracy() < 40) tripMaxMs = l.getSpeed();
        if (prevTrip != null && l.getAccuracy() <= 30 && l.getSpeed() >= 1.0f) {
            float d = prevTrip.distanceTo(l);
            if (d < 250) tripMeters += d;
        }
        if (l.getAccuracy() <= 30) prevTrip = l;
        markPoint(l);
    }

    private void endTrip() {
        try { if (csv != null) csv.close(); } catch (Throwable t) { /* ignore */ }
        csv = null;
        long durMs = SystemClock.elapsedRealtime() - tripStartReal;
        if (tripStartWall == 0 || tripMeters < 100) return;
        try {
            JSONArray a = trips();
            JSONObject o = new JSONObject();
            o.put("start", tripStartWall);
            o.put("dur", durMs);
            o.put("dist", tripMeters);
            o.put("max", tripMaxMs);
            a.put(o);
            File f = new File(ctx.getFilesDir(), "trips.json");
            FileWriter w = new FileWriter(f);
            w.write(a.toString());
            w.close();
        } catch (Throwable t) { /* ignore */ }
        tripStartWall = 0;
    }

    JSONArray trips() {
        File f = new File(ctx.getFilesDir(), "trips.json");
        if (!f.exists()) return new JSONArray();
        try {
            FileReader r = new FileReader(f);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();
            return new JSONArray(sb.toString());
        } catch (Throwable t) {
            return new JSONArray();
        }
    }


    // ---- marks: a named stretch of the recording, logged without stopping it ----

    File marksDir() {
        File d = dir();
        if (d == null) return null;
        File m = new File(d, "marks");
        m.mkdirs();
        return m;
    }

    void toggleMark() { h.post(new MarkJob(this)); }

    /**
     * A mark for a track run (TrackPage): opened with `preRollMs` of video before now and the run's label
     * ("Hill climb · lap 3"); returns the mark's id, or 0 when the dashcam is not recording or a mark is
     * already open. The run ends it with endTrackMark.
     */
    int startTrackMark(final String label, final int preRollMs) {
        if (!recording || mark != null) return 0;
        final int[] id = {0};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        h.post(new Runnable() {
            public void run() {
                if (mark == null) {
                    beginMark();
                    if (mark != null) {
                        mark.label = label;
                        Span sp = mark.spans.get(0);
                        sp.inMs = Math.max(0, sp.inMs - preRollMs);
                        writeMarkJson(mark, true);
                        id[0] = mark.id;
                    }
                }
                done.countDown();
            }
        });
        try { done.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { /* give up */ }
        return id[0];
    }

    /**
     * Ends a track run's mark `afterMs` from now (a little video past the line). Its footage is exported
     * once the loop clip it ends in is finished (finishSegment), so the clip can be shared from the desktop.
     */
    void endTrackMark(final int id, int afterMs) {
        h.postDelayed(new Runnable() {
            public void run() {
                if (mark == null || mark.id != id) return;
                String base = mark.base;
                endMark(true);
                pendingExports.add(base);
            }
        }, afterMs);
    }

    private final List<String> pendingExports = new ArrayList<String>();

    /** Exports the next waiting track mark, if the exporter is free (one at a time, at clip boundaries). */
    private void exportPending() {
        if (pendingExports.isEmpty() || Exporter.busy) return;
        File md = marksDir();
        if (md == null) return;
        MarkInfo m = MarkInfo.load(new File(md, pendingExports.remove(0) + ".json"));
        if (m != null && !m.exported()) Exporter.start(this, m);
    }
    private static class MarkJob implements Runnable {
        private final Dashcam d;
        MarkJob(Dashcam d) { this.d = d; }
        public void run() { if (d.mark != null) d.endMark(true); else d.beginMark(); }
    }

    private void beginMark() {
        if (!recording || rec == null) { markNote = "Start recording first"; return; }
        File md = marksDir();
        if (md == null) { markNote = "No storage"; return; }
        Mark m = new Mark();
        m.id = DashPrefs.nextMarkId(ctx);
        m.startWall = wallNow();
        m.startReal = SystemClock.elapsedRealtime();
        m.clockFromGps = gpsOffset != 0;
        Location l = lastLoc;
        if (l != null && SystemClock.elapsedRealtime() - lastLocAt < 5000) m.a = l;
        m.base = String.format(Locale.US, "mark_%04d_%s", m.id, nameFmt.format(new Date(m.startWall)));
        Span sp = new Span();
        sp.seq = curSeq;
        sp.inMs = (int) (SystemClock.elapsedRealtime() - segStartReal);
        m.spans.add(sp);
        try {
            m.gpxFile = new File(md, m.base + ".gpx");
            m.gpx = new FileWriter(m.gpxFile);
            m.gpx.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"coldshift.dash\" "
                + "xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Mark " + m.id + "</name><trkseg>\n");
        } catch (Throwable t) { m.gpx = null; }
        mark = m;
        lockPending = true;
        markId = m.id;
        markStartReal = m.startReal;
        marking = true;
        markNote = "Marking";
        writeMarkJson(m, true);
    }

    private void endMark(boolean closeSpan) {
        Mark m = mark;
        if (m == null) return;
        if (closeSpan && rec != null && !m.spans.isEmpty())
            m.spans.get(m.spans.size() - 1).outMs = (int) (SystemClock.elapsedRealtime() - segStartReal);
        m.endWall = wallNow();
        Location l = lastLoc;
        if (l != null && SystemClock.elapsedRealtime() - lastLocAt < 5000) m.b = l;
        try {
            if (m.gpx != null) { m.gpx.write("</trkseg></trk></gpx>\n"); m.gpx.close(); }
        } catch (Throwable t) { /* ignore */ }
        m.gpx = null;
        if (m.points == 0 && m.gpxFile != null) { m.gpxFile.delete(); m.gpxFile = null; }
        writeMarkJson(m, false);
        mark = null;
        marking = false;
        markNote = "Mark " + m.id + " saved";
    }

    private void markPoint(Location l) {
        Mark m = mark;
        if (m == null) return;
        try {
            if (m.gpx != null && l.getAccuracy() <= 60) {
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                iso.setTimeZone(TimeZone.getTimeZone("UTC"));
                m.gpx.write(String.format(Locale.US,
                    "<trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele><time>%s</time><extensions><speed>%.2f</speed></extensions></trkpt>\n",
                    l.getLatitude(), l.getLongitude(), l.getAltitude(), iso.format(new Date(wallNow())), l.getSpeed()));
                m.gpx.flush();
                m.points++;
            }
        } catch (Throwable t) { /* ignore */ }
        if (m.a == null) m.a = l;
        if (l.getSpeed() > m.max && l.getAccuracy() < 40) m.max = l.getSpeed();
        if (m.prev != null && l.getAccuracy() <= 30 && l.getSpeed() >= 1.0f) {
            float d = m.prev.distanceTo(l);
            if (d < 250) m.dist += d;
        }
        if (l.getAccuracy() <= 30) m.prev = l;
    }

    private void writeMarkJson(Mark m, boolean open) {
        File md = marksDir();
        if (md == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("id", m.id);
            o.put("label", m.label != null ? m.label : "Mark " + m.id);
            o.put("open", open);
            o.put("start", m.startWall);
            o.put("end", open ? 0 : m.endWall);
            o.put("durationMs", open ? 0 : m.endWall - m.startWall);
            o.put("clockFromGps", m.clockFromGps);
            if (m.a != null) o.put("startFix", new JSONObject().put("lat", m.a.getLatitude()).put("lon", m.a.getLongitude()));
            if (m.b != null) o.put("endFix", new JSONObject().put("lat", m.b.getLatitude()).put("lon", m.b.getLongitude()));
            o.put("distanceM", m.dist);
            o.put("maxKmh", m.max * 3.6);
            o.put("gpx", m.gpxFile == null ? "" : m.gpxFile.getName());
            JSONArray sp = new JSONArray();
            for (Span s : m.spans) sp.put(new JSONObject().put("seq", s.seq).put("in", s.inMs).put("out", s.outMs));
            o.put("spans", sp);
            FileWriter w = new FileWriter(new File(md, m.base + ".json"));
            w.write(o.toString(1));
            w.close();
        } catch (Throwable t) { android.util.Log.w("Dashcam", "mark json: " + t); }
    }

    /** Marks on disk, newest first. */
    List<MarkInfo> marks() {
        List<MarkInfo> out = new ArrayList<MarkInfo>();
        File md = marksDir();
        File[] fs = md == null ? null : md.listFiles();
        if (fs != null) for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            MarkInfo mi = MarkInfo.load(f);
            if (mi != null) out.add(mi);
        }
        Collections.sort(out, new MarkInfo.Newest());
        return out;
    }

    void deleteMark(final MarkInfo mi) { h.post(new DeleteMarkJob(mi)); }
    private static class DeleteMarkJob implements Runnable {
        private final MarkInfo mi;
        DeleteMarkJob(MarkInfo mi) { this.mi = mi; }
        public void run() { mi.deleteFiles(); }
    }

    File clipFor(int seq) {
        File d = dir();
        File[] fs = d == null ? null : d.listFiles();
        String prefix = String.format(Locale.US, "%06d_", seq);
        if (fs != null) for (File f : fs) if (f.getName().startsWith(prefix) && f.getName().endsWith(".mp4")) return f;
        return null;
    }


    // ---- live preview: downscaled frames from the camera, for aiming the phone ----

    void setPreviewTarget(View v) { previewTarget = v; }

    // ---- the interior shot: one photo with the front camera, after time to get out of the car ----

    static final int SHOT_DELAY_MS = 10000;
    /** While counting down, when the photo is taken (elapsedRealtime); 0 otherwise. */
    volatile long shotAt;
    volatile boolean shooting;
    volatile String shotStatus = "";
    volatile File lastShot;
    private boolean shotResume;
    private Camera front;
    private SurfaceTexture frontSt;
    private android.media.ToneGenerator tones;

    /** Counts down SHOT_DELAY_MS, beeping for the last three seconds, then takes the photo (recording pauses for it). */
    void interiorShot() {
        h.post(new Runnable() {
            public void run() {
                if (shooting || shotAt != 0) return;
                if (frontId() < 0) { shotStatus = "This phone has no front camera"; return; }
                shotAt = SystemClock.elapsedRealtime() + SHOT_DELAY_MS;
                shotStatus = "";
                for (int s = 3; s >= 1; s--) h.postDelayed(beepJob, SHOT_DELAY_MS - s * 1000);
                h.postDelayed(shootJob, SHOT_DELAY_MS);
            }
        });
    }

    void cancelShot() {
        h.post(new Runnable() {
            public void run() {
                if (shotAt == 0) return;
                h.removeCallbacks(beepJob);
                h.removeCallbacks(shootJob);
                shotAt = 0;
                shotStatus = "Cancelled";
            }
        });
    }

    /** The interior shots, newest first. */
    List<File> interiorShots() {
        List<File> out = new ArrayList<File>();
        File d = dir();
        File[] fs = d == null ? null : new File(d, "Interior").listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".jpg")) out.add(f);
        Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) { return b.getName().compareTo(a.getName()); }
        });
        return out;
    }

    private static int frontId() {
        Camera.CameraInfo ci = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, ci);
            if (ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
        }
        return -1;
    }

    private void beep(int tone, int ms) {
        try {
            if (tones == null) tones = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100);
            tones.startTone(tone, ms);
        } catch (Throwable t) { /* no sound, no matter */ }
    }

    private final Runnable beepJob = new Runnable() {
        public void run() { beep(android.media.ToneGenerator.TONE_PROP_BEEP, 180); }
    };

    private final Runnable shootJob = new Runnable() {
        public void run() { startShot(); }
    };

    private final Runnable takeJob = new Runnable() {
        public void run() {
            try {
                front.takePicture(null, null, new Camera.PictureCallback() {
                    public void onPictureTaken(byte[] jpeg, Camera c) { saveShot(jpeg); }
                });
            } catch (Throwable t) {
                endShot("The front camera did not take the photo");
            }
        }
    };

    private final Runnable shotTimeout = new Runnable() {
        public void run() { endShot("The front camera did not answer"); }
    };

    private void startShot() {
        shotAt = 0;
        shooting = true;
        shotStatus = "Taking the photo…";
        // the front and back cameras cannot both be open on these phones
        shotResume = recording;
        if (recording) doStop("Paused for the interior shot");
        detachPreview();
        closeCamera();
        previewOnly = false;
        int id = frontId();
        try {
            front = Camera.open(id);
            Camera.Parameters p = front.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPictureSizes())
                if (best == null || s.width * s.height > best.width * best.height) best = s;
            p.setPictureSize(best.width, best.height);
            p.setJpegQuality(95);
            Camera.CameraInfo ci = new Camera.CameraInfo();
            Camera.getCameraInfo(id, ci);
            p.setRotation((ci.orientation + DashPrefs.rotation(ctx) * 90) % 360);   // upright, for a front camera
            if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            front.setParameters(p);
            frontSt = new SurfaceTexture(11);
            front.setPreviewTexture(frontSt);
            front.startPreview();
            h.postDelayed(takeJob, 2000);                   // the exposure settles on a dark cabin first
            h.postDelayed(shotTimeout, 10000);
        } catch (Throwable t) {
            android.util.Log.w("Dashcam", "front camera failed", t);
            endShot("The front camera would not open");
        }
    }

    private void saveShot(byte[] jpeg) {
        File d = dir();
        if (d == null) { endShot("No storage for the photo"); return; }
        File f = new File(new File(d, "Interior"), "interior_" + nameFmt.format(new Date(wallNow())) + ".jpg");
        try {
            f.getParentFile().mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(jpeg);
            out.close();
            lastShot = f;
            beep(android.media.ToneGenerator.TONE_PROP_ACK, 400);
            endShot("Saved " + f.getName());
        } catch (Throwable t) {
            endShot("Could not save the photo: " + t.getMessage());
        }
    }

    private void endShot(String why) {
        h.removeCallbacks(takeJob);
        h.removeCallbacks(shotTimeout);
        if (front != null) {
            try { front.stopPreview(); } catch (Throwable t) { /* ignore */ }
            try { front.release(); } catch (Throwable t) { /* ignore */ }
            front = null;
        }
        if (frontSt != null) { try { frontSt.release(); } catch (Throwable t) { /* ignore */ } frontSt = null; }
        if (!shooting) return;
        shooting = false;
        shotStatus = why;
        if (shotResume) {
            userStopped = false;
            doStart();
        } else if (previewWanted) {
            doPreview(true);
        }
        shotResume = false;
    }

    // ---- scanning a QR code with the back camera (joining a convoy) ----

    interface Frames {
        /** A preview frame (NV21, w x h), on the camera's thread; return quickly (copy to keep it). */
        void frame(byte[] nv21, int w, int h);
    }

    private volatile Frames scanSink;
    private boolean scanning, scanResume;

    /** Recording pauses (it resumes in endScan) and preview frames go to `f`. */
    void beginScan(final Frames f) {
        h.post(new Runnable() {
            public void run() {
                scanResume = recording;
                if (recording) doStop("Paused to scan a QR code");
                scanning = true;
                scanSink = f;
                doPreview(true);
            }
        });
    }

    void endScan(final View previewWas) {
        h.post(new Runnable() {
            public void run() {
                scanSink = null;
                doPreview(false);
                previewTarget = previewWas;
                scanning = false;
                if (scanResume) {
                    userStopped = false;
                    doStart();
                }
                scanResume = false;
            }
        });
    }

    /** The newest preview picture, for showing the camera while scanning. */
    Bitmap previewBitmap() { return preview; }
    void setPreview(boolean on) { h.post(new PreviewJob(this, on)); }

    private static class PreviewJob implements Runnable {
        private final Dashcam d; private final boolean on;
        PreviewJob(Dashcam d, boolean on) { this.d = d; this.on = on; }
        public void run() { d.doPreview(on); }
    }

    private void doPreview(boolean on) {
        previewWanted = on;
        if (on) {
            if (cam == null) {
                if (openCamera()) previewOnly = true;
                else { previewState = "Camera unavailable"; return; }
            }
            attachPreview();
        } else {
            detachPreview();
            if (previewOnly && !recording) { closeCamera(); previewOnly = false; }
            previewState = "";
        }
    }

    private void attachPreview() {
        // The camera gives the app no usable frames once the recorder owns it (it sends empty ones).
        if (cam == null || recording) { previewState = recording ? "recording" : previewState; return; }
        try {
            int len = pw * ph * 3 / 2;
            cam.addCallbackBuffer(new byte[len]);
            cam.addCallbackBuffer(new byte[len]);
            cam.setPreviewCallbackWithBuffer(this);
            previewPending = false;
            previewState = recording ? "Live" : "Live";
        } catch (Throwable t) {
            previewPending = true;
            previewState = "Preview starts at the next clip";
        }
    }

    private void detachPreview() {
        previewPending = false;
        try { if (cam != null) cam.setPreviewCallbackWithBuffer(null); } catch (Throwable t) { /* ignore */ }
    }

    public void onPreviewFrame(byte[] data, Camera c) {
        long now = SystemClock.elapsedRealtime();
        Frames f = scanSink;
        if (f != null) {
            try { f.frame(data, pw, ph); } catch (Throwable t) { /* the scanner's problem */ }
        }
        try {
            if (previewWanted && !recording && now - lastConv >= 110) { convert(data); lastConv = now; }
        } catch (Throwable t) {
            previewState = "Preview error";
        }
        try { c.addCallbackBuffer(data); } catch (Throwable t) { /* camera released */ }
    }

    private void convert(byte[] d) {
        int step = Math.max(1, pw / 640);
        int ow = pw / step, oh = ph / step;
        if (ppix == null || ppix.length != ow * oh) {
            ppix = new int[ow * oh];
            pbm[0] = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            pbm[1] = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        }
        boolean flip = orientationHint() == 180;
        int frameSize = pw * ph;
        for (int y = 0; y < oh; y++) {
            int sy = (flip ? oh - 1 - y : y) * step;
            int yRow = sy * pw;
            int uvRow = frameSize + (sy >> 1) * pw;
            for (int x = 0; x < ow; x++) {
                int sx = (flip ? ow - 1 - x : x) * step;
                int yy = (d[yRow + sx] & 0xff) - 16;
                int uv = uvRow + (sx & ~1);
                int v = (d[uv] & 0xff) - 128, u = (d[uv + 1] & 0xff) - 128;
                if (yy < 0) yy = 0;
                int r = (298 * yy + 409 * v + 128) >> 8;
                int g = (298 * yy - 100 * u - 208 * v + 128) >> 8;
                int b = (298 * yy + 516 * u + 128) >> 8;
                r = r < 0 ? 0 : r > 255 ? 255 : r;
                g = g < 0 ? 0 : g > 255 ? 255 : g;
                b = b < 0 ? 0 : b > 255 ? 255 : b;
                ppix[y * ow + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        int next = pbm[0] == preview ? 1 : 0;
        pbm[next].setPixels(ppix, 0, ow, 0, 0, ow, oh);
        preview = pbm[next];
        previewAt = SystemClock.elapsedRealtime();
        View t = previewTarget;
        if (t != null) t.postInvalidate();
    }

    void rememberRotation(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        DashPrefs.setRotation(c, wm.getDefaultDisplay().getRotation());
    }
}

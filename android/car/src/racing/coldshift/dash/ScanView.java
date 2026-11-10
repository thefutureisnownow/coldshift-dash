package racing.coldshift.dash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scanning a QR code with the back camera, over the whole screen: the phone comes out of its cradle and
 * is held up to the code. The dashcam's camera is borrowed (Dashcam.beginScan: recording pauses, and
 * resumes when this closes). Frames are read with ZXing a few times a second, from the middle of the
 * picture; Cancel gives up.
 */
final class ScanView extends View {
    interface Done {
        /** What the QR code says (closing first). */
        void scanned(String text);
    }

    private static final RectF CANCEL = new RectF(1010, 610, 1250, 690);
    private static final long EVERY_MS = 250;

    private final Main act;
    private final Done done;
    private final View previewWas;
    private final boolean wasRecording;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;
    private long lastTry;

    static void open(Main a, Done d) {
        ScanView v = new ScanView(a, d);
        a.root().addView(v, new android.widget.FrameLayout.LayoutParams(-1, -1));
    }

    private ScanView(Main a, Done d) {
        super(a);
        act = a;
        done = d;
        Dashcam dc = Dashcam.get(a);
        previewWas = dc.previewTarget;
        wasRecording = dc.recording;
        dc.setPreviewTarget(this);
        dc.beginScan(new Dashcam.Frames() {
            public void frame(byte[] nv21, int w, int h) { offer(nv21, w, h); }
        });
    }

    /** A frame from the camera's thread: read it if the reader is free and it is time. */
    private void offer(byte[] nv21, final int w, final int h) {
        long now = System.currentTimeMillis();
        if (closed || now - lastTry < EVERY_MS || !busy.compareAndSet(false, true)) return;
        lastTry = now;
        final byte[] copy = nv21.clone();
        reader.execute(new Runnable() {
            public void run() {
                try {
                    final String text = decode(copy, w, h);
                    if (text != null) {
                        post(new Runnable() {
                            public void run() { finish(text); }
                        });
                    }
                } finally {
                    busy.set(false);
                }
            }
        });
    }

    /** The QR code in the middle square of the frame (the luminance plane is all ZXing needs), or null. */
    private static String decode(byte[] nv21, int w, int h) {
        int side = Math.min(w, h) * 9 / 10, left = (w - side) / 2, top = (h - side) / 2;
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(nv21, w, h, left, top, side, side, false);
            Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result r = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
            return r.getText();
        } catch (Exception e) {
            return null;                              // no code in this frame
        }
    }

    private void finish(String text) {
        if (closed) return;
        close();
        done.scanned(text);
    }

    private void close() {
        closed = true;
        reader.shutdownNow();
        Dashcam.get(act).endScan(previewWas);
        act.root().removeView(this);
    }

    @Override protected void onDraw(Canvas c) {
        c.save();
        Screen.begin(c, this);
        c.drawColor(0xff000000);
        Bitmap b = Dashcam.get(act).previewBitmap();
        if (b != null) {
            float k = Math.min(1280f / b.getWidth(), 720f / b.getHeight());
            float w = b.getWidth() * k, h = b.getHeight() * k;
            c.drawBitmap(b, null, new RectF((1280 - w) / 2, (720 - h) / 2, (1280 + w) / 2, (720 + h) / 2), Theme.BMP);
        }
        // the part of the picture that is read
        float side = 720 * 0.9f, l = (1280 - side) / 2, t = (720 - side) / 2;
        Theme.P.setShader(null);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        Theme.P.setColor(0x99000000);
        c.drawRect(0, 0, l, 720, Theme.P);
        c.drawRect(l + side, 0, 1280, 720, Theme.P);
        Theme.P.setStyle(android.graphics.Paint.Style.STROKE);
        Theme.P.setStrokeWidth(4);
        Theme.P.setColor(Theme.GOLD);
        float a = 60;
        float[][] corners = {{l, t, 1, 1}, {l + side, t, -1, 1}, {l, t + side, 1, -1}, {l + side, t + side, -1, -1}};
        for (float[] k : corners) {
            c.drawLine(k[0], k[1], k[0] + a * k[2], k[1], Theme.P);
            c.drawLine(k[0], k[1], k[0], k[1] + a * k[3], Theme.P);
        }
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        Theme.text(c, "Scan to join", 24, 60, 30, 0xffffffff, Theme.bold, Theme.LEFT);
        String[] lines = {"Hold the phone's", "back camera up to", "the convoy's QR code."};
        for (int i = 0; i < lines.length; i++) Theme.text(c, lines[i], 24, 110 + i * 30, 20, Theme.TEXT, Theme.sans, Theme.LEFT);
        if (wasRecording)
            Theme.text(c, "The dashcam is paused while scanning.", 24, 680, 16, Theme.GOLD, Theme.bold, Theme.LEFT);
        Theme.button(c, CANCEL, false, 12);
        Theme.text(c, "Cancel", CANCEL.centerX(), CANCEL.centerY() + 8, 22, Theme.TEXT, Theme.bold, Theme.CENTER);
        c.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        act.ui().poke();
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = Screen.x(this, e.getX()), y = Screen.y(this, e.getY());
            if (CANCEL.contains(x, y)) close();
        }
        return true;
    }
}

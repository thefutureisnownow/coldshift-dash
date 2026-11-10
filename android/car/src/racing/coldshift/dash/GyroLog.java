package racing.coldshift.dash;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * How the phone turned while a clip was filmed: a Gyroflow log (.gcsv) beside each clip, read by name, so a
 * clip can be stabilised afterwards from the phone's motion, bends from the rolling shutter included.
 *
 * Gyroscope at 200 Hz, each row with the latest accelerometer reading (Gyroflow's horizon lock needs it),
 * about 1.5 MB for three minutes. A phone without a real gyroscope writes nothing: the G7 has only a
 * "pseudo" one worked out from its accelerometer and compass, which can't follow a car's bounce.
 *
 * t = 0 is when the recorder's start call returns: it took about 1.1 s to reach its first frame on the A22, and the
 * recorder doesn't say exactly when that was, so Gyroflow's sync does the last of the lining up. Nothing here may
 * stop a recording: a log that can't be written just ends.
 */
final class GyroLog implements SensorEventListener {
    /** Gyroflow's axis setting per screen rotation the camera is set to (DashPrefs.rotation). Found by test for rotation 1; the rest follow from it. */
    private static final String[] ORIENTATION = {"YxZ", "XYZ", "yXZ", "xyZ"};
    private static final int PERIOD_US = 5000;      // 200 Hz: anything faster needs a permission on Android 12 and later

    private final SensorManager sm;
    private final Sensor gyro, accel;
    private HandlerThread thread;
    private BufferedWriter out;
    private File file;
    private long t0, shift;
    private boolean clockKnown;
    private float ax, ay, az;
    private final StringBuilder row = new StringBuilder(64);

    GyroLog(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor g = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gyro = g != null && !g.getName().toLowerCase(Locale.US).contains("pseudo") ? g : null;
        accel = gyro == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    /** The sensors run for the whole of a recording, so the first rows of a clip aren't lost to their waking up. */
    void on() {
        if (gyro == null || thread != null) return;
        thread = new HandlerThread("gyrolog");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        clockKnown = false;
        sm.registerListener(this, gyro, PERIOD_US, h);
        if (accel != null) sm.registerListener(this, accel, PERIOD_US, h);
    }

    void off() {
        end();
        if (thread == null) return;
        sm.unregisterListener(this);
        thread.quit();
        thread = null;
    }

    /** Starts the log beside a clip; call just before the recorder starts. */
    synchronized void begin(File f, int rotation, long wallMs) {
        close();
        if (gyro == null || thread == null) return;
        try {
            BufferedWriter w = new BufferedWriter(new FileWriter(f), 32768);
            w.write("GYROFLOW IMU LOG\nversion,1.3\n");
            w.write("id,coldshift_dash_" + Build.MODEL.replaceAll("[^A-Za-z0-9]", "_") + "\n");
            w.write("orientation," + ORIENTATION[((rotation % 4) + 4) % 4] + "\n");
            w.write("note,start_wall_ms=" + wallMs + "\n");
            w.write("timestamp," + wallMs / 1000 + "\nvendor,coldshift\n");
            if (Build.MODEL.startsWith("SM-A225")) w.write("frame_readout_time,23.1\nframe_readout_direction,0\n");   // measured from a walk
            w.write("tscale,0.000001\ngscale,0.00001\nascale,0.001\n");
            w.write(accel != null ? "t,gx,gy,gz,ax,ay,az\n" : "t,gx,gy,gz\n");
            out = w;
            file = f;
            t0 = Long.MAX_VALUE / 2;                    // nothing counts until started()
        } catch (IOException e) {
            android.util.Log.w("GyroLog", "can't write " + f + ": " + e);
            f.delete();
        }
    }

    /** Call when the recorder's start call returns. */
    synchronized void started() { t0 = SystemClock.elapsedRealtimeNanos(); }

    synchronized void end() {
        close();
        file = null;
    }

    /** The recording failed: its log goes too. */
    synchronized void abort() {
        close();
        if (file != null) file.delete();
        file = null;
    }

    private void close() {
        if (out != null) {
            try { out.close(); } catch (IOException e) { /* what was written stays */ }
            out = null;
        }
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            ax = e.values[0];
            ay = e.values[1];
            az = e.values[2];
        } else {
            write(e.timestamp, e.values);
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) { }

    private synchronized void write(long stamp, float[] v) {
        if (out == null) return;
        if (!clockKnown) {          // sensor times are since boot on nearly every phone; one that differs is corrected once
            long diff = SystemClock.elapsedRealtimeNanos() - stamp;
            shift = Math.abs(diff) > 1000000000L ? diff : 0;
            clockKnown = true;
        }
        long t = (stamp + shift - t0) / 1000;
        if (t < 0) return;
        row.setLength(0);
        row.append(t).append(',').append(Math.round(v[0] * 1e5f)).append(',').append(Math.round(v[1] * 1e5f)).append(',').append(Math.round(v[2] * 1e5f));
        if (accel != null) {        // in thousandths of g: at rest it reads +1 g upward, as Gyroflow expects
            row.append(',').append(Math.round(ax / SensorManager.GRAVITY_EARTH * 1000)).append(',').append(Math.round(ay / SensorManager.GRAVITY_EARTH * 1000))
                .append(',').append(Math.round(az / SensorManager.GRAVITY_EARTH * 1000));
        }
        row.append('\n');
        try {
            out.append(row);
        } catch (IOException e) {
            android.util.Log.w("GyroLog", "stopped: " + e);
            close();
        }
    }
}

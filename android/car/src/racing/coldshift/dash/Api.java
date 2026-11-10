package racing.coldshift.dash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * The calls that differ between Android 4.4 and newer Android, in one place. Both APKs (build.sh) are
 * compiled from the same source against a new SDK; the legacy one targets 4.4, the modern one a current
 * Android, which asks more of an app: foreground services that say what they are for, a notification
 * channel, immutable PendingIntents, and receivers that say whether other apps may send to them. Anything
 * newer than 4.4 is called only behind a version check, so the 4.4 phones never reach it.
 */
final class Api {
    static final int SDK = Build.VERSION.SDK_INT;
    private static final String CHANNEL = "running";

    private Api() {}

    /** True for the modern APK (built for a current Android rather than 4.4). */
    static boolean modern(Context c) { return c.getApplicationInfo().targetSdkVersion >= 23; }

    /** Starts a service that goes into the foreground (Android 8 needs to be told). False when refused. */
    static boolean startForeground(Context c, Intent i) {
        try {
            if (SDK >= 26) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (RuntimeException e) {           // Android 12+: not from the background
            android.util.Log.w("Api", "cannot start " + i.getComponent(), e);
            return false;
        }
    }

    /** A notification for a foreground service, on the app's quiet "running" channel (Android 8+). */
    static Notification.Builder notification(Context c) {
        if (SDK < 26) return new Notification.Builder(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Running in the background",
                NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(c, CHANNEL);
    }

    /** Opens the app, from a notification. */
    static PendingIntent openApp(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, Main.class), SDK >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    /**
     * startForeground with what the service does (ServiceInfo.FOREGROUND_SERVICE_TYPE_*, all of them in the
     * manifest too), which Android 14 requires; ignored before Android 10. False when Android refused: the
     * permission a type needs is not granted, or the app was in the background. The service then stops.
     */
    static boolean foreground(Service s, int id, Notification n, int types) {
        try {
            if (SDK >= 29) s.startForeground(id, n, types);
            else s.startForeground(id, n);
            return true;
        } catch (RuntimeException e) {
            android.util.Log.w("Api", "no foreground for " + s.getClass().getSimpleName(), e);
            s.stopSelf();
            return false;
        }
    }

    /**
     * Registers a receiver. exported: other senders may reach it (adb's shell, through the permission it
     * names); else only this app. Android 14 requires one or the other for anything not from the system.
     */
    static void listen(Context c, BroadcastReceiver r, IntentFilter f, String permission, boolean exported) {
        if (SDK >= 26) c.registerReceiver(r, f, permission, null,
            exported ? Context.RECEIVER_EXPORTED : Context.RECEIVER_NOT_EXPORTED);
        else c.registerReceiver(r, f, permission, null);
    }

    /** Granted, or not asked for on this Android (before 6, everything is granted on install). */
    static boolean granted(Context c, String permission) {
        return SDK < 23 || c.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}

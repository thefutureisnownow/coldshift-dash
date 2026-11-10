package racing.coldshift.dash;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * A foreground service while the app uses the GPS (the trip logger, a convoy, track timing, the map), so
 * a phone that is also someone's own (the app not a kiosk, and switched away from) keeps the app's process
 * and its location updates alive: newer Android and makers' battery savers stop background apps that have
 * none. Its notification says so and opens the app. The dashcam has its own (DashcamService).
 */
public class DriveService extends Service {
    private static boolean on;
    // asked to start and not yet in the foreground: Android kills the app if a service started that way is
    // stopped before it gets there, so a stop in that moment waits and the service ends itself (onStartCommand)
    private static boolean starting;

    /** Called by Gps when its first listener comes and its last goes. */
    static synchronized void update(Context c, boolean want) {
        if (want == on) return;
        Intent i = new Intent(c, DriveService.class);
        if (!want) {
            on = false;
            if (!starting) c.stopService(i);
        } else if (Api.granted(c, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (starting) { on = true; return; }           // still on its way up from before: it stays
            starting = true;
            on = Api.startForeground(c, i);
            if (!on) starting = false;
        }
    }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        Notification n = Api.notification(this)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("coldshift.dash")
            .setContentText("Using GPS: trips, convoy and the map keep working in the background")
            .setContentIntent(Api.openApp(this))
            .setOngoing(true)
            .build();
        boolean up = Api.foreground(this, 9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        synchronized (DriveService.class) {
            starting = false;
            if (!up) on = false;
            if (!on) stopSelf();                           // no longer wanted by the time it came up
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { stopForeground(true); }

    @Override public IBinder onBind(Intent i) { return null; }
}

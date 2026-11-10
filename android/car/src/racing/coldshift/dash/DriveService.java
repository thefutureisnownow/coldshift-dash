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

    /** Called by Gps when its first listener comes and its last goes. */
    static synchronized void update(Context c, boolean want) {
        if (want == on) return;
        Intent i = new Intent(c, DriveService.class);
        if (!want) {
            on = false;
            c.stopService(i);
        } else if (Api.granted(c, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            on = Api.startForeground(c, i);
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
        if (!Api.foreground(this, 9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)) on = false;
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { stopForeground(true); }

    @Override public IBinder onBind(Intent i) { return null; }
}

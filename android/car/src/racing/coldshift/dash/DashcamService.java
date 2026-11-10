package racing.coldshift.dash;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Foreground service so the recorder keeps running when the UI is not in front. */
public class DashcamService extends Service {
    static void update(Context c, boolean recording) {
        Intent i = new Intent(c, DashcamService.class);
        if (recording) Api.startForeground(c, i);
        else c.stopService(i);
    }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        Notification n = Api.notification(this)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Dashcam recording")
            .setContentIntent(Api.openApp(this))
            .setOngoing(true)
            .build();
        // the microphone too while it records sound, which Android 14 only allows with the permission granted
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        if (DashPrefs.audio(this) && Api.granted(this, android.Manifest.permission.RECORD_AUDIO))
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        Api.foreground(this, 8, n, types);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { stopForeground(true); }

    @Override public IBinder onBind(Intent i) { return null; }
}

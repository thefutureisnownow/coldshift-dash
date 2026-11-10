package racing.coldshift.dash;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Keeps the process alive while music plays so it survives the UI going to the background. */
public class PlayerService extends Service {
    private static boolean on;

    static synchronized void update(Context c, Track t, boolean playing) {
        Intent i = new Intent(c, PlayerService.class);
        if (!playing) {
            on = false;
            c.stopService(i);
            return;
        }
        String title = t == null ? "" : t.displayTitle(), artist = t == null ? "" : t.artist;
        if (on) {
            // already in the foreground: a new track only changes the notification (newer Android would not
            // let the app start the service again from the background)
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(7, notification(c, title, artist));
            return;
        }
        i.putExtra("title", title);
        i.putExtra("artist", artist);
        on = Api.startForeground(c, i);
    }

    private static Notification notification(Context c, String title, String artist) {
        return Api.notification(c)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(Api.openApp(c))
            .setOngoing(true)
            .build();
    }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        Notification n = notification(this, in == null ? "" : in.getStringExtra("title"),
            in == null ? "" : in.getStringExtra("artist"));
        if (!Api.foreground(this, 7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)) on = false;
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { stopForeground(true); }

    @Override public IBinder onBind(Intent i) { return null; }
}

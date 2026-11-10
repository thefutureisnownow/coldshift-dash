package racing.coldshift.dash;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import java.util.List;

/** Keeps the car UI in front. Pauses itself while maintenance mode is on. */
public class Watchdog extends Service implements Runnable {
    static volatile long allowUntil = 0;
    private final Handler h = new Handler();
    private boolean running;

    /**
     * Only the 4.4 phones need it: from Android 5 an app sees only its own tasks, so it could not tell
     * another app was in front, and from 8 a background start throws. On a newer phone kiosk mode is being
     * the home app.
     */
    static void start(Context c) {
        if (Api.SDK < 21) c.startService(new Intent(c, Watchdog.class));
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (!running) { running = true; h.post(this); }
        return START_STICKY;
    }

    @Override public void onDestroy() { running = false; h.removeCallbacks(this); }
    @Override public IBinder onBind(Intent i) { return null; }

    public void run() {
        try {
            if (!Prefs.kiosk(this)) {
                stopSelf();
                return;
            }
            if (System.currentTimeMillis() > allowUntil) {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
                if (t != null && !t.isEmpty() && t.get(0).topActivity != null) {
                    String pkg = t.get(0).topActivity.getPackageName();
                    if (!pkg.equals(getPackageName()) && !pkg.equals("android")) {
                        Intent i = new Intent(this, Main.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(i);
                    }
                }
            }
        } catch (Throwable t) { /* try again next tick */ }
        if (running) h.postDelayed(this, 600);
    }
}

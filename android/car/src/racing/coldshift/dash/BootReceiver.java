package racing.coldshift.dash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent in) {
        if (!Prefs.kiosk(c)) return;                      // an ordinary app does not start with the phone
        Watchdog.start(c);
        Dashcam.get(c);
        // Android 10+ lets an app start an activity from the background only in a few cases; as the home
        // app it comes up after boot anyway
        if (Api.SDK >= 29) return;
        Intent i = new Intent(c, Main.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}

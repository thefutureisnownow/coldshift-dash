package racing.coldshift.dash;

import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * The satellites in view on Android 7 and up (GnssStatus). An app built for Android 12 or later is refused
 * the older GpsStatus calls, which the Maps page still uses on the Android 4.4 phones; this class is only
 * touched behind a version check, so those phones never load it.
 */
final class GnssSky extends GnssStatus.Callback {
    interface Sink {
        void sky(List<MapsPage.Sat> sats);
        void firstFix(int ms);
    }

    private final Sink sink;

    private GnssSky(Sink s) { sink = s; }

    /** Starts listening; null when the phone refuses (no permission yet, no receiver). */
    static GnssSky start(LocationManager lm, Sink s) {
        GnssSky g = new GnssSky(s);
        try {
            return lm.registerGnssStatusCallback(g, new Handler(Looper.getMainLooper())) ? g : null;
        } catch (Throwable t) {
            android.util.Log.w("GnssSky", "no satellite status", t);
            return null;
        }
    }

    void stop(LocationManager lm) {
        try { lm.unregisterGnssStatusCallback(this); } catch (Throwable t) { /* gone already */ }
    }

    @Override public void onSatelliteStatusChanged(GnssStatus st) {
        List<MapsPage.Sat> out = new ArrayList<MapsPage.Sat>();
        for (int i = 0; i < st.getSatelliteCount(); i++) {
            MapsPage.Sat s = new MapsPage.Sat();
            s.prn = st.getSvid(i);
            s.elev = st.getElevationDegrees(i);
            s.az = st.getAzimuthDegrees(i);
            s.snr = st.getCn0DbHz(i);
            s.used = st.usedInFix(i);
            out.add(s);
        }
        sink.sky(out);
    }

    @Override public void onFirstFix(int ttffMillis) { sink.firstFix(ttffMillis); }

    @Override public void onStopped() { sink.sky(new ArrayList<MapsPage.Sat>()); }
}

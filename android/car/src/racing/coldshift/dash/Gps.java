package racing.coldshift.dash;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

/**
 * One GPS feed for the pages that need fixes (Maps, Track): the receiver runs while any of them listens,
 * with a foreground service (DriveService) so it carries on in the background on a phone that is not a kiosk.
 * Test positions sent over adb (Main's FAKE_FIX) go to every listener, and real fixes are ignored for
 * 15 s after each one, so a fake drive is not interrupted by the phone's real position.
 */
final class Gps implements LocationListener {
    interface Listener {
        void onFix(Location l);
    }

    private static Gps instance;
    private final Context ctx;
    private final LocationManager lm;
    private final List<Listener> listeners = new ArrayList<Listener>();
    private boolean running;
    private long fakeUntil, savedAt;
    private Location last;

    static synchronized Gps get(Context c) {
        if (instance == null) instance = new Gps(c.getApplicationContext());
        return instance;
    }

    private Gps(Context c) {
        ctx = c;
        lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
    }

    void add(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        startUpdates();
    }

    /** After the location permission was granted (Permissions): the listeners waiting get fixes from now. */
    void permitted() { if (!listeners.isEmpty()) startUpdates(); }

    private void startUpdates() {
        if (running) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            running = true;
            DriveService.update(ctx, true);              // kept alive in the background
        } catch (Throwable t) { /* no GPS provider, or no permission yet */ }
    }

    void remove(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty() && running) {
            lm.removeUpdates(this);
            running = false;
            DriveService.update(ctx, false);
        }
    }

    Location last() { return last; }

    /**
     * Where the car last was, as {lat, lon}, for opening the map before the first fix: the last fix of
     * this run, else the one saved from an earlier run, else what Android remembers; null when none.
     */
    double[] lastKnown() {
        if (last != null) return new double[] {last.getLatitude(), last.getLongitude()};
        String v = Prefs.sp(ctx).getString("last_fix", "");
        try {
            String[] p = v.split(",");
            if (p.length == 2) return new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1])};
        } catch (NumberFormatException e) { /* not saved yet */ }
        try {
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l != null) return new double[] {l.getLatitude(), l.getLongitude()};
        } catch (Throwable t) { /* no permission yet */ }
        return null;
    }

    /** A test position over adb. */
    void fake(Location l) {
        fakeUntil = System.currentTimeMillis() + 15000;
        dispatch(l);
    }

    public void onLocationChanged(Location l) {
        long now = System.currentTimeMillis();
        if (now < fakeUntil) return;                           // a test drive is feeding positions
        if (now - savedAt > 30000) {                           // real positions only: where the map opens next time
            savedAt = now;
            Prefs.sp(ctx).edit().putString("last_fix", l.getLatitude() + "," + l.getLongitude()).apply();
        }
        dispatch(l);
    }

    private void dispatch(Location l) {
        last = l;
        for (Listener x : new ArrayList<Listener>(listeners)) x.onFix(l);
    }

    public void onStatusChanged(String p, int s, Bundle e) {}
    public void onProviderEnabled(String p) {}
    public void onProviderDisabled(String p) {}
}

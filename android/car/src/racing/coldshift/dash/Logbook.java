package racing.coldshift.dash;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

/**
 * Logs trips by itself: one begins when the car moves off, and ends once it has stood still for
 * TRIP_GAP_MS (the end is when it stopped), so a quick stop does not split a trip. GPS runs while the car
 * has power (the phone is charging) and for a while after, to see the car stop. A stop of 2 to 25 minutes
 * beside a fuel station on the map leaves a fill-up to confirm on the Log page.
 *
 * Each trip keeps its route (LogStore's routes) for the desktop's GPX.
 *
 * The trip under way and the stop are kept in the preferences, so a restart of the app neither loses nor
 * splits them; a trip left with no fixes for TRIP_GAP_MS is closed at its last movement.
 */
final class Logbook implements Gps.Listener, Runnable {
    static final long TRIP_GAP_MS = 15 * 60000, FUEL_MIN_MS = 2 * 60000, FUEL_MAX_MS = 25 * 60000;
    static final long GPS_AFTER_POWER_MS = 30 * 60000;
    static final double MIN_TRIP_M = 300, MOVED_M = 60, FUEL_M = 80, PLACE_M = 150;
    static final double ROUTE_M = 15;                // a route keeps a point every 15 m, or every 30 s
    static final long ROUTE_MS = 30000;
    static final double FUEL_NOTE_M = 300;           // the events log names the nearest station this far away
    static final float MOVING_MS = 2.5f;            // 9 km/h

    interface Listener {
        void changed();
    }

    private static Logbook instance;
    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Geocoder geocoder = new Geocoder();
    private Listener listener;
    private boolean listening, plugged;
    private long unpluggedAt;
    private Location last, prev;
    private long lastFixReal;                     // when the last fix came (elapsed time)
    // the trip under way (start 0 when none) and the current stop (since 0 when moving)
    private long tripStart, lastMove;
    private double tripM, maxMs, fromLat, fromLon, endLat, endLon;
    private String fromName = "";
    private double routeLat, routeLon;
    private long routeAt;
    private long stopSince;
    private double stopLat, stopLon;

    static synchronized Logbook get(Context c) {
        if (instance == null) instance = new Logbook(c.getApplicationContext());
        return instance;
    }

    private Logbook(Context c) {
        ctx = c;
        restore();
        h.post(this);
    }

    void setListener(Listener l) { listener = l; }

    private void changed() { if (listener != null) listener.changed(); }

    // ---- power and the GPS ----

    /** Every 15 s: power, and trips or stops that have run out. */
    public void run() {
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean p = b != null && b.getIntExtra("plugged", 0) != 0;
        long now = System.currentTimeMillis();
        if (plugged && !p) unpluggedAt = now;
        if (plugged != p) event(now, p ? "car power on" : "car power off");
        plugged = p;
        boolean want = plugged || now - unpluggedAt < GPS_AFTER_POWER_MS || tripStart != 0;
        if (want && !listening) Gps.get(ctx).add(this);
        if (!want && listening) Gps.get(ctx).remove(this);
        listening = want;
        // no fixes for a long while (GPS off, the app restarted): the trip ended where it last moved
        if (tripStart != 0 && realNow() - lastFixReal >= TRIP_GAP_MS) {
            event(now, "no GPS fixes for " + TRIP_GAP_MS / 60000 + " min");
            if (stopSince != 0) endTrip(stopSince, stopLat, stopLon);
            else endTrip(lastMove, endLat, endLon);
        }
        h.postDelayed(this, 15000);
    }

    private static long realNow() { return android.os.SystemClock.elapsedRealtime(); }

    Location last() { return last; }

    /** GPS metres of the trip under way, 0 without one. */
    double tripMetres() { return tripStart == 0 ? 0 : tripM; }

    public void onFix(Location l) {
        long now = "fake".equals(l.getProvider()) ? l.getTime() : System.currentTimeMillis();
        last = l;
        lastFixReal = realNow();
        if (l.getAccuracy() > 30) return;
        boolean moving = l.getSpeed() > MOVING_MS
            || (stopSince != 0 && Nav.dist(stopLat, stopLon, l.getLatitude(), l.getLongitude()) > MOVED_M);
        if (tripStart == 0 && moving) {
            tripStart = now;
            tripM = 0;
            maxMs = 0;
            fromLat = l.getLatitude();
            fromLon = l.getLongitude();
            fromName = "";
            prev = null;
            routeAt = 0;
            event(now, String.format(java.util.Locale.US, "trip started at %.5f, %.5f, %d km/h%s", fromLat, fromLon,
                Math.round(l.getSpeed() * 3.6), "fake".equals(l.getProvider()) ? " (test position)" : ""));
            LogStore.get(ctx).routeStart();
            nameStart(fromLat, fromLon);
        }
        if (tripStart != 0) {
            if (prev != null && l.getSpeed() >= 1.0f) {
                float d = prev.distanceTo(l);
                if (d < 250) tripM += d;
            }
            prev = l;
            if (l.getSpeed() > maxMs) maxMs = l.getSpeed();
            if (moving && (routeAt == 0 || now - routeAt >= ROUTE_MS
                || Nav.dist(routeLat, routeLon, l.getLatitude(), l.getLongitude()) >= ROUTE_M)) {
                LogStore.get(ctx).routePoint(now, l.getLatitude(), l.getLongitude(), l.getSpeed());
                routeAt = now;
                routeLat = l.getLatitude();
                routeLon = l.getLongitude();
            }
        }
        if (moving) {
            lastMove = now;
            endLat = l.getLatitude();
            endLon = l.getLongitude();
            if (stopSince != 0) {
                long stood = now - stopSince;
                event(now, "moved on after " + time(stood));
                if (stood >= 60000) checkFuel(stopSince, stood, stopLat, stopLon);
                stopSince = 0;
            }
        } else if (stopSince == 0 && (tripStart != 0 || lastMove != 0)) {
            stopSince = lastMove != 0 ? lastMove : now;
            stopLat = l.getLatitude();
            stopLon = l.getLongitude();
            event(now, String.format(java.util.Locale.US, "stopped at %.5f, %.5f", stopLat, stopLon));
        } else if (tripStart != 0 && stopSince != 0 && now - stopSince >= TRIP_GAP_MS) {
            endTrip(stopSince, stopLat, stopLon);
        }
        save();
    }

    // ---- trips ----

    private void endTrip(long end, double lat, double lon) {
        long start = tripStart;
        double m = tripM;
        tripStart = 0;
        save();
        LogStore store = LogStore.get(ctx);
        if (m < MIN_TRIP_M || end <= start) {
            event(end, "trip dropped: " + Math.round(m) + " m, under " + Math.round(MIN_TRIP_M) + " m");
            store.routeEnd(null, 0);
            return;
        }
        event(end, String.format(java.util.Locale.US, "trip ended: %.1f km (GPS), %s, top speed %d km/h", m / 1000,
            time(end - start), Math.round(maxMs * 3.6)));
        final JSONObject o = LogStore.make("trip", start);
        try {
            o.put("end", end);
            o.put("dist_m", Math.round(m));
            o.put("max", Math.round(maxMs * 10) / 10.0);
            o.put("from", place(fromName, fromLat, fromLon));
            o.put("to", place("", lat, lon));
            o.put("business", false);
            o.put("purpose", "");
            double odo = store.odometer(start, 0);
            if (!Double.isNaN(odo)) {
                o.put("odo_start", Math.round(odo * 10) / 10.0);
                o.put("odo_end", Math.round((odo + store.factor() * m / 1000) * 10) / 10.0);
            }
        } catch (Exception e) { /* cannot happen */ }
        store.routeEnd(o.optString("id"), end);
        store.put(o);
        changed();
        final double la = lat, lo = lon;
        new Thread(new Runnable() {
            public void run() {
                final String name = name(la, lo);
                h.post(new Runnable() {
                    public void run() {
                        try {
                            o.getJSONObject("to").put("name", name);
                            if (o.getJSONObject("from").optString("name").isEmpty())
                                o.getJSONObject("from").put("name", name(o.getJSONObject("from")));
                        } catch (Exception e) { /* cannot happen */ }
                        LogStore.get(ctx).put(o);
                        changed();
                    }
                });
            }
        }).start();
    }

    private String name(JSONObject p) { return name(p.optDouble("lat"), p.optDouble("lon")); }

    private static JSONObject place(String name, double lat, double lon) throws Exception {
        JSONObject p = new JSONObject();
        p.put("name", name);
        p.put("lat", Math.round(lat * 1e6) / 1e6);
        p.put("lon", Math.round(lon * 1e6) / 1e6);
        return p;
    }

    private void nameStart(final double lat, final double lon) {
        new Thread(new Runnable() {
            public void run() {
                final String n = name(lat, lon);
                h.post(new Runnable() {
                    public void run() {
                        if (tripStart != 0 && fromLat == lat && fromLon == lon) {
                            fromName = n;
                            save();
                        }
                    }
                });
            }
        }).start();
    }

    /** A saved place's name (Home, Work, a place saved on the map) near the spot, else the map's. */
    String name(double lat, double lon) {
        Places ps = Places.get(ctx);
        Places.Place home = ps.find(Places.HOME), work = ps.find(Places.WORK);
        if (home != null && Nav.dist(lat, lon, home.lat, home.lon) < PLACE_M) return "Home";
        if (work != null && Nav.dist(lat, lon, work.lat, work.lon) < PLACE_M) return "Work";
        for (Places.Place p : ps.all(Places.SAVED)) if (Nav.dist(lat, lon, p.lat, p.lon) < PLACE_M) return p.name;
        try {
            Geocoder.Place p = geocoder.reverse(lat, lon, 0);
            if (!p.title.isEmpty()) return p.title;
        } catch (Throwable t) {
            android.util.Log.w("Logbook", "no name from the map", t);
        }
        return String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon);
    }

    // ---- fill-ups ----

    /** After a stop of a minute or more: the nearest fuel station goes in the events log; a stop of 2 to 25
     * minutes within FUEL_M of one leaves a fill-up to confirm. */
    private void checkFuel(final long at, final long stood, final double lat, final double lon) {
        new Thread(new Runnable() {
            public void run() {
                Geocoder.Fuel f = null;
                try {
                    f = geocoder.nearestFuel(lat, lon, FUEL_NOTE_M);
                } catch (Throwable t) {
                    android.util.Log.w("Logbook", "cannot look for fuel stations", t);
                }
                final boolean offer = f != null && f.metres <= FUEL_M && stood >= FUEL_MIN_MS && stood <= FUEL_MAX_MS;
                event(at, (f == null ? "no fuel station within " + Math.round(FUEL_NOTE_M) + " m"
                    : "nearest fuel station: " + f.name + ", " + Math.round(f.metres) + " m") + " of a stop of " + time(stood)
                    + (offer ? ": fill-up offered" : ""));
                if (!offer) return;
                final String station = f.name;
                h.post(new Runnable() {
                    public void run() {
                        try {
                            JSONObject p = new JSONObject();
                            p.put("time", at);
                            p.put("stood", stood);
                            p.put("station", station);
                            p.put("lat", lat);
                            p.put("lon", lon);
                            Prefs.sp(ctx).edit().putString("log_pending", p.toString()).apply();
                        } catch (Exception e) { /* cannot happen */ }
                        changed();
                    }
                });
            }
        }).start();
    }

    /** The fuel station on the map within FUEL_M of the spot, or null. Slow: off the UI thread. */
    String fuelStation(double lat, double lon) {
        try {
            return geocoder.fuel(lat, lon, FUEL_M);
        } catch (Throwable t) {
            android.util.Log.w("Logbook", "cannot look for fuel stations", t);
            return null;
        }
    }

    /** A stop at a fuel station waiting to be logged or dismissed, or null. */
    JSONObject pending() {
        try {
            String s = Prefs.sp(ctx).getString("log_pending", "");
            return s.isEmpty() ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    void clearPending() {
        Prefs.sp(ctx).edit().remove("log_pending").apply();
        changed();
    }

    // ---- the events log ----

    private void event(long at, String what) { LogStore.get(ctx).event(at, what); }

    private static String time(long ms) {
        long s = ms / 1000;
        return s < 60 ? s + " s" : s < 3600 ? (s / 60) + " min" : (s / 3600) + " h " + (s % 3600 / 60) + " min";
    }

    // ---- kept across restarts ----

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("trip_start", tripStart);
            o.put("last_move", lastMove);
            o.put("trip_m", tripM);
            o.put("max", maxMs);
            o.put("from_lat", fromLat);
            o.put("from_lon", fromLon);
            o.put("from_name", fromName);
            o.put("end_lat", endLat);
            o.put("end_lon", endLon);
            o.put("stop_since", stopSince);
            o.put("stop_lat", stopLat);
            o.put("stop_lon", stopLon);
            Prefs.sp(ctx).edit().putString("log_state", o.toString()).apply();
        } catch (Exception e) { /* cannot happen */ }
    }

    private void restore() {
        try {
            JSONObject o = new JSONObject(Prefs.sp(ctx).getString("log_state", "{}"));
            tripStart = o.optLong("trip_start");
            lastMove = o.optLong("last_move");
            tripM = o.optDouble("trip_m", 0);
            maxMs = o.optDouble("max", 0);
            fromLat = o.optDouble("from_lat", 0);
            fromLon = o.optDouble("from_lon", 0);
            fromName = o.optString("from_name");
            endLat = o.optDouble("end_lat", 0);
            endLon = o.optDouble("end_lon", 0);
            stopSince = o.optLong("stop_since");
            stopLat = o.optDouble("stop_lat", 0);
            stopLon = o.optDouble("stop_lon", 0);
        } catch (Exception e) { /* start afresh */ }
    }
}

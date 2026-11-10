package racing.coldshift.dash;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import btools.router.OsmNodeNamed;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RouteAccess;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline routing with BRouter, in its own process (":router" in the manifest): a long route needs
 * about 128 MB, and here it cannot push the dashboard out of memory. One request at a time.
 *
 * Request: ROUTE with from/to lat/lon. Reply: the broadcast DONE (only this app can receive it),
 * with "ok", "error" and "ms"; the route itself is in route.bin (Route.read).
 * Routing tiles (*.rd5 from brouter.de) live in coldshift.dash/routing/ on the card.
 */
public class RouterService extends IntentService {
    static final String ROUTE = "racing.coldshift.dash.ROUTE";
    static final String DONE = "racing.coldshift.dash.ROUTE_DONE";
    static final String PERMISSION = "racing.coldshift.dash.ROUTER";
    static final String PROFILE = "car-vario";

    public RouterService() { super("router"); }

    static void request(Context c, double fromLat, double fromLon, double toLat, double toLon) {
        Intent i = new Intent(c, RouterService.class).setAction(ROUTE);
        i.putExtra("fromLat", fromLat).putExtra("fromLon", fromLon).putExtra("toLat", toLat).putExtra("toLon", toLon);
        c.startService(i);
    }

    static File resultFile(Context c) { return new File(c.getFilesDir(), "route.bin"); }

    @Override protected void onHandleIntent(Intent in) {
        if (in == null || !ROUTE.equals(in.getAction())) return;
        long t0 = SystemClock.elapsedRealtime();
        String error = null;
        try {
            File profile = profile();
            File tiles = Storage.routingDir();
            if (!tiles.isDirectory()) throw new IllegalStateException("No routing tiles on the card (coldshift.dash/routing)");
            List<OsmNodeNamed> wps = new ArrayList<OsmNodeNamed>();
            wps.add(node("from", in.getDoubleExtra("fromLat", 0), in.getDoubleExtra("fromLon", 0)));
            wps.add(node("to", in.getDoubleExtra("toLat", 0), in.getDoubleExtra("toLon", 0)));
            RoutingContext rc = new RoutingContext();
            rc.localFunction = profile.getPath();
            rc.turnInstructionMode = 3;       // compute turn hints (the profile's "1" keeps this)
            RoutingEngine re = new RoutingEngine(null, null, tiles, wps, rc);
            re.quite = true;                  // (sic) without an output file it would print the GPX to stdout
            re.doRun(120000);
            if (re.getErrorMessage() != null) throw new IllegalStateException(re.getErrorMessage());
            OsmTrack t = re.getFoundTrack();
            if (t == null || t.nodes.isEmpty()) throw new IllegalStateException("No route found");
            toRoute(t).write(resultFile(this));
        } catch (Throwable e) {
            android.util.Log.w("RouterService", "routing failed", e);
            error = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        long ms = SystemClock.elapsedRealtime() - t0;
        android.util.Log.i("RouterService", (error == null ? "route ready" : "failed: " + error) + " in " + ms + " ms");
        Intent done = new Intent(DONE).setPackage(getPackageName());
        done.putExtra("ok", error == null).putExtra("error", error).putExtra("ms", ms);
        sendBroadcast(done, PERMISSION);
    }

    private static OsmNodeNamed node(String name, double lat, double lon) {
        OsmNodeNamed n = new OsmNodeNamed();
        n.name = name;
        n.ilon = (int) Math.round((lon + 180) * 1e6);
        n.ilat = (int) Math.round((lat + 90) * 1e6);
        return n;
    }

    private static Route toRoute(OsmTrack t) {
        Route r = new Route();
        int n = t.nodes.size();
        r.lat = new double[n];
        r.lon = new double[n];
        for (int i = 0; i < n; i++) {
            OsmPathElement e = t.nodes.get(i);
            r.lat[i] = e.getILat() / 1e6 - 90;
            r.lon[i] = e.getILon() / 1e6 - 180;
        }
        int[][] h = RouteAccess.hints(t);
        r.hintAt = new int[h.length];
        r.hintCmd = new int[h.length];
        r.hintExit = new int[h.length];
        r.hintDist = new int[h.length];
        for (int i = 0; i < h.length; i++) {
            r.hintAt[i] = h[i][0];
            r.hintCmd[i] = h[i][1];
            r.hintExit[i] = h[i][2];
            r.hintDist[i] = h[i][3];
        }
        r.lengthM = t.distance;
        r.seconds = t.getTotalSeconds();
        return r;
    }

    /** The car profile and lookups.dat, copied out of the APK (BRouter reads them as files). */
    private File profile() throws Exception {
        File dir = new File(getFilesDir(), "brouter");
        dir.mkdirs();
        for (String name : new String[] {PROFILE + ".brf", "lookups.dat"}) {
            File f = new File(dir, name);
            InputStream in = getAssets().open("brouter/" + name);
            int size = in.available();
            if (f.length() != size) {
                OutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[16384];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                out.close();
            }
            in.close();
        }
        return new File(dir, PROFILE + ".brf");
    }
}

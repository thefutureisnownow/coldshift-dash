package racing.coldshift.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationManager;
import java.util.ArrayList;
import java.util.List;

class MapsPage extends Page implements Gps.Listener, GpsStatus.Listener, MapOverlay.Listener {
    private static final float CX = 241, CY = 349, R = 186;
    private final LocationManager lm;
    private GpsStatus status;
    private Location last;
    private long lastFixAt;
    private boolean listening;          // the GPS status listener (the fixes come from Gps)
    private final List<Sat> sats = new ArrayList<Sat>();
    private Object sky;                 // the GnssSky listening, on Android 7 and up

    /** A satellite in view, from whichever of Android's two ways of telling this phone has. */
    static final class Sat {
        int prn;
        float elev, az, snr;
        boolean used;
    }

    private void setSats(List<Sat> now) {
        sats.clear();
        heard = used = inView = 0;
        for (Sat s : now) {
            sats.add(s);
            if (s.snr > 0) heard++;                              // in the almanac is not the same as heard
            if (s.used) used++;
            if (s.elev > 0 || s.az > 0) inView++;
        }
        // strongest first: the bars show the sixteen that matter, and the sky draws the weak ones underneath
        java.util.Collections.sort(sats, new java.util.Comparator<Sat>() {
            public int compare(Sat a, Sat b) { return a.used != b.used ? (a.used ? -1 : 1) : Float.compare(b.snr, a.snr); }
        });
    }
    private int heard, used, inView;
    private int ttff;
    private int mode;                 // 0 map, 1 GPS status
    private boolean follow = true;
    // navigation
    private Nav nav;
    private double destLat, destLon, pickLat, pickLon;
    private String destName;
    private String pickName, pickInfo;
    private boolean wantRoute, routing, rerouting, picking;
    private long lastRouteAt, arrivedAt;
    private int pickGen;
    // finding places
    private final Geocoder geocoder = new Geocoder();
    private final Search search = new Search();
    private final java.util.concurrent.ExecutorService lookups = java.util.concurrent.Executors.newSingleThreadExecutor();
    private SearchPanel panel;
    // voice prompts
    private final Prompter prompter = new Prompter();
    private boolean gpsLostSaid;
    // while navigating the zoom follows the speed, until + or - is pressed (the position button gives it back)
    private boolean autoZoom = true;
    private int navZoom;
    private long navZoomAt;

    MapsPage(Main a) {
        super(a);
        lm = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
    }

    String label() { return "Maps"; }
    @Override String icon() { return "maps"; }

    @Override void onShow() {
        shown = true;
        act.maps().show(mode == 0, this);
        refreshOverlay();
        Gps.get(act).add(this);
        onShowStatus();
    }

    /** Starts listening for the satellites in view, once. */
    private void onShowStatus() {
        if (listening) return;
        if (Api.SDK >= 24) {                               // the older calls are refused on a current Android
            sky = GnssSky.start(lm, new GnssSky.Sink() {
                public void sky(List<Sat> now) { setSats(now); }
                public void firstFix(int ms) { ttff = ms; }
            });
            listening = sky != null;
            return;
        }
        try {
            lm.addGpsStatusListener(this);
            listening = true;
        } catch (Throwable t) { /* no provider */ }
    }

    @Override void onHide() {
        shown = false;
        if (panel != null) panel.close();
        act.maps().hide();
        // on the way somewhere, the positions keep coming: the turns are still spoken, and the Drive page shows them
        if (destName == null) Gps.get(act).remove(this);
        if (!listening) return;
        if (sky != null) { ((GnssSky) sky).stop(lm); sky = null; }
        else lm.removeGpsStatusListener(this);
        listening = false;
    }

    private boolean shown;              // the Maps tab is the one showing

    /** On the way somewhere (or waiting to be): UI keeps navTick going while another page shows. */
    boolean navigating() { return destName != null; }

    /** The part of the clock's tick that navigation needs, whichever page shows. */
    void navTick() {
        if (arrivedAt != 0 && System.currentTimeMillis() - arrivedAt > 15000) endNav(true);
        if (destName != null && last != null && !gpsLostSaid && System.currentTimeMillis() - lastFixAt > 10000) {
            gpsLostSaid = true;
            Voice.get(act).say("gps_lost");
        }
    }

    /** What the next turn is, for the map's banner and the Drive page; null when not navigating with a route. */
    MapOverlay.NavInfo navInfo() {
        if (destName == null || nav == null) return null;
        MapOverlay.NavInfo i = new MapOverlay.NavInfo();
        if (arrivedAt != 0) {
            i.cmd = Route.END;
            i.distance = "Arrived";
            i.instruction = destName;
        } else {
            int h = nav.nextHint();
            i.cmd = h < 0 ? Route.END : nav.route.hintCmd[h];
            i.exit = h < 0 ? 0 : nav.route.hintExit[h];
            i.distance = Nav.distance(nav.toNext());
            i.instruction = Nav.instruction(i.cmd, i.exit);
            int t = nav.thenHint(150);
            if (t >= 0) {
                i.thenCmd = nav.route.hintCmd[t];
                i.thenExit = nav.route.hintExit[t];
            }
            if (routing && rerouting) i.status = "Off the route: finding a new one\u2026";
            else if (nav.offRoute()) i.status = "Off the route";
        }
        i.remaining = Nav.distance(nav.remaining()).replace("Now", "0 m");
        i.time = Nav.duration(nav.remainingSeconds());
        i.eta = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(
            new java.util.Date(System.currentTimeMillis() + nav.remainingSeconds() * 1000L));
        return i;
    }

    /** A destination is chosen and there is no route yet: what it waits for, else null. */
    String pending() {
        if (destName == null || nav != null) return null;
        return routing ? "Finding a route to " + destName + "\u2026" : "Waiting for GPS to go to " + destName;
    }

    /** Where it is going ("" when nowhere). */
    String destination() { return destName == null ? "" : destName; }

    /** Home as a destination; false when no Home is set. */
    boolean goHome() {
        Places.Place h = Places.get(act).find(Places.HOME);
        if (h == null) return false;
        navigateTo(h.lat, h.lon, h.name, h.sub);
        return true;
    }

    @Override void tick() {
        navTick();
        if (mode == 0 && (destName != null || act.maps().overlay() != null && act.maps().overlay().noFix != noFix()))
            refreshOverlay();                                           // the arrival clock, and whether GPS is there
        if (!listening) onShowStatus();                    // refused before the permission was given: try again
        if (Api.SDK >= 24) return;                         // GnssSky tells the page as the sky changes
        try {
            status = lm.getGpsStatus(status);
        } catch (Throwable t) { return; }
        List<Sat> now = new ArrayList<Sat>();
        for (GpsSatellite g : status.getSatellites()) {
            Sat s = new Sat();
            s.prn = g.getPrn();
            s.elev = g.getElevation();
            s.az = g.getAzimuth();
            s.snr = g.getSnr();
            s.used = g.usedInFix();
            now.add(s);
        }
        setSats(now);
        ttff = status.getTimeToFirstFix();
    }

    Location lastFix() { return last; }

    /** No position yet, or none for 10 s. */
    private boolean noFix() { return last == null || System.currentTimeMillis() - lastFixAt > 10000; }

    public void onFix(Location l) {
        last = l;
        lastFixAt = System.currentTimeMillis();
        if (gpsLostSaid) {
            gpsLostSaid = false;
            if (destName != null) Voice.get(act).say("gps_back");
        }
        act.maps().onFix(l, follow);
        if (wantRoute && !routing) requestRoute(false);       // the destination was chosen before the first fix
        if (nav != null && arrivedAt == 0) {
            nav.update(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 10);
            if (nav.arrived()) {
                arrivedAt = System.currentTimeMillis();
                Voice.get(act).say("arrived");
            } else if (nav.offRoute() && !routing && System.currentTimeMillis() - lastRouteAt > 15000) {
                Voice.get(act).say("off_route");
                requestRoute(true);
            } else if (!nav.offRoute()) {
                String[] lines = prompter.onFix(nav, l.hasSpeed() ? l.getSpeed() : 0);
                if (lines != null) Voice.get(act).say(lines);
            }
            zoomForDriving(l);
        }
        refreshOverlay();
    }

    // ---- navigation ----

    /**
     * About 20-30 s of road ahead: zoom 17 (about 240 m ahead on this screen, heading-up) under 40 km/h
     * and within 300 m of a turn, 16 (about 480 m) to 80 km/h, 15 (about 950 m) above. A wider view
     * waits 5 s, so a moment's change of speed does not redraw the map back and forth.
     */
    private void zoomForDriving(Location l) {
        if (!autoZoom || !follow || nav == null) return;
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0;
        int z = kmh < 40 ? 17 : kmh < 80 ? 16 : 15;
        if (arrivedAt == 0 && nav.toNext() < 300) z = 17;
        long now = System.currentTimeMillis();
        if (z == navZoom) return;
        if (z < navZoom && now - navZoomAt < 5000) return;
        navZoom = z;
        navZoomAt = now;
        act.maps().setZoom(z);
    }

    /** Start navigating to a place; the route is found from the next (or last) fix. */
    void navigateTo(double lat, double lon, String name) { navigateTo(lat, lon, name, ""); }

    void navigateTo(double lat, double lon, String name, String sub) {
        Places.get(act).visited(name, sub, lat, lon);
        destLat = lat;
        destLon = lon;
        destName = name;
        nav = null;
        arrivedAt = 0;
        picking = false;
        act.maps().showPin(new org.mapsforge.core.model.LatLong(lat, lon));
        act.maps().clearRoute();
        if (!follow) onFollow();
        if (last != null) requestRoute(false);
        else wantRoute = true;
        refreshOverlay();
    }

    private void requestRoute(boolean again) {
        wantRoute = false;
        routing = true;
        rerouting = again;
        lastRouteAt = System.currentTimeMillis();
        RouterService.request(act, last.getLatitude(), last.getLongitude(), destLat, destLon);
        refreshOverlay();
    }

    void onRoute(Route r, long ms) {
        routing = false;
        if (destName == null) return;        // navigation ended while the route was being found
        android.util.Log.i("MapsPage", "route " + r.lengthM + " m, " + r.hintAt.length + " turns in " + ms + " ms"
            + (rerouting ? " (new route)" : ""));
        nav = new Nav(r, destLat, destLon, destName);
        prompter.reset();
        if (last != null) {
            nav.update(last.getLatitude(), last.getLongitude(), 10);
            String[] lines = prompter.onFix(nav, last.hasSpeed() ? last.getSpeed() : 0);
            if (lines != null) Voice.get(act).say(lines);
        }
        act.maps().showRoute(r);
        refreshOverlay();
    }

    void onRouteFailed(String why) {
        routing = false;
        if (destName == null) return;
        MapOverlay o = act.maps().overlay();
        if (o != null) {
            o.note = "No route: " + why;
            o.postInvalidate();
        }
        Voice.get(act).say("no_route");
        if (nav == null && !rerouting) endNav(false);
    }

    private void endNav(boolean clearNote) {
        nav = null;
        destName = null;
        wantRoute = false;
        arrivedAt = 0;
        act.maps().clearRoute();
        act.maps().showPin(null);
        if (autoZoom && follow) act.maps().resetZoom();
        navZoom = 0;
        MapOverlay o = act.maps().overlay();
        if (o != null && clearNote) o.note = "";
        refreshOverlay();
        if (!shown) Gps.get(act).remove(this);             // it only listened on for the journey
    }

    /** Puts navigation and destination state into the overlay. */
    private void refreshOverlay() {
        MapOverlay o = act.maps().overlay();
        if (o == null) return;
        o.pickTitle = picking ? pickName : null;
        o.pickSub = picking ? pickInfo : "";
        o.pickSaved = picking && Places.get(act).isSaved(pickLat, pickLon);
        o.noFix = noFix();
        o.pending = null;
        if (destName == null) {
            o.nav = null;
        } else if (nav == null) {
            o.nav = null;
            if (o.note.startsWith("No route")) o.note = "";
            o.pending = pending();
        } else {
            o.nav = navInfo();
            if (o.note.startsWith("No route")) o.note = "";
        }
        o.postInvalidate();
    }

    private static String coords(double lat, double lon) {
        return String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon);
    }

    /** Holding the map: show the place card, and name the spot from the map file. */
    void onPick(final double lat, final double lon, final double reachM) {
        if (destName != null) return;          // not while navigating
        showCard("Picked place", coords(lat, lon), lat, lon);
        final int gen = pickGen;
        lookups.execute(new Runnable() {
            public void run() {
                final Geocoder.Place p;
                try {
                    long t0 = System.currentTimeMillis();
                    p = geocoder.reverse(lat, lon, reachM);
                    android.util.Log.i("MapsPage", "named the spot in " + (System.currentTimeMillis() - t0) + " ms: "
                        + p.title + " / " + p.sub);
                } catch (Throwable t) {
                    android.util.Log.w("MapsPage", "reverse geocoding failed", t);
                    return;
                }
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (gen != pickGen || !picking || p.title.isEmpty()) return;
                        pickName = p.title;
                        pickInfo = p.sub.isEmpty() ? coords(lat, lon) : p.sub;
                        refreshOverlay();
                    }
                });
            }
        });
    }

    /** The place card for a spot, with the map centred on it when it came from the search. */
    private void showCard(String name, String sub, double lat, double lon) {
        pickGen++;
        pickLat = lat;
        pickLon = lon;
        pickName = name;
        pickInfo = sub;
        picking = true;
        act.maps().showPin(new org.mapsforge.core.model.LatLong(lat, lon));
        refreshOverlay();
    }

    public void onGoTo() {
        if (panel == null) {
            panel = new SearchPanel(act, searchOwner);
            act.root().addView(panel, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        panel.bringToFront();
        panel.open();
    }

    public void onPickAction(int action) {
        Places places = Places.get(act);
        if (action == MapOverlay.PICK_GO) {
            navigateTo(pickLat, pickLon, pickName.equals("Picked place") ? "the picked place" : pickName,
                pickInfo.equals(coords(pickLat, pickLon)) ? "" : pickInfo);
            return;
        }
        if (action == MapOverlay.PICK_SAVE) {
            if (places.isSaved(pickLat, pickLon)) {
                for (Places.Place p : places.all(Places.SAVED))
                    if (Nav.dist(p.lat, p.lon, pickLat, pickLon) < 30) places.remove(p);
            } else {
                places.save(pickName, pickInfo, pickLat, pickLon);
            }
            refreshOverlay();
            return;
        }
        if (action == MapOverlay.PICK_HOME || action == MapOverlay.PICK_WORK) {
            String sub = pickName.equals("Picked place") ? pickInfo : pickInfo.equals(coords(pickLat, pickLon))
                ? pickName : pickName + " \u00b7 " + pickInfo;
            places.setHomeOrWork(action == MapOverlay.PICK_HOME ? Places.HOME : Places.WORK, sub, pickLat, pickLon);
        }
        picking = false;
        act.maps().showPin(null);
        refreshOverlay();
    }

    /** After a sync: new search data or map on the card, or places restored from the desktop. */
    void reloadPlaces(boolean data, boolean places) {
        if (data) {
            lookups.execute(new Runnable() {
                public void run() {
                    geocoder.reset();
                    search.close();
                }
            });
        }
        if (places) Places.get(act).restore();
        if (panel != null && panel.isOpen()) panel.refresh();
    }

    // ---- the Go to screen ----

    private final SearchPanel.Owner searchOwner = new SearchPanel.Owner() {
        public List<SearchPanel.Row> emptyRows() {
            Places p = Places.get(act);
            List<SearchPanel.Row> out = new ArrayList<SearchPanel.Row>();
            out.add(homeOrWork(p.find(Places.HOME), "Home"));
            out.add(homeOrWork(p.find(Places.WORK), "Work"));
            for (Places.Place s : p.all(Places.SAVED)) out.add(row(s, true));
            for (Places.Place s : p.all(Places.RECENT)) out.add(row(s, true));
            return out;
        }

        public List<SearchPanel.Row> search(String text) {
            List<SearchPanel.Row> out = new ArrayList<SearchPanel.Row>();
            String q = Search.fold(text);
            Places p = Places.get(act);
            List<Places.Place> mine = new ArrayList<Places.Place>();
            for (String k : new String[] {Places.HOME, Places.WORK, Places.SAVED, Places.RECENT}) mine.addAll(p.all(k));
            for (Places.Place s : mine) {
                String n = Search.fold(s.name);
                if (n.startsWith(q) || n.contains(" " + q)) out.add(row(s, false));
                if (out.size() >= 3) break;
            }
            Location at = last;
            for (Search.Result r : search.find(text, at == null ? 0 : at.getLatitude(), at == null ? 0 : at.getLongitude(),
                    at != null, 12 - out.size())) {
                SearchPanel.Row row = new SearchPanel.Row();
                row.name = r.name;
                row.sub = r.sub();
                row.lat = r.lat;
                row.lon = r.lon;
                row.km = r.km;
                row.tag = r;
                out.add(row);
            }
            return out;
        }

        public String status() {
            return search.available() ? "" : "No search data on the card yet: sync the map from the desktop tool."
                + " Home, Work and saved places work without it.";
        }

        public void chosen(SearchPanel.Row r) {
            panel.close();
            if (r.tag instanceof Places.Place) {               // Home, Work, saved, recent: go
                navigateTo(r.lat, r.lon, r.name, r.sub);
                return;
            }
            if (follow) onFollow();
            act.maps().centerOn(r.lat, r.lon);
            showCard(r.name, r.sub, r.lat, r.lon);
        }

        public void removed(SearchPanel.Row r) {
            if (r.tag instanceof Places.Place) Places.get(act).remove((Places.Place) r.tag);
        }

        public void closed() { panel.close(); }

        private SearchPanel.Row homeOrWork(Places.Place p, String title) {
            if (p != null) return row(p, false);
            SearchPanel.Row r = new SearchPanel.Row();
            r.name = title;
            r.sub = "Not set: hold the map on it, then \u201cSet as " + title + "\u201d";
            r.unset = true;
            return r;
        }

        private SearchPanel.Row row(Places.Place p, boolean removable) {
            SearchPanel.Row r = new SearchPanel.Row();
            r.name = p.name;
            r.sub = p.kind.equals(Places.RECENT) ? (p.sub.isEmpty() ? "Recent" : "Recent \u00b7 " + p.sub)
                : p.sub.isEmpty() ? coords(p.lat, p.lon) : p.sub;
            r.lat = p.lat;
            r.lon = p.lon;
            r.removable = removable;
            r.tag = p;
            if (last != null) r.km = Nav.dist(last.getLatitude(), last.getLongitude(), p.lat, p.lon) / 1000;
            return r;
        }
    };

    public void onEndNav() { endNav(true); }

    public void onMode() {
        mode = 1 - mode;
        act.maps().show(mode == 0, this);
    }

    public void onFollow() {
        follow = !follow;
        act.maps().overlay().follow = follow;
        if (follow) {
            autoZoom = true;
            navZoom = 0;
            act.maps().resetZoom();
            act.maps().centerOnMe(last);
        }
        act.maps().overlay().invalidate();
    }

    public void onZoom(int d) {
        autoZoom = false;
        act.maps().zoom(d);
    }

    public void onCompass() { act.maps().toggleHeadingUp(follow); }
    public void onGpsStatusChanged(int event) {}

    private int snrColor(float snr) { return snr >= 30 ? Theme.GREEN : snr >= 20 ? Theme.AMBER : Theme.BAD; }

    @Override boolean wide() { return true; }

    @Override int intervalMs() { return mode == 0 ? 1000 : 1000; }

    void draw(Canvas c) {
        if (mode == 1) {
            sky(c);
            info(c);
            return;
        }
        Theme.panel(c, 14, 68, Wide.rx(1266), 625);
        MapHost m = act.maps();
        if (!m.hasMap()) {
            String err = m.error.isEmpty() ? "Loading the map\u2026" : m.error;
            Theme.text(c, err, Wide.W / 2, 330, 24, 0xffffffff, Theme.bold, Theme.CENTER);
            if (m.error.startsWith("No map"))
                Theme.text(c, "Copy a .map file (from download.mapsforge.org) to coldshift.dash/maps/ on the SD card.",
                    Wide.W / 2, 366, 18, Theme.SUB, Theme.sans, Theme.CENTER);
        }
    }

    private void sky(Canvas c) {
        Theme.panel(c, 14, 68, 468, 625);
        Theme.text(c, "Satellites", 31, 102, 22, 0xffe8eef7, Theme.bold, Theme.LEFT);
        Paint p = Theme.P;
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f);
        p.setColor(Theme.accent(0x80));
        c.drawCircle(CX, CY, R, p);
        p.setColor(Theme.accent(0x55));
        c.drawCircle(CX, CY, R * 2 / 3, p);
        c.drawCircle(CX, CY, R / 3, p);
        c.drawLine(CX - R, CY, CX + R, CY, p);
        c.drawLine(CX, CY - R, CX, CY + R, p);
        p.setStyle(Paint.Style.FILL);
        Theme.text(c, "N", CX, CY - R - 8, 18, Theme.SUB, Theme.bold, Theme.CENTER);
        Theme.text(c, "S", CX, CY + R + 22, 18, Theme.SUB, Theme.bold, Theme.CENTER);
        Theme.text(c, "W", CX - R - 22, CY + 5, 18, Theme.SUB, Theme.bold, Theme.CENTER);
        Theme.text(c, "E", CX + R + 22, CY + 5, 18, Theme.SUB, Theme.bold, Theme.CENTER);
        Theme.text(c, "30\u00b0", CX, CY - R * 2 / 3 + 12, 18, Theme.SUB, Theme.bold, Theme.CENTER);
        Theme.text(c, "60\u00b0", CX, CY - R / 3 + 12, 18, Theme.SUB, Theme.bold, Theme.CENTER);
        if (sats.isEmpty()) {
            Theme.text(c, "No satellites in view yet", CX, CY + 5, 18, Theme.SUB, Theme.bold, Theme.CENTER);
        }
        for (int i = sats.size() - 1; i >= 0; i--) {             // the strongest last, on top
            Sat s = sats.get(i);
            if (s.elev <= 0 && s.az <= 0) continue;
            double az = Math.toRadians(s.az);
            float r = R * (90f - s.elev) / 90f;
            float x = CX + (float) Math.sin(az) * r, y = CY - (float) Math.cos(az) * r;
            if (s.snr <= 0) {                                    // up there but not heard: a faint dot, no number
                p.setStyle(Paint.Style.FILL);
                p.setColor(Theme.accent(0x80));
                c.drawCircle(x, y, 4, p);
                continue;
            }
            int col = snrColor(s.snr);
            p.setStyle(Paint.Style.FILL);
            p.setColor(s.used ? col : 0xf0050912);
            c.drawCircle(x, y, 13, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.4f);
            p.setColor(col);
            c.drawCircle(x, y, 13, p);
            p.setStyle(Paint.Style.FILL);
            Theme.text(c, String.valueOf(s.prn), x, y + 5, 14, s.used ? 0xff05070d : 0xffffffff,
                Theme.bold, Theme.CENTER);
        }
        Theme.text(c, "Solid: used in the fix. Green: 30 dB-Hz and up.", 31, 604, 18, Theme.SUB, Theme.sans, Theme.LEFT);
    }

    private String compass(float deg) {
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return n[Math.round(deg / 45f) % 8];
    }

    private void info(Canvas c) {
        Theme.panel(c, 482, 68, Wide.rx(1266), 625);
        boolean fix = last != null && System.currentTimeMillis() - lastFixAt < 5000;
        boolean provider;
        try { provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Throwable t) { provider = false; }
        String head = fix ? "GPS fix" : provider ? "Searching for satellites" : "GPS is turned off";
        String sub = fix ? "" : provider ? "A cold start can take a few minutes with a clear view of the sky."
            : "Turn on location in Android settings.";
        Theme.text(c, head, 499, 110, 29, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, sub, 499, 138, 18, Theme.DIM, Theme.sans, Theme.LEFT);

        String pos = "-", spd = "-", hdg = "-", alt = "-", acc = "-", upd = "-";
        if (last != null) {
            pos = String.format(java.util.Locale.US, "%.5f, %.5f", last.getLatitude(), last.getLongitude());
            spd = String.format(java.util.Locale.US, "%.0f km/h", last.getSpeed() * 3.6f);
            hdg = last.hasBearing() ? String.format(java.util.Locale.US, "%.0f\u00b0 %s", last.getBearing(), compass(last.getBearing())) : "-";
            alt = last.hasAltitude() ? String.format(java.util.Locale.US, "%.0f m", last.getAltitude()) : "-";
            acc = last.hasAccuracy() ? String.format(java.util.Locale.US, "\u00b1%.0f m", last.getAccuracy()) : "-";
            upd = (System.currentTimeMillis() - lastFixAt) / 1000 + " s ago";
        }
        String[][] cells = {
            {"Position", pos}, {"Speed", spd}, {"Heading", hdg}, {"Altitude", alt},
            {"Satellites", used + " used \u00b7 " + heard + " heard \u00b7 " + inView + " in view"},
            {"Accuracy", acc},
            {"Time to first fix", ttff > 0 ? String.format(java.util.Locale.US, "%.1f s", ttff / 1000f) : "-"},
            {"Last update", upd}};
        for (int i = 0; i < cells.length; i++) {
            float x = 499 + (i % 2) * (387 + Wide.d() / 2), y = 168 + (i / 2) * 54;
            Theme.text(c, cells[i][0], x, y, 18, Theme.DIM, Theme.sans, Theme.LEFT);
            Theme.text(c, Theme.fit(cells[i][1], 370, 22, Theme.bold), x, y + 24, 22, 0xffffffff, Theme.bold, Theme.LEFT);
        }

        Theme.text(c, "Signal strength per satellite (dB-Hz)", 499, 392, 18, Theme.DIM, Theme.sans, Theme.LEFT);
        Paint p = Theme.P;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        int[] marks = {20, 30, 40};
        for (int m : marks) {
            float y = 585 - m * 3.6f;
            p.setColor(Theme.accent(0x30));
            c.drawRect(527, y, Wide.rx(1248), y + 1, p);
            Theme.text(c, String.valueOf(m), 520, y + 4, 18, Theme.DIM, Theme.sans, Theme.RIGHT);
        }
        if (sats.isEmpty()) {
            Theme.text(c, "Waiting for satellites\u2026", Wide.mid(527, 1248), 520, 18, Theme.SUB, Theme.sans, Theme.CENTER);
            return;
        }
        int n = Math.min(sats.size(), 16);
        float bw = Math.min(36f, (700f + Wide.d()) / n - 6);
        for (int i = 0; i < n; i++) {
            Sat s = sats.get(i);
            float x = 540 + i * (bw + 8);
            float top = 585 - Math.max(0, s.snr) * 3.6f;
            RectF r = new RectF(x, top, x + bw, 585);
            p.setColor(s.used ? snrColor(s.snr) : (snrColor(s.snr) & 0x00ffffff) | 0x77000000);
            c.drawRoundRect(r, 3, 3, p);
            Theme.text(c, String.valueOf(s.prn), x + bw / 2, 603, 14, Theme.SUB, Theme.sans, Theme.CENTER);
        }
    }
}

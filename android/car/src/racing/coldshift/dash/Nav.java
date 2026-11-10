package racing.coldshift.dash;

/**
 * Following a Route: where along it the car is, the next turn, what is left, and whether the car
 * has left it or arrived. Plain Java (no Android), fed one fix at a time.
 */
final class Nav {
    private static final double R = 6371000;
    /** Further than this from the route (plus the fix's accuracy) counts as off it... */
    static final double OFF_ROUTE_M = 40;
    /** ...for this many fixes in a row (one wild fix must not trigger a new route). */
    static final int OFF_ROUTE_FIXES = 3;
    /** Within this of the end of the route is arrival. */
    static final double ARRIVED_M = 30;

    final Route route;
    final double destLat, destLon;
    final String destName;
    final double[] cum;             // metres along the route at each point
    private int idx;                // the segment the car was last matched to
    private double along;           // metres along the route at the car
    private double off;             // metres from the route at the last fix
    private int offCount;
    private boolean matched, arrived;

    Nav(Route r, double destLat, double destLon, String destName) {
        route = r;
        this.destLat = destLat;
        this.destLon = destLon;
        this.destName = destName;
        cum = new double[r.lat.length];
        for (int i = 1; i < cum.length; i++) cum[i] = cum[i - 1] + dist(r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i]);
    }

    static double dist(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * R;
    }

    /** Matches a fix to the route: a window ahead of the last match first, the whole route if that fails. */
    void update(double lat, double lon, float accuracy) {
        int n = cum.length;
        if (n < 2) return;
        int lo = matched ? Math.max(0, idx - 3) : 0, hi = matched ? Math.min(n - 2, idx + 400) : n - 2;
        double[] best = closest(lat, lon, lo, hi);
        if (best[1] > OFF_ROUTE_M && (lo > 0 || hi < n - 2)) {
            double[] all = closest(lat, lon, 0, n - 2);
            if (all[1] < best[1]) best = all;
        }
        idx = (int) best[0];
        along = cum[idx] + best[2];
        off = best[1];
        matched = true;
        offCount = off > OFF_ROUTE_M + Math.min(accuracy, 30) ? offCount + 1 : 0;
        if (remaining() < ARRIVED_M || dist(lat, lon, destLat, destLon) < ARRIVED_M) arrived = true;
    }

    /** {segment index, metres from the route, metres along that segment} of the closest point. */
    private double[] closest(double lat, double lon, int lo, int hi) {
        double cos = Math.cos(Math.toRadians(lat));
        double bestD = Double.MAX_VALUE, bestT = 0;
        int bestI = lo;
        for (int i = lo; i <= hi; i++) {
            // a flat projection around the fix is plenty at these distances
            double ax = (route.lon[i] - lon) * cos, ay = route.lat[i] - lat;
            double bx = (route.lon[i + 1] - lon) * cos, by = route.lat[i + 1] - lat;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double px = ax + t * dx, py = ay + t * dy;
            double d = Math.sqrt(px * px + py * py);
            if (d < bestD) {
                bestD = d;
                bestI = i;
                bestT = t;
            }
        }
        double segLen = cum[bestI + 1] - cum[bestI];
        return new double[] {bestI, Math.toRadians(bestD) * R, bestT * segLen};
    }

    /** The hint for the next turn ahead (index into route.hint*), or -1. */
    int nextHint() {
        for (int h = 0; h < route.hintAt.length; h++) {
            int at = Math.min(route.hintAt[h], cum.length - 1);
            if (cum[at] > along + 5) return h;
        }
        return -1;
    }

    /** Metres to the next turn, or to the end if there is none. */
    double toNext() {
        int h = nextHint();
        return (h < 0 ? cum[cum.length - 1] : cum[Math.min(route.hintAt[h], cum.length - 1)]) - along;
    }

    /** The turn after the next one, if it comes soon after (for "then turn left"), or -1. */
    int thenHint(double withinM) {
        int h = nextHint();
        if (h < 0 || h + 1 >= route.hintAt.length) return -1;
        double gap = cum[Math.min(route.hintAt[h + 1], cum.length - 1)] - cum[Math.min(route.hintAt[h], cum.length - 1)];
        return gap <= withinM ? h + 1 : -1;
    }

    double remaining() { return Math.max(0, cum[cum.length - 1] - along); }

    /** BRouter's time for the route, scaled to what is left of it. */
    int remainingSeconds() {
        double total = cum[cum.length - 1];
        return total <= 0 ? 0 : (int) Math.round(route.seconds * remaining() / total);
    }

    double offRouteMetres() { return off; }
    boolean offRoute() { return offCount >= OFF_ROUTE_FIXES; }
    boolean arrived() { return arrived; }
    double along() { return along; }

    // ---- words and numbers for the banner ----

    static String instruction(int cmd, int exit) {
        switch (cmd) {
            case Route.C: return "Continue straight";
            case Route.TL: return "Turn left";
            case Route.TSLL: return "Turn slightly left";
            case Route.TSHL: return "Turn sharp left";
            case Route.TR: return "Turn right";
            case Route.TSLR: return "Turn slightly right";
            case Route.TSHR: return "Turn sharp right";
            case Route.KL: return "Keep left";
            case Route.KR: return "Keep right";
            case Route.TLU: case Route.TRU: case Route.TU: return "Make a U-turn";
            case Route.RNDB: case Route.RNLB:
                return exit > 0 ? "At the roundabout, take exit " + exit : "Enter the roundabout";
            case Route.EL: return "Take the exit on the left";
            case Route.ER: return "Take the exit on the right";
            case Route.END: return "Arrive at your destination";
            default: return "Continue";
        }
    }

    static String distance(double m) {
        if (m < 30) return "Now";
        if (m < 1000) return (Math.round(m / 10) * 10) + " m";
        if (m < 10000) return String.format(java.util.Locale.US, "%.1f km", m / 1000);
        return Math.round(m / 1000) + " km";
    }

    static String duration(int s) {
        int min = Math.max(0, Math.round(s / 60f));
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }
}

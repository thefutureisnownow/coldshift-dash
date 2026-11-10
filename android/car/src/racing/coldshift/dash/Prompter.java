package racing.coldshift.dash;

/**
 * When to say what while navigating, fed one fix at a time; returns the voice lines to speak, or null.
 * Plain Java (no Android), like Nav.
 *
 * Each turn is announced at distances that suit the speed (in town 200 m; on open roads 500 and 200 m;
 * on highways 2 km, 1 km and 500 m) and again at the turn itself, about four seconds before it.
 * "then ..." follows when the turn after comes within 150 m. After a turn, a long road ahead gets
 * "follow the road for N km". A new route or a turn that comes up between the usual distances is
 * announced at once, with the distance rounded down to a line the pack has.
 */
final class Prompter {
    /** Distances the packs have lines for, metres, largest first, and their keys. */
    private static final int[] PREFIX_M = {2000, 1000, 500, 300, 200, 100};
    private static final String[] PREFIX = {"in_2km", "in_1km", "in_500m", "in_300m", "in_200m", "in_100m"};
    private static final int[] FOLLOW_KM = {50, 20, 10, 5};

    private int hint = -2;                // the turn the state below is about
    private int saidAt = Integer.MAX_VALUE;   // the smallest announcement distance said for it
    private boolean saidNow, followSaid, start = true;
    private int thenSaid = -1;            // a turn already announced as "then ..." with the one before it

    /** A new route: its first turn is announced on the next fix, however far it is. */
    void reset() {
        hint = -2;
        saidAt = Integer.MAX_VALUE;
        saidNow = false;
        start = true;
        thenSaid = -1;
    }

    static int[] stages(float speedMs) {
        if (speedMs < 16.7f) return new int[] {200};               // under 60 km/h
        if (speedMs < 27.8f) return new int[] {500, 200};          // under 100 km/h
        return new int[] {2000, 1000, 500};
    }

    /** The line for a turn: "turn_left", "roundabout_2", "destination" (h < 0: the end of the route). */
    static String action(Nav nav, int h) {
        if (h < 0) return "destination";
        int cmd = nav.route.hintCmd[h], exit = nav.route.hintExit[h];
        switch (cmd) {
            case Route.TL: return "turn_left";
            case Route.TR: return "turn_right";
            case Route.TSLL: return "turn_slight_left";
            case Route.TSLR: return "turn_slight_right";
            case Route.TSHL: return "turn_sharp_left";
            case Route.TSHR: return "turn_sharp_right";
            case Route.KL: return "keep_left";
            case Route.KR: return "keep_right";
            case Route.EL: return "exit_left";
            case Route.ER: return "exit_right";
            case Route.TLU: case Route.TRU: case Route.TU: return "u_turn";
            case Route.RNDB: case Route.RNLB: return exit >= 1 && exit <= 8 ? "roundabout_" + exit : "roundabout";
            case Route.END: return "destination";
            default: return "straight";
        }
    }

    /** The distance line nearest to m (180 m: "in 200 metres"), or null when it is too close for one. */
    static String prefix(double m) {
        if (m < 70) return null;
        int best = 0;
        for (int i = 1; i < PREFIX_M.length; i++) if (Math.abs(PREFIX_M[i] - m) < Math.abs(PREFIX_M[best] - m)) best = i;
        return PREFIX[best];
    }

    /** After announcing at distance d: every stage at or beyond d is done. */
    private void covered(int[] st, double d, double lead) {
        for (int t : st) if (d <= t + lead) saidAt = Math.min(saidAt, t);
    }

    private String[] with(String first, Nav nav, int h) {
        String act = action(nav, h);
        int t = h < 0 ? -1 : nav.thenHint(150);
        if (t >= 0) thenSaid = t;
        if (t >= 0) return first == null ? new String[] {act, "then", action(nav, t)} : new String[] {first, act, "then", action(nav, t)};
        return first == null ? new String[] {act} : new String[] {first, act};
    }

    /** The lines to say after this fix, or null. */
    String[] onFix(Nav nav, float speedMs) {
        if (nav.arrived()) return null;
        int h = nav.nextHint();
        double d = nav.toNext();
        boolean fresh = h != hint;
        if (fresh) {
            hint = h;
            saidAt = Integer.MAX_VALUE;
            saidNow = false;
            followSaid = false;
            if (h >= 0 && h == thenSaid) saidAt = 0;     // heard with the turn before; only "now" is left
        }
        double now = Math.max(25, speedMs * 4);
        if (d <= now) {
            if (saidNow || h < 0 || nav.route.hintCmd[h] == Route.END) return null;   // that gets "arrived" instead
            saidNow = true;
            saidAt = 0;
            return with(null, nav, h);
        }
        int[] st = stages(speedMs);
        double lead = speedMs * 1.5;                              // the lines take a moment to say
        if (start) {                                              // a new route: say where it starts
            start = false;
            if (d <= 2000 + lead) {
                covered(st, d, lead);
                hint = h;
                return with(prefix(Math.min(d, 2000)), nav, h);
            }
        }
        for (int s : st) {
            if (s >= saidAt) continue;
            if (d <= s + lead && d >= s * 0.6) {
                covered(st, d, lead);
                return with(prefix(Math.min(d, s)), nav, h);
            }
        }
        // a turn that came up between the usual distances (a new route, two turns close together)
        if (fresh && h != thenSaid && d <= st[0] + lead) {
            String p = prefix(d);
            covered(st, d, lead);
            return p == null ? with(null, nav, h) : with(p, nav, h);
        }
        if (fresh && !followSaid && d >= FOLLOW_KM[FOLLOW_KM.length - 1] * 1000) {
            followSaid = true;
            for (int km : FOLLOW_KM) if (d >= km * 1000) return new String[] {"follow_" + km + "km"};
        }
        return null;
    }
}

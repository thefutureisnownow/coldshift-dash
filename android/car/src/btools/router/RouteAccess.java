package btools.router;

/**
 * BRouter keeps a voice hint's command, track index and distance package-private; this class sits in
 * BRouter's package (inside the app, next to libs/brouter-*.jar) to read them for the app's Route.
 */
public final class RouteAccess {
    private RouteAccess() {}

    /** [index into track, command, roundabout exit, distance to next hint in m] per hint. The exit of a
     *  left-hand (clockwise) roundabout, as in South Africa, comes negative from BRouter. */
    public static int[][] hints(OsmTrack t) {
        if (t.voiceHints == null) return new int[0][];
        int[][] out = new int[t.voiceHints.list.size()][];
        for (int i = 0; i < out.length; i++) {
            VoiceHint h = t.voiceHints.list.get(i);
            out[i] = new int[] {h.indexInTrack, h.cmd, Math.abs(h.getExitNumber()), (int) Math.round(h.distanceToNext)};
        }
        return out;
    }
}

package racing.coldshift.dash;

final class Track {
    String path = "", title = "", artist = "", album = "", folder = "", artKey = "";
    int durationMs, trackNo;
    long mtime, size;

    String displayTitle() {
        if (!title.isEmpty()) return title;
        String n = new java.io.File(path).getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}

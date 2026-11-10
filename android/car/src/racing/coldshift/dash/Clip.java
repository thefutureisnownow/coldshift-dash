package racing.coldshift.dash;

import java.io.File;

final class Clip {
    File file;
    int seq;
    long size, startMs;
    boolean locked;

    static Clip parse(File f) {
        String n = f.getName();
        if (!n.endsWith(".mp4") || n.length() < 12) return null;
        Clip c = new Clip();
        c.file = f;
        c.size = f.length();
        c.locked = n.endsWith("_L.mp4");
        try {
            c.seq = Integer.parseInt(n.substring(0, 6));
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss");
            c.startMs = fmt.parse(n.substring(7, 22)).getTime();
        } catch (Exception e) {
            c.startMs = f.lastModified();
        }
        return c;
    }

    File sidecar() {
        String n = file.getName();
        return new File(file.getParentFile(), n.substring(0, n.length() - 4) + ".srt");
    }
}

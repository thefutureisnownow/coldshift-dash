package racing.coldshift.dash;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A computed route: the track and its turn hints. RouterService writes it to a file in its own
 * process and the UI reads it back, so a route never travels through a Binder transaction.
 */
final class Route {
    // BRouter's VoiceHint commands (btools.router.VoiceHint)
    static final int C = 1, TL = 2, TSLL = 3, TSHL = 4, TR = 5, TSLR = 6, TSHR = 7, KL = 8, KR = 9,
        TLU = 10, TRU = 11, OFFR = 12, RNDB = 13, RNLB = 14, TU = 15, BL = 16, EL = 17, ER = 18, END = 100;

    double[] lat, lon;          // the track
    int[] hintAt, hintCmd, hintExit, hintDist;   // per turn: track index, command, roundabout exit, metres to the next
    int lengthM, seconds;

    void write(File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
        o.writeInt(1);
        o.writeInt(lengthM);
        o.writeInt(seconds);
        o.writeInt(lat.length);
        for (int i = 0; i < lat.length; i++) {
            o.writeDouble(lat[i]);
            o.writeDouble(lon[i]);
        }
        o.writeInt(hintAt.length);
        for (int i = 0; i < hintAt.length; i++) {
            o.writeInt(hintAt[i]);
            o.writeInt(hintCmd[i]);
            o.writeInt(hintExit[i]);
            o.writeInt(hintDist[i]);
        }
        o.close();
        if (!tmp.renameTo(f)) throw new IOException("could not write " + f);
    }

    static Route read(File f) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
        try {
            if (in.readInt() != 1) throw new IOException("unknown route format");
            Route r = new Route();
            r.lengthM = in.readInt();
            r.seconds = in.readInt();
            int n = in.readInt();
            r.lat = new double[n];
            r.lon = new double[n];
            for (int i = 0; i < n; i++) {
                r.lat[i] = in.readDouble();
                r.lon[i] = in.readDouble();
            }
            int h = in.readInt();
            r.hintAt = new int[h];
            r.hintCmd = new int[h];
            r.hintExit = new int[h];
            r.hintDist = new int[h];
            for (int i = 0; i < h; i++) {
                r.hintAt[i] = in.readInt();
                r.hintCmd[i] = in.readInt();
                r.hintExit[i] = in.readInt();
                r.hintDist[i] = in.readInt();
            }
            return r;
        } finally {
            in.close();
        }
    }
}

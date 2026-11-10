package racing.coldshift.dash;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import java.io.File;
import java.nio.ByteBuffer;

/** Cuts a mark's footage out of the loop clips without re-encoding (cuts land on keyframes). */
final class Exporter implements Runnable {
    static volatile boolean busy;
    static volatile int busyId;
    static volatile String status = "";

    static void start(Dashcam d, MarkInfo m) {
        if (busy) return;
        busy = true;
        busyId = m.id;
        status = "Exporting mark " + m.id + "\u2026";
        new Thread(new Exporter(d, m)).start();
    }

    private final Dashcam dash;
    private final MarkInfo mark;

    private Exporter(Dashcam d, MarkInfo m) { dash = d; mark = m; }

    public void run() {
        File tmp = new File(mark.mp4.getParentFile(), mark.mp4.getName() + ".part");
        try {
            int n = export(tmp);
            if (n == 0) {
                tmp.delete();
                status = "Footage for mark " + mark.id + " is no longer on the card";
            } else if (tmp.renameTo(mark.mp4)) {
                status = "Exported mark " + mark.id + " (" + (mark.mp4.length() / (1024 * 1024)) + " MB)";
            } else {
                status = "Export failed: could not save the file";
            }
        } catch (Throwable t) {
            tmp.delete();
            status = "Export failed: " + t.getMessage();
            android.util.Log.w("Exporter", "failed", t);
        } finally {
            busy = false;
        }
    }

    /** Returns the number of spans written. */
    private int export(File out) throws Exception {
        MediaMuxer mux = null;
        int vOut = -1, aOut = -1, written = 0;
        long base = 0, lastPts = 0;
        ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int[] sp : mark.spans) {
            File clip = dash.clipFor(sp[0]);
            if (clip == null) continue;
            MediaExtractor ex = new MediaExtractor();
            try {
                ex.setDataSource(clip.getPath());
                int vIn = -1, aIn = -1;
                MediaFormat vf = null, af = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("video/") && vIn < 0) { vIn = i; vf = f; }
                    else if (mime.startsWith("audio/") && aIn < 0) { aIn = i; af = f; }
                }
                if (vIn < 0) continue;
                if (mux == null) {
                    mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    vOut = mux.addTrack(vf);
                    if (af != null) aOut = mux.addTrack(af);
                    mux.setOrientationHint(rotation(clip));
                    mux.start();
                }
                ex.selectTrack(vIn);
                if (aIn >= 0 && aOut >= 0) ex.selectTrack(aIn);
                ex.seekTo(sp[1] * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long outUs = sp[2] * 1000L, start = -1;
                while (true) {
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) break;
                    long t = ex.getSampleTime();
                    int ti = ex.getSampleTrackIndex();
                    if (ti == vIn && start < 0) start = t;
                    if (ti == vIn && t > outUs) break;
                    if (start >= 0 && t >= start && !(ti != vIn && t > outUs)) {
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = base + (t - start);
                        info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0;
                        mux.writeSampleData(ti == vIn ? vOut : aOut, buf, info);
                        if (info.presentationTimeUs > lastPts) lastPts = info.presentationTimeUs;
                    }
                    ex.advance();
                }
                base = lastPts + 34000;
                written++;
            } finally {
                ex.release();
            }
        }
        if (mux != null) {
            mux.stop();
            mux.release();
        }
        return written;
    }

    private static int rotation(File clip) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(clip.getPath());
            String v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Throwable t) {
            return 0;
        } finally {
            try { r.release(); } catch (Throwable t) { /* ignore */ }
        }
    }
}

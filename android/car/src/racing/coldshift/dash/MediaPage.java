package racing.coldshift.dash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class MediaPage extends Page {
    private static final float LIST_T = 162, LIST_B = 620, DIR_H = 74, TRK_H = 62;
    private static final float[][] BTN = {{145, 229}, {243, 327}, {341, 455}, {469, 553}, {567, 651}};
    private static boolean scannedOnce;

    private final Library lib;
    private final Player pl;
    private final Art art;
    private String folder;
    private List<Library.Entry> entries = new ArrayList<Library.Entry>();
    private final Map<String, Track> firstOf = new HashMap<String, Track>();
    private int builtCount = -1;
    private String builtFolder = "";
    private boolean builtAvail = true;
    private float scroll, fling;
    private boolean dragList, moved;
    private float downX, downY, lastY;
    private long downT;
    private VelocityTracker vt;
    private int dragging;            // 1 seek, 2 volume
    private float dragFrac;
    private boolean cardWas = true;

    MediaPage(Main a) {
        super(a);
        lib = Library.get(a);
        pl = Player.get(a);
        art = Art.get(a);
        folder = pl.browse.isEmpty() ? lib.root : pl.browse;
        if (!folder.startsWith(lib.root)) folder = lib.root;
    }

    String label() { return "Media"; }

    @Override int intervalMs() {
        if (Math.abs(fling) > 0.5f || dragList) return 33;
        return pl.isPlaying() ? 250 : 1000;
    }

    @Override void onShow() {
        art.setTarget(act.ui());
        if (!scannedOnce) { scannedOnce = true; lib.scan(); }
    }

    @Override void tick() {
        if (Math.abs(fling) > 0.5f) {
            scroll = clamp(scroll - fling);
            fling *= 0.93f;
        } else {
            fling = 0;
        }
        boolean avail = lib.rootAvailable();
        if (avail && !cardWas && !lib.scanning) lib.scan();
        cardWas = avail;
    }

    private float clamp(float s) {
        return Math.max(0, Math.min(s, Math.max(0, contentH() - (LIST_B - LIST_T))));
    }

    private float contentH() {
        float h = 0;
        for (Library.Entry e : entries) h += e.dir ? DIR_H : TRK_H;
        return h;
    }

    private void refresh() {
        int n = lib.all().size();
        boolean avail = lib.rootAvailable();
        if (n == builtCount && folder.equals(builtFolder) && avail == builtAvail) return;
        if (!folder.startsWith(lib.root)) folder = lib.root;
        entries = lib.entries(folder);
        firstOf.clear();
        for (Library.Entry e : entries) {
            if (e.dir) {
                List<Track> u = lib.under(e.path);
                if (!u.isEmpty()) firstOf.put(e.path, u.get(0));
            }
        }
        builtCount = n;
        builtFolder = folder;
        builtAvail = avail;
        scroll = clamp(scroll);
    }

    // ---- drawing ----

    @Override boolean wide() { return true; }

    /**
     * On a wide screen (Wide) the player gets half the extra width and the list the other half: the player's
     * content moves right by a quarter of it (staying centred), the list starts half of it later and is as
     * much wider. P() and Q() are those shifts; each part draws and takes touches in its own coordinates.
     */
    private static float P() { return Wide.d() / 4; }
    private static float Q() { return Wide.d() / 2; }

    void draw(Canvas c) {
        refresh();
        Theme.panel(c, 14, 68, 782 + 2 * P(), 625);
        c.save();
        c.translate(P(), 0);
        nowPlaying(c);
        c.restore();
        Theme.panel(c, 797 + Q(), 68, Wide.rx(1266), 625);
        c.save();
        c.translate(Q(), 0);
        browser(c);
        c.restore();
    }

    private static String mmss(int ms) {
        int s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private void nowPlaying(Canvas c) {
        Track t = pl.current();
        if (t == null) {
            Bitmap logo = Profile.wordmark(act);
            if (logo != null) {
                // within 410 x 86, its own shape kept
                float s = Math.min(410f / logo.getWidth(), 86f / logo.getHeight());
                float w = logo.getWidth() * s, h = logo.getHeight() * s;
                RectF r = new RectF(398 - w / 2, 193 - h / 2, 398 + w / 2, 193 + h / 2);
                c.drawBitmap(logo, null, r, Theme.BMP);
            }
            Theme.text(c, "No track selected", 398, 312, 31, 0xffffffff, Theme.bold, Theme.CENTER);
            Theme.text(c, "Pick a track on the right", 398, 344, 17, Theme.SUB, Theme.sans, Theme.CENTER);
        } else {
            Bitmap b = art.track(t, 280);
            RectF r = new RectF(32, 85, 312, 365);
            if (b != null) c.drawBitmap(b, null, r, Theme.BMP);
            else { Theme.P.setShader(null); Theme.P.setStyle(Paint.Style.FILL); Theme.P.setColor(0xff0c1830); c.drawRect(r, Theme.P); }
            Theme.P.setStyle(Paint.Style.STROKE);
            Theme.P.setStrokeWidth(1.5f);
            Theme.P.setColor(Theme.accent(0x4d));
            c.drawRect(r, Theme.P);
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.text(c, Theme.fit(t.displayTitle(), 430, 29, Theme.bold), 333, 195, 29, 0xffffffff, Theme.bold, Theme.LEFT);
            if (!t.artist.isEmpty())
                Theme.text(c, Theme.fit(t.artist, 430, 21, Theme.sans), 333, 228, 21, 0xffc9d6ea, Theme.sans, Theme.LEFT);
            if (!t.album.isEmpty())
                Theme.text(c, Theme.fit(t.album, 430, 16, Theme.sans), 333, 254, 16, Theme.DIM, Theme.sans, Theme.LEFT);
            String where = pl.queuePos() + " of " + pl.queueSize() + " \u00b7 " + new java.io.File(t.folder).getName();
            Theme.text(c, Theme.fit(where, 430, 13, Theme.sans), 333, 276, 13, Theme.DIMMER, Theme.sans, Theme.LEFT);
        }

        int dur = pl.duration(), pos = pl.position();
        float frac = dragging == 1 ? dragFrac : (dur > 0 ? pos / (float) dur : 0);
        Theme.text(c, mmss(dragging == 1 ? (int) (dragFrac * dur) : pos), 46, 407, 15, Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.text(c, mmss(dur), 750, 407, 15, Theme.SUB, Theme.sans, Theme.RIGHT);
        Theme.slider(c, 100, 691, 401, frac);

        for (int i = 0; i < BTN.length; i++) {
            RectF r = new RectF(BTN[i][0], 436, BTN[i][1], 504);
            boolean on = (i == 0 && pl.shuffle) || (i == 4 && pl.repeat != Player.REPEAT_OFF);
            float cx = (BTN[i][0] + BTN[i][1]) / 2, cy = 470;
            Theme.button(c, r, i == 2, 16);
            if (on) {
                Theme.P.setStyle(Paint.Style.STROKE);
                Theme.P.setStrokeWidth(1.6f);
                Theme.P.setColor(0x8ce3b341);
                c.drawRoundRect(r, 16, 16, Theme.P);
                Theme.P.setStyle(Paint.Style.FILL);
            }
            int col = i == 0 || i == 4 ? (on ? Theme.GOLD : 0xff5d7090) : 0xffdfe8f7;
            if (i == 0) Icons.shuffle(c, cx, cy, 1f, col);
            if (i == 1) Icons.skip(c, cx, cy, true, col);
            if (i == 2) { if (pl.isPlaying()) Icons.pause(c, cx, cy, 20, col); else Icons.play(c, cx, cy, 22, col); }
            if (i == 3) Icons.skip(c, cx, cy, false, col);
            if (i == 4) Icons.repeat(c, cx, cy, pl.repeat == Player.REPEAT_ONE, col);
        }
        Icons.speaker(c, 222, 537, 0xff6f86a6);
        Theme.slider(c, 258, 575, 537, dragging == 2 ? dragFrac : pl.volume());
    }

    /** In the list's coordinates; q is how much wider it is, which right-anchored things move by. */
    private void browser(Canvas c) {
        float q = Q();
        boolean atRoot = folder.equals(lib.root);
        float tx = 817;
        if (!atRoot) {
            RectF b = new RectF(813, 84, 869, 134);
            Theme.button(c, b, false, 12);
            Icons.chevron(c, 841, 109, true, 0xffe8eef7);
            tx = 881;
        }
        String title = atRoot ? lib.rootLabel() : new java.io.File(folder).getName();
        Theme.text(c, Theme.fit(title, (atRoot ? 300 : 236) + q, 21, Theme.bold), tx, 116, 21, 0xffe8eef7, Theme.bold, Theme.LEFT);
        if (!entries.isEmpty()) {
            RectF pa = new RectF(1129 + q, 84, 1185 + q, 134), sh = new RectF(1193 + q, 84, 1249 + q, 134);
            Theme.button(c, pa, false, 12);
            Icons.play(c, 1158 + q, 109, 13, 0xffe8eef7);
            Theme.button(c, sh, false, 12);
            Icons.shuffle(c, 1221 + q, 109, 0.8f, 0xffe8eef7);
        }
        String sub;
        if (lib.scanning) sub = "Scanning " + lib.scanned + " of " + lib.total + "\u2026";
        else sub = lib.countIn(folder) + " songs here \u00b7 " + lib.all().size() + " songs on the " + lib.rootLabel().toLowerCase();
        Theme.text(c, sub, 817, 151, 13, lib.scanning ? Theme.GOLD : Theme.DIM, Theme.sans, Theme.LEFT);

        c.save();
        c.clipRect(803, LIST_T, 1260 + q, LIST_B);
        if (entries.isEmpty()) {
            String msg = !lib.rootAvailable() ? "No " + lib.rootLabel().toLowerCase() + " found"
                : lib.scanning ? "Reading your music\u2026" : "No music found in " + new java.io.File(folder).getName();
            Theme.text(c, msg, 1031 + q / 2, 380, 17, Theme.SUB, Theme.sans, Theme.CENTER);
        }
        Track cur = pl.current();
        float y = LIST_T - scroll;
        for (Library.Entry e : entries) {
            float h = e.dir ? DIR_H : TRK_H;
            if (y + h > LIST_T && y < LIST_B) row(c, e, y, h, cur);
            y += h;
        }
        c.restore();
    }

    private void row(Canvas c, Library.Entry e, float y, float h, Track cur) {
        float q = Q();
        if (!e.dir && cur != null && cur.path.equals(e.track.path)) {
            Theme.P.setShader(null);
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(Theme.accent(0x42));
            c.drawRoundRect(new RectF(803, y + 2, 1260 + q, y + h - 2), 10, 10, Theme.P);
        }
        int th = e.dir ? 64 : 52;
        Bitmap b = e.dir ? art.folder(e.path, firstOf.get(e.path), th) : art.track(e.track, th);
        RectF r = new RectF(819, y + (h - th) / 2, 819 + th, y + (h - th) / 2 + th);
        if (b != null) c.drawBitmap(b, null, r, Theme.BMP);
        else { Theme.P.setShader(null); Theme.P.setStyle(Paint.Style.FILL); Theme.P.setColor(0xff0c1830); c.drawRect(r, Theme.P); }
        float x = 819 + th + 12;
        if (e.dir) {
            String n = e.name;
            float maxW = 1190 + q - x;
            String l1 = n, l2 = null;
            if (Theme.width(n, 17, Theme.sans) > maxW) {
                int cut = n.length();
                while (cut > 1 && Theme.width(n.substring(0, cut), 17, Theme.sans) > maxW) cut--;
                int sp = n.lastIndexOf(' ', cut);
                if (sp > 0) cut = sp;
                l1 = n.substring(0, cut).trim();
                l2 = Theme.fit(n.substring(cut).trim(), maxW, 17, Theme.sans);
            }
            if (l2 == null) {
                Theme.text(c, l1, x, y + 31, 17, 0xffe8eef7, Theme.sans, Theme.LEFT);
                Theme.text(c, e.count + (e.count == 1 ? " song" : " songs"), x, y + 53, 13, Theme.DIM, Theme.sans, Theme.LEFT);
            } else {
                Theme.text(c, l1, x, y + 28, 17, 0xffe8eef7, Theme.sans, Theme.LEFT);
                Theme.text(c, l2, x, y + 49, 17, 0xffe8eef7, Theme.sans, Theme.LEFT);
                Theme.text(c, e.count + (e.count == 1 ? " song" : " songs"), x, y + 68, 13, Theme.DIM, Theme.sans, Theme.LEFT);
            }
            Icons.chevron(c, 1230 + q, y + h / 2, false, 0xff6f86a6);
        } else {
            boolean playing = cur != null && cur.path.equals(e.track.path);
            Theme.text(c, Theme.fit(e.track.displayTitle(), 300 + q, 17, Theme.sans), x, y + 28, 17,
                playing ? Theme.GOLD : 0xffe8eef7, Theme.sans, Theme.LEFT);
            String sub = e.track.artist.isEmpty() ? "Unknown artist" : e.track.artist;
            Theme.text(c, Theme.fit(sub, 300 + q, 13, Theme.sans), x, y + 48, 13, Theme.DIM, Theme.sans, Theme.LEFT);
            Theme.text(c, mmss(e.track.durationMs), 1242 + q, y + 36, 13, Theme.DIM, Theme.sans, Theme.RIGHT);
        }
    }

    // ---- touch ----

    private static boolean in(float x, float y, float l, float t, float r, float b) {
        return x >= l && x <= r && y >= t && y <= b;
    }

    @Override boolean touch(MotionEvent e) {
        // in the touched part's own coordinates (as drawn: the list shifted by Q(), the player by P())
        float y = e.getY(), x = e.getX() >= 797 + Q() ? e.getX() - Q() : e.getX() - P();
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = x; downY = y; lastY = y; downT = System.currentTimeMillis(); moved = false;
            fling = 0;
            dragging = 0;
            dragList = false;
            if (in(x, y, 80, 372, 712, 432)) { dragging = 1; dragFrac = Math.max(0, Math.min(1, (x - 100) / 591f)); }
            else if (in(x, y, 240, 512, 600, 562)) { dragging = 2; dragFrac = Math.max(0, Math.min(1, (x - 258) / 317f)); pl.setVolume(dragFrac); }
            else if (in(x, y, 803, LIST_T, 1260 + Q(), LIST_B)) {
                dragList = true;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
            }
            return true;
        }
        if (a == MotionEvent.ACTION_MOVE) {
            if (dragging == 1) dragFrac = Math.max(0, Math.min(1, (x - 100) / 591f));
            else if (dragging == 2) { dragFrac = Math.max(0, Math.min(1, (x - 258) / 317f)); pl.setVolume(dragFrac); }
            else if (dragList) {
                if (vt != null) vt.addMovement(e);
                if (Math.abs(y - downY) > 12) moved = true;
                if (moved) { scroll = clamp(scroll - (y - lastY)); }
                lastY = y;
            }
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (dragging == 1 && a == MotionEvent.ACTION_UP) pl.seekTo((int) (dragFrac * pl.duration()));
            if (dragging != 0) { dragging = 0; return true; }
            if (dragList) {
                dragList = false;
                if (moved && vt != null) {
                    vt.addMovement(e);
                    vt.computeCurrentVelocity(1000);
                    fling = vt.getYVelocity() / 30f;
                    if (Math.abs(fling) < 3) fling = 0;
                }
                if (vt != null) { vt.recycle(); vt = null; }
                if (!moved && a == MotionEvent.ACTION_UP) listTap(x, y);
                return true;
            }
            if (a == MotionEvent.ACTION_UP) buttonTap(x, y);
        }
        return true;
    }

    private void buttonTap(float x, float y) {
        if (y >= 436 && y <= 504) {
            for (int i = 0; i < BTN.length; i++) {
                if (x < BTN[i][0] || x > BTN[i][1]) continue;
                if (i == 0) pl.toggleShuffle();
                else if (i == 1) pl.prev();
                else if (i == 2) pl.toggle();
                else if (i == 3) pl.next();
                else pl.cycleRepeat();
            }
            return;
        }
        if (in(x, y, 813, 84, 869, 134) && !folder.equals(lib.root)) {
            String p = new java.io.File(folder).getParent();
            folder = p == null || !p.startsWith(lib.root) ? lib.root : p;
            pl.browse = folder;
            scroll = 0;
            return;
        }
        if (!entries.isEmpty()) {
            if (in(x, y, 1129 + Q(), 84, 1185 + Q(), 134)) { pl.play(lib.under(folder), 0, false); }
            else if (in(x, y, 1193 + Q(), 84, 1249 + Q(), 134)) { pl.play(lib.under(folder), 0, true); }
        }
    }

    private void listTap(float x, float y) {
        float top = LIST_T - scroll;
        for (Library.Entry e : entries) {
            float h = e.dir ? DIR_H : TRK_H;
            if (y >= top && y < top + h) {
                if (e.dir) {
                    folder = e.path;
                    pl.browse = folder;
                    scroll = 0;
                } else {
                    List<Track> here = new ArrayList<Track>();
                    int start = 0;
                    for (Library.Entry o : entries) {
                        if (o.dir) continue;
                        if (o == e) start = here.size();
                        here.add(o.track);
                    }
                    boolean sh = pl.shuffle;
                    pl.play(here, start, sh);
                }
                return;
            }
            top += h;
        }
    }
}

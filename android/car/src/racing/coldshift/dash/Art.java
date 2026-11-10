package racing.coldshift.dash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.LruCache;
import android.view.View;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cover art for tracks and folders, loaded off the UI thread and cached in memory. */
final class Art {
    private static Art inst;

    static synchronized Art get(Context c) {
        if (inst == null) inst = new Art(c.getApplicationContext());
        return inst;
    }

    private static class Cache extends LruCache<String, Bitmap> {
        Cache(int bytes) { super(bytes); }
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    }

    private final Library lib;
    private final Cache cache = new Cache(10 * 1024 * 1024);
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Set<String> pending = new HashSet<String>();
    private volatile View target;

    private Art(Context c) { lib = Library.get(c); }

    void setTarget(View v) { target = v; }

    /** Bitmap for a track at px x px, or null while it loads (the view is invalidated when ready). */
    Bitmap track(Track t, int px) {
        String name = t.album.isEmpty() ? new File(t.folder).getName() : t.album;
        String key = (t.artKey.isEmpty() ? "g:" + t.folder + "|" + name : "k:" + t.artKey) + "@" + px;
        return fetch(key, t, null, name, px);
    }

    Bitmap folder(String path, Track first, int px) {
        return fetch("d:" + path + "@" + px, first, path, new File(path).getName(), px);
    }

    private Bitmap fetch(String key, Track t, String dir, String name, int px) {
        Bitmap b = cache.get(key);
        if (b != null) return b;
        synchronized (pending) {
            if (pending.contains(key)) return null;
            pending.add(key);
        }
        pool.execute(new Job(key, t, dir, name, px));
        return null;
    }

    private class Job implements Runnable {
        final String key, dir, name;
        final Track t;
        final int px;
        Job(String key, Track t, String dir, String name, int px) {
            this.key = key; this.t = t; this.dir = dir; this.name = name; this.px = px;
        }
        public void run() {
            Bitmap src = null;
            try {
                if (dir != null) src = folderImage(new File(dir));
                if (src == null && t != null && !t.artKey.isEmpty()) {
                    File f = lib.artFile(t.artKey);
                    if (f.exists()) src = BitmapFactory.decodeFile(f.getPath());
                }
                if (src == null && t != null && dir == null) src = folderImage(new File(t.folder));
                if (src == null) src = generated(name, 320);
                Bitmap out = src.getWidth() == px ? src : Bitmap.createScaledBitmap(src, px, px, true);
                cache.put(key, out);
            } catch (Throwable e) {
                // leave it blank; try again next draw
            } finally {
                synchronized (pending) { pending.remove(key); }
                View v = target;
                if (v != null) v.postInvalidate();
            }
        }
    }

    private static Bitmap folderImage(File dir) {
        String[] names = {"cover", "folder", "front", "album", "albumart"};
        String[] exts = {".jpg", ".jpeg", ".png"};
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        File only = null;
        int images = 0;
        for (File f : kids) {
            String n = f.getName().toLowerCase();
            if (n.startsWith(".")) continue;
            for (String e : exts) {
                if (!n.endsWith(e)) continue;
                images++;
                only = f;
                for (String base : names) if (n.equals(base + e)) return squareFile(f);
            }
        }
        return images == 1 ? squareFile(only) : null;
    }

    private static Bitmap squareFile(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (o.outWidth / (s * 2) >= 320 && o.outHeight / (s * 2) >= 320) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        int side = Math.min(b.getWidth(), b.getHeight());
        Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
        return Bitmap.createScaledBitmap(sq, 320, 320, true);
    }

    /** A drawn cover for music with no artwork: blue gradient, star, gold rule and the name. */
    static Bitmap generated(String name, int size) {
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, size, size, 0xff1e5bd6, 0xff081a46, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
        p.setShader(null);
        float k = size / 320f;
        Path star = new Path();
        float cx = size / 2f, cy = size * 0.30f, r = 44 * k, q = r * 0.24f;
        star.moveTo(cx, cy - r); star.lineTo(cx + q, cy - q); star.lineTo(cx + r, cy); star.lineTo(cx + q, cy + q);
        star.lineTo(cx, cy + r); star.lineTo(cx - q, cy + q); star.lineTo(cx - r, cy); star.lineTo(cx - q, cy - q);
        star.close();
        p.setColor(0xe6ffffff);
        c.drawPath(star, p);
        p.setColor(Theme.GOLD);
        c.drawRect(size * 0.30f, size * 0.52f, size * 0.70f, size * 0.52f + 3 * k, p);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        p.setTextSize(30 * k);
        p.setTextAlign(Paint.Align.CENTER);
        String[] words = (name == null || name.isEmpty() ? "Music" : name).split("\\s+");
        StringBuilder line = new StringBuilder();
        float y = size * 0.66f;
        int lines = 0;
        for (String w : words) {
            String trial = line.length() == 0 ? w : line + " " + w;
            if (p.measureText(trial) > size * 0.86f && line.length() > 0) {
                c.drawText(line.toString(), cx, y, p);
                y += 36 * k;
                line.setLength(0);
                line.append(w);
                if (++lines >= 3) break;
            } else {
                line.setLength(0);
                line.append(trial);
            }
        }
        if (lines < 3 && line.length() > 0) c.drawText(line.toString(), cx, y, p);
        return bm;
    }
}

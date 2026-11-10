package racing.coldshift.dash;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The vehicle's look: <volume>/coldshift.dash/profile/ from the desktop tool (dashsync/profile.py), else
 * the built-in one (assets/profile/, the neutral coldshift.dash look, made by tools/make_default_profile.py).
 *
 *     profile.json  {"version": 1, "title": "PEPPERMINT CRISP",
 *                    "badges": ["badge-1.png", ...],        title bar, left to right, drawn 42 px high
 *                    "background": "background.jpg",        behind every page (1280 x 720)
 *                    "wordmark": "wordmark.png",            Media page, while nothing plays
 *                    "car": "car.png",                      top-down, front up: Sensors' tyres and map marker
 *                    "splash": {"background": "splash.jpg", "logo": "logo.png",
 *                               "effect": "shine" | "glow" | "rise" | "none", "position": "left" | "centre"},
 *                    "theme": {"accent": "#2f7bff", "panel", "text", "subtext", "highlight"},   Theme.apply
 *                    "pages": ["media", "maps", "track", "sensors", "dashcam", "log", "convoy"]}   the tab bar, in
 *                                                                             order; Settings always comes last
 *                                                                             ("obd" or "tpms" from before mean sensors)
 *
 * A file the profile names but lacks falls back to the built-in one. A card with only car.png (from before
 * profile.json) gets the built-in look with that car.
 */
final class Profile {
    private static final String ASSETS = "profile/";

    private static boolean loaded;
    private static File dir;                   // the card's profile folder, or null for the built-in one
    private static JSONObject json;
    private static String title = "";
    private static final List<Bitmap> badges = new ArrayList<Bitmap>();
    private static Bitmap background, wordmark, car, carBody;

    private Profile() {}

    private static synchronized void load(Main act) {
        if (loaded) return;
        loaded = true;
        dir = null;
        json = readJson(act, null);
        for (String v : Storage.volumes()) {
            File d = new File(v, Storage.DIR + "/profile");
            if (new File(d, "profile.json").exists()) {
                JSONObject j = readJson(act, d);
                if (j != null) {
                    dir = d;
                    json = j;
                }
                break;
            }
        }
        if (json == null) json = new JSONObject();
        Theme.apply(json.optJSONObject("theme"));
        title = json.optString("title", "");
        badges.clear();
        JSONArray b = json.optJSONArray("badges");
        for (int i = 0; b != null && i < b.length(); i++) {
            Bitmap bm = bitmap(act, b.optString(i));
            if (bm != null) badges.add(bm);
        }
    }

    private static JSONObject readJson(Main act, File d) {
        try {
            InputStream in = d == null ? act.getAssets().open(ASSETS + "profile.json") : new FileInputStream(new File(d, "profile.json"));
            byte[] buf = new byte[64 * 1024];
            int n = 0, r;
            while ((r = in.read(buf, n, buf.length - n)) > 0) n += r;
            in.close();
            return new JSONObject(new String(buf, 0, n, "UTF-8"));
        } catch (Exception e) {
            android.util.Log.w("Profile", "cannot read the profile in " + (d == null ? "the app" : d.getPath()), e);
            return null;
        }
    }

    /** A file the profile names: from the card's folder, else the built-in one of the same name. */
    private static Bitmap bitmap(Main act, String name) {
        if (name == null || name.isEmpty() || name.contains("/")) return null;
        if (dir != null) {
            File f = new File(dir, name);
            if (f.exists()) {
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b != null) return b;
            }
        }
        return act.asset(ASSETS + name);
    }

    private static String name(String key, String fallback) { return json.optString(key, fallback); }

    static synchronized String title(Main act) { load(act); return title; }

    static final String[] PAGES = {"media", "maps", "track", "sensors", "dashcam", "log", "convoy"};

    /** The pages for the tab bar, in order (Settings is added after them by UI). All of them without a list. */
    static synchronized List<String> pages(Main act) {
        load(act);
        List<String> out = new ArrayList<String>();
        JSONArray a = json.optJSONArray("pages");
        if (a == null) {
            for (String p : PAGES) out.add(p);
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            String p = a.optString(i);
            if (p.equals("obd") || p.equals("tpms")) p = "sensors";       // one tab since 1 Nov
            if (java.util.Arrays.asList(PAGES).contains(p) && !out.contains(p)) out.add(p);
        }
        return out;
    }

    static synchronized List<Bitmap> badges(Main act) { load(act); return badges; }

    static synchronized Bitmap background(Main act) {
        load(act);
        if (background == null) background = bitmap(act, name("background", "background.jpg"));
        return background;
    }

    static synchronized Bitmap wordmark(Main act) {
        load(act);
        if (wordmark == null) wordmark = bitmap(act, name("wordmark", "wordmark.png"));
        return wordmark;
    }

    static synchronized Bitmap car(Main act) {
        load(act);
        if (car == null) {
            // a card from before profile.json may hold just the car
            if (dir == null) {
                for (String v : Storage.volumes()) {
                    File f = new File(v, Storage.DIR + "/profile/car.png");
                    if (f.exists()) car = BitmapFactory.decodeFile(f.getPath());
                    if (car != null) break;
                }
            }
            if (car == null) car = bitmap(act, name("car", "car.png"));
        }
        return car;
    }

    /** The car cropped to its solid body (no glow or margin), for drawing it small. */
    static synchronized Bitmap carBody(Main act) {
        if (carBody != null) return carBody;
        Bitmap b = car(act);
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, bo = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) <= 200) continue;
                if (x < l) l = x;
                if (x > r) r = x;
                if (y < t) t = y;
                if (y > bo) bo = y;
            }
        }
        carBody = r < l ? b : Bitmap.createBitmap(b, l, t, r - l + 1, bo - t + 1);
        return carBody;
    }

    /** The splash: background and logo bitmaps (loaded each time; the splash shows once), effect, position. */
    static final class Splash {
        Bitmap background, logo;
        String effect = "shine", position = "left";
    }

    static synchronized Splash splash(Main act) {
        load(act);
        JSONObject s = json.optJSONObject("splash");
        if (s == null) s = new JSONObject();
        Splash out = new Splash();
        out.background = bitmap(act, s.optString("background", "splash.jpg"));
        out.logo = bitmap(act, s.optString("logo", "logo.png"));
        out.effect = s.optString("effect", "shine");
        out.position = s.optString("position", "left");
        return out;
    }

    /** After a sync: read the profile again. */
    static synchronized void reload() {
        loaded = false;
        background = wordmark = car = carBody = null;
        badges.clear();
    }
}

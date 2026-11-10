package racing.coldshift.dash;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Whatever this phone's camera says it can do, as settings (Dashcam, Camera tab). Nothing here names a phone: the
 * camera's own parameter list is read, and every setting with a choice becomes a row, makers' own settings included.
 *
 * A choice is "x" with "x-values" beside it, or "x" of true or false with "x-supported=true". Exposure compensation
 * and the frame rate range say what they can do in their own ways. Sizes and formats are the recorder's business,
 * and the flash is no use while driving, so they are left out.
 *
 * A test drive (Dashcam.startTest) changes one option at a time against the saved settings.
 */
final class CameraOptions {
    private CameraOptions() {}

    private static final String SAVED = "dc_cam_opts", TESTED = "dc_cam_test";
    private static final String FPS = "preview-fps-range", EV = "exposure-compensation";
    private static final List<String> LEFT_OUT = Arrays.asList("preview-size", "picture-size", "video-size", "jpeg-thumbnail-size",
        "preview-format", "picture-format", "preview-frame-rate", "video-frame-format", "flash-mode");
    private static final int MOST_PER_OPTION = 3;

    static final class Opt {
        String key, label, current;
        String[] values;
        float evStep;

        String name(String v) {
            if ("true".equals(v)) return "On";
            if ("false".equals(v)) return "Off";
            if (key.equals(FPS)) {
                String[] p = v.split(",");
                if (p.length == 2) {
                    int lo = Math.round(Float.parseFloat(p[0]) / 1000), hi = Math.round(Float.parseFloat(p[1]) / 1000);
                    return lo == hi ? hi + " fps fixed" : lo + " to " + hi + " fps";
                }
            }
            if (key.equals(EV)) return String.format(Locale.US, "%+.1f EV", Integer.parseInt(v) * evStep);
            return v;
        }
    }

    /** One clip's settings in a test drive: the changes from the saved settings, and what to call them. */
    static final class Variant {
        final String label;
        final Map<String, String> settings = new LinkedHashMap<String, String>();
        Variant(String label) { this.label = label; }
    }

    static List<Opt> parse(String flat) {
        Map<String, String> all = new HashMap<String, String>();
        for (String kv : flat.split(";")) {
            int eq = kv.indexOf('=');
            if (eq > 0) all.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        List<Opt> out = new ArrayList<Opt>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            Opt o = null;
            if (k.endsWith("-values")) {
                String base = k.substring(0, k.length() - 7);
                if (!all.containsKey(base) || LEFT_OUT.contains(base)) continue;
                o = opt(base, all.get(base), base.equals(FPS) ? fpsRanges(v) : v.split(","));
            } else if (k.endsWith("-supported") && "true".equals(v)) {
                String base = k.substring(0, k.length() - 10), cur = all.get(base);
                if ("true".equals(cur) || "false".equals(cur)) o = opt(base, cur, new String[] {"false", "true"});
            }
            if (o != null && o.values.length >= 2) out.add(o);
        }
        Opt ev = exposure(all);
        if (ev != null) out.add(ev);
        Collections.sort(out, new java.util.Comparator<Opt>() {
            public int compare(Opt a, Opt b) {
                int ra = isLikelyTest(a) ? 0 : 1, rb = isLikelyTest(b) ? 0 : 1;
                return ra != rb ? ra - rb : a.label.compareTo(b.label);
            }
        });
        return out;
    }

    private static Opt opt(String key, String current, String[] values) {
        Opt o = new Opt();
        o.key = key;
        o.current = current;
        o.values = values;
        String words = key.replace('-', ' ').replace('_', ' ');
        o.label = words.substring(0, 1).toUpperCase(Locale.US) + words.substring(1);
        return o;
    }

    /** "(5000,30000),(30000,30000)" into "5000,30000" and "30000,30000". */
    private static String[] fpsRanges(String v) {
        List<String> out = new ArrayList<String>();
        for (String part : v.split("\\)\\s*,?\\s*\\(?")) {
            String p = part.replace("(", "").replace(")", "").trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out.toArray(new String[0]);
    }

    /** Half-stop choices across what the camera allows. */
    private static Opt exposure(Map<String, String> all) {
        try {
            int min = Integer.parseInt(all.get("min-" + EV)), max = Integer.parseInt(all.get("max-" + EV));
            float step = Float.parseFloat(all.get(EV + "-step"));
            if (step <= 0 || min >= max) return null;
            int by = Math.max(1, Math.round(0.5f / step));
            List<String> v = new ArrayList<String>();
            for (int i = -(-min / by) * by; i <= max; i += by) v.add(String.valueOf(i));
            Opt o = opt(EV, all.containsKey(EV) ? all.get(EV) : "0", v.toArray(new String[0]));
            o.evStep = step;
            return o;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- what's chosen ----

    private static SharedPreferences sp(Context c) { return Prefs.sp(c); }

    static Map<String, String> saved(Context c) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            JSONObject o = new JSONObject(sp(c).getString(SAVED, "{}"));
            for (java.util.Iterator<String> i = o.keys(); i.hasNext(); ) {
                String k = i.next();
                out.put(k, o.getString(k));
            }
        } catch (Exception e) { /* nothing chosen */ }
        return out;
    }

    static String value(Context c, Opt o) {
        String v = saved(c).get(o.key);
        return v != null ? v : o.current;
    }

    static void cycle(Context c, Opt o) {
        int at = Arrays.asList(o.values).indexOf(value(c, o));
        Map<String, String> s = saved(c);
        s.put(o.key, o.values[(at + 1) % o.values.length]);
        sp(c).edit().putString(SAVED, new JSONObject(s).toString()).apply();
    }

    /** Stabilisation and the frame rate go into a test drive unless changed; exposure too, for less blur. */
    private static boolean isLikelyTest(Opt o) {
        return o.key.matches(".*(stabili|shake|vdis|steady).*|(.*-)?(ois|dis)(-.*)?") || o.key.equals(FPS) || o.key.equals(EV);
    }

    static boolean inTest(Context c, Opt o) {
        String list = sp(c).getString(TESTED, null);
        if (list == null) return isLikelyTest(o);
        return testKeys(list).contains(o.key);
    }

    private static List<String> testKeys(String json) {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception e) { /* none */ }
        return out;
    }

    static void toggleTest(Context c, List<Opt> all, Opt o) {
        JSONArray a = new JSONArray();
        boolean now = !inTest(c, o);
        for (Opt x : all) if (x == o ? now : inTest(c, x)) a.put(x.key);
        sp(c).edit().putString(TESTED, a.toString()).apply();
    }

    /** The saved settings first, then one change each for what's ticked. */
    static List<Variant> variants(Context c, List<Opt> all) {
        List<Variant> out = new ArrayList<Variant>();
        out.add(new Variant("Base settings"));
        for (Opt o : all) {
            if (!inTest(c, o)) continue;
            for (String v : testValues(o, value(c, o))) {
                Variant t = new Variant(o.label + ": " + o.name(v));
                t.settings.put(o.key, v);
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> testValues(Opt o, String base) {
        List<String> out = new ArrayList<String>();
        if (o.key.equals(EV)) {                         // darker means a faster shutter: the nearest two steps down
            for (int i = o.values.length - 1; i >= 0 && out.size() < 2; i--)
                if (Integer.parseInt(o.values[i]) < Integer.parseInt(base)) out.add(o.values[i]);
            return out;
        }
        int top = 0;
        if (o.key.equals(FPS)) for (String v : o.values) top = Math.max(top, Integer.parseInt(v.split(",")[1]));
        for (String v : o.values) {
            if (v.equals(base) || (o.key.equals(FPS) && Integer.parseInt(v.split(",")[1]) != top)) continue;
            if (out.size() < MOST_PER_OPTION) out.add(v);
        }
        return out;
    }
}

package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Colours and drawing helpers shared by every page. Coordinates are 1280x720 px.
 *
 * The vehicle's profile can set five colours (profile.json "theme": accent, panel, text, subtext,
 * highlight, as "#rrggbb"); the rest are made from them (apply). Without a theme the colours are these.
 */
final class Theme {
    static int BLUE = 0xff2f7bff, BLUE_HI = 0xff4d8dff;           // the accent, and a lighter one
    static int GOLD = 0xffe3b341;                                  // highlight: MARK, amber notes
    static final int RED = 0xffe4002b;
    static int TEXT = 0xffe8eef7, SUB = 0xff93a9c8, DIM = 0xff7c8ea6, DIMMER = 0xff6f86a6;
    static final int GREEN = 0xff46d17a, AMBER = 0xffe3b341, BAD = 0xfffc8181;
    static int PANEL = 0xdb070e1b;                // rgba(7,14,27,0.86)
    static int EDGE = 0x382f7bff;                 // the accent at 0.22
    static int BTN = 0xf2101e36;                  // rgba(16,30,54,0.95)
    static int BTN_TOP = 0xff1e5bd6, BTN_BOTTOM = 0xff0b3d91;      // an active button's gradient

    /** The accent at an opacity (0..255). */
    static int accent(int alpha) { return (alpha << 24) | (BLUE & 0xffffff); }

    private static int scale(int c, float k) {
        int r = Math.min(255, Math.round(((c >> 16) & 255) * k)), g = Math.min(255, Math.round(((c >> 8) & 255) * k));
        int b = Math.min(255, Math.round((c & 255) * k));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int mix(int c, int to, float k) {
        int r = Math.round(((c >> 16) & 255) * (1 - k) + ((to >> 16) & 255) * k);
        int g = Math.round(((c >> 8) & 255) * (1 - k) + ((to >> 8) & 255) * k);
        int b = Math.round((c & 255) * (1 - k) + (to & 255) * k);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int colour(org.json.JSONObject t, String key, int fallback) {
        String v = t == null ? "" : t.optString(key, "");
        try {
            return v.matches("#[0-9a-fA-F]{6}") ? 0xff000000 | Integer.parseInt(v.substring(1), 16) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Sets the colours from a profile's "theme" (null: the defaults). */
    static void apply(org.json.JSONObject t) {
        int accent = colour(t, "accent", 0xff2f7bff), panel = colour(t, "panel", 0xff070e1b);
        BLUE = accent;
        BLUE_HI = mix(accent, 0xffffffff, 0.15f);
        EDGE = accent(0x38);
        BTN_TOP = scale(accent, 0.78f);
        BTN_BOTTOM = scale(accent, 0.42f);
        PANEL = 0xdb000000 | (panel & 0xffffff);
        BTN = 0xf2000000 | (mix(panel, accent, 0.12f) & 0xffffff);
        TEXT = colour(t, "text", 0xffe8eef7);
        SUB = colour(t, "subtext", 0xff93a9c8);
        DIM = mix(SUB, 0xff000000, 0.15f);
        DIMMER = mix(SUB, 0xff000000, 0.25f);
        GOLD = colour(t, "highlight", 0xffe3b341);
    }

    static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint BMP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    static final RectF R = new RectF();
    static final Path PATH = new Path();

    static final int LEFT = 0, CENTER = 1, RIGHT = 2;

    static Typeface sans = Typeface.create("sans-serif", Typeface.NORMAL);
    static Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    static Typeface italic = Typeface.create("sans-serif", Typeface.ITALIC);
    static Typeface boldItalic = Typeface.create("sans-serif", Typeface.BOLD_ITALIC);

    private Theme() {}

    static void text(Canvas c, String s, float x, float y, float size, int color, Typeface tf, int align) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setTypeface(tf);
        P.setTextSize(size);
        P.setColor(color);
        if (align == CENTER) x -= P.measureText(s) / 2;
        else if (align == RIGHT) x -= P.measureText(s);
        c.drawText(s, x, y, P);
    }

    static float width(String s, float size, Typeface tf) {
        P.setTypeface(tf);
        P.setTextSize(size);
        return P.measureText(s);
    }

    /** Text with extra tracking between letters. */
    static float spaced(Canvas c, String s, float x, float y, float size, int color, Typeface tf, float track) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setTypeface(tf);
        P.setTextSize(size);
        P.setColor(color);
        for (int i = 0; i < s.length(); i++) {
            String ch = s.substring(i, i + 1);
            c.drawText(ch, x, y, P);
            x += P.measureText(ch) + track;
        }
        return x;
    }

    static String fit(String s, float maxW, float size, Typeface tf) {
        if (s == null) return "";
        P.setTypeface(tf);
        P.setTextSize(size);
        if (P.measureText(s) <= maxW) return s;
        while (s.length() > 1 && P.measureText(s + "\u2026") > maxW) s = s.substring(0, s.length() - 1);
        return s + "\u2026";
    }

    static void panel(Canvas c, float l, float t, float r, float b) {
        R.set(l, t, r, b);
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(PANEL);
        c.drawRoundRect(R, 14, 14, P);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.2f);
        P.setColor(EDGE);
        c.drawRoundRect(R, 14, 14, P);
        P.setStyle(Paint.Style.FILL);
    }

    /** Rounded button. accent = the blue gradient "active" look. */
    static void button(Canvas c, RectF r, boolean accent, float radius) {
        P.setStyle(Paint.Style.FILL);
        if (accent) {
            P.setShader(new LinearGradient(0, r.top, 0, r.bottom, BTN_TOP, BTN_BOTTOM, Shader.TileMode.CLAMP));
        } else {
            P.setShader(null);
            P.setColor(BTN);
        }
        c.drawRoundRect(r, radius, radius, P);
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.2f);
        P.setColor(accent ? BLUE_HI : EDGE);
        c.drawRoundRect(r, radius, radius, P);
        P.setStyle(Paint.Style.FILL);
        pressed(c, r, radius);
    }

    /** Lights the button at `r` while a finger is on it (Press); every button and chip drawn calls this. */
    static void pressed(Canvas c, RectF r, float radius) {
        if (!Press.on(c, r)) return;
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(0x38ffffff);
        c.drawRoundRect(r, radius, radius, P);
    }

    static void chip(Canvas c, float l, float t, float r, float b, String label, boolean active, int color) {
        R.set(l, t, r, b);
        P.setStyle(Paint.Style.FILL);
        P.setShader(null);
        P.setColor(active ? BLUE : BTN);
        c.drawRoundRect(R, 10, 10, P);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.2f);
        P.setColor(active ? BLUE : (color != 0 ? (color & 0x00ffffff) | 0x99000000 : EDGE));
        c.drawRoundRect(R, 10, 10, P);
        P.setStyle(Paint.Style.FILL);
        pressed(c, R, 10);
        float size = b - t >= 50 ? 20 : 18;
        text(c, label, (l + r) / 2, (t + b) / 2 + size * 0.36f, size, active ? 0xffffffff : (color != 0 ? color : TEXT), sans, CENTER);
    }

    static void slider(Canvas c, float x0, float x1, float cy, float frac) {
        frac = Math.max(0, Math.min(1, frac));
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        R.set(x0, cy - 5, x1, cy + 5);
        P.setColor(accent(0x38));
        c.drawRoundRect(R, 5, 5, P);
        float kx = x0 + (x1 - x0) * frac;
        R.set(x0, cy - 5, kx, cy + 5);
        P.setColor(BLUE);
        c.drawRoundRect(R, 5, 5, P);
        P.setColor(0xffe8eef7);
        c.drawCircle(kx, cy, 14, P);
        P.setColor(0xffffffff);
        c.drawCircle(kx, cy, 12, P);
    }

    static void bolt(Canvas c, float x, float y, float h, int color) {
        float w = h * 0.55f;
        PATH.reset();
        PATH.moveTo(x + w * 0.62f, y);
        PATH.lineTo(x, y + h * 0.58f);
        PATH.lineTo(x + w * 0.42f, y + h * 0.58f);
        PATH.lineTo(x + w * 0.25f, y + h);
        PATH.lineTo(x + w, y + h * 0.38f);
        PATH.lineTo(x + w * 0.56f, y + h * 0.38f);
        PATH.close();
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        c.drawPath(PATH, P);
    }
}

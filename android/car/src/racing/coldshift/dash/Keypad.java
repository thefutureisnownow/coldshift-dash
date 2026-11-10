package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Typing a number or a few words over the whole screen, with keys big enough for the car: a number pad,
 * or letters with tappable suggestions (earlier answers) above them. Closes with OK or Cancel.
 */
final class Keypad extends View {
    interface Done {
        /** The text, or null when cancelled. */
        void done(String value);
    }

    private static final RectF FIELD = new RectF(16, 70, 940, 160);
    private static final RectF OK_TEXT = new RectF(956, 70, 1104, 160), CANCEL_TEXT = new RectF(1116, 70, 1264, 160);
    private static final RectF OK_NUM = new RectF(980, 190, 1264, 398), CANCEL_NUM = new RectF(980, 410, 1264, 618);
    private static final float KB_TOP = 414, KEY_H = 66, KEY_GAP = 8;
    private static final String[][] LETTERS = {
        {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
        {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
        {"z", "x", "c", "v", "b", "n", "m"}};
    private static final String[][] SYMBOLS = {
        {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
        {"-", "'", "/", "&", ",", ".", "(", ")", "#"},
        {"@", "+", "!", "?", ":", ";", "\""}};
    private static final String[] DIGITS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫"};
    private static final String BACK = "⌫", SPACE = " ", MODE = "mode";

    private final Main act;
    private final boolean numeric;
    private final String title, unit;
    private final List<String> suggestions;
    private final Done done;
    private final StringBuilder text = new StringBuilder();
    private final List<RectF> keyRects = new ArrayList<RectF>();
    private final List<String> keys = new ArrayList<String>();
    private boolean symbols, fresh = true, plain;
    private int pressed = -1;

    static void number(Main a, String title, String unit, String value, Done d) {
        show(a, new Keypad(a, true, title, unit, value, null, d));
    }

    static void text(Main a, String title, String value, List<String> suggestions, Done d) {
        show(a, new Keypad(a, false, title, "", value, suggestions, d));
    }

    /** An address (a host, a link): as typed, no capital letter first. */
    static void address(Main a, String title, String value, List<String> suggestions, Done d) {
        Keypad k = new Keypad(a, false, title, "", value, suggestions, d);
        k.plain = true;
        show(a, k);
    }

    private static void show(Main a, Keypad k) {
        a.root().addView(k, new android.widget.FrameLayout.LayoutParams(-1, -1));
    }

    private Keypad(Main a, boolean numeric, String title, String unit, String value, List<String> s, Done d) {
        super(a);
        act = a;
        this.numeric = numeric;
        this.title = title;
        this.unit = unit;
        this.suggestions = s == null ? new ArrayList<String>() : s;
        done = d;
        text.append(value == null ? "" : value);
        layoutKeys();
    }

    private void layoutKeys() {
        keyRects.clear();
        keys.clear();
        if (numeric) {
            float w = 200, kh = 98, gap = 12, x0 = 330;
            for (int i = 0; i < DIGITS.length; i++) {
                float x = x0 + (i % 3) * (w + gap), y = 190 + (i / 3) * (kh + gap);
                keys.add(DIGITS[i]);
                keyRects.add(new RectF(x, y, x + w, y + kh));
            }
            return;
        }
        String[][] set = symbols ? SYMBOLS : LETTERS;
        float w = (1248 - 9 * KEY_GAP) / 10f, u = w + KEY_GAP;
        for (int r = 0; r < 3; r++) {
            float y = KB_TOP + r * (KEY_H + KEY_GAP);
            float x = 16 + (r == 1 ? u / 2 : r == 2 ? u * 1.5f : 0);
            for (String k : set[r]) {
                add(k, x, y, w);
                x += u;
            }
            if (r == 2) add(BACK, x, y, 1264 - x);
        }
        float y = KB_TOP + 3 * (KEY_H + KEY_GAP);
        add(MODE, 16, y, u * 2 - KEY_GAP);
        add(SPACE, 16 + u * 2, y, 1264 - (16 + u * 2));
    }

    private void add(String k, float x, float y, float w) {
        keys.add(k);
        keyRects.add(new RectF(x, y, x + w, y + KEY_H));
    }

    private RectF suggestion(int i) {
        float w = (1248 - 2 * 12) / 3f, x = 16 + (i % 3) * (w + 12), y = 190 + (i / 3) * 74;
        return new RectF(x, y, x + w, y + 62);
    }

    private int shownSuggestions() { return Math.min(suggestions.size(), 9); }

    @Override protected void onDraw(Canvas c) {
        c.save();
        Screen.begin(c, this);
        c.drawColor(0xf205070d);
        Theme.text(c, title, 18, 46, 24, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.button(c, FIELD, false, 12);
        String shown = text.length() == 0 ? "" : text.toString();
        Theme.text(c, Theme.fit(shown, FIELD.width() - 60 - Theme.width(unit, 24, Theme.sans), 44, Theme.bold),
            FIELD.left + 24, FIELD.centerY() + 16, 44, fresh && numeric ? Theme.SUB : 0xffffffff, Theme.bold, Theme.LEFT);
        if (!unit.isEmpty()) Theme.text(c, unit, FIELD.right - 24, FIELD.centerY() + 10, 24, Theme.SUB, Theme.sans, Theme.RIGHT);
        RectF ok = numeric ? OK_NUM : OK_TEXT, cancel = numeric ? CANCEL_NUM : CANCEL_TEXT;
        Theme.button(c, ok, true, 12);
        Theme.text(c, "OK", ok.centerX(), ok.centerY() + 10, 28, 0xffffffff, Theme.bold, Theme.CENTER);
        Theme.button(c, cancel, false, 12);
        Theme.text(c, "Cancel", cancel.centerX(), cancel.centerY() + 8, 22, Theme.SUB, Theme.sans, Theme.CENTER);
        if (!numeric) {
            for (int i = 0; i < shownSuggestions(); i++) {
                RectF r = suggestion(i);
                Theme.button(c, r, false, 10);
                Theme.text(c, Theme.fit(suggestions.get(i), r.width() - 30, 20, Theme.sans), r.centerX(), r.centerY() + 7,
                    20, Theme.TEXT, Theme.sans, Theme.CENTER);
            }
        }
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            RectF r = keyRects.get(i);
            Theme.button(c, r, i == pressed, 10);
            boolean special = k.equals(BACK) || k.equals(MODE) || k.equals(SPACE);
            String label = k.equals(MODE) ? (symbols ? "ABC" : "123") : k.equals(SPACE) ? "space" : k.equals(BACK) ? "delete" : k;
            float size = special ? 22 : numeric ? 40 : 30;
            Theme.text(c, label, r.centerX(), r.centerY() + size * 0.36f, size, special ? Theme.SUB : 0xffffffff,
                special ? Theme.sans : Theme.bold, Theme.CENTER);
        }
        c.restore();
    }

    private int keyAt(float x, float y) {
        for (int i = 0; i < keyRects.size(); i++) {
            RectF r = new RectF(keyRects.get(i));
            r.inset(-KEY_GAP / 2, -KEY_GAP / 2);
            if (r.contains(x, y)) return i;
        }
        return -1;
    }

    private void press(String k) {
        // a number shown when the pad opens is replaced by the first key, not added to
        if (numeric && fresh && !k.equals(BACK)) text.setLength(0);
        fresh = false;
        if (k.equals(BACK)) {
            if (text.length() > 0) text.setLength(text.length() - 1);
        } else if (k.equals(MODE)) {
            symbols = !symbols;
            layoutKeys();
        } else if (k.equals(SPACE)) {
            if (text.length() > 0 && text.charAt(text.length() - 1) != ' ') text.append(' ');
        } else if (numeric) {
            if (k.equals(".") && text.indexOf(".") >= 0) return;
            if (text.length() < 10) text.append(k);
        } else if (text.length() < 60) {
            text.append(text.length() == 0 && !plain ? k.toUpperCase(java.util.Locale.US) : k);
        }
        invalidate();
    }

    private void close(String value) {
        act.root().removeView(this);
        done.done(value);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = Screen.x(this, e.getX()), y = Screen.y(this, e.getY());
        act.ui().poke();
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            pressed = keyAt(x, y);
            invalidate();
        } else if (e.getAction() == MotionEvent.ACTION_UP) {
            int k = pressed;
            pressed = -1;
            invalidate();
            if (k >= 0 && keyAt(x, y) == k) {
                press(keys.get(k));
            } else if ((numeric ? OK_NUM : OK_TEXT).contains(x, y)) {
                close(text.toString().trim());
            } else if ((numeric ? CANCEL_NUM : CANCEL_TEXT).contains(x, y)) {
                close(null);
            } else if (!numeric) {
                for (int i = 0; i < shownSuggestions(); i++) {
                    if (suggestion(i).contains(x, y)) {
                        text.setLength(0);
                        text.append(suggestions.get(i));
                        invalidate();
                    }
                }
            }
        } else if (e.getAction() == MotionEvent.ACTION_CANCEL) {
            pressed = -1;
            invalidate();
        }
        return true;
    }
}

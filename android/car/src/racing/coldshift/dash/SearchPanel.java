package racing.coldshift.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Go to screen: a search field, the results (Home, Work, saved and recent places while the field
 * is empty) and a keyboard of big keys drawn here, so no Android keyboard is needed. The owner finds
 * the results off the UI thread.
 */
final class SearchPanel extends View {
    /** A row in the list. */
    static final class Row {
        String name, sub = "";
        double lat, lon, km = -1;
        boolean unset;               // Home or Work not set yet: the row only explains how
        boolean removable;           // a saved or recent place, with a remove button
        Object tag;                  // the owner's object for the row
    }

    interface Owner {
        List<Row> emptyRows();
        List<Row> search(String text);       // called off the UI thread
        String status();                     // a line under the field: "No search data on the card", ...
        void chosen(Row r);
        void removed(Row r);
        void closed();
    }

    private static final RectF FIELD = new RectF(16, 14, 1060, 82);
    private static final RectF CLOSE = new RectF(1076, 14, 1264, 82);
    private static final float LIST_TOP = 112, ROW_H = 62, ROW_GAP = 6, KB_TOP = 414, KEY_H = 66, KEY_GAP = 8;
    private static final String[][] LETTERS = {
        {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
        {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
        {"z", "x", "c", "v", "b", "n", "m"}};
    private static final String[][] SYMBOLS = {
        {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
        {"-", "'", "/", "&", ",", ".", "(", ")", "#"},
        {"@", "+", "!", "?", ":", ";", "\""}};
    private static final String BACK = "⌫", SPACE = " ", MODE = "mode", HIDE = "hide";

    private final Owner owner;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<RectF> keyRects = new ArrayList<RectF>();
    private final List<String> keys = new ArrayList<String>();
    private final StringBuilder text = new StringBuilder();
    private List<Row> rows = new ArrayList<Row>();
    private boolean keyboard = true, symbols, searching;
    private int generation;
    private int pressedKey = -1, pressedRow = -1;
    private boolean pressedRemove;
    private long downAt;
    private String status = "";

    SearchPanel(Context c, Owner o) {
        super(c);
        owner = o;
        layoutKeys();
    }

    /** Opens empty, with the keyboard up. */
    void open() {
        text.setLength(0);
        keyboard = true;
        symbols = false;
        layoutKeys();
        refresh();
        setVisibility(VISIBLE);
    }

    void close() {
        setVisibility(GONE);
        generation++;
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Lists again (after a place was saved or removed, or new search data arrived). */
    void refresh() {
        final int gen = ++generation;
        status = owner.status();
        final String q = text.toString();
        if (Search.fold(q).length() < Search.MIN_CHARS) {
            rows = owner.emptyRows();
            searching = false;
            invalidate();
            return;
        }
        searching = true;
        invalidate();
        // wait a moment for the next key, then search on the worker thread
        postDelayed(new Runnable() {
            public void run() {
                if (gen != generation) return;
                worker.execute(new Runnable() {
                    public void run() {
                        if (gen != generation) return;
                        final List<Row> found = owner.search(q);
                        post(new Runnable() {
                            public void run() {
                                if (gen != generation) return;
                                rows = found;
                                searching = false;
                                invalidate();
                            }
                        });
                    }
                });
            }
        }, 150);
    }

    private void layoutKeys() {
        keyRects.clear();
        keys.clear();
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
        add(SPACE, 16 + u * 2, y, u * 5.5f - KEY_GAP);
        add(HIDE, 16 + u * 7.5f, y, 1264 - (16 + u * 7.5f));
    }

    private void add(String k, float x, float y, float w) {
        keys.add(k);
        keyRects.add(new RectF(x, y, x + w, y + KEY_H));
    }

    private float listBottom() { return keyboard ? KB_TOP - 12 : 706; }

    private RectF rowRect(int i) {
        float y = LIST_TOP + i * (ROW_H + ROW_GAP);
        return new RectF(16, y, 1264, y + ROW_H);
    }

    private static RectF removeRect(RectF r) { return new RectF(r.right - 74, r.top + 6, r.right - 8, r.bottom - 6); }

    private int visibleRows() { return (int) ((listBottom() - LIST_TOP + ROW_GAP) / (ROW_H + ROW_GAP)); }

    @Override protected void onDraw(Canvas c) {
        c.save();
        Screen.begin(c, this);
        c.drawColor(0xf205070d);
        // the field
        Theme.button(c, FIELD, false, 12);
        String q = text.toString();
        float tx = FIELD.left + 22, ty = FIELD.centerY() + 10;
        if (q.isEmpty()) {
            Theme.text(c, "Search places, streets and addresses", tx, ty, 26, Theme.DIM, Theme.sans, Theme.LEFT);
        } else {
            String shown = q;
            while (shown.length() > 1 && Theme.width(shown, 28, Theme.bold) > FIELD.width() - 60) shown = shown.substring(1);
            Theme.text(c, shown, tx, ty, 28, 0xffffffff, Theme.bold, Theme.LEFT);
            tx += Theme.width(shown, 28, Theme.bold) + 3;
        }
        if (keyboard) {
            Theme.P.setColor(Theme.BLUE_HI);
            c.drawRect(tx, FIELD.top + 16, tx + 3, FIELD.bottom - 16, Theme.P);
        }
        Theme.button(c, CLOSE, false, 12);
        Theme.text(c, "Close", CLOSE.centerX(), CLOSE.centerY() + 8, 22, Theme.TEXT, Theme.bold, Theme.CENTER);
        String line = searching ? "Searching…" : !status.isEmpty() ? status
            : Search.fold(q).length() >= Search.MIN_CHARS && rows.isEmpty() ? "Nothing found for “" + q.trim() + "”" : "";
        if (!line.isEmpty()) Theme.text(c, line, 32, 104, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        // the rows
        int n = Math.min(rows.size(), visibleRows());
        for (int i = 0; i < n; i++) {
            Row r = rows.get(i);
            RectF b = rowRect(i);
            if (!line.isEmpty()) b.offset(0, 14);
            Theme.button(c, b, i == pressedRow && !pressedRemove, 12);
            float right = r.removable ? b.right - 84 : b.right - 18;
            if (r.km >= 0) {
                String d = r.km < 1 ? Math.round(r.km * 100) * 10 + " m" : r.km < 10
                    ? String.format(java.util.Locale.US, "%.1f km", r.km) : Math.round(r.km) + " km";
                Theme.text(c, d, right, b.top + 38, 18, Theme.SUB, Theme.sans, Theme.RIGHT);
                right -= Theme.width(d, 18, Theme.sans) + 24;
            }
            float maxW = right - b.left - 20;
            Theme.text(c, Theme.fit(r.name, maxW, 22, Theme.bold), b.left + 18, b.top + 27, 22,
                r.unset ? Theme.SUB : Theme.TEXT, Theme.bold, Theme.LEFT);
            Theme.text(c, Theme.fit(r.sub, maxW, 18, Theme.sans), b.left + 18, b.top + 50, 18, Theme.SUB, Theme.sans,
                Theme.LEFT);
            if (r.removable) {
                RectF x = removeRect(b);
                Theme.button(c, x, i == pressedRow && pressedRemove, 10);
                Theme.text(c, "×", x.centerX(), x.centerY() + 11, 32, Theme.SUB, Theme.sans, Theme.CENTER);
            }
        }
        if (keyboard) drawKeys(c);
        c.restore();
    }

    private void drawKeys(Canvas c) {
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            RectF r = keyRects.get(i);
            boolean special = k.equals(BACK) || k.equals(MODE) || k.equals(HIDE);
            Theme.button(c, r, i == pressedKey, 10);
            String label = k.equals(MODE) ? (symbols ? "ABC" : "123") : k.equals(HIDE) ? "Hide keyboard"
                : k.equals(SPACE) ? "space" : k;
            float size = special || k.equals(SPACE) ? 22 : 30;
            int col = special || k.equals(SPACE) ? Theme.SUB : 0xffffffff;
            if (k.equals(BACK)) {
                backIcon(c, r.centerX(), r.centerY());
                continue;
            }
            Theme.text(c, label, r.centerX(), r.centerY() + size * 0.36f, size, col,
                special ? Theme.sans : Theme.bold, Theme.CENTER);
        }
    }

    /** A backspace key's symbol (the fonts on this phone have no U+232B). */
    private static void backIcon(Canvas c, float cx, float cy) {
        android.graphics.Paint p = Theme.P;
        android.graphics.Path a = new android.graphics.Path();
        a.moveTo(cx - 30, cy);
        a.lineTo(cx - 14, cy - 16);
        a.lineTo(cx + 28, cy - 16);
        a.lineTo(cx + 28, cy + 16);
        a.lineTo(cx - 14, cy + 16);
        a.close();
        p.setShader(null);
        p.setColor(Theme.SUB);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        c.drawPath(a, p);
        c.drawLine(cx - 2, cy - 7, cx + 12, cy + 7, p);
        c.drawLine(cx + 12, cy - 7, cx - 2, cy + 7, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
    }

    private int keyAt(float x, float y) {
        if (!keyboard) return -1;
        for (int i = 0; i < keyRects.size(); i++) {
            RectF r = new RectF(keyRects.get(i));
            r.inset(-KEY_GAP / 2, -KEY_GAP / 2);
            if (r.contains(x, y)) return i;
        }
        return -1;
    }

    private int rowAt(float x, float y) {
        int n = Math.min(rows.size(), visibleRows());
        boolean shifted = !status.isEmpty() || searching || (Search.fold(text.toString()).length() >= Search.MIN_CHARS && rows.isEmpty());
        for (int i = 0; i < n; i++) {
            RectF b = rowRect(i);
            if (shifted) b.offset(0, 14);
            if (b.contains(x, y)) {
                pressedRemove = rows.get(i).removable && removeRect(b).contains(x, y);
                return i;
            }
        }
        return -1;
    }

    private void press(String k, boolean held) {
        if (k.equals(BACK)) {
            if (held) text.setLength(0);
            else if (text.length() > 0) text.setLength(text.length() - 1);
        } else if (k.equals(MODE)) {
            symbols = !symbols;
            layoutKeys();
            invalidate();
            return;
        } else if (k.equals(HIDE)) {
            keyboard = false;
            invalidate();
            return;
        } else if (k.equals(SPACE)) {
            if (text.length() > 0 && text.charAt(text.length() - 1) != ' ') text.append(' ');
        } else if (text.length() < 60) {
            text.append(text.length() == 0 || text.charAt(text.length() - 1) == ' ' ? k.toUpperCase(java.util.Locale.US) : k);
        }
        refresh();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = Screen.x(this, e.getX()), y = Screen.y(this, e.getY());
        if (getContext() instanceof Main) ((Main) getContext()).ui().poke();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downAt = System.currentTimeMillis();
                pressedKey = keyAt(x, y);
                pressedRow = pressedKey < 0 ? rowAt(x, y) : -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                int k = pressedKey, r = pressedRow;
                boolean remove = pressedRemove;
                pressedKey = pressedRow = -1;
                pressedRemove = false;
                invalidate();
                if (k >= 0 && keyAt(x, y) == k) {
                    press(keys.get(k), System.currentTimeMillis() - downAt > 600);
                } else if (r >= 0 && rowAt(x, y) == r) {
                    Row row = rows.get(r);
                    if (remove) {
                        owner.removed(row);
                        refresh();
                    } else if (!row.unset) {
                        owner.chosen(row);
                    }
                } else if (CLOSE.contains(x, y)) {
                    owner.closed();
                } else if (FIELD.contains(x, y) && !keyboard) {
                    keyboard = true;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressedKey = pressedRow = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}

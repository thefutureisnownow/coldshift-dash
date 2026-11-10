package racing.coldshift.dash;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.location.Location;
import android.view.MotionEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * The log book: the odometer (estimated between readings), fuel use and this month's figures on the left;
 * trips and fill-ups on the right. Trips log themselves (Logbook); a stop at a fuel station offers a
 * fill-up to confirm. Tap a trip to mark it business and give its purpose (for the SARS travel logbook
 * the desktop tool makes); fill-ups, odometer readings and services are typed in here. Service reminders
 * (set on the desktop) head the list when due within 1000 km or 30 days; tapping one logs that service.
 */
class LogPage extends Page implements Logbook.Listener {
    private enum Mode { LIST, TRIP, FORM }

    private static final RectF ODO = new RectF(26, 80, 548, 212);
    private static final RectF LOG_PENDING = new RectF(38, 474, 290, 524), NO_PENDING = new RectF(302, 474, 536, 524);
    private static final RectF ADD_FUEL = new RectF(30, 553, 250, 607), ADD_ODO = new RectF(262, 553, 400, 607);
    private static final RectF ADD_SERVICE = new RectF(412, 553, 544, 607);
    private static final RectF[] FILTERS = {Wide.right(new RectF(922, 74, 1026, 124)),
        Wide.right(new RectF(1034, 74, 1138, 124)), Wide.right(new RectF(1146, 74, 1250, 124))};
    private static final String[] FILTER_NAMES = {"All", "Trips", "Fuel"};
    private static final RectF BACK = new RectF(598, 553, 790, 607), DELETE = new RectF(802, 553, 998, 607);
    private static final RectF SAVE = Wide.right(new RectF(1010, 553, 1250, 607));
    private static final RectF BUSINESS = new RectF(598, 296, 850, 352), PRIVATE = new RectF(862, 296, 1114, 352);
    private static final RectF PURPOSE = Wide.stretch(new RectF(598, 420, 1250, 476));
    private static final RectF TITLE = Wide.stretch(new RectF(590, 76, 1250, 122));
    private static final RectF FULL = new RectF(590, 442, 915, 494), MISSED = Wide.stretch(new RectF(927, 442, 1250, 494));
    private static final float ROWS_TOP = 132, ROWS_BOTTOM = 614, ROW_H = 72, FIELD_TOP = 130, FIELD_H = 62;
    private static final String[] SERVICES = {"Service", "Oil change", "Tyres", "Brakes", "Battery", "Licence renewal",
        "Roadworthy", "Wash"};

    private final LogStore store;
    private final Logbook book;
    private Mode mode = Mode.LIST;
    private int filter;
    private final Scroll scroll;        // the list of entries
    private boolean inList;             // the finger went down in it
    private List<JSONObject> shown = new ArrayList<JSONObject>();
    private JSONObject selected, pending;
    private boolean confirmDelete;
    private String note = "";
    // figures, worked out on refresh
    private double odoNow = Double.NaN, lastL100 = Double.NaN, avgL100 = Double.NaN, factor = 1;
    private long readingAt;
    private LogStore.Month month = new LogStore.Month();
    private List<LogStore.Due> due = new ArrayList<LogStore.Due>();       // reminders due soon or overdue
    private List<LogStore.Due> listedDue = new ArrayList<LogStore.Due>();  // those the list's rows show
    private long dueAt;
    // the form
    private JSONObject form;
    private boolean formNew, odoTyped;
    private double litres = Double.NaN, price = Double.NaN, total = Double.NaN, odometer = Double.NaN, cost = Double.NaN;
    private final List<String> edited = new ArrayList<String>();     // of litres, price, total: oldest first
    private String grade = "", station = "", what = "";
    private boolean full = true, missed, priceTyped;

    LogPage(Main a) {
        super(a);
        scroll = new Scroll(a);
        store = LogStore.get(a);
        book = Logbook.get(a);
        book.setListener(this);
    }

    String label() { return "Log book"; }
    @Override String icon() { return "log"; }

    @Override boolean wide() { return true; }

    @Override float overlay() { return 0.72f; }

    /** A fill-up to confirm, or a service due soon (looked at once a minute, whichever page shows). */
    @Override boolean attention() {
        if (System.currentTimeMillis() - dueAt > 60000) refreshDue();
        return book.pending() != null || !due.isEmpty();
    }

    private void refreshDue() {
        dueAt = System.currentTimeMillis();
        odoNow = store.odometer(dueAt, book.tripMetres());
        List<LogStore.Due> soon = new ArrayList<LogStore.Due>();
        for (LogStore.Due d : store.reminders(odoNow)) if (d.soon) soon.add(d);
        due = soon;
    }

    @Override void onShow() { refresh(); }

    public void changed() { refresh(); }

    /** After a sync: entries from the desktop. */
    void reload() {
        store.reload();
        refresh();
    }

    private int ticks;

    @Override void tick() {
        if (++ticks % 10 == 0) refresh();          // the trip under way moves the odometer on
    }

    private void refresh() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        refreshDue();
        listedDue = due;
        if (filter == 0) {
            for (LogStore.Due d : listedDue) {
                JSONObject row = new JSONObject();
                try {
                    row.put("type", "due");
                    row.put("index", out.size());
                    row.put("time", System.currentTimeMillis());
                } catch (Exception e) { /* cannot happen */ }
                out.add(row);
            }
        }
        for (JSONObject o : store.all()) {
            String t = o.optString("type");
            if (t.equals("reminder")) continue;
            if (filter == 0 || (filter == 1 && t.equals("trip")) || (filter == 2 && t.equals("fuel"))) out.add(o);
        }
        shown = out;
        factor = store.factor();
        readingAt = store.lastReadingTime();
        List<double[]> c = store.consumption();
        lastL100 = c.isEmpty() ? Double.NaN : c.get(c.size() - 1)[1];
        avgL100 = store.averageL100();
        month = store.month();
        pending = book.pending();
        if (selected != null) {
            JSONObject again = null;
            for (JSONObject o : store.all()) if (o.optString("id").equals(selected.optString("id"))) again = o;
            selected = again;
            if (again == null && mode == Mode.TRIP) mode = Mode.LIST;
        }
    }

    // ---- drawing ----

    void draw(Canvas c) {
        Theme.panel(c, 14, 68, 560, 625);
        summary(c);
        Theme.panel(c, 574, 68, Wide.rx(1266), 625);
        if (mode == Mode.TRIP && selected != null) trip(c);
        else if (mode == Mode.FORM) form(c);
        else list(c);
    }

    private void summary(Canvas c) {
        Theme.spaced(c, "ODOMETER", 38, 104, 18, Theme.SUB, Theme.bold, 2f);
        if (Double.isNaN(odoNow)) {
            Theme.text(c, "Tap to enter", 38, 164, 36, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, "Type the odometer reading once; trips keep it up to date.", 38, 196, 18, Theme.SUB,
                Theme.sans, Theme.LEFT);
        } else {
            Theme.text(c, km(odoNow), 38, 168, 54, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, "estimated from the reading on " + new SimpleDateFormat("d MMM", Locale.US).format(new Date(readingAt))
                + " and trips since · tap to correct", 38, 198, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        }
        String[][] rows = {
            {"Fuel use, last tank", Double.isNaN(lastL100) ? "-" : String.format(Locale.US, "%.1f L/100 km", lastL100)},
            {"Fuel use, average", Double.isNaN(avgL100) ? "-" : String.format(Locale.US, "%.1f L/100 km", avgL100)},
            {"Fuel this month", money(month.fuelCost) + " · " + month.fills + (month.fills == 1 ? " fill-up" : " fill-ups")},
            {"Driven this month", Math.round(month.tripKm) + " km · " + Math.round(month.businessKm) + " km business"},
            {"Trip under way", book.tripMetres() > 0 ? String.format(Locale.US, "%.1f km", book.tripMetres() * factor / 1000) : "-"}};
        for (int i = 0; i < rows.length; i++) {
            float y = 252 + i * 38;
            Theme.text(c, rows[i][0], 38, y, 18, Theme.SUB, Theme.sans, Theme.LEFT);
            Theme.text(c, rows[i][1], 536, y, 20, 0xffffffff, Theme.bold, Theme.RIGHT);
        }
        if (pending != null) {
            Theme.P.setShader(null);
            Theme.P.setColor(0x33e3b341);
            c.drawRoundRect(new RectF(26, 420, 548, 536), 12, 12, Theme.P);
            Theme.text(c, Theme.fit("Filled up at " + pending.optString("station") + "?", 500, 20, Theme.bold), 38, 448, 20,
                Theme.GOLD, Theme.bold, Theme.LEFT);
            Theme.text(c, "Stopped " + Math.round(pending.optLong("stood") / 60000.0) + " min at "
                + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(pending.optLong("time"))), 536, 448, 18,
                Theme.SUB, Theme.sans, Theme.RIGHT);
            Theme.button(c, LOG_PENDING, true, 10);
            Theme.text(c, "Log fill-up", LOG_PENDING.centerX(), LOG_PENDING.centerY() + 7, 20, 0xffffffff, Theme.bold, Theme.CENTER);
            Theme.button(c, NO_PENDING, false, 10);
            Theme.text(c, "Not a fill-up", NO_PENDING.centerX(), NO_PENDING.centerY() + 7, 18, Theme.SUB, Theme.sans, Theme.CENTER);
        }
        Theme.button(c, ADD_FUEL, true, 12);
        Theme.text(c, "Add fill-up", ADD_FUEL.centerX(), ADD_FUEL.centerY() + 8, 22, 0xffffffff, Theme.bold, Theme.CENTER);
        Theme.button(c, ADD_ODO, false, 12);
        Theme.text(c, "Odometer", ADD_ODO.centerX(), ADD_ODO.centerY() + 7, 20, Theme.TEXT, Theme.sans, Theme.CENTER);
        Theme.button(c, ADD_SERVICE, false, 12);
        Theme.text(c, "Service", ADD_SERVICE.centerX(), ADD_SERVICE.centerY() + 7, 20, Theme.TEXT, Theme.sans, Theme.CENTER);
    }

    private void list(Canvas c) {
        Theme.text(c, "Log", 598, 106, 24, 0xffffffff, Theme.bold, Theme.LEFT);
        for (int i = 0; i < FILTERS.length; i++) {
            Theme.button(c, FILTERS[i], i == filter, 10);
            Theme.text(c, FILTER_NAMES[i], FILTERS[i].centerX(), FILTERS[i].centerY() + 7, 20,
                i == filter ? 0xffffffff : Theme.SUB, Theme.sans, Theme.CENTER);
        }
        SimpleDateFormat when = new SimpleDateFormat("EEE d MMM  HH:mm", Locale.US);
        float bottom = listBottom();
        scroll.range(shown.size() * ROW_H - 8, bottom - ROWS_TOP);
        scroll.step();
        c.save();
        c.clipRect(586, ROWS_TOP, Wide.rx(1254), bottom);
        for (int i = 0; i < shown.size(); i++) {
            float top = ROWS_TOP + i * ROW_H - scroll.pos;
            if (top + ROW_H < ROWS_TOP || top > bottom) continue;
            JSONObject o = shown.get(i);
            String t = o.optString("type");
            RectF r = new RectF(590, top, Wide.rx(1250), top + ROW_H - 8);
            Theme.button(c, r, false, 10);
            String title, sub = when.format(new Date(o.optLong("time"))), right = "";
            int rightCol = 0xffffffff;
            if (t.equals("due")) {
                LogStore.Due d = listedDue.get(o.optInt("index"));
                drawDue(c, r, d);
                continue;
            }
            if (t.equals("trip")) {
                title = place(o, "from") + "  →  " + place(o, "to");
                long min = Math.max(1, (o.optLong("end") - o.optLong("time")) / 60000);
                sub += " · " + min + " min";
                right = String.format(Locale.US, "%.1f km", o.optDouble("dist_m", 0) * factor / 1000);
            } else if (t.equals("fuel")) {
                title = "Fill-up" + (o.optString("station").isEmpty() ? "" : " · " + o.optString("station"));
                sub += String.format(Locale.US, " · %.2f L at %s · %s%s", o.optDouble("litres", 0),
                    money(o.optDouble("price", 0)), o.optString("grade"), o.optBoolean("full", true) ? "" : " · part");
                right = money(o.optDouble("total", 0));
            } else if (t.equals("service")) {
                title = o.optString("what", "Service");
                right = o.optDouble("cost", 0) > 0 ? money(o.optDouble("cost")) : "";
            } else {
                title = "Odometer reading";
                right = km(o.optDouble("odometer", 0));
                rightCol = Theme.SUB;
            }
            if (!t.equals("trip") && o.optDouble("odometer", 0) > 0 && !t.equals("odometer")) sub += " · " + km(o.optDouble("odometer"));
            float rw = Theme.width(right, 20, Theme.bold);
            boolean biz = t.equals("trip") && o.optBoolean("business");
            Theme.text(c, Theme.fit(title, 620 + Wide.d() - rw - (biz ? 90 : 0), 20, Theme.bold), 606, r.top + 25, 20, 0xffffffff,
                Theme.bold, Theme.LEFT);
            Theme.text(c, Theme.fit(sub, 610 + Wide.d(), 18, Theme.sans), 606, r.top + 48, 18, Theme.SUB, Theme.sans, Theme.LEFT);
            Theme.text(c, right, Wide.rx(1234), r.top + 26, 20, rightCol, Theme.bold, Theme.RIGHT);
            if (biz) Theme.spaced(c, "BUSINESS", Wide.rx(1234) - rw - 92, r.top + 25, 18, Theme.GOLD, Theme.bold, 1.5f);
        }
        c.restore();
        scroll.bar(c, Wide.rx(1254), ROWS_TOP, bottom);
        if (shown.isEmpty()) {
            String[] lines = {
                "Trips appear here by themselves once the car moves off.",
                "Stops at a fuel station offer a fill-up to confirm,",
                "and Add fill-up logs one by hand.",
                "",
                "Mark trips as business for the SARS travel logbook,",
                "which the desktop tool makes (Logbook tab)."};
            for (int i = 0; i < lines.length; i++)
                Theme.text(c, lines[i], 598, 160 + i * 30, 18, Theme.TEXT, Theme.sans, Theme.LEFT);
        }
        if (!note.isEmpty()) Theme.text(c, note, 598, 600, 18, Theme.GOLD, Theme.bold, Theme.LEFT);
    }

    /** Where the list ends: the panel's foot, or above a note when there is one. */
    private float listBottom() { return note.isEmpty() ? ROWS_BOTTOM : 570; }

    private boolean listShowing() { return mode != Mode.FORM && !(mode == Mode.TRIP && selected != null); }

    private void drawDue(Canvas c, RectF r, LogStore.Due d) {
        JSONObject rem = d.reminder;
        int col = d.overdue ? Theme.BAD : Theme.GOLD;
        Theme.P.setShader(null);
        Theme.P.setColor((col & 0xffffff) | 0x26000000);
        c.drawRoundRect(r, 10, 10, Theme.P);
        String right = d.when();
        float rw = Theme.width(right, 20, Theme.bold);
        Theme.text(c, Theme.fit(d.what + (d.overdue ? " overdue" : " due"), 620 + Wide.d() - rw, 20, Theme.bold), 606, r.top + 25, 20,
            col, Theme.bold, Theme.LEFT);
        String every = "every" + (rem.optDouble("every_km", 0) > 0 ? " " + km(rem.optDouble("every_km")) : "")
            + (rem.optDouble("every_km", 0) > 0 && rem.optInt("every_months", 0) > 0 ? " or" : "")
            + (rem.optInt("every_months", 0) > 0 ? " " + rem.optInt("every_months") + " months" : "");
        String last = "last " + new SimpleDateFormat("d MMM yyyy", Locale.US).format(new Date(d.lastTime))
            + (Double.isNaN(d.lastOdo) || d.lastOdo <= 0 || rem.optDouble("every_km", 0) <= 0 ? "" : " at " + km(d.lastOdo));
        Theme.text(c, Theme.fit(every + " · " + last + " · tap when done", 610 + Wide.d(), 18, Theme.sans), 606, r.top + 48, 18,
            Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.text(c, right, Wide.rx(1234), r.top + 26, 20, col, Theme.bold, Theme.RIGHT);
    }

    private void trip(Canvas c) {
        JSONObject o = selected;
        Theme.text(c, Theme.fit(place(o, "from") + "  →  " + place(o, "to"), 640 + Wide.d(), 26, Theme.bold), 598, 110, 26,
            0xffffffff, Theme.bold, Theme.LEFT);
        long start = o.optLong("time"), end = o.optLong("end");
        Theme.text(c, new SimpleDateFormat("EEE d MMM yyyy  HH:mm", Locale.US).format(new Date(start)) + " – "
            + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(end)) + " · " + Math.max(1, (end - start) / 60000) + " min",
            598, 142, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.text(c, String.format(Locale.US, "%.1f km", o.optDouble("dist_m", 0) * factor / 1000), 598, 214, 52,
            0xffffffff, Theme.bold, Theme.LEFT);
        String more = "top speed " + Math.round(o.optDouble("max", 0) * 3.6) + " km/h";
        if (o.has("odo_start")) more += " · odometer about " + km(o.optDouble("odo_start")) + " to " + km(o.optDouble("odo_end"));
        Theme.text(c, more, 598, 250, 18, Theme.SUB, Theme.sans, Theme.LEFT);
        boolean biz = o.optBoolean("business");
        Theme.button(c, BUSINESS, biz, 12);
        Theme.text(c, "Business", BUSINESS.centerX(), BUSINESS.centerY() + 8, 22, biz ? 0xffffffff : Theme.TEXT, Theme.bold, Theme.CENTER);
        Theme.button(c, PRIVATE, !biz, 12);
        Theme.text(c, "Private", PRIVATE.centerX(), PRIVATE.centerY() + 8, 22, !biz ? 0xffffffff : Theme.TEXT, Theme.bold, Theme.CENTER);
        if (biz) {
            String p = o.optString("purpose");
            Theme.text(c, "Where to and why, for SARS:", 598, 400, 18, Theme.SUB, Theme.sans, Theme.LEFT);
            Theme.button(c, PURPOSE, false, 12);
            Theme.text(c, Theme.fit(p.isEmpty() ? "Tap to give the purpose" : p, 610 + Wide.d(), 22, Theme.bold), 618, PURPOSE.centerY() + 8,
                22, p.isEmpty() ? Theme.GOLD : 0xffffffff, Theme.bold, Theme.LEFT);
        } else {
            Theme.text(c, "Private trips (and driving to and from work) count as private use.", 598, 400, 18, Theme.SUB,
                Theme.sans, Theme.LEFT);
        }
        Theme.button(c, BACK, false, 12);
        Theme.text(c, "Back", BACK.centerX(), BACK.centerY() + 7, 20, Theme.TEXT, Theme.sans, Theme.CENTER);
        deleteButton(c);
    }

    private void deleteButton(Canvas c) {
        Theme.button(c, DELETE, false, 12);
        Theme.text(c, confirmDelete ? "Tap again" : "Delete", DELETE.centerX(), DELETE.centerY() + 7, 18,
            confirmDelete ? Theme.BAD : Theme.SUB, Theme.sans, Theme.CENTER);
    }

    private RectF field(int i) { return new RectF(590, FIELD_TOP + i * FIELD_H, Wide.rx(1250), FIELD_TOP + i * FIELD_H + FIELD_H - 8); }

    private void fieldRow(Canvas c, int i, String label, String value, boolean worked) {
        RectF r = field(i);
        Theme.button(c, r, false, 10);
        Theme.text(c, label, r.left + 18, r.centerY() + 7, 20, Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.text(c, value + (worked ? "  (worked out)" : ""), r.right - 18, r.centerY() + 8, 22,
            worked ? Theme.SUB : 0xffffffff, Theme.bold, Theme.RIGHT);
    }

    private void form(Canvas c) {
        String t = form.optString("type");
        String head = (formNew ? "" : "Edit ") + (t.equals("fuel") ? "fill-up" : t.equals("service") ? "service" : "odometer reading");
        head = head.substring(0, 1).toUpperCase(Locale.US) + head.substring(1);
        Theme.text(c, head, 598, 108, 24, 0xffffffff, Theme.bold, Theme.LEFT);
        if (t.equals("fuel")) {
            Theme.text(c, Theme.fit(station.isEmpty() ? "Tap to name the station" : station, 420, 18, Theme.sans), Wide.rx(1250), 106, 18,
                station.isEmpty() ? Theme.SUB : Theme.TEXT, Theme.sans, Theme.RIGHT);
            String worked = edited.isEmpty() ? "total" : edited.get(0);
            fieldRow(c, 0, "Litres", num(litres, "%.2f L"), worked.equals("litres") && !Double.isNaN(litres));
            fieldRow(c, 1, "Price per litre", Double.isNaN(price) ? "-" : money(price), worked.equals("price") && !Double.isNaN(price));
            fieldRow(c, 2, "Total", Double.isNaN(total) ? "-" : money(total), worked.equals("total") && !Double.isNaN(total));
            fieldRow(c, 3, "Odometer", Double.isNaN(odometer) ? "-" : km(odometer) + (odoTyped ? "" : " (estimate)"), false);
            float w = (660 + Wide.d() - 3 * 12) / 4f;
            for (int i = 0; i < LogStore.GRADES.length; i++) {
                RectF r = gradeRect(i, w);
                Theme.button(c, r, LogStore.GRADES[i].equals(grade), 10);
                Theme.text(c, LogStore.GRADES[i], r.centerX(), r.centerY() + 7, 20,
                    LogStore.GRADES[i].equals(grade) ? 0xffffffff : Theme.TEXT, Theme.bold, Theme.CENTER);
            }
            Theme.button(c, FULL, full, 10);
            Theme.text(c, full ? "Filled to full" : "Part fill", FULL.centerX(), FULL.centerY() + 7, 20,
                full ? 0xffffffff : Theme.TEXT, Theme.bold, Theme.CENTER);
            Theme.button(c, MISSED, missed, 10);
            Theme.text(c, "Missed logging one before", MISSED.centerX(), MISSED.centerY() + 7, 18,
                missed ? 0xffffffff : Theme.SUB, Theme.sans, Theme.CENTER);
        } else if (t.equals("service")) {
            fieldRow(c, 0, "What", what.isEmpty() ? "-" : what, false);
            fieldRow(c, 1, "Cost", Double.isNaN(cost) ? "-" : money(cost), false);
            fieldRow(c, 2, "Odometer", Double.isNaN(odometer) ? "-" : km(odometer) + (odoTyped ? "" : " (estimate)"), false);
        } else {
            fieldRow(c, 0, "Odometer", Double.isNaN(odometer) ? "-" : km(odometer) + (odoTyped ? "" : " (estimate)"), false);
            Theme.text(c, "Read it off the dashboard. Trips keep it up to date in between.", 598, 230, 18, Theme.SUB,
                Theme.sans, Theme.LEFT);
        }
        if (!note.isEmpty()) Theme.text(c, note, 598, 535, 18, Theme.GOLD, Theme.bold, Theme.LEFT);
        Theme.button(c, BACK, false, 12);
        Theme.text(c, "Cancel", BACK.centerX(), BACK.centerY() + 7, 20, Theme.TEXT, Theme.sans, Theme.CENTER);
        if (!formNew) deleteButton(c);
        Theme.button(c, SAVE, true, 12);
        Theme.text(c, "Save", SAVE.centerX(), SAVE.centerY() + 8, 22, 0xffffffff, Theme.bold, Theme.CENTER);
    }

    private static RectF gradeRect(int i, float w) {
        float x = 590 + i * (w + 12);
        return new RectF(x, 380, x + w, 430);
    }

    // ---- touch ----

    @Override boolean touch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int a = e.getAction();
        // the list: a drag scrolls it, and only a finger that stayed put taps a row
        if (a == MotionEvent.ACTION_DOWN) {
            inList = listShowing() && x >= 586 && x <= Wide.rx(1254) && y >= ROWS_TOP && y <= listBottom();
            if (inList) scroll.down(y);
            return true;
        }
        if (inList) {
            if (a == MotionEvent.ACTION_MOVE) { scroll.move(y); return true; }
            inList = false;
            if (a != MotionEvent.ACTION_UP) { scroll.cancel(); return true; }
            if (!scroll.up()) return true;
        }
        if (a != MotionEvent.ACTION_UP) return true;
        if (ODO.contains(x, y) || ADD_ODO.contains(x, y)) {
            newReading();
        } else if (pending != null && LOG_PENDING.contains(x, y)) {
            JSONObject f = LogStore.make("fuel", pending.optLong("time"));
            try {
                f.put("station", pending.optString("station"));
                f.put("lat", pending.optDouble("lat"));
                f.put("lon", pending.optDouble("lon"));
            } catch (Exception ex) { /* cannot happen */ }
            open(f, true);
        } else if (pending != null && NO_PENDING.contains(x, y)) {
            book.clearPending();
        } else if (ADD_FUEL.contains(x, y)) {
            newFuel();
        } else if (ADD_SERVICE.contains(x, y)) {
            open(LogStore.make("service", System.currentTimeMillis()), true);
        } else if (mode == Mode.TRIP && selected != null) {
            touchTrip(x, y);
        } else if (mode == Mode.FORM) {
            touchForm(x, y);
        } else {
            touchList(x, y);
        }
        return true;
    }

    private void touchList(float x, float y) {
        note = "";
        for (int i = 0; i < FILTERS.length; i++) {
            if (FILTERS[i].contains(x, y)) {
                filter = i;
                scroll.reset();
                refresh();
                return;
            }
        }
        if (x < 590 || x > Wide.rx(1250) || y < ROWS_TOP || y > listBottom()) return;
        for (int i = (int) ((y - ROWS_TOP + scroll.pos) / ROW_H); i >= 0 && i < shown.size(); i = -1) {
            if (y - ROWS_TOP + scroll.pos - i * ROW_H <= ROW_H - 8) {
                JSONObject o = shown.get(i);
                confirmDelete = false;
                if (o.optString("type").equals("due")) {
                    // done: a service of that name, which starts the reminder again
                    JSONObject s = LogStore.make("service", System.currentTimeMillis());
                    try {
                        s.put("what", listedDue.get(o.optInt("index")).what);
                    } catch (Exception e) { /* cannot happen */ }
                    open(s, true);
                } else if (o.optString("type").equals("trip")) {
                    selected = o;
                    mode = Mode.TRIP;
                } else {
                    open(copy(o), false);
                }
            }
        }
    }

    private void touchTrip(float x, float y) {
        JSONObject o = selected;
        try {
            if (BUSINESS.contains(x, y) && !o.optBoolean("business")) {
                o.put("business", true);
                store.put(o);
                if (o.optString("purpose").isEmpty()) askPurpose();
            } else if (PRIVATE.contains(x, y) && o.optBoolean("business")) {
                o.put("business", false);
                store.put(o);
            } else if (o.optBoolean("business") && PURPOSE.contains(x, y)) {
                askPurpose();
            } else if (BACK.contains(x, y)) {
                mode = Mode.LIST;
                selected = null;
            } else if (DELETE.contains(x, y)) {
                if (confirmDelete) {
                    store.delete(o);
                    mode = Mode.LIST;
                    selected = null;
                }
                confirmDelete = !confirmDelete;
            }
        } catch (Exception ex) { /* cannot happen */ }
        refresh();
    }

    private void askPurpose() {
        final JSONObject o = selected;
        Keypad.text(act, "Where to and why (for example: Client visit, Acme in George)", o.optString("purpose"),
            store.purposes(), new Keypad.Done() {
                public void done(String v) {
                    if (v == null) return;
                    try {
                        o.put("purpose", v);
                    } catch (Exception ex) { /* cannot happen */ }
                    store.put(o);
                    refresh();
                }
            });
    }

    // ---- the form ----

    /** A reading straight from the number pad, without the form. */
    private void newReading() {
        Keypad.number(act, "Odometer reading", "km", "", new Keypad.Done() {
            public void done(String v) {
                double km = v == null ? Double.NaN : parse(v);
                if (Double.isNaN(km) || km <= 0) return;
                JSONObject o = LogStore.make("odometer", System.currentTimeMillis());
                try {
                    o.put("odometer", Math.round(km));
                } catch (Exception e) { /* cannot happen */ }
                store.put(o);
                refresh();
            }
        });
    }

    private void newFuel() {
        final JSONObject f = LogStore.make("fuel", System.currentTimeMillis());
        final Location l = book.last();
        open(f, true);
        if (l == null) return;
        // the station from the map, when the car is at one
        new Thread(new Runnable() {
            public void run() {
                final String s = book.fuelStation(l.getLatitude(), l.getLongitude());
                if (s != null) act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (form == f && station.isEmpty()) station = s;
                    }
                });
            }
        }).start();
        try {
            f.put("lat", l.getLatitude());
            f.put("lon", l.getLongitude());
        } catch (Exception ex) { /* cannot happen */ }
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (Exception e) {
            return o;
        }
    }

    private void open(JSONObject f, boolean isNew) {
        form = f;
        formNew = isNew;
        mode = Mode.FORM;
        note = "";
        confirmDelete = false;
        litres = f.optDouble("litres", Double.NaN);
        total = f.optDouble("total", Double.NaN);
        cost = f.optDouble("cost", Double.NaN);
        odometer = f.optDouble("odometer", Double.NaN);
        odoTyped = !isNew;
        if (isNew && f.optString("type").equals("fuel") || isNew && f.optString("type").equals("service")) {
            odometer = Math.round(store.odometer(f.optLong("time"), f.optLong("time") >= System.currentTimeMillis() - 60000
                ? book.tripMetres() : 0));
        }
        if (Double.isNaN(odometer) || odometer <= 0) odometer = Double.NaN;
        grade = f.optString("grade", store.lastGrade());
        price = f.optDouble("price", Double.NaN);
        if (isNew && Double.isNaN(price)) {
            double p = store.lastPrice(grade);
            price = p > 0 ? p : Double.NaN;
        }
        station = f.optString("station");
        what = f.optString("what");
        full = f.optBoolean("full", true);
        missed = f.optBoolean("missed");
        priceTyped = !isNew;
        edited.clear();
        edited.addAll(Arrays.asList("total", "price", "litres"));
        if (!isNew) edited.add(0, edited.remove(edited.indexOf("price")));       // an old fill-up: price was worked out
    }

    private void touchForm(float x, float y) {
        String t = form.optString("type");
        if (BACK.contains(x, y)) {
            mode = selected != null ? Mode.TRIP : Mode.LIST;
            return;
        }
        if (!formNew && DELETE.contains(x, y)) {
            if (confirmDelete) {
                store.delete(form);
                mode = Mode.LIST;
                refresh();
            }
            confirmDelete = !confirmDelete;
            return;
        }
        if (SAVE.contains(x, y)) {
            save();
            return;
        }
        if (t.equals("fuel")) {
            if (field(0).contains(x, y)) askNumber("Litres", "L", litres, "litres");
            else if (field(1).contains(x, y)) askNumber("Price per litre", "R/L", price, "price");
            else if (field(2).contains(x, y)) askNumber("Total paid", "R", total, "total");
            else if (field(3).contains(x, y)) askOdometer();
            else if (FULL.contains(x, y)) full = !full;
            else if (MISSED.contains(x, y)) missed = !missed;
            else if (TITLE.contains(x, y)) {
                Keypad.text(act, "Fuel station", station, new ArrayList<String>(), new Keypad.Done() {
                    public void done(String v) { if (v != null) station = v; }
                });
            } else {
                float w = (660 + Wide.d() - 3 * 12) / 4f;
                for (int i = 0; i < LogStore.GRADES.length; i++) {
                    if (gradeRect(i, w).contains(x, y)) {
                        grade = LogStore.GRADES[i];
                        double p = store.lastPrice(grade);
                        if (!priceTyped && p > 0) {          // a price not typed follows the grade
                            price = p;
                            work();
                        }
                    }
                }
            }
        } else if (t.equals("service")) {
            if (field(0).contains(x, y)) {
                Keypad.text(act, "What was done", what, Arrays.asList(SERVICES), new Keypad.Done() {
                    public void done(String v) { if (v != null) what = v; }
                });
            } else if (field(1).contains(x, y)) {
                Keypad.number(act, "Cost", "R", Double.isNaN(cost) ? "" : plain(cost), new Keypad.Done() {
                    public void done(String v) { if (v != null) cost = parse(v); }
                });
            } else if (field(2).contains(x, y)) {
                askOdometer();
            }
        } else if (field(0).contains(x, y)) {
            askOdometer();
        }
    }

    private void askOdometer() {
        Keypad.number(act, "Odometer", "km", Double.isNaN(odometer) ? "" : plain(odometer), new Keypad.Done() {
            public void done(String v) {
                if (v == null) return;
                odometer = parse(v);
                odoTyped = true;
            }
        });
    }

    private void askNumber(String title, String unit, double now, final String which) {
        Keypad.number(act, title, unit, Double.isNaN(now) ? "" : plain(now), new Keypad.Done() {
            public void done(String v) {
                if (v == null) return;
                double d = parse(v);
                if (which.equals("litres")) litres = d;
                else if (which.equals("price")) price = d;
                else total = d;
                touched(which);
            }
        });
    }

    /** Litres, price and total: the one changed longest ago is worked out from the other two. */
    private void touched(String which) {
        edited.remove(which);
        edited.add(which);
        if (which.equals("price")) priceTyped = true;
        work();
    }

    private void work() {
        String worked = edited.get(0);
        if (worked.equals("total") && !Double.isNaN(litres) && !Double.isNaN(price)) total = round(litres * price, 100);
        else if (worked.equals("price") && !Double.isNaN(litres) && !Double.isNaN(total) && litres > 0) price = round(total / litres, 100);
        else if (worked.equals("litres") && !Double.isNaN(price) && !Double.isNaN(total) && price > 0) litres = round(total / price, 100);
    }

    private void save() {
        String t = form.optString("type");
        try {
            if (t.equals("fuel")) {
                if (Double.isNaN(litres) || litres <= 0 || Double.isNaN(total) || total <= 0) {
                    note = "Fill in two of litres, price and total.";
                    return;
                }
                form.put("litres", litres);
                form.put("price", Double.isNaN(price) ? round(total / litres, 100) : price);
                form.put("total", total);
                form.put("grade", grade);
                form.put("full", full);
                form.put("missed", missed);
                form.put("station", station);
            } else if (t.equals("service")) {
                if (what.isEmpty()) {
                    note = "Say what was done.";
                    return;
                }
                form.put("what", what);
                if (!Double.isNaN(cost)) form.put("cost", cost);
            }
            if (!Double.isNaN(odometer) && odometer > 0) form.put("odometer", Math.round(odometer));
            else if (t.equals("odometer")) {
                note = "Type the reading.";
                return;
            }
        } catch (Exception e) { /* cannot happen */ }
        boolean wasPending = pending != null && t.equals("fuel") && formNew && form.optLong("time") == pending.optLong("time");
        store.put(form);
        if (wasPending) book.clearPending();
        mode = selected != null ? Mode.TRIP : Mode.LIST;
        filter = 0;
        scroll.reset();
        refresh();
    }

    // ---- text ----

    private static String place(JSONObject o, String key) {
        JSONObject p = o.optJSONObject(key);
        String n = p == null ? "" : p.optString("name");
        return n.isEmpty() ? "…" : n;
    }

    static String km(double v) { return String.format(Locale.US, "%,d km", Math.round(v)).replace(',', ' '); }

    static String money(double v) { return String.format(Locale.US, "R %,.2f", v).replace(',', ' '); }

    private static String num(double v, String fmt) { return Double.isNaN(v) ? "-" : String.format(Locale.US, fmt, v); }

    private static String plain(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v);
    }

    private static double parse(String s) {
        try {
            return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double round(double v, int per) { return Math.round(v * per) / (double) per; }
}

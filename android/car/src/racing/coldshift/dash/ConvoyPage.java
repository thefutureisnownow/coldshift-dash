package racing.coldshift.dash;

import android.graphics.Bitmap;
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

/**
 * Driving together (Convoy): start a convoy (with a password) or join one by scanning its QR code with
 * the back camera (ScanView); then its QR code for others to scan, the cars in it (how far, ahead or
 * behind, how fast), the messages, quick messages to tap and one to type, and Talk for a voice note (held
 * or tapped: Talk). Voice notes in the list play when tapped. The messages' size is Aa beside their heading
 * (Prefs.convoyText, also in Settings). The others also show on the map; a message
 * arriving on another page shows as a banner (UI).
 *
 * Private messages, so as not to clutter the convoy's: tapping a car (or a watcher) in the list chooses it as
 * the one the messages here go to (a drag still scrolls the list; tapping it again, or "Everyone" in the To bar
 * beside the Aa, goes back to all). While one is chosen everything private is in PRIVATE, the colour of nothing
 * else: the To bar and its timer, the panel's edge, the car's row, and the quick messages, Talk and Type buttons,
 * which say who they go to. It reverts to everyone after PRIVATE_MS without sending anything to them, and on
 * leaving the convoy. A private message to this phone shows "Name → you · private" with a Reply to tap (the
 * sender is chosen), whether or not they are in the list. The map's Talk button always goes to everyone.
 */
class ConvoyPage extends Page implements Convoy.Listener, Talk.Listener {
    private static final RectF START = new RectF(510, 330, 760, 400), FIND = new RectF(770, 330, 1010, 400);
    private static final RectF SCAN = Wide.stretch(new RectF(1020, 330, 1250, 400));
    private static final RectF QR = new RectF(420, 80, 544, 204), PASSWORD = new RectF(26, 222, 548, 282);
    private static final RectF END = new RectF(630, 520, 1000, 576);
    private static final RectF LEAVE = new RectF(30, 553, 250, 607), MAP = new RectF(262, 553, 544, 607);
    private static final RectF TALK = new RectF(598, 553, 920, 607), TYPE = Wide.stretch(new RectF(932, 553, 1250, 607));
    private static final RectF HOST = Wide.right(new RectF(1000, 446, 1250, 496));
    private static final RectF HERE = Wide.right(new RectF(740, 446, 990, 496));
    private static final float FOUND_TOP = 146, FOUND_H = 58;
    private static final int FOUND_ROWS = 3;
    private static final String[] QUICK = {"Stopping for fuel", "Pull over", "All good", "Wait for me", "Slow down",
        "Take the lead"};
    private static final float MEMBERS_TOP = 222, MEMBER_H = 64;
    private static final int MEMBER_ROWS = 5;
    private static final RectF MEMBER_LIST = new RectF(26, MEMBERS_TOP, 548, MEMBERS_TOP + MEMBER_ROWS * MEMBER_H);
    /** The colour of private things (lavender): not gold, which is new or not sent yet. */
    static final int PRIVATE = 0xffc59bff;
    private static final int PRIVATE_TOP = 0xff7c4ddb, PRIVATE_BOTTOM = 0xff4a2a9b, PRIVATE_DARK = 0xff2a1a5c;
    static final long PRIVATE_MS = 2 * 60 * 1000;      // back to everyone after this long without sending to the one chosen
    private static final float TAP_SLOP = 14;          // a touch that moved less than this is a tap, not a drag

    private final Convoy convoy;
    private String note = "";
    private boolean busy, confirmLeave, noteOk, bigQr, asking, confirmEnd, finding;
    private String foundName = "";          // the host found nearby, until it has answered
    private List<Nearby.Found> found;       // convoys nearby, after Convoys nearby (null: not looked)
    private List<Nearby.Host> foundHosts;
    private int askedFor;                   // the refusal last asked about
    private boolean holding;                // Talk is down
    private int memberScroll;               // rows of the car list scrolled past (dragged)
    private float dragY = -1, downX;
    private int dragFrom;
    private boolean dragged;                // the touch on the car list moved: a drag, not a tap
    private final Convoy.Member[] rowCars = new Convoy.Member[MEMBER_ROWS];     // who is in each row drawn (for a tap)
    private String to = "", toName = "";    // the car or watcher the messages here go to (its id), "" for everyone
    private long toUntil;                   // when that reverts to everyone
    private String talkNote = "";
    private long talkNoteUntil;
    // the voice notes drawn in the list, and where (for tapping one to play it)
    private final List<RectF> voiceAt = new ArrayList<RectF>();
    private final List<Convoy.Message> voiceOf = new ArrayList<Convoy.Message>();
    // the private messages to this phone drawn in the list, and where to tap to reply to them
    private final List<RectF> replyAt = new ArrayList<RectF>();
    private final List<Convoy.Message> replyOf = new ArrayList<Convoy.Message>();

    ConvoyPage(Main a) {
        super(a);
        convoy = Convoy.get(a);
        convoy.addListener(this);
        Talk.get(a).addListener(this);
    }

    public void talkChanged() { if (shown()) act.ui().postInvalidate(); }

    String label() { return "Convoy"; }

    @Override boolean wide() { return true; }

    @Override float overlay() { return 0.72f; }

    @Override boolean attention() { return convoy.unread() > 0; }

    @Override int intervalMs() { return Talk.get(act).recording() ? 250 : 1000; }

    @Override void onShow() {
        convoy.read();
        askPassword();
    }

    public void convoyChanged() {
        if (shown()) {
            convoy.read();
            askPassword();
        }
        if (convoy.inConvoy() && convoy.connected() && !busy) note = "";      // what was said before joining is past
        if (!convoy.inConvoy()) everyone();
    }

    // ---- who the messages here go to ----

    /** The id of the one the messages here go to, "" for everyone: that reverts after PRIVATE_MS, and on leaving the convoy. */
    private String recipient() {
        if (!to.isEmpty() && (!convoy.inConvoy() || System.currentTimeMillis() > toUntil)) everyone();
        return to;
    }

    /** Their name now (they may have changed it), else as it was when they were chosen. */
    private String recipientName() {
        String n = convoy.nameOf(to);
        return !n.isEmpty() ? n : toName.isEmpty() ? "someone" : toName;
    }

    private void choose(String id, String name) {
        to = id;
        toName = name == null ? "" : name;
        toUntil = System.currentTimeMillis() + PRIVATE_MS;
    }

    private void everyone() {
        to = "";
        toName = "";
    }

    /** Something went to the one chosen: another PRIVATE_MS. */
    private void sentPrivately() {
        if (!to.isEmpty()) toUntil = System.currentTimeMillis() + PRIVATE_MS;
    }

    /** Says it to the one chosen, or the convoy. */
    private void say(String text) {
        String who = recipient();
        convoy.send(text, who);
        sentPrivately();
    }

    public void convoyMessage(Convoy.Message m) {}

    private boolean shown() { return act.ui() != null && act.ui().current() == this; }

    // ---- drawing ----

    void draw(Canvas c) {
        if (!convoy.inConvoy()) {
            intro(c);
            return;
        }
        if (bigQr) {
            share(c);
            return;
        }
        Theme.panel(c, 14, 68, 560, 625);
        members(c);
        Theme.panel(c, 574, 68, Wide.rx(1266), 625);
        messages(c);
    }

    private void intro(Canvas c) {
        String ended = convoy.takeNote();
        if (!ended.isEmpty()) {
            noteOk = false;
            note = ended.substring(0, 1).toUpperCase(Locale.US) + ended.substring(1) + ".";
        }
        Theme.panel(c, 14, 68, Wide.rx(1266), 625);
        Theme.text(c, "Convoy", 510, 120, 34, 0xffffffff, Theme.bold, Theme.LEFT);
        if (found != null) {
            foundList(c);
        } else {
            String[] lines = {
                "Driving with friends? Start a convoy; the others find it nearby",
                "or scan its QR code. You see each other's cars on the map, how far",
                "ahead or behind they are, and send each other messages and voice notes.",
                "",
                "With no internet, host it on this phone: the others join its hotspot",
                "(or the same Wi-Fi) and find it under Convoys nearby."};
            for (int i = 0; i < lines.length; i++)
                Theme.text(c, lines[i], 510, 160 + i * 28, 19, Theme.TEXT, Theme.sans, Theme.LEFT);
        }
        Theme.button(c, START, true, 12);
        Theme.text(c, busy ? "…" : "Start a convoy", START.centerX(), START.centerY() + 9, 22, 0xffffffff, Theme.bold, Theme.CENTER);
        Theme.button(c, FIND, false, 12);
        Theme.text(c, finding ? "Looking…" : "Convoys nearby", FIND.centerX(), FIND.centerY() + 9, 22, Theme.TEXT, Theme.bold,
            Theme.CENTER);
        Theme.button(c, SCAN, false, 12);
        Theme.text(c, "Scan to join", SCAN.centerX(), SCAN.centerY() + 9, 22, Theme.TEXT, Theme.bold, Theme.CENTER);
        hostLine(c, 510, 456);
        if (!note.isEmpty()) Theme.text(c, Theme.fit(note, 740 + Wide.d(), 17, Theme.bold), 510, 590, 17, noteOk ? Theme.GREEN : Theme.BAD,
            Theme.bold, Theme.LEFT);
        Bitmap car = Profile.carBody(act);
        if (car != null) {
            float h = 300, w = h * car.getWidth() / car.getHeight();
            c.drawBitmap(car, null, new RectF(250 - w / 2, 190, 250 + w / 2, 190 + h), Theme.BMP);
        }
    }

    /** The convoys found nearby, to tap; a host with none is offered to start one on. */
    private void foundList(Canvas c) {
        if (found.isEmpty() && foundHosts.isEmpty()) {
            Theme.text(c, "No convoy nearby. The host phone must be on this Wi-Fi, or this phone on its hotspot.", 510, 170, 17,
                Theme.SUB, Theme.sans, Theme.LEFT);
            return;
        }
        int row = 0;
        for (int i = 0; i < found.size() && row < FOUND_ROWS; i++, row++) {
            Nearby.Found f = found.get(i);
            RectF r = foundRow(row);
            Theme.button(c, r, false, 10);
            Theme.text(c, Theme.fit(f.name, 300 + Wide.d(), 20, Theme.bold), r.left + 18, r.top + 26, 20, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, "on " + f.host.name + " · " + f.cars + (f.cars == 1 ? " car" : " cars") + (f.locked ? " · password" : ""),
                r.left + 18, r.top + 47, 15, Theme.SUB, Theme.sans, Theme.LEFT);
            Theme.text(c, "Join", r.right - 18, r.centerY() + 7, 20, Theme.BLUE_HI, Theme.bold, Theme.RIGHT);
        }
        for (int i = 0; i < foundHosts.size() && row < FOUND_ROWS; i++) {
            Nearby.Host h = foundHosts.get(i);
            if (!h.convoys.isEmpty()) continue;
            RectF r = foundRow(row++);
            Theme.button(c, r, false, 10);
            Theme.text(c, Theme.fit(h.name, 300 + Wide.d(), 20, Theme.bold), r.left + 18, r.top + 26, 20, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, "a convoy host with no convoy yet", r.left + 18, r.top + 47, 15, Theme.SUB, Theme.sans, Theme.LEFT);
            Theme.text(c, "Use it", r.right - 18, r.centerY() + 7, 20, Theme.BLUE_HI, Theme.bold, Theme.RIGHT);
        }
    }

    private static RectF foundRow(int i) {
        float t = FOUND_TOP + i * FOUND_H;
        return new RectF(510, t, Wide.rx(1250), t + FOUND_H - 8);
    }

    /** "Convoy host: …", Change host and Host on this phone. */
    private void hostLine(Canvas c, float x, float y) {
        String host = convoy.server();
        boolean here = convoy.hosting();
        Theme.text(c, "Convoy host", x, y, 15, Theme.SUB, Theme.bold, Theme.LEFT);
        Theme.text(c, here ? "This phone" : Theme.fit(host.isEmpty() ? "none yet: choose one" : host.replaceFirst("^https?://", ""),
            220, 18, Theme.bold), x, y + 28, 18, host.isEmpty() ? Theme.GOLD : 0xffffffff, Theme.bold, Theme.LEFT);
        if (here) {
            Theme.text(c, "No internet needed: the others join this", x, y + 56, 15, Theme.SUB, Theme.sans, Theme.LEFT);
            Theme.text(c, "phone's hotspot or Wi-Fi and find it nearby.", x, y + 76, 15, Theme.SUB, Theme.sans, Theme.LEFT);
        }
        Theme.button(c, HOST, false, 12);
        Theme.text(c, "Change host", HOST.centerX(), HOST.centerY() + 7, 19, Theme.TEXT, Theme.sans, Theme.CENTER);
        Theme.button(c, HERE, here, 12);
        Theme.text(c, here ? "Hosting here" : "Host on this phone", HERE.centerX(), HERE.centerY() + 7, 19,
            here ? 0xffffffff : Theme.TEXT, here ? Theme.bold : Theme.sans, Theme.CENTER);
    }

    private void members(Canvas c) {
        Theme.text(c, Theme.fit(convoy.name().isEmpty() ? "Convoy" : convoy.name(), 500, 26, Theme.bold), 34, 108, 26,
            0xffffffff, Theme.bold, Theme.LEFT);
        boolean on = convoy.connected();
        Theme.text(c, Theme.fit((on ? "Connected" : convoy.error().isEmpty() ? "Connecting…" : convoy.error()) + " · "
            + (convoy.hosting() ? "hosted on this phone" : convoy.server().replaceFirst("^https?://", "")), 500, 16, Theme.sans), 34, 136, 16, on ? Theme.GREEN : Theme.GOLD,
            Theme.sans, Theme.LEFT);
        String refused = convoy.refused();
        boolean out = refused.equals("password") || refused.equals("ended") || refused.equals("full");
        Arrays.fill(rowCars, null);
        String chosen = recipient();
        Bitmap qr = convoy.qr();
        if (convoy.local() && !out) {
            // found nearby, not scanned: a QR code's address may not be one the others can reach
            Theme.text(c, convoy.hosting() ? "No internet needed: the others join this phone's Wi-Fi or hotspot." : "On a host nearby.", 34, 172, 15,
                Theme.SUB, Theme.sans, Theme.LEFT);
            Theme.text(c, Theme.fit("Others: Convoy, Convoys nearby" + (convoy.locked() ? " · password: " + convoy.password() : ""),
                500, 15, Theme.sans), 34, 196, 15, convoy.locked() ? Theme.SUB : Theme.GOLD, Theme.sans, Theme.LEFT);
        } else if (qr != null && !out) {
            c.drawBitmap(qr, null, QR, null);
            Theme.text(c, convoy.locked() ? "Password protected" : "No password: the QR code is enough", 34, 172, 15,
                convoy.locked() ? Theme.SUB : Theme.GOLD, Theme.sans, Theme.LEFT);
            Theme.text(c, "Tap the QR code to show it large", 34, 196, 15, Theme.SUB, Theme.sans, Theme.LEFT);
        }
        if (out) {
            if (refused.equals("password")) {
                Theme.button(c, PASSWORD, true, 12);
                Theme.text(c, "Type the password", PASSWORD.centerX(), PASSWORD.centerY() + 8, 21, 0xffffffff, Theme.bold,
                    Theme.CENTER);
            }
            if (!refused.equals("password"))
                Theme.text(c, "Leave, then start a convoy or scan another.", 34, 250, 17, Theme.TEXT, Theme.sans, Theme.LEFT);
            leaveAndMap(c);
            return;
        }
        Location here = Gps.get(act).last();
        List<Convoy.Member> cars = column(here);
        memberScroll = Math.max(0, Math.min(memberScroll, cars.size() - MEMBER_ROWS));
        for (int row = 0; row < MEMBER_ROWS && memberScroll + row < cars.size(); row++) {
            Convoy.Member m = cars.get(memberScroll + row);
            boolean me = m.id.equals(convoy.me());
            float t = MEMBERS_TOP + row * MEMBER_H;
            RectF r = new RectF(26, t, 548, t + MEMBER_H - 8);
            boolean picked = !me && m.id.equals(chosen);
            rowCars[row] = m;
            if (picked) privateButton(c, r, true, 10);
            else Theme.button(c, r, me, 10);
            Theme.P.setColor(m.online || me ? Theme.GREEN : Theme.DIM);
            c.drawCircle(r.left + 16, r.centerY(), 5, Theme.P);
            Bitmap car = me ? Profile.carBody(act) : m.car;
            if (car != null) {
                float h = 44, w = h * car.getWidth() / car.getHeight();
                c.drawBitmap(car, null, new RectF(r.left + 44 - w / 2, r.centerY() - h / 2, r.left + 44 + w / 2,
                    r.centerY() + h / 2), Theme.BMP);
            }
            Theme.text(c, Theme.fit(me ? convoy.carName() : m.name, 330, 19, Theme.bold), r.left + 74, r.top + 25, 19, 0xffffffff,
                Theme.bold, Theme.LEFT);
            Theme.text(c, me ? (convoy.hosting() ? "you · hosting the convoy" : "you") : where(m, here), r.left + 74, r.top + 47, 15,
                me || picked ? 0xffffffff : Theme.SUB, Theme.sans, Theme.LEFT);
            if (picked) Theme.text(c, "Private", r.right - 14, r.top + 20, 14, 0xffe9dcff, Theme.bold, Theme.RIGHT);
            double speed = me ? (here != null ? here.getSpeed() : Double.NaN) : Double.isNaN(m.lat) ? Double.NaN : m.speed;
            if (!Double.isNaN(speed) && "vehicle".equals(m.role))
                Theme.text(c, Math.round(speed * 3.6) + " km/h", r.right - 14, r.top + (picked ? 42 : 36), 18, Theme.TEXT, Theme.bold, Theme.RIGHT);
        }
        // how to choose one, or that one is
        if (cars.size() > 1)
            Theme.text(c, Theme.fit(chosen.isEmpty() ? "Tap a car to message it privately" : "Private to " + recipientName()
                + " · tap it again for everyone", 510, 13, Theme.bold), 34, MEMBERS_TOP - 8, 13, chosen.isEmpty() ? Theme.SUB : PRIVATE,
                chosen.isEmpty() ? Theme.sans : Theme.bold, Theme.LEFT);
        int below = cars.size() - memberScroll - MEMBER_ROWS;
        if (memberScroll > 0 || below > 0)
            Theme.text(c, (memberScroll > 0 ? memberScroll + " above" : "") + (memberScroll > 0 && below > 0 ? " · " : "")
                + (below > 0 ? below + " below" : "") + ": drag the list", 548, MEMBERS_TOP - 8, 13, Theme.SUB, Theme.sans, Theme.RIGHT);
        if (cars.size() <= 1) {
            Theme.text(c, convoy.local() ? "Nobody else yet: they find it under Convoys nearby." : "Nobody else yet: let them scan the QR code.",
                34, MEMBERS_TOP + MEMBER_H + 24, 17, Theme.SUB, Theme.sans,     // under this car's own row
                Theme.LEFT);
        }
        leaveAndMap(c);
    }

    private void leaveAndMap(Canvas c) {
        Theme.button(c, LEAVE, false, 12);
        boolean ends = convoy.hosting() && convoy.starter();       // the relay goes with this phone
        Theme.text(c, confirmLeave ? (ends ? "Tap: ends for all" : "Tap again") : ends ? "End convoy" : "Leave", LEAVE.centerX(),
            LEAVE.centerY() + 7, 19,
            confirmLeave ? Theme.BAD : Theme.TEXT, Theme.sans, Theme.CENTER);
        Theme.button(c, MAP, false, 12);
        Theme.text(c, "Show on the map", MAP.centerX(), MAP.centerY() + 7, 19, Theme.TEXT, Theme.sans, Theme.CENTER);
    }

    /** The QR code large, for scanning from another car's phone. */
    private void share(Canvas c) {
        Theme.panel(c, 14, 68, Wide.rx(1266), 625);
        Bitmap qr = convoy.qr();
        if (qr != null) {
            Theme.P.setColor(0xffffffff);
            c.drawRoundRect(new RectF(50, 88, 586, 606), 12, 12, Theme.P);
            c.drawBitmap(qr, null, new RectF(68, 106, 568, 588), null);
        }
        Theme.text(c, Theme.fit(convoy.name(), 600, 30, Theme.bold), 630, 150, 30, 0xffffffff, Theme.bold, Theme.LEFT);
        String[] lines = {
            "On the other car's phone: Convoy, then Scan to join,",
            "and hold its back camera up to this code.",
            "",
            convoy.locked() ? "They also need the password: " + convoy.password() : "No password: this code is enough to join.",
            "",
            "In a browser, the same link follows the convoy:"};
        for (int i = 0; i < lines.length; i++)
            Theme.text(c, lines[i], 630, 200 + i * 30, 19, i == 3 && !convoy.locked() ? Theme.GOLD : Theme.TEXT, Theme.sans,
                Theme.LEFT);
        Theme.text(c, Theme.fit(convoy.link(), 610, 16, Theme.sans), 630, 380, 16, Theme.SUB, Theme.sans, Theme.LEFT);
        if (convoy.starter()) {
            Theme.button(c, END, false, 12);
            Theme.text(c, confirmEnd ? "Tap again: everyone leaves" : "End the convoy for everyone", END.centerX(),
                END.centerY() + 7, 18, Theme.BAD, Theme.bold, Theme.CENTER);
            Theme.text(c, "Tap elsewhere to close", 630, 606, 15, Theme.SUB, Theme.sans, Theme.LEFT);
        } else {
            Theme.text(c, "Tap anywhere to close", 630, 590, 16, Theme.SUB, Theme.sans, Theme.LEFT);
        }
    }

    /**
     * Every car in the convoy, this one too (made up from this phone when the server has not said it yet),
     * and those watching online: in the column's order, front first, while moving (how far along this car's
     * way each is); in the order they joined, this car first, while standing. No position: at the end.
     */
    private List<Convoy.Member> column(final Location here) {
        List<Convoy.Member> out = new ArrayList<Convoy.Member>();
        boolean mine = false;
        for (Convoy.Member m : convoy.members()) {
            if (!m.online && !"vehicle".equals(m.role) && !m.id.equals(convoy.me())) continue;
            if (m.id.equals(convoy.me())) {
                mine = true;
                out.add(0, m);
            } else {
                out.add(m);
            }
        }
        if (!mine) {
            Convoy.Member me = new Convoy.Member();
            me.id = convoy.me();
            out.add(0, me);
        }
        if (here == null || here.getSpeed() <= 2) return out;
        final java.util.Map<Convoy.Member, Double> along = new java.util.HashMap<Convoy.Member, Double>();
        for (Convoy.Member m : out) {
            if (m.id.equals(convoy.me())) along.put(m, 0.0);
            else if (Double.isNaN(m.lat) || !"vehicle".equals(m.role)) along.put(m, Double.NEGATIVE_INFINITY);
            else {
                double d = Nav.dist(here.getLatitude(), here.getLongitude(), m.lat, m.lon);
                double to = Math.toDegrees(Math.atan2(
                    Math.toRadians(m.lon - here.getLongitude()) * Math.cos(Math.toRadians(here.getLatitude())),
                    Math.toRadians(m.lat - here.getLatitude())));
                along.put(m, d * Math.cos(Math.toRadians(to - here.getBearing())));
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<Convoy.Member>() {
            public int compare(Convoy.Member a, Convoy.Member b) { return Double.compare(along.get(b), along.get(a)); }
        });
        return out;
    }

    /** "1.2 km ahead", "300 m behind", "watching", "offline 5 min"... */
    private static String where(Convoy.Member m, Location here) {
        if (!"vehicle".equals(m.role)) return m.online ? "watching" : "was watching";
        if (Double.isNaN(m.lat)) return m.online ? "no position yet" : "offline";
        String age = "";
        long s = (System.currentTimeMillis() - m.time) / 1000;
        if (!m.online || s > 60) age = (m.online ? " · " : " · offline, ") + (s < 3600 ? s / 60 + " min ago" : s / 3600 + " h ago");
        if (here == null) return "position known" + age;
        double d = Nav.dist(here.getLatitude(), here.getLongitude(), m.lat, m.lon);
        String dist = d < 1000 ? Math.round(d / 10) * 10 + " m" : String.format(Locale.US, "%.1f km", d / 1000);
        String side = "";
        if (here.getSpeed() > 2 && d > 30) {
            double to = Math.toDegrees(Math.atan2(
                Math.toRadians(m.lon - here.getLongitude()) * Math.cos(Math.toRadians(here.getLatitude())),
                Math.toRadians(m.lat - here.getLatitude())));
            double diff = Math.abs(((to - here.getBearing()) % 360 + 540) % 360 - 180);
            side = diff < 90 ? " ahead" : " behind";
        } else {
            side = " away";
        }
        return dist + side + age;
    }

    /** The Aa button beside the Messages heading: each tap takes the messages one size up (Prefs.convoyText), then round. */
    private static RectF aa() {
        float l = 598 + Theme.width("Messages", 24, Theme.bold) + 16;
        return new RectF(l, 74, l + 72, 118);
    }

    /** The To bar beside the Aa, while messages go to one: "To Malva Pudding" and the way back to everyone. */
    private static RectF toBar() {
        RectF aa = aa();
        return new RectF(aa.right + 14, 74, Wide.rx(1250), 118);
    }

    /** Its Everyone button (the touch area runs the bar's whole height). */
    private static RectF everyoneButton() {
        RectF bar = toBar();
        return new RectF(bar.right - 172, bar.top + 4, bar.right - 4, bar.bottom - 4);
    }

    private void toBar(Canvas c) {
        RectF bar = toBar(), btn = everyoneButton();
        Theme.P.setShader(null);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        Theme.P.setColor(PRIVATE_DARK);
        c.drawRoundRect(bar, 10, 10, Theme.P);
        Theme.P.setStyle(android.graphics.Paint.Style.STROKE);
        Theme.P.setStrokeWidth(2.5f);
        Theme.P.setColor(PRIVATE);
        c.drawRoundRect(bar, 10, 10, Theme.P);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        float x = bar.left + 16, toW = Theme.width("To ", 24, Theme.bold);
        Theme.text(c, "To ", x, bar.centerY() + 8, 24, PRIVATE, Theme.bold, Theme.LEFT);
        Theme.text(c, Theme.fit(recipientName(), btn.left - 12 - x - toW, 24, Theme.bold), x + toW, bar.centerY() + 8, 24, 0xffffffff,
            Theme.bold, Theme.LEFT);
        // the time left before it goes back to everyone
        float left = Math.max(0, Math.min(1, (toUntil - System.currentTimeMillis()) / (float) PRIVATE_MS));
        Theme.P.setColor(0xaac59bff);
        c.drawRect(x, bar.bottom - 7, x + (btn.left - 12 - x) * left, bar.bottom - 4, Theme.P);
        Theme.P.setColor(PRIVATE);
        c.drawRoundRect(btn, 8, 8, Theme.P);
        Theme.text(c, "× Everyone", btn.centerX(), btn.centerY() + 7, 20, PRIVATE_DARK, Theme.bold, Theme.CENTER);
    }

    /** A button in the private colour: solid (the one that matters) or dark with a private edge. */
    private static void privateButton(Canvas c, RectF r, boolean solid, float radius) {
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        if (solid) {
            Theme.P.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.bottom, PRIVATE_TOP, PRIVATE_BOTTOM,
                android.graphics.Shader.TileMode.CLAMP));
        } else {
            Theme.P.setShader(null);
            Theme.P.setColor(PRIVATE_DARK);
        }
        c.drawRoundRect(r, radius, radius, Theme.P);
        Theme.P.setShader(null);
        Theme.P.setStyle(android.graphics.Paint.Style.STROKE);
        Theme.P.setStrokeWidth(2.5f);
        Theme.P.setColor(PRIVATE);
        c.drawRoundRect(r, radius, radius, Theme.P);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
    }

    private void messages(Canvas c) {
        String chosen = recipient();
        boolean priv = !chosen.isEmpty();
        if (priv) {
            // keep it going while a note to them is being recorded; and make the whole panel say so
            if (chosen.equals(Talk.get(act).recipient())) sentPrivately();
            Theme.P.setShader(null);
            Theme.P.setStyle(android.graphics.Paint.Style.STROKE);
            Theme.P.setStrokeWidth(3.5f);
            Theme.P.setColor(PRIVATE);
            c.drawRoundRect(new RectF(574, 68, Wide.rx(1266), 625), 14, 14, Theme.P);
            Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        }
        Theme.text(c, "Messages", 598, 106, 24, 0xffffffff, Theme.bold, Theme.LEFT);
        int step = Prefs.convoyText(act);
        float s = Prefs.convoyTextScale(act), colW = 630 + Wide.d();      // everything in the list grows by s
        RectF aa = aa();
        Theme.button(c, aa, step > 0, 10);
        Theme.text(c, "Aa", aa.centerX(), aa.centerY() + (19 + 3 * step) * 0.35f, 19 + 3 * step, step > 0 ? 0xffffffff : Theme.TEXT,
            Theme.bold, Theme.CENTER);
        if (priv) toBar(c);
        List<Convoy.Message> ms = convoy.messages();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        Convoy.Message now = Talk.get(act).playing();
        voiceAt.clear();
        voiceOf.clear();
        replyAt.clear();
        replyOf.clear();
        float y = 420;                                     // newest at the bottom, going up
        for (int i = ms.size() - 1; i >= 0 && y > 130; i--) {
            Convoy.Message m = ms.get(i);
            List<String> lines = m.isVoice() ? voiceLines(m, m == now, colW - 32 * s, 19 * s) : wrap(m.text, colW, 19 * s, s > 1 ? 4 : 3);
            float top = y - 24 * s * lines.size() - 20 * s;
            int room = (int) ((y - 20 * s - 120) / (24 * s));             // lines that fit above: the oldest shown gets those
            if (top < 120 && !m.isVoice() && room >= 1) {
                lines = wrap(m.text, colW, 19 * s, room);
                top = y - 24 * s * lines.size() - 20 * s;
            }
            if (top < 120) break;
            boolean mine = convoy.fromMe(m), reply = convoy.toMe(m);
            String meta;
            if (m.isPrivate()) {          // "Malva Pudding → you · private · 12:03"
                meta = Theme.fit(mine ? "You" : m.name, colW / 4, 14 * s, Theme.bold) + " → "
                    + Theme.fit(mine ? convoy.toName(m) : "you", colW / 4, 14 * s, Theme.bold) + " · private · " + hm.format(new Date(m.time));
            } else {
                meta = Theme.fit(mine ? "You" : m.name, colW / 2, 14 * s, Theme.bold) + " · " + hm.format(new Date(m.time));
            }
            if (m.pending) meta += s > 1 ? " · not sent yet" : " · not sent yet: it goes when the connection is back";
            else if (m.late > 0) meta += " · " + late(m.late);
            float chipW = reply ? Theme.width("Reply", 14 * s, Theme.bold) + 20 * s : 0;   // Reply, at the meta line's right
            Theme.text(c, Theme.fit(meta, colW - (reply ? chipW + 12 * s : 0), 14 * s, Theme.bold), 598, top + 14 * s, 14 * s,
                m.pending ? Theme.GOLD : m.isPrivate() ? PRIVATE : mine ? Theme.BLUE_HI : Theme.SUB, Theme.bold, Theme.LEFT);
            if (m.isPrivate()) {                                // a bar down its left edge
                Theme.P.setShader(null);
                Theme.P.setStyle(android.graphics.Paint.Style.FILL);
                Theme.P.setColor(PRIVATE);
                c.drawRect(586, top + 2 * s, 589.5f, y - 5 * s, Theme.P);
            }
            if (reply) {
                RectF chip = new RectF(598 + colW - chipW, top - 2 * s, 598 + colW, top + 20 * s);
                Theme.P.setShader(null);
                Theme.P.setStyle(android.graphics.Paint.Style.STROKE);
                Theme.P.setStrokeWidth(1.5f);
                Theme.P.setColor(PRIVATE);
                c.drawRoundRect(chip, 8 * s, 8 * s, Theme.P);
                Theme.P.setStyle(android.graphics.Paint.Style.FILL);
                Theme.text(c, "Reply", chip.centerX(), top + 14 * s, 14 * s, PRIVATE, Theme.bold, Theme.CENTER);
                // a voice note keeps tap-to-play, so only the meta line replies; a text message replies anywhere on it
                replyAt.add(m.isVoice() ? new RectF(chip.left - 14 * s, top - 8 * s, chip.right + 10, top + 26 * s)
                    : new RectF(586, top - 4 * s, chip.right + 10, y));
                replyOf.add(m);
            }
            if (m.isVoice()) {
                float wide = 0;
                for (String l : lines) wide = Math.max(wide, Theme.width(l, 19 * s, m.heard ? Theme.sans : Theme.bold));
                voiceAt.add(new RectF(598 - 6 * s, top - 4 * s, Math.min(598 + colW, 598 + Math.max(300 * s, 44 * s + wide)),
                    top + (52 + 24 * (lines.size() - 1)) * s));
                voiceOf.add(m);
                playIcon(c, 598 + 12 * s, top + (32 + 12 * (lines.size() - 1)) * s, m == now, m.heard ? 0xffffffff : Theme.GOLD, s);
                for (int k = 0; k < lines.size(); k++)
                    Theme.text(c, lines.get(k), 598 + 32 * s, top + (39 + 24 * k) * s, 19 * s, m.heard || m == now ? 0xffffffff : Theme.GOLD,
                        m.heard ? Theme.sans : Theme.bold, Theme.LEFT);
            } else {
                for (int k = 0; k < lines.size(); k++)
                    Theme.text(c, lines.get(k), 598, top + (38 + 24 * k) * s, 19 * s, 0xffffffff, Theme.sans, Theme.LEFT);
            }
            y = top - 10 * s;
        }
        String who = priv ? recipientName() : "";
        if (ms.isEmpty()) Theme.text(c, priv ? Theme.fit("No messages yet. Tap one below to send it to " + who + " only.", 640 + Wide.d(), 17,
            Theme.sans) : "No messages yet. Tap one below to send it.", 598, 150, 17, priv ? PRIVATE : Theme.SUB, Theme.sans, Theme.LEFT);
        for (int i = 0; i < QUICK.length; i++) {
            RectF r = quick(i);
            if (priv) privateButton(c, r, false, 10);
            else Theme.button(c, r, false, 10);
            Theme.text(c, QUICK[i], r.centerX(), r.centerY() + 7, 18, priv ? 0xffffffff : Theme.TEXT, Theme.sans, Theme.CENTER);
        }
        talkButton(c, TALK, act, who);
        boolean latched = Talk.get(act).latched();
        if (priv && !latched) privateButton(c, TYPE, true, 12);
        else Theme.button(c, TYPE, false, 12);
        String typeLabel = "Type a message…";
        if (priv) typeLabel = "Message " + Theme.fit(who, TYPE.width() - 28 - Theme.width("Message ", 21, Theme.bold), 21, Theme.bold);
        Theme.text(c, latched ? "Cancel" : typeLabel, TYPE.centerX(), TYPE.centerY() + 8, 21, latched ? Theme.BAD : priv ? 0xffffffff : Theme.TEXT,
            Theme.bold, Theme.CENTER);
        String tn = Talk.get(act).takeNote();
        if (!tn.isEmpty()) {
            talkNote = tn;
            talkNoteUntil = System.currentTimeMillis() + 6000;
        }
        String n = !note.isEmpty() ? note : System.currentTimeMillis() < talkNoteUntil ? talkNote : "";
        if (!n.isEmpty()) Theme.text(c, Theme.fit(n, 640 + Wide.d(), 15, Theme.bold), 598, 140, 15, Theme.BAD, Theme.bold, Theme.LEFT);
    }

    /**
     * The Talk button (here and on the map): a microphone and Talk; while recording, gold with the time and
     * what lets it go (letting go when held, a tap when tapped).
     */
    static void talkButton(Canvas c, RectF r, android.content.Context act) { talkButton(c, r, act, ""); }

    /**
     * The same, saying whom it talks to when `toName` is not empty (the car chosen on the Convoy page: private
     * colour, "Talk to Malva Pudding"). While recording it says whom the note in the making goes to, from wherever
     * it began, since that is where it goes.
     */
    static void talkButton(Canvas c, RectF r, android.content.Context act, String toName) {
        Talk t = Talk.get(act);
        boolean small = r.width() < 220;
        if (t.recording()) {
            boolean priv = !t.recipient().isEmpty();
            String rn = t.recipientName().isEmpty() ? "someone" : t.recipientName();
            Theme.P.setShader(null);
            Theme.P.setStyle(android.graphics.Paint.Style.FILL);
            Theme.P.setColor(Theme.GOLD);
            c.drawRoundRect(r, 12, 12, Theme.P);
            if (priv) {
                Theme.P.setStyle(android.graphics.Paint.Style.STROKE);
                Theme.P.setStrokeWidth(5f);
                Theme.P.setColor(PRIVATE);
                c.drawRoundRect(new RectF(r.left + 2, r.top + 2, r.right - 2, r.bottom - 2), 11, 11, Theme.P);
                Theme.P.setStyle(android.graphics.Paint.Style.FILL);
            }
            int ink = 0xff070e1b;
            if (small) {
                if (priv) Theme.text(c, Theme.fit("to " + rn, r.width() - 24, 14, Theme.bold), r.centerX(), r.top + 24, 14, ink, Theme.bold,
                    Theme.CENTER);
                Theme.text(c, t.latched() ? "Tap to send" : "Let go", r.centerX(), r.centerY() + (priv ? 2 : -4), 20, ink, Theme.bold,
                    Theme.CENTER);
                Theme.text(c, t.clock(), r.centerX(), r.centerY() + (priv ? 26 : 22), 16, ink, Theme.bold, Theme.CENTER);
            } else {
                Theme.P.setColor(0xffd0021b);
                if (System.currentTimeMillis() / 500 % 2 == 0) c.drawCircle(r.left + 24, r.centerY(), 7, Theme.P);
                if (priv) {          // two lines: who, then the clock
                    Theme.text(c, Theme.fit((t.latched() ? "Tap to send to " : "Let go to send to ") + rn, r.width() - 64, 18, Theme.bold),
                        r.centerX() + 10, r.centerY() - 2, 18, ink, Theme.bold, Theme.CENTER);
                    Theme.text(c, t.clock(), r.centerX() + 10, r.centerY() + 18, 15, ink, Theme.bold, Theme.CENTER);
                } else {
                    Theme.text(c, (t.latched() ? "Tap to send  " : "Let go to send  ") + t.clock(), r.centerX() + 10, r.centerY() + 8, 20,
                        ink, Theme.bold, Theme.CENTER);
                }
            }
            return;
        }
        boolean priv = !small && !toName.isEmpty();
        if (priv) privateButton(c, r, true, 12);
        else Theme.button(c, r, true, 12);
        String label = priv ? "Talk to " + Theme.fit(toName, r.width() - 70 - Theme.width("Talk to ", 22, Theme.bold), 22, Theme.bold) : "Talk";
        float size = priv ? 22 : 23;
        float tw = Theme.width(label, size, Theme.bold);
        float cx = small ? r.centerX() : r.centerX() - tw / 2 - 6, cy = small ? r.centerY() - 14 : r.centerY();
        mic(c, small ? cx : cx - 4, cy, 0xffffffff);
        if (small) Theme.text(c, "Talk", r.centerX(), r.centerY() + 30, 21, 0xffffffff, Theme.bold, Theme.CENTER);
        else Theme.text(c, label, cx + 18, r.centerY() + 8, size, 0xffffffff, Theme.bold, Theme.LEFT);
    }

    /** A microphone about 30 high, centred on (x, y). */
    private static void mic(Canvas c, float x, float y, int colour) {
        Theme.P.setShader(null);
        Theme.P.setColor(colour);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        c.drawRoundRect(new RectF(x - 6, y - 15, x + 6, y + 4), 6, 6, Theme.P);
        Theme.P.setStyle(android.graphics.Paint.Style.STROKE);
        Theme.P.setStrokeWidth(2.5f);
        c.drawArc(new RectF(x - 10, y - 8, x + 10, y + 9), 0, 180, false, Theme.P);
        c.drawLine(x, y + 9, x, y + 15, Theme.P);
        c.drawLine(x - 6, y + 15, x + 6, y + 15, Theme.P);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
    }

    /** A play triangle, or a stop square while it plays, centred on (x, y), s times its usual size. */
    private static void playIcon(Canvas c, float x, float y, boolean playing, int colour, float s) {
        Theme.P.setShader(null);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        Theme.P.setColor(colour);
        if (playing) {
            c.drawRect(x - 7 * s, y - 7 * s, x + 7 * s, y + 7 * s, Theme.P);
            return;
        }
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x - 7 * s, y - 9 * s);
        p.lineTo(x + 9 * s, y);
        p.lineTo(x - 7 * s, y + 9 * s);
        p.close();
        c.drawPath(p, Theme.P);
    }

    /**
     * What a voice note says in the list: one line ("Voice note · 8 s · new, tap to play"), or two when
     * that is wider than maxW at this size (the length, then what to do), each fitted.
     */
    private static List<String> voiceLines(Convoy.Message m, boolean playing, float maxW, float size) {
        String says = m.says(), tail = playing ? "playing, tap to stop" : m.heard ? "" : "new, tap to play";
        android.graphics.Typeface tf = m.heard ? Theme.sans : Theme.bold;
        List<String> out = new ArrayList<String>();
        String full = tail.isEmpty() ? says : says + " · " + tail;
        if (Theme.width(full, size, tf) <= maxW) {
            out.add(full);
        } else {
            out.add(Theme.fit(says, maxW, size, tf));
            if (!tail.isEmpty()) out.add(Theme.fit(tail, maxW, size, tf));
        }
        return out;
    }

    /** "came 3 min late": a message that waited on its sender's phone for a connection. */
    static String late(int s) { return "came " + (s < 90 ? s + " s" : Math.round(s / 60f) + " min") + " late"; }

    private static RectF quick(int i) {
        float w = (652 + Wide.d() - 2 * 10) / 3f, x = 598 + (i % 3) * (w + 10), y = 436 + (i / 3) * 56;
        return new RectF(x, y, x + w, y + 48);
    }

    /** The text in lines no wider than maxW at this size, at most maxLines (the last ends in … if there was more). */
    private static List<String> wrap(String text, float maxW, float size, int maxLines) {
        List<String> out = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String w : text.split("\\s+")) {
            String next = line.length() == 0 ? w : line + " " + w;
            if (Theme.width(next, size, Theme.sans) > maxW && line.length() > 0) {
                out.add(Theme.fit(line.toString(), maxW, size, Theme.sans));
                line = new StringBuilder(w);
                if (out.size() == maxLines) {
                    out.set(maxLines - 1, Theme.fit(out.get(maxLines - 1) + "\u2026", maxW, size, Theme.sans));
                    return out;
                }
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0 && out.size() < maxLines) out.add(Theme.fit(line.toString(), maxW, size, Theme.sans));
        return out;
    }

    // ---- touch ----

    @Override boolean touch(MotionEvent e) {
        int a = e.getAction();
        float x = e.getX(), y = e.getY();
        // Talk: down starts (or sends one tapped into recording), up sends a hold
        if (convoy.inConvoy() && !bigQr && a == MotionEvent.ACTION_DOWN && TALK.contains(x, y)) {
            holding = true;
            String who = recipient();          // the car chosen, else everyone (the map's button always goes to everyone)
            Talk.get(act).press(who, who.isEmpty() ? "" : recipientName());
            sentPrivately();
            return true;
        }
        if (holding) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                holding = false;
                Talk.get(act).release();
            }
            return true;
        }
        // the car list scrolls when dragged; a tap on a car chooses it to send private messages to
        if (convoy.inConvoy() && !bigQr) {
            if (a == MotionEvent.ACTION_DOWN && MEMBER_LIST.contains(x, y)) {
                dragY = y;
                downX = x;
                dragged = false;
                dragFrom = memberScroll;
            } else if (a == MotionEvent.ACTION_MOVE && dragY >= 0) {
                if (Math.abs(y - dragY) > TAP_SLOP || Math.abs(x - downX) > TAP_SLOP) dragged = true;
                memberScroll = Math.max(0, dragFrom - Math.round((y - dragY) / MEMBER_H));
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                boolean tap = a == MotionEvent.ACTION_UP && dragY >= 0 && !dragged && MEMBER_LIST.contains(x, y);
                dragY = -1;
                if (tap && tapCar(y)) return true;
            }
        }
        if (a != MotionEvent.ACTION_UP) return true;
        if (!convoy.inConvoy()) {
            if (START.contains(x, y) && !busy) askName();
            else if (SCAN.contains(x, y) && !busy) scan();
            else if (FIND.contains(x, y) && !finding) findNearby();
            else if (HOST.contains(x, y)) askHost();
            else if (HERE.contains(x, y) && !convoy.hosting()) {
                convoy.hostHere();
                found = null;
                noteOk = true;
                note = "Hosting on this phone: start a convoy, and the others find it under Convoys nearby.";
            } else if (found != null) {
                tapFound(x, y);
            }
            return true;
        }
        if (bigQr) {
            if (convoy.starter() && END.contains(x, y)) {
                if (confirmEnd) {
                    bigQr = false;
                    convoy.end(result);
                }
                confirmEnd = !confirmEnd;
                return true;
            }
            confirmEnd = false;
            bigQr = false;
            return true;
        }
        if (QR.contains(x, y) && convoy.qr() != null) {
            bigQr = true;
            return true;
        }
        if (PASSWORD.contains(x, y) && convoy.refused().equals("password")) {
            askedFor = -1;                    // asked for by hand: ask again whatever was asked before
            askPassword();
            return true;
        }
        if (LEAVE.contains(x, y)) {
            if (confirmLeave && convoy.hosting() && convoy.starter()) convoy.end(result);
            else if (confirmLeave) convoy.leave();
            confirmLeave = !confirmLeave;
            return true;
        }
        confirmLeave = false;
        if (MAP.contains(x, y)) {
            act.ui().show("maps");
        } else if (aa().contains(x, y)) {
            Prefs.setConvoyText(act, (Prefs.convoyText(act) + 1) % Prefs.CONVOY_TEXT.length);
        } else if (!recipient().isEmpty() && new RectF(everyoneButton().left - 10, toBar().top, toBar().right, toBar().bottom).contains(x, y)) {
            everyone();                           // the way back to everyone
        } else if (TYPE.contains(x, y) && Talk.get(act).latched()) {
            Talk.get(act).cancel();
        } else if (TYPE.contains(x, y)) {
            // the keyboard says whom it goes to, and it goes to them even if the time runs out meanwhile
            final String who = recipient(), whoName = who.isEmpty() ? "" : recipientName();
            Keypad.text(act, who.isEmpty() ? "Message to the convoy" : "Private message to " + whoName, "", new ArrayList<String>(),
                new Keypad.Done() {
                    public void done(String v) {
                        if (v == null || v.isEmpty()) return;
                        convoy.send(v, who);
                        if (who.equals(to)) sentPrivately();
                    }
                });
        } else {
            for (int i = 0; i < QUICK.length; i++) if (quick(i).contains(x, y)) say(QUICK[i]);
            for (int i = 0; i < replyAt.size(); i++) {
                if (!replyAt.get(i).contains(x, y)) continue;
                Convoy.Message m = replyOf.get(i);          // a private message to this phone: reply to its sender
                choose(m.from, m.name);
                return true;
            }
            for (int i = 0; i < voiceAt.size(); i++) {
                if (!voiceAt.get(i).contains(x, y)) continue;
                Talk.get(act).toggle(voiceOf.get(i));
                break;                                    // the newest, where two overlap
            }
        }
        return true;
    }

    /** A tap on the car list at y: the car in that row is chosen to send private messages to (again: back to everyone). */
    private boolean tapCar(float y) {
        int row = (int) ((y - MEMBERS_TOP) / MEMBER_H);
        if (row < 0 || row >= MEMBER_ROWS || rowCars[row] == null) return false;
        Convoy.Member m = rowCars[row];
        if (m.id.equals(convoy.me())) return true;                 // not oneself
        if (m.id.equals(recipient())) everyone();
        else choose(m.id, m.name.isEmpty() ? "Car" : m.name);
        return true;
    }

    private final Convoy.Done result = new Convoy.Done() {
        public void done(String error) {
            busy = false;
            noteOk = false;
            note = error == null ? "" : error;
        }
    };

    /** Convoys on the networks this phone is on (LocalNet's sweep), listed to tap (foundList). */
    private void findNearby() {
        finding = true;
        noteOk = true;
        note = "";
        new Thread(new Runnable() {
            public void run() {
                final List<Nearby.Host> hosts = Nearby.scan(act);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        finding = false;
                        foundHosts = new ArrayList<Nearby.Host>();
                        for (Nearby.Host h : hosts) if (!h.id.equals(Host.get(act).id())) foundHosts.add(h);  // not itself
                        found = Nearby.convoys(foundHosts);
                        act.ui().postInvalidate();
                    }
                });
            }
        }).start();
    }

    /** A convoy in the list: join it; a host with none: use it, to start one there. */
    private void tapFound(float x, float y) {
        int row = 0;
        for (Nearby.Found f : found) {
            if (row >= FOUND_ROWS) return;
            if (foundRow(row++).contains(x, y)) {
                found = null;
                noteOk = true;
                note = "Joining " + f.name + " on " + f.host.name + "…";
                convoy.joinNearby(f);
                return;
            }
        }
        for (Nearby.Host h : foundHosts) {
            if (!h.convoys.isEmpty()) continue;
            if (row >= FOUND_ROWS) return;
            if (foundRow(row++).contains(x, y)) {
                found = null;
                foundName = h.name;
                useHost(h.url);
                return;
            }
        }
    }

    private void askHost() {
        List<String> offer = convoy.recentHosts();
        Keypad.address(act, "Convoy host: an address like 192.168.0.10:8080 or convoy.example.com", convoy.server()
            .replaceFirst("^http://", ""), offer, new Keypad.Done() {
                public void done(String v) { if (v != null && !v.isEmpty()) useHost(v); }
            });
    }

    /** Takes the host and asks it whether it is a convoy server. */
    private void useHost(String typed) {
        if (!convoy.setServer(typed)) {
            noteOk = false;
            note = "That is not an address: try 192.168.0.10:8080 or convoy.example.com.";
            return;
        }
        noteOk = true;
        note = "Asking " + convoy.server() + "…";
        convoy.check(new Convoy.Done() {
            public void done(String error) {
                noteOk = error == null;
                note = error == null ? (foundName.isEmpty() ? "The host answers" : "Found " + foundName + " on this Wi-Fi")
                    + ": ready to start or join a convoy." : error;
                foundName = "";
            }
        });
    }

    private void askName() {
        Keypad.text(act, "Name the convoy", "", Arrays.asList("Sunday run", "Track day", "Road trip", "Breakfast run"),
            new Keypad.Done() {
                public void done(final String n) {
                    if (n == null) return;
                    Keypad.address(act, "A password for the convoy (recommended; OK with none for no password)", "",
                        new ArrayList<String>(), new Keypad.Done() {
                            public void done(String pw) {
                                if (pw == null) return;
                                busy = true;
                                note = "";
                                start(n.isEmpty() ? "Convoy" : n, pw);
                            }
                        });
                }
            });
    }

    /**
     * Starts the convoy; a host that wants its start key (none kept for it, or a wrong one) gets it asked for,
     * kept for that host, and the start tried again. Cancel leaves the host's refusal as the note.
     */
    private void start(final String n, final String pw) {
        final String host = convoy.server();
        final boolean had = !convoy.startKey(host).isEmpty();
        convoy.start(n, pw, new Convoy.Done() {
            public void done(String error) {
                if (error == null || !convoy.wantsStartKey()) {
                    result.done(error);
                    return;
                }
                Keypad.address(act, had ? "Wrong start key for " + host.replaceFirst("^https?://", "") + ": try again"
                    : "This host needs its start key to start a convoy (joining one needs none)", "",
                    new ArrayList<String>(), new Keypad.Done() {
                        public void done(String k) {
                            if (k == null || k.trim().isEmpty()) {
                                result.done(error);
                                return;
                            }
                            convoy.setStartKey(host, k.trim());
                            start(n, pw);
                        }
                    });
            }
        });
    }

    /** Scan a convoy's QR code with the back camera and join; a password is asked for if the convoy has one. */
    private void scan() {
        ScanView.open(act, new ScanView.Done() {
            public void scanned(String text) {
                note = "";
                if (!convoy.joinLink(text, "")) {
                    noteOk = false;
                    note = "That QR code is not a convoy's.";
                }
            }
        });
    }

    /**
     * When the server wants the password (none given, or a wrong one): the keyboard opens by itself, once
     * for each refusal; Cancel leaves the Type the password button.
     */
    private void askPassword() {
        if (asking || !convoy.refused().equals("password") || convoy.refusals() == askedFor) return;
        askedFor = convoy.refusals();
        asking = true;
        String title = convoy.error().startsWith("wrong") ? "Wrong password: try again" : "This convoy needs its password";
        Keypad.address(act, title, "", new ArrayList<String>(), new Keypad.Done() {
            public void done(String v) {
                asking = false;
                if (v != null) convoy.retry(v);
            }
        });
    }
}

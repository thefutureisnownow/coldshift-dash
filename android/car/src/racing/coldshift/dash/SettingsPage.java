package racing.coldshift.dash;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

/**
 * Settings, a tab to a topic. Add a tab by adding its name to TABS and a case in draw() and touch(); the row
 * narrows its tabs to fit. The tab last open comes back while the app runs (not across launches).
 *
 * The Devices tab is the face of Devices: one row for each job a Bluetooth device does in the car (Engine,
 * GPS, Talk button, Headset, Tyres) with the device chosen for it and whether it is in range, a list of
 * the devices around to choose from (scanning while it is open), and a test of the OBD adapter chosen for
 * Engine (DeviceTest) that logs what the adapter answers. The Sound tab (SoundSettings) does the same for sound:
 * which input or output each sound job uses.
 */
class SettingsPage extends Page {
    private static final int[] IDLE = {0, 1, 5, 10};
    private static final String[] IDLE_LABEL = {"Off", "1 min", "5 min", "10 min"};
    private static final float[] IDLE_W = {70, 90, 91, 101};
    private static final float SX0 = 74, SX1 = 1205;

    private static final String[] TABS = {"Display", "Convoy", "Navigation", "Devices", "Sound", "System"};
    private static final int DISPLAY = 0, CONVOY = 1, NAVIGATION = 2, DEVICES = 3, SOUND = 4, SYSTEM = 5;
    private static int lastTab;

    // Where a tab's sections start (the baseline of the title; the controls sit under it)
    private static final float Y1 = 240, Y2 = 358, Y3 = 476, LEFT = 63;

    private final UI ui;
    private boolean diag, about;
    private Bitmap dashLogo, tfinnLogo;
    private boolean dragging;
    private final long started = System.currentTimeMillis();

    private final SoundSettings sound;

    SettingsPage(Main a, UI ui) { super(a); this.ui = ui; sound = new SoundSettings(a); }

    String label() { return "Settings"; }

    @Override boolean wide() { return true; }

    void draw(Canvas c) {
        Theme.text(c, about ? "ABOUT" : diag ? "DIAGNOSTICS" : "SETTINGS", 24, 90, 30, 0xffffffff, Theme.bold, Theme.LEFT);
        if (diag || about) Theme.chip(c, Wide.rx(1119), 62, Wide.rx(1255), 102, "Back", false, 0);
        if (diag) { diagnostics(c); return; }
        if (about) { about(c); return; }

        Theme.panel(c, 46, 125, Wide.rx(1233), 597);
        for (int i = 0; i < TABS.length; i++) {
            RectF r = tabRect(i);
            Theme.chip(c, r.left, r.top, r.right, r.bottom, TABS[i], lastTab == i, 0);
        }
        Theme.P.setStyle(Paint.Style.STROKE);
        Theme.P.setStrokeWidth(1.2f);
        Theme.P.setColor(Theme.EDGE);
        c.drawLine(LEFT, 204, Wide.rx(1233) - 17, 204, Theme.P);
        Theme.P.setStyle(Paint.Style.FILL);
        if (lastTab == DISPLAY) display(c);
        else if (lastTab == CONVOY) convoy(c);
        else if (lastTab == NAVIGATION) navigation(c);
        else if (lastTab == DEVICES) devices(c);
        else if (lastTab == SOUND) sound.draw(c);
        else system(c);
    }

    // ---- the tabs ----

    /** The tab row: tabs 150 wide, narrower once there are too many for the panel. */
    private RectF tabRect(int i) {
        float room = Wide.rx(1233) - 17 - LEFT;
        float w = Math.min(150, (room - 10 * (TABS.length - 1)) / TABS.length);
        return new RectF(LEFT + i * (w + 10), 141, LEFT + i * (w + 10) + w, 185);
    }

    /** A section's title, with its one line of explanation below the controls. */
    private void section(Canvas c, float y, String title, float hintY, String hint) {
        Theme.text(c, title, LEFT, y, 17, Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.text(c, hint, LEFT, hintY, 14, Theme.DIM, Theme.sans, Theme.LEFT);
    }

    /** A control under the section that starts at y (a baseline). */
    private static RectF at(float x, float y, float w) { return new RectF(x, y + 16, x + w, y + 56); }

    private void chip(Canvas c, RectF r, String label, boolean on) {
        Theme.chip(c, r.left, r.top, r.right, r.bottom, label, on, 0);
    }

    private void display(Canvas c) {
        int b = Prefs.brightness(act);
        float frac = (b - 10) / 245f;
        section(c, Y1, "Screen brightness", Y1 + 112, "Dims the display. After the screen blanks, the first tap only wakes it.");
        Theme.slider(c, SX0, Wide.rx(SX1), Y1 + 66, frac);
        Theme.text(c, String.valueOf(b), SX0 + (Wide.rx(SX1) - SX0) * frac, Y1 + 34, 19, 0xffffffff, Theme.bold, Theme.CENTER);

        section(c, IDLE_Y, "Turn the screen off when idle", IDLE_Y + 82,
            "Idle means no touch. A tap wakes the screen without pressing anything underneath.");
        int idle = Prefs.idleMinutes(act);
        for (int i = 0; i < IDLE.length; i++) chip(c, idleChip(i), IDLE_LABEL[i], idle == IDLE[i]);
    }

    private static final float IDLE_Y = 408;

    private static RectF idleChip(int i) {
        float x = LEFT;
        for (int k = 0; k < i; k++) x += IDLE_W[k] + 10;
        return at(x, IDLE_Y, IDLE_W[i]);
    }

    private static final RectF WAKE = at(LEFT, Y1, 170), CHIME = at(LEFT + 180, Y1, 130);
    private static final RectF PLAY = at(LEFT, Y2, 190), WAIT = at(LEFT + 200, Y2, 160);
    private static final RectF[] TEXT = {at(LEFT, Y3, 100), at(LEFT + 110, Y3, 100), at(LEFT + 220, Y3, 140)};

    private void convoy(Canvas c) {
        boolean wake = Prefs.convoyWake(act), play = Prefs.convoyAutoplay(act);
        section(c, Y1, "Convoy messages", Y1 + 82, "With the screen off: wake it for a message, or only chime and leave it dark.");
        chip(c, WAKE, "Wake the screen", wake);
        chip(c, CHIME, "Chime only", !wake);
        section(c, Y2, "Voice notes", Y2 + 82, play ? "Voice notes play as they come." : "Voice notes wait for a tap.");
        chip(c, PLAY, "Play as they come", play);
        chip(c, WAIT, "Wait for a tap", !play);
        section(c, Y3, "Message size", Y3 + 82, "How big messages are drawn in the Convoy list and the banner.");
        for (int i = 0; i < TEXT.length; i++) chip(c, TEXT[i], Prefs.CONVOY_TEXT[i], Prefs.convoyText(act) == i);
    }

    private void navigation(Canvas c) {
        String chosen = Voice.get(act).chosen();
        java.util.List<RectF> r = voiceChips();
        java.util.List<String> ids = voiceIds();
        for (int i = 0; i < ids.size(); i++) chip(c, r.get(i), voiceLabel(ids.get(i)), ids.get(i).equals(chosen));
        float hint = r.get(r.size() - 1).bottom + 26;
        section(c, Y1, "Voice prompts while navigating", hint, "More voices come from the desktop tool. Test plays a sample.");
    }

    private static final RectF KIOSK_ON = at(LEFT, Y1, 90), KIOSK_OFF = at(LEFT + 100, Y1, 90);
    private static final RectF ANDROID = at(LEFT, Y2, 300);
    private static final RectF DIAG = at(LEFT, Y3, 150), ABOUT = at(LEFT + 160, Y3, 110);

    private void system(Canvas c) {
        boolean kiosk = Prefs.kiosk(act);
        section(c, Y1, "Kiosk", Y1 + 82, kiosk
            ? "Starts with the phone and stays in front. Android settings pauses it for 10 min."
            : "Not a kiosk: an ordinary app (Back leaves it running). For a phone that is also your own.");
        chip(c, KIOSK_ON, "On", kiosk);
        chip(c, KIOSK_OFF, "Off", !kiosk);
        section(c, Y2, "Android settings", Y2 + 82, kiosk
            ? "Opens the phone's own settings, and holds the kiosk off for 10 min while you are in them."
            : "Opens the phone's own settings (Wi-Fi, Bluetooth, storage).");
        chip(c, ANDROID, kiosk ? "Android settings (10 min)" : "Android settings", false);
        section(c, Y3, "About this app", Y3 + 82, "Diagnostics: battery, temperatures, storage and screen. About: version and credits.");
        chip(c, DIAG, "Diagnostics", false);
        chip(c, ABOUT, "About", false);
    }

    // ---- voice ----

    /** The packs, then Off, then Test (as "test"). */
    private java.util.List<String> voiceIds() {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (Voice.Pack p : Voice.get(act).packs()) ids.add(p.id);
        ids.add(Voice.OFF);
        ids.add("test");
        return ids;
    }

    private String voiceLabel(String id) {
        if (id.equals(Voice.OFF)) return "Off";
        if (id.equals("test")) return "▶ Test";
        for (Voice.Pack p : Voice.get(act).packs()) if (p.id.equals(id)) return p.name;
        return id;
    }

    /** One chip per voice, in as many rows as the panel's width asks for. */
    private java.util.List<RectF> voiceChips() {
        java.util.List<RectF> out = new java.util.ArrayList<RectF>();
        float x = LEFT, y = Y1;
        for (String id : voiceIds()) {
            float w = Math.max(80, Theme.width(voiceLabel(id), 17, Theme.sans) + 36);
            if (x > LEFT && x + w > Wide.rx(1215)) { x = LEFT; y += 50; }
            out.add(at(x, y, w));
            x += w + 10;
        }
        return out;
    }

    // ---- devices ----

    // The Devices tab has three views: the roles (D_MAIN), the list of devices to choose one from for a
    // role (D_CHOOSE) and the OBD adapter test (D_TEST). Taps act when the finger lifts, so the list can be dragged.
    private static final int D_MAIN = 0, D_CHOOSE = 1, D_TEST = 2;
    private static final float DY0 = 256, DSTEP = 64, DH = 56;          // the role rows
    private static final float LIST_T = 292, LIST_B = 588, LIST_ROW = 64;
    private static final int SCAN_MS = 12000;
    private static final float TAP_SLOP = 14;
    private static final android.graphics.Typeface MONO = android.graphics.Typeface.MONOSPACE;
    private int dMode, dRole;
    private boolean scanned, armed, dragged, showUnnamed;
    private int unnamed;                                    // devices left out of the list for having no name and no kind
    private float listScroll, listMax, downX, downY, dragFrom;
    private String note = "";
    private long noteUntil;
    private DeviceTest test;

    private void enterDevices() {
        dMode = D_MAIN;
        scanned = false;
        listScroll = 0;
    }

    private void leaveDevices() {
        if (test != null) test.stop();
        test = null;
        Devices.get(act).stopScan();
    }

    /** Settings opens on the Devices tab the next time it shows (the Engine view's "Choose an adapter"). */
    void showDevices() {
        diag = false;
        about = false;
        lastTab = DEVICES;
    }

    private void selectTab(int i) {
        if (i == lastTab) return;
        if (lastTab == DEVICES) leaveDevices();
        if (lastTab == SOUND) sound.leave();
        lastTab = i;
        if (i == DEVICES) enterDevices();
        if (i == SOUND) sound.enter();
    }

    @Override void onShow() {
        if (lastTab == DEVICES) enterDevices();
        if (lastTab == SOUND) sound.enter();
    }

    @Override void onHide() {
        if (lastTab == DEVICES) leaveDevices();
        if (lastTab == SOUND) sound.leave();
    }

    @Override void tick() {
        if (lastTab != DEVICES || diag || about || dMode == D_TEST || scanned) return;
        Devices d = Devices.get(act);
        if (d.problem().isEmpty()) {                                    // looks around once for each visit
            scanned = true;
            d.scan(SCAN_MS);
        }
    }

    @Override int intervalMs() { return lastTab == DEVICES ? 400 : lastTab == SOUND ? sound.intervalMs() : 1000; }

    private void say(String s) {
        note = s;
        noteUntil = System.currentTimeMillis() + 15000;
    }

    private RectF roleRow(int i) { return new RectF(LEFT, DY0 + i * DSTEP, Wide.rx(1216), DY0 + i * DSTEP + DH); }

    private static RectF button(RectF row, int fromRight, float w) {
        float r = row.right - 8 - fromRight;
        return new RectF(r - w, row.top + 9, r, row.bottom - 9);
    }

    private static RectF clearButton(RectF row) { return button(row, 0, 84); }
    private static RectF chooseButton(RectF row) { return button(row, 92, 104); }
    private static RectF testButton(RectF row) { return button(row, 204, 80); }

    /** The strip above the roles: what Bluetooth needs, or how it is. Its button's rectangle. */
    private static RectF stripButton() { return new RectF(Wide.rx(1216) - 190, 211, Wide.rx(1216), 247); }

    private void devices(Canvas c) {
        Devices d = Devices.get(act);
        if (dMode == D_CHOOSE) { chooseView(c, d); return; }
        if (dMode == D_TEST) { testView(c, d); return; }
        String problem = d.problem();
        RectF sb = stripButton();
        if (!problem.isEmpty()) {
            Theme.text(c, Theme.fit(problem, sb.left - LEFT - 16, 17, Theme.sans), LEFT, 238, 17, Theme.GOLD, Theme.sans, Theme.LEFT);
            if (d.present()) chip(c, sb, problemButton(d), true);
        } else {
            int paired = 0;
            for (Devices.Found f : d.found()) if (f.bonded) paired++;
            Theme.text(c, "Bluetooth is on  ·  " + paired + " paired" + (d.scanning() ? "  ·  looking around…"
                : d.scanError().isEmpty() ? "" : "  ·  " + d.scanError()), LEFT, 238, 17, Theme.SUB, Theme.sans, Theme.LEFT);
            chip(c, sb, d.scanning() ? "Looking… " + (d.scanLeft() + 999) / 1000 + " s" : "Look again", false);
        }
        for (int i = 0; i <= Devices.TYRES; i++) roleRow(c, d, i);
        String hint = System.currentTimeMillis() < noteUntil ? note
            : "Each job takes the device you choose. A dot shows whether it is in range.";
        Theme.text(c, Theme.fit(hint, Wide.rx(1216) - LEFT, 14, Theme.sans), LEFT, 590, 14,
            System.currentTimeMillis() < noteUntil ? Theme.GOLD : Theme.DIM, Theme.sans, Theme.LEFT);
    }

    private String problemButton(Devices d) {
        if (d.missing().length > 0) return "Allow";
        if (!d.on()) return "Turn on";
        return "Location settings";
    }

    private void roleRow(Canvas c, Devices d, int role) {
        RectF r = roleRow(role);
        Theme.button(c, r, false, 10);
        Theme.text(c, Devices.ROLE_NAME[role], r.left + 16, r.top + 25, 19, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, Devices.ROLE_ABOUT[role], r.left + 16, r.top + 45, 13, Theme.DIM, Theme.sans, Theme.LEFT);
        Devices.Chosen ch = role < Devices.TYRES ? d.chosen(role) : null;
        java.util.List<Devices.Chosen> tyres = role == Devices.TYRES ? d.tyres() : null;
        boolean has = ch != null || tyres != null && !tyres.isEmpty();
        float left = r.left + 214, right = (has && role == Devices.ENGINE ? testButton(r) : chooseButton(r)).left - 12;
        if (!has) {
            Theme.text(c, "None", left, r.top + 34, 18, Theme.DIM, Theme.sans, Theme.LEFT);
        } else {
            int state = Devices.AWAY, near = 0;
            String title, info;
            if (ch != null) {
                state = d.state(ch.address);
                title = ch.label();
                info = ch.address + "  ·  " + ch.transportName() + "  ·  " + ch.kindName();
                if (ch.link != BtLink.AUTO) info += "  ·  " + BtLink.NAME[ch.link] + " forced";
            } else {
                StringBuilder names = new StringBuilder();
                for (Devices.Chosen t : tyres) {
                    int s = d.state(t.address);
                    if (s == Devices.NEAR) near++;
                    state = Math.max(state, s);
                    if (names.length() > 0) names.append(", ");
                    names.append(t.label());
                }
                title = tyres.size() + (tyres.size() == 1 ? " sensor" : " sensors") + " of " + Devices.MAX_TYRES;
                info = names.toString();
            }
            String where = state == Devices.NEAR ? "in range" : state == Devices.LOOKING ? "looking…" : "not seen";
            if (tyres != null && near > 0 && near < tyres.size()) where = near + " in range";
            int col = state == Devices.NEAR ? Theme.GREEN : state == Devices.LOOKING ? Theme.AMBER : Theme.DIMMER;
            Theme.P.setShader(null);
            Theme.P.setStyle(Paint.Style.FILL);
            Theme.P.setColor(col);
            c.drawCircle(r.left + 196, r.centerY(), 7, Theme.P);
            Theme.text(c, Theme.fit(title, right - left, 19, Theme.bold), left, r.top + 25, 19, 0xffffffff, Theme.bold, Theme.LEFT);
            Theme.text(c, Theme.fit(info + (ch != null ? "  ·  " : "   ·   ") + where, right - left, 13, Theme.sans),
                left, r.top + 45, 13, Theme.SUB, Theme.sans, Theme.LEFT);
        }
        if (has && role == Devices.ENGINE) chip(c, testButton(r), "Test", false);
        chip(c, chooseButton(r), has ? "Change" : "Choose", !has);
        if (has) chip(c, clearButton(r), "Clear", false);
    }

    /** The devices to choose from for the role, the likely ones first. */
    private java.util.List<Devices.Found> choices(Devices d, final int role) {
        java.util.List<Devices.Found> out = new java.util.ArrayList<Devices.Found>();
        long now = android.os.SystemClock.elapsedRealtime();
        unnamed = 0;
        for (Devices.Found f : d.found()) {
            if (!(f.bonded || now - f.seen < 180000 && f.seen > 0 || now - f.connected < 180000 && f.connected > 0)) continue;
            // most of what a scan finds is other people's phones and gadgets: no name, nothing to recognise it by
            if (!showUnnamed && f.name.isEmpty() && f.kind == Devices.UNKNOWN && !f.bonded && !d.inUse(f.address)) {
                unnamed++;
                continue;
            }
            out.add(f);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Devices.Found>() {
            public int compare(Devices.Found a, Devices.Found b) {
                int ra = rank(a, role), rb = rank(b, role);
                if (ra != rb) return ra - rb;
                if (a.bonded != b.bonded) return a.bonded ? -1 : 1;
                return a.address.compareTo(b.address);
            }
        });
        return out;
    }

    private static int rank(Devices.Found f, int role) {
        if (f.kind == Devices.ROLE_KIND[role]) return 0;
        if (role == Devices.TALK && f.kind == Devices.AUDIO) return 1;
        return f.kind != Devices.UNKNOWN ? 2 : f.name.isEmpty() ? 4 : 3;
    }

    private static boolean inList(float y) { return y >= LIST_T && y <= LIST_B; }

    private RectF doneButton() { return new RectF(Wide.rx(1216) - 96, 211, Wide.rx(1216), 247); }
    private RectF unnamedButton() { return new RectF(Wide.rx(1216) - 96 - 10 - 150 - 10 - 170, 211, Wide.rx(1216) - 266, 247); }
    private RectF scanButton() { return new RectF(Wide.rx(1216) - 96 - 10 - 150, 211, Wide.rx(1216) - 106, 247); }

    private void chooseView(Canvas c, Devices d) {
        boolean tyres = dRole == Devices.TYRES;
        Theme.text(c, Devices.ROLE_NAME[dRole] + ": choose " + (tyres ? "up to " + Devices.MAX_TYRES + " sensors" : "its device"),
            LEFT, 241, 20, 0xffffffff, Theme.bold, Theme.LEFT);
        chip(c, doneButton(), tyres ? "Done" : "Back", tyres);
        java.util.List<Devices.Found> list = choices(d, dRole);
        chip(c, unnamedButton(), showUnnamed ? "Hide unnamed" : unnamed == 0 ? "Unnamed hidden" : unnamed + " unnamed hidden", false);
        chip(c, scanButton(), d.scanning() ? "Looking… " + (d.scanLeft() + 999) / 1000 + " s" : "Scan again", false);
        String hint = tyres ? "Tick the sensors on this car (" + d.tyres().size() + " of " + Devices.MAX_TYRES
            + "). They broadcast, so there is no pairing. Which wheel is which comes later."
            : dRole == Devices.ENGINE ? "A Classic adapter that is not paired gets paired when chosen: Android asks for a PIN, usually 1234 or 0000. LE ones need none."
            : "One that is not paired and speaks Classic is paired when chosen: Android asks for a PIN.";
        String problem = d.problem();
        Theme.text(c, Theme.fit(problem.isEmpty() ? hint : problem, Wide.rx(1216) - LEFT, 14, Theme.sans), LEFT, 274, 14,
            problem.isEmpty() ? Theme.DIM : Theme.GOLD, Theme.sans, Theme.LEFT);
        listMax = Math.max(0, list.size() * LIST_ROW - (LIST_B - LIST_T));
        listScroll = Math.max(0, Math.min(listScroll, listMax));
        if (list.isEmpty()) {
            Theme.text(c, d.scanning() ? "Looking for devices…" : "No devices found. Switch the device on and tap Scan again.",
                Wide.mid(LEFT, 1216), 420, 18, Theme.SUB, Theme.sans, Theme.CENTER);
            return;
        }
        c.save();
        c.clipRect(LEFT - 4, LIST_T, Wide.rx(1216) + 4, LIST_B);
        for (int i = 0; i < list.size(); i++) {
            float top = LIST_T + i * LIST_ROW - listScroll;
            if (top + LIST_ROW < LIST_T || top > LIST_B) continue;
            choiceRow(c, d, list.get(i), new RectF(LEFT, top, Wide.rx(1216), top + LIST_ROW - 6));
        }
        c.restore();
        if (listMax > 0) {                                          // where in the list
            float h = (LIST_B - LIST_T) * (LIST_B - LIST_T) / (listMax + LIST_B - LIST_T);
            float y = LIST_T + (LIST_B - LIST_T - h) * listScroll / listMax;
            Theme.P.setColor(Theme.accent(0x66));
            c.drawRoundRect(new RectF(Wide.rx(1216) + 6, y, Wide.rx(1216) + 10, y + h), 2, 2, Theme.P);
        }
    }

    private void choiceRow(Canvas c, Devices d, Devices.Found f, RectF r) {
        boolean tyres = dRole == Devices.TYRES;
        Devices.Chosen cur = tyres ? null : d.chosen(dRole);
        boolean on = tyres ? d.isTyre(f.address) : cur != null && cur.address.equals(f.address);
        Theme.button(c, r, on, 10);
        signal(c, r.left + 18, r.centerY() + 9, f);
        float left = r.left + 62, right = r.right - 130;
        Theme.text(c, Theme.fit(f.label(), right - left, 18, Theme.bold), left, r.top + 23, 18, f.name.isEmpty() ? Theme.DIM : 0xffffffff,
            Theme.bold, Theme.LEFT);
        String info = f.kindName() + "  ·  " + f.transportName() + "  ·  "
            + (f.rssi == Integer.MIN_VALUE ? "no signal reading" : f.rssi + " dBm") + "  ·  "
            + (d.pairing(f) ? "pairing…" : f.bonded ? "paired" : "not paired") + "  ·  " + f.address;
        Theme.text(c, Theme.fit(info, right - left, 13, Theme.sans), left, r.top + 41, 13, on ? 0xffffffff : Theme.SUB, Theme.sans, Theme.LEFT);
        String adverts = f.adverts();
        if (!adverts.isEmpty()) Theme.text(c, Theme.fit(adverts, right - left, 11, Theme.sans), left, r.top + 55, 11, Theme.DIM, Theme.sans, Theme.LEFT);
        if (tyres) {
            RectF box = new RectF(r.right - 46, r.centerY() - 14, r.right - 18, r.centerY() + 14);
            Theme.chip(c, box.left, box.top, box.right, box.bottom, on ? "✓" : "", on, 0);
        } else {
            Theme.text(c, on ? "Chosen ✓" : "Choose", r.right - 18, r.centerY() + 7, 18, on ? 0xffffffff : Theme.BLUE_HI,
                Theme.bold, Theme.RIGHT);
        }
    }

    /** Four bars for the signal: full above -60 dBm, none below -90 or without a reading. */
    private void signal(Canvas c, float x, float base, Devices.Found f) {
        int bars = f.rssi == Integer.MIN_VALUE ? 0 : f.rssi > -60 ? 4 : f.rssi > -70 ? 3 : f.rssi > -80 ? 2 : f.rssi > -90 ? 1 : 0;
        Theme.P.setShader(null);
        Theme.P.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            Theme.P.setColor(i < bars ? Theme.GREEN : Theme.accent(0x40));
            c.drawRect(x + i * 8, base - 8 - i * 5, x + i * 8 + 5, base, Theme.P);
        }
    }

    private void testView(Canvas c, Devices d) {
        Devices.Chosen e = d.chosen(Devices.ENGINE);
        if (e == null) { dMode = D_MAIN; return; }
        Theme.text(c, Theme.fit("Test: " + e.label(), Wide.rx(1216) - LEFT - 120, 20, Theme.bold), LEFT, 241, 20, 0xffffffff, Theme.bold, Theme.LEFT);
        chip(c, doneButton(), "Back", false);
        boolean running = test != null && test.running();
        for (int i = 0; i < 3; i++) chip(c, linkChip(i), BtLink.NAME[i], e.link == i);
        chip(c, runButton(), running ? "Stop" : "Run test", !running);
        Devices.Found f = d.find(e.address);
        boolean pairable = f != null && !f.bonded && (f.transports & Devices.CLASSIC) != 0;
        if (pairable) chip(c, pairButton(), d.pairing(f) ? "Pairing…" : "Pair", false);
        boolean noted = System.currentTimeMillis() < noteUntil;
        String status = noted ? note : test == null ? "Opens the adapter and sends ATZ, ATE0, ATI, ATDP, ATRV and 0100. Auto picks Classic for a paired dual-mode adapter, else LE."
            : test.status();
        Theme.text(c, Theme.fit(status, Wide.rx(1216) - LEFT, 14, Theme.sans), LEFT, 330, 14,
            noted ? Theme.GOLD : test != null && test.status().startsWith("Failed") || test != null && test.status().startsWith("No answer") ? Theme.BAD
            : test != null && test.status().startsWith("Done") ? Theme.GREEN : Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.panel(c, LEFT, 344, Wide.rx(1216), 590);
        if (test == null) return;
        java.util.List<String> lines = test.lines();
        int rows = 15, from = Math.max(0, lines.size() - rows);
        c.save();
        c.clipRect(LEFT + 4, 348, Wide.rx(1216) - 4, 588);
        for (int i = from; i < lines.size(); i++) {
            String s = lines.get(i);
            boolean sent = s.length() > 7 && s.charAt(7) == '>';
            int col = sent ? Theme.GOLD : s.contains("Failed") || s.contains("no answer") ? Theme.BAD : s.startsWith(" ") && s.length() > 8
                && s.substring(0, 8).trim().isEmpty() ? Theme.TEXT : Theme.SUB;
            Theme.text(c, Theme.fit(s, Wide.rx(1216) - LEFT - 28, 13, MONO), LEFT + 14, 366 + (i - from) * 15.5f, 13, col, MONO, Theme.LEFT);
        }
        c.restore();
    }

    private RectF linkChip(int i) { return new RectF(LEFT + i * 96, 262, LEFT + i * 96 + 88, 304); }
    private RectF runButton() { return new RectF(LEFT + 3 * 96 + 14, 262, LEFT + 3 * 96 + 14 + 130, 304); }
    private RectF pairButton() { return new RectF(LEFT + 3 * 96 + 14 + 140, 262, LEFT + 3 * 96 + 14 + 140 + 120, 304); }

    private void devicesTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            armed = true;
            dragged = false;
            downX = x;
            downY = y;
            dragFrom = listScroll;
            return;
        }
        if (!armed) return;
        if (a == MotionEvent.ACTION_MOVE) {
            if (dMode == D_CHOOSE && inList(downY)) {
                if (Math.abs(y - downY) > TAP_SLOP) dragged = true;
                if (dragged) listScroll = Math.max(0, Math.min(listMax, dragFrom - (y - downY)));
            }
            return;
        }
        armed = false;
        if (a != MotionEvent.ACTION_UP || dragged) return;
        Devices d = Devices.get(act);
        if (dMode == D_CHOOSE) chooseTap(d, x, y);
        else if (dMode == D_TEST) testTap(d, x, y);
        else mainTap(d, x, y);
    }

    private void mainTap(Devices d, float x, float y) {
        if (stripButton().contains(x, y)) {
            String problem = d.problem();
            if (problem.isEmpty()) d.scan(SCAN_MS);
            else if (d.missing().length > 0) Permissions.request(act, d.missing());
            else if (!d.on()) d.enable(act);
            else if (d.locationOff()) {
                if (Prefs.kiosk(act)) Watchdog.allowUntil = System.currentTimeMillis() + 60000;
                Intent in = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(in);
            }
            return;
        }
        for (int role = 0; role <= Devices.TYRES; role++) {
            RectF r = roleRow(role);
            boolean has = role < Devices.TYRES ? d.chosen(role) != null : !d.tyres().isEmpty();
            if (chooseButton(r).contains(x, y)) {
                dRole = role;
                dMode = D_CHOOSE;
                listScroll = 0;
                note = "";
                if (!d.scanning() && d.problem().isEmpty()) d.scan(SCAN_MS);
            } else if (has && clearButton(r).contains(x, y)) {
                d.clear(role);
            } else if (has && role == Devices.ENGINE && testButton(r).contains(x, y)) {
                test = null;
                dMode = D_TEST;
            }
        }
    }

    private void chooseTap(Devices d, float x, float y) {
        if (doneButton().contains(x, y)) { dMode = D_MAIN; return; }
        if (scanButton().contains(x, y)) { if (!d.scanning()) d.scan(SCAN_MS); return; }
        if (unnamedButton().contains(x, y)) { showUnnamed = !showUnnamed; listScroll = 0; return; }
        if (!inList(y) || x < LEFT || x > Wide.rx(1216)) return;
        java.util.List<Devices.Found> list = choices(d, dRole);
        int i = (int) ((y - LIST_T + listScroll) / LIST_ROW);
        if (i < 0 || i >= list.size()) return;
        Devices.Found f = list.get(i);
        if (dRole == Devices.TYRES) {
            // tyre sensors only broadcast over LE: an OBD adapter or a headset is never one
            if ((f.transports & Devices.LE) == 0) say(f.label() + " is Classic Bluetooth only: tyre sensors use LE.");
            else if (!d.toggleTyre(f)) say("Four sensors are chosen already: untick one first.");
            return;
        }
        d.choose(dRole, f);
        dMode = D_MAIN;
        // a Classic-only device that is not paired: pairing starts, and Android asks for the PIN
        if (!f.bonded && f.transports == Devices.CLASSIC && d.pair(f)) {
            say("Pairing with " + f.label() + ": Android asks for a PIN. ELM327 adapters use 1234 or 0000.");
        }
    }

    private void testTap(Devices d, float x, float y) {
        Devices.Chosen e = d.chosen(Devices.ENGINE);
        if (e == null || doneButton().contains(x, y)) {
            if (test != null) test.stop();
            test = null;
            dMode = D_MAIN;
            return;
        }
        boolean running = test != null && test.running();
        for (int i = 0; i < 3; i++) {
            if (linkChip(i).contains(x, y) && !running) d.setLink(Devices.ENGINE, i);
        }
        if (runButton().contains(x, y)) {
            if (running) {
                test.stop();
            } else {
                test = new DeviceTest(act, e, e.link);
                test.start();
            }
        } else if (pairButton().contains(x, y)) {
            Devices.Found f = d.find(e.address);
            if (f != null && !f.bonded && (f.transports & Devices.CLASSIC) != 0 && d.pair(f)) {
                say("Pairing with " + f.label() + ": Android asks for a PIN. ELM327 adapters use 1234 or 0000.");
            }
        }
    }

    private void about(Canvas c) {
        Theme.panel(c, 46, 125, Wide.rx(1233), 597);
        if (dashLogo == null) dashLogo = act.asset("brand/dash-logo-512.png");
        if (tfinnLogo == null) tfinnLogo = act.asset("brand/tfinn-logo.png");
        if (dashLogo != null) c.drawBitmap(dashLogo, null, new RectF(70, 150, 400, 480), Theme.BMP);
        float x = 450;
        Theme.text(c, "coldshift.dash", x, 210, 40, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, "Dash, media, maps and dashcam by coldshift.racing", x, 244, 17, Theme.SUB, Theme.sans, Theme.LEFT);
        String ver = "";
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            ver = "Version " + pi.versionName + "   \u00b7   updated "
                + new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(new java.util.Date(pi.lastUpdateTime));
        } catch (Throwable t) { /* no package info */ }
        Theme.text(c, ver, x, 274, 15, Theme.DIM, Theme.sans, Theme.LEFT);

        if (tfinnLogo != null) c.drawBitmap(tfinnLogo, null, new RectF(x, 310, x + 130, 440), Theme.BMP);
        Theme.text(c, "A TFINN production", x + 150, 366, 24, 0xffffffff, Theme.bold, Theme.LEFT);
        Theme.text(c, "The Future is Now-Now", x + 150, 394, 16, Theme.SUB, Theme.sans, Theme.LEFT);

        String[] credits = {
            "Map data \u00a9 OpenStreetMap contributors (ODbL). Maps drawn with Mapsforge (LGPL-3.0).",
            "Map themes and symbols from the Mapsforge project. Coldshift Racing | Rally."};
        float y = 500;
        for (String line : credits) { Theme.text(c, line, x, y, 13, Theme.DIM, Theme.sans, Theme.LEFT); y += 22; }
    }

    private String line(String k, String v) { return k + "   " + v; }

    private long freeMb(String path) {
        try {
            StatFs s = new StatFs(path);
            return s.getAvailableBlocks() * (long) s.getBlockSize() >> 20;
        } catch (Throwable t) { return -1; }
    }

    private void diagnostics(Canvas c) {
        Theme.panel(c, 46, 125, Wide.rx(1233), 597);
        int vis = act.systemUiVisibility();
        String[] k = {
            "Battery", "Temperature", "Internal storage", "SD card", "System UI", "Screen", "Idle", "App uptime"};
        float[] th = Thermal.read();
        String temps = "battery " + Thermal.text(ui.batteryTemp()) + "   \u00b7   processor " + Thermal.text(th[0])
            + "   \u00b7   charger " + Thermal.text(th[1]) + "   \u00b7   power chip " + Thermal.text(th[2]);
        String card = Storage.card();
        String sd = card != null && freeMb(card) >= 0 ? freeMb(card) + " MB free" : "not mounted";
        String[] v = {
            ui.battery() + "%" + (ui.charging() ? "  charging" : ""),
            temps,
            freeMb(Environment.getExternalStorageDirectory().getPath()) + " MB free",
            sd,
            ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0 ? "status bar hidden" : "STATUS BAR SHOWING")
                + ((vis & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0 ? ", nav hidden" : ", NAV SHOWING"),
            ui.getWidth() + " x " + ui.getHeight(),
            ui.idleSeconds() + " s",
            (System.currentTimeMillis() - started) / 1000 + " s"};
        float y = 175;
        for (int i = 0; i < k.length; i++) {
            Theme.text(c, k[i], 70, y, 17, Theme.DIM, Theme.sans, Theme.LEFT);
            int col = i == 1 && !Float.isNaN(ui.batteryTemp()) ? Thermal.colour(ui.batteryTemp()) : Theme.TEXT;
            Theme.text(c, v[i], 340, y, 19, col, Theme.sans, Theme.LEFT);
            y += 48;
        }
    }

    @Override boolean touch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            if (diag || about) {
                if (x > Wide.rx(1110) && y > 55 && y < 110) { diag = false; about = false; }
                return true;
            }
            for (int i = 0; i < TABS.length; i++) {
                if (tabRect(i).contains(x, y)) { selectTab(i); return true; }
            }
            if (lastTab == DISPLAY) {
                if (y > 270 && y < 340 && x > 55 && x < Wide.rx(1225)) dragging = true;
                for (int i = 0; i < IDLE.length; i++) if (idleChip(i).contains(x, y)) Prefs.setIdleMinutes(act, IDLE[i]);
            } else if (lastTab == CONVOY) {
                if (WAKE.contains(x, y)) Prefs.setConvoyWake(act, true);
                else if (CHIME.contains(x, y)) Prefs.setConvoyWake(act, false);
                else if (PLAY.contains(x, y)) Prefs.setConvoyAutoplay(act, true);
                else if (WAIT.contains(x, y)) Prefs.setConvoyAutoplay(act, false);
                for (int i = 0; i < TEXT.length; i++) if (TEXT[i].contains(x, y)) Prefs.setConvoyText(act, i);
            } else if (lastTab == NAVIGATION) {
                java.util.List<RectF> chips = voiceChips();
                java.util.List<String> ids = voiceIds();
                for (int i = 0; i < chips.size(); i++) {
                    if (!chips.get(i).contains(x, y)) continue;
                    Voice v = Voice.get(act);
                    if (ids.get(i).equals("test")) v.say("in_200m", "turn_left", "then", "turn_right");
                    else {
                        v.choose(ids.get(i));
                        if (!ids.get(i).equals(Voice.OFF)) v.say("in_200m", "turn_left");
                    }
                }
            } else if (lastTab == SYSTEM) {
                boolean kiosk = Prefs.kiosk(act);
                if ((KIOSK_ON.contains(x, y) && !kiosk) || (KIOSK_OFF.contains(x, y) && kiosk)) {
                    Prefs.setKiosk(act, !kiosk);
                    act.applyKiosk();
                } else if (ANDROID.contains(x, y)) {
                    if (kiosk) Watchdog.allowUntil = System.currentTimeMillis() + 10 * 60 * 1000;
                    Intent si = new Intent(Settings.ACTION_SETTINGS);
                    si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(si);
                } else if (DIAG.contains(x, y)) diag = true;
                else if (ABOUT.contains(x, y)) about = true;
            }
        }
        if (lastTab == DEVICES) devicesTouch(e);
        if (lastTab == SOUND && !diag && !about) sound.touch(e);
        if (dragging && (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE)) {
            int v = 10 + Math.round(245f * (x - SX0) / (Wide.rx(SX1) - SX0));
            v = Math.max(10, Math.min(255, v));
            Prefs.setBrightness(act, v);
            act.applyBrightness(v);
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) dragging = false;
        return true;
    }
}

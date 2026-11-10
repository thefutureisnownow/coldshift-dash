package racing.coldshift.dash;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The OBD engine: talks to the car through an ELM327 (the Engine device of Settings, Devices) and keeps the
 * latest readings, the state of the connection and the fault codes for the Engine view. What the lines mean
 * is ObdCodec's; the link is any Link (Bluetooth, or TCP for a simulator or a Wi-Fi adapter).
 *
 * One thread of its own does everything, one command at a time: it sends a command, waits for the ELM's
 * prompt (">") with a timeout, and only then sends the next. That, and not polling faster than the adapter
 * answers, is what keeps cheap adapters from dropping the link.
 * <ul>
 * <li>Runs while the app is in front (resume(), pause()) and an Engine device is chosen, so the readings
 *     are there when the view is opened. The Devices test holds it off (hold()), since one adapter takes
 *     one connection. It waits while Settings is scanning for devices, which a connection attempt stops.</li>
 * <li>Each session: open the link, set the adapter up (ATZ, ATE0, ATL0, ATS0, ATH0, ATAT1, ATSP0), ask for
 *     the car (0100, which makes the adapter search for the protocol: seconds, on K-line), learn the
 *     protocol (ATDPN) and which PIDs the car supports (0100, 0120, 0140), then poll.</li>
 * <li>Polling: the quick readings (rpm, speed, throttle, boost) all the time; the slow ones (coolant, intake
 *     air, barometer, trims, fuel, volts, and the rest) one at a time in between, every slowGap(), which
 *     follows how long the adapter takes to answer. A K-line car answers a few times a second, and the
 *     slow readings then come as seldom as they must.</li>
 * <li>A command that gets no prompt in its time is waited out and sent once more; the second time the link
 *     is dropped and opened again, after a back-off (2, 4, 8, 16, 30 s, then a minute). Any reply that is not for the
 *     command (a late one) is thrown away and the command sent again. STOPPED, NO DATA, ? and the bus
 *     errors are replies, not failures: they count against a reading, not the link.</li>
 * <li>No reading from the ECU for ECU_LOST_MS: the car is not answering (ignition off?) and it is asked
 *     with 0100 every few seconds. After noEcuMs (3 minutes) of that the engine stops talking (IDLE; the adapter stays
 *     powered but quiet, so the car's battery is spared) until the view is opened, the app comes back to
 *     the front, or retry().</li>
 * </ul>
 * The state, the readings and the fault codes are read from any thread (the UI reads them as it draws).
 * No Android in here but the singleton's Env, so a computer can run it against a simulated adapter.
 */
final class Obd {
    /** How the engine reaches the world: the chosen device and a link to it. */
    interface Env {
        /** The Engine device's name, null when none is chosen. */
        String device();
        /** A link to it, null when none is chosen. */
        Link open(Link.Listener l);
        /** Something else is using Bluetooth (a scan for devices) and a connect would spoil it. */
        boolean busy();
        void log(String s);
    }

    // the state
    static final int NONE = 0, CONNECTING = 1, ADAPTER = 2, SEARCHING = 3, CONNECTED = 4, NO_CAR = 5, LOST = 6, IDLE = 7;
    static final String[] STATE_NAME = {"No adapter", "Connecting", "Talking to the adapter", "Searching for the car",
        "Connected", "Car not answering", "Connection lost", "Stopped"};

    // the readings (value(), supports())
    static final int RPM = 0, SPEED = 1, COOLANT = 2, THROTTLE = 3, LOAD = 4, INTAKE = 5, TIMING = 6, BARO = 7, MAP = 8,
        BOOST = 9, MAF = 10, STFT = 11, LTFT = 12, FUEL = 13, VOLT = 14, LAMBDA = 15, LAMBDA_TARGET = 16, PEDAL = 17,
        OUTSIDE = 18, CAT = 19, FUEL_SYS = 20, COUNT = 21;
    static final String[] NAME = {"RPM", "SPEED", "COOLANT", "THROTTLE", "ENGINE LOAD", "INTAKE AIR", "TIMING", "BAROMETER",
        "MANIFOLD", "BOOST", "AIR FLOW", "SHORT TRIM", "LONG TRIM", "FUEL LEVEL", "VOLTAGE", "LAMBDA", "TARGET LAMBDA", "PEDAL",
        "OUTSIDE AIR", "CATALYST", "FUEL SYSTEM"};
    static final String[] UNIT = {"rpm", "km/h", "\u00b0C", "%", "%", "\u00b0C", "\u00b0", "kPa", "kPa", "bar", "g/s", "%", "%", "%", "V",
        "\u03bb", "\u03bb", "%", "\u00b0C", "\u00b0C", ""};
    /** The mode 1 PID of each reading; -1 for boost (made from two) and the voltage (ATRV, not a PID). */
    private static final int[] PID = {0x0C, 0x0D, 0x05, 0x11, 0x04, 0x0F, 0x0E, 0x33, 0x0B, -1, 0x10, 0x06, 0x07, 0x2F, -1,
        0x24, 0x44, 0x5A, 0x46, 0x3C, 0x03};
    /** Another PID for the same reading when the car lacks the first: the narrow-current sensor's lambda, the pedal's absolute position. */
    private static final int[] ALT = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x34, -1, 0x49, -1, -1, -1};
    private static final int STATUS_PID = 0x01;

    // times, in ms
    static final long SILENT_MS = 6000;                       // readings older than this are not shown (quick ones)
    static final long STALE_SLOW_MS = 90000;                  // ... and the slow ones
    private static final long ATZ_MS = 6000, AT_MS = 3000, PID_MS = 6000, SEARCH_MS = 40000, CODES_MS = 10000, GRACE_MS = 1500;
    private static final long ECU_LOST_MS = 8000, PROBE_MS = 6000, OPEN_MS = 45000;
    /** How long the car may stay silent before the engine stops asking (a field so that a test can shorten it). */
    static long noEcuMs = 3 * 60000;
    private static final int TRACE_KEEP = 400;

    // codes
    static final int CODES_NONE = 0, CODES_READING = 1, CODES_DONE = 2, CODES_FAILED = 3;
    static final int CLEAR_NONE = 0, CLEAR_CLEARING = 1, CLEAR_DONE = 2, CLEAR_FAILED = 3;
    private static final int TASK_READ = 1, TASK_CLEAR = 2, TASK_DIAG = 3;

    /**
     * What the car says about itself and its emissions tests (the Diagnostics view), read on request. Numbers
     * are -1 and texts null where the car does not say; a text is "" where it says nothing (a blank VIN).
     */
    static final class Diag {
        int[] since, cycle;                 // readiness: since the codes were cleared (0101), this drive cycle (0141)
        int distMil = -1, minMil = -1;      // driven with the check-engine light on: km, minutes
        int warmups = -1, distClear = -1, minClear = -1;     // since the codes were cleared
        int runSec = -1, standard = -1, fuelType = -1, o2 = -1, readings;
        float ecuVolt = Float.NaN;
        String[] ident = new String[4];     // VIN, calibration IDs, CVNs, ECU name
        long at;
    }

    static final int VIN = 0, CAL_ID = 1, CVN = 2, ECU_NAME = 3;

    /** A fault code the car reports; pending: seen but not confirmed yet (mode 07), else stored (mode 03). */
    static final class Code {
        final String code;
        final boolean pending;
        Code(String code, boolean pending) {
            this.code = code;
            this.pending = pending;
        }
        String describe() { return Dtcs.describe(code); }
    }

    private static final class Quit extends Exception {}                        // the session should end (paused, device changed)
    private static final class Timeout extends Exception {}                     // no prompt in time
    private static final class Idle extends Exception {}                        // the car has not answered for long
    private static final class LinkGone extends Exception {
        LinkGone(String m) { super(m); }
    }

    private static final int EV_OPEN = 0, EV_LINE = 1, EV_ERROR = 2, EV_CLOSED = 3;

    private static final class Ev {
        final int type;
        final String text;
        Ev(int type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    private static final class Item {
        final int key, pid;
        final boolean fast;
        long last, skipUntil, lastOk;
        int misses;
        Item(int key, int pid, boolean fast) {
            this.key = key;
            this.pid = pid;
            this.fast = fast;
        }
    }

    private final class Session implements Link.Listener {
        final LinkedBlockingQueue<Ev> q = new LinkedBlockingQueue<Ev>();
        Link link;
        public void onOpen(Link l) { q.add(new Ev(EV_OPEN, "")); }
        public void onLine(Link l, String line) { q.add(new Ev(EV_LINE, line)); }
        public void onError(Link l, String message) { q.add(new Ev(EV_ERROR, message)); }
        public void onClosed(Link l) { q.add(new Ev(EV_CLOSED, "")); }
    }

    private static Obd instance;
    private final Env env;
    private final Object lock = new Object();
    private boolean resumed, idle, started;
    private int holds, gen;
    private boolean viewing;
    private Thread thread;

    private volatile int state = NONE;
    private volatile String lastError = "", adapter = "", how = "", protocolName = "", device = "";
    private volatile long retryAt;
    private volatile int protocol;
    private volatile boolean connectedBefore;
    private int hintProto, fails;

    private final float[] vals = new float[COUNT];
    private final long[] at = new long[COUNT];
    private final boolean[] supported = new boolean[256];
    private volatile boolean known, voltOk;
    private volatile boolean milOn;
    private volatile int milCount = -1;
    private final long[] stamps = new long[48];
    private int stampAt;
    private volatile long connectedAt;
    private float avgMs = 60;

    private volatile int codesState = CODES_NONE, clearState = CLEAR_NONE;
    private volatile String codesMessage = "", clearMessage = "";
    private volatile List<Code> codes = new ArrayList<Code>();
    private volatile long codesAt;
    private final ConcurrentLinkedQueue<Integer> tasks = new ConcurrentLinkedQueue<Integer>();
    private volatile Diag diag;
    private volatile boolean diagReading;
    private String[] ident;                 // mode 09, read once a connection

    private final List<String> trace = new ArrayList<String>();
    private long traceZero = System.currentTimeMillis();

    private Session cur;
    private boolean useCount = true;

    Obd(Env env) { this.env = env; }

    static synchronized Obd get(final Context c) {
        if (instance == null) {
            final Context app = c.getApplicationContext();
            instance = new Obd(new Env() {
                public String device() {
                    Devices.Chosen d = Devices.get(app).chosen(Devices.ENGINE);
                    return d == null ? null : d.label();
                }
                public Link open(Link.Listener l) {
                    Devices.Chosen d = Devices.get(app).chosen(Devices.ENGINE);
                    return d == null ? null : Devices.get(app).link(d, l);
                }
                public boolean busy() {
                    Devices.Chosen d = Devices.get(app).chosen(Devices.ENGINE);
                    return d != null && !d.isTcp() && Devices.get(app).scanning();
                }
                public void log(String s) { android.util.Log.i("Obd", s); }
            });
            Devices.get(app).addListener(new Devices.Listener() {
                public void devicesChanged() {}
                public void roleChanged(int role) { if (role == Devices.ENGINE) instance.deviceChanged(); }
            });
        }
        return instance;
    }

    // ---- what the app tells it ----

    /** The app is in front: run (when an Engine device is chosen). */
    void resume() {
        synchronized (lock) {
            resumed = true;
            idle = false;
            if (!started) {
                started = true;
                thread = new Thread(new Runnable() {
                    public void run() { loop(); }
                }, "obd");
                thread.setDaemon(true);
                thread.start();
            }
            lock.notifyAll();
        }
    }

    /** The app is not in front: close the link and stop talking. */
    void pause() {
        synchronized (lock) {
            resumed = false;
            lock.notifyAll();
        }
    }

    /** The Devices test needs the adapter: let go of it until release(). Calls pair up. */
    void hold() {
        synchronized (lock) {
            holds++;
            lock.notifyAll();
        }
    }

    void release() {
        synchronized (lock) {
            if (holds > 0) holds--;
            lock.notifyAll();
        }
    }

    /** The Engine view shows (or not): showing it wakes an engine that gave up on a silent car. */
    void viewing(boolean on) {
        synchronized (lock) {
            viewing = on;
            if (on && idle) {
                idle = false;
                lock.notifyAll();
            }
        }
    }

    /** Try again now: a car that did not answer, a link that is waiting to be retried. */
    void retry() {
        synchronized (lock) {
            idle = false;
            fails = 0;
            gen++;
            lock.notifyAll();
        }
    }

    /** The Engine device was chosen, changed or cleared. */
    void deviceChanged() {
        synchronized (lock) {
            idle = false;
            fails = 0;
            hintProto = 0;
            connectedBefore = false;
            known = false;                                   // another adapter, another car: its PIDs are not known
            java.util.Arrays.fill(supported, false);
            voltOk = false;
            gen++;
            lock.notifyAll();
        }
    }

    // ---- what the UI reads ----

    int state() { return state; }
    String device() { return device; }
    String adapter() { return adapter; }
    String how() { return how; }
    String lastError() { return lastError; }
    String protocolName() { return protocolName; }
    int protocolNumber() { return protocol; }
    boolean connected() { return state == CONNECTED; }
    boolean known() { return known; }
    boolean milOn() { return milOn; }
    /** The number of stored codes the car says it has (0101), -1 until known. */
    int milCount() { return milCount; }
    /** Seconds until the next try, while waiting to retry (0 otherwise). */
    int retryIn() { return state == LOST || state == CONNECTING ? (int) Math.max(0, (retryAt - System.currentTimeMillis() + 999) / 1000) : 0; }

    /** Readings a second, over the last few seconds. */
    float rate() {
        long now = System.currentTimeMillis();
        int n = 0;
        long first = now;
        synchronized (stamps) {
            for (long t : stamps) {
                if (t != 0 && now - t < 5000) {
                    n++;
                    if (t < first) first = t;
                }
            }
        }
        long span = Math.min(5000, Math.max(1000, now - Math.max(first, connectedAt)));
        return n * 1000f / span;
    }

    /** Whether the car offers this reading (once it is known; the voltage is the adapter's). */
    boolean supports(int key) {
        if (key == VOLT) return voltOk;
        if (key == BOOST) return supported[0x0B];
        return pidOf(key) >= 0;
    }

    /** The PID a reading comes from on this car: its own, else the other one, else -1. */
    private int pidOf(int key) {
        if (PID[key] >= 0 && supported[PID[key]]) return PID[key];
        return ALT[key] >= 0 && supported[ALT[key]] ? ALT[key] : -1;
    }

    /** A reading now, NaN when it is not known or has gone stale. Boost is manifold minus barometric pressure, in bar. */
    float value(int key) {
        if (state != CONNECTED) return Float.NaN;
        if (key == BOOST) {
            float m = value(MAP), b = value(BARO);
            if (Float.isNaN(m)) return Float.NaN;
            return (m - (Float.isNaN(b) ? 101f : b)) / 100f;
        }
        synchronized (vals) {
            long a = at[key];
            if (a == 0) return Float.NaN;
            boolean quick = key == RPM || key == SPEED || key == THROTTLE || key == MAP;
            if (System.currentTimeMillis() - a > (quick ? SILENT_MS : STALE_SLOW_MS)) return Float.NaN;
            return vals[key];
        }
    }

    /** Readings for the trip log: ",rpm,kmh,throttle,load,coolant,boost_kpa,volts", blank for any not fresh. */
    String csvTail() {
        int[] keys = {RPM, SPEED, THROTTLE, LOAD, COOLANT, BOOST, VOLT};
        StringBuilder b = new StringBuilder();
        for (int k : keys) {
            float v = value(k);
            if (k == BOOST && !Float.isNaN(v)) v *= 100;                 // kPa in the log
            b.append(',');
            if (!Float.isNaN(v)) b.append(String.format(Locale.US, k == VOLT ? "%.1f" : "%.0f", v));
        }
        return b.toString();
    }

    static String csvHeader() { return ",rpm,obd_kmh,throttle_pct,load_pct,coolant_c,boost_kpa,volts"; }

    /** The header line of the view: what is going on, in words. */
    String status() {
        switch (state) {
            case NONE: return "No OBD adapter chosen";
            case CONNECTING: {
                int s = retryIn();
                return "Connecting to " + device + (s > 0 ? ", next try in " + s + " s" : "\u2026");
            }
            case ADAPTER: return "Talking to the adapter\u2026";
            case SEARCHING: return "Searching for the car\u2026";
            case CONNECTED: {
                float r = rate();
                String rs = r >= 9.5f ? String.format(Locale.US, "%.0f", r) : String.format(Locale.US, "%.1f", r);
                return "Connected" + (protocolName.isEmpty() ? "" : " \u00b7 " + protocolName) + " \u00b7 " + rs + " readings/s";
            }
            case NO_CAR: return "Car not answering \u00b7 is the ignition on?";
            case LOST: {
                int s = retryIn();
                return "Connection lost" + (s > 0 ? " \u00b7 trying again in " + s + " s" : " \u00b7 trying again\u2026");
            }
            default: return "Car not answering for " + noEcuMs / 60000 + " minutes \u00b7 stopped talking to it";
        }
    }

    /** The conversation, newest last: what was sent (">") and what came back. */
    List<String> trace() {
        synchronized (trace) {
            return new ArrayList<String>(trace);
        }
    }

    // ---- fault codes ----

    int codesState() { return codesState; }
    String codesMessage() { return codesMessage; }
    List<Code> codes() { return codes; }
    long codesAt() { return codesAt; }
    int clearState() { return clearState; }
    String clearMessage() { return clearMessage; }

    /** Reads the stored and pending codes (the engine does it between two readings). */
    void readCodes() {
        if (state != CONNECTED) {
            codesState = CODES_FAILED;
            codesMessage = state == NO_CAR ? "The car is not answering: switch the ignition on" : "Not connected to the car";
            return;
        }
        codesState = CODES_READING;
        codesMessage = "";
        tasks.add(TASK_READ);
        wake();
    }

    /** Clears them (mode 04): the check-engine light goes out and the readiness monitors reset. Only from a confirmed tap. */
    void clearCodes() {
        if (state != CONNECTED) {
            clearState = CLEAR_FAILED;
            clearMessage = state == NO_CAR ? "The car is not answering: switch the ignition on" : "Not connected to the car";
            return;
        }
        clearState = CLEAR_CLEARING;
        clearMessage = "";
        tasks.add(TASK_CLEAR);
        wake();
    }

    /** The last diagnostics read, null before the first. */
    Diag diag() { return diag; }
    boolean diagReading() { return diagReading; }

    /** Reads the diagnostics (the engine does it between two readings); nothing when not connected. */
    void readDiag() {
        if (state != CONNECTED || diagReading) return;
        diagReading = true;
        tasks.add(TASK_DIAG);
        wake();
    }

    private final ConcurrentLinkedQueue<String> asks = new ConcurrentLinkedQueue<String>();

    /**
     * For testing over adb (Main's OBD_ASK): requests sent once between two readings, their replies only in the
     * trace (ObdTrace in logcat). "survey" asks for each reading the car lists, its VIN, calibration and ECU name.
     * Only requests that read (modes 01, 02, 03, 05, 06, 07, 09, 0A): never 04 (clears codes), 08 or AT commands.
     * Returns how many were queued.
     */
    int ask(String what) {
        List<String> out = new ArrayList<String>();
        if ("survey".equalsIgnoreCase(what.trim())) {
            for (int i = 1; i < 0x60; i++) if (supported[i] && i % 0x20 != 0) out.add(String.format(Locale.US, "01%02X", i));
            out.addAll(java.util.Arrays.asList("0900", "0902", "0904", "0906", "090A"));
        } else {
            for (String s : what.split("[,\\s]+")) {
                s = s.trim().toUpperCase(Locale.US);
                if (s.matches("0[1235679A][0-9A-F]{0,6}")) out.add(s);
            }
        }
        asks.addAll(out);
        wake();
        return out.size();
    }

    private void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    // ---- the thread ----

    private static long now() { return System.currentTimeMillis(); }

    private void setState(int s) {
        if (state != s) env.log("state " + STATE_NAME[s]);
        state = s;
    }

    private void note(String s) {
        android.util.Log.i("ObdTrace", s);                  // the same lines in logcat, for watching over adb
        synchronized (trace) {
            trace.add(String.format(Locale.US, "%6.1f  %s", (now() - traceZero) / 1000f, s));
            while (trace.size() > TRACE_KEEP) trace.remove(0);
        }
    }

    /** Whether this session should end: paused, held, or the device changed. */
    private boolean aborted(int g) {
        synchronized (lock) {
            return gen != g || !resumed || holds > 0;
        }
    }

    /** Waits up to ms; ends the session (Quit) when it should end meanwhile. */
    private void sleep(int g, long ms) throws Quit {
        long end = now() + ms;
        synchronized (lock) {
            for (;;) {
                if (gen != g || !resumed || holds > 0) throw new Quit();
                long left = end - now();
                if (left <= 0) return;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    throw new Quit();
                }
            }
        }
    }

    private void loop() {
        for (;;) {
            int g;
            synchronized (lock) {
                try {
                    while (!resumed || holds > 0 || idle) lock.wait();
                } catch (InterruptedException e) {
                    return;
                }
                g = gen;
            }
            String dev = env.device();
            device = dev == null ? "" : dev;
            try {
                if (dev == null) {
                    setState(NONE);
                    waitForChange(g);
                    continue;
                }
                if (env.busy()) {                             // a scan for devices: not now
                    sleep(g, 1500);
                    continue;
                }
                session(g);
            } catch (Quit q) {
                closeCurrent();
            } catch (Idle i) {
                closeCurrent();
                setState(IDLE);
                synchronized (lock) {
                    idle = true;
                }
            } catch (LinkGone e) {
                closeCurrent();
                lastError = e.getMessage() == null ? "" : e.getMessage();
                env.log("lost: " + lastError);
                fails++;
                long wait = fails > 6 ? 60000 : Math.min(30000, 2000L << Math.min(4, fails - 1));      // 2, 4, 8, 16, 30 s, then a minute
                retryAt = now() + wait;
                setState(connectedBefore ? LOST : CONNECTING);
                try {
                    sleep(g, wait);
                } catch (Quit q) { /* changed meanwhile */ }
            } catch (RuntimeException e) {
                closeCurrent();
                env.log("failed: " + e);
                lastError = "Internal error: " + e;
                fails++;
                try {
                    sleep(g, 5000);
                } catch (Quit q) { /* changed meanwhile */ }
            }
        }
    }

    private void waitForChange(int g) throws Quit {
        synchronized (lock) {
            try {
                while (gen == g && resumed && holds == 0) lock.wait();
            } catch (InterruptedException e) {
                throw new Quit();
            }
        }
    }

    private void closeCurrent() {
        Session s = cur;
        cur = null;
        if (s != null && s.link != null) s.link.close();
        tasks.clear();                                       // a request for the codes that never got asked
        diagReading = false;
        if (codesState == CODES_READING) {
            codesState = CODES_FAILED;
            codesMessage = "The connection to the car was lost";
        }
        if (clearState == CLEAR_CLEARING) {
            clearState = CLEAR_FAILED;
            clearMessage = "The connection to the car was lost: the codes may not be cleared";
        }
    }

    // ---- one session ----

    private void session(int g) throws Quit, LinkGone, Idle {
        traceZero = now();
        synchronized (trace) {
            trace.clear();
        }
        protocol = 0;
        protocolName = "";
        adapter = "";
        how = "";
        milCount = -1;
        milOn = false;
        synchronized (vals) {
            java.util.Arrays.fill(at, 0);
        }
        useCount = true;
        setState(CONNECTING);
        Session s = new Session();
        cur = s;
        note("opening " + device);
        s.link = env.open(s);
        if (s.link == null) throw new LinkGone("No adapter chosen");
        awaitOpen(g, s);
        how = s.link.how();
        note("open over " + how + " in " + s.link.openMs() + " ms");
        lastError = "";
        setState(ADAPTER);
        init(g);
        long searchStart = now();
        search(g, searchStart);
        known = true;
        fails = 0;
        connectedBefore = true;
        connectedAt = now();
        poll(g);
    }

    private void awaitOpen(int g, Session s) throws Quit, LinkGone {
        long end = now() + OPEN_MS;
        for (;;) {
            Ev e = take(g, s, Math.min(250, Math.max(1, end - now())));
            if (e == null) {
                if (now() >= end) throw new LinkGone("No answer when opening the link");
                continue;
            }
            if (e.type == EV_OPEN) return;
            if (e.type == EV_ERROR) lastError = e.text;
            if (e.type == EV_CLOSED) throw new LinkGone(lastError.isEmpty() ? "The link closed" : lastError);
            if (e.type == EV_ERROR) throw new LinkGone(e.text);
        }
    }

    private Ev take(int g, Session s, long ms) throws Quit {
        try {
            if (aborted(g)) throw new Quit();
            Ev e = s.q.poll(ms, TimeUnit.MILLISECONDS);
            if (e == null && aborted(g)) throw new Quit();
            return e;
        } catch (InterruptedException x) {
            throw new Quit();
        }
    }

    /** The adapter set up for readings: reset, no echo, no line feeds, no spaces, no headers, adaptive timing. */
    private void init(int g) throws Quit, LinkGone {
        List<String> r = null;
        for (int i = 0; i < 3 && r == null; i++) {
            try {
                r = exchange(g, "ATZ", ATZ_MS);
            } catch (Timeout t) {
                note("(no answer to ATZ)");
                drainFor(g, 700);
            }
        }
        if (r == null) throw new LinkGone("The adapter does not answer: is it an ELM327, and awake?");
        for (String l : r) {
            String c = ObdCodec.clean(l);
            if (c.contains("ELM") || c.contains("OBD")) {
                adapter = l.trim();
                break;
            }
        }
        for (String cmd : new String[] {"ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"}) {
            request(g, cmd, AT_MS, 1);
        }
    }

    /**
     * Asks the car: 0100 makes the adapter find the protocol. A protocol found before is tried first. Returns
     * when the car answered; until then the state is NO_CAR and it is asked again every PROBE_MS, until noEcuMs
     * have gone (Idle).
     */
    private void search(int g, long start) throws Quit, LinkGone, Idle {
        setState(SEARCHING);
        boolean first = true;
        for (;;) {
            List<String> r = null;
            if (first && hintProto > 0) {
                request(g, "ATSP" + Integer.toHexString(hintProto).toUpperCase(Locale.US), AT_MS, 1);
                r = request(g, "0100", SEARCH_MS, 0);
                if (ObdCodec.answer(r, 1, 0, 4) == null) {
                    note("(protocol " + hintProto + " did not answer: searching)");
                    request(g, "ATSP0", AT_MS, 1);
                    r = null;
                }
            }
            first = false;
            if (r == null) r = request(g, "0100", SEARCH_MS, 0);
            int[] d = ObdCodec.answer(r, 1, 0, 4);
            if (d != null) {
                learn(g, r);
                return;
            }
            int k = ObdCodec.kind(r);
            note("(the car did not answer: " + (k == ObdCodec.UNABLE ? "unable to connect" : k == ObdCodec.NO_DATA ? "no data" : "kind " + k) + ")");
            setState(NO_CAR);
            if (now() - start >= noEcuMs) throw new Idle();
            sleep(g, PROBE_MS);
        }
    }

    /** The protocol and the PIDs the car supports, from the answer to the first 0100. */
    private void learn(int g, List<String> first) throws Quit, LinkGone {
        boolean[] sup = new boolean[256];
        boolean more = false;
        for (int[] d : ObdCodec.answers(first, 1, 0, 4)) more |= ObdCodec.supported(d, 0x00, sup);
        List<String> r = request(g, "ATDPN", AT_MS, 1);
        protocol = ObdCodec.protocol(r);
        protocolName = ObdCodec.protocolName(protocol);
        if (protocol == 0) {
            r = request(g, "ATDP", AT_MS, 1);
            for (String l : r) {
                if (ObdCodec.kind(l) == ObdCodec.INFO) {
                    String c = l.trim();
                    protocolName = c.toUpperCase(Locale.US).startsWith("AUTO, ") ? c.substring(6) : c;
                    break;
                }
            }
        } else {
            hintProto = protocol;
        }
        env.log("protocol " + protocol + " " + protocolName);
        for (int base = 0x20; more && base <= 0x40; base += 0x20) {
            r = request(g, String.format(Locale.US, "01%02X", base), PID_MS, 1);
            more = false;
            for (int[] d : ObdCodec.answers(r, 1, base, 4)) more |= ObdCodec.supported(d, base, sup);
        }
        sup[0x01] = true;
        System.arraycopy(sup, 0, supported, 0, supported.length);
        ident = null;
        r = request(g, "ATRV", AT_MS, 1);
        voltOk = !Float.isNaN(ObdCodec.volts(r));
        if (voltOk) store(VOLT, ObdCodec.volts(r));
        env.log("supports " + supportedText());
    }

    private String supportedText() {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < 0x60; i++) if (supported[i]) b.append(String.format(Locale.US, "%02X ", i));
        return b.toString().trim();
    }

    // ---- polling ----

    private List<Item> items() {
        List<Item> out = new ArrayList<Item>();
        int[] fast = {RPM, SPEED, THROTTLE, MAP}, slow = {LOAD, COOLANT, INTAKE, BARO, TIMING, MAF, STFT, LTFT, FUEL,
            LAMBDA, LAMBDA_TARGET, PEDAL, OUTSIDE, CAT, FUEL_SYS};
        for (int k : fast) if (pidOf(k) >= 0) out.add(new Item(k, pidOf(k), true));
        for (int k : slow) if (pidOf(k) >= 0) out.add(new Item(k, pidOf(k), false));
        if (voltOk) out.add(new Item(VOLT, -1, false));
        out.add(new Item(-1, STATUS_PID, false));
        return out;
    }

    private void poll(int g) throws Quit, LinkGone, Idle {
        List<Item> items = items();
        long lastGood = now(), lastSlow = 0, goodSince = now();
        int failRun = 0;
        boolean inCar = true;
        setState(CONNECTED);
        for (;;) {
            Integer task = tasks.poll();
            if (task != null) {
                runTask(g, task, inCar);
                continue;
            }
            String ask = inCar ? asks.poll() : null;
            if (ask != null) {
                request(g, ask, CODES_MS, 1);
                continue;
            }
            if (!inCar) {                                      // asked again until the car answers
                sleep(g, PROBE_MS);
                List<String> r = request(g, "0100", SEARCH_MS, 0);
                if (ObdCodec.answer(r, 1, 0, 4) != null) {
                    inCar = true;
                    lastGood = now();
                    failRun = 0;
                    for (Item i : items) {
                        i.skipUntil = 0;
                        i.misses = 0;
                    }
                    setState(CONNECTED);
                } else if (now() - lastGood >= noEcuMs) {
                    throw new Idle();
                }
                continue;
            }
            if (failRun > 0 && now() - lastGood >= ECU_LOST_MS) {
                env.log("the car stopped answering");
                inCar = false;
                setState(NO_CAR);
                continue;
            }
            Item it = next(items, lastSlow);
            if (it == null) {                                  // all left alone for a while
                sleep(g, 500);
                continue;
            }
            if (!quick(it)) lastSlow = now();
            it.last = now();
            long t0 = now();
            boolean ok = read(g, it);
            if (ok) {
                long took = now() - t0;
                avgMs = avgMs * 0.8f + took * 0.2f;
                if (it.key != VOLT) {                          // the adapter's own voltage says nothing of the car
                    lastGood = now();
                    failRun = 0;
                }
                it.misses = 0;
                it.lastOk = now();
                if (now() - goodSince > 20000) fails = 0;
                synchronized (stamps) {
                    stamps[stampAt++ % stamps.length] = now();
                }
            } else {
                if (it.key != VOLT) failRun++;
                // listed but not answered while others are: leave it for a minute (not when nothing answers)
                if (++it.misses >= 3 && lastGood > it.lastOk) {
                    it.skipUntil = now() + 60000;
                    it.misses = 0;
                }
            }
        }
    }

    /** Read all the time (the rest take turns): a quick adapter reads the load that way too. */
    private boolean quick(Item i) {
        if (i.fast) return true;
        if (avgMs >= 90) return false;
        return i.key == LOAD || mixture && (i.key == LAMBDA || i.key == LAMBDA_TARGET || i.key == PEDAL);
    }

    private volatile boolean mixture;

    /** The Engine view's second page shows: the lambda and the pedal are read as often as the rpm while it does. */
    void watchingMixture(boolean on) { mixture = on; }

    /** The next thing to read: the slow one waiting longest every slowGap, else the quick one waiting longest. */
    private Item next(List<Item> items, long lastSlow) {
        long t = now();
        long slowGap = (long) Math.max(200, Math.min(3000, 4 * avgMs));
        Item fast = null, slow = null;
        for (Item i : items) {
            if (i.skipUntil > t) continue;
            if (quick(i)) {
                if (fast == null || i.last < fast.last) fast = i;
            } else if (slow == null || i.last < slow.last) {
                slow = i;
            }
        }
        if (fast == null) return slow;
        if (slow != null && t - lastSlow >= slowGap && slow.last < t - slowGap) return slow;
        return fast;
    }

    /** One reading; false when the car did not answer it (NO DATA, an error, a reply for something else). */
    private boolean read(int g, Item it) throws Quit, LinkGone {
        if (it.pid == STATUS_PID) {
            List<String> r = request(g, "0101", PID_MS, 1);
            int[] d = ObdCodec.answer(r, 1, STATUS_PID, 4);
            if (d == null) return false;
            milOn = ObdCodec.milOn(d);
            milCount = ObdCodec.milCount(d);
            return true;
        }
        if (it.key == VOLT) {
            float v = ObdCodec.volts(request(g, "ATRV", AT_MS, 1));
            if (Float.isNaN(v)) return false;
            store(VOLT, v);
            return true;
        }
        String cmd = String.format(Locale.US, "01%02X", it.pid);
        List<String> r = request(g, useCount ? cmd + "1" : cmd, PID_MS, 1);
        if (useCount && ObdCodec.kind(r) == ObdCodec.UNKNOWN_CMD) {       // an adapter that does not take the reply count
            useCount = false;
            note("(no reply count: plain requests from now)");
            r = request(g, cmd, PID_MS, 1);
        }
        float v = ObdCodec.value(it.pid, ObdCodec.answer(r, 1, it.pid, ObdCodec.need(it.pid)));
        if (Float.isNaN(v)) return false;
        store(it.key, v);
        return true;
    }

    private void store(int key, float v) {
        synchronized (vals) {
            vals[key] = v;
            at[key] = now();
        }
    }

    // ---- fault codes ----

    private void runTask(int g, int task, boolean inCar) throws Quit, LinkGone {
        if (task == TASK_DIAG) {
            try {
                if (inCar) diagnose(g);
            } finally {
                diagReading = false;
            }
            return;
        }
        if (!inCar) {
            if (task == TASK_READ) {
                codesState = CODES_FAILED;
                codesMessage = "The car is not answering: switch the ignition on";
            } else {
                clearState = CLEAR_FAILED;
                clearMessage = "The car is not answering: switch the ignition on";
            }
            return;
        }
        boolean can = ObdCodec.isCan(protocol);
        if (task == TASK_CLEAR) {
            List<String> r = request(g, "04", CODES_MS, 1);
            int[] d = ObdCodec.answer(r, 4, -1, 0);
            if (d != null) {
                clearState = CLEAR_DONE;
                clearMessage = "Cleared";
            } else {
                clearState = CLEAR_FAILED;
                clearMessage = ObdCodec.kind(r) == ObdCodec.NO_DATA ? "The car did not answer (is the engine running? Some cars only clear with the engine off)"
                    : "The car refused to clear the codes";
            }
        }
        List<String> stored = request(g, "03", CODES_MS, 1), pending = request(g, "07", CODES_MS, 1);
        int ks = ObdCodec.kind(stored), kp = ObdCodec.kind(pending);
        boolean sOk = ks == ObdCodec.DATA || ks == ObdCodec.NO_DATA, pOk = kp == ObdCodec.DATA || kp == ObdCodec.NO_DATA || kp == ObdCodec.UNKNOWN_CMD;
        if (!sOk) {
            codesState = CODES_FAILED;
            codesMessage = "The car did not answer the request for its codes";
            return;
        }
        List<Code> out = new ArrayList<Code>();
        for (String c : ObdCodec.codes(stored, 3, can)) out.add(new Code(c, false));
        if (pOk) {
            for (String c : ObdCodec.codes(pending, 7, can)) {
                boolean dup = false;
                for (Code o : out) if (o.code.equals(c)) dup = true;
                if (!dup) out.add(new Code(c, true));
            }
        }
        codes = out;
        codesAt = now();
        codesState = CODES_DONE;
        codesMessage = "";
        List<String> r = request(g, "0101", PID_MS, 1);
        int[] d = ObdCodec.answer(r, 1, STATUS_PID, 4);
        if (d != null) {
            milOn = ObdCodec.milOn(d);
            milCount = ObdCodec.milCount(d);
        }
    }

    // ---- diagnostics ----

    private void diagnose(int g) throws Quit, LinkGone {
        Diag d = new Diag();
        d.since = pidBytes(g, 0x01, 4);
        if (d.since != null) {
            milOn = ObdCodec.milOn(d.since);
            milCount = ObdCodec.milCount(d.since);
        }
        d.cycle = pidBytes(g, 0x41, 4);
        d.distMil = number(g, 0x21);
        d.minMil = number(g, 0x4D);
        d.warmups = number(g, 0x30);
        d.distClear = number(g, 0x31);
        d.minClear = number(g, 0x4E);
        d.runSec = number(g, 0x1F);
        d.standard = number(g, 0x1C);
        d.fuelType = number(g, 0x51);
        d.o2 = number(g, 0x13);
        int[] v = pidBytes(g, 0x42, 2);
        d.ecuVolt = ObdCodec.value(0x42, v);
        for (int i = 1; i < 0x60; i++) if (supported[i] && i % 0x20 != 0) d.readings++;
        if (ident == null) ident = identify(g);
        d.ident = ident;
        d.at = now();
        diag = d;
    }

    /** VIN, calibration IDs, CVNs and ECU name (mode 09): only what the car lists, nothing when it lists none. */
    private String[] identify(int g) throws Quit, LinkGone {
        String[] out = new String[4];
        int[] s = ObdCodec.answer(request(g, "0900", CODES_MS, 1), 9, 0, 4);
        if (s == null) return out;                   // an older car: asking for more could time out
        boolean[] sup = new boolean[256];
        ObdCodec.supported(s, 0, sup);
        int[] pids = {0x02, 0x04, 0x06, 0x0A};
        for (int i = 0; i < 4; i++) {
            if (!sup[pids[i]]) continue;
            int[] d = ObdCodec.info(request(g, String.format(Locale.US, "09%02X", pids[i]), CODES_MS, 1), pids[i]);
            if (d == null) continue;
            StringBuilder b = new StringBuilder();
            int step = i == CAL_ID ? 16 : i == CVN ? 4 : d.length;
            for (int at = 0; at + (i == CVN ? 4 : 1) <= d.length && step > 0; at += step) {
                String part = i == CVN ? String.format(Locale.US, "%02X%02X%02X%02X", d[at], d[at + 1], d[at + 2], d[at + 3])
                    : ObdCodec.ascii(d, at, at + step);
                if (!part.isEmpty()) b.append(b.length() > 0 ? ", " : "").append(part);
            }
            out[i] = b.toString();
        }
        return out;
    }

    private int[] pidBytes(int g, int pid, int need) throws Quit, LinkGone {
        if (!supported[pid]) return null;
        return ObdCodec.answer(request(g, String.format(Locale.US, "01%02X", pid), PID_MS, 1), 1, pid, need);
    }

    private int number(int g, int pid) throws Quit, LinkGone {
        float v = ObdCodec.value(pid, pidBytes(g, pid, ObdCodec.need(pid)));
        return Float.isNaN(v) ? -1 : Math.round(v);
    }

    // ---- commands ----

    /**
     * Sends a command and returns the lines of its reply (the prompt not among them). A reply that is not
     * for it (a late one of an earlier command) is dropped and the command sent again; so is STOPPED. No
     * prompt in time: wait a little for a late one, send again, and the second time give up on the link.
     */
    private List<String> request(int g, String cmd, long ms, int retries) throws Quit, LinkGone {
        int timeouts = 0, again = 0;
        for (;;) {
            List<String> r;
            try {
                r = exchange(g, cmd, ms);
            } catch (Timeout t) {
                note("(no answer to " + cmd + " in " + ms / 1000 + " s)");
                if (timeouts++ >= retries) throw new LinkGone("The adapter stopped answering");
                drainFor(g, GRACE_MS);
                continue;
            }
            boolean stopped = ObdCodec.kind(r) == ObdCodec.STOPPED;
            if ((stopped || stale(cmd, r)) && again++ < 2) {
                note(stopped ? "(stopped: again)" : "(a late reply: again)");
                if (stopped) sleep(g, 250);
                else drainFor(g, 600);
                continue;
            }
            return again > 2 && !stopped ? new ArrayList<String>() : r;
        }
    }

    /** A reply of data that is not an answer to this request. */
    private static boolean stale(String cmd, List<String> r) {
        if (ObdCodec.kind(r) != ObdCodec.DATA || cmd.length() < 2 || cmd.startsWith("AT")) return false;
        int mode = Character.digit(cmd.charAt(0), 16) * 16 + Character.digit(cmd.charAt(1), 16);
        if (mode > 0x0f) return false;
        int pid = cmd.length() >= 4 ? Integer.parseInt(cmd.substring(2, 4), 16) : -1;
        boolean hasPid = mode == 1 || mode == 2 || mode == 9;
        return ObdCodec.answer(r, mode, hasPid ? pid : -1, 0) == null && !multiFrame(r);
    }

    private static boolean multiFrame(List<String> r) {
        for (String l : r) {
            String c = ObdCodec.clean(l);
            if (c.length() >= 2 && c.charAt(1) == ':') return true;
        }
        return false;
    }

    /** Sends a line and collects the lines until the prompt. Timeout when the prompt does not come. */
    private List<String> exchange(int g, String cmd, long ms) throws Quit, Timeout, LinkGone {
        Session s = cur;
        if (s == null || s.link == null) throw new LinkGone("No link");
        // whatever the device said since the last prompt belongs to nothing
        for (Ev e; (e = s.q.poll()) != null; ) {
            if (e.type == EV_ERROR) lastError = e.text;
            if (e.type == EV_CLOSED) throw new LinkGone(lastError.isEmpty() ? "The adapter closed the connection" : lastError);
            if (e.type == EV_LINE && !e.text.equals(">")) note("   (dropped) " + e.text);
        }
        note("> " + cmd);
        s.link.send(cmd);
        List<String> lines = new ArrayList<String>();
        long end = now() + ms;
        for (;;) {
            Ev e = take(g, s, Math.min(250, Math.max(1, end - now())));
            if (e == null) {
                if (now() >= end) throw new Timeout();
                continue;
            }
            switch (e.type) {
                case EV_LINE:
                    if (e.text.equals(">")) return lines;
                    note("  " + e.text);
                    lines.add(e.text);
                    break;
                case EV_ERROR:
                    lastError = e.text;
                    throw new LinkGone(e.text);
                case EV_CLOSED:
                    throw new LinkGone(lastError.isEmpty() ? "The adapter closed the connection" : lastError);
                default:
                    break;
            }
        }
    }

    /** Lets a late reply come and go: reads and drops everything for up to ms, or until a prompt. */
    private void drainFor(int g, long ms) throws Quit, LinkGone {
        Session s = cur;
        long end = now() + ms;
        while (now() < end) {
            Ev e = take(g, s, Math.min(100, Math.max(1, end - now())));
            if (e == null) continue;
            if (e.type == EV_ERROR) lastError = e.text;
            if (e.type == EV_CLOSED) throw new LinkGone(lastError.isEmpty() ? "The adapter closed the connection" : lastError);
            if (e.type == EV_LINE) {
                note("   (late) " + e.text);
                if (e.text.equals(">")) return;
            }
        }
    }
}

package racing.coldshift.dash;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tries the OBD adapter chosen for Engine from Settings, Devices: opens a link, sends the ELM327's first
 * commands (ATZ, ATE0, ATI, ATDP, ATRV, 0100) one at a time, and logs every line it answers with, the
 * transport the link used and how long each step took. It is how the adapter is checked in the car before
 * the OBD page exists, and a small example of driving a BtLink: send a line, collect lines until the
 * prompt (">") comes, send the next, and give up on a step that stays silent.
 *
 * A step that gets no answer (REPLY_MS; the first 0100 may take longer while the adapter searches for the
 * car's protocol, SEARCH_MS) is logged and skipped, except the first: an adapter that does not answer ATZ
 * is not an ELM327 (or is not awake), and the test ends. Safe to call from the UI thread; the log is read
 * with lines().
 */
final class DeviceTest implements BtLink.Listener {
    static final String[] COMMANDS = {"ATZ", "ATE0", "ATI", "ATDP", "ATRV", "0100"};
    private static final int REPLY_MS = 6000, SEARCH_MS = 20000, KEEP = 200;

    private final Context ctx;
    private final Devices.Chosen engine;
    private final int transport;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<String> log = new ArrayList<String>();
    private BtLink link;
    private int next, generation, silent;
    private long started, sentAt;
    private volatile boolean running;
    private volatile String result = "";

    DeviceTest(Context c, Devices.Chosen engine, int transport) {
        ctx = c.getApplicationContext();
        this.engine = engine;
        this.transport = transport;
    }

    boolean running() { return running; }

    /** "Running…", or how it ended (empty before it starts). */
    String status() { return result; }

    synchronized List<String> lines() { return new ArrayList<String>(log); }

    synchronized void start() {
        if (running) return;
        running = true;
        Obd.get(ctx).hold();                                 // one adapter, one connection: the OBD page lets go of it
        result = "Running…";
        log.clear();
        next = 0;
        silent = 0;
        started = SystemClock.elapsedRealtime();
        note("Opening " + engine.label() + " (" + BtLink.NAME[transport].toLowerCase(Locale.US) + ")");
        link = Devices.get(ctx).open(engine, transport, this);
    }

    synchronized void stop() {
        if (link != null) link.close();
    }

    private void note(String s) {
        float t = (SystemClock.elapsedRealtime() - started) / 1000f;
        log.add(String.format(Locale.US, "%5.1f  %s", t, s));
        while (log.size() > KEEP) log.remove(0);
    }

    public synchronized void onOpen(BtLink l) {
        note("Open over " + l.how() + ", " + String.format(Locale.US, "%.1f", l.openMs() / 1000f) + " s to connect");
        sendNext();
    }

    private void sendNext() {
        if (next >= COMMANDS.length) {
            result = silent == 0 ? "Done: the adapter answered every command"
                : "Done, but " + silent + (silent == 1 ? " command" : " commands") + " got no answer";
            note("Test finished");
            link.close();
            return;
        }
        final int mine = ++generation;
        sentAt = SystemClock.elapsedRealtime();
        note("> " + COMMANDS[next]);
        link.send(COMMANDS[next]);
        h.postDelayed(new Runnable() {
            public void run() { silent(mine); }
        }, COMMANDS[next].equals("0100") ? SEARCH_MS : REPLY_MS);
    }

    public synchronized void onLine(BtLink l, String line) {
        if (!line.equals(">")) {
            note("   " + line);
            return;
        }
        generation++;                                        // the timeout of this step is no longer needed
        note(String.format(Locale.US, "   (%.2f s)", (SystemClock.elapsedRealtime() - sentAt) / 1000f));
        next++;
        sendNext();
    }

    /** A step stayed silent. */
    private synchronized void silent(int mine) {
        if (mine != generation || !running) return;
        silent++;
        long waited = SystemClock.elapsedRealtime() - sentAt;
        note("   no answer to " + COMMANDS[next] + " in " + waited / 1000 + " s");
        if (next == 0) {
            result = "No answer to ATZ: not an ELM327, or not awake";
            link.close();
            return;
        }
        next++;
        sendNext();
    }

    public synchronized void onError(BtLink l, String message) {
        result = "Failed: " + message;
        note("Failed: " + message);
    }

    public synchronized void onClosed(BtLink l) {
        generation++;
        if (running) Obd.get(ctx).release();
        running = false;
        if (result.equals("Running…")) result = "Closed";
        note("Closed");
    }
}

package racing.coldshift.dash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A line-oriented, two-way link to a Bluetooth device, for the ELM327 of the OBD page (and anything else
 * that talks lines): send() a line, and the lines it answers with come back through the listener. Open one
 * with Devices.open(); it connects on a thread of its own and every listener call (onOpen, onLine, onError,
 * onClosed) comes from one background thread, in order, never the UI thread. send() and close() may be
 * called from any thread, but not from a listener call that must not block.
 *
 * Two transports, picked by the device unless forced (AUTO, CLASSIC, LE):
 * <ul>
 * <li>CLASSIC: RFCOMM to the serial port profile (SPP). The secure socket first, then the insecure one,
 *     then the channel 1 socket that many ELM327 clones need (a reflection call, since Android has no
 *     public API for it). A try that fails quickly is followed by the next; one that gets no answer for
 *     CONNECT_MS (the adapter is off or away) ends it.</li>
 * <li>LE: GATT, with a "serial" service: the known ELM ones (FFF0, FFE0, 18F0 and so on) first, else any
 *     service with a notify characteristic and a write characteristic. Notifications are switched on
 *     through the descriptor 0x2902, writes go out 20 bytes at a time. A GATT operation is in flight one
 *     at a time (Android 4.4's stack drops the second), and a failed connection (status 133, the stack's
 *     "something went wrong") is closed and opened again, up to three times.</li>
 * </ul>
 * AUTO takes the device's own type: Classic-only or LE-only as it is; a dual-mode one over Classic when it
 * is paired and offers SPP, else over LE. Classic is never tried on a device that is not paired unless
 * asked for (Android would put up its pairing dialog); the user pairs in Settings, Devices.
 *
 * What comes in is cut into lines at carriage returns and line feeds, and the ELM's prompt ">" is a line of
 * its own (it ends a reply without a line break). Empty lines are dropped. A line sent gets a carriage
 * return added.
 *
 * Opening is timed (openMs). A link that fails calls onError with a reason a person can read, then onClosed;
 * one closed on purpose (close()) calls onClosed alone. After onClosed nothing more comes.
 */
final class BtLink {
    interface Listener {
        /** Connected; transport(), how() and openMs say how. */
        void onOpen(BtLink l);
        /** One line (or the prompt ">") the device sent. */
        void onLine(BtLink l, String line);
        /** It failed or was lost: onClosed follows. */
        void onError(BtLink l, String message);
        /** Over: closed on purpose, or after an error. */
        void onClosed(BtLink l);
    }

    static final int AUTO = 0, CLASSIC = 1, LE = 2;
    static final String[] NAME = {"Auto", "Classic", "LE"};

    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD = uuid("2902");
    /** The time to wait for the device to answer a connect, a service search or a write. */
    static final int CONNECT_MS = 10000, DISCOVER_MS = 8000, WRITE_MS = 2500;
    private static final int CHUNK = 20;                     // the default ATT payload: 23 - 3
    private static final byte[] END = new byte[0];

    /**
     * The serial services of LE adapters, as OBD apps list them: {service, notify characteristic, write
     * characteristic}. A 4-digit UUID is a short one. FFF0 and FFE0 are the cheap ELM327 clones' (and
     * HM-10 style modules'), 18F0 the Vgate iCar Pro's, the long ones the Microchip/ISSC transparent UART
     * some vLinker and OBDLink adapters use, a common generic OBD one, and Nordic's UART.
     */
    static final String[][] SERIAL = {
        {"FFF0", "FFF1", "FFF2"},
        {"FFE0", "FFE1", "FFE1"},
        {"18F0", "2AF0", "2AF1"},
        {"49535343-FE7D-4AE5-8FA9-9FAFD205E455", "49535343-1E4D-4BD9-BA61-23C647249616", "49535343-8841-43F4-A8D4-ECBE34729BB3"},
        {"E7810A71-73AE-499D-8C15-FAA9AEF0C3F2", "BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F", "BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"},
        {"6E400001-B5A0-F393-89E4-0E6D2F0F7C4E", "6E400003-B5A0-F393-89E4-0E6D2F0F7C4E", "6E400002-B5A0-F393-89E4-0E6D2F0F7C4E"}};

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "bt-timer");
            t.setDaemon(true);
            return t;
        }
    });

    private interface Transport {
        /** Connects, ready to write and read; throws a reason a person can read. */
        void open() throws IOException;
        void write(byte[] b) throws IOException;
        void close();
        String how();
    }

    final String address, name;
    private final Context ctx;
    private final BluetoothAdapter adapter;
    private final int wanted, known;
    private final Listener listener;
    private final ExecutorService events = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<byte[]>();
    private final AtomicBoolean closed = new AtomicBoolean(), finished = new AtomicBoolean();
    private final StringBuilder rx = new StringBuilder();
    private final long started = SystemClock.elapsedRealtime();
    private volatile Transport current;
    private volatile boolean open;
    private boolean ended;                                   // onClosed has been called (the events thread only)
    private volatile int used;
    private volatile long openMs;
    private volatile String how = "";

    /** known: the transports the device is known to speak (CLASSIC | LE), 0 for not known. */
    private BtLink(Context c, String address, String name, int known, int wanted, Listener l) {
        ctx = c.getApplicationContext();
        adapter = BluetoothAdapter.getDefaultAdapter();
        this.address = address;
        this.name = name == null || name.isEmpty() ? address : name;
        this.known = known;
        this.wanted = wanted;
        listener = l;
    }

    /** Starts connecting (on a thread of its own) and returns at once. */
    static BtLink open(Context c, String address, String name, int known, int wanted, Listener l) {
        final BtLink link = new BtLink(c, address, name, known, wanted, l);
        Thread t = new Thread(new Runnable() {
            public void run() { link.run(); }
        }, "bt-link-" + address);
        t.setDaemon(true);
        t.start();
        return link;
    }

    /** Whether it is connected now. */
    boolean isOpen() { return open; }

    /** CLASSIC or LE once open (0 before), and a few words on how ("Classic, insecure RFCOMM", "LE, service FFF0"). */
    int transport() { return used; }
    String how() { return how; }

    /** How long opening took, in ms (0 until open). */
    long openMs() { return openMs; }

    /** Sends a line; a carriage return is added. Lines wait in order until the link is open. */
    void send(String line) { sendRaw((line + "\r").getBytes(java.nio.charset.Charset.forName("ISO-8859-1"))); }

    void sendRaw(byte[] b) {
        if (!closed.get()) out.offer(b);
    }

    /** Closes it (the listener hears onClosed). Safe to call again, and before it has connected. */
    void close() {
        closed.set(true);
        out.offer(END);
        finish(null);
    }

    // ---- the thread ----

    private void run() {
        try {
            if (adapter == null || !adapter.isEnabled()) {
                finish("Bluetooth is off");
                return;
            }
            try {
                adapter.cancelDiscovery();                  // a scan slows a connect to a crawl
            } catch (SecurityException e) { /* the connect says so */ }
            int[] order = plan();
            log("plan " + java.util.Arrays.toString(order) + " for " + address);
            StringBuilder why = new StringBuilder();
            Transport t = null;
            for (int i = 0; i < order.length && t == null && !closed.get(); i++) {
                Transport next = order[i] == CLASSIC ? new Rfcomm() : new Gatt();
                current = next;
                try {
                    next.open();
                    t = next;
                    used = order[i];
                } catch (IOException e) {
                    next.close();
                    if (why.length() > 0) why.append("; ");
                    why.append(NAME[order[i]]).append(": ").append(e.getMessage());
                    log(NAME[order[i]] + " failed: " + e.getMessage());
                }
            }
            if (t == null) {
                finish(closed.get() ? null : why.toString());
                return;
            }
            how = t.how();
            openMs = SystemClock.elapsedRealtime() - started;
            log("open: " + how + " in " + openMs + " ms");
            if (closed.get()) return;
            open = true;
            post(new Runnable() {
                public void run() { listener.onOpen(BtLink.this); }
            });
            while (!closed.get()) {
                byte[] b = out.take();
                if (b == END || closed.get()) break;
                try {
                    t.write(b);
                } catch (IOException e) {
                    finish("Could not send: " + e.getMessage());
                    return;
                }
            }
        } catch (InterruptedException e) {
            finish(null);
        } catch (SecurityException e) {
            finish("Bluetooth is not allowed: give the app Nearby devices in Android's settings");
        } catch (RuntimeException e) {
            android.util.Log.w("BtLink", "link to " + address, e);
            finish("Bluetooth failed: " + e);
        }
    }

    /** The transports to try, in order. */
    private int[] plan() {
        if (wanted == CLASSIC) return new int[] {CLASSIC};
        if (wanted == LE) return new int[] {LE};
        BluetoothDevice d = adapter.getRemoteDevice(address);
        int type = d.getType();                             // DEVICE_TYPE_CLASSIC 1, LE 2, DUAL 3, UNKNOWN 0
        boolean bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
        int kinds = type == BluetoothDevice.DEVICE_TYPE_UNKNOWN ? known : type;
        if (kinds == CLASSIC) return new int[] {CLASSIC};
        if (kinds == LE || kinds == 0) return new int[] {LE};
        // dual-mode: Classic when it is paired and offers SPP, else LE; Classic after LE only if paired
        if (bonded && hasSpp(d)) return new int[] {CLASSIC, LE};
        return bonded ? new int[] {LE, CLASSIC} : new int[] {LE};
    }

    private static boolean hasSpp(BluetoothDevice d) {
        ParcelUuid[] u = d.getUuids();
        if (u != null) for (ParcelUuid p : u) if (SPP.equals(p.getUuid())) return true;
        return false;
    }

    // ---- what comes in, and the end ----

    /** Bytes the device sent: cut into lines and passed on. */
    private void feed(byte[] b, int n) {
        synchronized (rx) {
            for (int i = 0; i < n; i++) {
                char ch = (char) (b[i] & 0xff);
                if (ch == '\r' || ch == '\n' || ch == '>') {
                    flush();
                    if (ch == '>') line(">");
                } else if (ch != 0) {
                    rx.append(ch);
                }
            }
        }
    }

    private void flush() {
        if (rx.length() == 0) return;
        String s = rx.toString().trim();
        rx.setLength(0);
        if (!s.isEmpty()) line(s);
    }

    private void line(final String s) {
        post(new Runnable() {
            public void run() { if (!ended) listener.onLine(BtLink.this, s); }
        });
    }

    private static void log(String s) { android.util.Log.i("BtLink", s); }

    private void post(Runnable r) {
        try {
            events.execute(r);
        } catch (RejectedExecutionException e) { /* already over */ }
    }

    /** Ends it, once: an error (null for none) and then onClosed. */
    private void finish(final String error) {
        if (!finished.compareAndSet(false, true)) return;
        log("closed" + (error == null ? "" : ": " + error));
        closed.set(true);
        open = false;
        out.offer(END);
        Transport t = current;
        if (t != null) t.close();
        if (error != null) {
            post(new Runnable() {
                public void run() { listener.onError(BtLink.this, error); }
            });
        }
        post(new Runnable() {
            public void run() {
                ended = true;
                listener.onClosed(BtLink.this);
            }
        });
        events.shutdown();
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new IOException("interrupted");
        }
    }

    /** A 4-digit short UUID, or a whole one. */
    static UUID uuid(String s) {
        return UUID.fromString(s.length() == 4 ? "0000" + s + "-0000-1000-8000-00805F9B34FB" : s);
    }

    // ---- Classic: RFCOMM ----

    private final class Rfcomm implements Transport {
        private BluetoothSocket socket;
        private String how = "Classic";

        public String how() { return how; }

        public void open() throws IOException {
            BluetoothDevice d = adapter.getRemoteDevice(address);
            IOException last = null;
            for (int i = 0; i < 3 && !closed.get(); i++) {
                final BluetoothSocket s;
                String way = i == 0 ? "secure RFCOMM" : i == 1 ? "insecure RFCOMM" : "RFCOMM channel 1";
                try {
                    if (i == 0) s = d.createRfcommSocketToServiceRecord(SPP);
                    else if (i == 1) s = d.createInsecureRfcommSocketToServiceRecord(SPP);
                    else s = channelOne(d);
                } catch (IOException e) {
                    last = e;
                    continue;
                }
                socket = s;
                final AtomicBoolean late = new AtomicBoolean();
                // a connect to an adapter that is off can wait for half a minute: closing the socket ends it
                java.util.concurrent.ScheduledFuture<?> timeout = TIMER.schedule(new Runnable() {
                    public void run() {
                        late.set(true);
                        try {
                            s.close();
                        } catch (IOException e) { /* gone */ }
                    }
                }, CONNECT_MS, TimeUnit.MILLISECONDS);
                try {
                    log("trying " + way);
                    s.connect();
                    timeout.cancel(false);
                    how = "Classic, " + way;
                    startReader(s);
                    return;
                } catch (IOException e) {
                    timeout.cancel(false);
                    last = e;
                    log(way + ": " + e.getMessage() + (late.get() ? " (timed out)" : ""));
                    try {
                        s.close();
                    } catch (IOException x) { /* gone */ }
                    if (closed.get()) throw new IOException("closed");
                    if (late.get()) {
                        throw new IOException("no answer from the device in " + CONNECT_MS / 1000
                            + " s: is it plugged in and in range?");
                    }
                    sleep(400);                              // a quick failure: the next way
                }
            }
            String why = last == null ? "no socket" : last.getMessage();
            throw new IOException(why == null ? "could not connect" : "could not connect (" + why + ")");
        }

        /** createRfcommSocket(1): hidden, but the way many ELM327 clones can be reached. */
        private BluetoothSocket channelOne(BluetoothDevice d) throws IOException {
            try {
                Method m = d.getClass().getMethod("createRfcommSocket", int.class);
                return (BluetoothSocket) m.invoke(d, 1);
            } catch (Exception e) {
                throw new IOException("no channel 1 socket on this Android");
            }
        }

        private void startReader(final BluetoothSocket s) throws IOException {
            final InputStream in = s.getInputStream();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    byte[] buf = new byte[256];
                    try {
                        int n;
                        while ((n = in.read(buf)) >= 0) feed(buf, n);
                        if (!closed.get()) finish("The adapter closed the connection");
                    } catch (IOException e) {
                        if (!closed.get()) finish("Lost the connection to the adapter");
                    }
                }
            }, "bt-read-" + address);
            t.setDaemon(true);
            t.start();
        }

        public void write(byte[] b) throws IOException {
            OutputStream o = socket.getOutputStream();
            o.write(b);
            o.flush();
        }

        public void close() {
            try {
                if (socket != null) socket.close();
            } catch (IOException e) { /* gone */ }
        }
    }

    // ---- LE: GATT ----

    private static final class Retry extends IOException {
        Retry(String m) { super(m); }
    }

    private final class Gatt extends BluetoothGattCallback implements Transport {
        private BluetoothGatt gatt;
        private BluetoothGattCharacteristic notify, write;
        private volatile CountDownLatch step;                 // the operation in flight; one at a time
        private volatile int status;
        private volatile boolean connected, ready;
        private String how = "LE";
        private boolean confirmed;

        public String how() { return how; }

        public void open() throws IOException {
            IOException last = null;
            for (int attempt = 0; attempt < 3 && !closed.get(); attempt++) {
                long t0 = SystemClock.elapsedRealtime();
                try {
                    openOnce();
                    return;
                } catch (Retry e) {
                    last = e;                                // the stack's 133: close, wait, open again
                    drop();
                    // one that took its time was the device not being there, not the stack's hiccup
                    if (SystemClock.elapsedRealtime() - t0 > 3000) break;
                    sleep(Api.SDK < 21 ? 1000 : 600);
                }
            }
            if (closed.get()) throw new IOException("closed");
            throw last == null ? new IOException("could not connect") : last;
        }

        private void openOnce() throws IOException {
            BluetoothDevice d = adapter.getRemoteDevice(address);
            expect();
            connected = false;
            gatt = Api.SDK >= 23 ? d.connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE)
                : d.connectGatt(ctx, false, this);
            if (gatt == null) throw new IOException("Android would not start the connection");
            await(CONNECT_MS, "no answer from the device in " + CONNECT_MS / 1000 + " s: is it switched on and in range?", false);
            if (status != 0 || !connected) {
                throw new Retry(status == 133 ? "could not reach the device (GATT status 133): is it switched on and in range?"
                    : "the connection failed (GATT status " + status + ")");
            }
            sleep(Api.SDK < 21 ? 600 : 250);                 // 4.4 and some others lose a search started at once
            expect();
            if (!gatt.discoverServices()) throw new Retry("the service search would not start");
            await(DISCOVER_MS, "the device did not list its services", true);
            if (status != 0) throw new Retry("the service search failed (GATT status " + status + ")");
            pick();
            enableNotifications();
            ready = true;
        }

        /** The serial characteristics: a known service, else any with a notify and a write one. */
        private void pick() throws IOException {
            for (String[] k : SERIAL) {
                BluetoothGattService s = gatt.getService(uuid(k[0]));
                if (s == null) continue;
                BluetoothGattCharacteristic n = s.getCharacteristic(uuid(k[1])), w = s.getCharacteristic(uuid(k[2]));
                if (n != null && w != null && can(n, BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)
                        && can(w, BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) {
                    use(k[0], n, w);
                    return;
                }
            }
            StringBuilder seen = new StringBuilder();
            for (BluetoothGattService s : gatt.getServices()) {
                String id = shortId(s.getUuid());
                if (seen.length() > 0) seen.append(' ');
                seen.append(id);
                if (id.startsWith("1800") || id.startsWith("1801") || id.startsWith("180A")) continue;
                BluetoothGattCharacteristic n = null, w = null;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (n == null && can(c, BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) n = c;
                    if (can(c, BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                            && (w == null || w == n)) w = c;
                }
                if (n != null && w != null) {
                    use(id, n, w);
                    return;
                }
            }
            throw new IOException("it has no serial service (it lists: " + (seen.length() == 0 ? "nothing" : seen) + ")");
        }

        private void use(String service, BluetoothGattCharacteristic n, BluetoothGattCharacteristic w) {
            notify = n;
            write = w;
            confirmed = can(w, BluetoothGattCharacteristic.PROPERTY_WRITE);
            how = "LE, service " + shortId(uuid(service));
        }

        private boolean can(BluetoothGattCharacteristic c, int props) { return (c.getProperties() & props) != 0; }

        private void enableNotifications() throws IOException {
            gatt.setCharacteristicNotification(notify, true);
            BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD);
            if (cccd == null) return;                       // some clones have none and notify anyway
            boolean notifies = can(notify, BluetoothGattCharacteristic.PROPERTY_NOTIFY);
            cccd.setValue(notifies ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            expect();
            if (!gatt.writeDescriptor(cccd)) throw new IOException("could not switch on notifications");
            await(WRITE_MS * 2, "the device did not accept notifications", false);
            if (status != 0) throw new IOException("the device refused notifications (GATT status " + status + ")");
        }

        public void write(byte[] b) throws IOException {
            if (!ready || !connected) throw new IOException("not connected");
            write.setWriteType(confirmed ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            for (int i = 0; i < b.length; i += CHUNK) {
                byte[] chunk = Arrays.copyOfRange(b, i, Math.min(b.length, i + CHUNK));
                for (int tries = 0; ; tries++) {
                    expect();
                    write.setValue(chunk);
                    if (gatt.writeCharacteristic(write)) break;
                    if (tries >= 40) throw new IOException("Android would not take the write");
                    sleep(25);                               // the stack is busy with something else
                }
                if (confirmed) {
                    await(WRITE_MS, "the device did not confirm a write", false);
                    if (status != 0) throw new IOException("a write failed (GATT status " + status + ")");
                } else {
                    try {
                        step.await(300, TimeUnit.MILLISECONDS);   // most stacks confirm these too; not all
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted");
                    }
                }
            }
        }

        public void close() {
            ready = false;
            drop();
        }

        /** Disconnects and lets go of the GATT client (always closed: leaked ones use up the stack's slots). */
        private void drop() {
            final BluetoothGatt g = gatt;
            gatt = null;
            if (g == null) return;
            try {
                if (connected) g.disconnect();
            } catch (RuntimeException e) { /* gone */ }
            connected = false;
            TIMER.schedule(new Runnable() {
                public void run() {
                    try {
                        g.close();
                    } catch (RuntimeException e) { /* gone */ }
                }
            }, 300, TimeUnit.MILLISECONDS);
        }

        private void expect() {
            status = -1;
            step = new CountDownLatch(1);
        }

        private void release(int s) {
            status = s;
            CountDownLatch l = step;
            if (l != null) l.countDown();
        }

        private void await(long ms, String timeout, boolean retry) throws IOException {
            try {
                if (!step.await(ms, TimeUnit.MILLISECONDS)) throw retry ? new Retry(timeout) : new IOException(timeout);
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
            if (closed.get()) throw new IOException("closed");
        }

        @Override public void onConnectionStateChange(BluetoothGatt g, int s, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED && s == BluetoothGatt.GATT_SUCCESS) {
                connected = true;
                release(0);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                if (ready) finish("The device disconnected");
                else release(s != 0 ? s : -2);
            } else if (s != 0) {
                release(s);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int s) { release(s); }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int s) { release(s); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int s) { release(s); }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v != null) feed(v, v.length);
        }

        // Android 13 and later call this one, which no longer goes through the one above
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            if (v != null) feed(v, v.length);
        }
    }

    /** "FFF0" for a short UUID, else the whole one. */
    static String shortId(UUID u) {
        String s = u.toString().toUpperCase(java.util.Locale.US);
        return s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805F9B34FB") ? s.substring(4, 8) : s;
    }
}

package racing.coldshift.dash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Bluetooth devices of this car, the foundation of OBD, tyre sensors, an outside GPS, a talk button
 * and a headset. It owns Bluetooth for the app: it finds devices, guesses what each is, remembers which
 * device does which job (a role), and opens links to them (BtLink). Settings, Devices is its page.
 *
 * Finding: the devices already paired with the phone, a Classic discovery scan, and a Bluetooth LE scan
 * (BluetoothAdapter.startLeScan on 4.4, BluetoothLeScanner from Android 5, kept apart in Le21 so that 4.4
 * never loads it). scan() runs both for a while and merges what they find by address into one Found per
 * device: its name, whether it is paired, which transports it speaks (Classic, LE or both), its signal,
 * its Classic device class and what its LE adverts carry (manufacturer data, service UUIDs). The adverts
 * are cut up here from their raw bytes, the same on every Android.
 *
 * Recognising is a best guess shown to the person, who decides: an OBD adapter (its name, or an LE serial
 * service), a TPMS sensor (the advert formats it knows, below), a GPS receiver (its name), a headset or
 * car audio (Classic major class audio/video), a remote or keyboard (class peripheral, or the LE HID
 * service 0x1812), else unknown.
 *
 * Roles: Engine (an OBD adapter), GPS, Talk button, Headset and Tyres. The first four take one device,
 * Tyres up to four sensors (which wheel is which is for the TPMS phase). They are kept in the preferences
 * as one JSON string, "devices" (backed up in settings.json with the other settings):
 *   {"engine": {"name": "OBDII", "address": "AA:BB:..", "transport": "classic"|"le"|"dual"|"tcp",
 *               "kind": "obd", "link": "auto"|"classic"|"le"}, "gps": {..}, "talk": {..}, "headset": {..},
 *    "tyres": [{..}, ..]}
 * "transport" is what the device was seen to speak, "link" the transport a person forced for it (auto
 * lets BtLink choose). A "tcp" device (address "tcp:host:port") is for developing: chooseTcp. Other code reads chosen(role) and tyres(), and hears roleChanged() on the UI thread.
 *
 * Permissions: Android 12+ asks for Nearby devices (BLUETOOTH_SCAN, marked neverForLocation, and
 * BLUETOOTH_CONNECT); Android 6 to 11 let LE scanning find devices only with location permission and the
 * phone's location switched on; 4.4 and older ask for nothing at run time. problem() says what is missing.
 */
final class Devices {
    static final int ENGINE = 0, GPS = 1, TALK = 2, HEADSET = 3, TYRES = 4;
    static final String[] ROLE_KEY = {"engine", "gps", "talk", "headset", "tyres"};
    static final String[] ROLE_NAME = {"Engine", "GPS", "Talk button", "Headset", "Tyres"};
    static final String[] ROLE_ABOUT = {"OBD adapter", "GPS receiver", "Remote or keyboard", "Headset or car audio", "Tyre sensors"};
    static final int MAX_TYRES = 4;

    /** What a device is guessed to be (Found.kind). */
    static final int UNKNOWN = 0, OBD = 1, TPMS = 2, GPS_RECEIVER = 3, AUDIO = 4, REMOTE = 5;
    static final String[] KIND_KEY = {"unknown", "obd", "tpms", "gps", "audio", "remote"};
    static final String[] KIND_NAME = {"Unknown", "OBD adapter", "Tyre sensor", "GPS receiver", "Headset or audio", "Remote or keyboard"};
    /** The kind that suits a role. */
    static final int[] ROLE_KIND = {OBD, GPS_RECEIVER, REMOTE, AUDIO, TPMS};

    /** Transports a device speaks, as bit flags (both is a dual-mode device). */
    static final int CLASSIC = BtLink.CLASSIC, LE = BtLink.LE;
    /** A device reached over TCP instead of Bluetooth (address "tcp:host:port"): a Wi-Fi adapter, or a simulator; see chooseTcp. */
    static final int TCP = 4;

    /** How near a device is: not seen, looked for in a scan under way, or seen lately (the status dot). */
    static final int AWAY = 0, LOOKING = 1, NEAR = 2;
    private static final long NEAR_MS = 45000;

    private static final String NEARBY_SCAN = "android.permission.BLUETOOTH_SCAN";
    private static final String NEARBY_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    interface Listener {
        /** The list of devices found, the scan or Bluetooth itself changed (the UI thread). */
        void devicesChanged();
        /** A role's device was chosen, changed or cleared (the UI thread). */
        void roleChanged(int role);
    }

    /** A device seen: paired, in a Classic scan or in an LE scan. */
    static final class Found {
        final String address;
        volatile String name = "";
        volatile int kind, transports, rssi = Integer.MIN_VALUE, major = -1;
        volatile boolean bonded;
        volatile long seen, connected;                  // when last seen, and last found connected (elapsedRealtime)
        volatile BluetoothDevice device;
        final Map<Integer, byte[]> manufacturer = new LinkedHashMap<Integer, byte[]>();
        final List<String> services = new ArrayList<String>();

        Found(String address) { this.address = address; }

        String label() { return name.isEmpty() ? "(no name)" : name; }
        String kindName() { return KIND_NAME[kind]; }
        String transportName() { return Devices.transportName(transports); }

        synchronized boolean hasService(String id) {
            for (String s : services) if (s.equalsIgnoreCase(id)) return true;
            return false;
        }

        /** "mfr 0100 · services FFF0 FFE0": what an LE advert carried, or "". */
        synchronized String adverts() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<Integer, byte[]> e : manufacturer.entrySet()) {
                if (b.length() > 0) b.append(" · ");
                b.append("mfr ").append(String.format(Locale.US, "%04X", e.getKey()));
            }
            if (!services.isEmpty()) {
                if (b.length() > 0) b.append(" · ");
                b.append("services");
                for (String s : services) b.append(' ').append(s.length() > 8 ? s.substring(0, 8) + "…" : s);
            }
            return b.toString();
        }
    }

    /** The device chosen for a role. */
    static final class Chosen {
        final String name, address;
        final int transports, kind, link;
        Chosen(String name, String address, int transports, int kind, int link) {
            this.name = name == null ? "" : name;
            this.address = address;
            this.transports = transports;
            this.kind = kind;
            this.link = link;
        }
        String label() { return name.isEmpty() ? address : name; }
        String transportName() { return Devices.transportName(transports); }
        String kindName() { return KIND_NAME[kind]; }
        boolean isTcp() { return (transports & TCP) != 0; }
        Chosen withLink(int l) { return new Chosen(name, address, transports, kind, l); }
    }

    private static Devices instance;
    private final Context ctx;
    private final BluetoothAdapter adapter;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Map<String, Found> found = new LinkedHashMap<String, Found>();
    private final Chosen[] chosen = new Chosen[TYRES];
    private final List<Chosen> tyres = new ArrayList<Chosen>();
    private boolean scanning, discovering, leScanning;
    private long scanEnds, boundAt;
    private String scanError = "";
    private BluetoothAdapter.LeScanCallback legacyScan;
    private Object newScan;                              // an Le21 (Android 5+); an Object so that 4.4 never loads the class
    private final Runnable stopper = new Runnable() {
        public void run() { stopScan(); }
    };

    static synchronized Devices get(Context c) {
        if (instance == null) instance = new Devices(c.getApplicationContext());
        return instance;
    }

    private Devices(Context c) {
        ctx = c;
        adapter = BluetoothAdapter.getDefaultAdapter();
        load();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        Api.listen(c, new BroadcastReceiver() {
            @Override public void onReceive(Context cx, Intent in) { onBroadcast(in); }
        }, f, null, false);
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        h.post(new Runnable() {
            public void run() { for (Listener l : listeners) l.devicesChanged(); }
        });
    }

    private void changed(final int role) {
        h.post(new Runnable() {
            public void run() { for (Listener l : listeners) l.roleChanged(role); }
        });
    }

    static String transportName(int t) {
        return (t & TCP) != 0 ? "TCP" : t == (CLASSIC | LE) ? "Classic + LE" : t == CLASSIC ? "Classic" : t == LE ? "LE" : "";
    }

    // ---- Bluetooth itself, and what it needs ----

    /** Whether this phone has Bluetooth at all. */
    boolean present() { return adapter != null; }

    boolean on() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    /** The permissions Android has not granted yet and scanning needs (see the class comment). */
    String[] missing() {
        List<String> m = new ArrayList<String>();
        if (Api.SDK >= 31) {
            if (!Api.granted(ctx, NEARBY_SCAN)) m.add(NEARBY_SCAN);
            if (!Api.granted(ctx, NEARBY_CONNECT)) m.add(NEARBY_CONNECT);
        } else if (Api.SDK >= 23 && !Api.granted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            m.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return m.toArray(new String[0]);
    }

    /** Android 6 to 11 show LE scan results only while the phone's location is switched on. */
    boolean locationOff() {
        if (Api.SDK < 23 || Api.SDK >= 31) return false;
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            return !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What stops scanning, in words for the Devices page, or "" when all is well. */
    String problem() {
        if (adapter == null) return "This phone has no Bluetooth";
        String[] m = missing();
        if (m.length > 0) return Api.SDK >= 31 ? "Needs the Nearby devices permission to find and use Bluetooth devices"
            : "Needs the location permission to scan for Bluetooth devices";
        if (!on()) return "Bluetooth is off";
        if (locationOff()) return "Android shows scan results only while location is on";
        return "";
    }

    /** Asks Android to switch Bluetooth on (its own dialog). */
    void enable(Main a) {
        try {
            holdKiosk(60000);
            a.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (RuntimeException e) {
            android.util.Log.w("Devices", "cannot ask to switch Bluetooth on", e);
        }
    }

    /** Android's own dialogs (switching on, pairing) are not pushed back behind the kiosk for a while. */
    private void holdKiosk(long ms) {
        if (Prefs.kiosk(ctx)) Watchdog.allowUntil = Math.max(Watchdog.allowUntil, System.currentTimeMillis() + ms);
    }

    // ---- finding ----

    /** Every device known: paired ones and those a scan found, as they are now. */
    synchronized List<Found> found() {
        long now = SystemClock.elapsedRealtime();
        if (now - boundAt > 3000) {
            boundAt = now;
            refreshBonded();
        }
        return new ArrayList<Found>(found.values());
    }

    synchronized Found find(String address) { return found.get(address); }

    boolean scanning() { return scanning; }

    /** Milliseconds left of the scan under way (0 when none). */
    long scanLeft() { return scanning ? Math.max(0, scanEnds - SystemClock.elapsedRealtime()) : 0; }

    /** What went wrong starting the last scan, or "". */
    String scanError() { return scanError; }

    /** Looks for devices for `ms`: a Classic discovery and an LE scan together. */
    synchronized void scan(int ms) {
        if (!problem().isEmpty()) return;
        stopScan();
        scanError = "";
        refreshBonded();
        refreshConnected();
        long now = SystemClock.elapsedRealtime();
        for (java.util.Iterator<Found> it = found.values().iterator(); it.hasNext(); ) {
            Found f = it.next();
            if (!f.bonded && now - f.seen > 10 * 60000L) it.remove();
        }
        scanEnds = now + ms;
        scanning = true;
        try {
            discovering = adapter.startDiscovery();
        } catch (SecurityException e) {
            discovering = false;
            scanError = "Classic scan not allowed";
        }
        startLe();
        h.removeCallbacks(stopper);
        h.postDelayed(stopper, ms);
        changed();
    }

    private void startLe() {
        try {
            if (Api.SDK >= 21) {
                newScan = Le21.start(this, adapter);
                leScanning = newScan != null;
            } else {
                legacyScan = new BluetoothAdapter.LeScanCallback() {
                    public void onLeScan(BluetoothDevice d, int rssi, byte[] record) { merge(d, rssi, record, LE); }
                };
                leScanning = adapter.startLeScan(legacyScan);
            }
            if (!leScanning) scanError = "LE scan did not start";
        } catch (RuntimeException e) {
            android.util.Log.w("Devices", "cannot start an LE scan", e);
            scanError = "LE scan not allowed";
        }
    }

    synchronized void stopScan() {
        h.removeCallbacks(stopper);
        try {
            if (discovering) adapter.cancelDiscovery();
            if (leScanning) {
                if (Api.SDK >= 21) {
                    if (newScan != null) ((Le21) newScan).stop(adapter);
                } else if (legacyScan != null) {
                    adapter.stopLeScan(legacyScan);
                }
            }
        } catch (RuntimeException e) {
            android.util.Log.w("Devices", "cannot stop scanning", e);
        }
        newScan = null;
        legacyScan = null;
        discovering = leScanning = false;
        if (scanning) {
            scanning = false;
            changed();
        }
    }

    /** Paired devices; and which known ones are no longer paired. */
    private synchronized void refreshBonded() {
        if (adapter == null) return;
        try {
            if (!on()) return;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (Found f : found.values()) {
                BluetoothDevice d = f.device;
                if (d != null) f.bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
            }
            if (bonded != null) for (BluetoothDevice d : bonded) merge(d, Integer.MIN_VALUE, null, 0);
        } catch (SecurityException e) { /* no permission yet */ }
    }

    /** Devices connected now for audio (headsets, car stereos): they do not show up in a scan. */
    private void refreshConnected() {
        for (int profile : new int[] {BluetoothProfile.HEADSET, BluetoothProfile.A2DP}) {
            try {
                adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                    public void onServiceConnected(int p, BluetoothProfile proxy) {
                        try {
                            for (BluetoothDevice d : proxy.getConnectedDevices()) {
                                Found f = merge(d, Integer.MIN_VALUE, null, 0);
                                f.connected = SystemClock.elapsedRealtime();
                            }
                        } catch (RuntimeException e) { /* no permission, or gone */ }
                        try {
                            adapter.closeProfileProxy(p, proxy);
                        } catch (RuntimeException e) { /* gone */ }
                    }
                    public void onServiceDisconnected(int p) {}
                }, profile);
            } catch (RuntimeException e) { /* not on this phone */ }
        }
    }

    private void onBroadcast(Intent in) {
        String a = in.getAction();
        if (BluetoothDevice.ACTION_FOUND.equals(a)) {
            BluetoothDevice d = in.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;
            Found f = merge(d, in.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE), null, CLASSIC);
            BluetoothClass bc = in.getParcelableExtra(BluetoothDevice.EXTRA_CLASS);
            if (bc != null) {
                f.major = bc.getMajorDeviceClass();
                f.kind = recognise(f);
            }
            String n = in.getStringExtra(BluetoothDevice.EXTRA_NAME);
            if (n != null && !n.isEmpty()) {
                f.name = n;
                f.kind = recognise(f);
            }
        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
            discovering = false;
        } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) {
            BluetoothDevice d = in.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d != null) merge(d, Integer.MIN_VALUE, null, 0);
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
            if (!on()) stopScan();
        }
        changed();
    }

    /**
     * Adds what was seen of a device to its Found. source: CLASSIC or LE for a sighting (a scan result),
     * 0 for a device merely listed (paired, connected).
     */
    private synchronized Found merge(BluetoothDevice d, int rssi, byte[] record, int source) {
        String address = d.getAddress();
        Found f = found.get(address);
        if (f == null) {
            f = new Found(address);
            found.put(address, f);
        }
        f.device = d;
        try {
            String n = d.getName();
            if (n != null && !n.isEmpty()) f.name = n;
            f.bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
            int type = d.getType();                        // 1 Classic, 2 LE, 3 dual, 0 not known
            f.transports |= type != 0 ? type : source;
            if (f.major < 0) {
                BluetoothClass bc = d.getBluetoothClass();
                if (bc != null) f.major = bc.getMajorDeviceClass();
            }
        } catch (SecurityException e) {
            f.transports |= source;
        }
        if (source != 0) f.seen = SystemClock.elapsedRealtime();
        if (rssi != Integer.MIN_VALUE && rssi != Short.MIN_VALUE) f.rssi = rssi;
        if (record != null) parseAdvert(record, f);
        f.kind = recognise(f);
        return f;
    }

    /** The Le21 callback (Android 5+), apart so that 4.4 never loads it. */
    private static final class Le21 extends ScanCallback {
        private final Devices d;

        private Le21(Devices d) { this.d = d; }

        static Le21 start(Devices d, BluetoothAdapter a) {
            BluetoothLeScanner s = a.getBluetoothLeScanner();
            if (s == null) return null;
            Le21 cb = new Le21(d);
            s.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
            return cb;
        }

        void stop(BluetoothAdapter a) {
            BluetoothLeScanner s = a.getBluetoothLeScanner();
            if (s != null) s.stopScan(this);
        }

        @Override public void onScanResult(int type, ScanResult r) {
            d.merge(r.getDevice(), r.getRssi(), r.getScanRecord() == null ? null : r.getScanRecord().getBytes(), LE);
            d.changed();
        }

        @Override public void onBatchScanResults(List<ScanResult> rs) {
            for (ScanResult r : rs) onScanResult(0, r);
        }

        @Override public void onScanFailed(int code) {
            d.scanError = "LE scan failed (" + code + ")";
            d.changed();
        }
    }

    /**
     * Cuts a raw LE advert into its fields (Bluetooth core spec, "advertising data"): length, type, data.
     * The local name, the service UUIDs (16, 32 and 128 bit; 16-bit ones are kept short, "FFF0") including
     * those of service data, and manufacturer data (the first two bytes, low byte first, are the company id).
     */
    private static void parseAdvert(byte[] b, Found f) {
        synchronized (f) {
            int i = 0;
            while (i + 1 < b.length) {
                int len = b[i] & 0xff;
                if (len == 0) break;
                int end = i + 1 + len, type = b[i + 1] & 0xff;
                if (end > b.length) break;
                switch (type) {
                    case 0x02: case 0x03: case 0x16:
                        for (int p = i + 2; p + 1 < end; p += 2) {
                            addService(f, String.format(Locale.US, "%04X", ((b[p + 1] & 0xff) << 8) | (b[p] & 0xff)));
                            if (type == 0x16) break;           // service data: one UUID, then the data
                        }
                        break;
                    case 0x04: case 0x05:
                        for (int p = i + 2; p + 3 < end; p += 4) {
                            addService(f, String.format(Locale.US, "%08X", ((b[p + 3] & 0xffL) << 24) | ((b[p + 2] & 0xff) << 16)
                                | ((b[p + 1] & 0xff) << 8) | (b[p] & 0xff)));
                        }
                        break;
                    case 0x06: case 0x07:
                        for (int p = i + 2; p + 15 < end; p += 16) addService(f, uuid128(b, p));
                        break;
                    case 0x08: case 0x09:
                        if (f.name.isEmpty() || type == 0x09) {
                            try {
                                String n = new String(b, i + 2, len - 1, "UTF-8").trim();
                                if (!n.isEmpty()) f.name = n;
                            } catch (java.io.UnsupportedEncodingException e) { /* UTF-8 is always there */ }
                        }
                        break;
                    case 0xff:
                        if (len >= 3) {
                            f.manufacturer.put(((b[i + 3] & 0xff) << 8) | (b[i + 2] & 0xff),
                                java.util.Arrays.copyOfRange(b, i + 4, end));
                        }
                        break;
                    default:
                        break;
                }
                i = end;
            }
        }
    }

    private static String uuid128(byte[] b, int p) {
        StringBuilder s = new StringBuilder();
        for (int k = 15; k >= 0; k--) {
            s.append(String.format(Locale.US, "%02X", b[p + k] & 0xff));
            if (k == 12 || k == 10 || k == 8 || k == 6) s.append('-');
        }
        String u = s.toString();
        // a short one in a long one's clothes: 0000xxxx-0000-1000-8000-00805F9B34FB
        return u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805F9B34FB") ? u.substring(4, 8) : u;
    }

    private static void addService(Found f, String id) {
        for (String s : f.services) if (s.equals(id)) return;
        f.services.add(id);
    }

    // ---- recognising ----

    /** Names of OBD adapters (lower case, found anywhere in the name). */
    private static final String[] OBD_NAMES = {"obd", "elm327", "elm 327", "v-link", "vlink", "kw906", "konnwei", "vgate",
        "veepeak", "viecar", "bluedriver", "scantool", "carista"};
    private static final String[] GPS_NAMES = {"garmin glo", "racebox", "xgps", "bad elf", "badelf", "qstarz", "gnss", "gps"};
    /** LE services that serial OBD adapters use. */
    private static final String[] OBD_SERVICES = {"FFF0", "FFE0", "18F0", "E7810A71-73AE-499D-8C15-FAA9AEF0C3F2",
        "49535343-FE7D-4AE5-8FA9-9FAFD205E455"};

    /** A best guess at what a device is, from its name, class and adverts. */
    static int recognise(Found f) {
        String n = f.name.toLowerCase(Locale.US);
        // a tyre sensor's formats (docs/devices-plan.md): the name TPMS{N}_... (with company 0x0100) or TPMS,
        // company 0x00AC with service FBB0, and service 0x27A5 (named BR)
        if (n.contains("tpms") || f.manufacturer.containsKey(0x00AC) && f.hasService("FBB0") || f.hasService("27A5")) return TPMS;
        for (String s : OBD_NAMES) if (n.contains(s)) return OBD;
        if (n.startsWith("icar") || n.startsWith("i-car")) return OBD;
        for (String s : GPS_NAMES) if (n.contains(s)) return GPS_RECEIVER;
        if (f.major == BluetoothClass.Device.Major.AUDIO_VIDEO) return AUDIO;
        if (f.major == BluetoothClass.Device.Major.PERIPHERAL || f.hasService("1812")) return REMOTE;
        for (String s : OBD_SERVICES) if (f.hasService(s)) return OBD;
        return UNKNOWN;
    }

    // ---- pairing ----

    /** Starts pairing: Android shows its own PIN dialog. False when it would not start (or already paired). */
    boolean pair(Found f) {
        try {
            if (f.device == null || f.bonded) return false;
            if (scanning) stopScan();                      // a scan under way makes pairing fail
            holdKiosk(60000);
            return f.device.createBond();
        } catch (RuntimeException e) {
            android.util.Log.w("Devices", "cannot pair " + f.address, e);
            return false;
        }
    }

    /** Whether Android is pairing with it now. */
    boolean pairing(Found f) {
        try {
            return f.device != null && f.device.getBondState() == BluetoothDevice.BOND_BONDING;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- roles ----

    /** The device for a role (not Tyres), or null. */
    synchronized Chosen chosen(int role) { return role < TYRES ? chosen[role] : null; }

    /** The tyre sensors of this car, up to MAX_TYRES. */
    synchronized List<Chosen> tyres() { return new ArrayList<Chosen>(tyres); }

    synchronized boolean isTyre(String address) {
        for (Chosen c : tyres) if (c.address.equals(address)) return true;
        return false;
    }

    /** Whether this device is chosen for any role. */
    synchronized boolean inUse(String address) {
        for (Chosen c : chosen) if (c != null && c.address.equals(address)) return true;
        return isTyre(address);
    }

    private static Chosen of(Found f) {
        return new Chosen(f.name, f.address, f.transports, f.kind, BtLink.AUTO);
    }

    /** Chooses the device for a role (not Tyres; see toggleTyre). */
    synchronized void choose(int role, Found f) {
        if (role >= TYRES) return;
        chosen[role] = of(f);
        save();
        changed(role);
    }

    /** Ticks or unticks a tyre sensor; false when four are chosen already and it is not one of them. */
    synchronized boolean toggleTyre(Found f) {
        for (int i = 0; i < tyres.size(); i++) {
            if (tyres.get(i).address.equals(f.address)) {
                tyres.remove(i);
                save();
                changed(TYRES);
                return true;
            }
        }
        if (tyres.size() >= MAX_TYRES) return false;
        tyres.add(of(f));
        save();
        changed(TYRES);
        return true;
    }

    /** Forgets a role's device (the phone's pairing stays). */
    synchronized void clear(int role) {
        if (role == TYRES) tyres.clear();
        else chosen[role] = null;
        save();
        changed(role);
    }

    /**
     * For developing without a car: points the Engine role at a TCP ELM327 ("host:port"; a simulator on a
     * computer, or a Wi-Fi adapter) with a Chosen whose address is "tcp:host:port" and whose transport is
     * "tcp". The device it replaces is kept (the "engine_before" preference) for restoreEngine(); pointing
     * at TCP again does not overwrite that. Reached by Main's ENGINE broadcast, nothing in the UI.
     */
    synchronized void chooseTcp(String hostPort) {
        Chosen now = chosen[ENGINE];
        android.content.SharedPreferences sp = Prefs.sp(ctx);
        if (now != null && !now.isTcp()) {
            try {
                sp.edit().putString("engine_before", write(now).toString()).apply();
            } catch (org.json.JSONException e) { /* nothing to keep */ }
        }
        chosen[ENGINE] = new Chosen("Wi-Fi " + hostPort, "tcp:" + hostPort, TCP, OBD, BtLink.AUTO);
        save();
        changed(ENGINE);
    }

    /** Puts back the Engine device chooseTcp replaced (none if there was none). Returns what it is now. */
    synchronized Chosen restoreEngine() {
        android.content.SharedPreferences sp = Prefs.sp(ctx);
        String before = sp.getString("engine_before", "");
        Chosen back = null;
        try {
            if (!before.isEmpty()) back = read(new JSONObject(before));
        } catch (org.json.JSONException e) { /* none */ }
        chosen[ENGINE] = back;
        sp.edit().remove("engine_before").apply();
        save();
        changed(ENGINE);
        return back;
    }

    /** Forces the transport a role's link uses (BtLink.AUTO, CLASSIC or LE). */
    synchronized void setLink(int role, int link) {
        if (role >= TYRES || chosen[role] == null) return;
        chosen[role] = chosen[role].withLink(link);
        save();
        changed(role);
    }

    /** Whether a chosen device is near now (NEAR, LOOKING while a scan looks for it, or AWAY). */
    synchronized int state(String address) {
        Found f = found.get(address);
        long now = SystemClock.elapsedRealtime();
        if (f != null && (now - f.seen < NEAR_MS && f.seen > 0 || now - f.connected < NEAR_MS && f.connected > 0)) return NEAR;
        return scanning ? LOOKING : AWAY;
    }

    /** The roles read again from the preferences (after a restore from settings.json). */
    synchronized void reload() {
        Chosen[] before = chosen.clone();
        String tyresBefore = tyreAddresses();
        load();
        for (int r = 0; r < TYRES; r++) {
            if (!same(before[r], chosen[r])) changed(r);
        }
        if (!tyresBefore.equals(tyreAddresses())) changed(TYRES);
    }

    private String tyreAddresses() {
        StringBuilder b = new StringBuilder();
        for (Chosen c : tyres) b.append(c.address).append(' ');
        return b.toString();
    }

    private static boolean same(Chosen a, Chosen b) {
        return a == null ? b == null : b != null && a.address.equals(b.address) && a.link == b.link;
    }

    private void load() {
        chosen[0] = chosen[1] = chosen[2] = chosen[3] = null;
        tyres.clear();
        try {
            String s = Prefs.devices(ctx);
            if (s.isEmpty()) return;
            JSONObject o = new JSONObject(s);
            for (int r = 0; r < TYRES; r++) chosen[r] = read(o.optJSONObject(ROLE_KEY[r]));
            JSONArray t = o.optJSONArray(ROLE_KEY[TYRES]);
            for (int i = 0; t != null && i < t.length() && tyres.size() < MAX_TYRES; i++) {
                Chosen c = read(t.optJSONObject(i));
                if (c != null) tyres.add(c);
            }
        } catch (Exception e) {
            android.util.Log.w("Devices", "cannot read the saved devices", e);
        }
    }

    private static Chosen read(JSONObject o) {
        if (o == null || o.optString("address", "").isEmpty()) return null;
        String t = o.optString("transport", "");
        int transports = t.equals("dual") ? (CLASSIC | LE) : t.equals("classic") ? CLASSIC : t.equals("le") ? LE : t.equals("tcp") ? TCP : 0;
        int kind = index(KIND_KEY, o.optString("kind", ""));
        int link = o.optString("link", "auto").equals("classic") ? BtLink.CLASSIC : o.optString("link", "auto").equals("le") ? BtLink.LE : BtLink.AUTO;
        return new Chosen(o.optString("name", ""), o.optString("address"), transports, Math.max(0, kind), link);
    }

    private static int index(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return -1;
    }

    private static JSONObject write(Chosen c) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("name", c.name);
        o.put("address", c.address);
        o.put("transport", c.transports == (CLASSIC | LE) ? "dual" : c.transports == CLASSIC ? "classic" : c.transports == LE ? "le"
            : c.transports == TCP ? "tcp" : "");
        o.put("kind", KIND_KEY[c.kind]);
        o.put("link", c.link == BtLink.CLASSIC ? "classic" : c.link == BtLink.LE ? "le" : "auto");
        return o;
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            for (int r = 0; r < TYRES; r++) if (chosen[r] != null) o.put(ROLE_KEY[r], write(chosen[r]));
            JSONArray t = new JSONArray();
            for (Chosen c : tyres) t.put(write(c));
            if (t.length() > 0) o.put(ROLE_KEY[TYRES], t);
            Prefs.setDevices(ctx, o.length() == 0 ? "" : o.toString());
        } catch (org.json.JSONException e) {
            android.util.Log.w("Devices", "cannot save the devices", e);
        }
    }

    // ---- links ----

    /** Opens a line link to a role's device (Obd's): TCP for a tcp: one, else Bluetooth with its forced transport. */
    Link link(Chosen c, Link.Listener l) {
        if (c.isTcp()) return TcpLink.open(c.address.substring(4), l);
        return new Link.Bt(this, c, l);
    }

    /** Opens a link to a role's device with the transport forced for it (usually AUTO). See BtLink. */
    BtLink open(Chosen c, BtLink.Listener l) { return open(c, c.link, l); }

    /** Opens a link with the transport given (BtLink.AUTO, CLASSIC or LE). A scan under way is stopped. */
    BtLink open(Chosen c, int transport, BtLink.Listener l) {
        stopScan();
        return BtLink.open(ctx, c.address, c.name, c.transports, transport, l);
    }
}

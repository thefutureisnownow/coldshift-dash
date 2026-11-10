package racing.coldshift.dash;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Convoys on the local network, found as LocalNet finds games (Development/localnet, its discovery.gd,
 * ported): a small JSON probe over UDP to every address of every local IPv4 /24 (each interface the
 * phone has, cellular and VPN ones left out), plus broadcast, and the hosts that answer. A host is a
 * phone hosting a convoy (Host) or a convoy server on a laptop. Unicast to each address works on phone
 * hotspots, where broadcast often does not reach.
 *
 * A host is reached at the address its reply came from, never one it names: a phone with Wi-Fi, a
 * hotspot and mobile data has several addresses and cannot know which one a guest can reach (why a QR
 * code with an address is not enough). Its reply lists its convoys, so one is joined by tapping it.
 *
 * The port is the convoy's own, 47660 (UDP for discovery, TCP for the convoy itself on a phone host),
 * beside LocalNet games' 47650-47653.
 *
 * And keeping a local host reachable: a phone on Wi-Fi without internet (a hotspot) while mobile data is
 * on keeps mobile data as its default network, and Android routes an app's sockets by the default
 * network, so they never arrive. From Android 6 the app binds itself to the Wi-Fi network for a local
 * host (as LocalNetWifiRoute); on 5 it sets the process's default network; on 4.4 it asks for a route
 * to that one address over Wi-Fi. The hotspot phone itself has no Wi-Fi network to bind and reaches its
 * guests directly.
 */
final class Nearby {
    static final String GAME = "coldshift.convoy";
    static final int PORT = 47660, VERSION = 1;
    // two sweeps a second or so apart, listened to for LISTEN_MS in all: a phone's Wi-Fi in power save
    // answers late, and a probe can be lost (LocalNet sweeps every second for the same reasons)
    static final int LISTEN_MS = 3000, SWEEP_AGAIN_MS = 1200, PER_SOCKET = 32;
    /** Cellular, VPN, loopback and the like: never swept (LocalNet's list). */
    private static final String[] SKIP = {"lo", "rmnet", "ccmni", "pdp_ip", "clat", "v4-", "dummy", "tun", "ipsec", "ppp",
        "p2p"};

    /** A host that answered, with the convoys it has. */
    static final class Host {
        String id, name, url;
        int online;
        long pingMs;
        boolean compatible;
        final List<Found> convoys = new ArrayList<Found>();
    }

    /** A convoy on a host nearby: tapping it joins (with its password, when it has one). */
    static final class Found {
        Host host;
        String name, token;
        boolean locked;
        int cars;
    }

    private Nearby() {}

    /** This phone's usable IPv4 addresses (LocalNet's local_ipv4). */
    static List<Inet4Address> localAddresses() {
        List<Inet4Address> out = new ArrayList<Inet4Address>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || skipped(ni.getName())) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    out.add((Inet4Address) a);
                }
            }
        } catch (Exception e) {
            android.util.Log.w("Nearby", "cannot list the network interfaces", e);
        }
        return out;
    }

    private static boolean skipped(String name) {
        for (String p : SKIP) if (name.startsWith(p)) return true;
        return false;
    }

    /**
     * Probes every local /24 (twice) and returns the hosts that answer. Blocks for LISTEN_MS: call it off
     * the UI thread. Empty with no local network.
     *
     * As LocalNet does, the probes are spread over short-lived sockets, PER_SOCKET each: probes to empty
     * addresses wait in the kernel's ARP queue, charged to the socket that sent them, and one socket's
     * send blocks once its buffer fills.
     */
    static List<Host> scan(Context c) {
        useWifi(c, true);
        Map<String, Host> found = new LinkedHashMap<String, Host>();
        List<DatagramSocket> socks = new ArrayList<DatagramSocket>();
        long sent = System.currentTimeMillis();
        try {
            byte[] probe = ("{\"localnet\":\"probe\",\"game\":\"" + GAME + "\",\"v\":" + VERSION + ",\"sent\":" + sent + "}")
                .getBytes("UTF-8");
            List<InetSocketAddress> targets = new ArrayList<InetSocketAddress>();
            List<String> nets = new ArrayList<String>();
            for (Inet4Address me : localAddresses()) {
                byte[] net = me.getAddress();
                String key = (net[0] & 0xff) + "." + (net[1] & 0xff) + "." + (net[2] & 0xff);
                if (nets.contains(key)) continue;
                nets.add(key);
                // addresses from their bytes: from a string, each would be a name lookup (about 5 ms on the G7)
                for (int i = 1; i < 256; i++) {
                    net[3] = (byte) i;                     // .255: the /24's broadcast
                    targets.add(new InetSocketAddress(InetAddress.getByAddress(net.clone()), PORT));
                }
            }
            if (targets.isEmpty()) return new ArrayList<Host>();
            targets.add(new InetSocketAddress(InetAddress.getByAddress(new byte[] {-1, -1, -1, -1}), PORT));
            send(targets, probe, socks, found, sent);
            boolean again = true;
            while (System.currentTimeMillis() < sent + LISTEN_MS) {
                read(socks, found, sent);
                if (again && System.currentTimeMillis() >= sent + SWEEP_AGAIN_MS) {
                    again = false;
                    send(targets, probe, socks, found, sent);
                }
                Thread.sleep(10);
            }
        } catch (Exception e) {
            android.util.Log.w("Nearby", "scan failed", e);
        } finally {
            for (DatagramSocket s : socks) s.close();
        }
        android.util.Log.i("Nearby", found.size() + " host(s) in " + (System.currentTimeMillis() - sent) + " ms");
        return new ArrayList<Host>(found.values());
    }

    /** Every convoy the hosts have, in the order they answered. */
    static List<Found> convoys(List<Host> hosts) {
        List<Found> out = new ArrayList<Found>();
        for (Host h : hosts) out.addAll(h.convoys);
        return out;
    }

    /** Sends the probes, PER_SOCKET to a new socket, reading replies as it goes. */
    private static void send(List<InetSocketAddress> targets, byte[] probe, List<DatagramSocket> socks, Map<String, Host> found,
                            long sent) throws java.io.IOException {
        for (int first = 0; first < targets.size(); first += PER_SOCKET) {
            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(1);
            s.setBroadcast(true);
            socks.add(s);
            for (InetSocketAddress t : targets.subList(first, Math.min(first + PER_SOCKET, targets.size()))) {
                try {
                    s.send(new DatagramPacket(probe, probe.length, t));
                } catch (Exception e) { /* that address can't be sent to */ }
            }
            read(socks, found, sent);
        }
    }

    /** Whatever replies have arrived on any of the sockets. */
    private static void read(List<DatagramSocket> socks, Map<String, Host> found, long sent) {
        byte[] buf = new byte[8192];
        for (DatagramSocket s : socks) {
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (Exception e) {
                    break;                                  // nothing more on this one for now
                }
                try {
                    JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                    if (!"host".equals(o.optString("localnet")) || !GAME.equals(o.optString("game"))) continue;
                    String id = o.optString("id", p.getAddress().getHostAddress());
                    if (found.containsKey(id)) continue;   // a host seen on several interfaces keeps the first address
                    Host h = new Host();
                    h.id = id;
                    h.name = o.optString("host", o.optString("name", "Convoy host"));
                    h.url = "http://" + p.getAddress().getHostAddress() + ":" + o.optInt("port", PORT);
                    h.online = o.optInt("players", 0);
                    h.compatible = o.optInt("v") == VERSION;
                    h.pingMs = System.currentTimeMillis() - o.optLong("sent", sent);
                    JSONArray cs = o.optJSONArray("convoys");
                    for (int i = 0; cs != null && i < cs.length(); i++) {
                        JSONObject co = cs.optJSONObject(i);
                        if (co == null || co.optString("token").isEmpty()) continue;
                        Found f = new Found();
                        f.host = h;
                        f.name = co.optString("name", "Convoy");
                        f.token = co.optString("token");
                        f.locked = co.optBoolean("locked");
                        f.cars = co.optInt("cars");
                        h.convoys.add(f);
                    }
                    found.put(id, h);
                } catch (Exception e) { /* not ours */ }
            }
        }
    }

    // ---- keeping a local host on Wi-Fi ----

    private static boolean bound;

    /**
     * Keeps a host at `url` reachable: one on the local network over Wi-Fi (not mobile data), and for one
     * on the internet, Android's own choice again. Call off the UI thread (it looks the name up).
     */
    static void routeOverWifi(Context c, String url) {
        try {
            InetAddress a = InetAddress.getByName(java.net.URI.create(url).getHost());
            if (a.isLoopbackAddress()) return;                         // this phone's own host
            boolean local = a.isSiteLocalAddress() || a.isLinkLocalAddress();
            if (Build.VERSION.SDK_INT >= 21) useWifi(c, local);
            else if (local) routeTo(c, a);
        } catch (Exception e) {
            android.util.Log.w("Nearby", "cannot route " + url + " over Wi-Fi", e);
        }
    }

    /**
     * Binds this app to the Wi-Fi network (on), or back to Android's default (off): Android 5 and later.
     * Without a Wi-Fi network it unbinds.
     */
    static synchronized void useWifi(Context c, boolean on) {
        if (Build.VERSION.SDK_INT < 21 || (!on && !bound)) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network wifi = null;
            if (on) {
                for (android.net.Network n : cm.getAllNetworks()) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                        wifi = n;
                        break;
                    }
                }
            }
            if (wifi == null && !bound) return;
            if (Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(wifi);
            else ConnectivityManager.setProcessDefaultNetwork(wifi);
            bound = wifi != null;
            android.util.Log.i("Nearby", bound ? "bound to Wi-Fi" : "back to the default network");
        } catch (Exception e) {
            android.util.Log.w("Nearby", "cannot bind to Wi-Fi", e);
        }
    }

    /** Android 4.4: a route to that address over Wi-Fi (requestRouteToHost, gone from later SDKs). */
    private static void routeTo(Context c, InetAddress a) throws Exception {
        byte[] b = a.getAddress();
        if (b.length != 4) return;
        int addr = (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Object ok = ConnectivityManager.class.getMethod("requestRouteToHost", int.class, int.class)
            .invoke(cm, ConnectivityManager.TYPE_WIFI, addr);
        if (!Boolean.TRUE.equals(ok)) android.util.Log.i("Nearby", "no Wi-Fi route to " + a + " (none needed, or no Wi-Fi)");
    }
}

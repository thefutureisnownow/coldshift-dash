package racing.coldshift.dash;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * This phone as the convoy's host, for driving with no internet: the others join its hotspot (or the
 * same Wi-Fi), find the convoy with Nearby and connect straight to this phone, which relays between them
 * as the convoy server does (coldshift-dash-convoy: the same HTTP and WebSocket messages, so the
 * phones' side is unchanged). It is LocalNet's lobby host in this app's terms: discovery replies on UDP
 * Nearby.PORT carry its convoy, and the convoy itself is on TCP Nearby.PORT.
 *
 * One convoy at a time, started from the Convoy page as on any host (POST /api/convoys); this phone joins
 * it over loopback (Convoy.LOCAL). The convoy (token, name, password, starter's key) is kept in the
 * preferences, so a restart of the app carries on with it; members, messages and voice notes are in
 * memory only. It runs while this phone's convoy host is itself (Convoy), with a Wi-Fi lock so the radio
 * keeps listening with the screen off. Ending the convoy (or choosing another host) closes everyone's
 * connection with "ended".
 *
 * A message or voice note may name one member ("to"): it then goes only to that member's connections and the
 * sender's own, carrying "to" and "to_name", and the welcome's history holds a private message only for its
 * sender and recipient. A "to" that names nobody known goes back to the sender alone (to_name empty).
 *
 * Each connection has a thread that reads and one that writes from a queue, so one guest gone quiet with
 * a full send buffer holds up nobody else; a queue that grows past QUEUE_MAX drops that guest.
 */
final class Host {
    static final int MAX_MEMBERS = 30, MAX_TEXT = 500, MAX_CAR = 64 * 1024, MAX_FRAME = 320 * 1024;
    static final int MAX_VOICE = 192 * 1024, MAX_VOICE_MS = 31000, KEEP_MESSAGES = 100, KEEP_VOICES = 30, KEEP_CIDS = 1000;
    static final int MAX_AGE_MS = 15 * 60 * 1000, LATE_MS = 30 * 1000, READ_MS = 45000, QUEUE_MAX = 200;
    static final int WRONG_MAX = 10, WRONG_WINDOW_MS = 10 * 60 * 1000;

    private static Host instance;
    private final Context ctx;
    private final SecureRandom random = new SecureRandom();
    private final String id = Long.toHexString(new SecureRandom().nextLong());

    private ServerSocket server;
    private DatagramSocket discovery;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean running;

    // the convoy (null token: none yet)
    private String token, name = "Convoy", password = "", key = "";
    private final Map<String, JSONObject> members = new LinkedHashMap<String, JSONObject>();
    private final List<Peer> peers = new ArrayList<Peer>();
    private final List<JSONObject> messages = new ArrayList<JSONObject>();
    private final LinkedHashMap<String, byte[]> voices = new LinkedHashMap<String, byte[]>();
    private final LinkedHashMap<String, Boolean> cids = new LinkedHashMap<String, Boolean>();
    private final Map<String, List<Long>> wrong = new HashMap<String, List<Long>>();

    static synchronized Host get(Context c) {
        if (instance == null) instance = new Host(c.getApplicationContext());
        return instance;
    }

    private Host(Context c) {
        ctx = c;
        try {
            JSONObject o = new JSONObject(Prefs.sp(c).getString("host_convoy", "{}"));
            if (!o.optString("token").isEmpty()) {
                token = o.optString("token");
                name = o.optString("name", "Convoy");
                password = o.optString("password");
                key = o.optString("key");
            }
        } catch (Exception e) { /* none kept */ }
    }

    // ---- running ----

    synchronized void start() {
        if (running) return;
        running = true;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(Nearby.PORT));
            discovery = new DatagramSocket(null);
            discovery.setReuseAddress(true);
            discovery.setBroadcast(true);
            discovery.bind(new InetSocketAddress(Nearby.PORT));
        } catch (IOException e) {
            android.util.Log.w("Host", "cannot listen on " + Nearby.PORT, e);
            stop();
            return;
        }
        WifiManager w = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (w != null) {
            wifiLock = w.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "coldshift convoy host");
            wifiLock.acquire();
        }
        final ServerSocket s = server;
        final DatagramSocket d = discovery;
        thread("host-accept", new Runnable() {
            public void run() { accept(s); }
        });
        thread("host-discovery", new Runnable() {
            public void run() { answer(d); }
        });
        android.util.Log.i("Host", "hosting on " + Nearby.PORT);
    }

    synchronized void stop() {
        running = false;
        if (server != null) try { server.close(); } catch (IOException e) { /* closed */ }
        if (discovery != null) discovery.close();
        server = null;
        discovery = null;
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
        for (Peer p : new ArrayList<Peer>(peers)) p.close();
    }

    boolean running() { return running; }

    /** The id in this host's discovery replies (a phone scanning leaves itself out). */
    String id() { return id; }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private void accept(ServerSocket s) {
        while (running && !s.isClosed()) {
            try {
                final Socket c = s.accept();
                thread("host-peer", new Runnable() {
                    public void run() { serve(c); }
                });
            } catch (IOException e) {
                if (running) android.util.Log.w("Host", "accept failed", e);
            }
        }
    }

    // ---- discovery: LocalNet's reply, with the convoy to join ----

    private void answer(DatagramSocket d) {
        byte[] buf = new byte[2048];
        while (running && !d.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                d.receive(p);
                JSONObject probe = new JSONObject(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                if (!"probe".equals(probe.optString("localnet")) || !Nearby.GAME.equals(probe.optString("game"))) continue;
                byte[] reply = advert(probe.optLong("sent")).toString().getBytes("UTF-8");
                d.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
            } catch (Exception e) {
                if (running && !(e instanceof org.json.JSONException)) android.util.Log.w("Host", "discovery", e);
            }
        }
    }

    private synchronized JSONObject advert(long sent) throws Exception {
        JSONObject o = new JSONObject();
        o.put("localnet", "host");
        o.put("game", Nearby.GAME);
        o.put("v", Nearby.VERSION);
        o.put("sent", sent);
        o.put("id", id);
        o.put("host", hostName());
        o.put("name", token != null ? name : hostName());
        o.put("port", Nearby.PORT);
        o.put("players", online());
        o.put("max", MAX_MEMBERS);
        o.put("state", "lobby");
        o.put("code", token != null && !password.isEmpty());
        JSONArray cs = new JSONArray();
        if (token != null) {
            JSONObject c = new JSONObject();
            c.put("name", name);
            c.put("token", token);
            c.put("locked", !password.isEmpty());
            c.put("cars", online());
            cs.put(c);
        }
        o.put("convoys", cs);
        return o;
    }

    /** The name the others see this host by: the vehicle's. */
    private String hostName() { return Convoy.get(ctx).carName(); }

    private int online() {
        int n = 0;
        for (JSONObject m : members.values()) if (m.optBoolean("online") && "vehicle".equals(m.optString("role"))) n++;
        return n;
    }

    // ---- HTTP ----

    private void serve(Socket c) {
        try {
            c.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();
            String[] request = line(in).split(" ");
            if (request.length < 2) { c.close(); return; }
            Map<String, String> headers = new HashMap<String, String>();
            for (String h; !(h = line(in)).isEmpty(); ) {
                int k = h.indexOf(':');
                if (k > 0) headers.put(h.substring(0, k).trim().toLowerCase(java.util.Locale.US), h.substring(k + 1).trim());
            }
            String method = request[0], path = request[1].replaceAll("\\?.*$", "");
            if ("websocket".equalsIgnoreCase(headers.get("upgrade")) && path.equals("/ws")) {
                upgrade(c, in, out, headers.get("sec-websocket-key"));
                return;
            }
            byte[] body = new byte[0];
            int len = 0;
            try { len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0"); }
            catch (NumberFormatException e) { /* none */ }
            if (len > 0 && len < 65536) {
                body = new byte[len];
                read(in, body);
            }
            route(method, path, new String(body, "UTF-8"), out);
            c.close();
        } catch (Exception e) {
            try { c.close(); } catch (IOException x) { /* closed */ }
        }
    }

    private void route(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/health")) {
            respond(out, 200, "text/plain", "ok".getBytes("UTF-8"));
        } else if (method.equals("POST") && path.equals("/api/convoys")) {
            JSONObject b = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            String k = create(b.optString("name", "Convoy"), b.optString("password", ""));
            JSONObject r = new JSONObject();
            r.put("token", token);
            r.put("name", name);
            r.put("link", "");
            r.put("password", !password.isEmpty());
            r.put("key", k);
            json(out, 200, r);
        } else if (method.equals("POST") && path.matches("^/api/convoys/[^/]+/end$")) {
            String t = path.split("/")[3];
            JSONObject b = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            if (token == null || !token.equals(t)) json(out, 404, error("this convoy has ended already"));
            else if (!same(key, b.optString("key"))) json(out, 403, error("only the convoy's starter can end it"));
            else {
                end("the convoy was ended by its starter");
                json(out, 200, new JSONObject().put("ended", true));
            }
        } else if (path.matches("^/c/[^/]+/voice/[^/]+\\.m4a$")) {
            String[] p = path.split("/");
            byte[] v;
            synchronized (this) { v = p[2].equals(token) ? voices.get(p[4].replace(".m4a", "")) : null; }
            if (v == null) respond(out, 404, "text/plain", "that voice note is gone".getBytes("UTF-8"));
            else respond(out, 200, "audio/mp4", v);
        } else if (path.matches("^/c/[^/]+/?$")) {
            respond(out, 200, "text/html; charset=utf-8", ("<!doctype html><meta name=viewport content='width=device-width'>"
                + "<body style='font-family:sans-serif;background:#070e1b;color:#e8eef7;padding:24px'><h2>A convoy on a phone</h2>"
                + "<p>Join it from coldshift.dash: Convoy, then Convoys nearby, on the same Wi-Fi or this phone's hotspot.</p>")
                .getBytes("UTF-8"));
        } else {
            respond(out, 404, "text/plain", "not here".getBytes("UTF-8"));
        }
    }

    private static JSONObject error(String e) throws Exception { return new JSONObject().put("error", e); }

    private static void json(OutputStream out, int status, JSONObject o) throws IOException {
        respond(out, status, "application/json", o.toString().getBytes("UTF-8"));
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : status == 403 ? "Forbidden" : "Error";
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
            + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    // ---- the convoy ----

    /** A new convoy (any before it ends); returns the starter's key. */
    private String create(String n, String pw) {
        if (token != null) end("the convoy was ended by its starter");
        String k = randomToken();
        synchronized (this) {
            token = randomToken();
            name = n.trim().isEmpty() ? "Convoy" : n.trim().substring(0, Math.min(60, n.trim().length()));
            password = pw.length() > 200 ? pw.substring(0, 200) : pw;
            key = k;
            members.clear();
            messages.clear();
            voices.clear();
            cids.clear();
            save();
        }
        return k;
    }

    /** Ends the convoy: everyone is told, and it is forgotten. */
    void end(String why) {
        List<Peer> ps;
        synchronized (this) {
            token = null;
            members.clear();
            messages.clear();
            voices.clear();
            ps = new ArrayList<Peer>(peers);
            save();
        }
        for (Peer p : ps) {
            try { p.send(new JSONObject().put("t", "ended").put("error", why).toString()); } catch (Exception e) { /* cannot */ }
            p.closeAfterSending();
        }
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            if (token != null) {
                o.put("token", token);
                o.put("name", name);
                o.put("password", password);
                o.put("key", key);
            }
            Prefs.sp(ctx).edit().putString("host_convoy", o.toString()).apply();
        } catch (Exception e) { /* cannot happen */ }
    }

    private String randomToken() {
        byte[] b = new byte[18];
        random.nextBytes(b);
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP
            | android.util.Base64.NO_PADDING);
    }

    private static boolean same(String a, String b) {
        try {
            return MessageDigest.isEqual(a.getBytes("UTF-8"), (b == null ? "" : b).getBytes("UTF-8"));
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized boolean blocked(String ip) {
        List<Long> times = wrong.get(ip);
        if (times == null) return false;
        long cut = System.currentTimeMillis() - WRONG_WINDOW_MS;
        while (!times.isEmpty() && times.get(0) < cut) times.remove(0);
        return times.size() >= WRONG_MAX;
    }

    private synchronized void wrongPassword(String ip) {
        List<Long> times = wrong.get(ip);
        if (times == null) wrong.put(ip, times = new ArrayList<Long>());
        times.add(System.currentTimeMillis());
    }

    private static JSONObject pub(JSONObject m) throws Exception {
        JSONObject o = new JSONObject();
        for (String k : new String[] {"id", "name", "colour", "role", "online", "pos", "car"}) o.put(k, m.opt(k));
        return o;
    }

    private void sendAll(JSONObject o, Peer but) {
        String text = o.toString();
        List<Peer> ps;
        synchronized (this) { ps = new ArrayList<Peer>(peers); }
        for (Peer p : ps) if (p != but && p.member != null) p.send(text);
    }

    /** Sends to the connections of two members (a private message: its sender and its recipient, which may be the same). */
    private void sendTo(JSONObject o, String a, String b) {
        String text = o.toString();
        List<Peer> ps;
        synchronized (this) { ps = new ArrayList<Peer>(peers); }
        for (Peer p : ps) if (p.member != null && (p.member.equals(a) || p.member.equals(b))) p.send(text);
    }

    /** Whether a message in the history is for this member: public, or private and theirs (to or from). */
    private static boolean visible(JSONObject m, String member) {
        String to = m.optString("to", "");
        return to.isEmpty() || to.equals(member) || member.equals(m.optString("from"));
    }

    /** One message from a member's connection (after its hello). */
    private void message(Peer p, JSONObject d) throws Exception {
        String t = d.optString("t");
        JSONObject me;
        synchronized (this) { me = members.get(p.member); }
        if (me == null) return;
        if (t.equals("pos")) {
            double lat = d.optDouble("lat", Double.NaN), lon = d.optDouble("lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
            JSONObject pos = new JSONObject();
            pos.put("lat", lat);
            pos.put("lon", lon);
            pos.put("speed", d.optDouble("speed", 0));
            pos.put("bearing", d.optDouble("bearing", 0));
            pos.put("time", d.optLong("time", System.currentTimeMillis()));
            synchronized (this) { me.put("pos", pos); }
            JSONObject out = new JSONObject(pos.toString());
            out.put("t", "pos");
            out.put("member", p.member);
            sendAll(out, p);
        } else if (t.equals("msg") || t.equals("voice")) {
            String cid = d.optString("cid", "");
            if (cid.length() > 40) cid = cid.substring(0, 40);
            synchronized (this) {
                if (!cid.isEmpty() && cids.containsKey(p.member + "\n" + cid)) {
                    p.send(new JSONObject().put("t", "ack").put("cid", cid).toString());   // a resend of one it has
                    return;
                }
            }
            JSONObject m = null;
            if (t.equals("msg")) {
                String text = d.optString("text", "").trim();
                if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
                if (!text.isEmpty()) m = new JSONObject().put("t", "msg").put("id", randomToken().substring(0, 12)).put("text", text);
            } else {
                int ms = d.optInt("ms", 0);
                byte[] sound = voiceData(d.optString("data", ""));
                if (sound != null && ms > 0 && ms <= MAX_VOICE_MS) {
                    String vid = randomToken().substring(0, 16);
                    synchronized (this) {
                        voices.put(vid, sound);
                        while (voices.size() > KEEP_VOICES) voices.remove(voices.keySet().iterator().next());
                    }
                    m = new JSONObject().put("t", "voice").put("id", vid).put("ms", ms).put("path", "/c/" + token + "/voice/" + vid + ".m4a");
                }
            }
            if (m == null) {
                if (!cid.isEmpty()) p.send(new JSONObject().put("t", "ack").put("cid", cid).toString());
                return;
            }
            long age = Math.max(0, Math.min(MAX_AGE_MS, d.optLong("age", 0)));
            m.put("time", System.currentTimeMillis() - age);
            m.put("from", p.member);
            m.put("name", me.optString("name"));
            if (age >= LATE_MS) m.put("late", age / 1000);
            String to = d.optString("to", "");             // private: to one member, not the convoy
            if (to.equals(p.member)) to = "";
            synchronized (this) {
                if (!to.isEmpty()) {
                    JSONObject r = members.get(to);
                    m.put("to", to);
                    m.put("to_name", r != null ? r.optString("name") : "");
                    if (r == null) to = p.member;          // no such member: back to its sender only
                }
                if (!cid.isEmpty()) {
                    m.put("cid", cid);
                    cids.put(p.member + "\n" + cid, true);
                    while (cids.size() > KEEP_CIDS) cids.remove(cids.keySet().iterator().next());
                }
                messages.add(m);
                while (messages.size() > KEEP_MESSAGES) messages.remove(0);
            }
            if (to.isEmpty()) sendAll(m, null);
            else sendTo(m, p.member, to);
        } else if (t.equals("leave")) {
            synchronized (this) { members.remove(p.member); }
            p.close();
        }
    }

    /** A voice note's sound from base64, or null: an MPEG-4 file (its "ftyp" box first) no bigger than MAX_VOICE. */
    private static byte[] voiceData(String b64) {
        if (b64.isEmpty() || b64.length() > MAX_VOICE * 4 / 3 + 8) return null;
        try {
            byte[] b = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            return b.length >= 12 && b.length <= MAX_VOICE && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p' ? b : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The hello: the convoy, its password, room for one more; then the welcome. */
    private void hello(Peer p, JSONObject d) throws Exception {
        String t = d.optString("token"), mid = d.optString("member");
        if (!mid.matches("^[A-Za-z0-9_-]{6,64}$")) { p.fail("that is not a convoy link", "bad"); return; }
        String err = null, code = null;
        synchronized (this) {
            if (token == null || !token.equals(t)) { err = "this convoy has ended (or the link is wrong)"; code = "ended"; }
            else if (blocked(p.ip)) { err = "too many wrong passwords from here: wait a few minutes"; code = "wait"; }
            else if (!password.isEmpty() && !same(password, d.optString("password"))) {
                if (!d.optString("password").isEmpty()) wrongPassword(p.ip);
                err = d.optString("password").isEmpty() ? "this convoy needs its password" : "wrong password";
                code = "password";
            } else if (!members.containsKey(mid) && members.size() >= MAX_MEMBERS) { err = "the convoy is full"; code = "full"; }
        }
        if (err != null) { p.fail(err, code); return; }
        JSONObject welcome = new JSONObject();
        JSONObject me = new JSONObject();
        synchronized (this) {
            JSONObject prev = members.get(mid);
            String car = d.optString("car", "");
            String n = d.optString("name", "Car");
            me.put("id", mid);
            me.put("name", n.length() > 40 ? n.substring(0, 40) : n);
            me.put("colour", d.optString("colour", ""));
            me.put("car", car.length() <= MAX_CAR ? car : "");
            me.put("role", "watcher".equals(d.optString("role")) ? "watcher" : "vehicle");
            me.put("online", true);
            if (prev != null && prev.has("pos")) me.put("pos", prev.get("pos"));
            members.put(mid, me);
            p.member = mid;
            welcome.put("t", "welcome");
            welcome.put("name", name);
            welcome.put("you", mid);
            welcome.put("link", "");
            welcome.put("locked", !password.isEmpty());
            JSONArray ms = new JSONArray();
            for (JSONObject m : members.values()) ms.put(pub(m));
            welcome.put("members", ms);
            JSONArray hist = new JSONArray();
            for (JSONObject m : messages) if (visible(m, mid)) hist.put(m);
            welcome.put("messages", new JSONArray(hist.toString()));
        }
        p.send(welcome.toString());
        sendAll(new JSONObject().put("t", "member").put("member", pub(me)), p);
    }

    /** A connection closed: its member shows offline (unless connected again on another). */
    private void gone(Peer p) {
        boolean tell = false;
        synchronized (this) {
            peers.remove(p);
            if (p.member != null) {
                boolean still = false;
                for (Peer o : peers) if (p.member.equals(o.member)) still = true;
                JSONObject m = members.get(p.member);
                if (m != null && !still) {
                    try { m.put("online", false); } catch (Exception e) { /* cannot happen */ }
                }
                tell = true;
            }
        }
        if (tell) {
            try { sendAll(new JSONObject().put("t", "left").put("member", p.member), p); } catch (Exception e) { /* cannot */ }
        }
    }

    // ---- WebSocket (RFC 6455, the server's side: frames from clients are masked, ours are not) ----

    private void upgrade(Socket c, InputStream in, OutputStream out, String wsKey) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        String accept = android.util.Base64.encodeToString(sha.digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
            .getBytes("UTF-8")), android.util.Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
            + accept + "\r\n\r\n").getBytes("UTF-8"));
        out.flush();
        c.setSoTimeout(READ_MS);                       // the phones ping every 10 s
        Peer p = new Peer(c, out);
        synchronized (this) { peers.add(p); }
        try {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (running) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) break;
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                if (len > MAX_FRAME || msg.size() + len > MAX_FRAME) break;
                byte[] mask = new byte[4];
                if ((b1 & 0x80) != 0) read(in, mask);
                byte[] data = new byte[(int) len];
                read(in, data);
                if ((b1 & 0x80) != 0) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
                if (op == 0x8) break;
                if (op == 0x9) { p.frame(0xA, data); continue; }
                if (op != 0x1 && op != 0x0) continue;
                msg.write(data);
                if ((b0 & 0x80) == 0) continue;
                String text = new String(msg.toByteArray(), "UTF-8");
                msg.reset();
                JSONObject d;
                try { d = new JSONObject(text); } catch (Exception e) { continue; }
                if ("ping".equals(d.optString("t"))) { p.send("{\"t\":\"pong\"}"); continue; }   // at any time
                if (p.member == null) {
                    if (!"hello".equals(d.optString("t"))) { p.fail("say hello first", "bad"); break; }
                    hello(p, d);
                    if (p.member == null) break;       // turned away
                } else {
                    message(p, d);
                    if (p.closed) break;
                }
            }
        } catch (IOException e) {
            /* the connection ended (or went quiet for READ_MS) */
        } finally {
            if (!p.closing) p.close();                 // else its writer closes once the last message is out
            gone(p);
        }
    }

    /** A connection: what is sent to it waits in a queue for its own writer thread. */
    private final class Peer {
        final Socket socket;
        final OutputStream out;
        final String ip;
        final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<byte[]>();
        volatile String member;
        volatile boolean closed, closing;

        Peer(Socket s, OutputStream o) {
            socket = s;
            out = o;
            ip = s.getInetAddress().getHostAddress();
            thread("host-send", new Runnable() {
                public void run() { write(); }
            });
        }

        void send(String text) {
            try {
                frame(0x1, text.getBytes("UTF-8"));
            } catch (IOException e) { /* closing */ }
        }

        void frame(int op, byte[] data) throws IOException {
            if (closed) return;
            ByteArrayOutputStream f = new ByteArrayOutputStream(data.length + 10);
            f.write(0x80 | op);
            if (data.length < 126) {
                f.write(data.length);
            } else if (data.length < 65536) {
                f.write(126);
                f.write(data.length >> 8);
                f.write(data.length);
            } else {
                f.write(127);
                for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : (data.length >>> (8 * i)) & 0xff);
            }
            f.write(data);
            if (queue.size() >= QUEUE_MAX) {           // not reading: let it go
                close();
                return;
            }
            queue.add(f.toByteArray());
        }

        void fail(String error, String code) {
            try { send(new JSONObject().put("t", "error").put("error", error).put("code", code).toString()); }
            catch (Exception e) { /* cannot happen */ }
            closeAfterSending();
        }

        void closeAfterSending() {
            closing = true;
            queue.add(new byte[0]);                     // the writer closes when it reaches this
        }

        private void write() {
            try {
                while (!closed) {
                    byte[] b = queue.take();
                    if (b.length == 0 && closing) {
                        out.write(new byte[] {(byte) 0x88, 0});    // close
                        out.flush();
                        break;
                    }
                    out.write(b);
                    out.flush();
                }
            } catch (Exception e) {
                /* gone */
            }
            close();
        }

        void close() {
            if (closed) return;
            closed = true;
            queue.add(new byte[0]);
            try { socket.close(); } catch (IOException e) { /* closed */ }
        }
    }

    // ---- reading ----

    private static void read(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) throw new IOException("closed");
            n += r;
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder s = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new IOException("closed");
            if (c != '\r') s.append((char) c);
            if (s.length() > 8192) throw new IOException("too long");
        }
        return s.toString();
    }
}

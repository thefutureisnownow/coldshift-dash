package racing.coldshift.dash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Driving together: the cars in a convoy see each other on the map and send each other messages,
 * through the convoy server (the Convoy relay, github.com/thefutureisnownow/coldshift-dash-convoy). Start one (with a password, best), or join one
 * from its link: scanned from its QR code (the link carries its server), or put on the card by the
 * desktop tool; a convoy with a password needs it too.
 *
 * The convoy host is anyone's: a relay of one's own (on Cloudflare, a laptop on the Wi-Fi), or this phone
 * (Host). There is none until one is chosen. It is set on the Convoy page (kept and backed up with the
 * settings) or by the desktop, which writes <volume>/coldshift.dash/convoy.json: {"server": "https://host",
 * "start_key"?: the host's start key, "name": the vehicle's name, "join"?: {"token", "name"}, "sent": when};
 * whichever changed it last wins. A host may need a start key to start a convoy (never to join one): it is
 * asked for when the host refuses without it, and kept per host, never built into the app. The convoy joined and this phone's member id are kept in
 * the preferences, so a restart rejoins. The connection runs on a thread of its own and comes back by
 * itself (mobile data or a phone's hotspot, whichever Android has); listeners hear on the UI thread.
 * While in a convoy this car's position goes out every 3 s while moving (15 s standing): only the latest
 * matters, so none is kept while disconnected. What is said (messages, voice notes) is kept in an outbox
 * until the server sends it back, and sent again after a reconnect (the server drops one it has had), for
 * up to OUTBOX_MS; it shows as not sent yet meanwhile. A host nearby that stops answering is looked for
 * again (it may have moved to another network, so another address) and followed. A ping every 10 s (a
 * WebSocket ping, and {"t":"ping"}, which a relay counts as a sign of life) and 25 s without a word (Wire)
 * notice a connection gone quiet, out of signal, instead of sending into it for a minute. Voice notes
 * (push to talk: Talk records and plays them) go out whole over the connection and come back as a place on
 * the server to fetch their sound from.
 *
 * A message or voice note may go to one member instead of everyone, so as not to clutter the convoy's
 * messages (not for secrecy: the host sees it): {"t": "msg", "text", "to": <member id>}, and the same for
 * "voice". The host relays it to that member's connections and the sender's own, with "to" and "to_name"
 * (the member's name; empty when the host knows no such member, then it goes back to the sender alone), and
 * the welcome's history holds a private message only for its sender and recipient. Vehicles and watchers
 * can both send and receive them. Without "to" a message goes to everyone as before.
 */
final class Convoy implements Gps.Listener {
    interface Listener {
        void convoyChanged();
        /** A message from someone else. */
        void convoyMessage(Message m);
    }

    static final class Member {
        String id, name = "", colour = "", role = "vehicle";
        boolean online;
        Bitmap car;
        double lat = Double.NaN, lon, speed, bearing;
        long time;
    }

    /** A message, or a voice note (voice: where on the server its sound is, ms: how long). */
    static final class Message {
        String id, from, name, text, voice = "", cid = "";
        String to = "", toName = "";        // a private message: its recipient's id and name ("" for the whole convoy)
        long time;
        int ms;
        int late;                           // seconds it waited on its sender's phone for a connection (30 s or more)
        volatile File file;                 // a voice note's sound, once fetched (fetchVoice)
        boolean heard;                      // a voice note played here (or this phone's own, or from before joining)
        boolean pending;                    // said here, not yet on the server (the outbox)

        boolean isVoice() { return !voice.isEmpty(); }

        /** Whether it went to one member rather than the convoy (only its sender and recipient have it). */
        boolean isPrivate() { return !to.isEmpty(); }

        /** "Voice note · 7 s" for a voice note, else the text. */
        String says() { return isVoice() ? "Voice note · " + Math.max(1, Math.round(ms / 1000f)) + " s" : text; }
    }

    static final long SEND_MOVING_MS = 3000, SEND_STILL_MS = 15000;

    private static Convoy instance;
    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    // sockets may not be written on the UI thread (Android throws), so everything sent goes through here
    private final java.util.concurrent.ExecutorService sender = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final Map<String, Member> members = new LinkedHashMap<String, Member>();
    private final List<Message> messages = new ArrayList<Message>();
    /** This phone as the host (Host), for driving with no internet: the others find it nearby. */
    static final String LOCAL = "http://127.0.0.1:" + Nearby.PORT;
    private static final int RECENT_HOSTS = 5;
    private static final String PING = "{\"t\":\"ping\"}";

    private String carName = "", token = "", password = "", name = "", error = "", link = "";
    private String key = "";                // the starter's key (this phone started it): ends it for everyone
    // why the server turned this phone away ("password", "ended", "full"): no more tries until it changes
    private volatile String refused = "";
    private volatile int refusals;          // counts them, so the page asks for the password once per refusal
    private boolean wantsStartKey;          // the last start was refused for want of the host's start key
    private Bitmap qr;
    private final String me;
    private volatile Wire wire;
    private Thread thread;
    private boolean connected, listening;
    private long sentAt;
    private Location sentAt0;
    private int unread;
    private String carBase64;

    static synchronized Convoy get(Context c) {
        if (instance == null) instance = new Convoy(c.getApplicationContext());
        return instance;
    }

    private Convoy(Context c) {
        ctx = c;
        Tls.init(c);
        String id = Prefs.sp(c).getString("convoy_member", "");
        if (id.isEmpty()) {
            id = "car-" + Long.toString(Math.abs(new java.security.SecureRandom().nextLong()), 36);
            Prefs.sp(c).edit().putString("convoy_member", id).apply();
        }
        me = id;
        h.postDelayed(pinger, Wire.PING_MS);
        token = Prefs.sp(c).getString("convoy_token", "");
        name = Prefs.sp(c).getString("convoy_name", "");
        password = Prefs.sp(c).getString("convoy_password", "");
        link = Prefs.sp(c).getString("convoy_link", "");
        key = Prefs.sp(c).getString("convoy_key", "");
        if (hosting()) Host.get(c).start();
        JSONObject join = readFile();                     // a sync made while the app was not running
        if (join != null) join(join.optString("token"), join.optString("name"), join.optString("password"));
        else if (!token.isEmpty()) run();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    private void changed() {
        h.post(new Runnable() {
            public void run() { for (Listener l : new ArrayList<Listener>(listeners)) l.convoyChanged(); }
        });
    }

    // ---- what the desktop put on the card ----

    /** Reads convoy.json; returns a convoy to join that the desktop sent and this phone has not joined yet. */
    private JSONObject readFile() {
        for (String v : Storage.volumes()) {
            File f = new File(v, Storage.DIR + "/convoy.json");
            if (!f.exists()) continue;
            try {
                FileInputStream in = new FileInputStream(f);
                byte[] b = new byte[(int) f.length()];
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
                in.close();
                JSONObject o = new JSONObject(new String(b, 0, n, "UTF-8"));
                // a sync not seen yet (by its time): its host, and its convoy to join; an older one may since
                // have been changed or left here, and stays so
                String host = o.optString("server", "").replaceAll("/+$", "");
                String sent = o.optString("sent", host);
                carName = o.optString("name", "");
                if (sent.equals(Prefs.sp(ctx).getString("convoy_file_sent", ""))) return null;
                Prefs.sp(ctx).edit().putString("convoy_file_sent", sent).apply();
                if (!host.isEmpty()) remember(host);
                if (!host.isEmpty() && o.has("start_key")) setStartKey(host, o.optString("start_key", ""));
                JSONObject join = o.optJSONObject("join");
                if (join != null && !join.optString("token").equals(token)) return join;
            } catch (Exception e) {
                android.util.Log.w("Convoy", "cannot read " + f, e);
            }
            return null;
        }
        return null;
    }

    /** After a sync (Main's SYNCED "convoy"): a new server, or a convoy to join. */
    void reload() {
        String was = server();
        JSONObject join = readFile();
        if (join != null) {
            join(join.optString("token"), join.optString("name"), join.optString("password"));
        } else if (!was.equals(server()) && !token.isEmpty()) {
            restart();
        }
        changed();
    }

    // ---- state ----

    boolean configured() { return !server().isEmpty(); }
    String server() { return Prefs.sp(ctx).getString("convoy_server", ""); }

    /** The start key kept for `host` ("" for none). */
    String startKey(String host) {
        try {
            return new JSONObject(Prefs.sp(ctx).getString("convoy_start_keys", "{}")).optString(host, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Keeps (or with "", forgets) the start key for `host`; backed up with the settings. */
    void setStartKey(String host, String startKey) {
        try {
            JSONObject o = new JSONObject(Prefs.sp(ctx).getString("convoy_start_keys", "{}"));
            if (startKey.isEmpty()) o.remove(host);
            else o.put(host, startKey);
            Prefs.sp(ctx).edit().putString("convoy_start_keys", o.toString()).apply();
        } catch (Exception e) { /* cannot happen */ }
    }

    /** Whether the host turned the last start away for want of its start key (or a wrong one). */
    boolean wantsStartKey() { return wantsStartKey; }

    /** Whether this phone is the host (Host): the convoy runs here, with no internet needed. */
    boolean hosting() { return LOCAL.equals(server()); }

    /** Whether the host is on the local network (this phone, or one found nearby): joined by finding it, not a QR code. */
    boolean local() {
        String h = server().replaceFirst("^https?://", "").replaceFirst(":\\d+$", "");
        return hosting() || h.matches("^(10|127)\\..*") || h.matches("^192\\.168\\..*") || h.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    /** Hosts on this phone from now on (a convoy under way elsewhere is left). */
    void hostHere() { setServer(LOCAL); }

    /** Joins a convoy found nearby (Nearby): its host, then the convoy; its password is asked for if it has one. */
    void joinNearby(Nearby.Found f) {
        if (!f.host.url.equals(server())) remember(f.host.url);
        Host.get(ctx).stop();
        join(f.token, f.name, "");
    }

    /** Hosts used before, the latest first (for tapping instead of typing). */
    List<String> recentHosts() {
        List<String> out = new ArrayList<String>();
        for (String h : Prefs.sp(ctx).getString("convoy_hosts", "").split("\n")) if (!h.isEmpty()) out.add(h);
        return out;
    }

    private void remember(String host) {
        if (host.equals(LOCAL)) {                      // not one to offer as an address
            Prefs.sp(ctx).edit().putString("convoy_server", host).apply();
            return;
        }
        List<String> hosts = recentHosts();
        hosts.remove(host);
        hosts.add(0, host);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(hosts.size(), RECENT_HOSTS); i++) s.append(i > 0 ? "\n" : "").append(hosts.get(i));
        Prefs.sp(ctx).edit().putString("convoy_server", host).putString("convoy_hosts", s.toString()).apply();
    }

    /**
     * An address as typed, made whole: "192.168.0.10:8080" and "laptop:8080" get http://, a bare domain
     * ("convoy.example.com") https://; null when it cannot be an address.
     */
    static String normalise(String typed) {
        String s = typed.trim().replaceAll("/+$", "");
        if (s.isEmpty() || s.contains(" ")) return null;
        if (!s.matches("^https?://.*")) {
            boolean local = s.matches("^[0-9.]+(:\\d+)?$") || s.matches("^[^.]+(:\\d+)?$") || s.matches(".*:\\d+$");
            s = (local ? "http://" : "https://") + s;
        }
        return s.matches("^https?://[A-Za-z0-9.-]+(:\\d{1,5})?$") ? s : null;
    }

    /** Uses that host from now on (reconnecting a convoy under way); false when it is not an address. */
    boolean setServer(String typed) {
        String host = normalise(typed);
        if (host == null) return false;
        boolean changed = !host.equals(server());
        remember(host);
        if (hosting()) Host.get(ctx).start();
        else Host.get(ctx).stop();
        if (changed && !token.isEmpty()) restart();
        changed();
        return true;
    }

    /** Asks the host whether it is a convoy server. */
    void check(final Done d) {
        http("GET", "/health", null, new Reply() {
            public void reply(JSONObject o, String err) { d.done(err); }
        }, true);
    }
    boolean inConvoy() { return !token.isEmpty(); }
    boolean connected() { return connected; }
    /** The convoy's link (as its QR code holds it), once the server has said it. */
    String link() { return link; }
    boolean locked() { return !password.isEmpty(); }
    /** The convoy's password as this phone joined with it ("" for none), to tell the others. */
    String password() { return password; }
    /** Whether this phone started the convoy (and may end it for everyone). */
    boolean starter() { return !key.isEmpty(); }
    /** Why the server turned this phone away, or "" ("password", "ended", "full", "wait"). */
    String refused() { return refused; }
    /** Why this phone left a convoy without being asked (its starter ended it), once; "" otherwise. */
    String takeNote() {
        String n = lastNote;
        lastNote = "";
        return n;
    }

    private String lastNote = "";

    /** How many times the server has turned this phone away (a new one: ask for the password again). */
    int refusals() { return refusals; }

    /** The QR code of the convoy's link, from the server; null until fetched. */
    Bitmap qr() { return qr; }
    String name() { return name; }
    String error() { return error; }
    String me() { return me; }
    String token() { return token; }

    synchronized List<Member> members() { return new ArrayList<Member>(members.values()); }

    /** What was said, oldest first, then what this phone said that the server does not have yet. */
    synchronized List<Message> messages() {
        List<Message> out = new ArrayList<Message>(messages);
        for (Out x : outbox) out.add(x.shown);
        return out;
    }

    /** Whether a private message was sent to this phone. */
    boolean toMe(Message m) { return m.isPrivate() && me.equals(m.to); }

    /** Whether this phone sent it. */
    boolean fromMe(Message m) { return me.equals(m.from); }

    /** A member's name, or "" when the convoy has not heard of them. */
    synchronized String nameOf(String id) {
        Member m = members.get(id);
        return m != null ? m.name : "";
    }

    /** Who a private message went to: the name the host gave, else the member's, else "someone" (an unknown id). */
    String toName(Message m) {
        if (!m.toName.isEmpty()) return m.toName;
        String n = nameOf(m.to);
        return n.isEmpty() ? "someone" : n;
    }

    int unread() { return unread; }

    void read() {
        if (unread == 0) return;
        unread = 0;
        changed();
    }

    /** This car's name in the convoy: the desktop's name for the vehicle, else the profile's title. */
    String carName() {
        if (!carName.isEmpty()) return carName;
        String t = main != null ? Profile.title(main) : "";
        // no vehicle set up (the app's own name as the title): the phone's model, so two such differ
        if (t.trim().isEmpty() || t.trim().equalsIgnoreCase("coldshift.dash")) return android.os.Build.MODEL;
        StringBuilder out = new StringBuilder();
        for (String w : t.trim().toLowerCase(java.util.Locale.US).split("\\s+")) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.length() > 0 ? out.toString() : "Car";
    }

    /** The car picture 96 px long as PNG in base64, for the others' maps. */
    private String carPicture() {
        if (carBase64 != null) return carBase64;
        carBase64 = "";
        Bitmap body = main != null ? Profile.carBody(main) : null;
        if (body == null) return carBase64;
        int hgt = 96, w = Math.max(1, Math.round((float) hgt * body.getWidth() / body.getHeight()));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        Bitmap.createScaledBitmap(body, w, hgt, true).compress(Bitmap.CompressFormat.PNG, 100, b);
        carBase64 = android.util.Base64.encodeToString(b.toByteArray(), android.util.Base64.NO_WRAP);
        return carBase64;
    }

    private Main main;

    /** The activity, for the profile's name and car picture. */
    void attach(Main m) {
        main = m;
        carBase64 = null;
    }

    // ---- starting, joining, leaving ----

    interface Done {
        /** Null on success, else what went wrong. */
        void done(String error);
    }

    /** Starts a new convoy on the server, with a password (or "" for none). */
    void start(final String convoyName, final String pw, final Done d) {
        String sk = startKey(server());
        http("POST", "/api/convoys", "{\"name\":" + JSONObject.quote(convoyName) + ",\"password\":" + JSONObject.quote(pw)
            + (sk.isEmpty() ? "" : ",\"start_key\":" + JSONObject.quote(sk)) + "}", new Reply() {
            public void reply(JSONObject o, String err) {
                wantsStartKey = err != null && o != null && o.optString("code").equals("start_key");
                if (err != null) {
                    d.done(err);
                    return;
                }
                join(o.optString("token"), o.optString("name"), pw);
                key = o.optString("key");
                Prefs.sp(ctx).edit().putString("convoy_key", key).apply();
                d.done(null);
            }
        });
    }

    /** A convoy's link: {server, token}, or null when it is not one. */
    static String[] parseLink(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(https?://[A-Za-z0-9.-]+(?::\\d{1,5})?)/c/([A-Za-z0-9_-]{20,64})/?$")
            .matcher(s.trim());
        return m.matches() ? new String[] {m.group(1), m.group(2)} : null;
    }

    /** Joins the convoy a link (from a QR code) names, on its server, with its password ("" for none). */
    boolean joinLink(String s, String pw) {
        String[] l = parseLink(s);
        if (l == null) return false;
        if (!l[0].equals(server())) remember(l[0]);
        join(l[1], "", pw);
        return true;
    }

    /** Ends the convoy for everyone (only the phone that started it can). */
    void end(final Done d) {
        http("POST", "/api/convoys/" + token + "/end", "{\"key\":" + JSONObject.quote(key) + "}",
            new Reply() {
                public void reply(JSONObject o, String err) {
                    if (err == null || err.contains("ended already")) leaveQuietly();
                    changed();
                    d.done(err != null && !err.contains("ended already") ? err : null);
                }
            });
    }

    /** Tries again with another password (after the server said it was wrong). */
    void retry(String pw) {
        password = pw;
        Prefs.sp(ctx).edit().putString("convoy_password", pw).apply();
        refused = "";
        error = "";
        restart();
        changed();
    }

    private synchronized void join(String t, String n, String pw) {
        if (t.isEmpty()) return;
        if (!token.isEmpty() && !token.equals(t)) leaveQuietly();
        token = t;
        name = n;
        password = pw == null ? "" : pw;
        link = "";
        key = "";
        qr = null;
        error = "";
        refused = "";
        members.clear();
        messages.clear();
        outbox.clear();
        Prefs.sp(ctx).edit().putString("convoy_token", t).putString("convoy_name", n).putString("convoy_password", password)
            .remove("convoy_link").remove("convoy_key").apply();
        restart();
        changed();
    }

    void leave() {
        final Wire w = wire;
        if (w != null) {
            sender.execute(new Runnable() {
                public void run() {
                    w.send("{\"t\":\"leave\"}");
                    w.close();
                }
            });
        }
        leaveQuietly();
        changed();
    }

    /** Sends on the sender thread; false when not connected. A send that fails drops the connection (reconnect). */
    private boolean post(final String text) {
        final Wire w = wire;
        if (w == null || !connected) return false;
        sender.execute(new Runnable() {
            public void run() { if (!w.send(text)) w.close(); }
        });
        return true;
    }

    // ---- what this phone says: kept until the server has it ----

    static final long OUTBOX_MS = 10 * 60 * 1000;     // given up after this long without getting through
    private static final int OUTBOX_MAX = 30;

    /** Something said here, until the server sends it back (or answers "ack": it had it already). */
    private static final class Out {
        final JSONObject o;
        final Message shown;
        final long at = System.currentTimeMillis();

        Out(JSONObject o, Message shown) {
            this.o = o;
            this.shown = shown;
        }
    }

    private final List<Out> outbox = new ArrayList<Out>();
    private int said;

    /** Says it: into the outbox, and to the server now if connected. */
    private synchronized void say(JSONObject o, Message m) {
        String cid = Long.toString(System.currentTimeMillis(), 36) + "-" + said++;
        try { o.put("cid", cid); } catch (Exception e) { /* cannot happen */ }
        m.id = "out-" + cid;
        m.cid = cid;
        m.from = me;
        m.name = carName();
        m.time = System.currentTimeMillis();
        m.pending = true;
        m.heard = true;
        Out x = new Out(o, m);
        outbox.add(x);
        while (outbox.size() > OUTBOX_MAX) giveUp(outbox.get(0));
        sendOut(x);
        changed();
    }

    /** Sends one from the outbox, with how long it has waited (the server dates it back by that). */
    private void sendOut(Out x) {
        try {
            x.o.put("age", System.currentTimeMillis() - x.at);
            post(x.o.toString());
        } catch (Exception e) { /* cannot happen */ }
    }

    /** The server has it (`m`: as it came back, null for an "ack"): out of the outbox. */
    private void delivered(String cid, Message m) {
        if (cid.isEmpty()) return;
        for (int i = 0; i < outbox.size(); i++) {
            Out x = outbox.get(i);
            if (!x.shown.cid.equals(cid)) continue;
            if (m != null && x.shown.file != null) m.file = x.shown.file;    // its sound is here already
            outbox.remove(i);
            return;
        }
    }

    private void giveUp(Out x) {
        outbox.remove(x);
        error = "Not sent, no connection for " + (OUTBOX_MS / 60000) + " min: " + x.shown.says();
    }

    /** Every PING_MS: a ping (a quiet connection then times out), and the outbox's old ones given up. */
    private final Runnable pinger = new Runnable() {
        public void run() {
            synchronized (Convoy.this) {
                long cut = System.currentTimeMillis() - OUTBOX_MS;
                boolean gone = false;
                while (!outbox.isEmpty() && outbox.get(0).at < cut) {
                    giveUp(outbox.get(0));
                    gone = true;
                }
                if (gone) changed();
            }
            final Wire w = wire;
            if (w != null && connected) {
                sender.execute(new Runnable() {
                    // a relay answers WebSocket pings without the convoy hearing: {"t":"ping"} tells it this car is here
                    public void run() { if (!w.ping() || !w.send(PING)) w.close(); }
                });
            }
            h.postDelayed(this, Wire.PING_MS);
        }
    };

    private synchronized void leaveQuietly() {
        outbox.clear();
        token = "";
        password = "";
        link = "";
        key = "";
        qr = null;
        refused = "";
        name = "";
        members.clear();
        messages.clear();
        unread = 0;
        Talk.get(ctx).clear();
        Prefs.sp(ctx).edit().remove("convoy_token").remove("convoy_name").remove("convoy_password").remove("convoy_link").remove("convoy_key").apply();
        restart();
        listen(false);
    }

    /** Says it to the convoy: now, or when the connection is back (the outbox). */
    void send(String text) { send(text, ""); }

    /** Says it to one member (their id; "" for the whole convoy), as send(). */
    void send(String text, String to) {
        if (!inConvoy()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "msg");
            o.put("text", text);
            Message m = new Message();
            m.text = text;
            addressed(o, m, to);
            say(o, m);
        } catch (Exception e) { /* cannot happen */ }
    }

    /** Sends a voice note (its .m4a, kept at `file` for playing here, and how long it is), as send(). */
    void sendVoice(byte[] sound, int ms, File file) { sendVoice(sound, ms, file, ""); }

    /** Sends a voice note to one member (their id; "" for the whole convoy), as sendVoice(). */
    void sendVoice(byte[] sound, int ms, File file, String to) {
        if (!inConvoy()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "voice");
            o.put("ms", ms);
            o.put("data", android.util.Base64.encodeToString(sound, android.util.Base64.NO_WRAP));
            Message m = new Message();
            m.text = "";
            m.voice = "here";
            m.ms = ms;
            m.file = file;
            addressed(o, m, to);
            say(o, m);
        } catch (Exception e) { /* cannot happen */ }
    }

    /** Makes it private to `to` (the outbox keeps the "to", so a resend is private too), unless that is no one or this phone. */
    private void addressed(JSONObject o, Message m, String to) throws Exception {
        if (to == null || to.isEmpty() || to.equals(me)) return;
        o.put("to", to);
        m.to = to;
        m.toName = nameOf(to);
    }

    /** Fetches a voice note's sound into the cache (blocking: not on the UI thread); null when it cannot. */
    File fetchVoice(Message m) {
        if (m.file != null && m.file.exists()) return m.file;
        File dir = new File(ctx.getCacheDir(), "voice");
        dir.mkdirs();
        File f = new File(dir, m.id.replaceAll("[^A-Za-z0-9_-]", "") + ".m4a");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(server() + m.voice).openConnection();
            if (c instanceof javax.net.ssl.HttpsURLConnection)
                ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(Tls.factory());
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;     // gone: the server keeps the latest few
            InputStream in = c.getInputStream();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            out.close();
            in.close();
            m.file = f;
            return f;
        } catch (Exception e) {
            android.util.Log.w("Convoy", "cannot fetch the voice note " + m.voice, e);
            f.delete();
            return null;
        }
    }

    // ---- the connection ----

    private void restart() {
        final Wire w = wire;
        wire = null;
        if (w != null) {
            sender.execute(new Runnable() {
                public void run() { w.close(); }
            });
        }
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        if (!token.isEmpty()) run();
    }

    private synchronized void run() {
        if (thread != null || server().isEmpty() || token.isEmpty()) return;
        final String t = token;
        thread = new Thread(new Runnable() {
            public void run() { loop(t); }
        }, "convoy");
        thread.start();
        listen(true);
    }

    private void loop(String t) {
        long wait = 2000;
        int misses = 0;                         // tries in a row that never got in
        while (t.equals(token) && Thread.currentThread() == thread) {
            final Wire w = new Wire();
            wire = w;
            try {
                Nearby.routeOverWifi(ctx, server());     // a host on the local network: over Wi-Fi, not mobile data
                // ?c= lets a relay take the connection straight to its convoy (the Cloudflare one needs it)
                String url = server().replaceFirst("^http", "ws") + "/ws?c=" + t;
                w.connect(url, new Wire.Listener() {
                    public void opened() {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("t", "hello");
                            o.put("token", token);
                            o.put("password", password);
                            o.put("member", me);
                            o.put("name", carName());
                            o.put("name_convoy", name);
                            o.put("colour", String.format("#%06x", Theme.BLUE & 0xffffff));
                            o.put("car", carPicture());
                            o.put("role", "vehicle");
                            w.send(o.toString());
                        } catch (Exception e) { /* cannot happen */ }
                    }

                    public void text(String s) { if (w == wire) receive(s); }   // not an old connection's last words
                });
            } catch (Exception e) {
                android.util.Log.i("Convoy", "connection ended: " + e.getMessage());
            }
            if (connected) {
                connected = false;
                wait = 2000;
                misses = 0;
                changed();
            } else {
                misses++;
            }
            if (!t.equals(token)) break;
            // a host nearby that stopped answering may have moved (a new network, its hotspot: a new address):
            // look for the convoy again, as LocalNet does, and follow it
            if (misses >= 2 && refused.isEmpty() && local() && !hosting() && rediscover(t)) {
                misses = 0;
                wait = 2000;
                continue;
            }
            try {
                // turned away: a wrong password or an ended convoy waits for the user; too many tries waits a minute
                if (refused.equals("wait")) Thread.sleep(60000);
                else Thread.sleep(wait);
                while (!refused.isEmpty() && !refused.equals("wait") && t.equals(token)) Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
            wait = Math.min(wait * 2, 30000);
        }
    }

    /** Finds this convoy (by its token) on a host nearby at another address; true when it moved there. */
    private boolean rediscover(String t) {
        for (Nearby.Host h : Nearby.scan(ctx)) {
            for (Nearby.Found f : h.convoys) {
                if (!f.token.equals(t) || h.url.equals(server())) continue;
                android.util.Log.i("Convoy", "the convoy's host moved to " + h.url);
                remember(h.url);
                changed();
                return true;
            }
        }
        return false;
    }

    private void receive(String s) {
        final JSONObject o;
        try {
            o = new JSONObject(s);
        } catch (Exception e) {
            return;
        }
        if (o.optString("t").equals("error")) {
            refused = o.optString("code", "bad");      // before the loop tries again
            refusals++;
        } else if (o.optString("t").equals("ended")) {
            refused = "ended";                         // its starter ended it: no more tries
        }
        h.post(new Runnable() {
            public void run() { handle(o); }
        });
    }

    private synchronized void handle(JSONObject o) {
        String t = o.optString("t");
        if (t.equals("welcome")) {
            connected = true;
            error = "";
            refused = "";
            if (!o.optString("name").isEmpty()) name = o.optString("name");
            if (!o.optString("link").equals(link) || (qr == null && !local())) {
                link = o.optString("link");
                if (!local()) fetchQr(token);              // a local host is found nearby, not scanned
            }
            Prefs.sp(ctx).edit().putString("convoy_link", link).putString("convoy_name", name).apply();
            members.clear();
            JSONArray ms = o.optJSONArray("members");
            for (int i = 0; ms != null && i < ms.length(); i++) member(ms.optJSONObject(i));
            // those already here keep what was done with them (a voice note heard, its sound fetched); the rest
            // were said before this phone was here (or while it was away), and wait to be tapped
            Map<String, Message> had = new java.util.HashMap<String, Message>();
            for (Message m : messages) had.put(m.id, m);
            messages.clear();
            JSONArray msgs = o.optJSONArray("messages");
            for (int i = 0; msgs != null && i < msgs.length(); i++) {
                Message m = message(msgs.optJSONObject(i));
                Message old = had.get(m.id);
                if (old == null) m.heard = had.isEmpty() || me.equals(m.from);
                messages.add(old != null ? old : m);
                if (me.equals(m.from)) delivered(m.cid, m);          // it got there before the connection went
            }
            for (Out x : new ArrayList<Out>(outbox)) sendOut(x);     // the rest again (the server drops repeats)
            sentAt = 0;
            sendPosition(Gps.get(ctx).last(), true);
        } else if (t.equals("member")) {
            member(o.optJSONObject("member"));
        } else if (t.equals("pos")) {
            Member m = members.get(o.optString("member"));
            if (m != null) {
                position(m, o);
                m.online = true;
            }
        } else if (t.equals("left")) {
            Member m = members.get(o.optString("member"));
            if (m != null) m.online = false;
        } else if (t.equals("msg") || t.equals("voice")) {
            final Message m = message(o);
            m.heard = me.equals(m.from);
            if (me.equals(m.from)) delivered(m.cid, m);
            messages.add(m);
            while (messages.size() > 100) messages.remove(0);
            if (!me.equals(m.from)) {
                unread++;
                for (Listener l : new ArrayList<Listener>(listeners)) l.convoyMessage(m);
                if (m.isVoice()) Talk.get(ctx).arrived(m);
            }
        } else if (t.equals("ack")) {
            delivered(o.optString("cid"), null);
        } else if (t.equals("ended")) {
            // its starter ended it: out of it, with a note of why on the Convoy page
            String why = o.optString("error");
            leaveQuietly();
            lastNote = why;
        } else if (t.equals("error")) {
            error = o.optString("error");
        }
        for (Listener l : new ArrayList<Listener>(listeners)) l.convoyChanged();
    }

    /** The QR code of the convoy's link, drawn by the server (/c/<token>/qr.png). */
    private void fetchQr(final String t) {
        final String url = server() + "/c/" + t + "/qr.png?scale=8";
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    if (c instanceof javax.net.ssl.HttpsURLConnection)
                        ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(Tls.factory());
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    InputStream in = c.getInputStream();
                    final Bitmap b = BitmapFactory.decodeStream(in);
                    in.close();
                    h.post(new Runnable() {
                        public void run() {
                            if (t.equals(token)) {
                                qr = b;
                                changed();
                            }
                        }
                    });
                } catch (Exception e) {
                    android.util.Log.w("Convoy", "no QR code from " + url, e);
                }
            }
        }).start();
    }

    private void member(JSONObject o) {
        if (o == null) return;
        Member m = members.get(o.optString("id"));
        if (m == null) {
            m = new Member();
            m.id = o.optString("id");
            members.put(m.id, m);
        }
        m.name = o.optString("name");
        m.colour = o.optString("colour");
        m.role = o.optString("role", "vehicle");
        m.online = o.optBoolean("online");
        String car = o.optString("car");
        if (!car.isEmpty() && m.car == null) {
            try {
                byte[] b = android.util.Base64.decode(car, android.util.Base64.DEFAULT);
                m.car = BitmapFactory.decodeByteArray(b, 0, b.length);
            } catch (Exception e) { /* no picture */ }
        }
        JSONObject p = o.optJSONObject("pos");
        if (p != null) position(m, p);
    }

    private static void position(Member m, JSONObject p) {
        m.lat = p.optDouble("lat", Double.NaN);
        m.lon = p.optDouble("lon", 0);
        m.speed = p.optDouble("speed", 0);
        m.bearing = p.optDouble("bearing", 0);
        m.time = p.optLong("time", System.currentTimeMillis());
    }

    private static Message message(JSONObject o) {
        Message m = new Message();
        m.id = o.optString("id");
        m.from = o.optString("from");
        m.name = o.optString("name");
        m.text = o.optString("text");
        m.time = o.optLong("time");
        m.cid = o.optString("cid");
        m.late = o.optInt("late");
        m.to = o.optString("to");
        m.toName = o.optString("to_name");
        if (o.optString("t").equals("voice")) {
            m.voice = o.optString("path");
            m.ms = o.optInt("ms");
        }
        return m;
    }

    // ---- this car's position ----

    private void listen(final boolean on) {
        h.post(new Runnable() {
            public void run() {
                if (on && !listening) Gps.get(ctx).add(Convoy.this);
                if (!on && listening) Gps.get(ctx).remove(Convoy.this);
                listening = on;
            }
        });
    }

    public void onFix(Location l) { sendPosition(l, false); }

    private void sendPosition(Location l, boolean now) {
        if (l == null || wire == null || !connected) return;
        long t = System.currentTimeMillis();
        boolean moving = l.getSpeed() > 1.5f;
        if (!now && t - sentAt < (moving ? SEND_MOVING_MS : SEND_STILL_MS)) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "pos");
            o.put("lat", Math.round(l.getLatitude() * 1e6) / 1e6);
            o.put("lon", Math.round(l.getLongitude() * 1e6) / 1e6);
            o.put("speed", Math.round(l.getSpeed() * 10) / 10.0);
            o.put("bearing", Math.round(l.getBearing()));
            o.put("time", t);
            if (post(o.toString())) {
                sentAt = t;
                sentAt0 = l;
            }
        } catch (Exception e) { /* cannot happen */ }
    }

    Location sent() { return sentAt0; }

    // ---- HTTP to the server ----

    private interface Reply {
        void reply(JSONObject o, String error);
    }

    private void http(String method, String path, String body, Reply r) { http(method, path, body, r, false); }

    /** `text`: the reply is not JSON (/health says "ok"). */
    private void http(final String method, final String path, final String body, final Reply r, final boolean text) {
        final String server = server();
        if (server.isEmpty()) {
            r.reply(null, "No convoy host yet: choose one (Change host).");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                JSONObject o = null;
                String err = null;
                try {
                    Nearby.routeOverWifi(ctx, server);
                    HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
                    if (c instanceof javax.net.ssl.HttpsURLConnection)
                        ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(Tls.factory());
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setRequestMethod(method);
                    if (body != null) {
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/json");
                        OutputStream out = c.getOutputStream();
                        out.write(body.getBytes("UTF-8"));
                        out.close();
                    }
                    int status = c.getResponseCode();
                    InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int n; in != null && (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                    if (in != null) in.close();
                    if (text) {
                        o = new JSONObject();
                        if (status >= 400 || !b.toString("UTF-8").trim().equals("ok")) err = "That host is not a convoy server.";
                    } else {
                        o = new JSONObject(b.size() > 0 ? b.toString("UTF-8") : "{}");
                        if (status >= 400) err = o.optString("error", "the server said " + status);
                    }
                } catch (Exception e) {
                    android.util.Log.w("Convoy", "cannot reach " + server + path, e);
                    err = e instanceof javax.net.ssl.SSLException
                        ? "The certificate of " + server + " is not trusted (expired, or not for that name)."
                        : "Cannot reach " + server + " (" + e.getMessage() + ").";
                }
                final JSONObject fo = o;
                final String fe = err;
                h.post(new Runnable() {
                    public void run() { r.reply(fo, fe); }
                });
            }
        }).start();
    }
}

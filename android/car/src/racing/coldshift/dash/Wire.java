package racing.coldshift.dash;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * A small WebSocket client (RFC 6455), enough for the convoy server: text messages both ways, pings
 * answered, closing. Android 4.4 has none built in. One connection per object: connect() blocks while
 * it reads (run it on a thread of its own) and returns when the connection ends; send() may be called
 * from any thread. wss:// goes through Tls (TLS 1.2, SNI, Let's Encrypt's roots, the name checked).
 */
final class Wire {
    interface Listener {
        void opened();
        void text(String s);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    // the server pings every 20 s and the client every PING_MS (ping()): READ_MS without a word is a
    // connection gone quiet (out of signal), given up so the convoy reconnects instead of sending into it
    static final int PING_MS = 10000;
    private static final int CONNECT_MS = 10000, READ_MS = 25000;
    private final SecureRandom random = new SecureRandom();
    private Socket socket;
    private OutputStream out;
    private volatile boolean closed;

    /** Connects, says hello through the listener, and reads until the connection ends (then throws). */
    void connect(String url, Listener l) throws IOException {
        URI u = URI.create(url);
        boolean tls = "wss".equals(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        Socket s = new Socket();
        s.connect(new InetSocketAddress(u.getHost(), port), CONNECT_MS);
        if (tls) s = Tls.wrap(s, u.getHost(), port);
        s.setSoTimeout(READ_MS);
        synchronized (this) {
            socket = s;
            out = s.getOutputStream();
            if (closed) throw new IOException("closed");
        }
        InputStream in = new BufferedInputStream(s.getInputStream());
        byte[] k = new byte[16];
        random.nextBytes(k);
        String key = android.util.Base64.encodeToString(k, android.util.Base64.NO_WRAP);
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
            + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        write(("GET " + path + " HTTP/1.1\r\nHost: " + u.getHost() + (u.getPort() > 0 ? ":" + port : "")
            + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key
            + "\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes("UTF-8"));
        String status = line(in);
        if (!status.startsWith("HTTP/1.1 101")) throw new IOException("the server said " + status);
        String accept = null;
        for (String h; !(h = line(in)).isEmpty(); ) {
            int c = h.indexOf(':');
            if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) accept = h.substring(c + 1).trim();
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("no SHA-1");
        }
        String want = android.util.Base64.encodeToString(sha.digest((key + GUID).getBytes("UTF-8")), android.util.Base64.NO_WRAP);
        if (!want.equals(accept)) throw new IOException("not a WebSocket server");
        l.opened();
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) throw new IOException("the server closed the connection");
            int op = b0 & 0x0f;
            long len = b1 & 0x7f;
            if (len == 126) len = (in.read() << 8) | in.read();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            if (len > 4 * 1024 * 1024) throw new IOException("a message too big");
            byte[] mask = null;
            if ((b1 & 0x80) != 0) {
                mask = new byte[4];
                read(in, mask);
            }
            byte[] data = new byte[(int) len];
            read(in, data);
            if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            if (op == 0x9) {
                frame(0xA, data);                          // ping: pong with the same data
            } else if (op == 0x8) {
                try { frame(0x8, new byte[0]); } catch (IOException e) { /* closing anyway */ }
                throw new IOException("the server closed the connection");
            } else if (op == 0x1 || op == 0x0) {
                message.write(data);
                if ((b0 & 0x80) != 0) {
                    l.text(new String(message.toByteArray(), "UTF-8"));
                    message.reset();
                }
            }
        }
    }

    /** False when not connected (the message is dropped). */
    boolean send(String text) {
        try {
            frame(0x1, text.getBytes("UTF-8"));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** A ping (the server answers with a pong, which keeps the read alive); false when not connected. */
    boolean ping() {
        try {
            frame(0x9, new byte[0]);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    void close() {
        Socket s;
        synchronized (this) {
            closed = true;
            s = socket;
        }
        if (s == null) return;
        try { frame(0x8, new byte[0]); } catch (IOException e) { /* closing anyway */ }
        try { s.close(); } catch (IOException e) { /* closed */ }
    }

    /** A frame from the client: always masked. */
    private void frame(int op, byte[] data) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(data.length + 14);
        f.write(0x80 | op);
        if (data.length < 126) {
            f.write(0x80 | data.length);
        } else if (data.length < 65536) {
            f.write(0x80 | 126);
            f.write(data.length >> 8);
            f.write(data.length);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : (data.length >>> (8 * i)) & 0xff);
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i % 4]);
        write(f.toByteArray());
    }

    private synchronized void write(byte[] b) throws IOException {
        if (out == null) throw new IOException("not connected");
        out.write(b);
        out.flush();
    }

    private static void read(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) throw new IOException("the server closed the connection");
            n += r;
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder s = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new IOException("the server closed the connection");
            if (c != '\r') s.append((char) c);
        }
        return s.toString();
    }
}

package racing.coldshift.dash;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A line link over TCP, with the semantics of BtLink: a Wi-Fi OBD adapter (they serve an ELM327 on a port,
 * usually 192.168.0.10:35000), or a simulator on a computer for trying the OBD page without a car. Open it
 * with open("host:port", listener); it connects on a thread of its own, and every listener call comes from
 * one background thread, in order. send() adds a carriage return; what comes in is cut into lines at
 * carriage returns, line feeds and the prompt ">", which is a line of its own; empty lines are dropped.
 * Nothing in the app points at a TCP device but the developer broadcast (Main.ENGINE).
 */
final class TcpLink implements Link {
    static final int CONNECT_MS = 8000;

    private final String host;
    private final int port;
    private final Listener listener;
    private final ExecutorService events = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<byte[]>();
    private final AtomicBoolean closed = new AtomicBoolean(), finished = new AtomicBoolean();
    private final StringBuilder rx = new StringBuilder();
    private final long started = System.currentTimeMillis();
    private volatile Socket socket;
    private volatile boolean open;
    private volatile long openMs;
    private boolean ended;                                   // onClosed has been called (the events thread only)
    private static final byte[] END = new byte[0];

    private TcpLink(String host, int port, Listener l) {
        this.host = host;
        this.port = port;
        listener = l;
    }

    /** "host:port" (port 35000 when missing). Starts connecting and returns at once. */
    static TcpLink open(String hostPort, Listener l) {
        String h = hostPort.trim();
        int port = 35000, colon = h.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(h.substring(colon + 1));
                h = h.substring(0, colon);
            } catch (NumberFormatException e) { /* the whole thing is the host */ }
        }
        final TcpLink link = new TcpLink(h, port, l);
        Thread t = new Thread(new Runnable() {
            public void run() { link.run(); }
        }, "tcp-link-" + h);
        t.setDaemon(true);
        t.start();
        return link;
    }

    public boolean isOpen() { return open; }
    public String how() { return "TCP " + host + ":" + port; }
    public long openMs() { return openMs; }

    public void send(String line) {
        if (!closed.get()) out.offer((line + "\r").getBytes(java.nio.charset.Charset.forName("ISO-8859-1")));
    }

    public void close() {
        closed.set(true);
        out.offer(END);
        finish(null);
    }

    private void run() {
        Socket s = new Socket();
        try {
            socket = s;
            s.connect(new InetSocketAddress(host, port), CONNECT_MS);
            s.setTcpNoDelay(true);
            if (closed.get()) return;
            openMs = System.currentTimeMillis() - started;
            open = true;
            startReader(s);
            post(new Runnable() {
                public void run() { listener.onOpen(TcpLink.this); }
            });
            OutputStream o = s.getOutputStream();
            while (!closed.get()) {
                byte[] b = out.take();
                if (b == END || closed.get()) break;
                o.write(b);
                o.flush();
            }
        } catch (InterruptedException e) {
            finish(null);
        } catch (IOException e) {
            if (!closed.get()) finish(open ? "Could not send: " + e.getMessage()
                : "could not connect to " + host + ":" + port + " (" + why(e) + ")");
        } catch (RuntimeException e) {
            finish("Wi-Fi link failed: " + e);
        }
    }

    /** The reason in a few words: Android's messages run to a line. */
    private static String why(IOException e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("ECONNREFUSED")) return "refused: is the adapter or simulator running?";
        if (m.contains("EHOSTUNREACH") || m.contains("ENETUNREACH")) return "no route: is the phone on the same Wi-Fi?";
        if (m.contains("timed out") || m.contains("ETIMEDOUT")) return "no answer";
        return m;
    }

    private void startReader(final Socket s) throws IOException {
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
        }, "tcp-read-" + host);
        t.setDaemon(true);
        t.start();
    }

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
            public void run() { if (!ended) listener.onLine(TcpLink.this, s); }
        });
    }

    private void post(Runnable r) {
        try {
            events.execute(r);
        } catch (RejectedExecutionException e) { /* already over */ }
    }

    /** Ends it, once: an error (null for none) and then onClosed. */
    private void finish(final String error) {
        if (!finished.compareAndSet(false, true)) return;
        closed.set(true);
        open = false;
        out.offer(END);
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) { /* gone */ }
        }
        if (error != null) {
            post(new Runnable() {
                public void run() { listener.onError(TcpLink.this, error); }
            });
        }
        post(new Runnable() {
            public void run() {
                ended = true;
                listener.onClosed(TcpLink.this);
            }
        });
        events.shutdown();
    }
}

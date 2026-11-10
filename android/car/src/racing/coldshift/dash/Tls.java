package racing.coldshift.dash;

import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * TLS that today's servers accept, on Android 4.4: TLS 1.2 turned on (4.4 has it, but off by default),
 * the server's name sent (SNI, which shared hosts need to pick the certificate), and Let's Encrypt's roots
 * (assets/certs: ISRG Root X1 and X2, newer than 4.4's list) trusted after the system's own. Used for the
 * convoy host, over WebSocket (Wire) and HTTP (Convoy).
 */
final class Tls {
    private static SSLSocketFactory factory;

    private Tls() {}

    /** Loads the bundled roots; call once with any context before connecting. */
    static synchronized void init(Context c) {
        if (factory != null) return;
        try {
            final X509TrustManager system = trustManager(null);
            KeyStore bundled = KeyStore.getInstance(KeyStore.getDefaultType());
            bundled.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            String[] names = c.getAssets().list("certs");
            for (String n : names == null ? new String[0] : names) {
                InputStream in = c.getAssets().open("certs/" + n);
                bundled.setCertificateEntry(n, cf.generateCertificate(in));
                in.close();
            }
            final X509TrustManager mine = trustManager(bundled);
            TrustManager both = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    system.checkClientTrusted(chain, auth);
                }

                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    try {
                        system.checkServerTrusted(chain, auth);
                    } catch (CertificateException e) {
                        mine.checkServerTrusted(chain, auth);      // a chain to one of the bundled roots
                    }
                }

                public X509Certificate[] getAcceptedIssuers() { return system.getAcceptedIssuers(); }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {both}, null);
            factory = new Factory(ctx.getSocketFactory());
        } catch (Exception e) {
            android.util.Log.w("Tls", "no bundled roots", e);
            factory = new Factory((SSLSocketFactory) SSLSocketFactory.getDefault());
        }
    }

    private static X509TrustManager trustManager(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager t : f.getTrustManagers()) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IllegalStateException("no X509TrustManager");
    }

    static SSLSocketFactory factory() {
        if (factory == null) throw new IllegalStateException("Tls.init first");
        return factory;
    }

    /**
     * A TLS connection over a connected socket, its certificate checked against `host` (a bare TLS socket
     * checks the chain, not whose it is).
     */
    static SSLSocket wrap(Socket plain, String host, int port) throws IOException {
        SSLSocket s = (SSLSocket) factory().createSocket(plain, host, port, true);
        s.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
            s.close();
            throw new IOException("the certificate is not for " + host);
        }
        return s;
    }

    /** Every socket made: all the protocols the phone has on, and the server's name sent. */
    private static final class Factory extends SSLSocketFactory {
        private final SSLSocketFactory d;

        Factory(SSLSocketFactory d) { this.d = d; }

        private Socket prepare(Socket s, String host) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                ssl.setEnabledProtocols(ssl.getSupportedProtocols());
                if (host != null) {
                    try {
                        // Android's own sockets have setHostname (SNI) without it being public
                        Method m = ssl.getClass().getMethod("setHostname", String.class);
                        m.invoke(ssl, host);
                    } catch (Exception e) { /* not this socket kind: no SNI */ }
                }
            }
            return s;
        }

        public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }

        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return prepare(d.createSocket(s, host, port, autoClose), host);
        }

        public Socket createSocket(String host, int port) throws IOException {
            return prepare(d.createSocket(host, port), host);
        }

        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return prepare(d.createSocket(host, port, local, localPort), host);
        }

        public Socket createSocket(InetAddress host, int port) throws IOException {
            return prepare(d.createSocket(host, port), null);
        }

        public Socket createSocket(InetAddress a, int port, InetAddress local, int localPort) throws IOException {
            return prepare(d.createSocket(a, port, local, localPort), null);
        }
    }
}

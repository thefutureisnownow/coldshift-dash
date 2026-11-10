package racing.coldshift.dash;

/**
 * A line link to an ELM327-style device, whatever carries it: Bluetooth (BtLink, through Bt below) or TCP
 * (TcpLink: a Wi-Fi adapter, or a simulator on a computer). Obd talks to this alone. The semantics are
 * BtLink's: send() adds a carriage return, the lines the device answers with come back through the
 * listener from one background thread, in order, the ELM's prompt ">" a line of its own; after an error
 * onClosed follows, and after onClosed nothing more comes.
 */
interface Link {
    interface Listener {
        /** Connected; how() and openMs() say how. */
        void onOpen(Link l);
        /** One line (or the prompt ">") the device sent. */
        void onLine(Link l, String line);
        /** It failed or was lost: onClosed follows. */
        void onError(Link l, String message);
        /** Over: closed on purpose, or after an error. */
        void onClosed(Link l);
    }

    void send(String line);
    void close();
    boolean isOpen();
    /** A few words on how it is connected ("Classic, insecure RFCOMM", "TCP 192.168.0.10:35000"). */
    String how();
    /** How long opening took, in ms (0 until open). */
    long openMs();

    /** A BtLink seen as a Link. */
    final class Bt implements Link, BtLink.Listener {
        private final Listener to;
        private volatile BtLink link;

        Bt(Devices devices, Devices.Chosen d, Listener to) {
            this.to = to;
            link = devices.open(d, this);
        }

        public void send(String line) { link.send(line); }
        public void close() { link.close(); }
        public boolean isOpen() { return link != null && link.isOpen(); }
        public String how() { return link == null ? "" : link.how(); }
        public long openMs() { return link == null ? 0 : link.openMs(); }

        public void onOpen(BtLink l) { to.onOpen(this); }
        public void onLine(BtLink l, String line) { to.onLine(this, line); }
        public void onError(BtLink l, String message) { to.onError(this, message); }
        public void onClosed(BtLink l) { to.onClosed(this); }
    }
}

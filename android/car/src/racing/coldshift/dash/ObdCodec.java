package racing.coldshift.dash;

import java.util.ArrayList;
import java.util.List;

/**
 * What an ELM327 says, and what it means: no Android in here, so it is tested on a computer with recorded
 * transcripts (the JVM harness). Obd (the engine that talks) and the Engine view use it.
 *
 * Lines come with or without spaces ("41 0C 1A F8", "410C1AF8"), with or without the ELM's own notes in front
 * ("SEARCHING...", "BUS INIT: ...OK"), from one ECU or several (headers are off, so the lines of two ECUs
 * look alike: the first valid one answers). A CAN reply longer than a frame is a length line and numbered
 * lines ("014", "0: 49 02 ...", "1: ..."); K-line and J1850 replies are one line for each frame.
 */
final class ObdCodec {
    private ObdCodec() {}

    // ---- what a line is ----

    /** Kinds of reply, from nothing to say to the worst. */
    static final int EMPTY = 0, INFO = 1, OK = 2, DATA = 3, UNKNOWN_CMD = 4, NO_DATA = 5, ERROR = 6, UNABLE = 7, STOPPED = 8;

    /** Fixes a line for reading: upper case, trimmed, and the notes the ELM glues to the data left off. */
    static String clean(String line) {
        String s = line == null ? "" : line.trim().toUpperCase(java.util.Locale.US);
        for (boolean again = true; again; ) {
            again = false;
            if (s.startsWith("SEARCHING...")) {
                s = s.substring(12).trim();
                again = true;
            } else if (s.startsWith("BUS INIT:")) {
                int k = s.indexOf("OK");
                if (k >= 0 && k + 2 < s.length()) {              // "BUS INIT: ...OK41 00 ..."
                    s = s.substring(k + 2).trim();
                    again = true;
                }
            }
        }
        return s;
    }

    /** The bytes of a line of hex ("41 0C 1A F8" or "410C1AF8"), null when it is not one. */
    static int[] hex(String line) {
        String s = clean(line).replace(" ", "");
        if (s.length() == 0 || (s.length() & 1) != 0) return null;
        int[] out = new int[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = hi * 16 + lo;
        }
        return out;
    }

    /** The kind of one line (not the prompt). */
    static int kind(String line) {
        String s = clean(line);
        if (s.isEmpty()) return line != null && !line.trim().isEmpty() ? INFO : EMPTY;     // "SEARCHING..." alone
        if (s.equals("OK")) return OK;
        if (s.equals("?")) return UNKNOWN_CMD;
        if (s.contains("STOPPED")) return STOPPED;
        if (s.contains("UNABLE TO CONNECT")) return UNABLE;
        if (s.contains("NO DATA")) return NO_DATA;
        if (s.contains("ERROR") || s.contains("BUS BUSY") || s.contains("BUFFER FULL") || s.contains("LV RESET")
                || s.contains("ACT ALERT") || s.contains("LP ALERT") || s.equals("<")) return ERROR;
        if (s.startsWith("SEARCHING") || s.startsWith("BUS INIT") || s.startsWith("ELM") || s.startsWith("AT")) return INFO;
        if (isData(s)) return DATA;
        return INFO;
    }

    /** A line of hex, perhaps behind a frame number ("1: 49 02"), or a CAN length line ("014"). */
    static boolean isData(String line) {
        String s = clean(line);
        if (s.length() >= 2 && s.charAt(1) == ':') s = s.substring(2).trim();
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && Character.digit(c, 16) < 0) return false;
        }
        return true;
    }

    /** The kind of a whole reply: data if any line is, else the most serious thing its lines say. */
    static int kind(List<String> lines) {
        int worst = EMPTY;
        for (String l : lines) {
            int k = kind(l);
            if (k == DATA) return DATA;
            if (k > worst) worst = k;
        }
        return worst;
    }

    // ---- the PIDs ----

    /**
     * The data bytes of the answer to a request of `mode` (1, 2, 9...) for `pid` (-1 for a mode with none):
     * the first line that begins with the answer header (mode + 0x40, the PID) and has `min` bytes after it.
     * Null when no line does. A line may come from any ECU; the first valid one wins.
     */
    static int[] answer(List<String> lines, int mode, int pid, int min) {
        List<int[]> all = answers(lines, mode, pid, min);
        return all.isEmpty() ? null : all.get(0);
    }

    /** All the answers like answer(): one for each ECU that gave one, in the order they came. */
    static List<int[]> answers(List<String> lines, int mode, int pid, int min) {
        List<int[]> out = new ArrayList<int[]>();
        for (String l : lines) {
            String t = clean(l);
            if (t.length() >= 2 && t.charAt(1) == ':') continue;          // part of a longer reply
            int[] b = hex(t);
            if (b == null || b.length < (pid < 0 ? 1 : 2) + min) continue;
            if (b[0] != mode + 0x40 || (pid >= 0 && b[1] != pid)) continue;
            int from = pid < 0 ? 1 : 2;
            int[] d = new int[b.length - from];
            System.arraycopy(b, from, d, 0, d.length);
            out.add(d);
        }
        return out;
    }

    /** How many data bytes a PID's decode needs. */
    static int need(int pid) {
        switch (pid) {
            case 0x0C: case 0x10: case 0x1F: case 0x21: case 0x24: case 0x31: case 0x34: case 0x3C: case 0x42: case 0x44:
            case 0x4D: case 0x4E: return 2;
            default: return 1;
        }
    }

    /** A PID's value in the units of its Obd key (see Obd), NaN when the bytes are too few. */
    static float value(int pid, int[] d) {
        if (d == null || d.length < need(pid)) return Float.NaN;
        switch (pid) {
            case 0x04: case 0x11: case 0x2F: return d[0] * 100f / 255f;           // load, throttle, fuel level, %
            case 0x05: case 0x0F: return d[0] - 40;                              // coolant, intake air, °C
            case 0x06: case 0x07: case 0x08: case 0x09: return d[0] * 100f / 128f - 100f;   // fuel trims, %
            case 0x0B: case 0x33: return d[0];                                   // manifold and barometric pressure, kPa
            case 0x0C: return (d[0] * 256 + d[1]) / 4f;                          // rpm
            case 0x0D: return d[0];                                              // km/h
            case 0x0E: return d[0] / 2f - 64f;                                   // ignition timing, °
            case 0x10: return (d[0] * 256 + d[1]) / 100f;                        // MAF, g/s
            case 0x24: case 0x34: case 0x44: return (d[0] * 256 + d[1]) * 2f / 65536f;  // lambda (wide-range sensor, commanded)
            case 0x3C: return (d[0] * 256 + d[1]) / 10f - 40f;                   // catalyst temperature, °C
            case 0x46: return d[0] - 40;                                         // outside air, °C
            case 0x45: case 0x47: case 0x49: case 0x4A: case 0x4C: case 0x5A: return d[0] * 100f / 255f;   // throttle and pedal, %
            case 0x42: return (d[0] * 256 + d[1]) / 1000f;                       // the ECU's supply, V
            case 0x1F: case 0x21: case 0x31: case 0x4D: case 0x4E: return d[0] * 256 + d[1];   // s, km, km, min, min
            case 0x03: case 0x12: case 0x13: case 0x1C: case 0x30: case 0x51: return d[0];     // states and counts
            default: return Float.NaN;
        }
    }

    // ---- what the car says of itself ----

    /**
     * The data of a mode 09 answer for `pid` (VIN 02, calibration IDs 04, CVNs 06, ECU name 0A), after the
     * mode, the PID and the count or frame number: on CAN one frame ("49 06 01 ...") or a length line and
     * numbered frames ("014", "0: 49 02 01 ...", "1: ..."); on K-line and J1850 a line for each frame, each
     * "49 PID n" and four bytes. Null when there is no answer.
     */
    static int[] info(List<String> lines, int pid) {
        java.io.ByteArrayOutputStream multi = new java.io.ByteArrayOutputStream(), single = new java.io.ByteArrayOutputStream();
        int len = -1;
        boolean frames = false, any = false;
        for (String l : lines) {
            String t = clean(l), bare = t.replace(" ", "");
            if (t.length() >= 2 && t.charAt(1) == ':' && Character.digit(t.charAt(0), 16) >= 0) {
                int[] b = hex(t.substring(2));
                if (b == null) continue;
                frames = true;
                for (int x : b) multi.write(x);
            } else if (bare.length() == 3 && hex("0" + bare) != null) {
                len = Integer.parseInt(bare, 16);
            } else {
                int[] b = hex(t);
                if (b == null || b.length < 3 || b[0] != 0x49 || b[1] != pid) continue;
                any = true;
                for (int i = 3; i < b.length; i++) single.write(b[i]);
            }
        }
        if (frames) {
            int[] b = bytes(multi);
            if (len > 0 && len < b.length) b = java.util.Arrays.copyOf(b, len);
            if (b.length < 3 || b[0] != 0x49 || b[1] != pid) return null;
            return java.util.Arrays.copyOfRange(b, 3, b.length);
        }
        return any ? bytes(single) : null;
    }

    /** Printable text in a run of bytes (a VIN, a calibration ID), the padding left off; "" when there is none. */
    static String ascii(int[] d, int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < Math.min(to, d == null ? 0 : d.length); i++) if (d[i] > 0x20 && d[i] < 0x7f) b.append((char) d[i]);
        return b.toString();
    }

    /** Fuel system status (0103, the first system). */
    static String fuelSystem(int a) {
        switch (a) {
            case 1: return "open loop, warming up";
            case 2: return "closed loop";
            case 4: return "open loop, load or decel";
            case 8: return "open loop, fault";
            case 16: return "closed loop, sensor fault";
            default: return "";
        }
    }

    /** The OBD standard the car was made to (011C). */
    static String standard(int a) {
        String[] n = {"", "OBD-II (California)", "OBD (EPA)", "OBD and OBD-II", "OBD-I", "Not OBD compliant", "EOBD (Europe)",
            "EOBD and OBD-II", "EOBD and OBD", "EOBD, OBD and OBD-II", "JOBD (Japan)", "JOBD and OBD-II", "JOBD and EOBD",
            "JOBD, EOBD and OBD-II"};
        return a > 0 && a < n.length ? n[a] : a == 30 ? "KOBD (Korea)" : a > 0 ? "Standard " + a : "";
    }

    /** The fuel the car runs on (0151). */
    static String fuelType(int a) {
        String[] n = {"", "Petrol", "Methanol", "Ethanol", "Diesel", "LPG", "CNG", "Propane", "Electric", "Petrol or gas",
            "Methanol or gas", "Ethanol or gas", "Petrol or LPG", "Petrol or CNG", "Petrol or propane", "Petrol or electric",
            "Petrol, electric and combustion", "Hybrid petrol", "Hybrid ethanol", "Hybrid diesel", "Hybrid electric",
            "Hybrid mixed", "Hybrid regenerative", "Diesel or gas"};
        return a > 0 && a < n.length ? n[a] : "";
    }

    /** One readiness monitor: whether the car has it, and whether its test is done (complete) or still to run. */
    static final class Monitor {
        final String name;
        final boolean done;
        Monitor(String name, boolean done) {
            this.name = name;
            this.done = done;
        }
    }

    private static final String[] COMMON = {"Misfire", "Fuel system", "Components"};
    private static final String[] SPARK = {"Catalyst", "Heated catalyst", "Evaporative system", "Secondary air", "A/C refrigerant",
        "O2 sensor", "O2 sensor heater", "EGR / VVT"};
    private static final String[] DIESEL = {"NMHC catalyst", "NOx / SCR", "", "Boost pressure", "", "Exhaust gas sensor",
        "Particulate filter", "EGR / VVT"};

    /**
     * The monitors in an answer to 0101 (since the codes were cleared) or 0141 (this drive cycle), bytes A to D:
     * B has the three common ones (bits 0-2 supported or enabled, 4-6 incomplete) and says spark or compression
     * ignition (bit 3), C lists the engine's own and D which of those are incomplete.
     */
    static List<Monitor> monitors(int[] d) {
        List<Monitor> out = new ArrayList<Monitor>();
        if (d == null || d.length < 4) return out;
        for (int i = 0; i < 3; i++) if ((d[1] & (1 << i)) != 0) out.add(new Monitor(COMMON[i], (d[1] & (0x10 << i)) == 0));
        String[] names = (d[1] & 0x08) != 0 ? DIESEL : SPARK;
        for (int i = 0; i < 8; i++) {
            if ((d[2] & (1 << i)) != 0 && !names[i].isEmpty()) out.add(new Monitor(names[i], (d[3] & (1 << i)) == 0));
        }
        return out;
    }

    /** Which O2 sensors are fitted (0113): "bank 1: 1, 2". */
    static String o2Sensors(int a) {
        StringBuilder b = new StringBuilder();
        for (int bank = 0; bank < 2; bank++) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 4; i++) if ((a & (1 << (bank * 4 + i))) != 0) s.append(s.length() > 0 ? ", " : "").append(i + 1);
            if (s.length() > 0) b.append(b.length() > 0 ? "; " : "").append("bank ").append(bank + 1).append(": ").append(s);
        }
        return b.toString();
    }

    /**
     * Which PIDs an answer to 0100, 0120, 0140... lists: sets supported[base + 1 .. base + 32]. Returns
     * whether the next block (base + 0x20) is listed too.
     */
    static boolean supported(int[] d, int base, boolean[] supported) {
        if (d == null || d.length < 4) return false;
        for (int i = 0; i < 32; i++) {
            if ((d[i / 8] & (0x80 >> (i % 8))) != 0 && base + 1 + i < supported.length) supported[base + 1 + i] = true;
        }
        return (d[3] & 1) != 0;
    }

    /** The warning light and code count of an 0101 answer: bit 7 of the first byte is the MIL, the rest the codes. */
    static int milCount(int[] d) { return d == null || d.length < 1 ? -1 : d[0] & 0x7f; }
    static boolean milOn(int[] d) { return d != null && d.length >= 1 && (d[0] & 0x80) != 0; }

    /** The volts of an ATRV answer ("12.4V", "12.4"), NaN when there is none. */
    static float volts(List<String> lines) {
        for (String l : lines) {
            String s = clean(l);
            if (s.endsWith("V")) s = s.substring(0, s.length() - 1).trim();
            if (s.isEmpty() || !Character.isDigit(s.charAt(0))) continue;
            try {
                float v = Float.parseFloat(s);
                if (v > 0 && v < 40) return v;
            } catch (NumberFormatException e) { /* not a number */ }
        }
        return Float.NaN;
    }

    // ---- the protocol ----

    private static final String[] PROTOCOL = {"", "SAE J1850 PWM (41.6 kbaud)", "SAE J1850 VPW (10.4 kbaud)",
        "ISO 9141-2 (5 baud init)", "ISO 14230-4 KWP (5 baud init)", "ISO 14230-4 KWP (fast init)",
        "ISO 15765-4 CAN (11 bit, 500 kbaud)", "ISO 15765-4 CAN (29 bit, 500 kbaud)",
        "ISO 15765-4 CAN (11 bit, 250 kbaud)", "ISO 15765-4 CAN (29 bit, 250 kbaud)",
        "SAE J1939 CAN (29 bit, 250 kbaud)", "User1 CAN (11 bit, 125 kbaud)", "User2 CAN (11 bit, 50 kbaud)"};

    /** The protocol number (1..12) ATDPN answered ("A6": automatic, number 6), 0 when it is not one. */
    static int protocol(List<String> lines) {
        for (String l : lines) {
            String s = clean(l);
            if (s.length() == 2 && s.charAt(0) == 'A') s = s.substring(1);
            if (s.length() != 1) continue;
            int n = Character.digit(s.charAt(0), 16);
            if (n >= 1 && n <= 12) return n;
        }
        return 0;
    }

    static String protocolName(int p) { return p >= 1 && p <= 12 ? PROTOCOL[p] : ""; }

    /** Protocols 6 to 9 are OBD on CAN (B and C are user-set CAN): replies may be multi-frame, with a count byte. */
    static boolean isCan(int p) { return p >= 6 && p <= 9 || p == 11 || p == 12; }

    // ---- the fault codes ----

    /**
     * The codes in the answer to 03 (stored), 07 (pending) or 0A (permanent): the request's mode is 3, 7 or
     * 0xA, the answer's 0x43, 0x47 or 0x4A. On CAN the answer is "43 NN code code ...", in one frame or several
     * (a length line and numbered lines); elsewhere each frame is "43" and up to three codes. Codes from more
     * than one ECU are joined, each once. Returns an empty list when there are none.
     */
    static List<String> codes(List<String> lines, int mode, boolean can) {
        List<int[]> messages = new ArrayList<int[]>();
        java.io.ByteArrayOutputStream multi = null;
        for (String l : lines) {
            String t = clean(l);
            String bare = t.replace(" ", "");
            if (t.length() >= 2 && t.charAt(1) == ':' && Character.digit(t.charAt(0), 16) >= 0) {
                int[] b = hex(t.substring(2));
                if (b == null) continue;
                if (multi == null) multi = new java.io.ByteArrayOutputStream();
                for (int x : b) multi.write(x);
            } else if (bare.length() == 3 && hex("0" + bare) != null && t.indexOf(' ') < 0) {
                if (multi != null) messages.add(bytes(multi));
                multi = new java.io.ByteArrayOutputStream();           // a length line starts a longer reply
            } else {
                int[] b = hex(t);
                if (b == null) continue;
                if (multi != null) {
                    messages.add(bytes(multi));
                    multi = null;
                }
                messages.add(b);
            }
        }
        if (multi != null) messages.add(bytes(multi));
        List<String> out = new ArrayList<String>();
        for (int[] m : messages) {
            if (m.length == 0 || m[0] != mode + 0x40) continue;
            int n = can ? (m.length > 1 ? m[1] : 0) : 3, from = can ? 2 : 1;
            for (int i = from, taken = 0; i + 1 < m.length && (!can || taken < n); i += 2, taken++) {
                if (m[i] == 0 && m[i + 1] == 0) continue;
                String c = code(m[i], m[i + 1]);
                if (!out.contains(c)) out.add(c);
            }
        }
        return out;
    }

    private static int[] bytes(java.io.ByteArrayOutputStream b) {
        byte[] x = b.toByteArray();
        int[] out = new int[x.length];
        for (int i = 0; i < x.length; i++) out[i] = x[i] & 0xff;
        return out;
    }

    /** A code from its two bytes: the top two bits are P, C, B or U, then a digit and three hex digits. */
    static String code(int hi, int lo) {
        return "" + "PCBU".charAt(hi >> 6 & 3) + (hi >> 4 & 3) + Integer.toHexString(hi & 15).toUpperCase(java.util.Locale.US)
            + String.format(java.util.Locale.US, "%02X", lo);
    }
}

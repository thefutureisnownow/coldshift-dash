package racing.coldshift.dash;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The phone's temperatures from the kernel's thermal zones (/sys/class/thermal/thermal_zone*), readable
 * by apps on this phone. On the G7: tsens_tz_sensor0..5 are the processor (whole degrees), ti-charger
 * and pm8916_tz the charger and power chips, battery the battery (thousandths of a degree). The battery's
 * also comes with every battery broadcast (UI reads that one for the title bar).
 */
final class Thermal {
    /** Hot enough to note: Android limits charging from about 45 C; from 55 C the phone is in trouble. */
    static final float WARM = 45, HOT = 55;

    private static List<File[]> zones;           // {type file, temp file}

    private Thermal() {}

    private static synchronized List<File[]> zones() {
        if (zones != null) return zones;
        zones = new ArrayList<File[]>();
        File[] all = new File("/sys/class/thermal").listFiles();
        for (File z : all == null ? new File[0] : all) {
            if (!z.getName().startsWith("thermal_zone")) continue;
            File type = new File(z, "type"), temp = new File(z, "temp");
            if (type.canRead() && temp.canRead()) zones.add(new File[] {type, temp});
        }
        return zones;
    }

    private static String read(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[64];
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n).trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Degrees C from a zone's reading: whole degrees, or thousandths (values over 200). */
    private static float degrees(String v) {
        try {
            float t = Float.parseFloat(v);
            return t > 200 || t < -200 ? t / 1000f : t;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /** {processor (the hottest core sensor), charger, power chip}, NaN where there is no sensor. */
    static float[] read() {
        float cpu = Float.NaN, charger = Float.NaN, pmic = Float.NaN;
        for (File[] z : zones()) {
            String type = read(z[0]);
            float t = degrees(read(z[1]));
            if (Float.isNaN(t)) continue;
            if (type.startsWith("tsens") || type.contains("cpu")) cpu = Float.isNaN(cpu) ? t : Math.max(cpu, t);
            else if (type.contains("charger")) charger = t;
            else if (type.startsWith("pm") || type.contains("pmic")) pmic = t;
        }
        return new float[] {cpu, charger, pmic};
    }

    static int colour(float t) { return t >= HOT ? Theme.BAD : t >= WARM ? Theme.GOLD : Theme.TEXT; }

    static String text(float t) { return Float.isNaN(t) ? "-" : Math.round(t) + "°C"; }
}

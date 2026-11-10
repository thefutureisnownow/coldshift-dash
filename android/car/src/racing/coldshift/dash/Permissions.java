package racing.coldshift.dash;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * The first-run screen of the modern APK: what the app needs on a newer phone, one row each, with a
 * button that asks Android for it. The legacy APK (Android 4.4) never shows it: there everything is
 * granted on install. It comes up over the app whenever something it needs is missing, until Continue
 * (or Not now, for this launch).
 *
 * Nearby devices (Android 12+: Bluetooth scan and connect, for the Devices in Settings) is optional: it
 * does not bring this screen up by itself. Before Android 12 Bluetooth scanning rides on Location.
 *
 * Android's own question comes up for each, except two it handles in Settings: All files access (the
 * music, maps and coldshift.dash/ on the SD card are ordinary files, which an app built for Android 11+
 * reads only with it) and location all the time. A permission refused twice is no longer asked by
 * Android; its button then opens the app's page in Settings. The screen checks again twice a second, so
 * what was granted in Settings shows when the user comes back.
 */
final class Permissions extends View {
    static final int REQUEST = 41;
    private static boolean dismissed;

    /** One thing the app asks for. */
    private static final class Item {
        final String name, why;
        final String[] perms;          // runtime permissions; none for All files access
        final boolean optional;
        Item(String name, String why, boolean optional, String... perms) {
            this.name = name;
            this.why = why;
            this.optional = optional;
            this.perms = perms;
        }
        boolean files() { return perms.length == 0; }
        boolean is(String p) { return perms.length > 0 && perms[0].equals(p); }
    }

    private final Main act;
    private final List<Item> items;
    private final boolean[] had;
    private final RectF done = new RectF(490, 628, 790, 684);
    private final RectF[] buttons;

    Permissions(Main a) {
        super(a);
        act = a;
        items = items();
        had = new boolean[items.size()];
        buttons = new RectF[items.size()];
        float y = 150, step = Math.min(78, 460f / items.size());
        for (int i = 0; i < items.size(); i++) {
            had[i] = granted(a, items.get(i));
            buttons[i] = new RectF(900, y + i * step + 8, 1150, y + i * step + step - 10);
        }
        setClickable(true);
    }

    /** What this Android asks for. */
    private static List<Item> items() {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item("Location", "Trips, the map, convoy and track timing", false,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION));
        items.add(new Item("Camera", "The dashcam", false, Manifest.permission.CAMERA));
        items.add(new Item("Microphone", "The dashcam's sound, and convoy voice notes", false,
            Manifest.permission.RECORD_AUDIO));
        if (Api.SDK >= 30) {
            items.add(new Item("All files access", "Music, maps and the coldshift.dash folder on the SD card", false));
        } else {
            items.add(new Item("Files", "Music, maps and the coldshift.dash folder on the SD card", false,
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE));
        }
        if (Api.SDK >= 33) {
            items.add(new Item("Notifications", "Says when the GPS, dashcam or music runs in the background", false,
                Manifest.permission.POST_NOTIFICATIONS));
        }
        if (Api.SDK >= 31) {
            items.add(new Item("Nearby devices", "Optional: the OBD adapter, tyre sensors, GPS receiver and headset", true,
                "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"));
        }
        if (Api.SDK >= 29) {
            items.add(new Item("Location all the time", "Optional: trips logged when the app starts in the background",
                true, Manifest.permission.ACCESS_BACKGROUND_LOCATION));
        }
        return items;
    }

    /** Whether to show it now: the modern APK, something it needs missing, and not put off this launch. */
    static boolean wanted(Context c) {
        if (dismissed || Api.SDK < 23 || !Api.modern(c)) return false;
        for (Item it : items()) if (!it.optional && !granted(c, it)) return true;
        return false;
    }

    private static boolean granted(Context c, Item it) {
        if (it.files()) return Environment.isExternalStorageManager();
        for (String p : it.perms) if (!Api.granted(c, p)) return false;
        return true;
    }

    /** Refused before, and Android would no longer ask (refused twice, or "Don't ask again"). */
    private boolean refused(Item it) {
        return !it.files() && refused(act, it.perms);
    }

    static boolean refused(Main a, String[] perms) {
        SharedPreferences sp = a.getSharedPreferences("permissions", 0);
        for (String p : perms) {
            if (sp.getBoolean(p, false) && !Api.granted(a, p) && !a.shouldShowRequestPermissionRationale(p)) return true;
        }
        return false;
    }

    /** Asks Android for permissions from elsewhere in the app (Settings, Devices): its question, or the app's page in Settings once refused. */
    static void request(Main a, String[] perms) {
        try {
            if (refused(a, perms)) {
                a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
            } else {
                a.requestPermissions(perms, REQUEST);
            }
        } catch (RuntimeException e) {
            android.util.Log.w("Permissions", "cannot ask for " + java.util.Arrays.toString(perms), e);
        }
    }

    /** Android's answer (Main.onRequestPermissionsResult): what was refused is remembered for refused(). */
    static void answered(Context c, String[] perms, int[] results) {
        SharedPreferences.Editor e = c.getSharedPreferences("permissions", 0).edit();
        for (int i = 0; i < results.length && i < perms.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) e.putBoolean(perms[i], true);
        }
        e.apply();
    }

    private boolean blockedBy(int i) {
        // all the time only once the app may use location at all
        return items.get(i).is(Manifest.permission.ACCESS_BACKGROUND_LOCATION) && !had[0];
    }

    private void ask(int i) {
        Item it = items.get(i);
        try {
            if (it.files()) {
                Intent in = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + act.getPackageName()));
                try {
                    act.startActivity(in);
                } catch (RuntimeException e) {
                    act.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else if (refused(it)) {
                act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + act.getPackageName())));
            } else {
                act.requestPermissions(it.perms, REQUEST);
            }
        } catch (RuntimeException e) {
            android.util.Log.w("Permissions", "cannot ask for " + it.name, e);
        }
    }

    /** What was just granted takes effect: the GPS starts, the card is read again. */
    private void check() {
        boolean files = false;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            boolean g = granted(act, it);
            if (g && !had[i]) {
                if (it.is(Manifest.permission.ACCESS_FINE_LOCATION)) Gps.get(act).permitted();
                if (it.is(Manifest.permission.CAMERA)) Dashcam.get(act).permitted();
                if (it.files() || it.is(Manifest.permission.READ_EXTERNAL_STORAGE)) files = true;
            }
            had[i] = g;
        }
        if (files) {
            Storage.refresh();
            act.reloadSynced(Main.RELOAD_ALL);
        }
    }

    private boolean allNeeded() {
        for (int i = 0; i < items.size(); i++) if (!items.get(i).optional && !had[i]) return false;
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        check();
        c.save();
        Screen.begin(c, this);
        float e = Screen.extra(this);
        Theme.P.setShader(null);
        Theme.P.setStyle(android.graphics.Paint.Style.FILL);
        Theme.P.setColor(0xf4040912);
        c.drawRect(-e, 0, 1280 + e, 720, Theme.P);
        Theme.text(c, "Before you drive", 130, 84, 38, Theme.TEXT, Theme.bold, Theme.LEFT);
        Theme.text(c, "coldshift.dash needs these on this phone. Android asks you for each one.",
            130, 118, 20, Theme.SUB, Theme.sans, Theme.LEFT);
        Theme.panel(c, 110, 140, 1170, 140 + 470);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            RectF b = buttons[i];
            float cy = b.centerY();
            Theme.text(c, it.name, 140, cy - 4, 24, Theme.TEXT, Theme.bold, Theme.LEFT);
            Theme.text(c, Theme.fit(it.why, 720, 18, Theme.sans), 140, cy + 20, 18, Theme.SUB, Theme.sans, Theme.LEFT);
            if (had[i]) {
                Theme.text(c, "✓ Allowed", b.centerX(), cy + 8, 22, Theme.GREEN, Theme.bold, Theme.CENTER);
            } else if (blockedBy(i)) {
                Theme.text(c, "After location", b.centerX(), cy + 7, 18, Theme.DIM, Theme.sans, Theme.CENTER);
            } else {
                boolean settings = it.files() || refused(it);
                Theme.button(c, b, !settings, 12);
                Theme.text(c, settings ? "Open settings" : "Allow", b.centerX(), cy + 8, 22, 0xffffffff,
                    Theme.bold, Theme.CENTER);
            }
        }
        boolean ok = allNeeded();
        Theme.button(c, done, ok, 14);
        Theme.text(c, ok ? "Continue" : "Not now", done.centerX(), done.centerY() + 8, 22, 0xffffffff,
            Theme.bold, Theme.CENTER);
        c.restore();
        postInvalidateDelayed(500);
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() != MotionEvent.ACTION_UP) return true;
        float x = Screen.x(this, ev.getX()), y = Screen.y(this, ev.getY());
        if (done.contains(x, y)) {
            dismissed = true;
            ((android.view.ViewGroup) getParent()).removeView(this);
            return true;
        }
        for (int i = 0; i < items.size(); i++) {
            if (buttons[i].contains(x, y) && !had[i] && !blockedBy(i)) {
                ask(i);
                break;
            }
        }
        return true;
    }
}

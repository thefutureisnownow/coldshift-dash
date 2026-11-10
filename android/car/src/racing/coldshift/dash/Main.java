package racing.coldshift.dash;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.InputStream;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.WindowManager;

public class Main extends Activity {
    /**
     * The desktop tool sends this over adb after it changes files on the card:
     *   adb shell am broadcast -a racing.coldshift.dash.SYNCED --es what maps,music,search,places
     * Only the shell and the system hold DUMP, so other apps cannot send it. The result data
     * says what was reloaded, which `am broadcast` prints back to the tool.
     */
    static final String SYNCED = "racing.coldshift.dash.SYNCED";

    /**
     * Test positions over adb, for trying the map indoors (shell or system only, as SYNCED):
     *   adb shell am broadcast -a racing.coldshift.dash.FAKE_FIX --ef lat -34.03 --ef lon 23.05
     *     --ef bearing 90 --ef speed 15 [--el time <ms>]
     * Every page listening to Gps gets them (a time lets the log book's tests skip through long stops); real GPS fixes are ignored for 15 s after each one.
     */
    static final String FAKE_FIX = "racing.coldshift.dash.FAKE_FIX";

    private final BroadcastReceiver fakeFixReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent in) {
            android.location.Location l = new android.location.Location("fake");
            l.setLatitude(in.getFloatExtra("lat", 0));
            l.setLongitude(in.getFloatExtra("lon", 0));
            if (in.hasExtra("bearing")) l.setBearing(in.getFloatExtra("bearing", 0));
            if (in.hasExtra("speed")) l.setSpeed(in.getFloatExtra("speed", 0));
            l.setAccuracy(5);
            l.setTime(in.getLongExtra("time", System.currentTimeMillis()));
            Gps.get(Main.this).fake(l);
        }
    };

    /**
     * Points the Engine role (the OBD adapter) at a TCP ELM327, for testing without a car or later for a
     * Wi-Fi adapter (shell or system only, as SYNCED); nothing in the UI does it:
     *   adb shell am broadcast -a racing.coldshift.dash.ENGINE --es target tcp:192.168.0.10:35000
     * "target" can also be "restore" (the device it replaced comes back, or none if there was none) and
     * "none" (no Engine device). The result data says what the Engine role is now.
     */
    static final String ENGINE = "racing.coldshift.dash.ENGINE";

    private final BroadcastReceiver engineReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent in) {
            String t = in.getStringExtra("target");
            Devices d = Devices.get(Main.this);
            if (t != null && t.startsWith("tcp:")) d.chooseTcp(t.substring(4));
            else if ("restore".equals(t)) d.restoreEngine();
            else if ("none".equals(t)) d.clear(Devices.ENGINE);
            Devices.Chosen now = d.chosen(Devices.ENGINE);
            if (isOrderedBroadcast()) setResultData("engine: " + (now == null ? "none" : now.label() + " (" + now.address + ")"));
        }
    };

    /**
     * Ask the car for readings the dash does not show, for testing (shell or system only); the replies go to
     * logcat (ObdTrace), and "survey" asks for everything the car lists plus its VIN:
     *   adb shell am broadcast -a racing.coldshift.dash.OBD_ASK --es cmd survey
     *   adb shell am broadcast -a racing.coldshift.dash.OBD_ASK --es cmd "0146 015C"
     */
    static final String OBD_ASK = "racing.coldshift.dash.OBD_ASK";

    private final BroadcastReceiver obdAskReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent in) {
            String cmd = in.getStringExtra("cmd");
            int n = cmd == null ? 0 : Obd.get(Main.this).ask(cmd);
            if (isOrderedBroadcast()) setResultData("queued " + n);
        }
    };

    /**
     * Navigate somewhere over adb, for testing (shell or system only); it starts from the last fix:
     *   adb shell am broadcast -a racing.coldshift.dash.ROUTE_TO --ef lat -33.9249 --ef lon 18.4241
     */
    static final String ROUTE_TO = "racing.coldshift.dash.ROUTE_TO";

    private final BroadcastReceiver routeToReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent in) {
            ui.mapsPage().navigateTo(in.getFloatExtra("lat", 0), in.getFloatExtra("lon", 0), "Test destination");
        }
    };

    private final BroadcastReceiver routeDoneReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent in) {
            if (!in.getBooleanExtra("ok", false)) {
                ui.mapsPage().onRouteFailed(in.getStringExtra("error"));
                return;
            }
            try {
                ui.mapsPage().onRoute(Route.read(RouterService.resultFile(Main.this)), in.getLongExtra("ms", 0));
            } catch (java.io.IOException e) {
                ui.mapsPage().onRouteFailed(e.getMessage());
            }
        }
    };

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent in) {
            // a card put back in (synced in a card reader) may have new maps and music too
            if (!SYNCED.equals(in.getAction())) Storage.refresh();        // a card went in: look for it again
            String what = SYNCED.equals(in.getAction()) ? in.getStringExtra("what") : null;
            String done = reloadSynced(what == null || what.isEmpty() ? RELOAD_ALL : what);
            if (isOrderedBroadcast()) setResultData(done);
        }
    };

    /** Everything reloadSynced can reload. */
    static final String RELOAD_ALL = "maps,music,search,places,voice,profile,settings,track,log,convoy";

    private UI ui;
    private FrameLayout root;
    private MapHost maps;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        drawUnderCutout();
        applyKiosk();
        Convoy.get(this).attach(this);
        Cues.get(this);                                     // loads the sounds
        ui = new UI(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        root.addView(ui, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        // a convoy message over whatever shows, the map included
        final Banner banner = new Banner(this);
        root.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        Convoy.get(this).addListener(new Convoy.Listener() {
            public void convoyChanged() {}
            public void convoyMessage(Convoy.Message m) {
                // a private message says so: "Malva Pudding → you", in the private colour
                banner.show(m.isPrivate() ? m.name + " → you" : m.name,
                    m.says() + (m.late > 0 ? " (" + ConvoyPage.late(m.late) + ")" : ""), m.isPrivate());
            }
        });
        // the modern APK: what Android has not granted yet, asked for over the app (under the splash)
        if (Permissions.wanted(this)) {
            root.addView(new Permissions(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (!Splash.shown) {
            root.addView(new Splash(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setContentView(root);
        hideSystemBars();
        applyBrightness(Prefs.brightness(this));
        if (Prefs.restore(this)) {
            applyBrightness(Prefs.brightness(this));
            applyKiosk();
            Devices.get(this).reload();
        }
        Prefs.watch(this);
        // adb's shell sends the first three (only it and the system hold DUMP); the router is this app
        Api.listen(this, syncReceiver, new IntentFilter(SYNCED), android.Manifest.permission.DUMP, true);
        Api.listen(this, fakeFixReceiver, new IntentFilter(FAKE_FIX), android.Manifest.permission.DUMP, true);
        Api.listen(this, routeToReceiver, new IntentFilter(ROUTE_TO), android.Manifest.permission.DUMP, true);
        Api.listen(this, engineReceiver, new IntentFilter(ENGINE), android.Manifest.permission.DUMP, true);
        Api.listen(this, obdAskReceiver, new IntentFilter(OBD_ASK), android.Manifest.permission.DUMP, true);
        Api.listen(this, routeDoneReceiver, new IntentFilter(RouterService.DONE), RouterService.PERMISSION, false);
        IntentFilter mounted = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        mounted.addDataScheme("file");
        registerReceiver(syncReceiver, mounted);
    }

    String reloadSynced(String what) {
        StringBuilder done = new StringBuilder();
        if (what.contains("maps")) {
            maps().reload();
            done.append("maps");
        }
        boolean data = what.contains("maps") || what.contains("search");
        if (data || what.contains("places")) {
            ui.mapsPage().reloadPlaces(data, what.contains("places"));
            if (what.contains("search")) done.append(done.length() > 0 ? "," : "").append("search");
            if (what.contains("places")) done.append(done.length() > 0 ? "," : "").append("places");
        }
        if (what.contains("settings")) {
            if (Prefs.restore(this)) {
                applyBrightness(Prefs.brightness(this));
                Devices.get(this).reload();
            }
            done.append(done.length() > 0 ? "," : "").append("settings");
        }
        if (what.contains("profile")) {
            Profile.reload();
            ui.reloadPages();
            maps().reload();
            done.append(done.length() > 0 ? "," : "").append("profile");
        }
        if (what.contains("track")) {
            ui.reloadTrack();
            done.append(done.length() > 0 ? "," : "").append("track");
        }
        if (what.contains("log")) {
            ui.reloadLog();
            done.append(done.length() > 0 ? "," : "").append("log");
        }
        if (what.contains("convoy")) {
            Convoy.get(this).reload();
            done.append(done.length() > 0 ? "," : "").append("convoy");
        }
        if (what.contains("voice")) {
            Voice.get(this).reload();
            done.append(done.length() > 0 ? "," : "").append("voice");
        }
        if (what.contains("music")) {
            Library.get(this).reload();
            done.append(done.length() > 0 ? "," : "").append("music");
        }
        android.util.Log.i("Main", "synced: " + what);
        return done.length() > 0 ? "reloaded " + done : "nothing to reload for " + what;
    }

    void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == Permissions.REQUEST) Permissions.answered(this, perms, results);
    }

    @Override protected void onResume() {
        super.onResume();
        Dashcam.get(this).rememberRotation(this);
        ui.start();
        Obd.get(this).resume();                            // reads the car while the app is in front
    }
    @Override protected void onPause() {
        super.onPause();
        ui.stop();
        Obd.get(this).pause();
    }

    /**
     * On a phone with a camera notch (the A22), the app also draws beside it in landscape (Android 9's
     * layoutInDisplayCutoutMode).
     */
    private void drawUnderCutout() {
        if (Api.SDK < 28) return;                          // older Android: no notches
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
    }

    /** Kiosk mode or not (Prefs.kiosk): over the lock screen and kept in front, or an ordinary app. */
    void applyKiosk() {
        int over = WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        if (Prefs.kiosk(this)) {
            getWindow().addFlags(over);
            Watchdog.start(this);
        } else {
            getWindow().clearFlags(over);
            stopService(new Intent(this, Watchdog.class));
        }
    }

    @Override public void onBackPressed() {
        if (!Prefs.kiosk(this)) moveTaskToBack(true);          // to the phone, the app still running
        // kiosk: Back does nothing
    }

    /** 0..255 for the window only; 0 blanks the backlight. */
    void applyBrightness(int v) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = v <= 0 ? 0f : Math.max(0.04f, v / 255f);
        getWindow().setAttributes(lp);
    }

    Bitmap asset(String name) {
        try {
            InputStream in = getAssets().open(name);
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    UI ui() { return ui; }
    FrameLayout root() { return root; }
    MapHost maps() { if (maps == null) maps = new MapHost(this); return maps; }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(syncReceiver);
        unregisterReceiver(fakeFixReceiver);
        unregisterReceiver(routeToReceiver);
        unregisterReceiver(engineReceiver);
        unregisterReceiver(obdAskReceiver);
        unregisterReceiver(routeDoneReceiver);
        if (maps != null) maps.destroy();
    }

    int systemUiVisibility() { return getWindow().getDecorView().getSystemUiVisibility(); }
}

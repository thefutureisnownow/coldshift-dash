package racing.coldshift.dash;

import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/** The whole 1280x720 UI: photo background, top bar, tab bar and the current page. */
public class UI extends View implements Runnable {
    static final int TOPH = 54, TABY = 633;

    private final Main act;
    private final Handler handler = new Handler();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm");
    private final SimpleDateFormat date = new SimpleDateFormat("EEE d MMM");
    private final Page[] all;           // every page, made once
    private final java.util.Map<String, Page> byName = new java.util.HashMap<String, Page>();
    private Page[] pages;                // those the profile shows, Settings last
    private int cur;
    private final RectF rect = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int batteryPct = -1;
    private float batteryTemp = Float.NaN;          // C, from the battery broadcast
    private boolean charging;
    private long lastTouch = System.currentTimeMillis();
    private boolean blank;
    private long awakeUntil;            // woken for a convoy message: no blanking before this
    private boolean swallow;
    private final Press press = new Press(this);

    public UI(Main a) {
        super(a);
        act = a;
        all = new Page[] {
            new DrivePage(a), new MediaPage(a), new MapsPage(a), new TrackPage(a), new SensorsPage(a),
            new DashcamPage(a), new LogPage(a), new ConvoyPage(a), new SettingsPage(a, this), new MorePage(a, this)};
        String[] names = {"drive", "media", "maps", "track", "sensors", "dashcam", "log", "convoy", "settings", "more"};
        for (int i = 0; i < all.length; i++) byName.put(names[i], all[i]);
        choosePages();
        // the page that was showing when the app last closed comes back
        Page was = byName.get(Prefs.sp(a).getString("last_page", ""));
        int i = was == null ? -1 : java.util.Arrays.asList(pages).indexOf(was);
        if (i >= 0) cur = i;
    }

    Bitmap loadAsset(String name) {
        try {
            InputStream in = act.getAssets().open(name);
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** The Maps page, shown or not: navigation and the adb test broadcasts go through it. */
    MapsPage mapsPage() { return (MapsPage) byName.get("maps"); }

    /** After the desktop sent segments: the Track page reads them again. */
    void reloadTrack() { ((TrackPage) byName.get("track")).refresh(); }

    /** The page showing. */
    Page current() { return pages[cur]; }

    /** Shows the page with that name, when the profile shows it. */
    void show(String name) { show(byName.get(name)); }

    void show(Page p) {
        int i = java.util.Arrays.asList(pages).indexOf(p);
        if (i >= 0) select(i);
    }

    // ---- the tab bar: the few pages a driver reaches for, and More for the rest ----

    private static final int BAR = 5;                       // tabs, More among them
    private static final String[] ON_BAR = {"drive", "maps", "media", "dashcam"};

    /**
     * The tabs, left to right. Few enough pages all get one; else Drive, Maps, Media and Dashcam (those this
     * car shows, made up to four from the others), Convoy too while in a convoy, and More last.
     */
    java.util.List<Page> bar() {
        Page more = byName.get("more");
        java.util.List<Page> rest = new java.util.ArrayList<Page>(java.util.Arrays.asList(pages));
        rest.remove(more);
        if (rest.size() <= BAR) return rest;
        java.util.List<Page> out = new java.util.ArrayList<Page>();
        for (String n : ON_BAR) if (rest.contains(byName.get(n))) out.add(byName.get(n));
        for (Page p : rest) if (out.size() < BAR - 1 && !out.contains(p) && p != byName.get("settings")) out.add(p);
        Page convoy = byName.get("convoy");
        if (rest.contains(convoy) && !out.contains(convoy) && Convoy.get(act).inConvoy()) out.add(convoy);
        out.add(more);
        return out;
    }

    /** The pages reached through More. */
    java.util.List<Page> behind() {
        java.util.List<Page> out = new java.util.ArrayList<Page>(java.util.Arrays.asList(pages));
        out.removeAll(bar());
        out.remove(byName.get("more"));
        return out;
    }

    /** Shows Settings on its Devices tab, where an OBD adapter is chosen. */
    void openDevices() {
        ((SettingsPage) byName.get("settings")).showDevices();
        show("settings");
    }

    /** After the desktop sent log book entries. */
    void reloadLog() { ((LogPage) byName.get("log")).reload(); }

    /** The pages the profile shows (Settings always, last, so the setup can always be reached). */
    private void choosePages() {
        java.util.List<Page> show = new java.util.ArrayList<Page>();
        show.add(byName.get("drive"));                      // what a driver glances at: always there, first
        for (String n : Profile.pages(act)) show.add(byName.get(n));
        show.add(byName.get("settings"));
        show.add(byName.get("more"));
        pages = show.toArray(new Page[0]);
    }

    /** After a sync changed the profile: a new page list, keeping the page on screen if it stays. */
    void reloadPages() {
        Page was = pages[cur];
        choosePages();
        int i = java.util.Arrays.asList(pages).indexOf(was);
        if (i < 0) {
            was.onHide();
            cur = 0;
            pages[cur].onShow();
        } else {
            cur = i;
        }
        invalidate();
    }

    void start() { pages[cur].onShow(); handler.post(this); }
    void stop() { pages[cur].onHide(); handler.removeCallbacks(this); }

    void poke() { lastTouch = System.currentTimeMillis(); }

    /**
     * A convoy message while the screen is off: it comes on (at the set brightness) for `ms`, then goes off
     * again unless touched. Returns whether it was off.
     */
    boolean blank() { return blank; }

    boolean wake(long ms) {
        if (!blank) return false;
        setBlank(false);
        awakeUntil = System.currentTimeMillis() + ms;
        return true;
    }

    long idleSeconds() { return (System.currentTimeMillis() - lastTouch) / 1000; }
    int battery() { return batteryPct; }
    float batteryTemp() { return batteryTemp; }
    boolean charging() { return charging; }

    public void run() {
        Intent i = act.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i != null) {
            int level = i.getIntExtra("level", -1), scale = i.getIntExtra("scale", 100);
            batteryPct = level < 0 ? -1 : level * 100 / scale;
            charging = i.getIntExtra("plugged", 0) != 0;
            int t = i.getIntExtra("temperature", Integer.MIN_VALUE);       // tenths of a degree
            batteryTemp = t == Integer.MIN_VALUE ? Float.NaN : t / 10f;
        }
        int mins = Prefs.idleMinutes(act);
        if (mins > 0 && !blank && idleSeconds() >= mins * 60L && System.currentTimeMillis() >= awakeUntil) setBlank(true);
        pages[cur].tick();
        MapsPage maps = mapsPage();
        if (pages[cur] != maps && maps.navigating()) maps.navTick();    // a journey goes on behind any page
        invalidate();
        handler.postDelayed(this, pages[cur].intervalMs());
    }

    private void setBlank(boolean b) {
        blank = b;
        act.maps().setBlank(b);
        act.applyBrightness(b ? 0 : Prefs.brightness(act));
        invalidate();
    }

    void select(int i) {
        if (i == cur) return;
        pages[cur].onHide();
        cur = i;
        pages[cur].onShow();
        for (java.util.Map.Entry<String, Page> e : byName.entrySet())
            if (e.getValue() == pages[cur]) Prefs.sp(act).edit().putString("last_page", e.getKey()).apply();
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        c.save();
        Screen.begin(c, this);
        float e = Screen.extra(this);                       // the side margins of a screen wider than 16:9
        press.begin();
        if (blank) {
            press.end();
            c.drawColor(Color.BLACK);
            c.restore();
            return;
        }
        paint.setShader(null);
        c.drawColor(0xff05070d);
        Bitmap bg = Profile.background(act);
        if (bg != null) {
            rect.set(-e, 0, 1280 + e, 720);
            c.drawBitmap(bg, cover(bg, rect), rect, Theme.BMP);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int) (255 * pages[cur].overlay()), 5, 8, 16));
        c.drawRect(-e, 0, 1280 + e, 720, paint);
        Page pg = pages[cur];
        if (pg.wide()) {
            Wide.setWidth(1280 + 2 * e);                    // the page spans the whole screen
            c.save();
            c.translate(-e, 0);
            pg.draw(c);
            c.restore();
        } else {
            Wide.setWidth(1280);
            pg.draw(c);
        }
        topBar(c, e);
        tabBar(c, e);
        press.end();
        c.restore();
    }

    /** The part of a picture that fills the box without stretching (cropped top and bottom, or the sides). */
    static android.graphics.Rect cover(Bitmap b, RectF box) {
        float k = Math.max(box.width() / b.getWidth(), box.height() / b.getHeight());
        int w = Math.round(box.width() / k), h = Math.round(box.height() / k);
        int l = (b.getWidth() - w) / 2, t = (b.getHeight() - h) / 2;
        return new android.graphics.Rect(l, t, l + w, t + h);
    }

    /** The bar runs the screen's whole width: the badges at its left edge, the clock at its right. */
    private void topBar(Canvas c, float e) {
        paint.setShader(new LinearGradient(0, 0, 0, TOPH, 0xf8060c18, 0xc7060c18, Shader.TileMode.CLAMP));
        c.drawRect(-e, 0, 1280 + e, TOPH, paint);
        paint.setShader(null);
        paint.setColor(Theme.accent(0x47));
        c.drawRect(-e, TOPH - 1, 1280 + e, TOPH, paint);

        // the vehicle's badges, 42 px high, then its name (Profile)
        float x = 16 - e;
        for (Bitmap badge : Profile.badges(act)) {
            float w = badge.getWidth() * 42f / badge.getHeight();
            rect.set(x, 6, x + w, 48);
            c.drawBitmap(badge, null, rect, Theme.BMP);
            x += w + 10;
        }
        String title = Profile.title(act);
        if (!title.isEmpty())
            Theme.text(c, Theme.fit(title, 540 - x, 18, Theme.italic), x + 8, 32, 18, 0xff93a9c8, Theme.italic, Theme.LEFT);

        Dashcam dc = Dashcam.get(act);
        if (dc.recording) {
            boolean blink = (android.os.SystemClock.elapsedRealtime() / 500) % 2 == 0;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blink ? 0xffe4002b : 0xff8a1426);
            c.drawCircle(548, 27, 7, paint);
            rect.set(562, 9, 716, 45);
            boolean mk = dc.marking;
            paint.setColor(mk ? Theme.GOLD : 0xf2101e36);
            c.drawRoundRect(rect, 10, 10, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f);
            paint.setColor(mk ? Theme.GOLD : 0xaae3b341);
            c.drawRoundRect(rect, 10, 10, paint);
            paint.setStyle(Paint.Style.FILL);
            Theme.pressed(c, rect, 10);
            String lab = "MARK";
            if (mk) {
                long sec = (android.os.SystemClock.elapsedRealtime() - dc.markStartReal) / 1000;
                lab = "END  " + sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
            }
            Theme.text(c, lab, 639, 34, 20, mk ? 0xff05070d : Theme.GOLD, Theme.bold, Theme.CENTER);
        }

        float r = 1264 + e;
        // Android 12 and up shows a green dot in this corner while the camera or microphone is in use
        if (Api.SDK >= 31 && (Dashcam.get(act).recording || Dashcam.get(act).testing || Dashcam.get(act).shooting
                || Sound.get(act).listening || Sound.get(act).recording)) r -= 64;
        if (charging) {
            Theme.text(c, "Charging", r, 33, 20, Theme.TEXT, Theme.sans, Theme.RIGHT);
            r -= Theme.width("Charging", 20, Theme.sans) + 12;
        }
        String b = batteryPct < 0 ? "--%" : batteryPct + "%";
        int bc = batteryPct >= 0 && batteryPct < 15 && !charging ? Theme.BAD : Theme.TEXT;
        Theme.text(c, b, r, 33, 20, bc, Theme.sans, Theme.RIGHT);
        r -= Theme.width(b, 20, Theme.sans) + 3;
        if (charging) {
            Theme.bolt(c, r - 16, 14, 24, 0xffffc22e);
            r -= 22;
        }
        if (!Float.isNaN(batteryTemp)) {           // a hot car matters to a phone: amber from 45, red from 55
            r -= 12;
            String t = Thermal.text(batteryTemp);
            Theme.text(c, t, r, 33, 20, Thermal.colour(batteryTemp), Theme.sans, Theme.RIGHT);
            r -= Theme.width(t, 20, Theme.sans);
        }
        r -= 12;
        Date now = new Date();
        String d = date.format(now);
        Theme.text(c, d, r, 31, 18, 0xff8fa3bd, Theme.sans, Theme.RIGHT);
        r -= Theme.width(d, 18, Theme.sans) + 10;
        Theme.text(c, clock.format(now), r, 36, 28, 0xffffffff, Theme.bold, Theme.RIGHT);
    }

    private void tabBar(Canvas c, float e) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xf0050912);
        c.drawRect(-e, TABY, 1280 + e, 720, paint);
        paint.setColor(Theme.accent(0x33));
        c.drawRect(-e, TABY, 1280 + e, TABY + 1, paint);
        java.util.List<Page> bar = bar();
        Page more = byName.get("more");
        boolean hidden = !bar.contains(pages[cur]);         // a page from More shows: More is where it lives
        int n = bar.size();
        float tw = (1280f + 2 * e - 8 * (n + 1)) / n;        // the tabs share the whole width
        for (int i = 0; i < n; i++) {
            Page p = bar.get(i);
            boolean on = p == pages[cur] || hidden && p == more;
            float l = 8 - e + i * (tw + 8);
            rect.set(l, TABY + 9, l + tw, 712);
            Theme.button(c, rect, on, 12);
            int ink = on ? 0xffffffff : 0xffa9bcd6;
            android.graphics.Typeface tf = on ? Theme.bold : Theme.sans;
            float lw = Theme.width(p.label(), 24, tf), iw = 38, x = l + (tw - lw - iw - 12) / 2;     // picture and word, centred together
            if (Icons.draw(c, act, p.icon(), x + iw / 2, TABY + 44, iw, ink)) x += iw + 12;
            else x = l + (tw - lw) / 2;
            Theme.text(c, p.label(), x, TABY + 53, 24, ink, tf, Theme.LEFT);
            boolean dot = p.attention();
            if (p == more) for (Page b : behind()) dot |= b.attention();
            if (dot) {
                paint.setColor(Theme.GOLD);
                c.drawCircle(l + tw - 18, TABY + 27, 7, paint);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            lastTouch = System.currentTimeMillis();
            if (blank) {
                setBlank(false);
                swallow = true;
                return true;
            }
            swallow = false;
        } else if (a == MotionEvent.ACTION_MOVE) {
            lastTouch = System.currentTimeMillis();
        }
        if (swallow) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) swallow = false;
            return true;
        }
        press.touch(e);
        MotionEvent m = MotionEvent.obtain(e);
        m.setLocation(Screen.x(this, e.getX()), Screen.y(this, e.getY()));
        boolean used = false;
        if (a == MotionEvent.ACTION_DOWN && m.getY() < TOPH && m.getX() >= 556 && m.getX() <= 722
                && Dashcam.get(act).recording) {
            Dashcam.get(act).toggleMark();
            used = true;
        } else if (a == MotionEvent.ACTION_DOWN && m.getY() >= TABY) {
            java.util.List<Page> bar = bar();
            int n = bar.size();
            float ex = Screen.extra(this);
            int i = (int) ((m.getX() + ex) / ((1280f + 2 * ex) / n));
            show(bar.get(Math.max(0, Math.min(n - 1, i))));
            used = true;
        } else {
            if (pages[cur].wide()) m.offsetLocation(Screen.extra(this), 0);    // into the page's own width
            used = pages[cur].touch(m);
        }
        m.recycle();
        invalidate();
        return true;
    }
}

package racing.coldshift.dash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Rotation;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.labels.LabelLayer;
import org.mapsforge.map.layer.overlay.Marker;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;

/** Owns the Mapsforge MapView and the control overlay, and shows them above the UI view. */
final class MapHost implements View.OnTouchListener {
    private static boolean factoryReady;

    private final Main act;
    private MapView mv;
    private MapOverlay overlay;
    private TileCache cache;
    private MapFile file;
    private Marker me;
    private MapOverlay.Listener listener;
    private boolean created, blank, showMap, active;
    private boolean headingUp;
    private float bearing = -1;            // direction of travel, -1 until we have moved
    private float markerAngle = -2;        // what the marker bitmap shows (-1 = dot)
    private float mapRot;                  // the map's rotation on screen, degrees
    private byte defaultZoom = 14;         // what the map opens at; the position button goes back to it
    private Location lastFix;
    private Route route;
    private Polyline routeLine;
    private Marker pin;                    // a picked place or the destination
    private Bitmap carIcon;
    private LatLong pinAt;
    // the other cars in a convoy: a marker each, and the angle its bitmap was drawn at
    private final java.util.Map<String, Marker> convoyMarkers = new java.util.HashMap<String, Marker>();
    private final java.util.Map<String, Float> convoyAngles = new java.util.HashMap<String, Float>();
    private GestureDetector gestures;
    String error = "";
    String mapName = "";

    MapHost(Main a) {
        act = a;
        Convoy.get(a).addListener(new Convoy.Listener() {
            public void convoyChanged() { showConvoy(); }
            public void convoyMessage(Convoy.Message m) {}
        });
    }

    static File findMap() {
        for (File dir : Storage.mapsDirs()) {
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            java.util.Arrays.sort(fs);
            for (File f : fs) if (f.getName().endsWith(".map") && !f.getName().startsWith(".")) return f;
        }
        return null;
    }

    boolean hasMap() { return mv != null; }

    MapOverlay overlay() { return overlay; }

    /** Builds the views the first time. */
    private void create() {
        if (created) return;
        created = true;
        FrameLayout root = act.root();
        overlay = new MapOverlay(act);
        File f = findMap();
        if (f == null) {
            error = "No map installed";
        } else {
            try {
                if (!factoryReady) {
                    AndroidGraphicFactory.createInstance(act.getApplication());
                    factoryReady = true;
                }
                mv = new MapView(act);
                mv.setClickable(true);
                mv.getMapScaleBar().setVisible(true);
                mv.setBuiltInZoomControls(false);
                mv.setOnTouchListener(this);
                cache = AndroidUtil.createTileCache(act, "carmap", mv.getModel().displayModel.getTileSize(), 1f,
                    mv.getModel().frameBufferModel.getOverdrawFactor());
                file = new MapFile(f);
                mapName = f.getName();
                // labels in their own layer stay upright when heading-up turns the map
                TileRendererLayer layer = new TileRendererLayer(cache, file, mv.getModel().mapViewPosition,
                    false, false, true, AndroidGraphicFactory.INSTANCE);
                // our own quiet dark theme (assets/maptheme/quiet.xml): the route and car need to stand out on it.
                // The tile cache is not persistent (createTileCache's default), so no tile in an older style survives a launch
                layer.setXmlRenderTheme(new AssetsRenderTheme(act.getAssets(), "maptheme/", "quiet.xml"));
                mv.getLayerManager().getLayers().add(layer);
                mv.getLayerManager().getLayers().add(new LabelLayer(AndroidGraphicFactory.INSTANCE, layer.getLabelStore()));
                if (route != null) drawRoute();
                if (pinAt != null) showPin(pinAt);
                LatLong start = file.startPosition();
                if (start == null) start = file.boundingBox().getCenterPoint();
                mv.setCenter(start);
                byte z = file.getMapFileInfo().startZoomLevel != null ? file.getMapFileInfo().startZoomLevel : 14;
                defaultZoom = (byte) Math.max(z, 13);
                mv.setZoomLevel(defaultZoom);
                headingUp = Prefs.headingUp(act);
                markerAngle = -1;
                me = new Marker(null, markerBitmap(-1), 0, 0);
                me.setVisible(false);
                mv.getLayerManager().getLayers().add(me);
                convoyMarkers.clear();
                convoyAngles.clear();
                showConvoy();
                // the map's slot in the 1280x720 layout the pages draw in, in this screen's pixels
                // as wide as the screen (the Maps page is wide: Wide), less the panel's margins
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(screen().x - sx(28), sy(517),
                    Gravity.TOP | Gravity.LEFT);
                lp.leftMargin = sx(14);
                lp.topMargin = oy() + sy(108);
                mv.setVisibility(View.GONE);
                root.addView(mv, 1, lp);
            } catch (Throwable t) {
                android.util.Log.w("MapHost", "map failed", t);
                error = "Map failed: " + t;
                mv = null;
            }
        }
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(-1, -1);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, mv != null ? 2 : 1, full);
    }

    void show(boolean showMap, MapOverlay.Listener l) {
        create();
        this.showMap = showMap;
        active = true;
        listener = l;
        overlay.setListener(l);
        overlay.hasMap = mv != null;
        overlay.headingUp = headingUp;
        overlay.gpsMode = !showMap;
        overlay.setVisibility(blank ? View.GONE : View.VISIBLE);
        if (mv != null) mv.setVisibility(showMap && !blank ? View.VISIBLE : View.GONE);
        overlay.invalidate();
    }

    void hide() {
        active = false;
        if (!created) return;
        if (mv != null) mv.setVisibility(View.GONE);
        overlay.setVisibility(View.GONE);
    }

    /** The idle blanking draws black in the UI view, so the map must go too. */
    void setBlank(boolean b) {
        blank = b;
        if (!created) return;
        if (b) {
            if (mv != null) mv.setVisibility(View.GONE);
            overlay.setVisibility(View.GONE);
        } else if (active) {
            overlay.setVisibility(View.VISIBLE);
            if (mv != null) mv.setVisibility(showMap ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * The car picture (Profile) 72 px long, front up, with a white outline (so a blue car still shows on
     * the blue route line) and a soft shadow; made once.
     */
    private Bitmap carIcon() {
        if (carIcon == null) carIcon = outlined(Profile.carBody(act), sy(72));
        return carIcon;
    }

    /** A car picture `h` px long with the white outline and shadow; null without a picture. */
    private static Bitmap outlined(Bitmap body, int h) {
        if (body == null) return null;
        Bitmap carIcon;
        int w = Math.max(1, Math.round((float) h * body.getWidth() / body.getHeight()));
        Bitmap scaled = Bitmap.createScaledBitmap(body, w, h, true);
        Bitmap mask = scaled.extractAlpha();
        int pad = 9;
        carIcon = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(carIcon);
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setMaskFilter(new android.graphics.BlurMaskFilter(5, android.graphics.BlurMaskFilter.Blur.NORMAL));
        shadow.setColor(0xcc000000);
        c.drawBitmap(mask, pad, pad + 2, shadow);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setColor(0xffffffff);
        for (int i = 0; i < 16; i++) {                       // the outline: the shape stamped in a 2.5 px ring
            double a = Math.PI * i / 8;
            c.drawBitmap(mask, pad + (float) (2.5 * Math.cos(a)), pad + (float) (2.5 * Math.sin(a)), edge);
        }
        c.drawBitmap(scaled, pad, pad, new Paint(Paint.FILTER_BITMAP_FLAG));
        return carIcon;
    }

    /** A dot until we know the direction of travel, then the car pointing along it. */
    private org.mapsforge.core.graphics.Bitmap markerBitmap(float angle) {
        Bitmap car = angle < 0 ? null : carIcon();
        int size = car == null ? 64 : (int) Math.ceil(Math.hypot(car.getWidth(), car.getHeight()));
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (angle < 0) {
            p.setColor(0xffffffff);
            c.drawCircle(32, 32, 26, p);
            p.setColor(Theme.BLUE);
            c.drawCircle(32, 32, 19, p);
        } else if (car != null) {
            // markers stay upright while the map turns, so this is the angle on screen
            c.rotate(angle, size / 2f, size / 2f);
            c.drawBitmap(car, (size - car.getWidth()) / 2f, (size - car.getHeight()) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        } else {
            // markers stay upright while the map turns, so this is the angle on screen
            c.rotate(angle, 32, 32);
            Path a = new Path();
            a.moveTo(32, 5);
            a.lineTo(55, 57);
            a.lineTo(32, 45);
            a.lineTo(9, 57);
            a.close();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(7);
            p.setColor(0xffffffff);
            c.drawPath(a, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.BLUE);
            c.drawPath(a, p);
        }
        return AndroidGraphicFactory.convertToBitmap(new BitmapDrawable(act.getResources(), b));
    }

    private void updateMarker() {
        float want = bearing < 0 ? -1 : (Math.round((bearing + mapRot) / 3f) * 3f + 360) % 360;
        if (want == markerAngle) return;
        markerAngle = want;
        org.mapsforge.core.graphics.Bitmap old = me.getBitmap();
        me.setBitmap(markerBitmap(want));
        if (old != null) old.decrementRefCount();
    }

    void onFix(Location l, boolean follow) {
        if (overlay != null) {
            overlay.speedKmh = l == null ? -1 : l.getSpeed() * 3.6f;
            overlay.postInvalidate();
        }
        if (mv == null || l == null) return;
        // GPS bearings are noise at walking pace and below; keep the last one while stopped
        if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 1.5f) bearing = l.getBearing();
        lastFix = l;
        LatLong ll = new LatLong(l.getLatitude(), l.getLongitude());
        me.setLatLong(ll);
        if (follow) recenter(ll);
        updateMarker();
        me.setVisible(true);
        me.requestRedraw();
    }

    /** Centres on the position; heading-up turns the map so travel points up and shows more road ahead. */
    private void recenter(LatLong ll) {
        float rot = headingUp && bearing >= 0 ? -bearing : 0;
        setRotation(rot);
        if (rot != 0) ll = ahead(ll, bearing, mapH() * 0.25);
        mv.setCenter(ll);
    }

    private void setRotation(float rot) {
        mapRot = rot;
        mv.rotate(new Rotation(rot, mapW() * 0.5f, mapH() * 0.5f));
        if (overlay != null) {
            overlay.north = rot;
            overlay.postInvalidate();
        }
        if (me != null) {
            updateMarker();
            me.requestRedraw();
        }
        showConvoy();
    }

    // ---- the others in a convoy ----

    /** Each other car in the convoy where it last was: its picture turned along its way, its name under it. */
    void showConvoy() {
        if (mv == null) return;
        Convoy cv = Convoy.get(act);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Convoy.Member m : cv.inConvoy() ? cv.members() : new java.util.ArrayList<Convoy.Member>()) {
            if (m.id.equals(cv.me()) || !"vehicle".equals(m.role) || Double.isNaN(m.lat)) continue;
            seen.add(m.id);
            float angle = (Math.round(((float) m.bearing + mapRot) / 3f) * 3f + 360) % 360 + (m.online ? 0 : 1000);
            Marker mk = convoyMarkers.get(m.id);
            LatLong at = new LatLong(m.lat, m.lon);
            Float was = convoyAngles.get(m.id);
            if (mk == null || was == null || was != angle) {
                int[] off = new int[1];
                org.mapsforge.core.graphics.Bitmap b = convoyBitmap(m, angle % 1000, !m.online, off);
                if (mk == null) {
                    mk = new Marker(at, b, 0, off[0]);
                    mv.getLayerManager().getLayers().add(mk);
                    convoyMarkers.put(m.id, mk);
                } else {
                    org.mapsforge.core.graphics.Bitmap old = mk.getBitmap();
                    mk.setBitmap(b);
                    if (old != null) old.decrementRefCount();
                }
                convoyAngles.put(m.id, angle);
            }
            mk.setLatLong(at);
            mk.requestRedraw();
        }
        for (String id : new java.util.ArrayList<String>(convoyMarkers.keySet())) {
            if (seen.contains(id)) continue;
            mv.getLayerManager().getLayers().remove(convoyMarkers.remove(id));
            convoyAngles.remove(id);
        }
    }

    /** off[0]: how far down to move the bitmap so the car (not the bitmap with its label) sits on the spot. */
    private org.mapsforge.core.graphics.Bitmap convoyBitmap(Convoy.Member m, float angle, boolean faded, int[] off) {
        Bitmap car = outlined(m.car, sy(60));
        int size = car == null ? 48 : (int) Math.ceil(Math.hypot(car.getWidth(), car.getHeight()));
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Theme.bold);
        text.setTextSize(sy(20));
        text.setColor(0xffffffff);
        String name = m.name.length() > 24 ? m.name.substring(0, 23) + "…" : m.name;
        int tw = (int) Math.ceil(text.measureText(name)), lh = sy(30);
        int w = Math.max(size, tw + 16);
        Bitmap b = Bitmap.createBitmap(w, size + lh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (faded) p.setAlpha(110);
        c.save();
        c.rotate(angle, w / 2f, size / 2f);
        if (car != null) {
            c.drawBitmap(car, (w - car.getWidth()) / 2f, (size - car.getHeight()) / 2f, p);
        } else {
            p.setColor(Theme.GOLD);
            c.drawCircle(w / 2f, size / 2f, sy(16), p);
        }
        c.restore();
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(faded ? 0x99070e1b : 0xd9070e1b);
        c.drawRoundRect(new android.graphics.RectF((w - tw) / 2f - 8, size + 2, (w + tw) / 2f + 8, size + lh - 2), 8, 8, bg);
        if (faded) text.setColor(0xff93a9c8);
        c.drawText(name, (w - tw) / 2f, size + lh - 9, text);
        off[0] = lh / 2;
        return AndroidGraphicFactory.convertToBitmap(new BitmapDrawable(act.getResources(), b));
    }

    private int mapW() { return mv.getWidth() > 0 ? mv.getWidth() : sx(1252); }
    private int mapH() { return mv.getHeight() > 0 ? mv.getHeight() : sy(517); }

    /**
     * Layout pixels (the pages draw in 1280x720, scaled to the screen) in this screen's pixels: the G7 is
     * 1280x720 itself, the Y625 854x480. The window covers the whole screen (immersive), so its real size.
     */
    private int sx(int px) { return Math.round(px * k()); }
    private int sy(int px) { return Math.round(px * k()); }

    /** Screen pixels per layout pixel, as Screen.k: the same both ways, so nothing is stretched. */
    private float k() {
        android.graphics.Point s = screen();
        return Math.min(s.x / 1280f, s.y / 720f);
    }

    /** Where the layout starts on a screen wider (or taller) than 16:9, as Screen.ox and oy. */
    private int ox() { return Math.round((screen().x - 1280 * k()) / 2); }
    private int oy() { return Math.round((screen().y - 720 * k()) / 2); }

    private android.graphics.Point screen() {
        android.graphics.Point p = new android.graphics.Point();
        View root = act.getWindow().getDecorView();
        if (root.getWidth() > 0) {
            p.set(root.getWidth(), root.getHeight());      // the window (beside a notch, or not)
            return p;
        }
        act.getWindowManager().getDefaultDisplay().getRealSize(p);
        if (p.y > p.x) p.set(p.y, p.x);               // landscape, whichever way the display reports it
        return p;
    }

    /** The point `px` screen pixels ahead of `ll` along `deg`, at the current zoom. */
    private LatLong ahead(LatLong ll, float deg, double px) {
        byte z = mv.getModel().mapViewPosition.getZoomLevel();
        long size = MercatorProjection.getMapSize(z, mv.getModel().displayModel.getTileSize());
        double x = MercatorProjection.longitudeToPixelX(ll.longitude, size);
        double y = MercatorProjection.latitudeToPixelY(ll.latitude, size);
        double r = Math.toRadians(deg);
        x += Math.sin(r) * px;
        y -= Math.cos(r) * px;
        return new LatLong(MercatorProjection.pixelYToLatitude(y, size), MercatorProjection.pixelXToLongitude(x, size));
    }

    boolean headingUp() { return headingUp; }

    // ---- route ----

    void showRoute(Route r) {
        route = r;
        if (mv != null) drawRoute();
    }

    void clearRoute() {
        route = null;
        if (routeLine != null && mv != null) mv.getLayerManager().getLayers().remove(routeLine);
        routeLine = null;
    }

    /** A pin on a picked place or the destination (null removes it). */
    void showPin(LatLong at) {
        pinAt = at;
        if (mv == null) return;
        if (pin != null) mv.getLayerManager().getLayers().remove(pin);
        pin = null;
        if (at == null) return;
        Bitmap b = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888);
        MapOverlay.turnIcon(new Canvas(b), 24, 34, 28, Route.END, 0, 0xffe4002b, 0);
        pin = new Marker(at, AndroidGraphicFactory.convertToBitmap(new BitmapDrawable(act.getResources(), b)), 0, -30);
        mv.getLayerManager().getLayers().add(pin);
    }

    /** The route as a line under the labels (layer 1, just above the map tiles). */
    private void drawRoute() {
        if (routeLine != null) mv.getLayerManager().getLayers().remove(routeLine);
        org.mapsforge.core.graphics.Paint p = AndroidGraphicFactory.INSTANCE.createPaint();
        p.setColor(Theme.accent(0xd9));
        p.setStrokeWidth(sy(11));
        p.setStyle(org.mapsforge.core.graphics.Style.STROKE);
        p.setStrokeCap(org.mapsforge.core.graphics.Cap.ROUND);
        p.setStrokeJoin(org.mapsforge.core.graphics.Join.ROUND);
        routeLine = new Polyline(p, AndroidGraphicFactory.INSTANCE);
        java.util.List<LatLong> pts = new java.util.ArrayList<LatLong>(route.lat.length);
        for (int i = 0; i < route.lat.length; i++) pts.add(new LatLong(route.lat[i], route.lon[i]));
        routeLine.addPoints(pts);
        mv.getLayerManager().getLayers().add(1, routeLine);
    }

    void toggleHeadingUp(boolean follow) {
        headingUp = !headingUp;
        Prefs.setHeadingUp(act, headingUp);
        if (overlay != null) overlay.headingUp = headingUp;
        if (mv == null) return;
        if (follow && lastFix != null) {
            recenter(new LatLong(lastFix.getLatitude(), lastFix.getLongitude()));
        } else if (!headingUp) {
            setRotation(0);
        }
    }

    void zoom(int d) {
        if (mv == null) return;
        byte z = mv.getModel().mapViewPosition.getZoomLevel();
        mv.setZoomLevel((byte) Math.max(3, Math.min(20, z + d)));
    }

    /** Shows a spot close up (the map stops following the car first). */
    void centerOn(double lat, double lon) {
        if (mv == null) return;
        mv.setCenter(new LatLong(lat, lon));
        if (mv.getModel().mapViewPosition.getZoomLevel() < 16) mv.setZoomLevel((byte) 16);
    }

    void resetZoom() {
        if (mv != null) mv.setZoomLevel(defaultZoom);
    }

    /** Sets the zoom if it differs (each change redraws the tiles). */
    void setZoom(int z) {
        if (mv != null && mv.getModel().mapViewPosition.getZoomLevel() != z) mv.setZoomLevel((byte) z);
    }

    void centerOnMe(Location l) {
        if (mv != null && l != null) recenter(new LatLong(l.getLatitude(), l.getLongitude()));
    }

    /** Drops the map and builds it again from whatever .map is on the card now (after a sync). */
    void reload() {
        if (!created) return;            // the first show() reads the card anyway
        boolean follow = overlay.follow;
        FrameLayout root = act.root();
        if (mv != null) root.removeView(mv);
        root.removeView(overlay);
        destroy();
        mv = null;
        cache = null;
        file = null;
        me = null;
        convoyMarkers.clear();
        convoyAngles.clear();
        routeLine = null;
        pin = null;
        carIcon = null;
        overlay = null;
        error = "";
        mapName = "";
        mapRot = 0;
        markerAngle = -2;
        created = false;
        if (active) {
            show(showMap, listener);
            overlay.follow = follow;
        }
        android.util.Log.i("MapHost", "reloaded: " + (mv != null ? mapName : error));
    }

    void destroy() {
        try {
            if (mv != null) mv.destroyAll();
            if (cache != null) cache.destroy();
            if (file != null) file.close();
            AndroidGraphicFactory.clearResourceMemoryCache();
        } catch (Throwable t) { /* shutting down */ }
    }

    public boolean onTouch(View v, MotionEvent e) {
        act.ui().poke();
        if (gestures == null) {
            gestures = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
                @Override public void onLongPress(MotionEvent ev) { longPress(ev.getX(), ev.getY()); }
            });
        }
        gestures.onTouchEvent(e);
        return false;
    }

    /** Holding the map picks a place. The projection ignores rotation, so the point is turned back first. */
    private void longPress(float x, float y) {
        if (mv == null) return;
        double r = Math.toRadians(-mapRot);
        float cx = mapW() * 0.5f, cy = mapH() * 0.5f;
        double dx = x - cx, dy = y - cy;
        double ux = cx + dx * Math.cos(r) - dy * Math.sin(r), uy = cy + dx * Math.sin(r) + dy * Math.cos(r);
        LatLong ll = mv.getMapViewProjection().fromPixels(ux, uy);
        if (ll == null) return;
        // a fingertip (about 30 px) in metres at this zoom
        double mpp = 40075016.686 * Math.cos(Math.toRadians(ll.latitude))
            / (mv.getModel().displayModel.getTileSize() * Math.pow(2, mv.getModel().mapViewPosition.getZoomLevel()));
        act.ui().mapsPage().onPick(ll.latitude, ll.longitude, 30 * mpp);
    }
}

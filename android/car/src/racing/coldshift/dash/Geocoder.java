package racing.coldshift.dash;

import java.io.File;
import java.util.List;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.PointOfInterest;
import org.mapsforge.map.datastore.Way;
import org.mapsforge.map.reader.MapFile;

/**
 * Names a spot from the map file on the card, offline: a named place on it, the house number and
 * street, or the nearest street, and the suburb and town around it. The map stores names and house
 * numbers but not which street a number belongs to, so a number is only used close to the street.
 * Slow (tens of ms to read the tiles), so callers run it off the UI thread.
 */
final class Geocoder {
    static final class Place {
        String title = "", sub = "";
    }

    private static final double R = 6371000;
    private static final double STREET_M = 120, NUMBER_M = 30, POI_M = 25, SUBURB_M = 4000, TOWN_M = 25000;

    private MapFile file;
    private File opened;

    /** Drops the open map file, so the next lookup reads whatever is on the card now. */
    synchronized void reset() {
        if (file != null) file.close();
        file = null;
        opened = null;
    }

    private MapFile map() {
        File f = MapHost.findMap();
        if (f == null) return null;
        if (file == null || !f.equals(opened)) {
            reset();
            file = new MapFile(f);
            opened = f;
        }
        return file;
    }

    /** `reachM`: how far from the spot a named place still counts (a fingertip on the map at its zoom). */
    synchronized Place reverse(double lat, double lon, double reachM) {
        Place out = new Place();
        MapFile m = map();
        if (m == null || !m.boundingBox().contains(lat, lon)) return out;
        String street = null, number = null, poi = null;
        double reach = Math.max(POI_M, Math.min(reachM, 150));
        double streetD = STREET_M, numberD = NUMBER_M, poiD = reach;

        MapReadResult near = read(m, lat, lon, 17, 1, false);
        for (Way w : near.ways) {
            String name = tag(w.tags, "name");
            if (isRoad(w.tags) && name != null) {
                double d = toLine(lat, lon, w.latLongs);
                if (d < streetD) { streetD = d; street = name; }
                continue;
            }
            String no = tag(w.tags, "addr:housenumber");
            if (no == null && name == null) continue;
            boolean in = w.latLongs.length > 0 && inside(lat, lon, w.latLongs[0]);
            double d = in ? 0 : w.labelPosition != null
                ? dist(lat, lon, w.labelPosition.latitude, w.labelPosition.longitude) : toLine(lat, lon, w.latLongs);
            if (no != null && d < numberD) { numberD = d; number = no; }
            // being inside a big area (a school's grounds) counts for less than pressing on a place's icon
            double pd = in ? 0.8 * reach : d;
            if (name != null && isPoi(w.tags) && pd < poiD) { poiD = pd; poi = name; }
        }
        for (PointOfInterest p : near.pointOfInterests) {
            double d = dist(lat, lon, p.position.latitude, p.position.longitude);
            String no = tag(p.tags, "addr:housenumber"), name = tag(p.tags, "name");
            if (no != null && d < numberD) { numberD = d; number = no; }
            if (name != null && isPoi(p.tags) && d < poiD) { poiD = d; poi = name; }
        }

        String suburb = nearestPlace(read(m, lat, lon, 15, 3, true), lat, lon, false, SUBURB_M);
        String town = nearestPlace(read(m, lat, lon, 11, 2, true), lat, lon, true, TOWN_M);
        String area = suburb == null ? (town == null ? "" : town)
            : town == null || Search.fold(town).equals(Search.fold(suburb)) ? suburb : suburb + ", " + town;

        String road = street == null ? null : number != null ? number + " " + street : street;
        if (poi != null) {
            out.title = poi;
            out.sub = road == null ? area : area.isEmpty() ? road : road + " · " + area;
        } else if (road != null) {
            out.title = road;
            out.sub = area;
        } else {
            out.title = suburb != null ? suburb : town != null ? town : "";
            out.sub = suburb != null && town != null && !area.equals(suburb) ? town : "";
        }
        return out;
    }

    static final class Fuel {
        String name;
        double metres;
    }

    /** The nearest fuel station on the map within `maxM` of the point (its name, or "Fuel station"), or null. */
    String fuel(double lat, double lon, double maxM) {
        Fuel f = nearestFuel(lat, lon, maxM);
        return f == null ? null : f.name;
    }

    /** The nearest fuel station on the map within `maxM` of the point, with how far it is, or null. */
    synchronized Fuel nearestFuel(double lat, double lon, double maxM) {
        MapFile m = map();
        if (m == null || !m.boundingBox().contains(lat, lon)) return null;
        MapReadResult near = read(m, lat, lon, 17, 1, false);
        String best = null;
        double bestD = maxM;
        for (PointOfInterest p : near.pointOfInterests) {
            if (!"fuel".equals(tag(p.tags, "amenity"))) continue;
            double d = dist(lat, lon, p.position.latitude, p.position.longitude);
            if (d < bestD) { bestD = d; best = name(p.tags); }
        }
        for (Way w : near.ways) {
            if (!"fuel".equals(tag(w.tags, "amenity")) || w.latLongs.length == 0) continue;
            double d = inside(lat, lon, w.latLongs[0]) ? 0 : w.labelPosition != null
                ? dist(lat, lon, w.labelPosition.latitude, w.labelPosition.longitude) : toLine(lat, lon, w.latLongs);
            if (d < bestD) { bestD = d; best = name(w.tags); }
        }
        if (best == null) return null;
        Fuel f = new Fuel();
        f.name = best;
        f.metres = bestD;
        return f;
    }

    private static String name(List<Tag> tags) {
        String n = tag(tags, "name"), b = tag(tags, "brand");
        return n != null ? n : b != null ? b : "Fuel station";
    }

    /** The tiles at `zoom` within `reach` tiles of the point. */
    private static MapReadResult read(MapFile m, double lat, double lon, int zoom, int reach, boolean poisOnly) {
        byte z = (byte) zoom;
        int x = MercatorProjection.longitudeToTileX(lon, z), y = MercatorProjection.latitudeToTileY(lat, z);
        int max = Tile.getMaxTileNumber(z);
        Tile ul = new Tile(Math.max(0, x - reach), Math.max(0, y - reach), z, 256);
        Tile lr = new Tile(Math.min(max, x + reach), Math.min(max, y + reach), z, 256);
        MapReadResult r = poisOnly ? m.readPoiData(ul, lr) : m.readMapData(ul, lr);
        if (r == null) r = new MapReadResult();
        if (r.ways == null) r.ways = new java.util.ArrayList<Way>();
        if (r.pointOfInterests == null) r.pointOfInterests = new java.util.ArrayList<PointOfInterest>();
        return r;
    }

    private static String nearestPlace(MapReadResult r, double lat, double lon, boolean town, double maxM) {
        String best = null;
        double bestD = maxM;
        for (PointOfInterest p : r.pointOfInterests) {
            String kind = tag(p.tags, "place"), name = tag(p.tags, "name");
            if (kind == null || name == null) continue;
            boolean big = kind.equals("city") || kind.equals("town");
            boolean small = kind.equals("suburb") || kind.equals("neighbourhood") || kind.equals("quarter")
                || kind.equals("village") || kind.equals("hamlet");
            if (town ? !big : !small) continue;
            double d = dist(lat, lon, p.position.latitude, p.position.longitude) * (kind.equals("city") ? 0.5 : 1);
            if (d < bestD) { bestD = d; best = name; }
        }
        return best;
    }

    static String tag(List<Tag> tags, String key) {
        for (Tag t : tags) if (t.key.equals(key)) return t.value == null || t.value.isEmpty() ? null : t.value;
        return null;
    }

    private static boolean isRoad(List<Tag> tags) {
        String h = tag(tags, "highway");
        return h != null && !h.equals("footway") && !h.equals("path") && !h.equals("cycleway") && !h.equals("steps")
            && !h.equals("track") && !h.equals("bridleway") && !h.equals("platform");
    }

    private static boolean isPoi(List<Tag> tags) {
        for (Tag t : tags) {
            String k = t.key;
            if (k.equals("amenity") || k.equals("shop") || k.equals("tourism") || k.equals("leisure")
                || k.equals("office") || k.equals("aeroway") || k.equals("historic") || k.equals("railway")
                || (k.equals("building") && !"yes".equals(t.value) && !"house".equals(t.value)
                    && !"residential".equals(t.value))) return true;
        }
        return false;
    }

    static double dist(double lat1, double lon1, double lat2, double lon2) { return Nav.dist(lat1, lon1, lat2, lon2); }

    /** Metres from the point to the nearest segment of any of the lines. */
    private static double toLine(double lat, double lon, LatLong[][] lines) {
        double cos = Math.cos(Math.toRadians(lat)), best = Double.MAX_VALUE;
        for (LatLong[] line : lines) {
            for (int i = 0; i + 1 < line.length; i++) {
                double ax = (line[i].longitude - lon) * cos, ay = line[i].latitude - lat;
                double bx = (line[i + 1].longitude - lon) * cos, by = line[i + 1].latitude - lat;
                double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
                double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
                double px = ax + t * dx, py = ay + t * dy;
                best = Math.min(best, Math.sqrt(px * px + py * py));
            }
        }
        return Math.toRadians(best) * R;
    }

    /** Whether the point is inside a closed ring (a building or area). */
    private static boolean inside(double lat, double lon, LatLong[] ring) {
        if (ring.length < 4) return false;
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double yi = ring[i].latitude, yj = ring[j].latitude, xi = ring[i].longitude, xj = ring[j].longitude;
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }
}

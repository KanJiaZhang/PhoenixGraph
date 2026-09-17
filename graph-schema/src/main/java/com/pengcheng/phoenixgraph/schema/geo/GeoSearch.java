package com.pengcheng.phoenixgraph.schema.geo;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * A radius search: the centre, the radius and how the search may spend its effort.
 *
 * <p>A query writes it as JSON, which is what the Gremlin {@code circle} step takes. The radius
 * is in <b>metres</b>:
 *
 * <pre>
 * circle('{"lat":31.2304,"lon":121.4737,"radius":1000}')
 * circle('{"lat":31.2304,"lon":121.4737}', 2000)        // the step argument wins
 * </pre>
 *
 * <p>The level and the cell budget come from the schema's geo property (an index is built at a
 * level, and a covering is bounded by {@code maxCells}); {@code maxResults} caps the search -
 * the step pushes a following {@code limit(k)} into it, so a limited query reads only the k
 * nearest points instead of every point inside the circle.
 *
 * @param center       the centre of the circle
 * @param radiusMeters the radius in metres, greater than zero
 * @param level        the S2 level the covering cells are taken at (0 = the index's own level)
 * @param maxCells     the largest number of cells one query may scan (0 = the index's own budget)
 * @param maxResults   the largest number of hits to return, or 0 for all of them
 */
public record GeoSearch(GeoPoint center, double radiusMeters, int level, int maxCells, int maxResults) {

    /** How many cells a covering may use when neither the schema nor the query says. */
    public static final int DEFAULT_MAX_CELLS = 32;

    /** The largest radius a query may ask for (half the earth's circumference). */
    public static final double MAX_RADIUS_METERS = 20_000_000d;

    public GeoSearch {
        if (center == null) {
            throw new IllegalArgumentException("A geo search needs a centre point");
        }
        if (!(radiusMeters > 0) || !Double.isFinite(radiusMeters) || radiusMeters > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException("A geo search radius must be between 0 and "
                    + MAX_RADIUS_METERS + " metres, got " + radiusMeters);
        }
        if (level < 0) {
            throw new IllegalArgumentException("The geo search level must not be negative, got " + level);
        }
        if (maxCells < 0) {
            throw new IllegalArgumentException("The geo search cell budget must not be negative, got " + maxCells);
        }
        if (maxResults < 0) {
            throw new IllegalArgumentException("The geo search result limit must not be negative, got " + maxResults);
        }
    }

    /** A search with the defaults of the index it runs against. */
    public static GeoSearch of(GeoPoint center, double radiusMeters) {
        return new GeoSearch(center, radiusMeters, 0, 0, 0);
    }

    /**
     * Parses the step's JSON: the centre is required, the radius may come from the text or be
     * given separately, and everything else is optional.
     *
     * @param json          the JSON (or {@code "lat,lon"}) text
     * @param radiusOverride a radius in metres from the step's own argument, or {@code null}
     * @throws IllegalArgumentException when the text holds no usable point or no radius
     */
    public static GeoSearch parse(String json, Double radiusOverride) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("circle expects a point such as "
                    + "{\"lat\":31.2304,\"lon\":121.4737,\"radius\":1000}");
        }
        GeoPoint center;
        Double radius = radiusOverride;
        String trimmed = json.trim();
        if (trimmed.startsWith("{")) {
            Map<?, ?> parsed;
            try {
                parsed = new ObjectMapper().readValue(trimmed, Map.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("circle cannot read the point JSON: " + e.getOriginalMessage(), e);
            }
            center = GeoPoint.from(parsed);
            if (radius == null) {
                radius = number(parsed, "radius", "radiusMeters", "r");
            }
        } else {
            center = GeoPoint.parseOrNull(trimmed);
        }
        if (center == null) {
            throw new IllegalArgumentException("circle cannot read a point from: " + json
                    + " (expected {\"lat\":..,\"lon\":..} or 'lat,lon')");
        }
        if (radius == null) {
            throw new IllegalArgumentException("circle needs a radius in metres: put \"radius\" in the point JSON "
                    + "or pass it as the second argument");
        }
        return new GeoSearch(center, radius, 0, 0, 0);
    }

    /** The same search with a level (used when the index has one and the query did not ask). */
    public GeoSearch withLevel(int newLevel) {
        return new GeoSearch(center, radiusMeters, newLevel, maxCells, maxResults);
    }

    /** The same search with a cell budget. */
    public GeoSearch withMaxCells(int newMaxCells) {
        return new GeoSearch(center, radiusMeters, level, newMaxCells, maxResults);
    }

    /** The same search with a result limit (the {@code limit(k)} of a query). */
    public GeoSearch withMaxResults(int newMaxResults) {
        return new GeoSearch(center, radiusMeters, level, maxCells, newMaxResults);
    }

    private static Double number(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!String.valueOf(entry.getKey()).equalsIgnoreCase(key)) {
                    continue;
                }
                if (entry.getValue() instanceof Number number) {
                    return number.doubleValue();
                }
                if (entry.getValue() instanceof String text) {
                    try {
                        return Double.parseDouble(text.trim());
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}

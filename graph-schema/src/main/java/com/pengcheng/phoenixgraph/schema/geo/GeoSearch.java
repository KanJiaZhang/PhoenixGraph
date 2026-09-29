package com.pengcheng.phoenixgraph.schema.geo;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * A radius search: the centre, the radius and how the search may spend its effort.
 *
 * <p>A query writes the centre as JSON with the full coordinate names and the radius in
 * <b>metres</b> as the step's second argument:
 *
 * <pre>
 * circle('{"latitude":31.2304,"longitude":121.4737}', 1000)
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
     * Parses the step's arguments: the centre JSON (with the full {@code latitude}/{@code longitude}
     * names) and the radius in metres.
     *
     * @param json           the point JSON, or the {@code "lat,lon"} text
     * @param radiusOverride the radius in metres from the step's second argument
     * @throws IllegalArgumentException when the text holds no usable point or no radius
     */
    public static GeoSearch parse(String json, Double radiusOverride) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("circle expects a point such as "
                    + "circle('{\"latitude\":31.2304,\"longitude\":121.4737}', 1000)");
        }
        GeoPoint center;
        String trimmed = json.trim();
        if (trimmed.startsWith("{")) {
            Map<?, ?> parsed;
            try {
                parsed = new ObjectMapper().readValue(trimmed, Map.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("circle cannot read the point JSON: " + e.getOriginalMessage(), e);
            }
            if (number(parsed, "radius", "radiusMeters", "r") != null) {
                throw new IllegalArgumentException("circle takes the radius as its second argument, not in the"
                        + " point JSON: circle('{\"latitude\":..,\"longitude\":..}', radiusMetres)");
            }
            center = coordinates(parsed);
        } else {
            center = GeoPoint.parseOrNull(trimmed);
        }
        if (center == null) {
            throw new IllegalArgumentException("circle cannot read a point from: " + json
                    + " (expected {\"latitude\":..,\"longitude\":..} or 'lat,lon')");
        }
        if (radiusOverride == null) {
            throw new IllegalArgumentException("circle needs a radius in metres as its second argument:"
                    + " circle('{\"latitude\":..,\"longitude\":..}', radiusMetres)");
        }
        return new GeoSearch(center, radiusOverride, 0, 0, 0);
    }

    /**
     * The centre of a circle: the JSON must spell {@code latitude} and {@code longitude} out. The
     * abbreviations {@code lat}/{@code lon} (and {@code lng}) are refused with a message that names
     * the full keys, so a query cannot silently read a point written another way.
     */
    private static GeoPoint coordinates(Map<?, ?> map) {
        Double latitude = number(map, "latitude");
        Double longitude = number(map, "longitude");
        if (latitude == null || longitude == null) {
            if (number(map, "lat") != null || number(map, "lon") != null || number(map, "lng") != null) {
                throw new IllegalArgumentException("circle's point JSON uses 'latitude' and 'longitude', not the"
                        + " abbreviations 'lat'/'lon': circle('{\"latitude\":31.2304,\"longitude\":121.4737}',"
                        + " 1000)");
            }
            return null;
        }
        return GeoPoint.isValid(latitude, longitude) ? new GeoPoint(latitude, longitude) : null;
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

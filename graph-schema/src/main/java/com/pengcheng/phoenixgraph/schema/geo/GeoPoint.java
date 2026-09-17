package com.pengcheng.phoenixgraph.schema.geo;

import java.util.Map;

/**
 * A geographic point (WGS-84 degrees) a geo query works with.
 *
 * <p>Stored values are read tolerantly because a schema may describe a point in more than one
 * way, and a query may be typed by hand:
 *
 * <ul>
 *   <li>a map with {@code lat}/{@code lon} keys ({@code latitude}/{@code longitude} and
 *       {@code lng} are accepted too) - the shape the demos write,</li>
 *   <li>a two element list in GeoJSON order {@code [lon, lat]},</li>
 *   <li>a string {@code "31.2304,121.4737"} ({@code lat,lon}) or the same inside JSON,</li>
 *   <li>another {@link GeoPoint}.</li>
 * </ul>
 *
 * <p>Distances use the haversine formula on a sphere of {@link #EARTH_RADIUS_KM}, which is
 * accurate to a few metres over the ranges a radius query uses - plenty for "shops within one
 * kilometre", and cheap enough to run on every candidate a cell scan returns.
 *
 * @param lat latitude in degrees, -90 to 90
 * @param lon longitude in degrees, -180 to 180
 */
public record GeoPoint(double lat, double lon) {

    /** Mean earth radius in kilometres. */
    public static final double EARTH_RADIUS_KM = 6371.0088d;

    private static final double DEG_TO_RAD = Math.PI / 180d;

    public GeoPoint {
        if (!isValid(lat, lon)) {
            throw new IllegalArgumentException("Invalid geo point: lat=" + lat + ", lon=" + lon
                    + " (lat must be -90..90 and lon -180..180)");
        }
    }

    /** A point, without the constructor's range check being a surprise in a parser. */
    public static GeoPoint of(double lat, double lon) {
        return new GeoPoint(lat, lon);
    }

    /** Whether a coordinate pair is inside the valid ranges. */
    public static boolean isValid(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90d && lat <= 90d && lon >= -180d && lon <= 180d;
    }

    /**
     * Reads a point from a stored or user supplied value.
     *
     * @return the point, or {@code null} when the value does not describe one
     */
    public static GeoPoint from(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof GeoPoint point) {
            return point;
        }
        if (value instanceof Map<?, ?> map) {
            Double lat = number(map, "lat", "latitude", "y");
            Double lon = number(map, "lon", "lng", "longitude", "x");
            if (lat == null || lon == null) {
                // a single element map may itself hold the text form
                for (Object entry : map.values()) {
                    GeoPoint parsed = from(entry);
                    if (parsed != null) {
                        return parsed;
                    }
                }
                return null;
            }
            return isValid(lat, lon) ? new GeoPoint(lat, lon) : null;
        }
        if (value instanceof java.util.List<?> list) {
            // GeoJSON order: [lon, lat]
            if (list.size() != 2) {
                return null;
            }
            GeoPoint asMap = from(list.get(0));
            if (asMap != null) {
                return asMap;
            }
            Double first = number(list.get(0));
            Double second = number(list.get(1));
            if (first == null || second == null) {
                return null;
            }
            return isValid(second, first) ? new GeoPoint(second, first) : null;
        }
        if (value instanceof double[] array && array.length == 2) {
            return isValid(array[0], array[1]) ? new GeoPoint(array[0], array[1]) : null;
        }
        if (value instanceof String text) {
            return parseOrNull(text);
        }
        return null;
    }

    /**
     * Parses {@code "31.2304,121.4737"} or a JSON object such as
     * {@code {"lat":31.2304,"lon":121.4737}} (the radius, if present, is ignored here).
     *
     * @throws IllegalArgumentException when the text does not describe a point
     */
    public static GeoPoint parse(String text) {
        GeoPoint point = parseOrNull(text);
        if (point == null) {
            throw new IllegalArgumentException("Not a geo point: " + text
                    + " (expected {\"lat\":..,\"lon\":..} or 'lat,lon')");
        }
        return point;
    }

    /** Parses a point, returning {@code null} instead of throwing. */
    public static GeoPoint parseOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            try {
                Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(trimmed, Object.class);
                return from(parsed);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return null;
            }
        }
        String[] parts = trimmed.split("[,;\\s]+");
        if (parts.length != 2) {
            return null;
        }
        Double lat = number(parts[0].trim());
        Double lon = number(parts[1].trim());
        if (lat == null || lon == null || !isValid(lat, lon)) {
            return null;
        }
        return new GeoPoint(lat, lon);
    }

    /** The great-circle distance to another point, in kilometres. */
    public double distanceKm(GeoPoint other) {
        double lat1 = lat * DEG_TO_RAD;
        double lat2 = other.lat * DEG_TO_RAD;
        double dLat = (other.lat - lat) * DEG_TO_RAD;
        double dLon = (other.lon - lon) * DEG_TO_RAD;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1d, Math.sqrt(a)));
    }

    @Override
    public String toString() {
        return lat + "," + lon;
    }

    private static Double number(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (String.valueOf(entry.getKey()).equalsIgnoreCase(key)) {
                    Double value = number(entry.getValue());
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}

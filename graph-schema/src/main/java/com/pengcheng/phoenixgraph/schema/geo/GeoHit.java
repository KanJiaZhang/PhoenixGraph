package com.pengcheng.phoenixgraph.schema.geo;

import java.util.Objects;

/**
 * One vertex a {@link GeoSearch} found, with how far it is from the centre.
 *
 * <p>Only the id and the distance come from the index - the search reads the points out of the
 * index table and filters them without touching the vertex table - so the caller reads the
 * vertices it actually returns (batched, with its own projection).
 *
 * @param vertexName the vertex type the hit belongs to
 * @param vertexId   the vertex id
 * @param point      the stored point
 * @param distanceKm the great-circle distance from the search centre, in kilometres
 */
public record GeoHit(String vertexName, Object vertexId, GeoPoint point, double distanceKm)
        implements Comparable<GeoHit> {

    public GeoHit {
        Objects.requireNonNull(vertexName, "vertexName");
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(point, "point");
    }

    /** Nearest first, then by id so a result is stable. */
    @Override
    public int compareTo(GeoHit other) {
        int byDistance = Double.compare(distanceKm, other.distanceKm);
        return byDistance != 0 ? byDistance : String.valueOf(vertexId).compareTo(String.valueOf(other.vertexId));
    }
}

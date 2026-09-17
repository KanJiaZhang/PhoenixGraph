package com.pengcheng.phoenixgraph.schema.geo;

import java.util.Objects;

/**
 * One vertex a {@link GeoSearch} found, with how far it is from the centre.
 *
 * <p>Only the id and the distance come from the index - the search reads the points out of the
 * index table and filters them without touching the vertex table - so the caller reads the
 * vertices it actually returns (batched, with its own projection).
 *
 * @param vertexName     the vertex type the hit belongs to
 * @param vertexId       the vertex id
 * @param point          the stored point
 * @param distanceMeters the great-circle distance from the search centre, in metres
 */
public record GeoHit(String vertexName, Object vertexId, GeoPoint point, double distanceMeters)
        implements Comparable<GeoHit> {

    public GeoHit {
        Objects.requireNonNull(vertexName, "vertexName");
        Objects.requireNonNull(vertexId, "vertexId");
        Objects.requireNonNull(point, "point");
    }

    /** Nearest first, then by id so a result is stable. */
    @Override
    public int compareTo(GeoHit other) {
        int byDistance = Double.compare(distanceMeters, other.distanceMeters);
        return byDistance != 0 ? byDistance : String.valueOf(vertexId).compareTo(String.valueOf(other.vertexId));
    }

    /**
     * Keeps the nearest hits while a scan walks candidates, so a limited query never holds more
     * than that many of them: a max-heap of the wanted size, drained into distance order.
     */
    public static final class TopK {

        private final java.util.PriorityQueue<GeoHit> heap;
        private final int limit;
        private long candidates;

        public TopK(int limit) {
            this.limit = limit;
            this.heap = limit > 0
                    ? new java.util.PriorityQueue<>(limit + 1,
                            (a, b) -> a.compareTo(b) > 0 ? -1 : 1) // farthest first on top
                    : null;
        }

        /** Offers a hit; hits beyond the limit are dropped once the heap is full. */
        public void offer(GeoHit hit) {
            candidates++;
            if (heap == null) {
                return;
            }
            heap.add(hit);
            if (heap.size() > limit) {
                heap.poll();
            }
        }

        /** The hits kept (there is no heap when the limit is 0 - see {@link #collect}). */
        public java.util.List<GeoHit> collect(java.util.List<GeoHit> all) {
            if (heap == null) {
                all.sort(GeoHit::compareTo);
                return java.util.List.copyOf(all);
            }
            java.util.List<GeoHit> hits = new java.util.ArrayList<>(heap);
            hits.sort(GeoHit::compareTo);
            return java.util.List.copyOf(hits);
        }

        /** How many candidates were offered (reported in the log line). */
        public long candidates() {
            return candidates;
        }

        /** Distance order of hits that were collected without a limit. */
        public static java.util.List<GeoHit> copyOf(java.util.List<GeoHit> hits) {
            hits.sort(GeoHit::compareTo);
            return java.util.List.copyOf(hits);
        }

        /** Whether more hits are wanted (a scan can stop once a limited search is full). */
        public boolean wantsMore() {
            return heap != null && heap.size() < limit;
        }
    }
}

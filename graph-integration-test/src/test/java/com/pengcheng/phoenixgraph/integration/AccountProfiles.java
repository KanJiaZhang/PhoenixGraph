package com.pengcheng.phoenixgraph.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The behaviour profile (a vector) and the home location (a geo point) the payment demo gives every
 * account.
 *
 * <p>Both are derived from the account id, so loading the demo twice produces the same graph - a
 * demo answer does not change between loads - and structured so the answers are readable:
 *
 * <ul>
 *   <li>the profile falls into <b>three spending clusters</b> (cash-like, card-like, mixed), so
 *       {@code nearest(k)} returns the accounts of one cluster rather than an arbitrary set;</li>
 *   <li>the location sits in one of <b>four districts</b> around Shanghai, so a 5 km search finds
 *       the accounts of a neighbourhood.</li>
 * </ul>
 */
public final class AccountProfiles {

    /** Dimensions of the profile; the schema declares eight. */
    public static final int DIMENSIONS = 8;

    /** The three spending clusters, as the value of every second dimension. */
    private static final double[][] CLUSTERS = {
            {0.9, 0.1},   // cash-like: every even dimension high
            {0.1, 0.9},   // card-like: every odd dimension high
            {0.5, 0.5},   // mixed
    };

    /** The district centres, as {lat, lon}. */
    private static final double[][] DISTRICTS = {
            {31.2304, 121.4737}, // People's Square
            {31.1940, 121.4370}, // Xujiahui
            {31.2397, 121.4998}, // Lujiazui
            {31.2290, 121.4450}, // Jing'an
    };

    private AccountProfiles() {
    }

    /** The 8-dimensional behaviour profile of an account, in one of three clusters. */
    public static List<Double> vectorOf(Object id) {
        int hash = Math.floorMod(String.valueOf(id).hashCode(), 100_000);
        double[] cluster = CLUSTERS[hash % CLUSTERS.length];
        List<Double> vector = new ArrayList<>(DIMENSIONS);
        for (int dimension = 0; dimension < DIMENSIONS; dimension++) {
            // a small deterministic jitter, so the members of a cluster are not identical
            double jitter = ((hash / (dimension + 1)) % 100) / 1000.0;
            vector.add(Math.round((cluster[dimension % 2] + jitter) * 1000) / 1000.0);
        }
        return vector;
    }

    /** The home location of an account, in one of four districts (a small deterministic offset). */
    public static Map<String, Double> locationOf(Object id) {
        int hash = Math.floorMod(String.valueOf(id).hashCode(), 100_000);
        double[] district = DISTRICTS[hash % DISTRICTS.length];
        // up to ~1.5 km from the centre, so a 5 km radius covers a district and its neighbours
        double latOffset = ((hash % 30) - 15) / 1000.0;
        double lonOffset = (((hash / 30) % 30) - 15) / 1000.0;
        Map<String, Double> point = new LinkedHashMap<>();
        point.put("lat", Math.round((district[0] + latOffset) * 1_000_000) / 1_000_000.0);
        point.put("lon", Math.round((district[1] + lonOffset) * 1_000_000) / 1_000_000.0);
        return point;
    }
}

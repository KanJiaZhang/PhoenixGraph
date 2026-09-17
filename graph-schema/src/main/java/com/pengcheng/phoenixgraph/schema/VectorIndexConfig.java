package com.pengcheng.phoenixgraph.schema;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Index configuration of a vector property (see {@link PropertySchema#isVector()}).
 *
 * <p>Declared on the property in the schema JSON, e.g.
 * <pre>
 * { "id": 2, "name": "embedding", "type": { "list": "DOUBLE" }, "isVector": true,
 *   "metric": "COSINE", "m": 15, "efConstruction": 100, "efSearch": 50 }
 * </pre>
 * Everything except {@code isVector} is optional: the defaults below are used when
 * a field is absent. {@code metric}/{@code m}/{@code efConstruction} describe how
 * the graph is built; {@code efSearch} is the default candidate list size of a
 * query (a query may still ask for a wider one, see {@code nearest(k, ef)}).
 *
 * @param metric         distance metric ({@code L2}, {@code COSINE} or {@code INNER_PRODUCT})
 * @param m              neighbours kept per layer while inserting
 * @param efConstruction candidate list size while inserting
 * @param efSearch       default candidate list size while searching
 */
public record VectorIndexConfig(String metric, int m, int efConstruction, int efSearch) {

    /** Metrics the index understands. */
    public static final Set<String> METRICS = Set.of("L2", "COSINE", "INNER_PRODUCT");

    public static final int DEFAULT_M = 15;
    public static final int DEFAULT_EF_CONSTRUCTION = 100;
    public static final int DEFAULT_EF_SEARCH = 50;

    /** {@code L2} distance, {@code m = 15}, {@code efConstruction = 100}, {@code efSearch = 50}. */
    public static final VectorIndexConfig DEFAULT =
            new VectorIndexConfig("L2", DEFAULT_M, DEFAULT_EF_CONSTRUCTION, DEFAULT_EF_SEARCH);

    public VectorIndexConfig {
        metric = normalizeMetric(metric);
        if (m < 1) {
            throw new IllegalArgumentException("vector index m must be >= 1, got " + m);
        }
        if (efConstruction < 1) {
            throw new IllegalArgumentException("efConstruction must be >= 1, got " + efConstruction);
        }
        if (efSearch < 1) {
            throw new IllegalArgumentException("efSearch must be >= 1, got " + efSearch);
        }
    }

    /** Fills the absent fields with the defaults. */
    public static VectorIndexConfig of(String metric, Integer m, Integer efConstruction, Integer efSearch) {
        return new VectorIndexConfig(
                metric == null || metric.isBlank() ? DEFAULT.metric() : metric,
                m == null ? DEFAULT_M : m,
                efConstruction == null ? DEFAULT_EF_CONSTRUCTION : efConstruction,
                efSearch == null ? DEFAULT_EF_SEARCH : efSearch);
    }

    /** Uppercased metric name, validated against {@link #METRICS}. */
    public static String normalizeMetric(String metric) {
        String name = metric == null || metric.isBlank()
                ? DEFAULT.metric()
                : metric.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!METRICS.contains(name)) {
            throw new IllegalArgumentException("Unknown vector metric '" + metric
                    + "', expected one of " + METRICS);
        }
        return name;
    }

    public VectorIndexConfig withMetric(String metric) {
        return new VectorIndexConfig(metric, m, efConstruction, efSearch);
    }

    public VectorIndexConfig withEfSearch(int efSearch) {
        return new VectorIndexConfig(metric, m, efConstruction, efSearch);
    }

    /** Compact description for logs. */
    public String describe() {
        return "metric=" + metric + ", m=" + m + ", efConstruction=" + efConstruction
                + ", efSearch=" + efSearch;
    }

    @Override
    public String toString() {
        return "VectorIndexConfig{" + describe() + '}';
    }

    /** Equality helper used by the tests: same configuration, ignoring the record identity. */
    public boolean sameAs(VectorIndexConfig other) {
        return other != null && Objects.equals(metric, other.metric) && m == other.m
                && efConstruction == other.efConstruction && efSearch == other.efSearch;
    }
}

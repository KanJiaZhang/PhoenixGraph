package com.pengcheng.phoenixgraph.hnsw;

/**
 * Distance metric used to order vectors in the HNSW graph.
 *
 * <p>All metrics return a <em>distance</em>: the smaller the value the closer
 * the two vectors. {@link #L2} returns the squared euclidean distance (the
 * square root is monotonic and therefore irrelevant for ranking), {@link #COSINE}
 * returns {@code 1 - cosine similarity} and {@link #INNER_PRODUCT} the negated
 * dot product.
 */
public enum HnswMetric {

    /** Squared euclidean distance. */
    L2 {
        @Override
        public double distance(float[] a, float[] b) {
            double sum = 0;
            for (int i = 0; i < a.length; i++) {
                double d = a[i] - b[i];
                sum += d * d;
            }
            return sum;
        }
    },

    /** {@code 1 - cosine similarity}, in {@code [0, 2]}. */
    COSINE {
        @Override
        public double distance(float[] a, float[] b) {
            double dot = 0;
            double na = 0;
            double nb = 0;
            for (int i = 0; i < a.length; i++) {
                dot += (double) a[i] * b[i];
                na += (double) a[i] * a[i];
                nb += (double) b[i] * b[i];
            }
            if (na == 0 || nb == 0) {
                return 1.0;
            }
            return 1.0 - dot / (Math.sqrt(na) * Math.sqrt(nb));
        }
    },

    /** Negated dot product (maximises the inner product). */
    INNER_PRODUCT {
        @Override
        public double distance(float[] a, float[] b) {
            double dot = 0;
            for (int i = 0; i < a.length; i++) {
                dot += (double) a[i] * b[i];
            }
            return -dot;
        }
    };

    /** Distance between two equally sized vectors; lower means closer. */
    public abstract double distance(float[] a, float[] b);

    /** Parses a metric name ({@code l2}, {@code cosine}, {@code ip}), case-insensitively. */
    public static HnswMetric of(String name) {
        if (name == null || name.isBlank()) {
            return L2;
        }
        return switch (name.trim().toUpperCase().replace('-', '_')) {
            case "L2", "EUCLIDEAN", "EUCLID" -> L2;
            case "COSINE", "COS" -> COSINE;
            case "IP", "INNER_PRODUCT", "DOT" -> INNER_PRODUCT;
            default -> throw new IllegalArgumentException("Unknown HNSW metric: " + name);
        };
    }
}

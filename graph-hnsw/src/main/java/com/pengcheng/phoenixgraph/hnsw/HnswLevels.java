package com.pengcheng.phoenixgraph.hnsw;

/**
 * The layer scheme of an HNSW index.
 *
 * <p>A dataset of {@code size} vectors gets {@code ceil(log2(size)) + 1} layers,
 * numbered {@code 0} (bottom, every vertex is present) up to {@code topLevel =
 * ceil(log2(size))}. For {@code 4096 = 2^12} that is the 13 layers {@code 0..12},
 * materialised as the 13 bidirectional edge classes
 * {@code vector_0, vector_1, ... vector_12}.
 *
 * <p>A vertex's top layer follows the standard exponentially decaying draw
 * {@code P(level = l) = 2^-(l+1)}, i.e. {@code level = floor(-log2(u))} for
 * {@code u} uniform in {@code (0, 1]}. With that law ~{@code size / 2^l} vertices
 * reach layer {@code l}, so layer {@code topLevel} holds about
 * {@code size / 2^log2(size)} = 1 vertex - the single entry point a search starts
 * from. The draw is derived from a hash of the vertex id, so the same id always
 * lands on the same layer (a rebuild is reproducible and a second process agrees).
 */
public final class HnswLevels {

    /** Edge class name prefix: layer {@code l} is stored under {@code vector_<l>}. */
    public static final String EDGE_PREFIX = "vector_";

    /** Highest number of entry points kept in the metadata record. */
    public static final int MAX_ENTRY_POINTS = 10;

    private static final double LN2 = Math.log(2);

    private HnswLevels() {
    }

    /** Number of layers for a dataset of {@code size} vectors ({@code ceil(log2(size)) + 1}). */
    public static int layerCount(long size) {
        return topLevel(size) + 1;
    }

    /**
     * Index of the top layer for a dataset of {@code size} vectors
     * ({@code ceil(log2(size))}; {@code 4096 -> 12}).
     */
    public static int topLevel(long size) {
        if (size <= 1) {
            return 0;
        }
        int level = (int) Math.ceil(Math.log(size) / LN2);
        // guard against floating point noise on exact powers of two
        if (level > 0 && (1L << (level - 1)) >= size) {
            level--;
        }
        return Math.max(1, level);
    }

    /** Edge class name of a layer: {@code 0 -> vector_0}. */
    public static String edgeName(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("level must be >= 0, got " + level);
        }
        return EDGE_PREFIX + level;
    }

    /** Layer of an edge class name, or {@code -1} when the name is not an HNSW layer. */
    public static int levelOfEdgeName(String edgeName) {
        if (edgeName == null || !edgeName.startsWith(EDGE_PREFIX)) {
            return -1;
        }
        try {
            int level = Integer.parseInt(edgeName.substring(EDGE_PREFIX.length()));
            return level < 0 ? -1 : level;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Whether the edge class name follows the HNSW layer naming scheme. */
    public static boolean isLayerEdge(String edgeName) {
        return levelOfEdgeName(edgeName) >= 0;
    }

    /**
     * Top layer of a vertex: the geometric draw described above, capped at
     * {@code topLevel}. Deterministic for a given id.
     */
    public static int levelFor(Object id, int topLevel) {
        long hash = hash64(String.valueOf(id));
        double u = ((hash >>> 11) + 1) * 0x1.0p-53; // (0, 1]
        int level = (int) Math.floor(-Math.log(u) / LN2);
        if (level < 0) {
            level = 0;
        }
        return Math.min(level, topLevel);
    }

    /** Deterministic splitmix64 hash of the string form of the id. */
    static long hash64(String value) {
        long h = 0x9E3779B97F4A7C15L ^ value.hashCode();
        for (int i = 0; i < value.length(); i++) {
            h = (h ^ value.charAt(i)) * 0x100000001B3L; // FNV-1a step
            h ^= h >>> 29;
        }
        h += 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}

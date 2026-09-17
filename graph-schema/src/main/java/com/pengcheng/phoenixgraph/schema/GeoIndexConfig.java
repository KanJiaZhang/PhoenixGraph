package com.pengcheng.phoenixgraph.schema;

/**
 * The settings of a geo index declared with {@code isGeo} on a property.
 *
 * <p>A geo index stores each point's Google S2 cell id, so a radius query becomes a range
 * scan over a handful of cells (see the RocksDB storage). The two settings decide the
 * resolution of that index:
 *
 * @param level    the S2 level a point is indexed at; level 16 is a cell of roughly 200 m,
 *                 so a point is one row of the index. A finer level means more cells to scan
 *                 for one query, a coarser one puts more points in each cell
 * @param maxCells the largest number of cells one query may cover. The circle is approximated
 *                 by cells at the query's level and this bounds that covering, so a query can
 *                 never degenerate into a table scan
 */
public record GeoIndexConfig(int level, int maxCells) {

    /** Level 16 (cells of about 200 m). */
    public static final int DEFAULT_LEVEL = 16;

    /** At most 32 cells per query by default. */
    public static final int DEFAULT_MAX_CELLS = 32;

    /** The default geo index settings. */
    public static final GeoIndexConfig DEFAULT = new GeoIndexConfig(DEFAULT_LEVEL, DEFAULT_MAX_CELLS);

    /** The finest level an index may use (a few centimetres). */
    public static final int MIN_LEVEL = 1;

    /** The coarsest level that still makes sense for a radius query. */
    public static final int MAX_LEVEL = 20;

    public GeoIndexConfig {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "Geo index level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + ", got " + level);
        }
        if (maxCells <= 0) {
            throw new IllegalArgumentException("Geo index maxCells must be positive, got " + maxCells);
        }
    }

    /** The default settings (level 16, at most 32 cells). */
    public static GeoIndexConfig defaults() {
        return DEFAULT;
    }

    public GeoIndexConfig withLevel(int newLevel) {
        return new GeoIndexConfig(newLevel, maxCells);
    }

    public GeoIndexConfig withMaxCells(int newMaxCells) {
        return new GeoIndexConfig(level, newMaxCells);
    }
}

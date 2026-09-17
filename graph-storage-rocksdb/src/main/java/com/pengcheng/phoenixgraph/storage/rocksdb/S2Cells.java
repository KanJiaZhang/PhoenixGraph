package com.pengcheng.phoenixgraph.storage.rocksdb;

import com.google.common.geometry.S1Angle;
import com.google.common.geometry.S2Cap;
import com.google.common.geometry.S2CellId;
import com.google.common.geometry.S2CellUnion;
import com.google.common.geometry.S2LatLng;
import com.google.common.geometry.S2RegionCoverer;
import com.pengcheng.phoenixgraph.schema.geo.GeoPoint;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;

import java.util.ArrayList;
import java.util.List;

/**
 * Google S2 cell arithmetic for the geo index.
 *
 * <p>A geo index stores, for every point, the S2 cell id it falls in at a fixed level. Cell ids
 * are nested - every descendant of a cell has an id inside {@code [rangeMin, rangeMax]} of that
 * cell - so a radius query does not have to look at a whole table: it covers the circle with a
 * handful of cells and scans exactly the id ranges of those cells, and the distances of the
 * candidate points are then computed for real. The covering is a superset of the circle (a cell
 * may stick out of it), which is why the distance check still has to run.
 *
 * <p>The level decides the trade-off: cells of about 200 m (level 16) make one cell a couple of
 * points, and a circle is covered at a level that keeps the covering small - for the one
 * kilometre of the demo that is level 14, a circle of roughly nine to sixteen cells.
 */
public final class S2Cells {

    /** The level an index uses when the schema does not say: cells of roughly 200 m. */
    public static final int DEFAULT_INDEX_LEVEL = 16;

    /** Average edge length of a level-16 cell, in kilometres (S2 is not uniform, this is the mean). */
    private static final double LEVEL_16_EDGE_KM = 0.2d;

    private S2Cells() {
    }

    /** The S2 cell id of a point at a level. */
    public static long cellId(GeoPoint point, int level) {
        S2CellId cellId = S2CellId.fromLatLng(S2LatLng.fromDegrees(point.lat(), point.lon()));
        return cellId.parent(clampLevel(level)).id();
    }

    /** The level of an S2 cell id. */
    public static int levelOf(long cellId) {
        return new S2CellId(cellId).level();
    }

    /**
     * The level to cover a circle of the given radius at: the cell edge is roughly the radius,
     * so the covering stays small while a cell is not much bigger than the search area.
     */
    public static int levelForRadius(double radiusKm) {
        if (!(radiusKm > 0)) {
            return DEFAULT_INDEX_LEVEL;
        }
        double steps = Math.log(radiusKm / LEVEL_16_EDGE_KM) / Math.log(2d);
        return clampLevel((int) Math.round(DEFAULT_INDEX_LEVEL - steps));
    }

    /**
     * One cell of a covering: the inclusive id range of the cell (its own id up to the last
     * descendant) and the level it was taken at.
     */
    public record CellRange(long min, long max, int level) {

        /** Whether a cell id (a point's, at whatever level) falls inside this cell. */
        public boolean contains(long cellId) {
            return Long.compareUnsigned(cellId, min) >= 0 && Long.compareUnsigned(cellId, max) <= 0;
        }
    }

    /**
     * The cells that cover a circle, as inclusive id ranges.
     *
     * <p>Every point the index holds inside the circle has its cell id inside one of these
     * ranges, so scanning them finds all candidates (and possibly a few points just outside,
     * which the distance check drops). The covering is <b>never finer than the index</b>: a
     * point stored as a level 16 cell is a descendant of any coarser covering cell, so its key
     * still falls inside that cell's range, while a finer covering cell would narrow the scan
     * past it.
     */
    public static List<CellRange> coveringRanges(GeoSearch search, int indexLevel, int maxCells) {
        GeoPoint center = search.center();
        int stored = indexLevel > 0 ? clampLevel(indexLevel) : DEFAULT_INDEX_LEVEL;
        int level = search.level() > 0 ? Math.min(clampLevel(search.level()), stored)
                : Math.min(stored, levelForRadius(search.radiusKm()));
        int budget = search.maxCells() > 0 ? search.maxCells() : maxCells;
        S1Angle angle = S1Angle.radians(search.radiusKm() / GeoPoint.EARTH_RADIUS_KM);
        S2Cap cap = S2Cap.fromAxisAngle(
                S2LatLng.fromDegrees(center.lat(), center.lon()).toPoint(), angle);
        S2RegionCoverer.Builder coverer = S2RegionCoverer.builder()
                .setMinLevel(level)
                .setMaxLevel(level);
        if (budget > 0) {
            coverer.setMaxCells(budget);
        }
        S2CellUnion covering = coverer.build().getCovering(cap);
        List<CellRange> ranges = new ArrayList<>(covering.size());
        for (S2CellId cell : covering.cellIds()) {
            ranges.add(new CellRange(cell.rangeMin().id(), cell.rangeMax().id(), cell.level()));
        }
        ranges.sort((a, b) -> Long.compareUnsigned(a.min(), b.min()));
        return ranges;
    }

    /** The level a search scans at (its own, the index's, or the one its radius asks for). */
    public static int levelOf(GeoSearch search, int indexLevel) {
        if (search.level() > 0) {
            return clampLevel(search.level());
        }
        return indexLevel > 0 ? clampLevel(indexLevel) : levelForRadius(search.radiusKm());
    }

    private static int clampLevel(int level) {
        return Math.max(0, Math.min(30, level));
    }
}

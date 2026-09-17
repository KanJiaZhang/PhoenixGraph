package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The layout of the aggregated Aerospike edge tables ({@code Edge_Out} / {@code Edge_In}).
 *
 * <p>A vertex with hundreds of millions of edges cannot keep them in its record (the inline
 * {@code ve_<schema>_<dir>} bin has a degree limit and one record has a size limit). Its edges
 * are written by a bulk import into tables of their own, grouped by <b>creation time</b>, and
 * indexed by two further levels:
 *
 * <pre>
 * level 0   vertexId | min | max | 0     edges                 (up to CHUNK_SIZE edges, a CDT map)
 * level 1   vertexId | min | max | 1     the (min,max) of its children (a CDT list)
 * level 2   vertexId | min | max | 2     the (min,max) of its children
 * ...                                    one root, so levels keep growing until a single chunk remains
 * </pre>
 *
 * <p>A chunk's key <b>is</b> its time span, so a child's key is reconstructed from the
 * {@code (min, max)} pair its parent keeps - the parent value only has to hold those pairs, and
 * a query descends the levels by keeping the children whose span intersects the window it was
 * asked for. That is what makes "the 500 edges of this hub between two dates" a handful of reads
 * instead of a scan of a hundred million edges.
 *
 * <p>Every directed edge is stored twice - in the {@code Edge_Out} table of its source and in
 * the {@code Edge_In} table of its destination - while an undirected edge uses the single
 * {@code Edge} table of both endpoints. A hub marker record per (edge class, direction, vertex)
 * says whether the aggregated tables are the place to look, and how many levels they have.
 */
public final class AerospikeEdgeChunks {

    /** How many edges one level-0 chunk holds (and how many children one level holds). */
    public static final int DEFAULT_CHUNK_SIZE = 2_000;

    /** How many levels a bulk import builds at most (2000^8 edges is far beyond any graph). */
    public static final int MAX_LEVEL = 8;

    /** The level of a hub marker record (never a chunk: it keeps the table's shape). */
    public static final int HUB_LEVEL = -1;

    /** Bins of a chunk record. */
    public static final String VERTEX_BIN = "v";
    public static final String LEVEL_BIN = "l";
    public static final String MIN_BIN = "mn";
    public static final String MAX_BIN = "mx";
    public static final String COUNT_BIN = "n";
    /** Level 0: the edges themselves, a CDT map edgeId -> serialized edge value. */
    public static final String EDGES_BIN = "e";
    /** Level 1+: the {@code [min, max]} span of every child, a CDT list. */
    public static final String CHILDREN_BIN = "k";

    private AerospikeEdgeChunks() {
    }

    // --- table names ---

    /** The table (set) holding the outgoing edges of a graph, per edge class. */
    public static String outSet(String baseSet, EdgeSchema edgeSchema) {
        return baseSet + "_edge_out_" + edgeSchema.id();
    }

    /** The table (set) holding the incoming edges of a graph, per edge class. */
    public static String inSet(String baseSet, EdgeSchema edgeSchema) {
        return baseSet + "_edge_in_" + edgeSchema.id();
    }

    /** The single table of an undirected/bidirectional edge class. */
    public static String bothSet(String baseSet, EdgeSchema edgeSchema) {
        return baseSet + "_edge_" + edgeSchema.id();
    }

    /** The table holding the hub markers of a graph. */
    public static String hubSet(String baseSet) {
        return baseSet + "_edgeheads";
    }

    /** The table an edge class and a direction are written to. */
    public static String edgeSet(String baseSet, EdgeSchema edgeSchema, int directionCode) {
        if (edgeSchema.direction() != EdgeDirection.DIRECTED) {
            return bothSet(baseSet, edgeSchema);
        }
        return directionCode == AerospikeEdgeStore.EDGE_DIR_IN
                ? inSet(baseSet, edgeSchema) : outSet(baseSet, edgeSchema);
    }

    /** The sets an edge class needs (one for an undirected class, two for a directed one). */
    public static List<String> setsOf(String baseSet, EdgeSchema edgeSchema) {
        if (edgeSchema.direction() != EdgeDirection.DIRECTED) {
            return List.of(bothSet(baseSet, edgeSchema));
        }
        return List.of(outSet(baseSet, edgeSchema), inSet(baseSet, edgeSchema));
    }

    // --- keys ---

    /** The key of a chunk: {@code vertexId|min|max|level}. */
    public static String chunkKey(Object vertexId, long min, long max, int level) {
        return vertexId + "|" + min + "|" + max + "|" + level;
    }

    /** The key of the hub marker of a vertex, an edge class and a direction. */
    public static String hubKey(EdgeSchema edgeSchema, int directionCode, Object vertexId) {
        return edgeSchema.id() + "|" + directionCode + "|" + vertexId;
    }

    /** A parsed chunk key. */
    public record ParsedKey(Object vertexId, long min, long max, int level) {
    }

    /** Parses a chunk key; a key that is not one returns {@code null}. */
    public static ParsedKey parseKey(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split("\\|");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new ParsedKey(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- building the levels (pure: the bulk import writes what this returns) ---

    /** An edge as the bulk import knows it before it is written. */
    public record EdgeEntry(String edgeId, Object otherVertexId, long createdTime, long lastUpdatedTime,
                            List<Object> value) {
    }

    /** One record to write: its span, its level, and either its edges or its children's spans. */
    public record ChunkRecord(int level, long min, long max, List<EdgeEntry> edges, List<long[]> children) {

        /** The key of this record for a vertex. */
        public String key(Object vertexId) {
            return chunkKey(vertexId, min, max, level);
        }
    }

    /**
     * Groups the edges of one vertex into a chunk and builds the levels above it, bottom-up,
     * until a single root chunk remains.
     *
     * @param edges      the edges of one vertex, any order (they are sorted by creation time)
     * @param chunkSize  how many edges one level-0 chunk holds
     * @return every record to write, level 0 first
     */
    public static List<ChunkRecord> build(List<EdgeEntry> edges, int chunkSize) {
        if (edges.isEmpty()) {
            return List.of();
        }
        int size = chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
        List<EdgeEntry> sorted = new ArrayList<>(edges);
        sorted.sort(Comparator.comparingLong(EdgeEntry::createdTime).thenComparing(EdgeEntry::edgeId));

        List<ChunkRecord> records = new ArrayList<>();
        List<ChunkRecord> level = new ArrayList<>();
        for (int start = 0; start < sorted.size(); start += size) {
            List<EdgeEntry> chunk = List.copyOf(sorted.subList(start, Math.min(start + size, sorted.size())));
            level.add(new ChunkRecord(0, chunk.get(0).createdTime(),
                    chunk.get(chunk.size() - 1).createdTime(), chunk, null));
        }
        records.addAll(level);

        int levelNumber = 0;
        while (level.size() > 1 && levelNumber < MAX_LEVEL) {
            levelNumber++;
            List<ChunkRecord> parents = new ArrayList<>();
            for (int start = 0; start < level.size(); start += size) {
                List<ChunkRecord> children = level.subList(start, Math.min(start + size, level.size()));
                List<long[]> spans = new ArrayList<>(children.size());
                long min = Long.MAX_VALUE;
                long max = Long.MIN_VALUE;
                for (ChunkRecord child : children) {
                    spans.add(new long[]{child.min(), child.max()});
                    min = Math.min(min, child.min());
                    max = Math.max(max, child.max());
                }
                parents.add(new ChunkRecord(levelNumber, min, max, null, List.copyOf(spans)));
            }
            records.addAll(parents);
            level = parents;
        }
        return List.copyOf(records);
    }

    /** How many levels {@link #build} produced for the given number of edges. */
    public static int levelsFor(long edgeCount, int chunkSize) {
        int size = chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
        int levels = 1;
        long capacity = size;
        while (edgeCount > capacity && levels < MAX_LEVEL) {
            capacity *= size;
            levels++;
        }
        return levels;
    }

    /** The children of a record that overlap a window, in time order. */
    public static List<long[]> selectChildren(List<long[]> children, long from, long to) {
        List<long[]> selected = new ArrayList<>();
        for (long[] span : children) {
            if (span[0] <= to && span[1] >= from) {
                selected.add(span);
            }
        }
        selected.sort(Comparator.comparingLong(span -> span[0]));
        return selected;
    }
}

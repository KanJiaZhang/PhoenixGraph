package com.pengcheng.phoenixgraph.storage.rocksdb.inline;

import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbBytes;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbIdCodec;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyCodec;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredEdge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The stored form of the edges of one vertex and one edge class.
 *
 * <pre>
 * binCount
 * per bin:  binName, edgeCount
 *           per edge: edgeId, otherVertexId(type+text), createdTime, lastUpdatedTime,
 *                     propertyCount, [propertyId, value, createdTime, lastUpdatedTime] ...
 * </pre>
 *
 * <p>A bin is the "unified column" of this scheme: every edge of a class and direction
 * attached to a vertex lives in one value, named by the Aerospike bin name
 * ({@link RocksDbEdgeBins}). The whole map is read and rewritten when one edge changes -
 * which is exactly what makes the degree limits enforceable, because the store can see
 * every edge of that direction when it decides which ones to drop.
 */
final class RocksDbInlineEdgeCodec {

    private RocksDbInlineEdgeCodec() {
    }

    /** One bin: its name and the edges it holds, keyed by edge id. */
    record Bin(String name, Map<String, RocksDbStoredEdge> edges) {

        Bin {
            edges = new LinkedHashMap<>(edges);
        }
    }

    /** Encodes the bins of one vertex record. */
    static byte[] encode(List<Bin> bins) {
        RocksDbBytes.Writer writer = new RocksDbBytes.Writer();
        writer.writeInt(bins.size());
        for (Bin bin : bins) {
            writer.writeString(bin.name());
            writer.writeInt(bin.edges().size());
            for (Map.Entry<String, RocksDbStoredEdge> entry : bin.edges().entrySet()) {
                writeEdge(writer, entry.getKey(), entry.getValue());
            }
        }
        return writer.toByteArray();
    }

    private static void writeEdge(RocksDbBytes.Writer writer, String edgeId, RocksDbStoredEdge edge) {
        writer.writeString(edgeId);
        RocksDbIdCodec.write(writer, edge.otherVertexId());
        writer.writeLong(edge.createdTime());
        writer.writeLong(edge.lastUpdatedTime());
        writer.writeInt(edge.properties().size());
        for (Map.Entry<Integer, RocksDbEdgeProperty> property : edge.properties().entrySet()) {
            writer.writeInt(property.getKey());
            writer.writeBytes(RocksDbPropertyCodec.encode(property.getValue().value()));
            writer.writeLong(property.getValue().createdTime());
            writer.writeLong(property.getValue().lastUpdatedTime());
        }
    }

    /** Decodes the bins of one vertex record; {@code null} means the vertex has none. */
    static List<Bin> decode(byte[] value) {
        if (value == null) {
            return List.of();
        }
        RocksDbBytes.Reader reader = new RocksDbBytes.Reader(value);
        int binCount = reader.readInt();
        List<Bin> bins = new ArrayList<>(Math.max(binCount, 0));
        for (int i = 0; i < binCount; i++) {
            String name = reader.readString();
            int edgeCount = reader.readInt();
            Map<String, RocksDbStoredEdge> edges = new LinkedHashMap<>();
            for (int e = 0; e < edgeCount; e++) {
                String edgeId = reader.readString();
                Object otherVertexId = RocksDbIdCodec.read(reader);
                long createdTime = reader.readLong();
                long lastUpdatedTime = reader.readLong();
                int propertyCount = reader.readInt();
                Map<Integer, RocksDbEdgeProperty> properties = new LinkedHashMap<>();
                for (int p = 0; p < propertyCount; p++) {
                    int propertyId = reader.readInt();
                    Object propertyValue = RocksDbPropertyCodec.decode(reader.readBytes());
                    long propertyCreated = reader.readLong();
                    long propertyUpdated = reader.readLong();
                    properties.put(propertyId,
                            new RocksDbEdgeProperty(propertyValue, propertyCreated, propertyUpdated));
                }
                edges.put(edgeId, new RocksDbStoredEdge(edgeId, otherVertexId, createdTime,
                        lastUpdatedTime, properties));
            }
            bins.add(new Bin(name, edges));
        }
        return bins;
    }

    /** The edges of one bin, projected onto the requested property indexes. */
    static List<RocksDbStoredEdge> project(Bin bin, Set<Integer> requestedProperties) {
        List<RocksDbStoredEdge> out = new ArrayList<>(bin.edges().size());
        for (RocksDbStoredEdge edge : bin.edges().values()) {
            if (requestedProperties.isEmpty()) {
                out.add(edge);
                continue;
            }
            Map<Integer, RocksDbEdgeProperty> properties = new LinkedHashMap<>();
            for (Map.Entry<Integer, RocksDbEdgeProperty> entry : edge.properties().entrySet()) {
                if (requestedProperties.contains(entry.getKey())) {
                    properties.put(entry.getKey(), entry.getValue());
                }
            }
            out.add(new RocksDbStoredEdge(edge.edgeId(), edge.otherVertexId(), edge.createdTime(),
                    edge.lastUpdatedTime(), properties));
        }
        return out;
    }

    /**
     * Merges the incoming edges into a bin following the per-property timestamp rule and
     * drops the oldest edges beyond the degree limit.
     *
     * @return a new bin (the input is not mutated)
     */
    static Bin merge(Bin bin, Map<String, RocksDbStoredEdge> incoming, int degreeLimit) {
        Map<String, RocksDbStoredEdge> merged = new LinkedHashMap<>(bin.edges());
        for (Map.Entry<String, RocksDbStoredEdge> entry : incoming.entrySet()) {
            RocksDbStoredEdge existing = merged.get(entry.getKey());
            if (existing == null) {
                merged.put(entry.getKey(), entry.getValue());
                continue;
            }
            RocksDbStoredEdge updated = mergeEdge(existing, entry.getValue());
            if (updated != null) {
                merged.put(entry.getKey(), updated);
            }
            // otherwise the stored edge is newer: keep it
        }
        evictOldest(merged, degreeLimit);
        return new Bin(bin.name(), merged);
    }

    /**
     * Merges one edge into its stored version: properties are replaced only by a newer
     * timestamp, the other endpoint and creation time are kept. Returns {@code null} when
     * the incoming edge carries nothing new.
     */
    static RocksDbStoredEdge mergeEdge(RocksDbStoredEdge existing, RocksDbStoredEdge incoming) {
        Map<Integer, RocksDbEdgeProperty> properties = new LinkedHashMap<>(existing.properties());
        boolean anyNewer = false;
        for (Map.Entry<Integer, RocksDbEdgeProperty> entry : incoming.properties().entrySet()) {
            RocksDbEdgeProperty stored = properties.get(entry.getKey());
            if (stored == null || entry.getValue().lastUpdatedTime() > stored.lastUpdatedTime()) {
                properties.put(entry.getKey(), entry.getValue());
                anyNewer = true;
            }
        }
        if (!anyNewer) {
            return null;
        }
        long lastUpdatedTime = Math.max(existing.lastUpdatedTime(), maxUpdatedTime(incoming, properties));
        return new RocksDbStoredEdge(existing.edgeId(), existing.otherVertexId(),
                existing.createdTime(), lastUpdatedTime, properties);
    }

    /** The newest property timestamp of an edge, falling back to the edge timestamp. */
    static long maxUpdatedTime(RocksDbStoredEdge edge, Map<Integer, RocksDbEdgeProperty> properties) {
        long max = Long.MIN_VALUE;
        for (RocksDbEdgeProperty property : properties.values()) {
            max = Math.max(max, property.lastUpdatedTime());
        }
        return max == Long.MIN_VALUE ? edge.lastUpdatedTime() : max;
    }

    /** Removes the oldest edges (by lastUpdatedTime, then id) beyond {@code degreeLimit}. */
    static void evictOldest(Map<String, RocksDbStoredEdge> edges, int degreeLimit) {
        if (degreeLimit <= 0 || edges.size() <= degreeLimit) {
            return;
        }
        List<String> toEvict = edges.entrySet().stream()
                .sorted((a, b) -> {
                    int byTime = Long.compare(a.getValue().lastUpdatedTime(), b.getValue().lastUpdatedTime());
                    return byTime != 0 ? byTime : a.getKey().compareTo(b.getKey());
                })
                .limit(edges.size() - degreeLimit)
                .map(Map.Entry::getKey)
                .toList();
        toEvict.forEach(edges::remove);
    }

    /** Finds a bin by name, or an empty one. */
    static Bin bin(List<Bin> bins, String name) {
        for (Bin bin : bins) {
            if (bin.name().equals(name)) {
                return bin;
            }
        }
        return new Bin(name, new LinkedHashMap<>());
    }

    /** Replaces (or appends) a bin in the list. */
    static List<Bin> withBin(List<Bin> bins, Bin updated) {
        List<Bin> out = new ArrayList<>(bins.size() + 1);
        boolean replaced = false;
        for (Bin bin : bins) {
            if (bin.name().equals(updated.name())) {
                out.add(updated);
                replaced = true;
            } else {
                out.add(bin);
            }
        }
        if (!replaced) {
            out.add(updated);
        }
        return out;
    }

    /**
     * Removes an edge id from a bin (the input is not mutated). An edge that is already
     * gone - it was evicted by the degree limit - leaves the bin as it is, so a delete is
     * idempotent.
     */
    static Bin withoutEdge(List<Bin> bins, String binName, String edgeId) {
        Bin bin = bin(bins, binName);
        Map<String, RocksDbStoredEdge> edges = new LinkedHashMap<>(bin.edges());
        edges.remove(edgeId);
        return new Bin(binName, edges);
    }

    /** Whether a bin holds no edges at all. */
    static boolean isEmpty(Bin bin) {
        return bin.edges().isEmpty();
    }

    /** Whether every bin of a record is empty (the record can then be dropped). */
    static boolean isEmpty(List<Bin> bins) {
        for (Bin bin : bins) {
            if (!bin.edges().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}

package com.pengcheng.phoenixgraph.storage.rocksdb.edge;

import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbBytes;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbIdCodec;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyCodec;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoredEdge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The stored form of one edge-table row.
 *
 * <pre>
 * otherVertexId (type + text), createdTime, lastUpdatedTime, propertyCount,
 * per property: propertyId, value, createdTime, lastUpdatedTime
 * </pre>
 *
 * <p>The row is addressed by the key, so nothing that is part of the key (the owning
 * vertex, the direction, the creation time, the edge id) is repeated in the value. An
 * update rewrites the row: the properties are merged by their own timestamps first, so an
 * older write cannot undo a newer one even though there is no version on the key.
 */
final class RocksDbEdgeRowCodec {

    private RocksDbEdgeRowCodec() {
    }

    static byte[] encode(RocksDbStoredEdge edge) {
        RocksDbBytes.Writer writer = new RocksDbBytes.Writer();
        RocksDbIdCodec.write(writer, edge.otherVertexId());
        writer.writeLong(edge.createdTime());
        writer.writeLong(edge.lastUpdatedTime());
        writer.writeInt(edge.properties().size());
        for (Map.Entry<Integer, RocksDbEdgeProperty> entry : edge.properties().entrySet()) {
            writer.writeInt(entry.getKey());
            writer.writeBytes(RocksDbPropertyCodec.encode(entry.getValue().value()));
            writer.writeLong(entry.getValue().createdTime());
            writer.writeLong(entry.getValue().lastUpdatedTime());
        }
        return writer.toByteArray();
    }

    /** Decodes a row; properties not in {@code requested} are skipped (empty = all). */
    static RocksDbStoredEdge decode(String edgeId, byte[] value, Set<Integer> requested) {
        RocksDbBytes.Reader reader = new RocksDbBytes.Reader(value);
        Object otherVertexId = RocksDbIdCodec.read(reader);
        long createdTime = reader.readLong();
        long lastUpdatedTime = reader.readLong();
        int propertyCount = reader.readInt();
        Map<Integer, RocksDbEdgeProperty> properties = new LinkedHashMap<>();
        for (int i = 0; i < propertyCount; i++) {
            int propertyId = reader.readInt();
            Object propertyValue = RocksDbPropertyCodec.decode(reader.readBytes());
            long propertyCreated = reader.readLong();
            long propertyUpdated = reader.readLong();
            if (requested.isEmpty() || requested.contains(propertyId)) {
                properties.put(propertyId,
                        new RocksDbEdgeProperty(propertyValue, propertyCreated, propertyUpdated));
            }
        }
        return new RocksDbStoredEdge(edgeId, otherVertexId, createdTime, lastUpdatedTime, properties);
    }

    /**
     * The header of a stored edge: the far endpoint and the timestamps, without decoding a single property.
     *
     * <p>A vertex hop walks an edge to arrive somewhere and never looks at the edge itself, so the property
     * values - each with its own two timestamps - are skipped rather than decoded. The row is still read
     * (the endpoint lives in it), which is what makes this cheaper than building the edge: no property
     * object, no value decoding, no map.
     */
    static NeighbourHeader decodeHeader(String edgeId, byte[] value) {
        RocksDbBytes.Reader reader = new RocksDbBytes.Reader(value);
        Object otherVertexId = RocksDbIdCodec.read(reader);
        long createdTime = reader.readLong();
        long lastUpdatedTime = reader.readLong();
        return new NeighbourHeader(edgeId, otherVertexId, createdTime, lastUpdatedTime);
    }

    /** The far endpoint of a stored edge with its timestamps (see {@link #decodeHeader}). */
    record NeighbourHeader(String edgeId, Object otherVertexId, long createdTime, long lastUpdatedTime) {
    }

    /**
     * Merges the properties of an incoming row into the stored one: a property is replaced
     * only by a newer timestamp, the peer and the creation time are kept.
     */
    static RocksDbStoredEdge merge(RocksDbStoredEdge existing, RocksDbStoredEdge incoming) {
        Map<Integer, RocksDbEdgeProperty> properties = new LinkedHashMap<>(existing.properties());
        for (Map.Entry<Integer, RocksDbEdgeProperty> entry : incoming.properties().entrySet()) {
            RocksDbEdgeProperty stored = properties.get(entry.getKey());
            if (stored == null || entry.getValue().lastUpdatedTime() > stored.lastUpdatedTime()) {
                properties.put(entry.getKey(), entry.getValue());
            }
        }
        long lastUpdatedTime = Math.max(existing.lastUpdatedTime(), incoming.lastUpdatedTime());
        for (RocksDbEdgeProperty property : properties.values()) {
            lastUpdatedTime = Math.max(lastUpdatedTime, property.lastUpdatedTime());
        }
        return new RocksDbStoredEdge(existing.edgeId(), existing.otherVertexId(),
                existing.createdTime(), lastUpdatedTime, properties);
    }
}

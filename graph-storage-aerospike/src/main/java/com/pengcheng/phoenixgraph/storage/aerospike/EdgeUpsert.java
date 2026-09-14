package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;

import java.util.Map;

/**
 * An edge upsert request.
 *
 * <p>The edge schema index and direction semantics are derived from
 * {@link #edgeSchema()}, so callers pass the {@link EdgeSchema} instance
 * directly and downstream reads {@code edgeSchema().id()} when it needs the
 * schema index.
 *
 * @param edgeId          edge id (the CDT map key)
 * @param edgeSchema      the {@link EdgeSchema} this edge conforms to
 * @param source          source vertex id
 * @param destination     destination vertex id
 * @param properties      property index -> value + per-property timestamps
 * @param createdTime     edge creation timestamp (epoch millis)
 * @param lastUpdatedTime edge last-update timestamp (epoch millis)
 */
public record EdgeUpsert(Object edgeId, EdgeSchema edgeSchema, Object source, Object destination,
                         Map<Integer, EdgePropertyValue> properties,
                         long createdTime, long lastUpdatedTime) {
}

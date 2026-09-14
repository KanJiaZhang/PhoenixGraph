package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;

/**
 * An edge deletion request (hard delete of the in-edge and out-edge).
 *
 * <p>The edge schema index and direction semantics are derived from
 * {@link #edgeSchema()}.
 *
 * @param edgeId       edge id
 * @param edgeSchema   the {@link EdgeSchema} this edge conforms to
 * @param source       source vertex id
 * @param destination  destination vertex id
 */
public record EdgeDelete(Object edgeId, EdgeSchema edgeSchema, Object source, Object destination) {
}

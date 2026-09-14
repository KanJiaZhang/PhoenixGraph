package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;

import java.util.Map;
import java.util.Objects;

/**
 * Resolves the vertex schema name of the neighbour reached by following an edge
 * of a given class in a direction.
 *
 * <p>This is what allows {@code out(...)} / {@code in(...)} / {@code both(...)}
 * to project the neighbour's properties by name. Returning {@code null} means
 * the schema is unknown; traversal still works (the neighbour's id is known),
 * but property access on that neighbour is not available.
 */
@FunctionalInterface
public interface NeighborVertexResolver {

    /** Returns the neighbour vertex schema name, or {@code null} when unknown. */
    String neighborVertexName(String edgeName, EdgeSide side);

    /** A resolver that never knows the neighbour schema. */
    static NeighborVertexResolver none() {
        return (edgeName, side) -> null;
    }

    /**
     * Builds a resolver from a {@link GraphSchema} using each edge schema's
     * declared {@link EdgeSchema#sourceVertexName()} /
     * {@link EdgeSchema#targetVertexName()}.
     */
    static NeighborVertexResolver from(GraphSchema schema) {
        Objects.requireNonNull(schema, "schema");
        return (edgeName, side) -> {
            EdgeSchema edge = schema.edgeSchema(edgeName);
            if (edge == null) {
                return null;
            }
            return switch (side) {
                case OUT -> edge.targetVertexName();   // following an out-edge reaches the target vertex
                case IN -> edge.sourceVertexName();    // following an in-edge reaches the source vertex
                case BOTH -> edge.targetVertexName() != null
                        ? edge.targetVertexName() : edge.sourceVertexName();
            };
        };
    }

    /**
     * Builds a resolver from edge-name keyed maps of target vertex names
     * (used for {@link EdgeSide#OUT}) and source vertex names (used for
     * {@link EdgeSide#IN}).
     */
    static NeighborVertexResolver of(Map<String, String> targetByEdge, Map<String, String> sourceByEdge) {
        return (edgeName, side) -> switch (side) {
            case OUT -> targetByEdge.getOrDefault(edgeName, null);
            case IN -> sourceByEdge.getOrDefault(edgeName, null);
            case BOTH -> targetByEdge.containsKey(edgeName)
                    ? targetByEdge.get(edgeName) : sourceByEdge.get(edgeName);
        };
    }
}

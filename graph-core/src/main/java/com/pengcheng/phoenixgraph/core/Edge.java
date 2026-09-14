package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;

import java.util.Objects;

/**
 * An edge (relationship) instance conforming to an {@link EdgeSchema}.
 */
public interface Edge extends Element {

    /** The edge schema this instance conforms to. */
    EdgeSchema schema();

    /** The edge id, typed according to {@code schema().idSchema()}. */
    Object edgeId();

    /** The source vertex id. */
    Object source();

    /** The destination vertex id. */
    Object destination();

    /** The direction semantics declared by this edge's schema. */
    default EdgeDirection direction() {
        return schema().direction();
    }

    /**
     * Whether this edge is an outgoing edge of the given vertex.
     *
     * <p>For a {@link EdgeDirection#DIRECTED} edge this is true only for the
     * source vertex; for {@link EdgeDirection#UNDIRECTED} and
     * {@link EdgeDirection#BIDIRECTIONAL} edges it is true for both endpoints.
     */
    default boolean isOutgoing(Object vertexId) {
        return switch (direction()) {
            case DIRECTED -> Objects.equals(source(), vertexId);
            case UNDIRECTED, BIDIRECTIONAL ->
                    Objects.equals(source(), vertexId) || Objects.equals(destination(), vertexId);
        };
    }

    /**
     * Whether this edge is an incoming edge of the given vertex.
     *
     * <p>For a {@link EdgeDirection#DIRECTED} edge this is true only for the
     * destination vertex; for {@link EdgeDirection#UNDIRECTED} and
     * {@link EdgeDirection#BIDIRECTIONAL} edges it is true for both endpoints.
     */
    default boolean isIncoming(Object vertexId) {
        return switch (direction()) {
            case DIRECTED -> Objects.equals(destination(), vertexId);
            case UNDIRECTED, BIDIRECTIONAL ->
                    Objects.equals(source(), vertexId) || Objects.equals(destination(), vertexId);
        };
    }

    /**
     * Updates a property value (lastUpdatedTime = now) and propagates its new
     * lastUpdatedTime to this edge if it is newer.
     */
    Property updateProperty(String name, Object value);

    /** Updates a property value with an explicit last-update timestamp and propagates it. */
    Property updateProperty(String name, Object value, long lastUpdatedTime);
}

package com.pengcheng.phoenixgraph.schema;

/**
 * Schema definition for an edge endpoint.
 *
 * <p>An endpoint is both the id property of the endpoint (index, name,
 * description and value type) and the {@link #vertexName() vertex schema name}
 * the endpoint connects to. Defining an edge's {@code source}/{@code target}
 * therefore declares the source/target vertex id schema <em>and</em> the source/
 * target vertex schema name in one place.
 */
public class EdgeEndpointSchema extends EdgePropertySchema {

    private final String vertexName;

    public EdgeEndpointSchema(int id, String name, String description, PropertyType type, String vertexName) {
        this(id, name, description, type, vertexName, PropertyConstraints.NONE);
    }

    public EdgeEndpointSchema(int id, String name, String description, PropertyType type,
                              String vertexName, PropertyConstraints constraints) {
        super(id, name, description, type, constraints);
        if (vertexName != null && vertexName.isBlank()) {
            throw new IllegalArgumentException("vertex name must not be blank");
        }
        this.vertexName = vertexName;
    }

    /** The vertex schema name this endpoint connects to, or {@code null} if not declared. */
    public String vertexName() {
        return vertexName;
    }
}

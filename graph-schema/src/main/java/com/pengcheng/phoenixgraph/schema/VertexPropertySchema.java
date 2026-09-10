package com.pengcheng.phoenixgraph.schema;

/**
 * Schema definition for a property attached to a vertex.
 *
 * <p>Inherits the {@code id} (index), {@code name}, {@code description} and
 * {@link PropertyType} from {@link PropertySchema}.
 */
public class VertexPropertySchema extends PropertySchema {

    public VertexPropertySchema(int id, String name, String description, PropertyType type) {
        super(id, name, description, type);
    }
}

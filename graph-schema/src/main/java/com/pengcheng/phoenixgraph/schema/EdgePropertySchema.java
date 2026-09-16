package com.pengcheng.phoenixgraph.schema;

/**
 * Schema definition for a property attached to an edge.
 *
 * <p>Inherits the {@code id} (index), {@code name}, {@code description} and
 * {@link PropertyType} from {@link PropertySchema}.
 */
public class EdgePropertySchema extends PropertySchema {

    public EdgePropertySchema(int id, String name, String description, PropertyType type) {
        super(id, name, description, type);
    }

    public EdgePropertySchema(int id, String name, String description, PropertyType type,
                              PropertyConstraints constraints) {
        super(id, name, description, type, constraints);
    }

    @Override
    public EdgePropertySchema display(boolean display) {
        super.display(display);
        return this;
    }
}

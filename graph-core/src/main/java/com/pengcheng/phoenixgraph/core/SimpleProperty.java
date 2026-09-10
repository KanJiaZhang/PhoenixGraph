package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.PropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;

import java.util.Map;
import java.util.Objects;

/**
 * Default {@link Property} implementation. Validates the value against the
 * schema's type, or instantiates a typed default when the value is {@code null}.
 */
public class SimpleProperty implements Property {

    private final PropertySchema schema;
    private final Object value;
    private final MetaProperty meta;

    public SimpleProperty(PropertySchema schema, Object value) {
        this(schema, value, MetaProperty.now());
    }

    public SimpleProperty(PropertySchema schema, Object value, MetaProperty meta) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.value = PropertyValues.validate(schema.type(), value);
        this.meta = Objects.requireNonNull(meta, "meta");
    }

    /** Creates the appropriate implementation with a "now" meta. */
    public static Property create(PropertySchema schema, Object value) {
        return create(schema, value, MetaProperty.now());
    }

    /**
     * Creates the appropriate {@link Property} implementation for the schema's
     * type: a {@link SimpleMapProperty} for map types, otherwise a
     * {@link SimpleProperty}.
     */
    public static Property create(PropertySchema schema, Object value, MetaProperty meta) {
        Objects.requireNonNull(schema, "schema");
        if (schema.type() instanceof PropertyType.MapType) {
            return new SimpleMapProperty(schema, (Map<String, ?>) value, meta);
        }
        return new SimpleProperty(schema, value, meta);
    }

    @Override
    public PropertySchema schema() {
        return schema;
    }

    @Override
    public int id() {
        return schema.id();
    }

    @Override
    public Object value() {
        return value;
    }

    @Override
    public MetaProperty meta() {
        return meta;
    }

    @Override
    public String toString() {
        return "Property{" + schema.name() + "=" + value + '}';
    }
}

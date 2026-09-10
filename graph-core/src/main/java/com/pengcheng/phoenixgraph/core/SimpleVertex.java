package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default {@link Vertex} implementation.
 *
 * <p>Builds a {@link Property} for every {@link VertexPropertySchema} declared by
 * the vertex schema, instantiating each value according to its type. Missing
 * values fall back to the type's default. Updating a property propagates its
 * newer lastUpdatedTime to the vertex's own lastUpdatedTime.
 */
public class SimpleVertex implements Vertex {

    private final VertexSchema schema;
    private final Object id;
    private MetaProperty meta;
    private final Map<String, Property> propertiesByName;
    private final Map<Integer, Property> propertiesById;

    public SimpleVertex(VertexSchema schema, Object id, Map<String, ?> values) {
        this(schema, id, values, MetaProperty.now());
    }

    public SimpleVertex(VertexSchema schema, Object id, Map<String, ?> values, MetaProperty meta) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.meta = Objects.requireNonNull(meta, "meta");
        this.id = PropertyValues.validate(schema.idSchema().type(), id);

        Map<String, Property> byName = new LinkedHashMap<>();
        Map<Integer, Property> byId = new LinkedHashMap<>();
        for (VertexPropertySchema propertySchema : schema.properties()) {
            Object value = values == null ? null : values.get(propertySchema.name());
            Property property = SimpleProperty.create(propertySchema, value);
            byName.put(propertySchema.name(), property);
            byId.put(propertySchema.id(), property);
        }
        this.propertiesByName = byName;
        this.propertiesById = byId;
    }

    @Override
    public Property updateProperty(String name, Object value) {
        return updateProperty(name, value, System.currentTimeMillis());
    }

    @Override
    public Property updateProperty(String name, Object value, long lastUpdatedTime) {
        Property existing = propertiesByName.get(name);
        if (existing == null) {
            throw new IllegalArgumentException(
                    "Unknown property '" + name + "' in vertex schema '" + schema.name() + "'");
        }
        MetaProperty propertyMeta = new MetaProperty(existing.meta().createdTime(), lastUpdatedTime);
        Property updated = SimpleProperty.create(existing.schema(), value, propertyMeta);
        propertiesByName.put(name, updated);
        propertiesById.put(existing.id(), updated);
        // propagate the property's lastUpdatedTime to the vertex (only if newer)
        meta = meta.mergedWith(lastUpdatedTime);
        return updated;
    }

    @Override
    public VertexSchema schema() {
        return schema;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public MetaProperty meta() {
        return meta;
    }

    @Override
    public Collection<Property> properties() {
        return List.copyOf(propertiesByName.values());
    }

    @Override
    public Property property(String name) {
        return propertiesByName.get(name);
    }

    @Override
    public Property property(int id) {
        return propertiesById.get(id);
    }

    @Override
    public String toString() {
        return "Vertex{" + schema.name() + ", id=" + id + '}';
    }
}

package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default {@link Edge} implementation.
 *
 * <p>Validates the edge id, source and destination against the edge schema's
 * dedicated property schemas, and builds a {@link Property} for every remaining
 * {@link EdgePropertySchema}, instantiating each value according to its type.
 * Updating a property propagates its newer lastUpdatedTime to the edge's own
 * lastUpdatedTime.
 */
public class SimpleEdge implements Edge {

    private final EdgeSchema schema;
    private final Object edgeId;
    private final Object source;
    private final Object destination;
    private MetaProperty meta;
    private final Map<String, Property> propertiesByName;
    private final Map<Integer, Property> propertiesById;

    public SimpleEdge(EdgeSchema schema, Object edgeId, Object source, Object destination, Map<String, ?> values) {
        this(schema, edgeId, source, destination, values, MetaProperty.now());
    }

    public SimpleEdge(EdgeSchema schema, Object edgeId, Object source, Object destination,
                      Map<String, ?> values, MetaProperty meta) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.meta = Objects.requireNonNull(meta, "meta");
        this.edgeId = PropertyValues.validate(schema.idSchema().type(), edgeId);
        this.source = PropertyValues.validate(schema.source().type(), source);
        this.destination = PropertyValues.validate(schema.target().type(), destination);

        Map<String, Property> byName = new LinkedHashMap<>();
        Map<Integer, Property> byId = new LinkedHashMap<>();
        for (EdgePropertySchema propertySchema : schema.properties()) {
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
                    "Unknown property '" + name + "' in edge schema '" + schema.name() + "'");
        }
        MetaProperty propertyMeta = new MetaProperty(existing.meta().createdTime(), lastUpdatedTime);
        Property updated = SimpleProperty.create(existing.schema(), value, propertyMeta);
        propertiesByName.put(name, updated);
        propertiesById.put(existing.id(), updated);
        // propagate the property's lastUpdatedTime to the edge (only if newer)
        meta = meta.mergedWith(lastUpdatedTime);
        return updated;
    }

    @Override
    public EdgeSchema schema() {
        return schema;
    }

    @Override
    public Object edgeId() {
        return edgeId;
    }

    @Override
    public Object source() {
        return source;
    }

    @Override
    public Object destination() {
        return destination;
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
        return "Edge{" + schema.name() + ", edgeId=" + edgeId + ", " + source + " -> " + destination + '}';
    }
}

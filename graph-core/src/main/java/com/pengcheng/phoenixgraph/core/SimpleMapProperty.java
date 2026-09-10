package com.pengcheng.phoenixgraph.core;

import com.pengcheng.phoenixgraph.schema.PropertySchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Default {@link MapProperty} implementation. Each entry is validated against the
 * map's value type and carries its own created/last-updated timestamps.
 */
public class SimpleMapProperty implements MapProperty {

    private final PropertySchema schema;
    private final MetaProperty meta;
    private final Map<String, MapEntryValue> entries = new LinkedHashMap<>();

    public SimpleMapProperty(PropertySchema schema, Map<String, ?> initialValues) {
        this(schema, initialValues, MetaProperty.now());
    }

    public SimpleMapProperty(PropertySchema schema, Map<String, ?> initialValues, MetaProperty meta) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.meta = Objects.requireNonNull(meta, "meta");
        if (!(schema.type() instanceof PropertyType.MapType)) {
            throw new IllegalArgumentException(
                    "Property '" + schema.name() + "' is not a map type: " + schema.type());
        }
        if (initialValues != null) {
            initialValues.forEach(this::put);
        }
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
    public MetaProperty meta() {
        return meta;
    }

    @Override
    public Object value() {
        return entries();
    }

    @Override
    public Map<String, MapEntryValue> entries() {
        return Collections.unmodifiableMap(entries);
    }

    @Override
    public MapEntryValue get(String key) {
        return entries.get(key);
    }

    @Override
    public MapEntryValue put(String key, Object value) {
        Objects.requireNonNull(key, "key");
        PropertyType.MapType mapType = (PropertyType.MapType) schema.type();
        Object validated = PropertyValues.validate(mapType.valueType(), value);
        MapEntryValue entry = MapEntryValue.of(validated);
        entries.put(key, entry);
        return entry;
    }

    @Override
    public MapEntryValue remove(String key) {
        return entries.remove(key);
    }

    @Override
    public boolean containsKey(String key) {
        return entries.containsKey(key);
    }

    @Override
    public String toString() {
        return "MapProperty{" + schema.name() + "=" + entries + '}';
    }
}

package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The geo properties a graph schema declares (the ones marked {@code isGeo}).
 *
 * <p>Mirrors {@link VectorIndexSchemas} for the other kind of index: the storage layer builds
 * one S2 index per declared property, and the query layer resolves "the geo property of this
 * vertex type" the same way, so a query only has to name the type.
 */
public final class GeoIndexSchemas {

    /**
     * The property name a geo query attaches the computed distance under (in metres). It is not
     * stored anywhere: a geo result carries it so a query can read or print the distance like
     * any other property.
     */
    public static final String DISTANCE_PROPERTY = "distanceMeters";

    private GeoIndexSchemas() {
    }

    /**
     * One geo property: the vertex type it belongs to, its name and index settings.
     *
     * @param vertexName   the vertex type holding the point
     * @param propertyName the property name (the "bin" the point is stored in)
     * @param propertyId   the property index the value is stored under
     * @param config       the S2 index settings
     */
    public record GeoProperty(String vertexName, String propertyName, int propertyId, GeoIndexConfig config) {

        public GeoProperty {
            if (vertexName == null || vertexName.isBlank()) {
                throw new IllegalArgumentException("vertexName must not be blank");
            }
            if (propertyName == null || propertyName.isBlank()) {
                throw new IllegalArgumentException("propertyName must not be blank");
            }
            config = config == null ? GeoIndexConfig.DEFAULT : config;
        }

        /** The name of this index (used in logs and index listings). */
        public String indexName() {
            return vertexName + "." + propertyName;
        }
    }

    /** Every geo property of a graph, in schema declaration order. */
    public static List<GeoProperty> geoProperties(GraphSchema schema) {
        List<GeoProperty> properties = new ArrayList<>();
        for (VertexSchema vertex : schema.vertexSchemas()) {
            properties.addAll(geoProperties(schema, vertex.name()));
        }
        return List.copyOf(properties);
    }

    /** The geo properties of one vertex type, in declaration order. */
    public static List<GeoProperty> geoProperties(GraphSchema schema, String vertexName) {
        VertexSchema vertex = schema.vertexSchema(vertexName);
        if (vertex == null) {
            return List.of();
        }
        List<GeoProperty> properties = new ArrayList<>();
        for (VertexPropertySchema property : vertex.properties()) {
            if (property.isGeo()) {
                properties.add(new GeoProperty(vertexName, property.name(), property.id(),
                        property.geoConfigOrDefault()));
            }
        }
        return List.copyOf(properties);
    }

    /** The geo properties of a graph grouped by vertex type. */
    public static Map<String, List<GeoProperty>> byVertexType(GraphSchema schema) {
        Map<String, List<GeoProperty>> byType = new LinkedHashMap<>();
        for (GeoProperty property : geoProperties(schema)) {
            byType.computeIfAbsent(property.vertexName(), k -> new ArrayList<>()).add(property);
        }
        return Map.copyOf(byType);
    }

    /** One geo property by vertex type and name, or {@code null}. */
    public static GeoProperty geoProperty(GraphSchema schema, String vertexName, String propertyName) {
        for (GeoProperty property : geoProperties(schema, vertexName)) {
            if (property.propertyName().equals(propertyName)) {
                return property;
            }
        }
        return null;
    }

    /** Whether a graph declares any geo property at all. */
    public static boolean hasGeoProperties(GraphSchema schema) {
        return !geoProperties(schema).isEmpty();
    }
}

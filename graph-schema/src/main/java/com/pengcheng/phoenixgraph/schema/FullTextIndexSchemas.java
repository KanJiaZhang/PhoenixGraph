package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full-text properties a graph schema declares (the ones marked {@code isFullText}).
 *
 * <p>Mirrors {@link GeoIndexSchemas} for the text kind of index: the storage layer builds one
 * inverted index per declared property, and the query layer resolves "the full-text property of
 * this vertex type" the same way, so a query only has to name the type.
 */
public final class FullTextIndexSchemas {

    private FullTextIndexSchemas() {
    }

    /**
     * One full-text property: the vertex type it belongs to, its name and index settings.
     *
     * @param vertexName   the vertex type holding the text
     * @param propertyName the property name the text is stored under
     * @param propertyId   the property index the value is stored under
     * @param config       the n-gram index settings
     */
    public record FullTextProperty(String vertexName, String propertyName, int propertyId,
                                   FullTextIndexConfig config) {

        public FullTextProperty {
            if (vertexName == null || vertexName.isBlank()) {
                throw new IllegalArgumentException("vertexName must not be blank");
            }
            if (propertyName == null || propertyName.isBlank()) {
                throw new IllegalArgumentException("propertyName must not be blank");
            }
            config = config == null ? FullTextIndexConfig.DEFAULT : config;
        }

        /** The name of this index (used in logs and index listings). */
        public String indexName() {
            return vertexName + "." + propertyName;
        }
    }

    /** Every full-text property of a graph, in schema declaration order. */
    public static List<FullTextProperty> fullTextProperties(GraphSchema schema) {
        List<FullTextProperty> properties = new ArrayList<>();
        for (VertexSchema vertex : schema.vertexSchemas()) {
            properties.addAll(fullTextProperties(schema, vertex.name()));
        }
        return List.copyOf(properties);
    }

    /** The full-text properties of one vertex type, in declaration order. */
    public static List<FullTextProperty> fullTextProperties(GraphSchema schema, String vertexName) {
        VertexSchema vertex = schema.vertexSchema(vertexName);
        if (vertex == null) {
            return List.of();
        }
        List<FullTextProperty> properties = new ArrayList<>();
        for (VertexPropertySchema property : vertex.properties()) {
            if (property.isFullText()) {
                properties.add(new FullTextProperty(vertexName, property.name(), property.id(),
                        property.fullTextConfigOrDefault()));
            }
        }
        return List.copyOf(properties);
    }

    /** The full-text properties of a graph grouped by vertex type. */
    public static Map<String, List<FullTextProperty>> byVertexType(GraphSchema schema) {
        Map<String, List<FullTextProperty>> byType = new LinkedHashMap<>();
        for (FullTextProperty property : fullTextProperties(schema)) {
            byType.computeIfAbsent(property.vertexName(), k -> new ArrayList<>()).add(property);
        }
        return Map.copyOf(byType);
    }

    /** One full-text property by vertex type and name, or {@code null}. */
    public static FullTextProperty fullTextProperty(GraphSchema schema, String vertexName,
                                                    String propertyName) {
        for (FullTextProperty property : fullTextProperties(schema, vertexName)) {
            if (property.propertyName().equals(propertyName)) {
                return property;
            }
        }
        return null;
    }

    /** Whether a graph declares any full-text property at all. */
    public static boolean hasFullTextProperties(GraphSchema schema) {
        return !fullTextProperties(schema).isEmpty();
    }
}

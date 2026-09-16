package com.pengcheng.phoenixgraph.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * The edge classes an HNSW vector index is made of, derived from the schema.
 *
 * <p>A property flagged {@code "isVector": true} (see {@link PropertySchema#isVector()})
 * gets one <em>bidirectional</em> self-edge class per layer, named
 * {@code <property>_<layer>}: the {@code vector} property of a {@code Vector}
 * vertex type therefore becomes {@code vector_0} (bottom layer, every vertex) up
 * to {@code vector_<ceil(log2 n)>} for {@code n} vectors - the 13 classes
 * {@code vector_0 .. vector_12} of a 4096-vector dataset.
 *
 * <p>The classes are <em>created on demand</em> ({@link #ensureLayer}): a schema
 * only needs to declare the vector property, the layer edge classes are added as
 * the index grows (each layer arrives when the dataset doubles), so a graph can
 * start empty and still reach the layers its data deserves. Ids are allocated
 * deterministically (the id of an existing layer wins, otherwise the next free
 * id above the schema's highest), which keeps the Aerospike bins - they embed the
 * edge schema id - stable across restarts.
 */
public final class VectorIndexSchemas {

    /** Separator between the vector property name and the layer number. */
    public static final String LAYER_SEPARATOR = "_";

    private VectorIndexSchemas() {
    }

    /**
     * A vector property of a graph schema: the vertex type and property that
     * declare it, the index configuration and the layers already declared.
     *
     * @param vertexName    vertex type owning the property
     * @param propertyName  the vector property
     * @param config        index configuration (metric, m, efConstruction, efSearch)
     * @param declaredLevels number of layer edge classes already present ({@code 0} when none)
     */
    public record VectorProperty(String vertexName, String propertyName, VectorIndexConfig config,
                                 int declaredLevels) {

        /** Highest layer already declared, or {@code -1}. */
        public int topLevel() {
            return declaredLevels - 1;
        }
    }

    /** Edge class name of one layer: {@code vector} + layer {@code 3} → {@code vector_3}. */
    public static String layerEdgeName(String propertyName, int level) {
        if (propertyName == null || propertyName.isBlank()) {
            throw new IllegalArgumentException("vector property name must not be blank");
        }
        if (level < 0) {
            throw new IllegalArgumentException("layer must be >= 0, got " + level);
        }
        return propertyName + LAYER_SEPARATOR + level;
    }

    /** Layer of a layer edge class name, or {@code -1} when the name is not one. */
    public static int levelOfLayerEdge(String propertyName, String edgeName) {
        if (edgeName == null || propertyName == null) {
            return -1;
        }
        String prefix = propertyName + LAYER_SEPARATOR;
        if (!edgeName.startsWith(prefix)) {
            return -1;
        }
        try {
            int level = Integer.parseInt(edgeName.substring(prefix.length()));
            return level < 0 ? -1 : level;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Every vector property of the schema, in declaration order. */
    public static List<VectorProperty> vectorProperties(GraphSchema schema) {
        List<VectorProperty> properties = new ArrayList<>();
        for (VertexSchema vertex : schema.vertexSchemas()) {
            for (VertexPropertySchema property : vertex.properties()) {
                if (property.isVector()) {
                    properties.add(new VectorProperty(vertex.name(), property.name(),
                            property.vectorConfigOrDefault(),
                            declaredLevels(schema, vertex.name(), property.name())));
                }
            }
        }
        return List.copyOf(properties);
    }

    /** The vector property of one vertex type, or {@code null} when it has none. */
    public static VectorProperty vectorProperty(GraphSchema schema, String vertexName, String propertyName) {
        for (VectorProperty property : vectorProperties(schema)) {
            if (property.vertexName().equals(vertexName) && property.propertyName().equals(propertyName)) {
                return property;
            }
        }
        return null;
    }

    /** The vector properties of one vertex type, in declaration order. */
    public static List<VectorProperty> vectorProperties(GraphSchema schema, String vertexName) {
        return vectorProperties(schema).stream()
                .filter(property -> property.vertexName().equals(vertexName))
                .toList();
    }

    /** Number of layer edge classes of a vector property that the schema already declares. */
    public static int declaredLevels(GraphSchema schema, String vertexName, String propertyName) {
        int levels = 0;
        while (layerSchema(schema, vertexName, propertyName, levels) != null) {
            levels++;
        }
        return levels;
    }

    /** The layer edge class of a vector property, or {@code null} when it is not declared. */
    public static EdgeSchema layerSchema(GraphSchema schema, String vertexName, String propertyName, int level) {
        EdgeSchema edge = schema.edgeSchema(layerEdgeName(propertyName, level));
        if (edge == null || !connects(edge, vertexName)) {
            return null;
        }
        return edge;
    }

    /**
     * Makes sure the layer edge classes {@code 0..level} of a vector property exist,
     * creating the missing ones.
     *
     * @param schema       graph schema to extend (mutated in place and returned for chaining)
     * @param vertexName   vertex type owning the vector property
     * @param propertyName the vector property
     * @param level        highest layer that must exist
     * @return the number of edge classes created
     */
    public static int ensureLayer(GraphSchema schema, String vertexName, String propertyName, int level) {
        if (level < 0) {
            return 0;
        }
        VertexSchema vertex = schema.vertexSchema(vertexName);
        if (vertex == null) {
            throw new IllegalArgumentException("Unknown vertex schema: " + vertexName);
        }
        VertexPropertySchema property = vertex.property(propertyName);
        if (property == null) {
            throw new IllegalArgumentException("Unknown property '" + propertyName
                    + "' of vertex schema '" + vertexName + "'");
        }
        if (!property.isVector()) {
            throw new IllegalArgumentException("Property '" + propertyName + "' of vertex schema '"
                    + vertexName + "' is not declared as a vector property (isVector=true)");
        }
        VectorIndexConfig config = property.vectorConfigOrDefault();
        int created = 0;
        synchronized (schema) {
            for (int layer = 0; layer <= level; layer++) {
                if (layerSchema(schema, vertexName, propertyName, layer) != null) {
                    continue;
                }
                schema.edge(layerEdge(schema, vertex, property, config, layer));
                created++;
            }
        }
        return created;
    }

    /** Ensures the layer edge classes of every vector property of the schema up to {@code level}. */
    public static int ensureLayers(GraphSchema schema, int level) {
        int created = 0;
        for (VectorProperty property : vectorProperties(schema)) {
            created += ensureLayer(schema, property.vertexName(), property.propertyName(), level);
        }
        return created;
    }

    /** The layer edge class of a vector property, as declared by the schema. */
    private static EdgeSchema layerEdge(GraphSchema schema, VertexSchema vertex, VertexPropertySchema property,
                                        VectorIndexConfig config, int layer) {
        int maxLinks = layer == 0 ? 2 * config.m() : config.m();
        EdgeEndpointSchema source = new EdgeEndpointSchema(1, "source", "First vertex id",
                PropertyType.ScalarType.STRING, vertex.name());
        EdgeEndpointSchema target = new EdgeEndpointSchema(2, "target", "Second vertex id",
                PropertyType.ScalarType.STRING, vertex.name());
        return new EdgeSchema(nextEdgeId(schema, property.name(), layer), layerEdgeName(property.name(), layer),
                "HNSW layer " + layer + " of the '" + property.name() + "' vector index of " + vertex.name()
                        + " (at most " + maxLinks + " links per vertex)",
                new EdgePropertySchema(0, "id", "Link id", PropertyType.ScalarType.STRING),
                source, target,
                MetaPropertySchema.createdTime(), MetaPropertySchema.lastUpdatedTime(),
                EdgeDirection.BIDIRECTIONAL,
                // head-room above the index' own cap: the index prunes first, the store only guards
                2 * maxLinks, 2 * maxLinks, 2 * maxLinks);
    }

    /**
     * Deterministic id of a layer edge class: an already declared class keeps its
     * id (top of the range), otherwise the next free id is used, counting upwards
     * from the schema's highest edge id.
     */
    private static int nextEdgeId(GraphSchema schema, String propertyName, int layer) {
        for (EdgeSchema edge : schema.edgeSchemas()) {
            if (edge.name().equals(layerEdgeName(propertyName, layer))) {
                return edge.id();
            }
        }
        int highest = schema.edgeSchemas().stream().mapToInt(EdgeSchema::id).max().orElse(0);
        for (int candidate = highest + 1; ; candidate++) {
            int id = candidate;
            if (schema.edgeSchemas().stream().noneMatch(edge -> edge.id() == id)) {
                return id;
            }
        }
    }

    private static boolean connects(EdgeSchema edge, String vertexName) {
        return edge.direction() != EdgeDirection.DIRECTED
                && edge.source() != null && vertexName.equals(edge.source().vertexName())
                && edge.target() != null && vertexName.equals(edge.target().vertexName());
    }
}

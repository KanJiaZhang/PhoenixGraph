package com.pengcheng.phoenixgraph.query.storage;

import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the property <em>names</em> a query asks for into the property <em>indexes</em> a
 * storage backend stores them under, following the graph schema.
 *
 * <p>Shared by the storage-backed {@link GraphQuery} implementations: a requested name
 * that the schema does not declare is an error (a property read is never silently empty),
 * an empty request means every declared property, and the returned map keeps the schema's
 * declaration order so a response is stable.
 */
public final class SchemaProjections {

    private SchemaProjections() {
    }

    /** The vertex schema of a name; throws when the schema does not declare it. */
    public static VertexSchema requireVertex(GraphSchema schema, String vertexName) {
        if (vertexName == null || vertexName.isBlank()) {
            throw new QueryException("vertexName must not be blank");
        }
        VertexSchema vertexSchema = schema.vertexSchema(vertexName);
        if (vertexSchema == null) {
            throw new QueryException("Unknown vertex schema: " + vertexName);
        }
        return vertexSchema;
    }

    /** The edge schema of a name; throws when the schema does not declare it. */
    public static EdgeSchema requireEdge(GraphSchema schema, String edgeName) {
        if (edgeName == null || edgeName.isBlank()) {
            throw new QueryException("edgeName must not be blank");
        }
        EdgeSchema edgeSchema = schema.edgeSchema(edgeName);
        if (edgeSchema == null) {
            throw new QueryException("Unknown edge schema: " + edgeName);
        }
        return edgeSchema;
    }

    /**
     * The requested vertex properties as property index -> name, or every declared
     * property when nothing was requested.
     */
    public static Map<Integer, String> vertexProperties(VertexSchema vertexSchema, List<String> propertyNames) {
        Map<Integer, String> requested = new LinkedHashMap<>();
        List<String> names = propertyNames == null ? List.of() : propertyNames;
        if (names.isEmpty()) {
            for (VertexPropertySchema propertySchema : vertexSchema.properties()) {
                requested.put(propertySchema.id(), propertySchema.name());
            }
            return requested;
        }
        for (String name : names) {
            VertexPropertySchema propertySchema = vertexSchema.property(name);
            if (propertySchema == null) {
                throw new QueryException("Unknown property '" + name + "' in vertex schema '"
                        + vertexSchema.name() + "'");
            }
            requested.put(propertySchema.id(), propertySchema.name());
        }
        return requested;
    }

    /**
     * The requested edge properties as property index -> name, or every declared property
     * when nothing was requested.
     */
    public static Map<Integer, String> edgeProperties(EdgeSchema edgeSchema, List<String> propertyNames) {
        Map<Integer, String> requested = new LinkedHashMap<>();
        List<String> names = propertyNames == null ? List.of() : propertyNames;
        if (names.isEmpty()) {
            for (EdgePropertySchema propertySchema : edgeSchema.properties()) {
                requested.put(propertySchema.id(), propertySchema.name());
            }
            return requested;
        }
        for (String name : names) {
            EdgePropertySchema propertySchema = edgeProperty(edgeSchema, name);
            requested.put(propertySchema.id(), propertySchema.name());
        }
        return requested;
    }

    private static EdgePropertySchema edgeProperty(EdgeSchema edgeSchema, String name) {
        for (EdgePropertySchema propertySchema : edgeSchema.properties()) {
            if (propertySchema.name().equals(name)) {
                return propertySchema;
            }
        }
        throw new QueryException("Unknown property '" + name + "' in edge schema '" + edgeSchema.name() + "'");
    }
}

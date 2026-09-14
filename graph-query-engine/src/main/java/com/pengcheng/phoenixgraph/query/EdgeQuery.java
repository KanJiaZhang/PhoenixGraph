package com.pengcheng.phoenixgraph.query;

import java.util.Arrays;
import java.util.List;

/**
 * Specifies one edge class (edge schema) to fetch from a vertex, plus the
 * property names to project.
 *
 * <p>An empty or {@code null} property list selects <em>all</em> properties
 * declared by the edge schema. Meta properties (createdTime / lastUpdatedTime)
 * are not part of this selection: they are always returned by
 * {@link VertexEdgeQueryEngine}.
 *
 * @param edgeSchema the edge schema name to match
 * @param properties the property names to project; empty means all
 */
public record EdgeQuery(String edgeSchema, List<String> properties) {

    public EdgeQuery {
        if (edgeSchema == null || edgeSchema.isBlank()) {
            throw new IllegalArgumentException("edgeSchema must not be blank");
        }
        if (properties == null) {
            properties = List.of();
        } else {
            for (String property : properties) {
                if (property == null || property.isBlank()) {
                    throw new IllegalArgumentException("property name must not be blank");
                }
            }
            properties = List.copyOf(properties);
        }
    }

    /** An edge query selecting all properties of the given edge schema. */
    public EdgeQuery(String edgeSchema) {
        this(edgeSchema, List.of());
    }

    /** An edge query selecting all properties of the given edge schema. */
    public static EdgeQuery all(String edgeSchema) {
        return new EdgeQuery(edgeSchema);
    }

    /** An edge query selecting only the given property names. */
    public static EdgeQuery select(String edgeSchema, String... propertyNames) {
        return new EdgeQuery(edgeSchema,
                propertyNames == null ? null : Arrays.asList(propertyNames));
    }

    /** Whether this query selects all declared properties (empty property list). */
    public boolean selectsAll() {
        return properties.isEmpty();
    }
}

package com.pengcheng.phoenixgraph.schema.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pengcheng.phoenixgraph.schema.PropertyType;

/**
 * JSON DTO for a property definition.
 *
 * <p>{@code min}/{@code max} and {@code minLength}/{@code maxLength} carry the
 * optional value-level constraints ({@code null} = unconstrained). {@code vertex}
 * carries the vertex schema name for an edge endpoint property ({@code null} for
 * regular properties).
 *
 * <p>{@code display} marks the property as a display property ({@code null} /
 * absent = not a display property).
 *
 * <p>{@code isVector} marks the property as an embedding the graph indexes with
 * HNSW; {@code metric}/{@code m}/{@code efConstruction}/{@code efSearch} are its
 * index tunables ({@code null} = the defaults of
 * {@link com.pengcheng.phoenixgraph.schema.VectorIndexConfig}).
 *
 * <p>{@code isNull} marks an edge target that may be absent ({@code null} = required).
 *
 * <p>{@code isGeo} marks the property as a geographic point the graph indexes with
 * Google S2 cells; {@code geoLevel}/{@code geoMaxCells} are its index tunables
 * ({@code null} = the defaults of
 * {@link com.pengcheng.phoenixgraph.schema.GeoIndexConfig}). Absent values are
 * omitted from the emitted JSON, so schema files stay terse and readable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PropertyDto(int id, String name, String description, PropertyType type,
                          Number min, Number max, Integer minLength, Integer maxLength,
                          String vertex, Boolean display,
                          Boolean isVector, String metric, Integer m, Integer efConstruction,
                          Integer efSearch,
                          Boolean isGeo, Integer geoLevel, Integer geoMaxCells,
                          Boolean isNull, Boolean isFullText, Integer ftNGram) {

    public PropertyDto(int id, String name, String description, PropertyType type) {
        this(id, name, description, type, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    public PropertyDto(int id, String name, String description, PropertyType type,
                       Number min, Number max, Integer minLength, Integer maxLength, String vertex) {
        this(id, name, description, type, min, max, minLength, maxLength, vertex, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    public PropertyDto(int id, String name, String description, PropertyType type,
                       Number min, Number max, Integer minLength, Integer maxLength, String vertex,
                       Boolean display) {
        this(id, name, description, type, min, max, minLength, maxLength, vertex, display,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The vector, geo and full-text tunables in one place (used by the codec when it rebuilds a property). */
    public PropertyDto(int id, String name, String description, PropertyType type,
                       Number min, Number max, Integer minLength, Integer maxLength,
                       String vertex, Boolean display,
                       Boolean isVector, String metric, Integer m, Integer efConstruction,
                       Integer efSearch, Boolean isGeo) {
        this(id, name, description, type, min, max, minLength, maxLength, vertex, display,
                isVector, metric, m, efConstruction, efSearch, isGeo, null, null, null, null, null);
    }
}

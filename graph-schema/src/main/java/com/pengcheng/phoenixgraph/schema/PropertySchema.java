package com.pengcheng.phoenixgraph.schema;

import java.util.Objects;

/**
 * Base schema definition for a property that can be attached to a vertex or an edge.
 *
 * <p>Every property schema has an {@code int} id which is its unique index within
 * its parent schema (a {@link VertexSchema} or an {@link EdgeSchema}), plus a
 * {@code name}, a human-readable {@code description}, a {@link PropertyType}
 * and optional value-level {@link PropertyConstraints}.
 *
 * <p>A property may additionally be flagged as a <em>display</em> property:
 * display properties carry the value(s) a UI should show to identify the
 * element (e.g. an {@code Account}'s {@code firstName}/{@code lastName}, a
 * {@code Phone}'s {@code cnCode}/{@code phoneNumber}). See
 * {@link VertexSchema#displayProperties()}.
 *
 * <p>A property may also be flagged as a <em>vector</em> property
 * ({@link #isVector()}): its value is an embedding, and the graph keeps an HNSW
 * index over it (one bidirectional edge class per layer, see
 * {@link VectorIndexSchemas}). Writes of such a property index the element
 * automatically and queries reach the nearest neighbours with
 * {@code nearest(k)}. The optional {@link VectorIndexConfig} carries the metric
 * and the {@code m}/{@code efConstruction}/{@code efSearch} tunables; the
 * defaults are used when the schema does not declare them.
 *
 * <p>Concrete subclasses are {@link VertexPropertySchema}, {@link EdgePropertySchema}
 * and {@link MetaPropertySchema}.
 */
public abstract class PropertySchema {

    private final int id;
    private final String name;
    private final String description;
    private final PropertyType type;
    private final PropertyConstraints constraints;
    private boolean display;
    private VectorIndexConfig vectorConfig;
    private GeoIndexConfig geoConfig;

    protected PropertySchema(int id, String name, String description, PropertyType type) {
        this(id, name, description, type, PropertyConstraints.NONE);
    }

    protected PropertySchema(int id, String name, String description, PropertyType type,
                             PropertyConstraints constraints) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Property schema name must not be blank");
        }
        this.id = id;
        this.name = name;
        this.description = description;
        this.type = Objects.requireNonNull(type, "type");
        this.constraints = constraints == null ? PropertyConstraints.NONE : constraints;
    }

    /** Unique index of this property within its parent schema. */
    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public PropertyType type() {
        return type;
    }

    /** The optional value-level constraints declared for this property (never {@code null}). */
    public PropertyConstraints constraints() {
        return constraints;
    }

    /**
     * Whether this property is a display property, i.e. one whose value(s) a UI
     * should render to identify the owning element. Defaults to {@code false}.
     */
    public boolean display() {
        return display;
    }

    /** Marks (or unmarks) this property as a display property (fluent). */
    public PropertySchema display(boolean display) {
        this.display = display;
        return this;
    }

    /**
     * Whether this property holds an embedding the graph indexes with HNSW (see
     * {@link VectorIndexSchemas}). Defaults to {@code false}.
     */
    public boolean isVector() {
        return vectorConfig != null;
    }

    /**
     * The vector index configuration of this property, or {@code null} when the
     * property is not a vector property.
     */
    public VectorIndexConfig vectorConfig() {
        return vectorConfig;
    }

    /**
     * The vector index configuration of this property, falling back to
     * {@link VectorIndexConfig#DEFAULT} for a property that is not a vector
     * property (handy for callers that do not care whether it is one).
     */
    public VectorIndexConfig vectorConfigOrDefault() {
        return vectorConfig == null ? VectorIndexConfig.DEFAULT : vectorConfig;
    }

    /**
     * Marks this property as a vector property (fluent) and declares how its index
     * is built and queried. Passing {@code null} unmarks it.
     */
    public PropertySchema vector(VectorIndexConfig config) {
        this.vectorConfig = config;
        return this;
    }

    /** Marks this property as a vector property with the default index configuration. */
    public PropertySchema vector(boolean isVector) {
        return vector(isVector ? VectorIndexConfig.DEFAULT : null);
    }

    /**
     * Whether this property holds a geographic point ({@code {lat, lon}}) the graph indexes
     * with Google S2 cells, so a radius query is a range scan over the index. Defaults to
     * {@code false}.
     */
    public boolean isGeo() {
        return geoConfig != null;
    }

    /** The geo index configuration of this property, or {@code null} when it is not a geo property. */
    public GeoIndexConfig geoConfig() {
        return geoConfig;
    }

    /**
     * The geo index configuration of this property, falling back to
     * {@link GeoIndexConfig#DEFAULT} for a property that is not a geo property.
     */
    public GeoIndexConfig geoConfigOrDefault() {
        return geoConfig == null ? GeoIndexConfig.DEFAULT : geoConfig;
    }

    /** Marks this property as a geo property (fluent) with the given index settings. */
    public PropertySchema geo(GeoIndexConfig config) {
        this.geoConfig = config;
        return this;
    }

    /** Marks this property as a geo property with the default index settings. */
    public PropertySchema geo(boolean isGeo) {
        return geo(isGeo ? GeoIndexConfig.DEFAULT : null);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "{id=" + id
                + ", name='" + name + '\''
                + ", type=" + type
                + (display ? ", display=true" : "")
                + (isVector() ? ", isVector=true (" + vectorConfig.describe() + ")" : "")
                + '}';
    }
}

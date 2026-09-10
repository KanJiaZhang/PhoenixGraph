package com.pengcheng.phoenixgraph.schema;

/**
 * Schema of a meta-property.
 *
 * <p>A meta-property is always of {@code long} type. Two meta-properties are
 * predefined: {@code createdTime} (default index {@value #CREATED_TIME_ID}) and
 * {@code lastUpdatedTime} (default index {@value #LAST_UPDATED_TIME_ID}).
 */
public class MetaPropertySchema extends PropertySchema {

    /** Default index of the {@code createdTime} meta-property. */
    public static final int CREATED_TIME_ID = -2;

    /** Default index of the {@code lastUpdatedTime} meta-property. */
    public static final int LAST_UPDATED_TIME_ID = -1;

    public MetaPropertySchema(int id, String name, String description) {
        super(id, name, description, PropertyType.ScalarType.LONG);
    }

    /** The built-in {@code createdTime} meta-property (index -2). */
    public static MetaPropertySchema createdTime() {
        return new MetaPropertySchema(CREATED_TIME_ID, "createdTime",
                "Property creation time (epoch milliseconds)");
    }

    /** The built-in {@code lastUpdatedTime} meta-property (index -1). */
    public static MetaPropertySchema lastUpdatedTime() {
        return new MetaPropertySchema(LAST_UPDATED_TIME_ID, "lastUpdatedTime",
                "Property last-update time (epoch milliseconds)");
    }
}

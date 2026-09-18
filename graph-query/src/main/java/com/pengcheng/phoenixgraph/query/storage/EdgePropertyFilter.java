package com.pengcheng.phoenixgraph.query.storage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A condition on the properties of an edge that a store can evaluate <b>while it decodes</b>.
 *
 * <p>{@code outE('Payment').has('status','SUCCESS')} does not change what is read - the bin of a
 * vertex holds all of its payments - but it does change how much has to be turned into objects:
 * an edge that fails the condition never needs its other properties, its timestamps sorted or its
 * endpoint resolved. So the condition travels to the store instead of being applied to a list of
 * fully built edges.
 *
 * <p>The condition reads only {@link #propertyNames()}, which lets a store decode exactly those
 * properties for the test (and the requested projection afterwards). A missing property never
 * matches, exactly like {@code has} in a traversal.
 *
 * <p>Stores that cannot evaluate conditions (or a store whose edge table is already reduced by a
 * time window) fall back to applying it to the edges they read: the
 * {@link GraphQuery} defaults do that, so every implementation stays correct.
 */
public record EdgePropertyFilter(List<String> propertyNames, Predicate<Map<String, Object>> matches) {

    public EdgePropertyFilter {
        Objects.requireNonNull(matches, "matches");
        propertyNames = propertyNames == null ? List.of() : List.copyOf(propertyNames);
    }

    /** A condition that reads one property, e.g. {@code has('status','SUCCESS')}. */
    public static EdgePropertyFilter of(String propertyName, Predicate<Object> matches) {
        Objects.requireNonNull(propertyName, "propertyName");
        Objects.requireNonNull(matches, "matches");
        return new EdgePropertyFilter(List.of(propertyName), properties -> matches.test(properties.get(propertyName)));
    }

    /** A condition that holds when the property is present at all ({@code has('status')}). */
    public static EdgePropertyFilter present(String propertyName) {
        return of(propertyName, Objects::nonNull);
    }

    /** Whether an edge with the given (already decoded) properties passes. */
    public boolean test(Map<String, Object> properties) {
        return matches.test(properties);
    }
}

package com.pengcheng.phoenixgraph.http.service;

/**
 * The write-path policies a deployment can choose, in one place.
 *
 * <p>Two decisions here are deliberate and are the difference between "the API accepted it" and "the store
 * contains what the schema promised":
 *
 * <ul>
 *   <li><b>{@code requireExistingEndpoints}</b> - writing an edge whose source or destination vertex does not
 *       exist is refused. The store can always write the edge (an edge lives in its endpoints' records, so a
 *       missing endpoint is created as an untyped stub), and that is exactly the trap: a typo in an id
 *       silently materialises a vertex with no type, which then appears in scans and in
 *       {@code elementMap()} with no properties, and nothing in the response said so. A bulk import that
 *       deliberately writes edges before vertices turns this off and says so in its configuration.</li>
 *   <li><b>{@code maxPropertyValueBytes}</b> - a single property value that is larger than this is refused
 *       with a 400 that names the property and the limit. Without it the value travels to the store, which
 *       fails on its own record-size limit with a message about a bin, not about the request.</li>
 * </ul>
 *
 * @param requireExistingEndpoints  refuse an edge whose endpoints are not in the graph (default true)
 * @param maxPropertyValueBytes     largest single property value accepted, in UTF-8 bytes (default 512 KiB)
 */
public record WriteValidation(boolean requireExistingEndpoints, int maxPropertyValueBytes) {

    /** 512 KiB: half of the Aerospike record limit, so a write cannot fail on size alone. */
    public static final int DEFAULT_MAX_PROPERTY_VALUE_BYTES = 512 * 1024;

    public WriteValidation {
        if (maxPropertyValueBytes < 1) {
            throw new IllegalArgumentException("phoenixgraph.write.max-property-value-bytes must be >= 1");
        }
    }

    /** What the service uses when no configuration bean is present (unit tests, tooling). */
    public static WriteValidation defaults() {
        return new WriteValidation(true, DEFAULT_MAX_PROPERTY_VALUE_BYTES);
    }
}

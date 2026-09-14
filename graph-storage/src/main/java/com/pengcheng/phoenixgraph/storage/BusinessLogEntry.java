package com.pengcheng.phoenixgraph.storage;

import java.util.Map;

/**
 * A single business-log entry describing a key write operation.
 *
 * @param operation operation name (e.g. {@code "putVertex"}, {@code "updateVertexProperty"})
 * @param target    the affected record (e.g. a vertex id)
 * @param details   additional context such as property values and timestamps
 * @param timestamp when the entry was created (epoch millis)
 * @param success   whether the operation ultimately succeeded
 */
public record BusinessLogEntry(String operation, String target,
                               Map<String, Object> details,
                               long timestamp, boolean success) {

    public static BusinessLogEntry success(String operation, String target, Map<String, Object> details) {
        return new BusinessLogEntry(operation, target, details, System.currentTimeMillis(), true);
    }

    public static BusinessLogEntry failure(String operation, String target, Map<String, Object> details) {
        return new BusinessLogEntry(operation, target, details, System.currentTimeMillis(), false);
    }

    public static BusinessLogEntry of(String operation, String target,
                                      Map<String, Object> details, boolean success) {
        return new BusinessLogEntry(operation, target, details, System.currentTimeMillis(), success);
    }
}

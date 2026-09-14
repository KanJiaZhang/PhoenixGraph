package com.pengcheng.phoenixgraph.batchload;

/**
 * Result of a single imported row.
 *
 * @param id     vertex id or edge id
 * @param status {@code SUCCESS} / {@code CONFLICT} / {@code FAILED}
 */
public record ItemResult(Object id, String status) {
}

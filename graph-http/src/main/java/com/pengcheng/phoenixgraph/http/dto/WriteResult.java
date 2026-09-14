package com.pengcheng.phoenixgraph.http.dto;

/**
 * Result of a single write operation.
 *
 * @param id     the vertex/edge id
 * @param status {@code SUCCESS} / {@code CONFLICT} / {@code FAILED}
 */
public record WriteResult(Object id, String status) {
}

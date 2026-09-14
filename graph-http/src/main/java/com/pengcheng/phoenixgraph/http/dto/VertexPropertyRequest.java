package com.pengcheng.phoenixgraph.http.dto;

/**
 * A vertex property write payload.
 *
 * @param index           property index (bin suffix)
 * @param value           raw property value
 * @param lastModifiedTime property's last-modified timestamp (epoch millis), optional
 */
public record VertexPropertyRequest(int index, Object value, Long lastModifiedTime) {
}

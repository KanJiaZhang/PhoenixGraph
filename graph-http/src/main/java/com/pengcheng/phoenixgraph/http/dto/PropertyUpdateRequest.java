package com.pengcheng.phoenixgraph.http.dto;

/**
 * Payload for updating a single vertex property.
 *
 * @param value            raw property value
 * @param lastModifiedTime property's last-modified timestamp (epoch millis), optional
 */
public record PropertyUpdateRequest(Object value, Long lastModifiedTime) {
}

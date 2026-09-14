package com.pengcheng.phoenixgraph.http.dto;

/**
 * An edge property write payload.
 *
 * @param index            property index (bin suffix)
 * @param value            raw property value
 * @param createdTime      property creation timestamp (epoch millis), optional
 * @param lastModifiedTime property last-update timestamp (epoch millis), optional
 */
public record EdgePropertyRequest(int index, Object value, Long createdTime, Long lastModifiedTime) {
}

package com.pengcheng.phoenixgraph.http.dto;

import java.util.List;

/**
 * A batch write request.
 *
 * <p>Either the inline {@code vertices}/{@code edges} lists are used, or
 * {@code url} points to a JSON file (http(s)://, file://, or a local path)
 * containing another {@code BatchRequest} definition.
 */
public record BatchRequest(List<VertexRequest> vertices, List<EdgeRequest> edges, String url) {
}

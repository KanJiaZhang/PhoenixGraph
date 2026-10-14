package com.pengcheng.phoenixgraph.http.filter;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;

/**
 * The one 413 answer, written by whichever filter read the body first.
 *
 * <p>Two filters want the body (the key filter for the graph a request names, the limits filter for the same
 * reason), so both can be the one that reads an oversized body. They must answer identically - same status,
 * same JSON shape, same request id - or the contract depends on which filter happened to run.
 */
final class BodyLimits {

    private BodyLimits() {
    }

    static void refuse(HttpServletResponse response, String requestId, BodyTooLargeException tooLarge)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + tooLarge.getMessage()
                + "\",\"exception\":\"com.pengcheng.phoenixgraph.http.filter.BodyTooLargeException\","
                + "\"hint\":\"Send a smaller body, or raise phoenixgraph.limits.max-body-bytes\","
                + "\"requestId\":\"" + requestId + "\"}");
    }
}

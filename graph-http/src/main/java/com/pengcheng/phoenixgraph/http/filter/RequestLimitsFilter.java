package com.pengcheng.phoenixgraph.http.filter;

import com.pengcheng.phoenixgraph.http.service.RequestLimits;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * One line per request and one decision per request: admit it (with a deadline), or refuse it with a status
 * code the client can act on.
 *
 * <p>Three things happen here, in this order, because each one is only useful if the others are in place:
 *
 * <ol>
 *   <li><b>A request id</b> ({@code X-Request-Id}, taken from the caller when it sends one) is put in the MDC
 *       and echoed back, so a user's screenshot and a log line can be tied together - which is the first
 *       thing anyone needs when a query "sometimes" fails;</li>
 *   <li><b>Admission</b>: the graph the request names is read (from the path, or from the body when the
 *       endpoint takes one - the body is buffered so reading it here does not consume it for the controller),
 *       then the rate and concurrency limits decide {@code 429}/{@code 503} with {@code Retry-After};</li>
 *   <li><b>The deadline</b>: an admitted query runs with a budget the engine checks between steps, so a
 *       runaway traversal ends with a message that names the setting instead of holding a thread forever.</li>
 * </ol>
 *
 * <p>A refused request never reaches a controller, and the log line says which limit fired: "why is this
 * 429" is answerable from the logs alone.
 */
public class RequestLimitsFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLimitsFilter.class);

    /** The id a caller can correlate with: theirs if they sent one, ours otherwise. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final int MAX_BODY_BYTES = 1 << 20; // 1 MiB: bigger payloads are writes, not queries

    private final RequestLimits limits;

    public RequestLimitsFilter(RequestLimits limits) {
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        HttpServletRequest effective = request;
        String graph = graphOfPath(request.getRequestURI());
        if (graph == null && "POST".equalsIgnoreCase(request.getMethod())) {
            effective = new CachedBodyRequest(request);
            graph = graphOfBody(effective);
        }

        long started = System.nanoTime();
        RequestLimits.Admission admission = limits.admit(graph);
        if (admission != RequestLimits.Admission.ADMITTED) {
            int status = admission == RequestLimits.Admission.RATE_LIMITED ? 429 : 503;
            long retryAfter = limits.retryAfter().toSeconds();
            response.setStatus(status);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"" + message(admission)
                    + "\",\"exception\":\"com.pengcheng.phoenixgraph.http.service.RequestLimits\","
                    + "\"hint\":\"Retry after " + retryAfter + "s; see phoenixgraph.limits in the configuration\","
                    + "\"requestId\":\"" + requestId + "\"}");
            LOGGER.warn("refused {} {} graph={} reason={} in {}ms", request.getMethod(), request.getRequestURI(),
                    graph == null ? "-" : graph, admission, (System.nanoTime() - started) / 1_000_000);
            MDC.remove("requestId");
            return;
        }

        try {
            chain.doFilter(effective, response);
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("{} {} graph={} status={} in {}ms bytes={} queryId={}",
                        request.getMethod(), request.getRequestURI(), graph == null ? "-" : graph,
                        response.getStatus(), millis,
                        response.getHeader(HttpHeaders.CONTENT_LENGTH) == null ? "-" : response.getHeader(HttpHeaders.CONTENT_LENGTH),
                        selectedQuery(request, effective));
            }
            limits.release(graph);
            MDC.remove("requestId");
        }
    }

    /** The graph named in the path (`/api/v1/graphs/{graph}/...`, `/api/v1/metrics/{graph}`, ...). */
    private static String graphOfPath(String uri) {
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals("graphs") || parts[i].equals("metrics")) {
                String candidate = parts[i + 1];
                if (!candidate.isBlank() && !candidate.equals("flush") && !candidate.equals("reset")
                        && !candidate.equals("prometheus")) {
                    return candidate;
                }
            }
        }
        for (int i = 0; i < parts.length - 2; i++) {
            if (parts[i].equals("schema") || parts[i].equals("agent")) {
                String candidate = parts[i + 1];
                if (!candidate.isBlank() && !candidate.equals("validate") && !candidate.equals("storage-types")
                        && !candidate.equals("tools") && !candidate.equals("ingest")) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * The graph named in the body: {@code "graphName"} for a query, {@code "graph"} for the agent tools. A
     * small pattern match rather than a JSON parse - the filter only needs the name, and parsing the whole
     * document twice per request to get one string would be waste on the hot path.
     */
    private static String graphOfBody(HttpServletRequest request) {
        String body = ((CachedBodyRequest) request).bodyAsString();
        for (String key : new String[] {"graphName", "graph"}) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"" + key + "\"\\s*:\\s*\"([^\"]{1,120})\"").matcher(body);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    /** The query text, when the request carried one, so the log line can be grepped by statement. */
    private static String selectedQuery(HttpServletRequest original, HttpServletRequest effective) {
        if (!"POST".equalsIgnoreCase(original.getMethod()) || !(effective instanceof CachedBodyRequest cached)) {
            return "-";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"query\"\\s*:\\s*\"([^\"]{1,200})\"").matcher(cached.bodyAsString());
        return matcher.find() ? matcher.group(1).replace('"', '\'') : "-";
    }

    private static String message(RequestLimits.Admission admission) {
        return admission == RequestLimits.Admission.RATE_LIMITED
                ? "Too many requests for this graph; the per-second budget of phoenixgraph.limits is spent"
                : "The instance (or this graph) is at its concurrency limit; the request waited "
                        + "phoenixgraph.limits.queue-wait and gave up";
    }

    /**
     * The registration already restricts the filter to {@code /api/*} (see LimitsConfig); this stays as a
     * guard for the case where someone wires the filter directly, which the tests do.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}

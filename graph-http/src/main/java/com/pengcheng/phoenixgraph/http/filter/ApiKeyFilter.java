package com.pengcheng.phoenixgraph.http.filter;

import com.pengcheng.phoenixgraph.http.service.ApiKeys;
import com.pengcheng.phoenixgraph.http.service.AuditLog;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies the caller, decides whether they may do this, and records the call.
 *
 * <p>Runs before the request gate (a request that will be refused for identity reasons must not take a slot)
 * and after nothing: the request id is set here, so an authentication failure is traceable too.
 *
 * <p>Three answers, and each one is deliberate:
 * <ul>
 *   <li><b>401</b> + {@code WWW-Authenticate: Bearer} when the key is missing or unknown - the caller can fix
 *       it, and a client library recognises the status;</li>
 *   <li><b>403</b> when the key is valid but the role or the graph allowlist says no - retrying will not
 *       help, and the message says which rule refused;</li>
 *   <li><b>200-series</b> when it is allowed, with the entry written to the audit log either way (a refused
 *       call is exactly the thing an audit wants to see).</li>
 * </ul>
 *
 * <p>The token is read from {@code Authorization: Bearer ...} or {@code X-API-Key}, and never logged; what
 * the audit records is the key's name.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final Pattern BEARER = Pattern.compile("^Bearer\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_TEXT = Pattern.compile("\"query\"\\s*:\\s*\"([^\"]{1,400})\"");

    private final ApiKeys keys;
    private final AuditLog audit;
    private final int maxBodyBytes;

    public ApiKeyFilter(ApiKeys keys, AuditLog audit) {
        this(keys, audit, 1 << 20);
    }

    public ApiKeyFilter(ApiKeys keys, AuditLog audit, int maxBodyBytes) {
        this.keys = keys;
        this.audit = audit;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put("requestId", requestId);
        // the request id is also a request attribute, so the gate behind this filter reuses this one instead
        // of generating a second id (the id in the response header and the id in the audit entry must be the
        // same string, or a user's screenshot and the audit trail stop being linkable)
        request.setAttribute("phoenixgraph.requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        long started = System.nanoTime();
        String graph = RequestGraphs.ofPath(request.getRequestURI());
        String body = null;
        HttpServletRequest effective = request;
        boolean bodyNeeded = graph == null && !"GET".equalsIgnoreCase(request.getMethod());
        if (bodyNeeded) {
            try {
                effective = new CachedBodyRequest(request, maxBodyBytes);
            } catch (BodyTooLargeException tooLarge) {
                BodyLimits.refuse(response, requestId, tooLarge);
                return;
            }
            body = ((CachedBodyRequest) effective).bodyAsString();
            graph = RequestGraphs.ofBody(body);
        }

        ApiKeys.Principal principal;
        try {
            principal = keys.authenticate(tokenOf(request));
            keys.require(principal, ApiKeys.permissionFor(request.getMethod(), request.getRequestURI()), graph);
        } catch (ApiKeys.UnauthorizedException unauthorized) {
            refuse(request, response, 401, unauthorized.getMessage(), principalName(request, keys), graph, body,
                    started);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"phoenixgraph\"");
            MDC.remove("requestId");
            return;
        } catch (ApiKeys.ForbiddenException forbidden) {
            refuse(request, response, 403, forbidden.getMessage(), principalName(request, keys), graph, body,
                    started);
            MDC.remove("requestId");
            return;
        }

        MDC.put("key", principal.name());
        try {
            chain.doFilter(effective, response);
        } finally {
            audit.record(principal.name(), principal.role(), request.getMethod(), request.getRequestURI(), graph,
                    body == null ? null : queryOf(body), response.getStatus(),
                    (System.nanoTime() - started) / 1_000_000);
            MDC.remove("key");
            MDC.remove("requestId");
        }
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, int status, String message,
                        String keyName, String graph, String body, long started) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + escape(message)
                + "\",\"exception\":\"com.pengcheng.phoenixgraph.http.service.ApiKeys\","
                + "\"hint\":\"Send 'Authorization: Bearer <key>'; see docs/API.md section 2\","
                + "\"requestId\":\"" + MDC.get("requestId") + "\"}");
        audit.record(keyName, "-", request.getMethod(), request.getRequestURI(), graph,
                body == null ? null : queryOf(body), status, (System.nanoTime() - started) / 1_000_000);
        LOGGER.warn("refused {} {} graph={} status={} reason={}", request.getMethod(), request.getRequestURI(),
                graph == null ? "-" : graph, status, message);
    }

    /** Who the caller claimed to be, for the audit line of a refusal: the key name, never the token. */
    private String principalName(HttpServletRequest request, ApiKeys keys) {
        String token = tokenOf(request);
        if (token == null || token.isBlank()) return "anonymous";
        try {
            return keys.authenticate(token).name();
        } catch (RuntimeException unknown) {
            return "unknown";
        }
    }

    private static String queryOf(String body) {
        Matcher matcher = QUERY_TEXT.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String tokenOf(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null) {
            Matcher bearer = BEARER.matcher(header.trim());
            if (bearer.matches()) return bearer.group(1).trim();
        }
        String apiKey = request.getHeader("X-API-Key");
        return apiKey == null ? null : apiKey.trim();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}

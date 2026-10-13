package com.pengcheng.phoenixgraph.http.filter;

import com.pengcheng.phoenixgraph.http.config.SecurityProperties;
import com.pengcheng.phoenixgraph.http.service.ApiKeys;
import com.pengcheng.phoenixgraph.http.service.AuditLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key filter through the servlet API: which status a caller gets, what the audit records, which graph a
 * per-graph key may touch, and that the body survives for the controller.
 */
class ApiKeyFilterTest {

    private static SecurityProperties.Key key(String name, String role, String... graphs) {
        return new SecurityProperties.Key(name, name + "-token", null, role, List.of(graphs));
    }

    private static ApiKeys keys(SecurityProperties.Key... entries) {
        return new ApiKeys(new SecurityProperties(true, List.of(entries)));
    }

    private static ApiKeyFilter filter(ApiKeys keys, AuditLog audit) {
        return new ApiKeyFilter(keys, audit);
    }

    /** A chain that answers 200 and records that it ran. */
    private static FilterChain chain(AtomicInteger calls) {
        return (request, response) -> {
            calls.incrementAndGet();
            ((MockHttpServletResponse) response).setStatus(200);
        };
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private static MockHttpServletRequest query(String graph, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query");
        request.setContentType("application/json");
        request.setContent(("{\"graphName\":\"" + graph + "\",\"query\":\"g.V(1)\"}").getBytes(StandardCharsets.UTF_8));
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    void aMissingKeyIsUnauthorizedWithTheStandardChallenge() throws ServletException, IOException {
        AuditLog audit = new AuditLog();
        AtomicInteger calls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(keys(key("dash", "reader")), audit)
                .doFilter(request("GET", "/api/v1/graphs"), response, chain(calls));

        assertEquals(401, response.getStatus());
        assertEquals("Bearer realm=\"phoenixgraph\"", response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentAsString().contains("Authorization: Bearer"), response.getContentAsString());
        assertEquals(0, calls.get(), "the controller never ran");
        assertEquals(1, audit.size(), "a refused call is exactly what an audit wants to see");
        assertEquals(401, audit.recent(1).get(0).status());
        assertEquals("anonymous", audit.recent(1).get(0).key());
    }

    @Test
    void anUnknownKeyIsUnauthorizedAndAuditedAsUnknown() throws ServletException, IOException {
        AuditLog audit = new AuditLog();
        MockHttpServletRequest request = request("GET", "/api/v1/graphs");
        request.addHeader("Authorization", "Bearer not-a-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(keys(key("dash", "reader")), audit).doFilter(request, response, chain(new AtomicInteger()));

        assertEquals(401, response.getStatus());
        assertEquals("unknown", audit.recent(1).get(0).key(), "the token itself is never recorded");
    }

    @Test
    void anAllowedCallReachesTheControllerAndIsAuditedWithTheKeyName() throws ServletException, IOException {
        AuditLog audit = new AuditLog();
        AtomicInteger calls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(keys(key("dash", "writer")), audit).doFilter(query("payment-graph", "dash-token"), response, chain(calls));

        assertEquals(200, response.getStatus());
        assertEquals(1, calls.get());
        AuditLog.Entry entry = audit.recent(1).get(0);
        assertEquals("dash", entry.key());
        assertEquals("writer", entry.role());
        assertEquals("payment-graph", entry.graph(), "the graph is read from the buffered body");
        assertEquals("g.V(1)", entry.query());
        assertTrue(response.getHeader("X-Request-Id").matches("[0-9a-f]{8}"), response.getHeader("X-Request-Id"));
    }

    @Test
    void aReaderMayQueryButNotWrite() throws ServletException, IOException {
        ApiKeys keys = keys(key("reader", "reader"));
        AuditLog audit = new AuditLog();
        AtomicInteger calls = new AtomicInteger();

        MockHttpServletRequest readRequest = request("GET", "/api/v1/vertices/1000");
        readRequest.addHeader("Authorization", "Bearer reader-token");
        MockHttpServletResponse read = new MockHttpServletResponse();
        filter(keys, audit).doFilter(readRequest, read, chain(calls));
        assertEquals(200, read.getStatus());

        MockHttpServletResponse write = new MockHttpServletResponse();
        MockHttpServletRequest upsert = request("POST", "/api/v1/graphs/payment-graph/vertices");
        upsert.addHeader("Authorization", "Bearer reader-token");
        upsert.setContent("{\"id\":\"1000\"}".getBytes(StandardCharsets.UTF_8));
        filter(keys, audit).doFilter(upsert, write, chain(calls));

        assertEquals(403, write.getStatus());
        assertTrue(write.getContentAsString().contains("'reader'"), write.getContentAsString());
        assertEquals(1, calls.get(), "only the read reached the controller");
    }

    @Test
    void anAdminOnlyEndpointIsRefusedForAWriter() throws ServletException, IOException {
        ApiKeys keys = keys(key("writer", "writer"));
        MockHttpServletRequest schema = request("POST", "/api/v1/schema");
        schema.addHeader("Authorization", "Bearer writer-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(keys, new AuditLog()).doFilter(schema, response, chain(new AtomicInteger()));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("'admin'"), response.getContentAsString());
    }

    @Test
    void aScopedKeyMayNotNameAnotherGraphEvenForARead() throws ServletException, IOException {
        ApiKeys keys = keys(key("scoped", "writer", "payment-graph"));
        MockHttpServletRequest other = request("GET", "/api/v1/metrics/tpc-h-data");
        other.addHeader("Authorization", "Bearer scoped-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(keys, new AuditLog()).doFilter(other, response, chain(new AtomicInteger()));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("tpc-h-data"), response.getContentAsString());
    }

    @Test
    void anOpenInstanceLetsEverythingThroughAndStillAudits() throws ServletException, IOException {
        ApiKeys open = new ApiKeys(new SecurityProperties(false, List.of()));
        AuditLog audit = new AuditLog();
        AtomicInteger calls = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(open, audit).doFilter(query("payment-graph", null), response, chain(calls));

        assertEquals(200, response.getStatus());
        assertEquals(1, calls.get());
        assertEquals("anonymous", audit.recent(1).get(0).key());
    }

    @Test
    void staticFilesAreNotFiltered() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger calls = new AtomicInteger();
        filter(keys(key("dash", "reader")), new AuditLog())
                .doFilter(request("GET", "/index.html"), response, chain(calls));
        assertEquals(200, response.getStatus());
        assertEquals(1, calls.get(), "the UI itself is served without a key");
    }

    @Test
    void theRequestIdIsSharedWithWhateverRunsBehindTheFilter() throws ServletException, IOException {
        MockHttpServletRequest request = query("payment-graph", "dash-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(keys(key("dash", "writer")), new AuditLog()).doFilter(request, response, chain(new AtomicInteger()));

        Object attribute = request.getAttribute("phoenixgraph.requestId");
        assertNotNull(attribute, "the gate behind this filter reuses it instead of generating a second id");
        assertEquals(response.getHeader("X-Request-Id"), attribute);
    }
}

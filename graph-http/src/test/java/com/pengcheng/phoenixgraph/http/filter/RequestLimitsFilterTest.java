package com.pengcheng.phoenixgraph.http.filter;

import com.pengcheng.phoenixgraph.http.config.LimitsProperties;
import com.pengcheng.phoenixgraph.http.service.RequestLimits;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the filter does to a request and to a refusal, with the servlet API mocked rather than a Spring
 * context: the interesting behaviour is the status code, the headers, the body the caller reads, and that the
 * body is still there for the controller afterwards.
 */
class RequestLimitsFilterTest {

    private static LimitsProperties properties(int maxConcurrent, int perGraph, int perSecond) {
        return new LimitsProperties(true, maxConcurrent, Duration.ofMillis(20), perGraph, perSecond,
                Duration.ofSeconds(5), 1, 1 << 20, 65536);
    }

    /** A chain that records the body it saw, so the test can prove nothing was consumed by the filter. */
    private static FilterChain recordingChain(AtomicReference<String> seen) {
        return (request, response) -> {
            seen.set(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            ((MockHttpServletResponse) response).setStatus(200);
        };
    }

    private static MockHttpServletRequest query(String graph) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query");
        request.setContentType("application/json");
        request.setContent(("{\"graphName\":\"" + graph + "\",\"query\":\"g.V()\"}").getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void theRequestBodySurvivesTheFilterAndTheControllerSeesIt() throws ServletException, IOException {
        RequestLimitsFilter filter = new RequestLimitsFilter(new RequestLimits(properties(4, 2, 0)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(query("payment-graph"), response, recordingChain(seen));

        assertEquals(200, response.getStatus());
        assertTrue(seen.get().contains("\"graphName\":\"payment-graph\""), seen.get());
    }

    @Test
    void aRequestIdIsEchoedWhenTheCallerSendsOneAndGeneratedWhenItDoesNot() throws ServletException, IOException {
        RequestLimitsFilter filter = new RequestLimitsFilter(new RequestLimits(properties(4, 2, 0)));
        AtomicReference<String> seen = new AtomicReference<>();

        MockHttpServletRequest withId = new MockHttpServletRequest("GET", "/api/v1/graphs");
        withId.addHeader(RequestLimitsFilter.REQUEST_ID_HEADER, "abc123");
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(withId, first, recordingChain(seen));
        assertEquals("abc123", first.getHeader(RequestLimitsFilter.REQUEST_ID_HEADER));

        MockHttpServletRequest withoutId = new MockHttpServletRequest("GET", "/api/v1/graphs");
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(withoutId, second, recordingChain(seen));
        String generated = second.getHeader(RequestLimitsFilter.REQUEST_ID_HEADER);
        assertNotNull(generated);
        assertEquals(8, generated.length(), generated);
    }

    @Test
    void tooManyRequestsForOneGraphIsA429WithRetryAfterAndAnErrorBody() throws ServletException, IOException {
        RequestLimits limits = new RequestLimits(properties(64, 8, 1));
        RequestLimitsFilter filter = new RequestLimitsFilter(limits);
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(query("payment-graph"), new MockHttpServletResponse(), recordingChain(seen));
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(query("payment-graph"), refused, recordingChain(seen));

        assertEquals(429, refused.getStatus());
        assertEquals("1", refused.getHeader("Retry-After"));
        assertTrue(refused.getContentAsString().contains("Too many requests for this graph"), refused.getContentAsString());
        assertTrue(refused.getContentAsString().contains("requestId"), refused.getContentAsString());
        assertEquals(1L, limits.counters().get("requests.refused"));
        assertEquals(1, seen.get() == null ? 0 : 1, "only the admitted request reached the chain");
    }

    @Test
    void aBusyGraphIsA503ThatNamesTheConcurrencyLimit() throws Exception {
        RequestLimits limits = new RequestLimits(properties(64, 1, 0));
        RequestLimitsFilter filter = new RequestLimitsFilter(limits);
        AtomicReference<String> inside = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                filter.doFilter(query("payment-graph"), new MockHttpServletResponse(), (request, response) -> {
                    inside.set("in");
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Exception ignored) {
                // the test only asserts the second request's verdict
            }
        });
        holder.start();
        while (inside.get() == null) {
            Thread.onSpinWait();
        }

        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(query("payment-graph"), refused, recordingChain(new AtomicReference<>()));

        assertEquals(503, refused.getStatus());
        assertTrue(refused.getContentAsString().contains("concurrency limit"), refused.getContentAsString());
        holder.join(5000);
        assertTrue(!holder.isAlive());
    }

    @Test
    void staticFilesAreNotGated() throws ServletException, IOException {
        RequestLimits limits = new RequestLimits(properties(1, 1, 1));
        RequestLimitsFilter filter = new RequestLimitsFilter(limits);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/index.html"), response, recordingChain(new AtomicReference<>()));

        assertEquals(200, response.getStatus());
        assertEquals(0L, limits.counters().getOrDefault("requests.admitted", 0L), "static files are not gated");
    }
}

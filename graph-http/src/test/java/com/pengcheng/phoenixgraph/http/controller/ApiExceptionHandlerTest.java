package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.ElementNotFoundException;
import com.pengcheng.phoenixgraph.http.controller.ApiExceptionHandler.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure contract: which status a failure gets, and what the body may say.
 *
 * <p>Two things are asserted here because they are part of the API contract (documented in docs/API.md and
 * checked against the running service by verification/verify-input-contract.mjs):
 *
 * <ul>
 *   <li><b>A client mistake is never a 500.</b> A wrong path is 404, a wrong method 405, an unreadable body
 *       400, a missing parameter 400, a wrong parameter type 400, an unsupported media type 415, an element
 *       that is not there 404. Each of these used to fall through to the catch-all. A retry policy would
 *       retry a typo forever, and a monitoring probe cannot tell "the request was wrong" from "the server is
 *       broken".</li>
 *   <li><b>A stack trace is not in the body unless it was asked for.</b> It names absolute paths and library
 *       versions, and a client cannot act on the frames; the 500 body points at the request id and the log
 *       instead.</li>
 * </ul>
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void anUnknownPathIsNotFound() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/nope");
        ResponseEntity<ApiError> response =
                handler.notFound(new NoResourceFoundException(HttpMethod.GET, "/api/v1/nope"), request);

        assertEquals(404, response.getStatusCode().value());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertEquals("Unknown endpoint: GET /api/v1/nope", body.error());
        assertTrue(body.exception().endsWith("NoResourceFoundException"), body.exception());
        assertTrue(body.hint().contains("docs/API.md"), body.hint());
    }

    @Test
    void aKnownPathWithTheWrongMethodIsMethodNotAllowed() {
        ResponseEntity<ApiError> response = handler.methodNotAllowed(
                new HttpRequestMethodNotSupportedException("POST", List.of("GET", "PUT")));

        assertEquals(405, response.getStatusCode().value());
        assertEquals("GET, PUT", response.getHeaders().getFirst("Allow"));
        ApiError body = response.getBody();
        assertNotNull(body);
        assertTrue(body.error().contains("POST"), body.error());
    }

    @Test
    void aQueryErrorIsBadRequest() {
        ResponseEntity<ApiError> response = handler.badRequest(
                new com.pengcheng.phoenixgraph.query.QueryException("Unbalanced parentheses in: g.V("));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Unbalanced parentheses in: g.V(", response.getBody().error());
        assertTrue(response.getBody().hint().startsWith("Check the Gremlin query syntax"));
    }

    @Test
    void anUnreadableBodyIsBadRequestNotAServerError() {
        ResponseEntity<ApiError> response = handler.unreadableBody(
                new HttpMessageNotReadableException("JSON parse error: Unexpected character ('n'): was expecting"
                        + " double-quote to start field name\n at [Source: (String)\"{not json}\"; line: 1,"
                        + " column: 2]"));

        assertEquals(400, response.getStatusCode().value());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertTrue(body.error().startsWith("Malformed request body: JSON parse error"), body.error());
        // Jackson's message ends with a source snippet: the first line is the part that says what is wrong
        assertFalse(body.error().contains("at [Source"), body.error());
    }

    @Test
    void aMissingRequiredParameterIsBadRequestAndNamesIt() {
        ResponseEntity<ApiError> response = handler.missingParameter(
                new MissingServletRequestParameterException("edgeSchemaName", "String"));

        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().error().contains("edgeSchemaName"), response.getBody().error());
    }

    @Test
    void aParameterOfTheWrongTypeIsBadRequestAndNamesIt() {
        ResponseEntity<ApiError> response = handler.wrongParameterType(
                new MethodArgumentTypeMismatchException("abc", Integer.class, "index", null, null));

        assertEquals(400, response.getStatusCode().value());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertTrue(body.error().contains("index"), body.error());
        assertTrue(body.error().contains("Integer"), body.error());
        assertTrue(body.error().contains("abc"), body.error());
    }

    @Test
    void anElementThatIsNotThereIsNotFound() {
        ResponseEntity<ApiError> response =
                handler.elementNotFound(new ElementNotFoundException("No vertex 'ghost' to delete"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals("No vertex 'ghost' to delete", response.getBody().error());
    }

    @Test
    void anUnsupportedMediaTypeIsFourFifteen() {
        ResponseEntity<ApiError> response = handler.unsupportedMediaType(
                new HttpMediaTypeNotSupportedException(org.springframework.http.MediaType.TEXT_PLAIN, List.of()));

        assertEquals(415, response.getStatusCode().value());
        assertTrue(response.getBody().hint().contains("application/json"), response.getBody().hint());
    }

    @Test
    void aServerErrorCarriesNoStackTraceAndPointsAtTheLog() {
        ResponseEntity<ApiError> response = handler.failure(new IllegalStateException("storage is gone"));

        assertEquals(500, response.getStatusCode().value());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertEquals("java.lang.IllegalStateException", body.exception());
        assertTrue(body.error().contains("IllegalStateException"), body.error());
        assertFalse(body.error().contains("storage is gone"),
                "the internals go to the log, not to the client");
        assertTrue(body.stackTrace().isEmpty(), "no stack trace unless phoenixgraph.errors.include-stack-trace");
        assertTrue(body.hint().contains("X-Request-Id"), body.hint());
    }

    @Test
    void theStackTraceIsThereWhenTheFlagAsksForIt() {
        ApiExceptionHandler verbose = new ApiExceptionHandler(true);

        ApiError body = verbose.failure(new IllegalStateException("storage is gone")).getBody();

        assertNotNull(body);
        assertTrue(body.stackTrace().stream().anyMatch((line) -> line.contains("ApiExceptionHandlerTest")),
                "with the flag on, the trace names the caller");
        assertEquals("storage is gone", body.error(), "and the real message is back");
    }
}

package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.controller.ApiExceptionHandler.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status codes a client hits before it gets anything else: a path that is not an endpoint and a
 * supported path with the wrong method.
 *
 * <p>Both used to fall through to the catch-all and answer <b>500</b>, which is worse than unhelpful: a
 * retry policy would retry a typo forever, and a monitoring probe cannot tell "the request was wrong"
 * from "the server is broken". These cases are asserted here because they are part of the API contract
 * (documented in docs/API.md, and checked against the running service by
 * verification/verify-api-errors.mjs).
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
    void anythingElseIsAnInternalErrorWithTheDetails() {
        ResponseEntity<ApiError> response = handler.failure(new IllegalStateException("storage is gone"));

        assertEquals(500, response.getStatusCode().value());
        ApiError body = response.getBody();
        assertNotNull(body);
        assertEquals("storage is gone", body.error());
        assertEquals("java.lang.IllegalStateException", body.exception());
        assertTrue(body.stackTrace().stream().anyMatch((line) -> line.contains("ApiExceptionHandlerTest")),
                "the stack trace names the caller");
    }
}

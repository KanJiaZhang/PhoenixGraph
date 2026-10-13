package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternException;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.QueryTimeoutException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a failed request into a machine- and human-readable error payload.
 *
 * <p>The UI renders it in a modal dialog so a mistyped Gremlin query shows the
 * parser/executor message plus the stack trace instead of a bare status code.
 *
 * <p>The status code is part of the contract, so the two cases a client hits first - a path that does
 * not exist and a supported path with the wrong method - are answered explicitly. Without the handlers
 * below they fall into the catch-all and come back as <b>500</b>, which tells an integrator that the
 * server is broken when the request was simply wrong (a monitoring probe cannot tell the two apart, and
 * neither can a retry policy).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** A failed request: the message, where it came from and the stack trace. */
    public record ApiError(String error, String exception, List<String> stackTrace, String hint) {

        static ApiError of(Throwable throwable, String hint) {
            return new ApiError(messageOf(throwable),
                    throwable.getClass().getName(),
                    stackTraceOf(throwable),
                    hint);
        }

        /** An error whose message is written by the handler rather than taken from the exception. */
        static ApiError named(String message, String exceptionName, Throwable throwable, String hint) {
            return new ApiError(message, exceptionName, stackTraceOf(throwable), hint);
        }

        private static String messageOf(Throwable throwable) {
            String message = throwable.getMessage();
            return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
        }

        private static List<String> stackTraceOf(Throwable throwable) {
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            List<String> frames = new ArrayList<>();
            for (String line : writer.toString().split("\\R")) {
                if (!line.isBlank()) {
                    frames.add(line.strip());
                }
            }
            return List.copyOf(frames);
        }
    }

    /** Malformed query, unknown step, bad parameter, unknown graph, ... -> 400. */
    @ExceptionHandler({QueryException.class, FuzzyPatternException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> badRequest(RuntimeException exception) {
        LOGGER.debug("Rejected request", exception);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(exception, "Check the Gremlin query syntax and the request parameters"));
    }

    /**
     * A query that spent its budget -&gt; 503 with Retry-After.
     *
     * <p>The request was well formed, so a 400 would send the caller off to fix a query that has nothing
     * wrong with it. 503 says "this instance will not spend more on it now", which is what a retry policy
     * and a human both need to hear; the message names the setting to raise.
     */
    @ExceptionHandler(QueryTimeoutException.class)
    public ResponseEntity<ApiError> timeout(QueryTimeoutException exception) {
        LOGGER.warn("Query timed out: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiError.of(exception, "Narrow the query or raise phoenixgraph.limits.request-timeout"));
    }

    /** A path that is not an endpoint -&gt; 404, not the catch-all's 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException exception, HttpServletRequest request) {
        LOGGER.debug("Unknown path", exception);
        // Spring's own message is about "static resources", which is true of the handler and wrong as an API
        // error: an integrator needs the path they asked for and where the list of endpoints is
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.named("Unknown endpoint: " + request.getMethod() + " " + request.getRequestURI(),
                        NoResourceFoundException.class.getName(), exception,
                        "GET /api/v1/graphs lists what is served; docs/API.md lists the endpoints"));
    }

    /** A known path with the wrong verb -&gt; 405, with the allowed methods in the header. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        LOGGER.debug("Wrong method", exception);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, String.join(", ", exception.getSupportedMethods() == null
                        ? new String[] {"GET"} : exception.getSupportedMethods()))
                .body(ApiError.of(exception, "This path exists, but not for that HTTP method (see the Allow header)"));
    }

    /** Anything else is a server-side failure (-&gt; 500) but still returns the details. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception exception) {
        LOGGER.error("Request failed", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(exception, "Check the Gremlin query and the graph data"));
    }
}

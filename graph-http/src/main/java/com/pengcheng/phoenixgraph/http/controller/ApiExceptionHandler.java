package com.pengcheng.phoenixgraph.http.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.aerospike.client.AerospikeException;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternException;
import com.pengcheng.phoenixgraph.http.ConflictException;
import com.pengcheng.phoenixgraph.http.ElementNotFoundException;
import com.pengcheng.phoenixgraph.query.QueryException;
import com.pengcheng.phoenixgraph.query.QueryTimeoutException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a failed request into a machine- and human-readable error payload.
 *
 * <p><b>Every error body has the same shape</b>, so a client parses one thing: {@code error} (a sentence
 * naming the request, the field or the setting at fault), {@code exception} (the class, for a bug report),
 * {@code hint} (what to change) and - only when {@code phoenixgraph.errors.include-stack-trace=true} -
 * {@code stackTrace}. Stack traces are <b>off by default</b>: they hand an absolute path, the library
 * versions and the shape of the code to anyone probing the service, and a client cannot act on the frames
 * anyway. The UI shows whatever the body carries, so the flag keeps the debugging experience that motivated
 * them.
 *
 * <p><b>The status code is part of the contract</b>, and the work here is telling the failure categories
 * apart instead of letting them all fall into the catch-all:
 * <ul>
 *   <li>a path that is not an endpoint -&gt; 404, a known path with the wrong method -&gt; 405. Left to the
 *       catch-all both are 500, which tells an integrator the server is broken when the request was simply
 *       wrong - and a monitoring probe cannot tell the two apart, nor can a retry policy.</li>
 *   <li>a body Spring could not read (malformed JSON, no body, a field of the wrong JSON type) -&gt; 400: the
 *       client sent something unreadable and the server must not blame itself.</li>
 *   <li>a required query parameter that is missing or of the wrong type -&gt; 400, naming the parameter and
 *       the type it needs.</li>
 *   <li>a body in a media type the endpoint does not take -&gt; 415.</li>
 *   <li>an operation naming an element that is not in the graph -&gt; 404 (see
 *       {@link ElementNotFoundException}).</li>
 *   <li>a query that spent its budget -&gt; 503 + {@code Retry-After}: the request was well formed, so a 400
 *       would send the caller off to fix a query with nothing wrong with it.</li>
 *   <li>anything else -&gt; 500, with a message that carries no internals: the details go to the log with the
 *       request id, which is what makes the 500 reportable.</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final boolean includeStackTrace;

    /** Production wiring: stack traces only when {@code phoenixgraph.errors.include-stack-trace} says so. */
    public ApiExceptionHandler() {
        this(false);
    }

    @Autowired
    public ApiExceptionHandler(
            @Value("${phoenixgraph.errors.include-stack-trace:false}") boolean includeStackTrace) {
        this.includeStackTrace = includeStackTrace;
    }

    /** A failed request: the message, where it came from, and the stack trace only when asked for. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ApiError(String error, String exception, List<String> stackTrace, String hint) {

        static ApiError of(Throwable throwable, String hint) {
            return new ApiError(messageOf(throwable),
                    throwable.getClass().getName(),
                    List.of(),
                    hint);
        }

        /** An error whose message is written by the handler rather than taken from the exception. */
        static ApiError named(String message, String exceptionName, List<String> stackTrace, String hint) {
            return new ApiError(message, exceptionName, stackTrace, hint);
        }

        private static String messageOf(Throwable throwable) {
            String message = throwable.getMessage();
            return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
        }
    }

    /**
     * The stack trace, or an empty list.
     *
     * <p>Its own method so every handler that reports an exception builds the body the same way, and so the
     * flag is read in exactly one place.
     */
    private List<String> stackTraceOf(Throwable throwable) {
        if (!includeStackTrace) {
            return List.of();
        }
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

    /** Malformed query, unknown step, bad parameter, unknown graph, ... -&gt; 400. */
    @ExceptionHandler({QueryException.class, FuzzyPatternException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> badRequest(RuntimeException exception) {
        LOGGER.debug("Rejected request", exception);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.named(messageOf(exception), exception.getClass().getName(),
                        stackTraceOf(exception),
                        "Check the Gremlin query syntax and the request parameters"));
    }

    /**
     * A body Spring could not read -&gt; 400: malformed JSON, no body at all, or a field of the wrong JSON
     * type. Only the message is echoed, never the body.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException exception) {
        LOGGER.debug("Unreadable body", exception);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.named("Malformed request body: " + rootMessage(exception),
                        HttpMessageNotReadableException.class.getName(), stackTraceOf(exception),
                        "The body must be JSON matching docs/API.md; an endpoint that takes no body needs none"));
    }

    /** A required query parameter that is not there -&gt; 400, naming it. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException exception) {
        LOGGER.debug("Missing parameter", exception);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.named("Required query parameter '" + exception.getParameterName() + "' is missing",
                        exception.getClass().getName(), stackTraceOf(exception),
                        "docs/API.md lists the parameters of every endpoint"));
    }

    /** A query parameter of the wrong type (an index that is not a number) -&gt; 400, naming it. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> wrongParameterType(MethodArgumentTypeMismatchException exception) {
        LOGGER.debug("Parameter of the wrong type", exception);
        String expected = exception.getRequiredType() == null
                ? "value of another type" : "a " + exception.getRequiredType().getSimpleName();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.named("Parameter '" + exception.getName() + "' must be " + expected
                                + ", got '" + exception.getValue() + "'",
                        exception.getClass().getName(), stackTraceOf(exception),
                        "docs/API.md lists the parameters of every endpoint"));
    }

    /** A body in a media type this endpoint does not take -&gt; 415. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        LOGGER.debug("Unsupported media type", exception);
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiError.named(messageOf(exception), exception.getClass().getName(),
                        stackTraceOf(exception), "Send the body as application/json"));
    }

    /**
     * An operation naming an element that is not in the graph -&gt; 404.
     *
     * <p>Also the answer to a property update or delete, and to a vertex delete, for an id that is not there:
     * the caller asked for something specific, and "SUCCESS" for a vertex that never existed would hide the
     * typo that caused it.
     */
    @ExceptionHandler(ElementNotFoundException.class)
    public ResponseEntity<ApiError> elementNotFound(ElementNotFoundException exception) {
        LOGGER.debug("Element not found", exception);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.named(messageOf(exception), exception.getClass().getName(),
                        stackTraceOf(exception),
                        "GET /api/v1/graphs lists the graphs, and a query can list what a vertex type holds"));
    }

    /**
     * The store is unreachable or too slow -&gt; 503 with Retry-After.
     *
     * <p>The same reasoning as the query timeout: the request was fine, the *deployment* is in trouble, and the
     * caller's best move is to retry shortly - which is what 503 plus {@code Retry-After} says. It used to be a
     * 500 (the catch-all), which tells a retry policy to give up and an operator to look for a bug in the
     * request. The instance itself keeps serving what it can read: an Aerospike graph that cannot be reached
     * does not stop the RocksDB graphs on the same node from answering (see the client policy in
     * {@code StorageConfig}).
     *
     * <p>Five shapes mean "the cluster is not usable right now" and all five are mapped here:
     * {@code Connection} (nothing listening), {@code Timeout} (listening, too slow), {@code InvalidNamespace}
     * (what a read reports while the partition map is empty, i.e. while no node has answered yet),
     * {@code InvalidNode} (the node that held the partition left) and {@code BatchRecords} (a batch read whose
     * nodes all failed, which is what a multi-key read reports when the store is gone). A {@code Command}
     * failure - the server answered with an error - stays a 500: that one is not a retry.
     */
    @ExceptionHandler({AerospikeException.Connection.class, AerospikeException.Timeout.class,
            AerospikeException.InvalidNamespace.class, AerospikeException.InvalidNode.class,
            AerospikeException.BatchRecords.class})
    public ResponseEntity<ApiError> storageUnavailable(AerospikeException exception) {
        LOGGER.warn("Storage unavailable: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiError.named(messageOf(exception), exception.getClass().getName(),
                        stackTraceOf(exception),
                        "The storage this graph uses did not answer; a retry may succeed. "
                                + "GET /api/v1/health says the instance is alive (docs/OPERATIONS.md)"));
    }

    /**
     * The request is fine but clashes with the current state -&gt; 409.
     *
     * <p>A checkpoint whose target directory already exists is the case this exists for. It used to be a 500
     * whose message said "already exists", which meant the only way to learn the cause was to read an internal
     * error string - and a retry policy would keep asking.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException exception) {
        LOGGER.debug("Conflicting request", exception);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.named(messageOf(exception), exception.getClass().getName(),
                        stackTraceOf(exception),
                        "Choose another target (a name that does not exist), or delete the existing one first"));
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
                .body(ApiError.named(messageOf(exception), exception.getClass().getName(),
                        stackTraceOf(exception),
                        "Narrow the query or raise phoenixgraph.limits.request-timeout"));
    }

    /** A path that is not an endpoint -&gt; 404, not the catch-all's 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException exception, HttpServletRequest request) {
        LOGGER.debug("Unknown path", exception);
        // Spring's own message is about "static resources", which is true of the handler and wrong as an API
        // error: an integrator needs the path they asked for and where the list of endpoints is
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.named("Unknown endpoint: " + request.getMethod() + " " + request.getRequestURI(),
                        NoResourceFoundException.class.getName(), stackTraceOf(exception),
                        "GET /api/v1/graphs lists what is served; docs/API.md lists the endpoints"));
    }

    /** A known path with the wrong verb -&gt; 405, with the allowed methods in the header. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        LOGGER.debug("Wrong method", exception);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, String.join(", ", exception.getSupportedMethods() == null
                        ? new String[] {"GET"} : exception.getSupportedMethods()))
                .body(ApiError.named(messageOf(exception), exception.getClass().getName(),
                        stackTraceOf(exception),
                        "This path exists, but not for that HTTP method (see the Allow header)"));
    }

    /**
     * Anything else is a server-side failure (-&gt; 500), with the details in the log rather than the body.
     *
     * <p>The client gets the exception's simple name and the request id: enough to report the bug, nothing
     * that describes the inside of the server to someone who is only guessing.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception exception) {
        LOGGER.error("Request failed", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.named(includeStackTrace
                                // a debugging instance says what broke; a production one says how to report it
                                ? messageOf(exception)
                                : "The request failed inside the server (" + exception.getClass().getSimpleName()
                                        + "); the log holds the stack trace",
                        exception.getClass().getName(), stackTraceOf(exception),
                        "This is a bug, not a bad request: report it with the X-Request-Id of this response"));
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    /** The deepest message of a wrapped chain, first line only: Jackson appends a source snippet with paths. */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        if (message == null) {
            return throwable.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return (newline < 0 ? message : message.substring(0, newline)).strip();
    }
}

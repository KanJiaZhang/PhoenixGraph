package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternException;
import com.pengcheng.phoenixgraph.query.QueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a failed request into a machine- and human-readable error payload.
 *
 * <p>The UI renders it in a modal dialog so a mistyped Gremlin query shows the
 * parser/executor message plus the stack trace instead of a bare status code.
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

    /** Anything else is a server-side failure (-&gt; 500) but still returns the details. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failure(Exception exception) {
        LOGGER.error("Request failed", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(exception, "Check the Gremlin query and the graph data"));
    }
}

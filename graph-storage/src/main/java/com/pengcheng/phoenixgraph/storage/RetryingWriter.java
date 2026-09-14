package com.pengcheng.phoenixgraph.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Executes a key write with bounded retries and mandatory logging.
 *
 * <p>Each write is attempted up to {@link #DEFAULT_MAX_ATTEMPTS} times (three by
 * default). On success a success entry is written to the {@link BusinessLog}; a
 * {@link WriteConflictException} (CAS/version conflict) is recorded only in the
 * business log; after the final failed attempt the operation is recorded in the
 * {@link ErrorLog} (a separate {@link BusinessLog} sink) for later
 * inspection/enrichment, plus a failure entry in the business log.
 */
public final class RetryingWriter {

    /** Default number of write attempts before giving up. */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final int maxAttempts;
    private final BusinessLog businessLog;
    private final ErrorLog errorLog;

    public RetryingWriter(BusinessLog businessLog, ErrorLog errorLog) {
        this(DEFAULT_MAX_ATTEMPTS, businessLog, errorLog);
    }

    public RetryingWriter(int maxAttempts, BusinessLog businessLog, ErrorLog errorLog) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.businessLog = Objects.requireNonNull(businessLog, "businessLog");
        this.errorLog = Objects.requireNonNull(errorLog, "errorLog");
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Runs the write, retrying transient failures up to {@code maxAttempts}
     * times.
     */
    public WriteOutcome execute(String operation, String target,
                                Map<String, Object> payload, WriteAction action) {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                action.run();
                businessLog.record(BusinessLogEntry.success(operation, target, payload));
                return WriteOutcome.SUCCESS;
            } catch (WriteConflictException conflict) {
                businessLog.record(BusinessLogEntry.failure(operation, target,
                        conflictDetails(payload, conflict.getMessage())));
                return WriteOutcome.CONFLICT;
            } catch (Exception e) {
                last = e;
            }
        }
        errorLog.record(BusinessLogEntry.failure(operation, target,
                failureDetails(payload, last)));
        businessLog.record(BusinessLogEntry.failure(operation, target, payload));
        return WriteOutcome.FAILED;
    }

    private static Map<String, Object> conflictDetails(Map<String, Object> payload, String message) {
        Map<String, Object> details = copy(payload);
        details.put("conflict", true);
        details.put("message", message);
        return details;
    }

    private static Map<String, Object> failureDetails(Map<String, Object> payload, Exception error) {
        Map<String, Object> details = copy(payload);
        details.put("error", error == null ? "unknown" : error.toString());
        return details;
    }

    private static Map<String, Object> copy(Map<String, Object> payload) {
        return payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    /** A single write operation. */
    @FunctionalInterface
    public interface WriteAction {
        void run() throws Exception;
    }
}

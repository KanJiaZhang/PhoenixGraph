package com.pengcheng.phoenixgraph.storage;

/**
 * Signals that a write was rejected by a CAS/version check (e.g. an Aerospike
 * filtered-out predicate, a generation mismatch, or an already-existing record).
 *
 * <p>This is not a transient failure: {@link RetryingWriter} does not retry it
 * and does not record it in the {@link ErrorLog}; it only writes a business-log
 * entry.
 */
public class WriteConflictException extends RuntimeException {

    public WriteConflictException(String message) {
        super(message);
    }

    public WriteConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

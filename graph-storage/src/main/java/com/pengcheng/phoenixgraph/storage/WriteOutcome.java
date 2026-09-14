package com.pengcheng.phoenixgraph.storage;

/**
 * Outcome of a key write executed through {@link RetryingWriter}.
 */
public enum WriteOutcome {
    /** The write was applied. */
    SUCCESS,
    /** A CAS/version conflict prevented the write (not an error; only logged). */
    CONFLICT,
    /** The write failed after all retries and was recorded in the error log. */
    FAILED
}

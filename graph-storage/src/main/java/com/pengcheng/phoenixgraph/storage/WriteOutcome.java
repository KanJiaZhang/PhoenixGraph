package com.pengcheng.phoenixgraph.storage;

/**
 * Outcome of a key write executed through {@link RetryingWriter}.
 */
public enum WriteOutcome {
    /** The write was applied. */
    SUCCESS,
    /**
     * The write changed nothing: every incoming property (or edge) was older than the stored
     * copy, so nothing was written. Not an error and not a CAS conflict that the caller must
     * retry - the record already holds the newest state.
     */
    SUCCESS_STALE,
    /** A CAS/version conflict prevented the write (not an error; only logged). */
    CONFLICT,
    /** The write failed after all retries and was recorded in the error log. */
    FAILED
}

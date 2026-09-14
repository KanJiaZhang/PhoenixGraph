package com.pengcheng.phoenixgraph.storage;

/**
 * Business log: records key write operations for audit and traceability.
 *
 * <p>Every key write (vertex/edge create, update or delete) should be recorded
 * here, regardless of success or failure. The concrete sink is pluggable (JUL,
 * a database table, a message queue, ...).
 */
public interface BusinessLog {

    /** Records one business-log entry. */
    void record(BusinessLogEntry entry);
}

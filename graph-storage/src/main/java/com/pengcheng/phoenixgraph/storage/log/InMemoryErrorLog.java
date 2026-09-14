package com.pengcheng.phoenixgraph.storage.log;

import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;
import com.pengcheng.phoenixgraph.storage.ErrorLog;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link ErrorLog} collecting failed writes (as {@link BusinessLogEntry})
 * so they can be inspected or enriched later. Useful for tests and simple
 * single-node deployments.
 */
public class InMemoryErrorLog implements ErrorLog {

    private final List<BusinessLogEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void record(BusinessLogEntry entry) {
        entries.add(entry);
    }

    /** Immutable view of the recorded errors. */
    public List<BusinessLogEntry> entries() {
        return List.copyOf(entries);
    }
}

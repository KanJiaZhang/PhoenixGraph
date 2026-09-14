package com.pengcheng.phoenixgraph.storage.log;

import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link BusinessLog} collecting entries in insertion order. Useful
 * for tests and for simple single-node deployments.
 */
public class InMemoryBusinessLog implements BusinessLog {

    private final List<BusinessLogEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void record(BusinessLogEntry entry) {
        entries.add(entry);
    }

    /** Immutable view of the recorded entries. */
    public List<BusinessLogEntry> entries() {
        return List.copyOf(entries);
    }
}

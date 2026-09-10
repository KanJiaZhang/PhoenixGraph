package com.pengcheng.phoenixgraph.storage.memory;

import com.pengcheng.phoenixgraph.core.Graph;
import com.pengcheng.phoenixgraph.storage.StorageEngine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link StorageEngine} useful for development and testing.
 */
public class InMemoryStorageEngine implements StorageEngine {

    public static final String NAME = "in-memory";

    private final Map<String, Graph> graphs = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Graph open(String database) {
        return graphs.computeIfAbsent(database, d -> new InMemoryGraph());
    }

    @Override
    public void create(String database) {
        graphs.putIfAbsent(database, new InMemoryGraph());
    }

    @Override
    public void drop(String database) {
        Graph graph = graphs.remove(database);
        if (graph != null) {
            graph.close();
        }
    }

    @Override
    public void close() {
        graphs.values().forEach(Graph::close);
        graphs.clear();
    }
}

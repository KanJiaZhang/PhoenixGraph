package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.schema.GraphSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The graphs a single service instance serves, keyed by graph name and kept in
 * configuration order (the primary graph first).
 */
public class GraphRegistry implements GraphResolver {

    /** Concurrent, because the graph builder registers a schema while queries are running. */
    private final Map<String, GraphBinding> byName = new ConcurrentHashMap<>();
    /** The order graphs were added in (the primary graph first), for the UI's dropdown. */
    private final List<String> order = Collections.synchronizedList(new ArrayList<>());

    public GraphRegistry(List<GraphBinding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        for (GraphBinding binding : bindings) {
            register(binding);
        }
    }

    /**
     * Adds or replaces a graph, keeping its position in the order when it already exists.
     * Used at startup and by the graph builder, so a schema created in the UI is queryable
     * immediately.
     */
    public void register(GraphBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (byName.put(binding.name(), binding) == null) {
            order.add(binding.name());
        }
    }

    /** Whether a graph is served. */
    public boolean contains(String graphName) {
        return graphName != null && byName.containsKey(graphName);
    }

    @Override
    public GraphBinding require(String graphName) {
        if (graphName == null || graphName.isBlank()) {
            throw new IllegalArgumentException("graphName must not be blank");
        }
        GraphBinding binding = byName.get(graphName);
        if (binding == null) {
            throw new IllegalArgumentException(
                    "Unknown graph '" + graphName + "'; available graphs: " + String.join(", ", byName.keySet()));
        }
        return binding;
    }

    @Override
    public List<GraphSchema> schemas() {
        List<String> names;
        synchronized (order) {
            names = List.copyOf(order);
        }
        return names.stream().map(byName::get).filter(Objects::nonNull)
                .map(GraphBinding::schema).collect(Collectors.toUnmodifiableList());
    }
}

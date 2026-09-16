package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.schema.GraphSchema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The graphs a single service instance serves, keyed by graph name and kept in
 * configuration order (the primary graph first).
 */
public class GraphRegistry implements GraphResolver {

    private final Map<String, GraphBinding> byName;

    public GraphRegistry(List<GraphBinding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        Map<String, GraphBinding> map = new LinkedHashMap<>();
        for (GraphBinding binding : bindings) {
            map.putIfAbsent(binding.name(), binding);
        }
        // LinkedHashMap + unmodifiableMap, so iteration keeps the configuration order
        // (Map.copyOf would not)
        this.byName = Collections.unmodifiableMap(map);
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
        return byName.values().stream().map(GraphBinding::schema).collect(Collectors.toUnmodifiableList());
    }
}

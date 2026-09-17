package com.pengcheng.phoenixgraph.query.cypher;

import com.pengcheng.phoenixgraph.query.result.Subgraph;

import java.util.List;
import java.util.Map;

/**
 * The result of a Cypher query: the projected rows plus the subgraph the query
 * touched.
 *
 * @param columns  the {@code RETURN} column names, in order
 * @param rows     one map per row (column name to value); a bare variable projects the
 *                 element's description, {@code a.id} the value itself
 * @param subgraph the vertices and edges the match visited (empty unless collected)
 */
public record CypherResult(List<String> columns, List<Map<String, Object>> rows, Subgraph subgraph) {

    public static final CypherResult EMPTY = new CypherResult(List.of(), List.of(), Subgraph.EMPTY);

    public CypherResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        subgraph = subgraph == null ? Subgraph.EMPTY : subgraph;
    }

    /** Compact description used in logs and tests. */
    public String describe() {
        return rows.size() + " row(s) " + columns + ", subgraph " + subgraph.vertices().size()
                + " vertices / " + subgraph.edges().size() + " edges";
    }
}

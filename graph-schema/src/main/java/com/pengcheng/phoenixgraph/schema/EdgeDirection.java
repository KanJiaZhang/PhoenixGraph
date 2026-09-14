package com.pengcheng.phoenixgraph.schema;

/**
 * Direction semantics declared by an {@link EdgeSchema}.
 *
 * <p>Controls how an edge of this class relates to its {@code source} and
 * {@code target} endpoints:
 * <ul>
 *   <li>{@link #DIRECTED} — 单向边: only {@code source -> target} exists; the
 *       edge is an out-edge of the source and an in-edge of the target.</li>
 *   <li>{@link #UNDIRECTED} — 无向边: source and target are interchangeable.</li>
 *   <li>{@link #BIDIRECTIONAL} — 双向边: both {@code source -> target} and
 *       {@code target -> source} exist.</li>
 * </ul>
 */
public enum EdgeDirection {
    DIRECTED,
    UNDIRECTED,
    BIDIRECTIONAL
}

package com.pengcheng.phoenixgraph.query.cypher;

import java.util.List;
import java.util.Objects;

/**
 * A parsed openCypher read query: {@code MATCH ... [WHERE ...] RETURN ... [ORDER BY ...] [SKIP n] [LIMIT n]}.
 *
 * <p>Supported shape (see {@link CypherParser}):
 * <ul>
 *   <li>one or more comma-separated path patterns, each
 *       {@code (v:Label {prop: value})-[r:TYPE|TYPE2]->(w:Label)} with {@code ->},
 *       {@code <-} or undirected {@code -}, and optional variable length
 *       ({@code -[:TYPE*1..3]->});</li>
 *   <li>an optional {@code WHERE} predicate over the bound variables;</li>
 *   <li>{@code RETURN [DISTINCT] *|expr [AS alias], ...} including the aggregations
 *       {@code count/sum/avg/min/max/collect};</li>
 *   <li>{@code ORDER BY expr [ASC|DESC], ...}, {@code SKIP n}, {@code LIMIT n}.</li>
 * </ul>
 *
 * <p>Writes ({@code CREATE}, {@code MERGE}, {@code SET}, {@code DELETE}, ...) are not
 * supported: the graph is written through the storage API.
 *
 * @param matches      the pattern clauses ({@code MATCH a, b} is one clause with two patterns)
 * @param where        the predicate, or {@code null}
 * @param projection   the {@code RETURN} items
 * @param distinct     whether {@code DISTINCT} was given
 * @param orderBy      the ordering keys (empty = insertion order)
 * @param skip         rows to skip ({@code 0} when absent)
 * @param limit        maximum rows ({@code -1} = unlimited)
 */
public record CypherQuery(List<CypherPattern.Path> matches, CypherExpression where,
                          List<CypherQuery.ReturnItem> projection, boolean distinct,
                          List<CypherQuery.OrderKey> orderBy, long skip, long limit) {

    public CypherQuery {
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        projection = List.copyOf(Objects.requireNonNull(projection, "projection"));
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("a Cypher query needs at least one MATCH pattern");
        }
        if (projection.isEmpty()) {
            throw new IllegalArgumentException("a Cypher query needs a RETURN clause");
        }
    }

    /** One {@code RETURN} item: the expression, its source text and an optional alias. */
    public record ReturnItem(CypherExpression expression, String text, String alias) {

        /** Column name of this item: the alias, or the expression as written. */
        public String column() {
            return alias != null ? alias : text;
        }

        /** Whether the item contains an aggregation, i.e. the projection groups rows. */
        public boolean aggregated() {
            return expression.isAggregating();
        }
    }

    /** One {@code ORDER BY} key. */
    public record OrderKey(CypherExpression expression, String text, boolean descending) {
    }
}

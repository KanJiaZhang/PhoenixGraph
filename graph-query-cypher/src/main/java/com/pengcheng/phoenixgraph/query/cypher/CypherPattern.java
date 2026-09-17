package com.pengcheng.phoenixgraph.query.cypher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pattern of a {@code MATCH} clause: a chain of nodes connected by relationships.
 *
 * <p>{@code (a:Account {id:'135'})-[p:Payment]->(b:Account)} is the start node
 * {@code a}, one step ({@code p} pointing out at {@code b}).
 */
public final class CypherPattern {

    private CypherPattern() {
    }

    /** Direction of a relationship pattern. */
    public enum Direction {
        /** {@code ->}: from the left node to the right one (their out-edges). */
        OUT,
        /** {@code <-}: from the right node to the left one (their in-edges). */
        IN,
        /** {@code -}: either way. */
        BOTH
    }

    /** A node pattern: {@code (variable:Label {prop: value})}. Every part is optional. */
    public record Node(String variable, String label, Map<String, CypherExpression> properties) {

        public Node {
            properties = properties == null ? Map.of() : new LinkedHashMap<>(properties);
        }

        /** Whether this node constrains {@code key} to a value. */
        public CypherExpression property(String key) {
            for (Map.Entry<String, CypherExpression> entry : properties.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    /**
     * A relationship pattern: {@code -[variable:TYPE1|TYPE2*min..max]->}.
     *
     * @param variable  the relationship variable, or {@code null}
     * @param types     the relationship types (empty = any edge class)
     * @param direction which way the relationship points
     * @param minHops   minimum number of hops ({@code 1} unless variable length)
     * @param maxHops   maximum number of hops ({@code minHops} unless variable length)
     */
    public record Relationship(String variable, List<String> types, Direction direction, int minHops, int maxHops) {

        public Relationship {
            types = types == null ? List.of() : List.copyOf(types);
            if (minHops < 1) {
                throw new IllegalArgumentException("a relationship needs at least one hop");
            }
            if (maxHops < minHops) {
                throw new IllegalArgumentException("the hop range must not be empty");
            }
        }

        /** Whether the relationship spans a range of hops, e.g. {@code *1..3}. */
        public boolean variableLength() {
            return maxHops > 1;
        }
    }

    /** One step of a path: the relationship and the node it arrives at. */
    public record Step(Relationship relationship, Node node) {
    }

    /**
     * A path pattern.
     *
     * @param start the first node
     * @param steps the relationships and nodes that follow it
     */
    public record Path(Node start, List<Step> steps) {

        public Path {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        /** Every node pattern in the path, in order. */
        public List<Node> nodes() {
            List<Node> nodes = new java.util.ArrayList<>(steps.size() + 1);
            nodes.add(start);
            for (Step step : steps) {
                nodes.add(step.node());
            }
            return nodes;
        }

        /** Every relationship pattern in the path, in order. */
        public List<Relationship> relationships() {
            return steps.stream().map(Step::relationship).toList();
        }

        /** Node at position {@code index} of the path (0 = start). */
        public Node node(int index) {
            return index == 0 ? start : steps.get(index - 1).node();
        }
    }
}

package com.pengcheng.phoenixgraph.query.gremlin;

import java.util.Set;

/**
 * The condition a {@code filter} step keeps its elements by.
 *
 * <p>It is {@link GremlinExpression} seen from the step's point of view: the property-key grammar
 * the step grew up with ({@code relation.equals('directedBy') || xrelation = 'directed'},
 * {@code key equals value}, a bare key meaning "present") is a subset of the expression language,
 * so both spellings compile to the same predicate - and the Groovy accessors
 * ({@code it.get().value('k')}, {@code it.get().label()}) work in both.
 *
 * <p>{@link #propertyKeys()} is what the projection planner asks storage for; the label/id
 * accessors are answered from the element itself and are therefore not part of it.
 */
public final class GremlinFilterExpression {

    private final GremlinExpression expression;

    private GremlinFilterExpression(GremlinExpression expression) {
        this.expression = expression;
    }

    /** Parses a filter expression; throws {@link com.pengcheng.phoenixgraph.query.QueryException} when malformed. */
    public static GremlinFilterExpression parse(String expression) {
        return new GremlinFilterExpression(GremlinExpression.parse(expression));
    }

    /** Whether the element satisfies the condition. */
    public boolean test(GremlinElement element) {
        return expression.test(element);
    }

    /** The property keys the condition reads (used for read projection planning). */
    public Set<String> propertyKeys() {
        return expression.propertyKeys();
    }

    /** Whether the condition reads the element's label or id rather than only its properties. */
    public boolean readsElementIdentity() {
        return expression.readsElementIdentity();
    }
}

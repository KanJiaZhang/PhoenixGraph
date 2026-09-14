package com.pengcheng.phoenixgraph.query.gremlin;

import java.util.List;

/**
 * One traversal step: maps the incoming traversers to the outgoing traversers.
 *
 * <p>Steps are stateless; any parameters (labels, keys, values, ...) are captured
 * when the step is created by a {@link GremlinStepRegistry}.
 */
@FunctionalInterface
public interface GremlinStep {

    List<Object> apply(GremlinContext context, List<Object> input);
}

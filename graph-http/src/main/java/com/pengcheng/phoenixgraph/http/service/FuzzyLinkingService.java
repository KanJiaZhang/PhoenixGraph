package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.fuzzy.FuzzyContext;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyLink;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyLinks;
import com.pengcheng.phoenixgraph.fuzzy.FuzzyPatternException;
import com.pengcheng.phoenixgraph.fuzzy.FuzzySchema;
import com.pengcheng.phoenixgraph.http.dto.EdgePropertyRequest;
import com.pengcheng.phoenixgraph.http.dto.EdgeRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexPropertyRequest;
import com.pengcheng.phoenixgraph.http.dto.VertexRequest;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Links a vertex: turns a context into the keys its patterns produce and writes the link vertices
 * and the edges to them.
 *
 * <p>Writing the links is the same kind of write as any other - it goes through {@link GraphService},
 * so a link vertex is validated, indexed and conflict-checked like a vertex a client sent. The point
 * is that nobody has to know which accounts belong together: the fragments do.
 *
 * <p>Linking is idempotent: the id of a link vertex is the pattern and the key, and the id of an edge
 * is the link and the member, so linking an account twice leaves one node with one edge - which is
 * what makes it safe to link on every write.
 */
@Service
public class FuzzyLinkingService {

    private final GraphService service;
    private final GraphRegistry graphs;
    private final FuzzyPatternStore patterns;

    public FuzzyLinkingService(GraphService service, GraphRegistry graphs, FuzzyPatternStore patterns) {
        this.service = service;
        this.graphs = graphs;
        this.patterns = patterns;
    }

    /**
     * Writes every link the graph's patterns produce for a context.
     *
     * @param graph      the graph to write into
     * @param vertexId   the vertex being linked (usually an account)
     * @param context    the details the patterns read
     * @return one entry per pattern that applied, with the key it produced
     */
    public List<FuzzyLink> link(String graph, Object vertexId, FuzzyContext context) {
        GraphSchema schema = graphs.require(graph).schema();
        if (!FuzzySchema.isSupported(schema)) {
            throw new FuzzyPatternException("Graph '" + graph + "' declares no " + FuzzySchema.LINK_VERTEX
                    + " vertex, " + FuzzySchema.PATTERN_VERTEX + " vertex and " + FuzzySchema.LINK_EDGE
                    + " edge, so it cannot hold fuzzy links");
        }
        List<FuzzyLink> plan = FuzzyLinks.plan(patterns.registryOf(graph), context);
        if (plan.isEmpty()) {
            return List.of();
        }
        GraphService bound = service.forGraph(graphs, graph);
        for (FuzzyLink link : plan) {
            bound.upsertVertex(linkVertex(schema, link, vertexId));
            bound.upsertEdge(linkEdge(schema, link, vertexId));
        }
        return plan;
    }

    /** The link vertices a context produces, without writing anything (a preview). */
    public List<FuzzyLink> plan(String graph, FuzzyContext context) {
        return FuzzyLinks.plan(patterns.registryOf(graph), context);
    }

    private VertexRequest linkVertex(GraphSchema schema, FuzzyLink link, Object vertexId) {
        VertexSchema linkSchema = schema.vertexSchema(FuzzySchema.LINK_VERTEX);
        long now = System.currentTimeMillis();
        List<VertexPropertyRequest> properties = new ArrayList<>();
        properties.add(new VertexPropertyRequest(index(linkSchema, FuzzySchema.PATTERN_ID_PROPERTY),
                link.patternId(), now));
        properties.add(new VertexPropertyRequest(index(linkSchema, FuzzySchema.KEY_PROPERTY), link.key(), now));
        properties.add(new VertexPropertyRequest(index(linkSchema, FuzzySchema.PATTERN_PROPERTY),
                link.patternText(), now));
        return new VertexRequest(link.linkVertexId(), FuzzySchema.LINK_VERTEX, properties, now, now);
    }

    private EdgeRequest linkEdge(GraphSchema schema, FuzzyLink link, Object vertexId) {
        EdgeSchema linkEdge = schema.edgeSchema(FuzzySchema.LINK_EDGE);
        long now = System.currentTimeMillis();
        List<EdgePropertyRequest> properties = new ArrayList<>();
        properties.add(new EdgePropertyRequest(index(linkEdge, FuzzySchema.PATTERN_ID_PROPERTY),
                link.patternId(), now, now));
        properties.add(new EdgePropertyRequest(index(linkEdge, FuzzySchema.KEY_PROPERTY), link.key(), now, now));
        return new EdgeRequest(FuzzySchema.linkEdgeId(link.linkVertexId(), vertexId), FuzzySchema.LINK_EDGE,
                link.linkVertexId(), vertexId, properties, now, now);
    }

    private static int index(VertexSchema vertexSchema, String name) {
        VertexPropertySchema property = vertexSchema.property(name);
        if (property == null) {
            throw new FuzzyPatternException("Vertex type '" + vertexSchema.name() + "' has no property '"
                    + name + "'");
        }
        return property.id();
    }

    private static int index(EdgeSchema edgeSchema, String name) {
        EdgePropertySchema property = edgeSchema.property(name);
        if (property == null) {
            throw new FuzzyPatternException("Edge class '" + edgeSchema.name() + "' has no property '"
                    + name + "'");
        }
        return property.id();
    }
}

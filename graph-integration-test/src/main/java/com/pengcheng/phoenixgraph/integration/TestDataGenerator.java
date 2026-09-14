package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.schema.EdgeDirection;
import com.pengcheng.phoenixgraph.schema.EdgePropertySchema;
import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the CSV test dataset for the integration test graph.
 *
 * <p>Vertices: Account (1001, 1002) and IP (2001, 2002). Edges: Payment e1
 * (1001 -&gt; 1002) and Account-&gt;IP e2 (1001 -&gt; 2001).
 *
 * <p>The first CSV column uses the composite id form
 * {@code GraphName::Vertex::VertexName:ID} /
 * {@code GraphName::Edge::EdgeName:ID} so the loader can resolve the element's
 * schema and id.
 */
public final class TestDataGenerator {

    public static final String GRAPH_NAME = "payment-graph";

    /** Base timestamp for generated data; current time so re-runs overwrite (not conflict). */
    public static final long BASE_TIME = System.currentTimeMillis();

    /** Generates {@code vertices.csv} and {@code edges.csv} into the directory. */
    public void generate(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("vertices.csv"), verticesCsv());
        Files.writeString(dir.resolve("edges.csv"), edgesCsv());
    }

    /** The graph schema used by the generated CSV data and verifier. */
    public static GraphSchema schema() {
        VertexSchema account = new VertexSchema(1, "Account", "An account")
                .property(new VertexPropertySchema(1, "accountType", "Account type", PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "primaryAddress", "Primary address", PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(3, "accountStatus", "Account status",
                        PropertyType.enumOf("ACTIVE", "FROZEN", "CLOSED")));
        VertexSchema ip = new VertexSchema(2, "IP", "An IP address")
                .property(new VertexPropertySchema(1, "ipAddress", "IP address string", PropertyType.ScalarType.STRING));

        EdgeSchema payment = new EdgeSchema(1, "Payment", "A payment from a sender to a receiver",
                new EdgePropertySchema(1, "source", "Sender vertex id", PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", "Receiver vertex id", PropertyType.ScalarType.LONG),
                EdgeDirection.DIRECTED);

        EdgeSchema accountToIp = new EdgeSchema(2, "AccountToIP", "An account logs in from an IP",
                new EdgePropertySchema(1, "source", "Account vertex id", PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", "IP vertex id", PropertyType.ScalarType.LONG),
                EdgeDirection.DIRECTED);

        return new GraphSchema(1, GRAPH_NAME, "Account / IP graph schema")
                .vertex(account)
                .vertex(ip)
                .edge(payment)
                .edge(accountToIp);
    }

    static String verticesCsv() {
        return "VertexID,1,2,3,lastUpdatedTime\n"
                + GRAPH_NAME + "::Vertex::Account:1001,savings,Shanghai,ACTIVE," + (BASE_TIME + 1) + "\n"
                + GRAPH_NAME + "::Vertex::Account:1002,checking,Beijing,ACTIVE," + (BASE_TIME + 2) + "\n"
                + GRAPH_NAME + "::Vertex::IP:2001,1.2.3.4,,," + (BASE_TIME + 3) + "\n"
                + GRAPH_NAME + "::Vertex::IP:2002,5.6.7.8,,," + (BASE_TIME + 4) + "\n";
    }

    static String edgesCsv() {
        return "EdgeID,source,destination,1,2,3,lastUpdatedTime\n"
                + GRAPH_NAME + "::Edge::Payment:e1,1001,1002,500.0,USD,SUCCESS," + (BASE_TIME + 5) + "\n"
                + GRAPH_NAME + "::Edge::AccountToIP:e2,1001,2001," + BASE_TIME + ",,," + (BASE_TIME + 6) + "\n";
    }
}

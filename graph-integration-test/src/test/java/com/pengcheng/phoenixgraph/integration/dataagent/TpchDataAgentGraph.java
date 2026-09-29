package com.pengcheng.phoenixgraph.integration.dataagent;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeProperty;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbEdgeUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbPropertyValue;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbVertexUpsert;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The enterprise data-agent graph {@code tpc-h-analytics} on RocksDB-Edge: it builds the semantic
 * layer of {@link TpchDataAgentDataset} and loads it into the store a query engine reads.
 *
 * <p>The node id is the node's own name ({@code metric/revenue}, {@code table/orders}), so an edge
 * reads {@code metric/revenue --computedFrom--> table/lineitem} and a query can start at the node
 * the agent named. The reverse name of every relation is carried on the edge ({@code xrelation}),
 * so an agent can walk the lineage in either direction with {@code inE()}.
 */
public final class TpchDataAgentGraph {

    /** The graph name (matching the schema file and the service registration). */
    public static final String GRAPH = "tpc-h-analytics";

    /** The RocksDB root the service reads, relative to the module directory. */
    public static final String DEFAULT_ROOT = ".rocksdb";

    /** The reverse relation name carried on every edge. */
    private static final Map<String, String> REVERSE = Map.of(
            "hasColumn", "belongsToTable",
            "references", "referencedBy",
            "computedFrom", "sourceFor",
            "usesColumn", "usedByMetric",
            "readsTable", "readByQuery",
            "groupedBy", "groupsMetric",
            "dimensionColumn", "dimensionOf",
            "definedBy", "definesMetric",
            "contributesTo", "drivenBy",
            "belongsTo", "containsMetric");

    /** The built graph: the dataset, ready to load. */
    public record Built(TpchDataAgentDataset.Dataset dataset) {
    }

    /** The outcome of a load. */
    public record Result(String graph, String path, int nodes, int relations) {
    }

    public static GraphSchema loadSchema() {
        for (String candidate : List.of("graph-http/src/main/resources/" + GRAPH + ".json",
                "../graph-http/src/main/resources/" + GRAPH + ".json")) {
            Path file = Path.of(candidate);
            if (Files.isReadable(file)) {
                return readSchema(file);
            }
        }
        try (InputStream in = TpchDataAgentGraph.class.getResourceAsStream("/" + GRAPH + ".json")) {
            if (in != null) {
                return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the schema of " + GRAPH, e);
        }
        throw new IllegalStateException(GRAPH + ".json not found");
    }

    private static GraphSchema readSchema(Path file) {
        try {
            return new SchemaJsonCodec().fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }

    public static Built build() {
        return new Built(TpchDataAgentDataset.build());
    }

    /** Loads the graph into the RocksDB root the service reads (and prints where it went). */
    public static Result load(Built built, String rocksDbRoot, boolean reseed) {
        GraphSchema schema = loadSchema();
        String root = rocksDbRoot == null || rocksDbRoot.isBlank() ? DEFAULT_ROOT : rocksDbRoot;
        Path path = Path.of(root).resolve(schema.storage().requireRocksDbPath());
        if (reseed && Files.exists(path)) {
            deleteDirectory(path);
        }
        RocksDbStoreConfig config = new RocksDbStoreConfig(path.toString(), schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        List<RocksDbVertexUpsert> vertices = nodeUpserts(schema, built.dataset());
        List<RocksDbEdgeUpsert> edges = relationUpserts(schema, built.dataset());
        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
            store.upsertVertices(vertices);
            store.upsertEdges(edges);
        }
        return new Result(GRAPH, path.toString(), vertices.size(), edges.size());
    }

    public static boolean isLoaded(String rocksDbRoot) {
        Path path = Path.of(rocksDbRoot == null || rocksDbRoot.isBlank() ? DEFAULT_ROOT : rocksDbRoot)
                .resolve(loadSchema().storage().requireRocksDbPath());
        return Files.exists(path.resolve("CURRENT"));
    }

    /** The node upserts: one per semantic-layer node, only the properties its kind uses. */
    static List<RocksDbVertexUpsert> nodeUpserts(GraphSchema schema, TpchDataAgentDataset.Dataset dataset) {
        VertexSchema nodeSchema = schema.vertexSchema("Node");
        long now = System.currentTimeMillis();
        List<RocksDbVertexUpsert> upserts = new ArrayList<>(dataset.nodes().size());
        long tick = 0;
        for (TpchDataAgentDataset.Node node : dataset.nodes()) {
            long time = now + tick++;
            Map<Integer, RocksDbPropertyValue> props = new LinkedHashMap<>();
            put(props, nodeSchema, "name", node.name(), time);
            put(props, nodeSchema, "entityType", node.entityType(), time);
            put(props, nodeSchema, "description", node.description(), time);
            put(props, nodeSchema, "dataType", node.dataType(), time);
            put(props, nodeSchema, "columnRole", node.columnRole(), time);
            put(props, nodeSchema, "tableName", node.tableName(), time);
            put(props, nodeSchema, "aggFunction", node.aggFunction(), time);
            put(props, nodeSchema, "expression", node.expression(), time);
            put(props, nodeSchema, "metricKind", node.metricKind(), time);
            put(props, nodeSchema, "unit", node.unit(), time);
            put(props, nodeSchema, "sqlText", node.sqlText(), time);
            if (node.queryNumber() != null) {
                put(props, nodeSchema, "queryNumber", node.queryNumber(), time);
            }
            upserts.add(new RocksDbVertexUpsert(node.name(), nodeSchema, props, time, time));
        }
        return upserts;
    }

    /** The edge upserts: one per relation, carrying its Chinese label, family and reverse name. */
    static List<RocksDbEdgeUpsert> relationUpserts(GraphSchema schema, TpchDataAgentDataset.Dataset dataset) {
        EdgeSchema relationSchema = schema.edgeSchema("Relation");
        Map<String, String[]> meta = TpchDataAgentDataset.relationMeta();
        long now = System.currentTimeMillis();
        List<RocksDbEdgeUpsert> upserts = new ArrayList<>(dataset.relations().size());
        long tick = 0;
        for (TpchDataAgentDataset.Relation relation : dataset.relations()) {
            String[] m = meta.get(relation.relation());
            String label = m == null ? relation.relation() : m[0];
            String family = m == null ? "其他" : m[1];
            long time = now + tick++;
            Map<Integer, RocksDbEdgeProperty> props = new LinkedHashMap<>();
            putEdge(props, relationSchema, "relation", relation.relation(), time);
            putEdge(props, relationSchema, "xrelation", REVERSE.getOrDefault(relation.relation(), relation.relation()), time);
            putEdge(props, relationSchema, "relationLabel", label, time);
            putEdge(props, relationSchema, "relationFamily", family, time);
            String edgeId = relation.head() + "|" + relation.relation() + "|" + relation.tail();
            upserts.add(new RocksDbEdgeUpsert(edgeId, relationSchema, relation.head(), relation.tail(),
                    props, time, time));
        }
        return upserts;
    }

    private static void put(Map<Integer, RocksDbPropertyValue> props, VertexSchema schema,
                            String name, Object value, long time) {
        if (value != null) {
            props.put(schema.property(name).id(), new RocksDbPropertyValue(value, time));
        }
    }

    private static void putEdge(Map<Integer, RocksDbEdgeProperty> props, EdgeSchema schema,
                                String name, Object value, long time) {
        if (value != null) {
            props.put(schema.property(name).id(), new RocksDbEdgeProperty(value, time, time));
        }
    }

    private static void deleteDirectory(Path path) {
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort: a file another handle holds is left for the next reseed
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clear " + path, e);
        }
    }

    private TpchDataAgentGraph() {
    }
}

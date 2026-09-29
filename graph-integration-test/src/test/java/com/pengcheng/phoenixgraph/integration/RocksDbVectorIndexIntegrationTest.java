package com.pengcheng.phoenixgraph.integration;

import com.pengcheng.phoenixgraph.hnsw.HnswIndex;
import com.pengcheng.phoenixgraph.hnsw.HnswSearchResult;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.EdgeQueryResult;
import com.pengcheng.phoenixgraph.query.storage.EdgeSide;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GeoIndexSchemas;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.VectorIndexSchemas;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbHnswIndexFactory;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbVectorIndexBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A vector index on a graph served by <b>RocksDB-Edge</b>: built over the demo payment data, searched, and
 * used together with the geo index and the edges of the graph.
 *
 * <p>The demo directory {@code .rocksdb/payment-graph-rocksdb-edge} holds 151 accounts, each with a
 * behaviour vector and a location. A running service locks that directory, so the test works on a copy and
 * never needs the service stopped.
 *
 * <p>The three pieces are exercised in the order a caller needs them:
 * <ol>
 *   <li><b>build</b>: one scan of the vertex type gives both the ids and the vectors, the old layers go, and
 *       the inserts write the layer classes - ordinary edge classes of the graph, created on demand in
 *       their own column families;</li>
 *   <li><b>search</b>: the index opened for reading goes through {@code GraphQueryHnswStore}, i.e. through
 *       the generic graph query, which is the same path the HTTP service uses;</li>
 *   <li><b>combination</b>: {@code nearest}, {@code circle} (geo) and the payment edges in one traversal,
 *       including the query this data is for - the accounts around a point, their lookalikes, and what
 *       those pay.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RocksDbVectorIndexIntegrationTest {

    /** The centre of the geo searches (People's Square, Shanghai); the radius is the five-kilometre step argument. */
    private static final String CENTRE = "{\"latitude\":31.2304,\"longitude\":121.4737}";

    private Path directory;
    private RocksDbEdgeStore store;
    private GraphSchema schema;
    private GraphQuery query;
    private VectorIndexSchemas.VectorProperty vector;
    private HnswIndex index;
    private RocksDbVectorIndexBuilder.Built built;
    private long indexed;

    @BeforeAll
    void buildTheIndexOverACopyOfTheDemoData() throws IOException {
        Path source = Path.of("..", ".rocksdb", "payment-graph-rocksdb-edge");
        assumeTrue(Files.isDirectory(source), "the RocksDB demo data is not loaded: " + source);
        directory = Files.createTempDirectory("phoenixgraph-rocksdb-hnsw-");
        copyDirectory(source, directory);

        schema = loadSchema();
        // the copy already carries a column family per HNSW layer (the demo was indexed once), and RocksDB
        // opens every family it finds: the layer classes have to be in the schema before the open
        RocksDbHnswIndexFactory.declareLayers(schema);
        store = RocksDbEdgeStore.open(schema, RocksDbStoreConfig.of(directory.toString()));
        query = new RocksDbEdgeGraphQuery(store, schema);

        vector = VectorIndexSchemas.vectorProperties(schema).stream()
                .filter(property -> property.vertexName().equals("Account"))
                .findFirst().orElse(null);
        assertNotNull(vector, "the schema declares a vector property on Account");
        assertEquals("accountVector", vector.propertyName());
        assertEquals(1, GeoIndexSchemas.geoProperties(schema).size(), "and one geo property");

        long withVector = 0;
        for (com.pengcheng.phoenixgraph.query.storage.VertexQueryResult vertex : query.scanVertices(
                vector.vertexName(), List.of(vector.propertyName()))) {
            if (vertex.properties().get(vector.propertyName()) != null) {
                withVector++;
            }
        }
        indexed = withVector;
        built = RocksDbVectorIndexBuilder.rebuild(store, query, schema, vector);
        index = RocksDbHnswIndexFactory.open(query, store.handle(), schema, vector, Duration.ZERO);
    }

    @AfterAll
    void close() {
        if (store != null) {
            store.close();
        }
        if (directory != null) {
            deleteRecursively(directory);
        }
    }

    @Test
    void theBuildIndexesEveryAccountThatHasAVector() {
        System.out.printf(Locale.ROOT,
                "built %s.%s: %d vertices, %d layers, %d links, %d pruned, %d old rows dropped, %.1f s%n",
                built.vertexName(), built.vectorProperty(), built.vertices(), built.layers(), built.links(),
                built.pruned(), built.dropped(), built.seconds());
        assertEquals(indexed, built.vertices(), "every vertex that carries a vector is indexed");
        assertTrue(indexed > 40, "the demo data has a dataset to index: " + indexed);
        assertTrue(built.layers() >= Math.ceil(Math.log(indexed) / Math.log(2)) + 1 - 1,
                "layers follow the size (" + indexed + "): " + built.layers());
        assertTrue(built.links() > built.vertices(), "an index has more links than vertices: " + built.links());
        assertEquals(built.vertices(), index.stats().size(), "the metadata records what was inserted");
        assertFalse(index.entryPoints().isEmpty(), "with an entry point to search from");
    }

    @Test
    void theLayersAreOrdinaryEdgesTheGraphQueryCanRead() {
        // the search reads the layers through the graph query: this is the read it makes
        List<Object> entryPoints = index.entryPoints().stream().map(point -> point.vertexId()).toList();
        List<List<EdgeQueryResult>> layer0 = query.readEdgesBatch("accountVector_0", EdgeSide.BOTH,
                entryPoints, List.of());
        int links = layer0.stream().mapToInt(List::size).sum();
        assertTrue(links > 0, "layer 0 of the entry points has links: " + links);

        // one edge class per layer, named after the vector property, in the schema and as column families
        assertNotNull(schema.edgeSchema("accountVector_0"), "layer 0 is a declared edge class");
        assertNotNull(schema.edgeSchema("accountVector_" + (built.layers() - 1)), "so is the top layer");
        assertTrue(store.columnFamilies().contains(RocksDbEdgeStore.familyOf("accountVector_0")),
                "layer 0 has a column family: " + store.columnFamilies());
    }

    @Test
    void aSearchFindsTheNearestAccounts() {
        List<HnswSearchResult> hits = index.searchById("1001", 5, 64);
        assertEquals(5, hits.size(), "five neighbours of 1001");
        assertTrue(hits.stream().noneMatch(hit -> String.valueOf(hit.vertexId()).equals("1001")),
                "never the vertex itself: " + hits.stream().map(hit -> hit.vertexId().getClass().getSimpleName()
                        + ":" + hit.vertexId()).toList());
        assertTrue(hits.stream().allMatch(hit -> hit.distance() >= 0), "a distance per hit");
        System.out.println("nearest(1001): " + hits.stream().map(hit -> String.valueOf(hit.vertexId())).toList()
                + " at " + hits.stream().map(hit -> String.format(Locale.ROOT, "%.4f", hit.distance())).toList());
    }

    @Test
    void nearestAndThePaymentEdgesAreUsedTogether() {
        GremlinGraphQuery engine = engine();
        List<Object> similar = engine.execute("g.V('1001','Account').nearest(5).values('id')");
        assertEquals(5, similar.size(), "five similar accounts: " + similar);

        List<Object> payments = engine.execute("g.V('1001','Account').nearest(5).outE('Payment')");
        System.out.println("payments of the lookalikes of 1001: " + payments.size() + " edges");
        assertFalse(payments.isEmpty(), "their payments are reachable");

        // vector -> graph -> vector: the lookalikes of the accounts they pay
        List<Object> second = engine.execute("g.V('1001','Account').nearest(5).out('Payment').dedup()"
                + ".nearest(3).values('id')");
        assertFalse(second.isEmpty(), "a second vector hop answers");
    }

    @Test
    void theGeoIndexAndTheVectorIndexAnswerInOneQuery() {
        GremlinGraphQuery engine = engine();
        // the query the demo data is for: the accounts within five kilometres of People's Square, their
        // lookalikes, and the payments of those lookalikes, as one subgraph
        String gremlin = "g.V('1001','Account').circle('" + CENTRE + "', 5000).limit(10).nearest(3)"
                + ".outE('Payment')";
        GremlinGraphQuery.Result result = engine.executeWithSubgraph(gremlin);
        long near = engine.execute("g.V('1001','Account').circle('" + CENTRE + "', 5000).count()").stream()
                .mapToLong(value -> ((Number) value).longValue()).sum();
        System.out.printf(Locale.ROOT,
                "geo+vector+graph: %d accounts within 5 km -> their lookalikes -> %d payment edges, "
                        + "subgraph %d vertices / %d edges%n",
                near, result.values().size(), result.subgraph().vertices().size(),
                result.subgraph().edges().size());
        assertTrue(near >= 10, "the circle finds accounts: " + near);
        assertFalse(result.values().isEmpty(), "the combined query answers");
        assertTrue(result.subgraph().vertices().size() > 10,
                "the picture holds the geo hits and their lookalikes: " + result.subgraph().vertices().size());
        assertFalse(result.subgraph().edges().isEmpty(), "and the payments between them");

        // the distance travels with a geo hit, so a caller can print it
        List<Object> withDistance = engine.execute("g.V('1001','Account').circle('" + CENTRE
                + "', 5000).limit(5).values('id','distanceMeters')");
        assertEquals(10, withDistance.size(), "five hits, each with its distance");
    }

    @Test
    void theVectorSideOfTheSameQueryWorksWithoutTheGeoIndex() {
        GremlinGraphQuery engine = engine();
        // only the vector index: the 10 nearest accounts and their payments
        GremlinGraphQuery.Result result = engine.executeWithSubgraph(
                "g.V('1001','Account').nearest(10).outE('Payment')");
        System.out.printf(Locale.ROOT, "vector+graph: %d payment edges, subgraph %d vertices / %d edges%n",
                result.values().size(), result.subgraph().vertices().size(),
                result.subgraph().edges().size());
        assertFalse(result.values().isEmpty(), "the vector hop finds payers");
        assertTrue(result.subgraph().vertices().size() >= 10, "the lookalikes are on the canvas");
    }

    // ------------------------------------------------------------------ helpers

    /** The engine the HTTP service builds: the graph query, the geo properties and the vector index. */
    private GremlinGraphQuery engine() {
        Map<String, String> geo = new LinkedHashMap<>();
        for (GeoIndexSchemas.GeoProperty property : GeoIndexSchemas.geoProperties(schema)) {
            geo.putIfAbsent(property.vertexName(), property.propertyName());
        }
        return new GremlinGraphQuery(query, NeighborVertexResolver.from(schema))
                .schema(schema)
                .geoProperties(geo)
                .hnswIndex(vector.vertexName(),
                        RocksDbHnswIndexFactory.open(query, store.handle(), schema, vector, Duration.ZERO));
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                // the LOCK of the original is held by whoever has it open (a running service): the copy
                // gets its own when the store opens it
                if (file.getFileName().toString().equals("LOCK")) {
                    continue;
                }
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // a temporary directory: what is left behind is not worth failing a test for
                }
            }
        } catch (IOException ignored) {
            // same
        }
    }

    private static GraphSchema loadSchema() {
        Path fromResources = Path.of("..", "graph-http", "src", "main", "resources",
                "payment-graph-rocksdb-edge.json");
        try {
            return new SchemaJsonCodec().fromJson(Files.readString(fromResources, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + fromResources.toAbsolutePath(), e);
        }
    }
}

package com.pengcheng.phoenixgraph.integration.sanguo;

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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the 《三国演义》 knowledge graph into the <b>RocksDB-Edge</b> storage — the
 * same dataset as the Aerospike {@code sanguo-kg}, but modelled for the edge table
 * instead of for a keyed record.
 *
 * <p><b>No hash, no shard.</b> The vertex id is the plain entity name
 * ({@code 曹操}, {@code 魏}, {@code 第49回}), so one person is one record that
 * holds its whole neighbourhood instead of the ten relation-sharded records
 * {@link SanguoGraphIds} writes to Aerospike. Names are unique across the entity
 * kinds (a faction whose name is also a person's carries the {@code 势力} suffix),
 * so the single {@code Entity} table stays collision free.
 *
 * <p><b>One edge per relation.</b> The relations of {@link SanguoDataset} — the upstream curated
 * triples plus the local {@code triples-extra.csv} supplement (see SANGUO-KG-QUERIES.md §9) —
 * are written as they are parsed: {@code head --relation--> tail}. The inverse is
 * not duplicated, because RocksDB-Edge stores each edge in the outgoing list of its
 * source <em>and</em> the incoming list of its target, so {@code bothE()} reaches it
 * from either end and {@code relation.equals(R) || xrelation.equals(R)} still finds
 * the relation from both sides. (The Aerospike graph materializes the inverse as a
 * second edge because its per-relation sharding needs it to land in the right
 * record; here it would be a copy of the same fact.)
 *
 * <p>Usage:
 * <pre>
 * java ... SanguoRocksDbLoader /path/to/rocksdb-root      # (re)load the served instance
 * </pre>
 */
public final class SanguoRocksDbLoader {

    /** The RocksDB-Edge instance of the 《三国演义》 knowledge graph. */
    public static final String GRAPH = "sanguo-kg-rocksdb-edge";

    /** The default RocksDB root, relative to the repository root. */
    public static final String DEFAULT_ROOT = ".rocksdb";

    /** Base timestamp of the load, so a reload produces the same graph. */
    private static final long BASE_TIME = 1_700_000_000_000L;

    private SanguoRocksDbLoader() {
    }

    /** What a load wrote. */
    public record Result(String graph, String path, int vertices, int edges) {
    }

    /** The schema of the RocksDB-Edge instance, from the classpath. */
    public static GraphSchema loadSchema() {
        return loadSchema(GRAPH);
    }

    /** A schema by graph name, from the classpath. */
    public static GraphSchema loadSchema(String graphName) {
        try (InputStream in = SanguoRocksDbLoader.class.getResourceAsStream("/" + graphName + ".json")) {
            if (in == null) {
                throw new IllegalStateException("Missing schema resource: /" + graphName + ".json");
            }
            return new SchemaJsonCodec().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read /" + graphName + ".json", e);
        }
    }

    /** Loads the instance the service serves, under {@code rocksDbRoot} (default {@value #DEFAULT_ROOT}). */
    public static Result load(String rocksDbRoot, boolean fresh) {
        return load(SanguoDataset.load(), rocksDbRoot, fresh);
    }

    /** Loads the given dataset under {@code rocksDbRoot}. */
    public static Result load(SanguoDataset dataset, String rocksDbRoot, boolean fresh) {
        GraphSchema schema = loadSchema();
        String root = rocksDbRoot == null || rocksDbRoot.isBlank() ? DEFAULT_ROOT : rocksDbRoot;
        Path path = Path.of(root).resolve(schema.storage().requireRocksDbPath());
        return load(schema, dataset, path, fresh);
    }

    /** Writes {@code dataset} into the RocksDB directory {@code path}. */
    public static Result load(GraphSchema schema, SanguoDataset dataset, Path path, boolean fresh) {
        if (fresh) {
            deleteDirectory(path);
        }
        RocksDbStoreConfig config = new RocksDbStoreConfig(path.toString(),
                schema.storage().regionCount(), schema.storage().maxVersions(), true,
                schema.storage().cacheEdges());
        List<RocksDbVertexUpsert> vertices = vertices(schema, dataset);
        List<RocksDbEdgeUpsert> edges = edges(schema, dataset);

        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
            store.upsertVertices(vertices);
            store.upsertEdges(edges);
            store.flush();
        }
        return new Result(schema.name(), path.toString(), vertices.size(), edges.size());
    }

    /** Whether a RocksDB directory already holds a database (checked without opening it). */
    public static boolean isLoaded(String rocksDbRoot) {
        Path path = Path.of(rocksDbRoot == null || rocksDbRoot.isBlank() ? DEFAULT_ROOT : rocksDbRoot)
                .resolve(loadSchema().storage().requireRocksDbPath());
        return Files.exists(path.resolve("CURRENT"));
    }

    /**
     * One vertex per entity, keyed by its plain name. The properties are the person
     * attributes when the entity has a document; there is no {@code shard}.
     */
    public static List<RocksDbVertexUpsert> vertices(GraphSchema schema, SanguoDataset dataset) {
        VertexSchema entity = schema.vertexSchema(SanguoGraphIds.VERTEX_LABEL);
        if (entity == null) {
            throw new IllegalStateException("schema is missing the " + SanguoGraphIds.VERTEX_LABEL + " vertex");
        }
        Map<String, SanguoDataset.Document> documents = dataset.documentsByName();
        // 方案 ⑫：角色分类由图谱关系推断（君主/军师/谋士/武将/文臣），不引入人工数据
        Map<String, String> roles = SanguoRoles.of(dataset);
        List<RocksDbVertexUpsert> upserts = new ArrayList<>();
        long tick = 0;
        for (SanguoDataset.Entity value : dataset.entities().values()) {
            String name = value.name();
            long time = BASE_TIME + tick++;
            Map<String, RocksDbPropertyValue> values = new LinkedHashMap<>();
            values.put("name", new RocksDbPropertyValue(name, time));
            values.put("entityType", new RocksDbPropertyValue(value.type().rdfClass(), time));
            SanguoDataset.Document document = documents.get(name);
            if (document != null) {
                put(values, "gender", document.gender(), time);
                put(values, "courtesyName", document.courtesyName(), time);
                put(values, "historicity", document.historicity(), time);
                put(values, "history", truncate(document.history()), time);
                put(values, "novel", truncate(document.novel()), time);
                if (document.birthYear() != null) {
                    values.put("birthYear", new RocksDbPropertyValue((long) document.birthYear(), time));
                }
                if (document.deathYear() != null) {
                    values.put("deathYear", new RocksDbPropertyValue((long) document.deathYear(), time));
                }
            }
            double[] geo = dataset.geoLocation(name);
            if (geo != null) {
                values.put("latitude", new RocksDbPropertyValue(geo[0], time));
                values.put("longitude", new RocksDbPropertyValue(geo[1], time));
            }
            Integer eventYearEnd = dataset.eventYearEnd(name);
            if (eventYearEnd != null) {
                values.put("eventYearEnd", new RocksDbPropertyValue((long) eventYearEnd, time));
            }
            Integer eventYear = dataset.eventYear(name);
            if (eventYear != null) {
                values.put("eventYear", new RocksDbPropertyValue((long) eventYear, time));
            }
            if (!value.aliases().isEmpty()) {
                // a multi-valued property: has('alias', TextP.containing('水镜')) reads the list
                values.put("alias", new RocksDbPropertyValue(value.aliases(), time));
            }
            String role = roles.get(name);
            if (role != null) {
                values.put("role", new RocksDbPropertyValue(role, time));
            }
            upserts.add(new RocksDbVertexUpsert(name, entity, indexed(entity, values), time, time));
        }
        return upserts;
    }

    /** One directed edge per parsed relation, {@code head --relation--> tail}. */
    public static List<RocksDbEdgeUpsert> edges(GraphSchema schema, SanguoDataset dataset) {
        EdgeSchema edgeSchema = schema.edgeSchema(SanguoGraphIds.EDGE_LABEL);
        if (edgeSchema == null) {
            throw new IllegalStateException("schema is missing the " + SanguoGraphIds.EDGE_LABEL + " edge");
        }
        SanguoOntology ontology = dataset.ontology();
        List<SanguoDataset.Relation> relations = new ArrayList<>(dataset.relations());
        // a deterministic order, so a reload writes the same rows in the same order
        relations.sort(Comparator.comparing(SanguoDataset.Relation::head)
                .thenComparing(SanguoDataset.Relation::relation)
                .thenComparing(SanguoDataset.Relation::tail));
        List<RocksDbEdgeUpsert> edges = new ArrayList<>(relations.size());
        for (int i = 0; i < relations.size(); i++) {
            SanguoDataset.Relation relation = relations.get(i);
            long time = BASE_TIME + (i % 1000);
            Map<String, RocksDbEdgeProperty> values = new LinkedHashMap<>();
            values.put("relation", new RocksDbEdgeProperty(relation.relation(), time, time));
            values.put("xrelation", new RocksDbEdgeProperty(ontology.inverse(relation.relation()), time, time));
            values.put("relationLabel", new RocksDbEdgeProperty(ontology.label(relation.relation()), time, time));
            values.put("relationFamily",
                    new RocksDbEdgeProperty(ontology.relationFamilyLabel(relation.relation()), time, time));
            // the time dimension (SANGUO-KG.md §7.8): when both endpoints are attested, and where in
            // the biography the relation was read
            SanguoDataset.RelationTime interval = dataset.timeOf(relation);
            if (interval.year() != null) {
                values.put("year", new RocksDbEdgeProperty(interval.year(), time, time));
            }
            if (interval.chapter() != null) {
                values.put("chapter", new RocksDbEdgeProperty(interval.chapter(), time, time));
            }
            if (interval.ordered()) {
                values.put("seq", new RocksDbEdgeProperty(interval.seq(), time, time));
            }
            if (!relation.source().name().equals("CURATED")) {
                values.put("provenance", new RocksDbEdgeProperty(relation.source().name(), time, time));
            }
            edges.add(new RocksDbEdgeUpsert(edgeId(relation), edgeSchema, relation.head(), relation.tail(),
                    indexed(edgeSchema, values), time, time));
        }
        return edges;
    }

    /**
     * A <b>stable</b> edge id derived from the triple itself, not from its position — the
     * same reason (and the same format) as {@code SanguoGraphSeeder.edgeId}.
     */
    static String edgeId(SanguoDataset.Relation relation) {
        return relation.head() + "|" + relation.relation() + "|" + relation.tail();
    }

    private static void put(Map<String, RocksDbPropertyValue> values, String name, String value, long time) {
        if (value != null && !value.isEmpty()) {
            values.put(name, new RocksDbPropertyValue(value, time));
        }
    }

    /** Turns edge property names into the property indexes a row is stored under. */
    private static Map<Integer, RocksDbEdgeProperty> indexed(EdgeSchema edgeSchema,
                                                             Map<String, RocksDbEdgeProperty> values) {
        Map<Integer, RocksDbEdgeProperty> indexed = new LinkedHashMap<>();
        for (Map.Entry<String, RocksDbEdgeProperty> entry : values.entrySet()) {
            var property = edgeSchema.property(entry.getKey());
            if (property != null) {
                indexed.put(property.id(), entry.getValue());
            }
        }
        return indexed;
    }

    /** Turns vertex property names into the property indexes a record is stored under. */
    private static Map<Integer, RocksDbPropertyValue> indexed(VertexSchema vertexSchema,
                                                              Map<String, RocksDbPropertyValue> values) {
        Map<Integer, RocksDbPropertyValue> indexed = new LinkedHashMap<>();
        for (Map.Entry<String, RocksDbPropertyValue> entry : values.entrySet()) {
            var property = vertexSchema.property(entry.getKey());
            if (property != null) {
                indexed.put(property.id(), entry.getValue());
            }
        }
        return indexed;
    }

    private static String truncate(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }

    private static void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to delete " + entry, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + path, e);
        }
    }

    /** {@code SanguoRocksDbLoader [rocksdb-root]} — (re)loads the instance the service serves. */
    public static void main(String[] args) {
        String root = args.length > 0 ? args[0] : DEFAULT_ROOT;
        boolean fresh = args.length < 2 || Boolean.parseBoolean(args[1]);
        SanguoDataset dataset = SanguoDataset.load();
        Result result = load(dataset, root, fresh);
        System.out.printf("[sanguo-rocksdb] %s -> %s: %d vertices, %d edges (%d entities, %d relations in the dataset)%n",
                result.graph(), result.path(), result.vertices(), result.edges(),
                dataset.entities().size(), dataset.relations().size());
    }
}

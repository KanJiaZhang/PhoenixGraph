package com.pengcheng.phoenixgraph.integration.sanguo;

import com.aerospike.client.AerospikeClient;
import com.pengcheng.phoenixgraph.integration.IntegrationConfig;
import com.pengcheng.phoenixgraph.query.aerospike.AerospikeGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherResult;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinEdge;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinVertex;
import com.pengcheng.phoenixgraph.query.result.Subgraph;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeEdgeStore;
import com.pengcheng.phoenixgraph.storage.aerospike.AerospikeVertexStore;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The 《三国演义》 knowledge graph on the <b>RocksDB-Edge</b> storage: the same dataset
 * as the Aerospike {@code sanguo-kg}, but with the plain entity name as the vertex id
 * (no relation hash, no shard) and one edge per parsed relation.
 *
 * <p>Three things are checked here:
 * <ol>
 *   <li>the instance the service serves is loaded into {@code .rocksdb}
 *   ({@code SANGUO_ROCKSDB_RESEED=true} forces it; it is skipped while the directory
 *   is held by the running service);</li>
 *   <li>the 20 typical queries of {@link SanguoRocksDbQueries} all answer something —
 *   and are printed, so the output can be read and compared with the Aerospike
 *   instance query by query;</li>
 *   <li>a question that does not depend on the sharding scheme gets <em>the same
 *   answer</em> from both storages (parity against the Aerospike graph).</li>
 * </ol>
 */
class SanguoRocksDbEdgeTest {

    /** The RocksDB root the service reads, relative to the module directory (the repo root is one up). */
    private static final String SERVED_ROOT = env("SANGUO_ROCKSDB_ROOT", "../.rocksdb");

    /**
     * Loads the RocksDB-Edge instance the service serves ({@code .rocksdb/sanguo-kg-rocksdb-edge}).
     *
     * <p>The directory is only opened when it is not in use: RocksDB takes an exclusive lock,
     * so a running service would block the load (and the load would block the service). An
     * existing database is therefore left alone unless {@code SANGUO_ROCKSDB_RESEED=true}
     * asks for a rebuild — which needs the service stopped first.
     */
    @Test
    void loadsTheInstanceTheServiceServes() {
        boolean reseed = Boolean.parseBoolean(env("SANGUO_ROCKSDB_RESEED", "false"));
        if (!reseed && SanguoRocksDbLoader.isLoaded(SERVED_ROOT)) {
            System.out.printf("[sanguo-rocksdb] %s is already loaded at %s "
                            + "- set SANGUO_ROCKSDB_RESEED=true (service stopped) to rebuild%n",
                    SanguoRocksDbLoader.GRAPH, servedDirectory());
            return;
        }
        SanguoDataset dataset = SanguoDataset.load();
        long started = System.currentTimeMillis();
        SanguoRocksDbLoader.Result result = SanguoRocksDbLoader.load(dataset, SERVED_ROOT, reseed);
        System.out.printf("[sanguo-rocksdb] loaded %s at %s: %d vertices, %d edges in %d ms%n",
                result.graph(), result.path(), result.vertices(), result.edges(),
                System.currentTimeMillis() - started);
    }

    /** Runs all 20 queries against a freshly loaded RocksDB-Edge graph and prints the answers. */
    @Test
    void answersTheTwentyTypicalQueries(@TempDir Path directory) {
        GraphSchema schema = SanguoRocksDbLoader.loadSchema();
        SanguoDataset dataset = SanguoDataset.load();
        Path path = directory.resolve(schema.storage().requireRocksDbPath());
        SanguoRocksDbLoader.Result result = SanguoRocksDbLoader.load(schema, dataset, path, true);

        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, storeConfig(schema, path))) {
            GraphQuery graph = new RocksDbEdgeGraphQuery(store, schema);
            GremlinGraphQuery gremlin = new GremlinGraphQuery(graph, NeighborVertexResolver.from(schema));
            CypherGraphQuery cypher = new CypherGraphQuery(graph, NeighborVertexResolver.from(schema));

            header(schema, result, dataset);

            List<SanguoRocksDbQueries.Example> gremlinQueries = SanguoRocksDbQueries.GREMLIN;
            for (int i = 0; i < gremlinQueries.size(); i++) {
                SanguoRocksDbQueries.Example example = gremlinQueries.get(i);
                List<Object> rows = gremlin.execute(example.query());
                System.out.printf("%n[Gremlin %2d] %s%n        %s%n        query  : %s%n        result : %s%n",
                        i + 1, example.title(), example.titleEn(), example.query(), summariseGremlin(rows));
                assertTrue(!rows.isEmpty(), "gremlin query " + (i + 1) + " returned nothing: " + example.title());
            }

            List<SanguoRocksDbQueries.Example> cypherQueries = SanguoRocksDbQueries.CYPHER;
            for (int i = 0; i < cypherQueries.size(); i++) {
                SanguoRocksDbQueries.Example example = cypherQueries.get(i);
                CypherResult rows = cypher.execute(example.query());
                System.out.printf("%n[Cypher  %2d] %s%n        %s%n        query  : %s%n        result : %s%n",
                        i + 1, example.title(), example.titleEn(), example.query(), summariseCypher(rows));
                assertTrue(!rows.rows().isEmpty(),
                        "cypher query " + (i + 1) + " returned nothing: " + example.title());
            }

            Stats stats = Stats.of(dataset);

            // the subgraph a visualisation draws for one person: now a single record's neighbourhood
            Subgraph subgraph = gremlin.executeSubgraph("g.V('曹操','Entity').bothE()");
            System.out.printf("%n[Gremlin 21] 子图（可视化）: g.V('曹操','Entity').bothE()%n"
                            + "        result : %d vertices, %d edges%n%n",
                    subgraph.vertices().size(), subgraph.edges().size());
            assertEquals(stats.degree("曹操"), subgraph.edges().size());
            assertEquals(stats.neighbourhood("曹操").size() + 1, subgraph.vertices().size());

            // the counts match the in-memory dataset exactly: one record, no shard duplication
            System.out.println("=========== 与内存中的数据集对照（点不分片，边不重复） ===========");
            check("曹操 bothE 度", stats.degree("曹操"),
                    count(gremlin, "g.V('曹操','Entity').bothE().count()"));
            check("曹操 outE + inE", stats.degree("曹操"),
                    count(gremlin, "g.V('曹操','Entity').outE().count()")
                            + count(gremlin, "g.V('曹操','Entity').inE().count()"));
            check("曹操 主公出边 (lord)", stats.forward("曹操", "lord"),
                    count(gremlin, "g.V('曹操','Entity').outE().filter(relation.equals('lord')).count()"));
            check("魏 效力入边 (serves)", stats.incoming("魏", "serves"),
                    count(gremlin, "g.V('魏','Entity').inE().filter(relation.equals('serves')).count()"));
            System.out.println("================================================================");
        }
    }

    /**
     * The same question, both storages: the answers must agree wherever the sharding
     * scheme is not part of the question. All four counts are closure-independent
     * (the Aerospike graph materializes inverse edges; none of these is affected).
     */
    @Test
    void matchesTheAerospikeGraphOnTheSharedQuestions(@TempDir Path directory) {
        AerospikeClient client = new IntegrationConfig("localhost", 3000, "test", "").connectOrNull();
        assumeTrue(client != null, "Aerospike not available, skipping the parity check");

        GraphSchema rocksSchema = SanguoRocksDbLoader.loadSchema();
        SanguoDataset dataset = SanguoDataset.load();
        Stats stats = Stats.of(dataset);
        Path path = directory.resolve(rocksSchema.storage().requireRocksDbPath());
        SanguoRocksDbLoader.load(rocksSchema, dataset, path, true);

        GraphSchema aerospikeSchema = SanguoRocksDbLoader.loadSchema("sanguo-kg");
        try (client) {
            AerospikeVertexStore vertexStore = new AerospikeVertexStore(client, "test", "kg_sanguo_v1",
                    entry -> {
                    }, entry -> {
                    }, 10);
            AerospikeEdgeStore edgeStore = new AerospikeEdgeStore(client, "test", "kg_sanguo_v1", aerospikeSchema,
                    entry -> {
                    }, entry -> {
                    }, 10);
            assumeTrue(vertexStore.readVertex(SanguoGraphIds.composeId("曹操", "lord")) != null,
                    "the Aerospike sanguo-kg set is not seeded");

            GremlinGraphQuery aerospike = new GremlinGraphQuery(
                    new AerospikeGraphQuery(vertexStore, edgeStore, aerospikeSchema),
                    NeighborVertexResolver.from(aerospikeSchema));

            try (RocksDbEdgeStore store = RocksDbEdgeStore.open(rocksSchema, storeConfig(rocksSchema, path))) {
                GremlinGraphQuery rocks = new GremlinGraphQuery(
                        new RocksDbEdgeGraphQuery(store, rocksSchema), NeighborVertexResolver.from(rocksSchema));

                System.out.println();
                System.out.println("=========== 两种存储回答同一批问题（点 id / 分片方式不同，答案相同） ===========");
                System.out.printf("%-32s %-14s %-14s %s%n", "问题", "Aerospike", "RocksDB-Edge", "一致");
                parity("曹操 的主公出边 (lord)", stats.forward("曹操", "lord"),
                        count(aerospike, aerospikeId("曹操", "lord") + ".outE()"
                                + ".filter(relation.equals('lord')).count()"),
                        count(rocks, "g.V('曹操','Entity').outE()"
                                + ".filter(relation.equals('lord')).count()"));
                parity("魏 的效力入边 (serves)", stats.incoming("魏", "serves"),
                        count(aerospike, aerospikeId("魏", "serves") + ".inE()"
                                + ".filter(relation.equals('serves')).count()"),
                        count(rocks, "g.V('魏','Entity').inE()"
                                + ".filter(relation.equals('serves')).count()"));
                parity("诸葛亮 的出场章回 (appearsIn)", stats.forward("诸葛亮", "appearsIn"),
                        count(aerospike, aerospikeId("诸葛亮", "appearsIn") + ".outE()"
                                + ".filter(relation.equals('appearsIn')).count()"),
                        count(rocks, "g.V('诸葛亮','Entity').outE()"
                                + ".filter(relation.equals('appearsIn')).count()"));
                parity("曹嵩 的父亲出边 (father)", stats.forward("曹嵩", "father"),
                        count(aerospike, aerospikeId("曹嵩", "father") + ".outE()"
                                + ".filter(relation.equals('father')).count()"),
                        count(rocks, "g.V('曹嵩','Entity').outE()"
                                + ".filter(relation.equals('father')).count()"));
                System.out.println("===============================================================================");
            }
        }
    }

    /** The worked answer the recipe section ends with. */
    private static final String RELATION_CONCLUSION =
            "关羽和诸葛亮之间**没有直接关系边**（第①步 0 条）。数据给出的关系是\n"
            + "「**同为刘备的臣、同为蜀汉阵营**」：\n\n"
            + "| 证据 | 查询结果 |\n|---|---|\n"
            + "| 共同势力（第②步） | **蜀** —— 关羽 `效力` 蜀，诸葛亮 `效力` 蜀 |\n"
            + "| **共同上司（第③步）** | **刘备** —— 补上 `triples-extra.csv` 后，关羽、诸葛亮都是刘备的臣（`刘备 --lord--> 关羽/诸葛亮`） |\n"
            + "| 共同中间人（第⑦步） | **刘备**、**蜀**（另有 39 个章回把两人连在一起） |\n"
            + "| 身份关系（第⑪步） | 关羽 ↔ 刘备 = `lord` / **主公**（关羽是臣）**和** `younger_sworn_brother` / **义弟**；刘备 ↔ 诸葛亮 = `lord` / **主公** |\n"
            + "| 共同出场（第⑧步） | **39 回** |\n"
            + "| 距离（第⑨步） | 1 跳不可达（0 条），**2 跳 42 条路径** |\n\n"
            + "所以自然语言答案是：**关羽和诸葛亮是「同殿为臣」的关系 —— 两人都是刘备的臣、都效力蜀汉；"
            + "关羽还是刘备的义弟（桃园结义）兼五虎上将之首，诸葛亮是刘备的丞相（数据只记「拜为丞相」，"
            + "不记「军师」，见 §9），两人在 39 个章回里同时出场。**\n\n"
            + "> 对比上一版：在补 `triples-extra.csv` 之前，第③步是 0 条 —— 关羽与刘备之间只有「义弟」，"
            + "所以只能靠「共同势力/共同中间人」说明关系；补上 `刘备 --lord--> 关羽` 之后，"
            + "「同为刘备的臣」这一条直接可查，这就是方案 B 带来的差别。\n";

    /**
     * Runs <em>every</em> query of the catalogue (typical, conversions, multi-hop,
     * questions), records the answer and writes the whole catalogue into one file:
     * {@code SANGUO-KG-QUERIES.md} (plus a raw {@code .gremlin} / {@code .cypher} list)
     * so it can be read and copied from in one place.
     */
    @Test
    void writesTheQueryCookbook(@TempDir Path directory) {
        GraphSchema schema = SanguoRocksDbLoader.loadSchema();
        SanguoDataset dataset = SanguoDataset.load();
        Path path = directory.resolve(schema.storage().requireRocksDbPath());

        Map<String, String> answers = new LinkedHashMap<>();
        List<SanguoRocksDbQueries.RelationStep> recipe =
                SanguoRocksDbQueries.relationRecipe("关羽", "诸葛亮");
        withRocksDb(schema, path, dataset, (gremlin, cypher) -> {
            for (SanguoRocksDbQueries.Example example : SanguoRocksDbQueries.all()) {
                answers.put(example.query(), example.language().equals("cypher")
                        ? summariseCypher(cypher.execute(example.query()))
                        : summariseGremlin(gremlin.execute(example.query())));
            }
            for (SanguoRocksDbQueries.Conversion conversion : SanguoRocksDbQueries.CYPHER_TO_GREMLIN) {
                answers.put(conversion.cypher(), summariseCypher(cypher.execute(conversion.cypher())));
                answers.put(conversion.gremlin(), summariseGremlin(gremlin.execute(conversion.gremlin())));
            }
            for (SanguoRocksDbQueries.HopQuery hop : SanguoRocksDbQueries.MULTI_HOP) {
                answers.put(hop.gremlin(), summariseGremlin(gremlin.execute(hop.gremlin())));
                answers.put(hop.cypher(), summariseCypher(cypher.execute(hop.cypher())));
            }
            for (int i = 0; i < SanguoRocksDbQueries.QUESTIONS.size(); i++) {
                SanguoRocksDbQueries.Question question = SanguoRocksDbQueries.QUESTIONS.get(i);
                List<Object> rows = gremlin.execute(question.gremlin());
                answers.put(question.gremlin(), summariseGremlin(rows));
                if (question.expectRows()) {
                    assertTrue(!rows.isEmpty(), question.id() + " returned nothing: " + question.question());
                }
                if (!question.cypher().isBlank()) {
                    CypherResult result = cypher.execute(question.cypher());
                    answers.put(question.cypher(), summariseCypher(result));
                    if (question.expectRows()) {
                        assertTrue(!result.rows().isEmpty(), question.id() + " (cypher) returned nothing");
                    }
                }
            }
            // the worked example of the "A 和 B 是什么关系?" recipe
            for (SanguoRocksDbQueries.RelationStep step : recipe) {
                answers.put(step.gremlin(), summariseGremlin(gremlin.execute(step.gremlin())));
                answers.put(step.cypher(), summariseCypher(cypher.execute(step.cypher())));
            }
            // the gap-diagnosis queries
            for (SanguoRocksDbQueries.GapCheck check : SanguoRocksDbQueries.GAP_CHECKS) {
                answers.put(check.gremlin(), summariseGremlin(gremlin.execute(check.gremlin())));
            }
        });

        SanguoQueryCookbook.Rendered cookbook = SanguoQueryCookbook.render(answers, recipe, "关羽", "诸葛亮",
                RELATION_CONCLUSION);
        Path outDir = Path.of("target", "sanguo-rdf");
        SanguoRdf.write(outDir.resolve("QUERY-COOKBOOK.md"), cookbook.markdown());
        SanguoRdf.write(outDir.resolve("sanguo-queries.gremlin"), cookbook.gremlinLines());
        SanguoRdf.write(outDir.resolve("sanguo-queries.cypher"), cookbook.cypherLines());

        if (Boolean.getBoolean("sanguo.queries.write")) {
            Path repo = Path.of("..");
            SanguoRdf.write(repo.resolve("SANGUO-KG-QUERIES.md"), cookbook.markdown());
            SanguoRdf.write(repo.resolve("sanguo-queries.gremlin"), cookbook.gremlinLines());
            SanguoRdf.write(repo.resolve("sanguo-queries.cypher"), cookbook.cypherLines());
        }

        System.out.printf("%n[queries] 手册已生成：%d 条问答 + %d 条多跳 + %d 条典型 + %d 条转换%n"
                        + "          去重后 Gremlin %d 条 / Cypher %d 条 -> %s%n",
                SanguoRocksDbQueries.QUESTIONS.size(), SanguoRocksDbQueries.MULTI_HOP.size(),
                SanguoRocksDbQueries.GREMLIN.size() + SanguoRocksDbQueries.CYPHER.size(),
                SanguoRocksDbQueries.CYPHER_TO_GREMLIN.size(),
                SanguoQueryCookbook.gremlinLines().lines().filter(line -> line.startsWith("g.")).count(),
                SanguoQueryCookbook.cypherLines().lines().filter(line -> line.startsWith("MATCH")).count(),
                outDir.toAbsolutePath().resolve("QUERY-COOKBOOK.md"));
    }

    // --- helpers ---

    /** Loads a fresh RocksDB-Edge graph and hands both query engines to {@code body}. */
    private static void withRocksDb(GraphSchema schema, Path path, SanguoDataset dataset,
                                    java.util.function.BiConsumer<GremlinGraphQuery, CypherGraphQuery> body) {
        SanguoRocksDbLoader.load(schema, dataset, path, true);
        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, storeConfig(schema, path))) {
            GraphQuery graph = new RocksDbEdgeGraphQuery(store, schema);
            body.accept(new GremlinGraphQuery(graph, NeighborVertexResolver.from(schema)),
                    new CypherGraphQuery(graph, NeighborVertexResolver.from(schema)));
        }
    }

    private static RocksDbStoreConfig storeConfig(GraphSchema schema, Path path) {
        return new RocksDbStoreConfig(path.toString(), schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
    }

    /**
     * The ten Cypher statements of the catalogue written as Gremlin: both are executed on the
     * same RocksDB-Edge graph and printed side by side, so the translation can be checked.
     */
    @Test
    void convertsTheTenCypherStatementsToGremlin(@TempDir Path directory) {
        GraphSchema schema = SanguoRocksDbLoader.loadSchema();
        SanguoDataset dataset = SanguoDataset.load();
        Path path = directory.resolve(schema.storage().requireRocksDbPath());

        withRocksDb(schema, path, dataset, (gremlin, cypher) -> {
            System.out.println();
            System.out.println("================================================================================");
            System.out.println(" 十个 Cypher 语句 → Gremlin 等价写法（同一张图：sanguo-kg-rocksdb-edge）");
            System.out.println(" 点 id = 实体名本身；边属性 relation / xrelation / relationLabel / relationFamily");
            System.out.println("================================================================================");

            List<SanguoRocksDbQueries.Conversion> conversions = SanguoRocksDbQueries.CYPHER_TO_GREMLIN;
            for (int i = 0; i < conversions.size(); i++) {
                SanguoRocksDbQueries.Conversion conversion = conversions.get(i);
                CypherResult cypherRows = cypher.execute(conversion.cypher());
                List<Object> gremlinRows = gremlin.execute(conversion.gremlin());
                System.out.printf("%n[%2d] %s%n"
                                + "      Cypher : %s%n"
                                + "               → %s%n"
                                + "      Gremlin: %s%n"
                                + "               → %s%n"
                                + "      说明   : %s%n",
                        i + 1, conversion.title(), conversion.cypher(), summariseCypher(cypherRows),
                        conversion.gremlin(), summariseGremlin(gremlinRows), conversion.note());
                assertTrue(!cypherRows.rows().isEmpty(), "cypher " + (i + 1) + " returned nothing");
                assertTrue(!gremlinRows.isEmpty(), "gremlin " + (i + 1) + " returned nothing");
            }

            // the two languages must agree on the statements that count rows
            assertEquals(cypher.execute("MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) "
                            + "WHERE r.relation = 'lord' RETURN x.name AS subject LIMIT 20").rows().size(),
                    gremlin.execute("g.V('曹操','Entity').outE('Entity_Entity')"
                            + ".filter(relation.equals('lord')).limit(20).otherV().values('name')").size(),
                    "③ the same 20 主公 relations");
            assertEquals(cypher.execute("MATCH (p:Entity {id:'诸葛亮'})-[r:Entity_Entity]->(c:Entity) "
                            + "WHERE r.relation = 'appearsIn' RETURN c.name LIMIT 25").rows().size(),
                    gremlin.execute("g.V('诸葛亮','Entity').outE('Entity_Entity')"
                            + ".filter(relation.equals('appearsIn')).limit(25).otherV().values('name')").size(),
                    "⑦ the same 25 chapters");
            assertEquals(cypher.execute("MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) "
                            + "RETURN r.relationFamily AS family, count(*) AS n").rows().size(),
                    ((Map<?, ?>) gremlin.execute("g.V('曹操','Entity').outE('Entity_Entity')"
                            + ".groupCount().by('relationFamily')").get(0)).size(),
                    "⑤ the same relation families");
            System.out.println("\n[check] ③ 20 条主公关系、⑦ 25 个章回、⑤ 7 个关系家族 —— 两种语言结果一致");

            // the same table, for the documentation
            SanguoRdf.write(Path.of("target", "sanguo-rdf", "CYPHER-TO-GREMLIN.md"),
                    "# sanguo-kg-rocksdb-edge: Cypher → Gremlin\n\n"
                            + SanguoRocksDbQueries.conversionsMarkdown());
        });
    }

    /** The two- and three-hop relation queries, run in both languages and printed. */
    @Test
    void answersTheMultiHopRelationQueries(@TempDir Path directory) {
        GraphSchema schema = SanguoRocksDbLoader.loadSchema();
        SanguoDataset dataset = SanguoDataset.load();
        Path path = directory.resolve(schema.storage().requireRocksDbPath());

        withRocksDb(schema, path, dataset, (gremlin, cypher) -> {
            System.out.println();
            System.out.println("================================================================================");
            System.out.println(" 多跳关系查询（两跳 / 三跳）—— 关系链、共同邻居、同势力、同回出场、路径强度、三跳环");
            System.out.println("================================================================================");

            List<SanguoRocksDbQueries.HopQuery> hops = SanguoRocksDbQueries.MULTI_HOP;
            for (int i = 0; i < hops.size(); i++) {
                SanguoRocksDbQueries.HopQuery hop = hops.get(i);
                List<Object> gremlinRows = gremlin.execute(hop.gremlin());
                CypherResult cypherRows = cypher.execute(hop.cypher());
                System.out.printf("%n[%2d] %s%n"
                                + "      (%s)%n"
                                + "      Gremlin: %s%n"
                                + "               → %s%n"
                                + "      Cypher : %s%n"
                                + "               → %s%n",
                        i + 1, hop.title(), hop.titleEn(), hop.gremlin(), summariseGremlin(gremlinRows),
                        hop.cypher(), summariseCypher(cypherRows));
                assertTrue(!gremlinRows.isEmpty(), "multi-hop " + (i + 1) + " (gremlin) returned nothing");
                assertTrue(!cypherRows.rows().isEmpty(), "multi-hop " + (i + 1) + " (cypher) returned nothing");
            }

            // the two languages must agree wherever both count the same thing
            System.out.println("\n[check] 两种语言对同一问题的计数对照");
            agree("1  刘备的两跳邻居数", scalar(gremlin, hops.get(0).gremlin()),
                    cell(cypher.execute(hops.get(0).cypher()), "twoHopNeighbours"));
            agree("4  徐庶的同僚数", scalar(gremlin, hops.get(3).gremlin()),
                    cell(cypher.execute(hops.get(3).cypher()), "colleagues"));
            agree("5  诸葛亮同回出场人数", scalar(gremlin, hops.get(4).gremlin()),
                    cell(cypher.execute(hops.get(4).cypher()), "sameChapter"));
            agree("7  刘备两跳到曹操的路径数", scalar(gremlin, hops.get(6).gremlin()),
                    cell(cypher.execute(hops.get(6).cypher()), "paths"));
            agree("8  刘备三跳到曹操的路径数", scalar(gremlin, hops.get(7).gremlin()),
                    cell(cypher.execute(hops.get(7).cypher()), "paths"));
            agree("10 魏三跳到蜀的路径数", scalar(gremlin, hops.get(9).gremlin()),
                    cell(cypher.execute(hops.get(9).cypher()), "paths"));
            agree("11 司马懿的孙子数",
                    gremlin.execute(hops.get(10).gremlin()).size(),
                    cypher.execute(hops.get(10).cypher()).rows().size());

            // the same table, for the documentation
            SanguoRdf.write(Path.of("target", "sanguo-rdf", "MULTI-HOP-QUERIES.md"),
                    "# sanguo-kg-rocksdb-edge: 多跳（2/3 跳）关系查询\n\n"
                            + SanguoRocksDbQueries.multiHopMarkdown());
        });
    }

    private static long scalar(GremlinGraphQuery gremlin, String query) {
        return ((Number) gremlin.execute(query).get(0)).longValue();
    }

    private static long cell(CypherResult result, String column) {
        Object value = result.rows().get(0).get(column);
        return ((Number) value).longValue();
    }

    private static void agree(String what, long gremlin, long cypher) {
        System.out.printf("        %-30s Gremlin %-6d Cypher %-6d %s%n", what, gremlin, cypher,
                gremlin == cypher ? "一致" : "不一致");
        assertEquals(gremlin, cypher, what);
    }

    /** The Aerospike vertex id of an entity in the shard of a relation. */
    private static String aerospikeId(String name, String relation) {
        return "g.V('" + SanguoGraphIds.composeId(name, relation) + "','Entity')";
    }

    private static void check(String what, long expected, long actual) {
        System.out.printf("        %-26s 期望 %-6d 实际 %-6d %s%n", what, expected, actual,
                expected == actual ? "ok" : "MISMATCH");
        assertEquals(expected, actual, what);
    }

    private static void parity(String question, long expected, long aerospike, long rocksDb) {
        boolean same = expected == aerospike && aerospike == rocksDb;
        System.out.printf("%-32s %-14d %-14d %s%n", question, aerospike, rocksDb, same ? "是" : "否");
        assertEquals(expected, aerospike, question + " (Aerospike)");
        assertEquals(expected, rocksDb, question + " (RocksDB-Edge)");
    }

    private static long count(GremlinGraphQuery gremlin, String query) {
        return ((Number) gremlin.execute(query).get(0)).longValue();
    }

    private static void header(GraphSchema schema, SanguoRocksDbLoader.Result result, SanguoDataset dataset) {
        System.out.println();
        System.out.println("================================================================");
        System.out.printf(" 实例   : %s%n", schema.name());
        System.out.printf(" 存储   : %s  (%s)%n", schema.storage().type().configName(), result.path());
        System.out.printf(" 规模   : %d 个点 / %d 条边（数据集 %d 实体 / %d 关系）%n",
                result.vertices(), result.edges(), dataset.entities().size(), dataset.relations().size());
        System.out.println(" 点 id  : 实体名本身（不做 hash、不分片），例如 曹操 / 魏 / 第49回");
        System.out.println(" 边     : 每条关系一条有向边 head --relation--> tail，边上带");
        System.out.println("          relation / xrelation / relationLabel(中文) / relationFamily(家族)");
        System.out.println(" 与 Aerospike 版的差异：");
        System.out.println("          · 一个人的全部关系只需读 1 条记录（Aerospike 版按 relation 分片，要读 10 个分片）");
        System.out.printf("          · 只存 %,d 条源关系，不复制逆/对称闭包（Aerospike 版为 %,d 条）：%n",
                dataset.relations().size(), dataset.closedRelations().size());
        System.out.println("            RocksDB-Edge 把同一条边同时挂在源点的出表和目标点的入表，");
        System.out.println("            所以 bothE() 两端都能读到，relation || xrelation 惯用法照样成立");
        System.out.println("================================================================");
    }

    private static String summariseGremlin(List<Object> rows) {
        if (rows.isEmpty()) {
            return "0 条";
        }
        if (rows.size() == 1 && rows.get(0) instanceof Number number) {
            return number + "  (标量)";
        }
        if (rows.size() == 1 && rows.get(0) instanceof Map<?, ?> map) {
            if (map.values().stream().allMatch(value -> value instanceof Number)) {
                // a groupCount() / aggregation map: show the biggest entries, not a 200-entry dump
                String top = map.entrySet().stream()
                        .sorted(java.util.Comparator.comparingLong(
                                entry -> -((Number) entry.getValue()).longValue()))
                        .limit(6)
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining(", "));
                return map.size() + " 组, Top6: {" + top + (map.size() > 6 ? ", ..." : "") + "}";
            }
            // a single elementMap(): the edge/vertex itself
            return "1 个元素: " + map.entrySet().stream().limit(8)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", ")) + (map.size() > 8 ? ", ..." : "");
        }
        String sample = rows.stream().limit(4).map(SanguoRocksDbEdgeTest::describe)
                .collect(Collectors.joining(" | "));
        return rows.size() + " 条: " + sample + (rows.size() > 4 ? " | ..." : "");
    }

    private static String describe(Object row) {
        if (row instanceof GremlinEdge edge) {
            Map<?, ?> properties = edge.properties();
            Object relation = properties == null ? null : properties.get("relation");
            Object label = properties == null ? null : properties.get("relationLabel");
            // a pushdown filter only reads the bins it needs, so relationLabel may not be loaded:
            // show it when it is there rather than printing a misleading null
            String text = relation == null ? "?" : String.valueOf(relation);
            if (label != null) {
                text += "/" + label;
            }
            return "边(" + text + " -> " + edge.otherVertexId() + ")";
        }
        if (row instanceof GremlinVertex vertex) {
            Map<?, ?> properties = vertex.properties();
            Object name = properties == null ? null : properties.get("name");
            return "点(" + (name == null ? vertex.id() : name) + ")";
        }
        if (row instanceof Map<?, ?> map) {
            return map.toString();
        }
        return String.valueOf(row);
    }

    private static String summariseCypher(CypherResult result) {
        if (result.rows().isEmpty()) {
            return "0 行";
        }
        String sample = result.rows().stream().limit(3).map(Object::toString).collect(Collectors.joining(" | "));
        return result.columns() + " -> " + result.rows().size() + " 行: " + sample
                + (result.rows().size() > 3 ? " | ..." : "");
    }

    private static String servedDirectory() {
        return Path.of(SERVED_ROOT).resolve(SanguoRocksDbLoader.loadSchema().storage().requireRocksDbPath())
                .toAbsolutePath().normalize().toString();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    // --- in-memory expectations, computed from the parsed dataset (no closure) ---

    /** Degrees and neighbours of the RocksDB-Edge graph, keyed by the plain entity name. */
    private record Stats(Map<String, Integer> out, Map<String, Integer> in,
                         Map<String, Map<String, Integer>> forward,
                         Map<String, Map<String, Integer>> incoming,
                         Map<String, Set<String>> neighbours) {

        static Stats of(SanguoDataset dataset) {
            Map<String, Integer> out = new HashMap<>();
            Map<String, Integer> in = new HashMap<>();
            Map<String, Map<String, Integer>> forward = new HashMap<>();
            Map<String, Map<String, Integer>> incoming = new HashMap<>();
            Map<String, Set<String>> neighbours = new HashMap<>();
            for (SanguoDataset.Relation relation : dataset.relations()) {
                String head = relation.head();
                String tail = relation.tail();
                out.merge(head, 1, Integer::sum);
                in.merge(tail, 1, Integer::sum);
                forward.computeIfAbsent(head, k -> new HashMap<>()).merge(relation.relation(), 1, Integer::sum);
                incoming.computeIfAbsent(tail, k -> new HashMap<>()).merge(relation.relation(), 1, Integer::sum);
                neighbours.computeIfAbsent(head, k -> new LinkedHashSet<>()).add(tail);
                neighbours.computeIfAbsent(tail, k -> new LinkedHashSet<>()).add(head);
            }
            return new Stats(out, in, forward, incoming, neighbours);
        }

        int degree(String name) {
            return out.getOrDefault(name, 0) + in.getOrDefault(name, 0);
        }

        int forward(String name, String relation) {
            return forward.getOrDefault(name, Map.of()).getOrDefault(relation, 0);
        }

        int incoming(String name, String relation) {
            return incoming.getOrDefault(name, Map.of()).getOrDefault(relation, 0);
        }

        Set<String> neighbourhood(String name) {
            return neighbours.getOrDefault(name, Set.of());
        }
    }
}

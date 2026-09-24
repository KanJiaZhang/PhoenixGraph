package com.pengcheng.phoenixgraph.integration.sanguo;

import com.pengcheng.phoenixgraph.query.cypher.CypherGraphQuery;
import com.pengcheng.phoenixgraph.query.cypher.CypherResult;
import com.pengcheng.phoenixgraph.query.gremlin.GremlinGraphQuery;
import com.pengcheng.phoenixgraph.query.storage.GraphQuery;
import com.pengcheng.phoenixgraph.query.traversal.NeighborVertexResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.storage.rocksdb.RocksDbStoreConfig;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeGraphQuery;
import com.pengcheng.phoenixgraph.storage.rocksdb.edge.RocksDbEdgeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>方案 C</b>: what the document free text ({@code 历史}/{@code 小说}) yields, and the
 * new <b>RocksDB-Edge</b> instance that holds it — {@code sanguo-kg-text}.
 *
 * <p>Checks:
 * <ol>
 *   <li>the extraction finds <em>more</em> entities and relations than the base graph;</li>
 *   <li>it rediscovers, from the text alone, the facts 方案 B had to be told by hand
 *   (the 五虎上将 offices) and the ones the earlier questions asked about
 *   (徐庶/庞统 是刘备的军师);</li>
 *   <li>the new graph answers those questions through the query engine, not just in
 *   memory;</li>
 *   <li>the extraction report (per rule, with provenance) is written out for review.</li>
 * </ol>
 */
class SanguoTextGraphTest {

    /** The RocksDB root the service reads, relative to the module directory. */
    private static final String SERVED_ROOT = env("SANGUO_ROCKSDB_ROOT", "../.rocksdb");

    @Test
    void extractsMoreEntitiesAndRelationsThanTheBaseGraph() {
        SanguoDataset base = SanguoDataset.load();
        SanguoTextGraph.Built built = SanguoTextGraph.build();
        SanguoTextExtractor.Result extraction = built.extraction();

        System.out.println();
        System.out.println("======================================================================");
        System.out.println(" 方案 C：从文档自由文本（历史 / 小说）抽取");
        System.out.println("======================================================================");
        System.out.printf(" base 图   : %d 实体 / %d 关系 (%d 物化)%n",
                base.entities().size(), base.relations().size(), base.closedRelations().size());
        System.out.printf(" text 图   : %s%n", built.describe());
        System.out.printf(" 增量      : 实体 +%d，关系 +%d%n",
                built.dataset().entities().size() - base.entities().size(),
                built.dataset().relations().size() - base.relations().size());
        System.out.println("\n 抽取规则计数：");
        extraction.countByRule().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> {
                    System.out.printf("   %-14s %5d   例: %s%n", entry.getKey(), entry.getValue(),
                            extraction.samplesByRule().getOrDefault(entry.getKey(), List.of()).stream()
                                    .findFirst().orElse(""));
                });
        System.out.println("\n 新增职位(" + extraction.newPositions().size() + "): "
                + String.join(" ", extraction.newPositions()));
        System.out.println(" 新增人物(" + extraction.newPersons().size() + "): "
                + String.join(" ", extraction.newPersons()));

        assertTrue(built.dataset().entities().size() > base.entities().size(),
                "方案 C must find more entities: " + built.dataset().entities().size() + " vs " + base.entities().size());
        assertTrue(built.dataset().relations().size() > base.relations().size(),
                "方案 C must find more relations");
        assertEquals(902, extraction.countByRule().getOrDefault("mentions", 0), "mentions is the bulk");
        assertTrue(!extraction.newPositions().isEmpty(), "new offices must be discovered");
        assertTrue(!extraction.newPersons().isEmpty(), "new people must be discovered");

        // it rediscovers what 方案 B was told by hand
        assertTrue(extraction.of("holds").stream()
                        .anyMatch(r -> r.tail().equals("五虎上将") || r.tail().equals("五虎上将之首")),
                "the 五虎 rule must fire (方案 B had to add these by hand)");
        // and answers the 军师 question with what the data actually says
        assertTrue(extraction.of("strategistOf").stream()
                        .anyMatch(r -> r.relation().equals("strategistOf") && r.tail().equals("刘备")
                                && (r.head().equals("徐庶") || r.head().equals("庞统"))),
                "the text says 徐庶/庞统 are 刘备's 军师");

        // the RDF view of the text graph is consistent with its ontology (text profile included)
        SanguoRdfValidator.Report report = SanguoRdfValidator.validate(built.dataset());
        report.violations().forEach(System.out::println);
        assertTrue(report.valid(), "the text graph must validate too: " + report.violations().size());
        System.out.printf(" 校验      : PASS（%d 条 domain/range 检查，%d 条无法判定）%n",
                report.domainRangeChecked(), report.domainRangeUnverifiable());
    }

    /** Loads the served instance and answers the questions through the query engine. */
    @Test
    void answersTheFiveTigerAndStrategistQuestions(@TempDir Path directory) {
        GraphSchema schema = SanguoTextGraph.loadSchema();
        SanguoTextGraph.Built built = SanguoTextGraph.build();
        Path path = directory.resolve(schema.storage().requireRocksDbPath());
        SanguoRocksDbLoader.load(schema, built.dataset(), path, true);

        RocksDbStoreConfig config = new RocksDbStoreConfig(path.toString(), schema.storage().regionCount(),
                schema.storage().maxVersions(), true, schema.storage().cacheEdges());
        try (RocksDbEdgeStore store = RocksDbEdgeStore.open(schema, config)) {
            GraphQuery graph = new RocksDbEdgeGraphQuery(store, schema);
            GremlinGraphQuery gremlin = new GremlinGraphQuery(graph, NeighborVertexResolver.from(schema));
            CypherGraphQuery cypher = new CypherGraphQuery(graph, NeighborVertexResolver.from(schema));

            System.out.println();
            System.out.println("======================================================================");
            System.out.println(" text 图（" + SanguoTextGraph.GRAPH + "）上的问答");
            System.out.println("======================================================================");
            String[][] questions = {
                    {"五虎上将是谁（方案 C 自动抽到）",
                            "g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV().values('name')"},
                    {"五虎将之首是谁",
                            "g.V('五虎上将之首','Entity').inE().filter(relation.equals('holds')).otherV().values('name')"},
                    {"刘备的军师是谁（文本抽取）",
                            "g.V('刘备','Entity').inE().filter(relation.equals('strategistOf')).otherV().values('name')"},
                    {"庞统是谁的军师（文本抽取）",
                            "g.V('庞统','Entity').outE().filter(relation.equals('strategistOf')).otherV().values('name')"},
                    {"诸葛亮是刘备的军师吗（数据说不是）",
                            "g.V('诸葛亮','Entity').inE().filter(relation.equals('strategistOf')).otherV().values('name')"},
                    {"曹操的谋士是谁",
                            "g.V('曹操','Entity').inE().filter(relation.equals('advisorOf')).otherV().values('name')"},
                    {"关羽和谁交战（文本抽取）",
                            "g.V('关羽','Entity').bothE().filter(relation.equals('fought')).otherV().dedup().values('name')"},
                    {"关羽斩杀过谁",
                            "g.V('关羽','Entity').outE().filter(relation.equals('killed')).otherV().values('name')"},
                    {"谁斩杀了关羽（反向）",
                            "g.V('关羽','Entity').inE().filter(relation.equals('killed')).otherV().values('name')"},
                    {"关羽的传记里提到了谁（mentions，前 12）",
                            "g.V('关羽','Entity').outE().filter(relation.equals('mentions')).limit(12).otherV().values('name')"},
                    {"关羽和马超是什么关系（共同职位）",
                            "g.V('关羽','Entity').outE().filter(relation.equals('holds')).otherV().dedup()"
                                    + ".where(inE().filter(relation.equals('holds')).otherV().hasId('马超')).values('name')"},
                    {"关羽和马超是什么关系（共同主公）",
                            "g.V('关羽','Entity').inE().filter(relation.equals('lord')).otherV().dedup()"
                                    + ".where(outE().filter(relation.equals('lord')).otherV().hasId('马超')).values('name')"},
            };
            for (String[] question : questions) {
                List<Object> rows = gremlin.execute(question[1]);
                System.out.printf("%n%s%n  %s%n  → %s%n", question[0], question[1], summarise(rows));
                if (question[0].contains("数据说不是")) {
                    assertTrue(rows.isEmpty(), "the data does not call 诸葛亮 a 军师 — see SANGUO-KG-QUERIES.md §9");
                } else {
                    assertTrue(!rows.isEmpty(), question[0] + " returned nothing");
                }
            }

            // the Cypher side sees the same new relations (it needs an anchored start)
            CypherResult result = cypher.execute(
                    "MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'五虎上将'}) WHERE r.relation='holds' "
                            + "RETURN p.name AS general LIMIT 10");
            System.out.printf("%nCypher: MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'五虎上将'}) "
                    + "WHERE r.relation='holds' RETURN p.name%n  → %s%n", result.rows());
            assertTrue(!result.rows().isEmpty(), "Cypher must see the extracted 五虎上将 edges");
        }
    }

    /** Loads the instance the service serves, so the UI can query it. */
    @Test
    void loadsTheInstanceTheServiceServes() {
        boolean reseed = Boolean.parseBoolean(env("SANGUO_ROCKSDB_RESEED", "false"));
        if (!reseed && SanguoTextGraph.isLoaded(SERVED_ROOT)) {
            System.out.printf("[sanguo-text] %s already loaded under %s "
                            + "- set SANGUO_ROCKSDB_RESEED=true (service stopped) to rebuild%n",
                    SanguoTextGraph.GRAPH, SERVED_ROOT);
            return;
        }
        SanguoTextGraph.Built built = SanguoTextGraph.build();
        long started = System.currentTimeMillis();
        SanguoRocksDbLoader.Result result = SanguoTextGraph.load(built, SERVED_ROOT, reseed);
        System.out.printf("[sanguo-text] loaded %s at %s: %d vertices, %d edges in %d ms%n",
                result.graph(), result.path(), result.vertices(), result.edges(),
                System.currentTimeMillis() - started);
    }

    /** Writes the extraction report (per rule, with provenance) for review. */
    @Test
    void writesTheExtractionReport() {
        SanguoDataset base = SanguoDataset.load();
        SanguoTextGraph.Built built = SanguoTextGraph.build();
        SanguoTextExtractor.Result extraction = built.extraction();

        StringBuilder out = new StringBuilder();
        out.append("# 方案 C：从文档自由文本抽取的实体与关系\n\n");
        out.append("> 由 `SanguoTextGraphTest#writesTheExtractionReport` 生成。规则、模式、命中样本与 provenance 全部列出，\n");
        out.append("> 可按行核对。抽取只用**词典 + 正则**，不用模型，所以结果可复现。\n\n");
        out.append("## 规模\n\n| | base 图 | text 图 | 增量 |\n|---|---|---|---|\n");
        out.append("| 实体 | ").append(base.entities().size()).append(" | ").append(built.dataset().entities().size())
                .append(" | +").append(built.dataset().entities().size() - base.entities().size()).append(" |\n");
        out.append("| 关系 | ").append(base.relations().size()).append(" | ").append(built.dataset().relations().size())
                .append(" | +").append(built.dataset().relations().size() - base.relations().size()).append(" |\n");
        out.append("| 物化边 | ").append(base.closedRelations().size()).append(" | ")
                .append(built.dataset().closedRelations().size()).append(" | +")
                .append(built.dataset().closedRelations().size() - base.closedRelations().size()).append(" |\n\n");

        out.append("## 规则与命中\n\n| 关系 | 中文 | 条数 | 样本 |\n|---|---|---|---|\n");
        extraction.countByRule().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> out.append("| `").append(entry.getKey()).append("` | ")
                        .append(built.dataset().ontology().label(entry.getKey())).append(" | ")
                        .append(entry.getValue()).append(" | ")
                        .append(extraction.samplesByRule().getOrDefault(entry.getKey(), List.of()).stream()
                                .findFirst().orElse("").replace("|", "\\|"))
                        .append(" |\n"));

        out.append("\n## 新增实体\n\n");
        out.append("### 职位（").append(extraction.newPositions().size()).append("）\n\n```\n");
        out.append(String.join("  ", extraction.newPositions()));
        out.append("\n```\n\n### 人物（").append(extraction.newPersons().size()).append("）\n\n```\n");
        out.append(String.join("  ", extraction.newPersons()));
        out.append("\n```\n\n");

        out.append("## 每个规则的样本（带 provenance）\n\n");
        for (Map.Entry<String, Integer> entry : extraction.countByRule().entrySet()) {
            out.append("### `").append(entry.getKey()).append("` （").append(entry.getValue()).append(" 条）\n\n");
            for (String sample : extraction.samplesByRule().getOrDefault(entry.getKey(), List.of())) {
                out.append("- ").append(sample.replace("|", "\\|")).append('\n');
            }
            out.append('\n');
        }

        out.append("## 已知局限\n\n");
        out.append("- `mentions` 只匹配**已知实体名**（词典），所以零幻觉，但不会发现新实体。\n");
        out.append("- `holds`/`fought`/`killed` 是**远程监督**：句子的主语未必是文档本人，噪声按规则逐条记录在样本里。\n");
        out.append("- 地点抽取刻意没做（没有词典会太脏）；`军师`/`谋士` 只连**人物**。\n");
        out.append("- 新增人物名来自关系槽（如 `为X所杀`、`X谋士Y`），可能有 `曹将吕旷` 这类带前缀的噪声。\n");

        Path outDir = Path.of("target", "sanguo-rdf");
        SanguoRdf.write(outDir.resolve("TEXT-EXTRACTION.md"), out.toString());
        if (Boolean.getBoolean("sanguo.queries.write")) {
            SanguoRdf.write(Path.of("..", "SANGUO-KG-TEXT-EXTRACTION.md"), out.toString());
        }
        System.out.printf("%n[text] 抽取报告已写出：%s%n", outDir.toAbsolutePath().resolve("TEXT-EXTRACTION.md"));
    }

    private static String summarise(List<Object> rows) {
        if (rows.isEmpty()) {
            return "0 条";
        }
        if (rows.size() == 1 && rows.get(0) instanceof Number number) {
            return String.valueOf(number);
        }
        String flat = rows.stream().limit(12).map(String::valueOf).reduce((a, b) -> a + " | " + b).orElse("");
        return rows.size() + " 条: " + flat + (rows.size() > 12 ? " | ..." : "");
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

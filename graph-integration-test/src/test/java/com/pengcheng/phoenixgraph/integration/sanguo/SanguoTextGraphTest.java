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
import java.util.stream.Collectors;

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
        System.out.println(" 典故/事件(" + extraction.allusions().size() + "):");
        extraction.allusions().entrySet().stream()
                .collect(Collectors.groupingBy(e -> e.getValue().rdfClass(), LinkedHashMap::new, Collectors.toList()))
                .forEach((type, entries) -> System.out.printf("   %-9s %2d  %s%n", type, entries.size(),
                        entries.stream().map(Map.Entry::getKey).collect(Collectors.joining(" "))));

        assertTrue(built.dataset().entities().size() > base.entities().size(),
                "方案 C must find more entities: " + built.dataset().entities().size() + " vs " + base.entities().size());
        assertTrue(built.dataset().relations().size() > base.relations().size(),
                "方案 C must find more relations");
        assertEquals(22_951, extraction.countByRule().getOrDefault("mentions", 0),
                "mentions is the bulk: known entity names + the 方案 ③ allusions");
        assertTrue(!extraction.newPositions().isEmpty(), "new offices must be discovered");
        assertTrue(!extraction.newPersons().isEmpty(), "new people must be discovered");

        // 方案 ③: the allusion is recorded as a node; its participants are never invented
        assertEquals(SanguoDataset.EntityType.EVENT, extraction.allusions().get("过五关斩六将"),
                "过五关斩六将 must become an Event");
        assertTrue(extraction.relations().stream().anyMatch(r -> r.relation().equals("mentions")
                        && r.head().equals("关羽") && r.tail().equals("过五关斩六将")),
                "关羽 --mentions--> 过五关斩六将");
        assertEquals(SanguoDataset.EntityType.EVENT,
                built.dataset().entities().get("过五关斩六将").type(), "the node must be typed Event");
        assertEquals(SanguoDataset.EntityType.ARTIFACT,
                built.dataset().entities().get("赤兔马").type(), "赤兔马 is an object");
        assertEquals(SanguoDataset.EntityType.IDIOM,
                built.dataset().entities().get("刮目相看").type(), "刮目相看 is a set phrase");
        // 方案 ③ still refuses to invent: 六将 is a count, not a name
        assertTrue(!built.dataset().entities().containsKey("六将"),
                "六将 must not be invented — it is the allusion's name, not a person");
        // ...but the full 4,487-person export documents all six of them, so they are real
        // entities now (the truncated 331-person copy stopped before any of them)
        for (String general : List.of("孔秀", "韩福", "孟坦", "卞喜", "王植", "秦琪", "蔡阳")) {
            assertTrue(built.dataset().entities().containsKey(general),
                    general + " is documented by the full export");
        }
        // and their biographies narrate the kills. Two of the seven are reachable by rule:
        // 卞喜 ("最后被关羽斩杀") and 蔡阳 ("为羽所杀", the one-character alias 羽 = 关羽).
        // The other five are told in narrative prose that needs coreference, not a pattern —
        // 孔秀 "羽怒，举刀杀秀", 韩福 "挥刀斩于马下", 孟坦 "砍为两段", 秦琪 "琪头落",
        // 王植 "被关公拦腰一刀，砍为两段". They stay in the data as nodes; the edge is the gap.
        for (String general : List.of("卞喜", "蔡阳")) {
            assertTrue(extraction.of("killed").stream().anyMatch(r -> r.head().equals("关羽")
                            && r.tail().equals(general)),
                    "关羽 --killed--> " + general + " must be extracted from that biography");
        }
        for (String allusion : extraction.allusionNames()) {
            assertTrue(!base.entities().containsKey(allusion),
                    allusion + " must not collide with a base entity");
        }

        // 方案 ④ (SANGUO-KG.md §7.8): the time dimension on the relations.
        // The question it is meant to answer: was 徐庶 刘备's strategist before he served 曹操?
        SanguoDataset.RelationTime toLiuBei = time(built.dataset(), "徐庶", "mentions", "刘备");
        SanguoDataset.RelationTime toCaoCao = time(built.dataset(), "徐庶", "mentions", "曹操");
        // both windows are the same: 刘备 and 曹操 each appear in nearly every chapter, so the
        // intersection is just 徐庶's own range. 时间/回数 alone CANNOT order the two relations.
        assertEquals("196-208", toLiuBei.year(), "the year window of 徐庶's 刘备 relations");
        assertEquals("35-48", toLiuBei.chapter(), "the chapter window of 徐庶's 刘备 relations");
        assertEquals(toLiuBei.year(), toCaoCao.year(), "the same year window for 曹操");
        assertEquals(toLiuBei.chapter(), toCaoCao.chapter(), "the same chapter window for 曹操");
        // the narrative order does: in 徐庶's 历史 column he 效力于刘备 (sentence 2) long before he
        // 北投曹操 (sentence 3). seq = field*1000 + sentence, so 1002 < 1003 — 刘备 first.
        assertEquals(1002L, toLiuBei.seq(), "刘备 is named in the 2nd sentence of 徐庶's 历史");
        assertEquals(1003L, toCaoCao.seq(), "曹操 in the 3rd — he 北投曹操 after 长坂");
        assertTrue(toLiuBei.seq() < toCaoCao.seq(), "徐庶 was 刘备's first");
        // and the 小说 column agrees (a different retelling, compared within itself)
        assertTrue(time(built.dataset(), "徐庶", "strategistOf", "刘备").seq()
                        < time(built.dataset(), "徐庶", "mentions", "赤壁之战").seq(),
                "in the 小说 column 被拜为军师 (sentence 3) precedes 赤壁之战 (sentence 6)");
        // every document-derived relation carries the window; only extracted ones carry a seq
        assertTrue(built.dataset().times().values().stream().filter(SanguoDataset.RelationTime::ordered).count()
                        > 20_000,
                "the extracted relations must all be ordered");

        // it rediscovers what 方案 B was told by hand
        assertTrue(extraction.of("holds").stream()
                        .anyMatch(r -> r.tail().equals("五虎上将") || r.tail().equals("五虎上将之首")),
                "the 五虎 rule must fire (方案 B had to add these by hand)");
        // and answers the 军师 question with what the data actually says
        assertTrue(extraction.of("strategistOf").stream()
                        .anyMatch(r -> r.relation().equals("strategistOf") && r.tail().equals("刘备")
                                && (r.head().equals("徐庶") || r.head().equals("庞统"))),
                "the text says 徐庶/庞统 are 刘备's 军师");

        // the RDF view of the text graph is consistent with its ontology (text profile included).
        // The only violation is the same upstream one the base graph carries (刘建[安国侯]'s 母亲
        // names a male) — asserting it exactly means a new extraction-induced violation fails.
        SanguoRdfValidator.Report report = SanguoRdfValidator.validate(built.dataset());
        assertEquals(List.of("domain: 刘英 --mother--> 刘建[安国侯] (刘英 should be female but is Male, 男)"),
                report.violations().stream().map(Object::toString).toList(),
                "the text extraction must not add violations — in particular `killed` is "
                        + "owl:AsymmetricProperty, so the 衣带诏 conspirators must not be both killer and victim");
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
                    // 方案 ③ — the allusion as a node. NOTE: `has('entityType','Event')` must be an
                    // equality test; the engine does not implement `within(...)` and returns empty for it.
                    {"关羽的传记提到哪些典故/事件",
                            "g.V('关羽','Entity').outE().filter(relation.equals('mentions')).otherV()"
                                    + ".has('entityType','Event').values('name')"},
                    {"关羽的传记提到哪些器物",
                            "g.V('关羽','Entity').outE().filter(relation.equals('mentions')).otherV()"
                                    + ".has('entityType','Artifact').values('name')"},
                    {"过五关斩六将是什么类型的节点",
                            "g.V('过五关斩六将','Entity').values('entityType')"},
                    {"谁的传记提到过五关斩六将",
                            "g.V('过五关斩六将','Entity').inE().filter(relation.equals('mentions')).otherV().values('name')"},
                    {"诸葛亮提到了哪些事件",
                            "g.V('诸葛亮','Entity').outE().filter(relation.equals('mentions')).otherV()"
                                    + ".has('entityType','Event').values('name')"},
                    {"赤兔马是谁的（器物反向）",
                            "g.V('赤兔马','Entity').inE().filter(relation.equals('mentions')).otherV().values('name')"},
                    // 方案 ④ — the time dimension. `seq` is the narrative position inside the biography
                    // (field*1000 + sentence): order by it to get "who came first".
                    {"方案 ④：徐庶的关系按叙述序排（谁在先）",
                            "g.V('徐庶','Entity').outE().filter(relation.equals('mentions')).order().by('seq')"
                                    + ".limit(4).values('seq')"},
                    {"方案 ④：徐庶关系的年份/回数区间",
                            "g.V('徐庶','Entity').outE().filter(relation.equals('mentions')).limit(4)"
                                    + ".values('year','chapter','seq')"},
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

        out.append("\n## 方案 ③：典故 / 事件节点（").append(extraction.allusions().size()).append(" 个）\n\n");
        out.append("只记录**典故本身**，不虚构它的参与者（`过五关斩六将` 不产生任何六将姓名 —— 语料从未给出）。\n\n");
        out.append("| 类型 | 中文 | 数量 | 典故 |\n|---|---|---|---|\n");
        extraction.allusions().entrySet().stream()
                .collect(Collectors.groupingBy(e -> e.getValue().rdfClass(), LinkedHashMap::new, Collectors.toList()))
                .forEach((type, entries) -> out.append("| `").append(type).append("` | ")
                        .append(built.dataset().ontology().classLabel(type)).append(" | ")
                        .append(entries.size()).append(" | ")
                        .append(entries.stream().map(Map.Entry::getKey).collect(Collectors.joining("、")))
                        .append(" |\n"));

        out.append("\n### 谁提到了哪个典故（`mentions` 边，带 provenance）\n\n");
        extraction.relations().stream()
                .filter(r -> extraction.allusions().containsKey(r.tail()))
                .limit(20)
                .forEach(r -> out.append("- ").append(r.head()).append(" --mentions--> ").append(r.tail())
                        .append("  («").append(r.sentence().length() > 70
                                ? r.sentence().substring(0, 70) + "…" : r.sentence()).append("»)\n"));
        out.append("\n全部 ").append(extraction.relations().stream()
                .filter(r -> extraction.allusions().containsKey(r.tail())).count())
                .append(" 条边；查询：`g.V('过五关斩六将','Entity').inE().filter(relation.equals('mentions')).otherV().values('name')`\n");

        out.append("\n## 每个规则的样本（带 provenance）\n\n");
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
        out.append("- 方案 ③ 的典故归到**传记主人**名下（与 `mentions` 一致）：\n");
        out.append("  郭常子的传记提到「关羽过五关斩六将」时，这条就归给郭常子而不是关羽 —— owner-scoped 抽取的固有边界，原句保留供核对。\n");

        Path outDir = Path.of("target", "sanguo-rdf");
        SanguoRdf.write(outDir.resolve("TEXT-EXTRACTION.md"), out.toString());
        if (Boolean.getBoolean("sanguo.queries.write")) {
            SanguoRdf.write(Path.of("..", "SANGUO-KG-TEXT-EXTRACTION.md"), out.toString());
        }
        System.out.printf("%n[text] 抽取报告已写出：%s%n", outDir.toAbsolutePath().resolve("TEXT-EXTRACTION.md"));
    }

    private static SanguoDataset.RelationTime time(SanguoDataset dataset, String head, String relation, String tail) {
        SanguoDataset.Relation found = dataset.relations().stream()
                .filter(r -> r.head().equals(head) && r.relation().equals(relation) && r.tail().equals(tail))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no relation " + head + "-" + relation + "->" + tail));
        return dataset.timeOf(found);
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

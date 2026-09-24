package com.pengcheng.phoenixgraph.integration.sanguo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the whole 《三国演义》 query catalogue into one file that can be read and
 * copied from: the quick-copy lists (one query per line) followed by the four
 * sections with the Gremlin/Cypher form of every query and the answer the graph
 * gave.
 *
 * <p>The catalogue is the four lists of {@link SanguoRocksDbQueries}: the 20 typical
 * queries, their ten Cypher → Gremlin conversions, the 12 two/three-hop relation
 * queries and the 22 question-style queries. The answers come from a run against the
 * seeded graph, so the file shows what the data actually says.
 */
public final class SanguoQueryCookbook {

    /** The rendered file plus the two quick-copy lists. */
    public record Rendered(String markdown, String gremlinLines, String cypherLines) {
    }

    private SanguoQueryCookbook() {
    }

    /** Renders everything; {@code answers} maps a query string to its result summary. */
    public static Rendered render(Map<String, String> answers) {
        return render(answers, List.of(), null, null, null);
    }

    /**
     * Renders everything, plus the worked example of the {@code "A 和 B 是什么关系?"}
     * recipe (section 8) when {@code recipe} is not empty.
     */
    public static Rendered render(Map<String, String> answers,
                                  List<SanguoRocksDbQueries.RelationStep> recipe, String pairA, String pairB,
                                  String conclusion) {
        return new Rendered(markdown(answers, recipe, pairA, pairB, conclusion),
                gremlinLines(recipe, pairA, pairB), cypherLines(recipe, pairA, pairB));
    }

    /** The Gremlin queries of the whole catalogue, one per line, grouped by section. */
    public static String gremlinLines() {
        return gremlinLines(List.of(), null, null);
    }

    /** The Gremlin queries, plus the worked example of the relation recipe when given. */
    public static String gremlinLines(List<SanguoRocksDbQueries.RelationStep> recipe, String pairA, String pairB) {
        StringBuilder out = new StringBuilder();
        out.append("// ================================================================\n");
        out.append("// 《三国演义》知识图谱 · Gremlin 查询清单（一行一条，可直接复制）\n");
        out.append("// 图：sanguo-kg-rocksdb-edge（RocksDB-Edge，点 id = 实体名）。\n");
        out.append("// 换到 Aerospike 图 sanguo-kg 时把点 id 换成 名字_shard（如 曹操_3）。\n");
        out.append("// ================================================================\n");

        out.append("\n// ---- 1. 问答式（22 条） ----\n");
        for (SanguoRocksDbQueries.Question question : SanguoRocksDbQueries.QUESTIONS) {
            out.append(question.gremlin()).append("\n");
        }
        out.append("\n// ---- 2. 多跳（两跳 / 三跳，12 条） ----\n");
        for (SanguoRocksDbQueries.HopQuery hop : SanguoRocksDbQueries.MULTI_HOP) {
            out.append(hop.gremlin()).append("\n");
        }
        out.append("\n// ---- 3. 典型查询（10 条） ----\n");
        for (SanguoRocksDbQueries.Example example : SanguoRocksDbQueries.GREMLIN) {
            out.append(example.query()).append("\n");
        }
        out.append("\n// ---- 4. Cypher → Gremlin 的 Gremlin 侧（10 条） ----\n");
        for (SanguoRocksDbQueries.Conversion conversion : SanguoRocksDbQueries.CYPHER_TO_GREMLIN) {
            out.append(conversion.gremlin()).append("\n");
        }
        if (!recipe.isEmpty()) {
            out.append("\n// ---- 5. 「").append(pairA).append(" 和 ").append(pairB)
                    .append(" 是什么关系」解题模板（11 步） ----\n");
            for (SanguoRocksDbQueries.RelationStep step : recipe) {
                out.append(step.gremlin()).append("\n");
            }
        }
        return out.toString();
    }

    /** The Cypher queries of the whole catalogue, one per line, grouped by section. */
    public static String cypherLines() {
        return cypherLines(List.of(), null, null);
    }

    /** The Cypher queries, plus the worked example of the relation recipe when given. */
    public static String cypherLines(List<SanguoRocksDbQueries.RelationStep> recipe, String pairA, String pairB) {
        StringBuilder out = new StringBuilder();
        out.append("// ================================================================\n");
        out.append("// 《三国演义》知识图谱 · Cypher 查询清单（一行一条，可直接复制）\n");
        out.append("// 图：sanguo-kg-rocksdb-edge（点 id = 实体名）。\n");
        out.append("// 注意：本 Cypher 引擎要求 MATCH 至少有一个用 id 指定的起点；\n");
        out.append("//       「全图扫描 + 聚合」那几条只有 Gremlin 版本（见 .gremlin 清单）。\n");
        out.append("// ================================================================\n");

        out.append("\n// ---- 1. 问答式（有 Cypher 版本的） ----\n");
        for (SanguoRocksDbQueries.Question question : SanguoRocksDbQueries.QUESTIONS) {
            if (!question.cypher().isBlank()) {
                out.append(question.cypher()).append("\n");
            }
        }
        out.append("\n// ---- 2. 多跳（两跳 / 三跳，12 条） ----\n");
        for (SanguoRocksDbQueries.HopQuery hop : SanguoRocksDbQueries.MULTI_HOP) {
            out.append(hop.cypher()).append("\n");
        }
        out.append("\n// ---- 3. 典型查询（10 条） ----\n");
        for (SanguoRocksDbQueries.Example example : SanguoRocksDbQueries.CYPHER) {
            out.append(example.query()).append("\n");
        }
        out.append("\n// ---- 4. Cypher → Gremlin 的 Cypher 侧（10 条） ----\n");
        for (SanguoRocksDbQueries.Conversion conversion : SanguoRocksDbQueries.CYPHER_TO_GREMLIN) {
            out.append(conversion.cypher()).append("\n");
        }
        if (!recipe.isEmpty()) {
            out.append("\n// ---- 5. 「").append(pairA).append(" 和 ").append(pairB)
                    .append(" 是什么关系」解题模板（11 步） ----\n");
            for (SanguoRocksDbQueries.RelationStep step : recipe) {
                out.append(step.cypher()).append("\n");
            }
        }
        return out.toString();
    }

    /** The Markdown cookbook. */
    public static String markdown(Map<String, String> answers) {
        return markdown(answers, List.of(), null, null, null);
    }

    /** The Markdown cookbook, optionally with the relation recipe worked example. */
    public static String markdown(Map<String, String> answers,
                                  List<SanguoRocksDbQueries.RelationStep> recipe, String pairA, String pairB,
                                  String conclusion) {
        StringBuilder out = new StringBuilder();
        out.append("# 《三国演义》知识图谱查询手册\n\n");
        out.append("> 由 `SanguoRocksDbEdgeTest#writesTheQueryCookbook` 生成：**下面每一条查询都在这张图上实测跑过**，\n");
        out.append("> 「实测答案」一列是数据集的回答。图：`sanguo-kg-rocksdb-edge`（RocksDB-Edge）。\n");
        out.append("> 同一份数据还有 Aerospike 版本 `sanguo-kg`，点 id 是 `名字_shard`（例：`曹操_3`），\n");
        out.append("> 把查询里的实体名换成对应的分片 id 即可（分片 = `floorMod(relation.hashCode(),10)`）。\n\n");

        out.append("## 0. 约定\n\n");
        out.append("| | |\n|---|---|\n");
        out.append("| 图 | `sanguo-kg-rocksdb-edge`（RocksDB-Edge）/ `sanguo-kg`（Aerospike） |\n");
        out.append("| 点 | 一个类型 `Entity`：人物 / 势力 / 籍贯 / 官职 / 章回，`entityType` 区分 |\n");
        out.append("| 点 id | RocksDB 版 = 实体名本身（`曹操`、`魏`、`第49回`）；Aerospike 版 = `名字_shard` |\n");
        out.append("| 边 | `Entity_Entity`（有向，head --relation--> tail），边上带 `relation` / `xrelation`（逆关系）/ `relationLabel`（中文）/ `relationFamily`（关系家族） |\n");
        out.append("| 关系家族 | 亲属 / 婚姻 / 朋友 / 君臣 / 师承 / 归降 / 招募 / 效力 / 官职 / 籍贯 / 出场 / 其他 |\n");
        out.append("| 中文关系名 | 父亲 / 儿子 / 哥哥 / 弟弟 / 妻 / 夫 / 妾 / 义兄 / 义弟 / 主公 / 臣 / 师傅 / 徒弟 / 效力 / 官职 / 籍贯 / 出场 … |\n\n");

        out.append("**两种语言的写法差异**（本引擎实测）：\n\n");
        out.append("- Gremlin 没有 `as()` / `select()`：Cypher 一行里同时投影「边 + 邻居」的，Gremlin 要写两条 —— `elementMap()` 取边、`otherV().values('name')` 取邻居。\n");
        out.append("- `path('name')` 返回整条走链 `[起点, 边, 中间点, 边, 终点]`，其中边渲染为 `null`。\n");
        out.append("- Cypher 必须有一个用 `{id:...}` 指定的起点，**不能全图扫描**；需要扫描全 `Entity` 的聚合（势力排行、部下排行）只有 Gremlin：`g.V('Entity')` + `groupCount().by('name')`。\n");
        out.append("- `groupCount().by('k')` 返回一个 map；`groupCount()` 数的是「到达的对端」，所以 `g.V('Entity').inE().filter(relation.equals('lord')).otherV().groupCount().by('name')` 就是「每个主公有多少部下」。\n");
        out.append("- 方向：`A --lord--> B` 表示 A 是 B 的主公（B 是 A 的臣）；`A --serves--> 势力` 表示 A 效力该势力。所以「谁是 X 的部下」用 `outE(lord)`，「X 是谁的部下」用 `inE(lord)`。\n\n");

        out.append("## 1. 快速复制 · Gremlin\n\n```text\n");
        out.append(gremlinLines());
        out.append("```\n\n## 2. 快速复制 · Cypher\n\n```text\n");
        out.append(cypherLines());
        out.append("```\n\n");

        section(out, "## 3. 问答式查询（22 条）", answers,
                "| # | 问题 | Gremlin | Cypher | 实测答案 |\n|---|---|---|---|---|\n",
                () -> {
                    StringBuilder body = new StringBuilder();
                    for (SanguoRocksDbQueries.Question question : SanguoRocksDbQueries.QUESTIONS) {
                        body.append("| ").append(question.id()).append(" | ").append(question.question())
                                .append(" | `").append(question.gremlin()).append("` | ")
                                .append(question.cypher().isBlank() ? "—（需全图扫描，仅 Gremlin）"
                                        : "`" + question.cypher() + "`")
                                .append(" | ").append(question.answer()).append(" |\n");
                    }
                    return body.toString();
                });

        section(out, "## 4. 多跳关系查询（两跳 / 三跳，12 条）", answers,
                "| # | 用途 | Gremlin | Cypher | 实测 |\n|---|---|---|---|---|\n",
                () -> {
                    StringBuilder body = new StringBuilder();
                    for (int i = 0; i < SanguoRocksDbQueries.MULTI_HOP.size(); i++) {
                        SanguoRocksDbQueries.HopQuery hop = SanguoRocksDbQueries.MULTI_HOP.get(i);
                        body.append("| ").append(i + 1).append(" | ").append(hop.title()).append(" | `")
                                .append(hop.gremlin()).append("` | `").append(hop.cypher()).append("` | ")
                                .append(oneLine(answers.get(hop.gremlin()))).append(" |\n");
                    }
                    return body.toString();
                });

        section(out, "## 5. 典型查询（Gremlin 10 + Cypher 10）", answers,
                "| # | 语言 | 用途 | Query | 实测 |\n|---|---|---|---|---|\n",
                () -> {
                    StringBuilder body = new StringBuilder();
                    for (int i = 0; i < SanguoRocksDbQueries.GREMLIN.size(); i++) {
                        SanguoRocksDbQueries.Example example = SanguoRocksDbQueries.GREMLIN.get(i);
                        body.append("| G").append(i + 1).append(" | Gremlin | ").append(example.title())
                                .append(" | `").append(example.query()).append("` | ")
                                .append(oneLine(answers.get(example.query()))).append(" |\n");
                    }
                    for (int i = 0; i < SanguoRocksDbQueries.CYPHER.size(); i++) {
                        SanguoRocksDbQueries.Example example = SanguoRocksDbQueries.CYPHER.get(i);
                        body.append("| C").append(i + 1).append(" | Cypher | ").append(example.title())
                                .append(" | `").append(example.query()).append("` | ")
                                .append(oneLine(answers.get(example.query()))).append(" |\n");
                    }
                    return body.toString();
                });

        section(out, "## 6. Cypher → Gremlin 转换（10 条）", answers,
                "| # | 用途 | Cypher | Gremlin | 说明 |\n|---|---|---|---|---|\n",
                () -> {
                    StringBuilder body = new StringBuilder();
                    for (int i = 0; i < SanguoRocksDbQueries.CYPHER_TO_GREMLIN.size(); i++) {
                        SanguoRocksDbQueries.Conversion conversion = SanguoRocksDbQueries.CYPHER_TO_GREMLIN.get(i);
                        body.append("| ").append(i + 1).append(" | ").append(conversion.title()).append(" | `")
                                .append(conversion.cypher()).append("` | `").append(conversion.gremlin())
                                .append("` | ").append(conversion.note()).append(" |\n");
                    }
                    return body.toString();
                });

        out.append("## 7. 统计\n\n");
        out.append("| 分组 | 条数 |\n|---|---|\n");
        out.append("| 问答式（Q1–Q22） | ").append(SanguoRocksDbQueries.QUESTIONS.size()).append(" |\n");
        out.append("| 多跳（两跳/三跳） | ").append(SanguoRocksDbQueries.MULTI_HOP.size()).append(" |\n");
        out.append("| 典型查询 | ").append(SanguoRocksDbQueries.GREMLIN.size()
                + SanguoRocksDbQueries.CYPHER.size()).append(" |\n");
        out.append("| Cypher → Gremlin 转换 | ").append(SanguoRocksDbQueries.CYPHER_TO_GREMLIN.size()).append(" |\n");
        out.append("| 「A 和 B 是什么关系」解题模板 | ").append(SanguoRocksDbQueries.RELATION_RECIPE.size()).append(" 步 |\n");
        out.append("| **去重后 Gremlin 查询** | ").append(distinct(gremlinQueries())).append(" |\n");
        out.append("| **去重后 Cypher 查询** | ").append(distinct(cypherQueries())).append(" |\n");

        if (!recipe.isEmpty()) {
            out.append("\n## 8. 关系问答模板：「").append(pairA).append(" 和 ").append(pairB)                    .append(" 是什么关系？」\n\n");
            out.append("> **解题思路**：① 先看有没有直接边 → ②③ 看共同阵营 / 共同上司 → ④⑤ 看两人各自的关系画像 →\n");
            out.append("> ⑥⑦ 找共同邻居（中间人）→ ⑧ 看共同出场 → ⑨ 量距离 → ⑩ 看关键链路 → ⑪ 读出「身份关系」。\n");
            out.append("> 把下面查询里的 `").append(pairA).append("` / `").append(pairB)
                    .append("` 换成任意两个实体名（实体名就是点 id）就能复用这套模板。\n\n");
            out.append("| 步 | 这一步回答什么 | Gremlin | Cypher | 实测（").append(pairA).append(" / ").append(pairB)
                    .append("） |\n|---|---|---|---|---|\n");
            for (SanguoRocksDbQueries.RelationStep step : recipe) {
                out.append("| ").append(step.no()).append(" | ").append(step.purpose()).append(" | `")
                        .append(step.gremlin()).append("` | `").append(step.cypher()).append("` | ")
                        .append(oneLine(answers.get(step.gremlin()))).append(" |\n");
            }
            if (conclusion != null && !conclusion.isBlank()) {
                out.append("\n### 本例的结论\n\n").append(conclusion).append('\n');
            }
        }
        out.append(gapAnalysis(answers));
        return out.toString();
    }

    /**
     * Section 9: why some expected edges are not in the graph. Data becomes an edge
     * only by passing three layers — the ontology declares the relation type, the
     * upstream dataset states the fact, the parser turns the document into an edge —
     * and a missing edge is stuck at one of them.
     */
    private static String gapAnalysis(Map<String, String> answers) {
        StringBuilder out = new StringBuilder();
        out.append("\n## 9. 缺口诊断：为什么没有「关羽是刘备的臣 / 五虎将之首」「诸葛亮是刘备的军师 / 谋士」\n\n");
        out.append("### 9.1 诊断查询（先看图里有什么、缺什么）\n\n");
        out.append("| 看什么 | Gremlin | 实测 |\n|---|---|---|\n");
        for (SanguoRocksDbQueries.GapCheck check : SanguoRocksDbQueries.GAP_CHECKS) {
            out.append("| ").append(check.what()).append(" | `").append(check.gremlin()).append("` | ")
                    .append(oneLine(answers.get(check.gremlin()))).append(" |\n");
        }

        out.append("\n### 9.2 三层原因\n\n");
        out.append("一条事实要变成边，必须依次通过三层：\n\n");
        out.append("```\n");
        out.append("① ontology  theory.owl 里有没有这种关系类型？\n");
        out.append("       ↓ 有\n");
        out.append("② upstream  triples.csv / renwu_process_v1.csv 里写没写这条事实？\n");
        out.append("       ↓ 有，而且解析器能读到\n");
        out.append("③ parser    SanguoDataset 把它翻成边？\n");
        out.append("```\n\n");
        out.append("缺边必然卡在其中一层：\n\n");
        out.append("| 期望的边 | ① 本体 | ② 上游数据 | ③ 解析器 | 卡在哪层 |\n|---|---|---|---|---|\n");
        out.append("| 关羽 —**臣**→ 刘备 | ✅ 有 `minister`/`lord` | ❌ `triples.csv` 里刘备的 `lord` 部下有 **15 人**"
                + "（黄忠/魏延/马超/马良/赵云/诸葛亮/蒋琬/糜芳/糜竺/法正/沙摩柯/徐庶/庞统/孟获/姜维），**独缺关羽、张飞** | ❌ 文档派生不出人物–人物关系"
                + "（关羽文档只有 `效力=东汉\\|蜀\\|魏`，指向的是**势力**蜀） | **② 上游漏标** |\n");
        out.append("| 关羽 —**五虎将之首**→ 刘备 | ❌ 60 个关系里没有「五虎将」 | ❌ curated 里没有 | 🟡 **文档自由文本里有原话**"
                + "（见 9.3），但 `SanguoDataset` **只读 9 个结构化字段，不读 `历史`/`小说`** | **① 本体缺类型 + ③ 解析器不读文本** |\n");
        out.append("| 诸葛亮 —**军师/谋士**→ 刘备 | ❌ 没有「军师」「谋士」 | ❌ 诸葛亮自己的 bio 里**没有**这两个词"
                + "（只有「蜀汉建立，**拜为丞相**」） | — | **① + ②：这条通俗说法本数据集没有记载** |\n");
        out.append("| 诸葛亮 —**臣**→ 刘备 | ✅ | ✅ `刘备,诸葛亮,lord` | ✅ | **已在图里**：`g.V('诸葛亮','Entity').inE().filter(relation.equals('lord'))`，"
                + "或从刘备端 `outE(lord)`，边的 `xrelation=minister` |\n");
        out.append("| 关羽/张飞 与 赵云/马超/黄忠 **同列** | 🟡 有 `colleague`(同事)，但语义偏「同事」 | ❌ 全图只有 1 条 `colleague`（马谡→诸葛亮） | — | **① 语义不匹配 + ② 上游没标** |\n");

        out.append("\n### 9.3 文档原文证据（`renwu_process_v1.csv` 的自由文本）\n\n");
        out.append("| 人 | 字段 | 原文（截取） |\n|---|---|---|\n");
        out.append("| 关羽 | 小说 | …刘备平定益州后，**封关羽为五虎大将之首**，督荆州事… |\n");
        out.append("| 张飞 | 小说 | …后**封为蜀汉五虎大将**… |\n");
        out.append("| 马超 | 小说 / 历史 | **五虎上将之一**… / …刘备为汉中王，升为左将军，**五虎上将**… |\n");
        out.append("| 诸葛亮 | 历史 | …蜀汉建立，**拜为丞相**…（全文无「军师」「谋士」） |\n");
        out.append("| 徐庶 | 小说 | …投效与刘备，**被拜为军师**…（数据集里「军师」指的是徐庶） |\n");
        out.append("| 庞统 | 官职 | **军师中郎将**（另一个人物的「军师」） |\n\n");
        out.append("> 统计：`renwu_process_v1.csv` 里 `五虎` 出现 **4** 次、`军师` **11** 次、`谋士` **5** 次——"
                + "全部在**自由文本**里；而 `SanguoDataset` 只读 `父亲/母亲/兄弟/配偶/孩子/官职/主效/效力/章节列表` 这 9 个结构化字段。\n");
        out.append("> 换句话说：**「五虎大将之首」这句话就在数据里，只是没被抽成边。**\n");

        out.append("\n### 9.4 怎么补（三种方案，代价从小到大）\n\n");
        out.append("#### ✅ 方案 B（已落地）——把「五虎上将」当成一个**职位**，用已有的 `holds`(官职) 表达\n\n");
        out.append("写进了本地补充文件 [`triples-extra.csv`](graph-integration-test/src/test/resources/kg/sanguo/triples-extra.csv)：\n\n");
        out.append("```csv\n");
        out.append("\"关羽\",\"五虎上将\",\"holds\",\"官职\"\n");
        out.append("\"关羽\",\"五虎上将之首\",\"holds\",\"官职\"\n");
        out.append("\"张飞\",\"五虎上将\",\"holds\",\"官职\"\n");
        out.append("\"赵云\",\"五虎上将\",\"holds\",\"官职\"\n");
        out.append("\"马超\",\"五虎上将\",\"holds\",\"官职\"\n");
        out.append("\"黄忠\",\"五虎上将\",\"holds\",\"官职\"\n");
        out.append("```\n\n");
        out.append("同时补上了方案 A 的「臣」部分（`lord` ↔ `minister` 是本体互逆关系，只写一端）：\n\n");
        out.append("```csv\n\"刘备\",\"关羽\",\"lord\",\"主公\"\n\"刘备\",\"张飞\",\"lord\",\"主公\"\n```\n\n");
        out.append("**为什么方案 B 比方案 A 好**：如果改用 `colleague`(同事) 表示五虎将，「**之首**」这个排序就丢了；\n");
        out.append("当成职位则两个问题都能查。实测：\n\n");
        out.append("| 问题 | 查询 | 答案 |\n|---|---|---|\n");
        out.append("| 谁是五虎将 | `g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV().values('name')`"
                + " | 关羽、张飞、赵云、马超、黄忠 |\n");
        out.append("| 谁是五虎将之首 | `g.V('五虎上将之首','Entity').inE().filter(relation.equals('holds')).otherV().values('name')`"
                + " | 关羽 |\n");
        out.append("| 关羽是不是刘备的臣 | `g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().dedup().where(hasId('关羽')).count()`"
                + " | 1（关羽的 `minister` 逆边也已自动物化） |\n");
        out.append("| 关羽与马超是什么关系 | `g.V('关羽','Entity').both('Entity_Entity').hasId('马超')` + 共同职位/共同主公/共同章回"
                + " | 同列五虎上将、同为刘备的臣、共同出场 62 回（详见本文 §8 的模板） |\n\n");
        out.append("#### ⬜ 方案 A（只落地了「臣」部分）\n\n");
        out.append("还差 `colleague`(同事) 那几条（`关羽→张飞/赵云/马超/黄忠`）。**建议不加**：方案 B 的 `holds 五虎上将`\n");
        out.append("已经能回答「同列」，而 `colleague` 会把「同列武将」和「一般同事」混在一起。\n\n");
        out.append("#### ⬜ 方案 C（未做，需要 NLP）\n\n");
        out.append("让 `SanguoDataset` 也扫 `历史`/`小说` 两列，用词典/正则抽「五虎大将之首」「军师」「谋士」等模式。\n");
        out.append("代价是引入 NLP，需要校验兜底（可用本项目的 `SanguoRdfValidator` + `rdfs:domain`/`range` 检查）。\n\n");
        out.append("> 落地后的规模（由本次运行实测）：数据集 **2,298 条源关系 / 1,102 个实体**，\n");
        out.append("> 按本体物化后 **2,447 条边**，ABox **4,946 条 RDF 三元组**；Aerospike 1,637 条点记录，RocksDB-Edge 1,102 点 / 2,298 边。\n");
        out.append("> 补边必须**重新播种**；Aerospike 的边 bins 是 CDT map，旧数据不会自动消失 ——\n");
        out.append("> 另外把边 id 从「下标」改成了「由三元组本身导出（`head|relation|tail`）」，\n");
        out.append("> 否则插一条三元组会让后面所有边 id 平移、旧边留在 bins 里变成重复边。\n");
        return out.toString();
    }

    private static void section(StringBuilder out, String title, Map<String, String> answers, String header,
                                java.util.function.Supplier<String> body) {
        out.append(title).append("\n\n").append(header).append(body.get()).append('\n');
    }

    private static List<String> gremlinQueries() {
        List<String> all = new java.util.ArrayList<>();
        SanguoRocksDbQueries.QUESTIONS.forEach(q -> all.add(q.gremlin()));
        SanguoRocksDbQueries.MULTI_HOP.forEach(h -> all.add(h.gremlin()));
        SanguoRocksDbQueries.GREMLIN.forEach(e -> all.add(e.query()));
        SanguoRocksDbQueries.CYPHER_TO_GREMLIN.forEach(c -> all.add(c.gremlin()));
        return all;
    }

    private static List<String> cypherQueries() {
        List<String> all = new java.util.ArrayList<>();
        SanguoRocksDbQueries.QUESTIONS.forEach(q -> {
            if (!q.cypher().isBlank()) {
                all.add(q.cypher());
            }
        });
        SanguoRocksDbQueries.MULTI_HOP.forEach(h -> all.add(h.cypher()));
        SanguoRocksDbQueries.CYPHER.forEach(e -> all.add(e.query()));
        SanguoRocksDbQueries.CYPHER_TO_GREMLIN.forEach(c -> all.add(c.cypher()));
        return all;
    }

    private static int distinct(List<String> queries) {
        Set<String> unique = new LinkedHashSet<>(queries);
        return unique.size();
    }

    /** Keeps a table cell on one line. */
    private static String oneLine(String text) {
        if (text == null || text.isBlank()) {
            return "—";
        }
        String flat = text.replace("\n", " ").replace("|", "\\|").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }
}

package com.pengcheng.phoenixgraph.integration.sanguo;

import java.util.List;

/**
 * The typical queries the 《三国演义》 knowledge graph is asked, written for the
 * <b>RocksDB-Edge</b> instance: 10 Gremlin + 10 Cypher = 20.
 *
 * <p>They differ from {@link SanguoExampleQueries} in exactly the way the two
 * storages differ. The Aerospike graph shards a person over ten records, so a
 * query has to seed all of them ({@code 曹操_2 … 曹操_9}); here the vertex id is the
 * plain name, so {@code g.V('曹操','Entity')} is the whole person. Everything else —
 * the relation filter, the {@code relation || xrelation} idiom, the 中文 relation
 * label, the relation family, the two-hop walk — is the same question.
 *
 * <p>The categories are the ones a knowledge-graph UI needs: point lookup, one
 * hop, relation filter, direction / inverse, two hops, aggregation (family, degree,
 * members), a cross-type walk (person → faction), "what relates A and B", and the
 * subgraph behind a visualisation.
 */
public final class SanguoRocksDbQueries {

    /** One example query. */
    public record Example(String title, String titleEn, String language, String query) {
    }

    /** The 10 Gremlin queries. */
    public static final List<Example> GREMLIN = List.of(
            new Example("① 实体属性（点查）", "Entity with its attributes (point lookup)", "gremlin",
                    "g.V('曹操','Entity').values('name','entityType','gender','courtesyName')"),
            new Example("② 一个人的全部关系（一条记录，不再分片）", "Every relation of one person, in one record",
                    "gremlin", "g.V('曹操','Entity').bothE()"),
            new Example("③ 邻居去重（社交圈）", "Distinct neighbours (the person's circle)", "gremlin",
                    "g.V('曹操','Entity').bothE().otherV().dedup()"),
            new Example("④ 按关系过滤：曹操的主公关系", "Filter by relation: Cao Cao as lord", "gremlin",
                    "g.V('曹操','Entity').outE().filter(relation.equals('lord'))"),
            new Example("⑤ 一跳：曹操手下的将领", "One hop: the generals under Cao Cao", "gremlin",
                    "g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().dedup()"),
            new Example("⑥ 反向惯用法：刘备的臣属", "Inverse idiom: who serves Liu Bei", "gremlin",
                    "g.V('刘备','Entity').bothE()"
                            + ".filter(relation.equals('lord') || xrelation.equals('lord')).otherV().dedup()"),
            new Example("⑦ 师承：姜维的师傅", "Mentorship: Jiang Wei's master", "gremlin",
                    "g.V('姜维','Entity').bothE()"
                            + ".filter(relation.equals('master') || xrelation.equals('master')).otherV().dedup()"),
            new Example("⑧ 按关系家族聚合", "Relations grouped by family", "gremlin",
                    "g.V('曹操','Entity').bothE().groupCount('relationFamily')"),
            new Example("⑨ 两跳：曹嵩 → 曹操 → 曹昂（祖孙）", "Two hops: grandfather to grandson", "gremlin",
                    "g.V('曹嵩','Entity').outE().filter(relation.equals('father')).otherV()"
                            + ".outE().filter(relation.equals('father')).otherV().dedup()"),
            new Example("⑩ 势力成员数：魏", "Faction size: how many serve Wei", "gremlin",
                    "g.V('魏','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()"));

    /** The 10 Cypher queries. */
    public static final List<Example> CYPHER = List.of(
            new Example("① 人物本体（属性）", "Person vertex with its attributes", "cypher",
                    "MATCH (e:Entity {id:'曹操'}) RETURN e.name, e.entityType, e.gender, e.courtesyName"),
            new Example("② 出边关系与中文关系名", "Outgoing relations with the Chinese label", "cypher",
                    "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) "
                            + "RETURN r.relation, r.relationLabel, x.name LIMIT 20"),
            new Example("③ 按关系过滤：曹操的主公关系", "Filter by relation: Cao Cao as lord", "cypher",
                    "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' "
                            + "RETURN x.name AS subject, r.relationLabel AS relation LIMIT 20"),
            new Example("④ 反向：马谡的主公（用 xrelation）", "Inverse: Ma Su's lord (via xrelation)", "cypher",
                    "MATCH (e:Entity {id:'马谡'})-[r:Entity_Entity]->(x:Entity) WHERE r.xrelation = 'lord' "
                            + "RETURN x.name AS lord, r.relationLabel AS relation"),
            new Example("⑤ 按关系家族聚合", "Aggregate by relation family", "cypher",
                    "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) "
                            + "RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC"),
            new Example("⑥ 两跳邻居：曹嵩 → 曹操 → X", "Two hops from Cao Song through Cao Cao", "cypher",
                    "MATCH (a:Entity {id:'曹嵩'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) "
                            + "RETURN DISTINCT b.name AS middle, c.name AS twoHop LIMIT 20"),
            new Example("⑦ 出场章回（诸葛亮）", "Chapters Zhuge Liang appears in", "cypher",
                    "MATCH (p:Entity {id:'诸葛亮'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation = 'appearsIn' "
                            + "RETURN c.name LIMIT 25"),
            new Example("⑧ 谁效力魏（跨类型：人物 → 势力）", "Who serves Wei (person -> faction)", "cypher",
                    "MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation = 'serves' "
                            + "RETURN p.name, p.entityType LIMIT 20"),
            new Example("⑨ 两个实体之间有什么关系", "What relates two entities", "cypher",
                    "MATCH (a:Entity {id:'刘备'})-[r:Entity_Entity]-(b:Entity {id:'诸葛亮'}) "
                            + "RETURN r.relation AS relation, r.relationLabel AS label"),
            new Example("⑩ 曹操的子女（亲属家族）", "Cao Cao's children (kindship family)", "cypher",
                    "MATCH (e:Entity {id:'曹操'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily = '亲属' "
                            + "RETURN r.relationLabel AS relation, x.name LIMIT 20"));

    private SanguoRocksDbQueries() {
    }

    /** All 20 queries, Gremlin first. */
    public static List<Example> all() {
        return java.util.stream.Stream.concat(GREMLIN.stream(), CYPHER.stream()).toList();
    }

    // ------------------------------------------------------------------ //
    // 1) The ten Cypher statements above, translated to Gremlin.        //
    // ------------------------------------------------------------------ //

    /** A Cypher statement and its Gremlin equivalent (plus the caveat, where there is one). */
    public record Conversion(String title, String cypher, String gremlin, String note) {
    }

    /**
     * The ten Cypher statements of {@link #CYPHER} written as Gremlin.
     *
     * <p>Two idioms differ between the languages in a way worth knowing:
     * <ul>
     *   <li>Gremlin has no {@code as()} / {@code select()} in this engine, so a Cypher row
     *   that projects an edge <em>and</em> its neighbour ({@code RETURN r.relation, x.name})
     *   becomes two traversals: {@code elementMap()} for the edge, {@code otherV().values('name')}
     *   for the neighbour;</li>
     *   <li>{@code path('name')} returns the whole walk, and the edges in it render as
     *   {@code null} — that is where Cypher's multi-hop {@code RETURN b.name, c.name}
     *   gets its two columns from.</li>
     * </ul>
     */
    public static final List<Conversion> CYPHER_TO_GREMLIN = List.of(
            new Conversion("① 点查：实体属性",
                    "MATCH (e:Entity {id:'曹操'}) RETURN e.name, e.entityType, e.gender, e.courtesyName",
                    "g.V('曹操','Entity').values('name','entityType','gender','courtesyName')",
                    "一一对应"),
            new Conversion("② 出边关系 + 中文关系名",
                    "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) "
                            + "RETURN r.relation, r.relationLabel, x.name LIMIT 20",
                    "g.V('曹操','Entity').outE('Entity_Entity').limit(20).elementMap()",
                    "Gremlin 无 as/select：elementMap() 返回边（含 relation/relationLabel）；邻居名另查 "
                            + "...otherV().values('name')"),
            new Conversion("③ 按关系过滤 + 邻居名",
                    "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' "
                            + "RETURN x.name AS subject, r.relationLabel AS relation LIMIT 20",
                    "g.V('曹操','Entity').outE('Entity_Entity').filter(relation.equals('lord'))"
                            + ".limit(20).otherV().values('name')",
                    "filter(relation.equals(R)) 会被下推到存储；去掉 limit(20) 就是全部 22 条"),
            new Conversion("④ 反向关系（xrelation）",
                    "MATCH (e:Entity {id:'马谡'})-[r:Entity_Entity]->(x:Entity) WHERE r.xrelation = 'lord' "
                            + "RETURN x.name AS lord, r.relationLabel AS relation",
                    "g.V('马谡','Entity').outE('Entity_Entity').filter(xrelation.equals('lord'))"
                            + ".otherV().values('name')",
                    "xrelation = 逆关系名，与 relation 一样可下推"),
            new Conversion("⑤ 按关系家族聚合",
                    "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) "
                            + "RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC",
                    "g.V('曹操','Entity').outE('Entity_Entity').groupCount().by('relationFamily')",
                    "Gremlin 返回一个 map（全部家族），Cypher 返回按 n 排序的行"),
            new Conversion("⑥ 两跳 + 中间点",
                    "MATCH (a:Entity {id:'曹嵩'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) "
                            + "RETURN DISTINCT b.name AS middle, c.name AS twoHop LIMIT 20",
                    "g.V('曹嵩','Entity').out('Entity_Entity').out('Entity_Entity').dedup()"
                            + ".path('name').limit(20)",
                    "path('name') 给整条走链 [起点, 边, 中间点, 边, 终点]，边渲染为 null"),
            new Conversion("⑦ 出场章回",
                    "MATCH (p:Entity {id:'诸葛亮'})-[r:Entity_Entity]->(c:Entity) "
                            + "WHERE r.relation = 'appearsIn' RETURN c.name LIMIT 25",
                    "g.V('诸葛亮','Entity').outE('Entity_Entity').filter(relation.equals('appearsIn'))"
                            + ".limit(25).otherV().values('name')",
                    "一一对应"),
            new Conversion("⑧ 入边（谁效力魏）",
                    "MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation = 'serves' "
                            + "RETURN p.name, p.entityType LIMIT 20",
                    "g.V('魏','Entity').inE('Entity_Entity').filter(relation.equals('serves'))"
                            + ".limit(20).otherV().valueMap('name','entityType')",
                    "方向的写法：Cypher 用箭头指向势力，Gremlin 从势力端起 inE()"),
            new Conversion("⑨ 两个实体之间有什么关系",
                    "MATCH (a:Entity {id:'刘备'})-[r:Entity_Entity]-(b:Entity {id:'诸葛亮'}) "
                            + "RETURN r.relation AS relation, r.relationLabel AS label",
                    "g.V('刘备','Entity').bothE('Entity_Entity').where(otherV().hasId('诸葛亮'))"
                            + ".values('relation','relationLabel')",
                    "Cypher 把端点写在模式里；Gremlin 用 where(child) 把'对端是谁'当过滤条件"),
            new Conversion("⑩ 亲属家族入边 + 中文标签",
                    "MATCH (e:Entity {id:'曹操'})<-[r:Entity_Entity]-(x:Entity) "
                            + "WHERE r.relationFamily = '亲属' RETURN r.relationLabel AS relation, x.name LIMIT 20",
                    "g.V('曹操','Entity').inE('Entity_Entity').filter(relationFamily.equals('亲属'))"
                            + ".limit(20).elementMap()",
                    "elementMap() 带出 relationLabel；邻居名用 ...otherV().values('name')"));

    // ------------------------------------------------------------------ //
    // 2) Multi-hop (2 / 3 hops) relation queries.                        //
    // ------------------------------------------------------------------ //

    /** A multi-hop query, written in both languages. */
    public record HopQuery(String title, String titleEn, String gremlin, String cypher) {
    }

    /**
     * Two- and three-hop queries that ask about <em>relations</em>, not about one record:
     * relation chains, common neighbours, same-faction colleagues, co-appearance, path
     * counts (the strength of a two-hop link) and three-hop cycles.
     */
    public static final List<HopQuery> MULTI_HOP = List.of(
            new HopQuery("两跳 · 二度人脉：刘备的两跳邻居有多少", "2 hops: how many entities within two hops of Liu Bei",
                    "g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').dedup().count()",
                    "MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity) "
                            + "RETURN count(DISTINCT c.name) AS twoHopNeighbours"),
            new HopQuery("两跳 · 指定关系链：刘备 —主公→ 诸葛亮 —师傅→ 姜维",
                    "2 hops: a named relation chain",
                    "g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV()"
                            + ".outE().filter(relation.equals('master')).otherV().dedup().values('name')",
                    "MATCH (a:Entity {id:'刘备'})-[r1:Entity_Entity]->(b:Entity)-[r2:Entity_Entity]->(c:Entity) "
                            + "WHERE r1.relation='lord' AND r2.relation='master' RETURN b.name AS mid, c.name AS end"),
            new HopQuery("两跳 · 共同邻居：刘备和曹操共同认识谁", "2 hops: a common neighbour",
                    "g.V('刘备','Entity').both('Entity_Entity').dedup()"
                            + ".where(both('Entity_Entity').hasId('曹操')).values('name')",
                    "MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(m:Entity)-[:Entity_Entity]-(b:Entity {id:'曹操'}) "
                            + "RETURN DISTINCT m.name"),
            new HopQuery("两跳 · 同势力同僚：徐庶的同僚有多少（经势力两跳）",
                    "2 hops: colleagues in the same faction",
                    "g.V('徐庶','Entity').outE().filter(relation.equals('serves')).otherV()"
                            + ".inE().filter(relation.equals('serves')).otherV().dedup().count()",
                    "MATCH (a:Entity {id:'徐庶'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity) "
                            + "WHERE r1.relation='serves' AND r2.relation='serves' "
                            + "RETURN count(DISTINCT b.name) AS colleagues"),
            new HopQuery("两跳 · 同回出场：诸葛亮和多少人出现在同一章回",
                    "2 hops: co-appearance in a chapter",
                    "g.V('诸葛亮','Entity').outE().filter(relation.equals('appearsIn')).otherV()"
                            + ".inE().filter(relation.equals('appearsIn')).otherV().dedup().count()",
                    "MATCH (a:Entity {id:'诸葛亮'})-[r1:Entity_Entity]->(ch:Entity)<-[r2:Entity_Entity]-(b:Entity) "
                            + "WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' "
                            + "RETURN count(DISTINCT b.name) AS sameChapter"),
            new HopQuery("两跳 · 路径强度：刘备两跳能到谁、各有几条路（Top 10）",
                    "2 hops: how many paths reach each entity (top 10)",
                    "g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').groupCount().by('name')",
                    "MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity) "
                            + "RETURN c.name AS name, count(*) AS paths ORDER BY paths DESC LIMIT 10"),
            new HopQuery("两跳 · 可达性：刘备两跳能不能到曹操（几条路）",
                    "2 hops: is Cao Cao reachable in two hops",
                    "g.V('刘备','Entity').repeat(both('Entity_Entity')).times(2).hasId('曹操').count()",
                    "MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity {id:'曹操'}) "
                            + "RETURN count(*) AS paths"),
            new HopQuery("三跳 · 路径数：刘备三跳到曹操有多少条路",
                    "3 hops: how many three-hop walks reach Cao Cao",
                    "g.V('刘备','Entity').repeat(both('Entity_Entity')).times(3).hasId('曹操').count()",
                    "MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity)"
                            + "-[:Entity_Entity]-(d:Entity {id:'曹操'}) RETURN count(*) AS paths"),
            new HopQuery("三跳 · 三跳环：刘备出发又绕回自己的三跳环",
                    "3 hops: a three-hop cycle back to the start",
                    "g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').both('Entity_Entity')"
                            + ".cyclicPath().path('name').limit(5)",
                    "MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity)"
                            + "-[:Entity_Entity]-(a) RETURN DISTINCT b.name AS b, c.name AS c LIMIT 5"),
            new HopQuery("三跳 · 跨势力：从魏出发三跳到蜀（路数）", "3 hops: from one faction to another",
                    "g.V('魏','Entity').in('Entity_Entity').out('Entity_Entity').out('Entity_Entity')"
                            + ".hasId('蜀').count()",
                    "MATCH (a:Entity {id:'魏'})<-[:Entity_Entity]-(p:Entity)-[:Entity_Entity]->(q:Entity)"
                            + "-[:Entity_Entity]->(r:Entity {id:'蜀'}) RETURN count(*) AS paths"),
            new HopQuery("三跳 · 祖孙三代：司马懿 → 儿子 → 孙子（司马炎）",
                    "3 hops: three generations",
                    "g.V('司马懿','Entity').inE().filter(relation.equals('son')).otherV()"
                            + ".inE().filter(relation.equals('son')).otherV().dedup().values('name')",
                    "MATCH (a:Entity {id:'司马懿'})<-[r1:Entity_Entity]-(s:Entity)<-[r2:Entity_Entity]-(g:Entity) "
                            + "WHERE r1.relation='son' AND r2.relation='son' RETURN DISTINCT g.name"),
            new HopQuery("三跳 · 混合关系链：曹操 —主公→ 司马懿 ←儿子← 司马昭 ←儿子← 司马炎",
                    "3 hops: a mixed relation chain",
                    "g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().hasId('司马懿')"
                            + ".inE().filter(relation.equals('son')).otherV().hasId('司马昭')"
                            + ".inE().filter(relation.equals('son')).otherV().dedup().values('name')",
                    "MATCH (a:Entity {id:'曹操'})-[:Entity_Entity]->(b:Entity {id:'司马懿'})"
                            + "<-[:Entity_Entity]-(s:Entity {id:'司马昭'})<-[:Entity_Entity]-(g:Entity) "
                            + "RETURN DISTINCT g.name"));

    // ------------------------------------------------------------------ //
    // 3) Question-style queries (知识图谱问答)                          //
    // ------------------------------------------------------------------ //

    /**
     * A natural-language question with the Gremlin that answers it, the Cypher
     * equivalent when this engine can express it ({@code ""} otherwise) and the
     * answer the data gives.
     *
     * <p>Why some questions have no Cypher: this Cypher engine requires every
     * {@code MATCH} to start from a vertex the query names by id, so a question that
     * has to scan the whole {@code Entity} type ("谁的手下最多", "哪个势力人最多") is
     * Gremlin-only — {@code g.V('Entity')} is a supported type scan, and
     * {@code groupCount().by('name')} then groups by the far endpoint of the hop.
     */
    public record Question(String id, String question, String questionEn, String gremlin, String cypher,
                           String answer, boolean expectRows) {

        /** A question whose answer is "yes, these rows" (the default). */
        public Question(String id, String question, String questionEn, String gremlin, String cypher,
                        String answer) {
            this(id, question, questionEn, gremlin, cypher, answer, true);
        }
    }

    /** 22 questions a 《三国演义》 knowledge graph is asked in practice. */
    public static final List<Question> QUESTIONS = List.of(
            new Question("Q1", "关羽和曹操是什么关系？", "How are Guan Yu and Cao Cao related?",
                    "g.V('关羽','Entity').outE().filter(relation.equals('serves')).otherV().dedup()"
                            + ".where(inE().filter(relation.equals('serves')).otherV().hasId('曹操')).values('name')",
                    "MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) "
                            + "WHERE r1.relation='serves' AND r2.relation='serves' RETURN DISTINCT f.name",
                    "共同势力 = 魏、东汉（史实：降汉不降曹）。两人没有直接边"),
            new Question("Q2", "关羽和曹操有直接关系吗？", "Is there a direct edge between them?",
                    "g.V('关羽','Entity').bothE('Entity_Entity').where(otherV().hasId('曹操')).count()",
                    "MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]-(b:Entity {id:'曹操'}) RETURN r.relation",
                    "没有（0 条边）；他们通过共同势力 / 共同章回相连", false),
            new Question("Q3", "关羽和曹操共同出场过多少回？", "In how many chapters do both appear?",
                    "g.V('关羽','Entity').outE().filter(relation.equals('appearsIn')).otherV().dedup()"
                            + ".where(inE().filter(relation.equals('appearsIn')).otherV().hasId('曹操')).count()",
                    "MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(ch:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) "
                            + "WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' RETURN count(DISTINCT ch.name) AS chapters",
                    "58 回"),
            new Question("Q4", "刘备和曹操是什么关系？", "How are Liu Bei and Cao Cao related?",
                    "g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().dedup()"
                            + ".where(inE().filter(relation.equals('lord')).otherV().hasId('曹操')).values('name')",
                    "MATCH (a:Entity {id:'刘备'})-[r1:Entity_Entity]->(p:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) "
                            + "WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT p.name",
                    "没有直接边；共同的部下是 徐庶（先事刘备，后归曹操）"),
            new Question("Q5", "刘备和诸葛亮是什么关系？", "How are Liu Bei and Zhuge Liang related?",
                    "g.V('诸葛亮','Entity').inE().filter(relation.equals('lord')).otherV().values('name')",
                    "MATCH (e:Entity {id:'诸葛亮'})<-[r:Entity_Entity]-(l:Entity) WHERE r.relation='lord' "
                            + "RETURN l.name AS lord, r.relationLabel AS relation",
                    "刘备是诸葛亮的主公（lord / 主公）"),
            new Question("Q6", "关羽和张飞是什么关系？", "How are Guan Yu and Zhang Fei related?",
                    "g.V('关羽','Entity').outE().filter(relation.equals('elder_sworn_brother')).otherV().values('name')",
                    "MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]->(b:Entity {id:'张飞'}) "
                            + "RETURN r.relation AS relation, r.relationLabel AS label",
                    "关羽是张飞的义兄（elder_sworn_brother / 义兄），张飞是关羽的义弟"),
            new Question("Q7", "桃园三兄弟是谁？", "Who are the three sworn brothers?",
                    "g.V('刘备','Entity').inE().filter(relation.equals('younger_sworn_brother')).otherV().dedup().values('name')",
                    "MATCH (e:Entity {id:'刘备'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relation='younger_sworn_brother' "
                            + "RETURN x.name, r.relationLabel AS relation",
                    "刘备、关羽、张飞；关羽和张飞都是刘备的义弟"),
            new Question("Q8", "三国三大主公分别是谁？", "Who are the three lords of the Three Kingdoms?",
                    "g.V('Entity').inE().filter(relation.equals('lord')).otherV().groupCount().by('name')",
                    "",
                    "按部下数排出：曹操=22、孙权=16、刘备=15（其后吕布=3、刘表=2）—— 即魏/吴/蜀三国主公"),
            new Question("Q9", "刘备、曹操之外的第三国主公是谁？", "Who is the third state's lord?",
                    "g.V('吴','Entity').inE().filter(relation.equals('serves')).otherV().dedup()"
                            + ".where(outE().filter(relation.equals('lord'))).values('name')",
                    "MATCH (p:Entity)-[r1:Entity_Entity]->(f:Entity {id:'吴'}), (p)-[r2:Entity_Entity]->(x:Entity) "
                            + "WHERE r1.relation='serves' AND r2.relation='lord' RETURN DISTINCT p.name AS lord",
                    "孙权（吴）。另：魏的主公=曹操；蜀用 serves 查不到（刘备没有文档，没有 serves 边），"
                            + "可用「蜀国成员的主公」推导出刘备"),
            new Question("Q10", "魏国阵营有多少人？", "How many people serve Wei?",
                    "g.V('魏','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()",
                    "MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation='serves' "
                            + "RETURN count(*) AS members",
                    "92 人"),
            new Question("Q11", "哪个势力的人最多？", "Which faction has the most members?",
                    "g.V('Entity').outE().filter(relation.equals('serves')).otherV().groupCount().by('name')",
                    "",
                    "魏=92、东汉=77、吴=44、西晋=38、蜀=35、少数民族=13、在野=13 …"),
            new Question("Q12", "刘备的手下有谁是从曹操那边过来的？", "Who under Liu Bei came over from Cao Cao?",
                    "g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().dedup()"
                            + ".where(inE().filter(relation.equals('lord')).otherV().hasId('刘备')).values('name')",
                    "MATCH (a:Entity {id:'曹操'})-[r1:Entity_Entity]->(p:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'刘备'}) "
                            + "WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT p.name AS defector",
                    "徐庶（先为刘备的臣，后归曹操）"),
            new Question("Q13", "谁换过主公（有 2 个以上主公）？", "Who served more than one lord?",
                    "g.V('Entity').outE().filter(relation.equals('lord')).otherV().groupCount().by('name')",
                    "",
                    "张辽=2（吕布→曹操）、徐庶=2（刘备→曹操），其余 1"),
            new Question("Q14", "张辽是从谁那里投奔曹操的？", "Whom did Zhang Liao leave for Cao Cao?",
                    "g.V('张辽','Entity').inE().filter(relation.equals('lord')).otherV().values('name')",
                    "MATCH (l:Entity)-[r:Entity_Entity]->(x:Entity {id:'张辽'}) WHERE r.relation='lord' "
                            + "RETURN l.name AS lord",
                    "吕布（先）与曹操（后）—— 从吕布处归降曹操"),
            new Question("Q15", "徐庶的两任主公是谁？", "Who were Xu Shu's two lords?",
                    "g.V('徐庶','Entity').inE().filter(relation.equals('lord')).otherV().values('name')",
                    "MATCH (l:Entity)-[r:Entity_Entity]->(x:Entity {id:'徐庶'}) WHERE r.relation='lord' "
                            + "RETURN l.name AS lord, r.relationLabel AS relation",
                    "刘备、曹操"),
            new Question("Q16", "谁效力过的势力最多？", "Who served the most factions?",
                    "g.V('Entity').inE().filter(relation.equals('serves')).otherV().groupCount().by('name')",
                    "",
                    "马超=4（东汉、马腾势力、蜀、张鲁）；甘宁/黄忠/张辽/袁绍/吕布/卢植/关羽=3"),
            new Question("Q17", "曹操的同族有谁？", "Who is in Cao Cao's clan?",
                    "g.V('曹操','Entity').bothE().filter(relation.equals('same_clan')).otherV().values('name')",
                    "MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]-(x:Entity) WHERE r.relation='same_clan' "
                            + "RETURN DISTINCT x.name",
                    "夏侯渊、夏侯淳、曹仁"),
            new Question("Q18", "诸葛亮的亲属都有谁？", "Who are Zhuge Liang's relatives?",
                    "g.V('诸葛亮','Entity').bothE().filter(relationFamily.equals('亲属')).otherV().dedup().values('name')",
                    "MATCH (e:Entity {id:'诸葛亮'})-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily='亲属' "
                            + "RETURN DISTINCT x.name",
                    "诸葛芸、诸葛瞻（子）、诸葛瑾（兄）、诸葛珪（父）、诸葛均（弟）、章氏（母）"),
            new Question("Q19", "诸葛亮的哥哥在哪个国家？", "Which state is Zhuge Liang's brother in?",
                    "g.V('诸葛亮','Entity').bothE().filter(relationFamily.equals('亲属')).otherV().dedup()"
                            + ".where(inE().filter(relation.equals('lord')).otherV().hasId('孙权')).values('name')",
                    "MATCH (e:Entity {id:'诸葛亮'})-[r:Entity_Entity]-(x:Entity)<-[:Entity_Entity]-(l:Entity {id:'孙权'}) "
                            + "WHERE r.relationFamily='亲属' RETURN DISTINCT x.name",
                    "诸葛瑾，在吴国（孙权的臣）"),
            new Question("Q20", "谁出场章回最多？", "Who appears in the most chapters?",
                    "g.V('Entity').inE().filter(relation.equals('appearsIn')).otherV().groupCount().by('name')",
                    "",
                    "曹操=93、诸葛亮=78、关羽=65、张飞=63、孙权=62、赵云=51 …"),
            new Question("Q21", "哪一回出场的人最多？", "Which chapter has the most characters?",
                    "g.V('Entity').outE().filter(relation.equals('appearsIn')).otherV().groupCount().by('name')",
                    "",
                    "第56回=18、第60回=15、第50回=14、第48回=14 …"),
            new Question("Q22", "诸葛亮和司马懿相隔多远？", "How far apart are Zhuge Liang and Sima Yi?",
                    "g.V('诸葛亮','Entity').repeat(both('Entity_Entity')).times(2).hasId('司马懿').count()",
                    "MATCH (a:Entity {id:'诸葛亮'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity {id:'司马懿'}) "
                            + "RETURN count(*) AS paths",
                    "2 跳（27 条路），路径多数经过章回；两人分属蜀/魏两个敌对阵营，无直接关系"),
            new Question("Q23", "谁是五虎上将？", "Who are the Five Tiger Generals?",
                    "g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV().values('name')",
                    "MATCH (p:Entity)-[r:Entity_Entity]->(x:Entity {id:'五虎上将'}) WHERE r.relation='holds' "
                            + "RETURN p.name",
                    "关羽、张飞、赵云、马超、黄忠（数据来自本地补充 triples-extra.csv，方案 B 把五虎上将当成职位）"),
            new Question("Q24", "谁是五虎将之首？", "Who leads the Five Tiger Generals?",
                    "g.V('五虎上将之首','Entity').inE().filter(relation.equals('holds')).otherV().values('name')",
                    "MATCH (p:Entity)-[r:Entity_Entity]->(x:Entity {id:'五虎上将之首'}) WHERE r.relation='holds' "
                            + "RETURN p.name",
                    "关羽（依据文档原文「刘备平定益州后，封关羽为五虎大将之首」）"));

    // ------------------------------------------------------------------ //
    // 4) "A 和 B 是什么关系?" — a reusable recipe                      //
    // ------------------------------------------------------------------ //

    /**
     * One step of the {@code "A 和 B 是什么关系?"} recipe. {@code {A}} and {@code {B}} are
     * replaced by the two entity names (which are the vertex ids in this graph).
     */
    public record RelationStep(String no, String purpose, String gremlin, String cypher) {
    }

    /**
     * How to answer "A 和 B 是什么关系?" by querying the graph, in 11 steps.
     *
     * <p>The order matters: first ask whether there is a <em>direct</em> edge at all,
     * then look for what the two have in common (faction, lord, intermediate
     * entities, chapters), then measure the distance, and only then read the two
     * semantic links that make the answer readable ("关羽是刘备的义弟").
     */
    public static final List<RelationStep> RELATION_RECIPE = List.of(
            new RelationStep("①", "有没有直接关系边（两个方向都算）",
                    "g.V('{A}','Entity').bothE('Entity_Entity').where(otherV().hasId('{B}')).elementMap()",
                    "MATCH (a:Entity {id:'{A}'})-[r:Entity_Entity]-(b:Entity {id:'{B}'}) RETURN r.relation, r.relationLabel"),
            new RelationStep("②", "共同阵营 / 势力（两人都效力于谁）",
                    "g.V('{A}','Entity').outE().filter(relation.equals('serves')).otherV().dedup()"
                            + ".where(inE().filter(relation.equals('serves')).otherV().hasId('{B}')).values('name')",
                    "MATCH (a:Entity {id:'{A}'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'{B}'}) "
                            + "WHERE r1.relation='serves' AND r2.relation='serves' RETURN DISTINCT f.name"),
            new RelationStep("③", "共同上司 / 主公（两人是不是同一个人的臣）",
                    // 主公边是 lord: L --lord--> P（L 是主公），所以从 A 看是 In、从主公 L 看是 Out
                    "g.V('{A}','Entity').inE().filter(relation.equals('lord')).otherV().dedup()"
                            + ".where(outE().filter(relation.equals('lord')).otherV().hasId('{B}')).values('name')",
                    "MATCH (a:Entity {id:'{A}'})<-[r1:Entity_Entity]-(l:Entity)-[r2:Entity_Entity]->(b:Entity {id:'{B}'}) "
                            + "WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT l.name"),
            new RelationStep("④", "{A} 的关系画像（都有哪些关系、各多少条）",
                    "g.V('{A}','Entity').bothE('Entity_Entity').groupCount().by('relationLabel')",
                    "MATCH (a:Entity {id:'{A}'})-[r:Entity_Entity]-(x:Entity) "
                            + "RETURN r.relationLabel AS relation, count(*) AS n ORDER BY n DESC"),
            new RelationStep("⑤", "{B} 的关系画像",
                    "g.V('{B}','Entity').bothE('Entity_Entity').groupCount().by('relationLabel')",
                    "MATCH (a:Entity {id:'{B}'})-[r:Entity_Entity]-(x:Entity) "
                            + "RETURN r.relationLabel AS relation, count(*) AS n ORDER BY n DESC"),
            new RelationStep("⑥", "共同邻居（谁同时认识两人）—— 按类型分组",
                    "g.V('{A}','Entity').both('Entity_Entity').dedup().where(both('Entity_Entity').hasId('{B}'))"
                            + ".groupCount().by('entityType')",
                    "MATCH (a:Entity {id:'{A}'})-[:Entity_Entity]-(m:Entity)-[:Entity_Entity]-(b:Entity {id:'{B}'}) "
                            + "RETURN m.entityType AS type, count(DISTINCT m.name) AS n ORDER BY n DESC"),
            new RelationStep("⑦", "共同邻居里的“人 / 势力”（去掉章回）—— 这就是中间人",
                    "g.V('{A}','Entity').both('Entity_Entity').dedup().where(both('Entity_Entity').hasId('{B}'))"
                            + ".where(not(has('entityType','Chapter'))).values('name')",
                    "MATCH (a:Entity {id:'{A}'})-[:Entity_Entity]-(m:Entity)-[:Entity_Entity]-(b:Entity {id:'{B}'}) "
                            + "WHERE m.entityType <> 'Chapter' RETURN DISTINCT m.name"),
            new RelationStep("⑧", "共同出场（两人出现在同一章回的数目）",
                    "g.V('{A}','Entity').outE().filter(relation.equals('appearsIn')).otherV().dedup()"
                            + ".where(inE().filter(relation.equals('appearsIn')).otherV().hasId('{B}')).count()",
                    "MATCH (a:Entity {id:'{A}'})-[r1:Entity_Entity]->(ch:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'{B}'}) "
                            + "WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' "
                            + "RETURN count(DISTINCT ch.name) AS chapters"),
            new RelationStep("⑨", "距离与可达性：多少条 2 跳路径（先看 1 跳是否为 0）",
                    "g.V('{A}','Entity').repeat(both('Entity_Entity')).times(2).hasId('{B}').count()",
                    "MATCH (a:Entity {id:'{A}'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity {id:'{B}'}) "
                            + "RETURN count(*) AS twoHopPaths"),
            new RelationStep("⑩", "关键链 ①：经“共同中间人”的两跳链（把第⑦步的人代进来）",
                    "g.V('{A}','Entity').both('Entity_Entity').dedup().where(hasId('刘备'))"
                            + ".both('Entity_Entity').dedup().where(hasId('{B}')).count()",
                    "MATCH (a:Entity {id:'{A}'})-[:Entity_Entity]-(m:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity {id:'{B}'}) "
                            + "RETURN count(*) AS paths"),
            new RelationStep("⑪", "把两人的“身份关系”读出来（“是什么关系”的最后一步）",
                    "g.V('{A}','Entity').bothE('Entity_Entity').where(otherV().hasId('刘备')).elementMap()",
                    "MATCH (a:Entity {id:'{A}'})-[r:Entity_Entity]-(m:Entity {id:'刘备'}) "
                            + "RETURN r.relation AS relation, r.relationLabel AS label"));

    /** The recipe with the two placeholders replaced (for a worked example). */
    public static List<RelationStep> relationRecipe(String a, String b) {
        return RELATION_RECIPE.stream()
                .map(step -> new RelationStep(step.no(),
                        step.purpose().replace("{A}", a).replace("{B}", b),
                        step.gremlin().replace("{A}", a).replace("{B}", b),
                        step.cypher().replace("{A}", a).replace("{B}", b)))
                .toList();
    }

    // ------------------------------------------------------------------ //
    // 5) Gap checks: why an edge is missing                              //
    // ------------------------------------------------------------------ //

    /** A read-only query that shows whether an expected relation is in the graph. */
    public record GapCheck(String what, String gremlin) {
    }

    /** The queries that show what is there and what is missing for a "why is this edge missing" question. */
    public static final List<GapCheck> GAP_CHECKS = List.of(
            new GapCheck("关羽的关系类型（有 臣/五虎将 吗）",
                    "g.V('关羽','Entity').outE().groupCount().by('relationLabel')"),
            new GapCheck("刘备的 lord 部下名单（关羽/张飞在不在）",
                    "g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().values('name')"),
            new GapCheck("全图 minister（臣）边有多少",
                    "g.V('Entity').outE().filter(relation.equals('minister')).elementMap()"),
            new GapCheck("全图 colleague（同事）边有多少",
                    "g.V('Entity').outE().filter(relation.equals('colleague')).elementMap()"),
            new GapCheck("诸葛亮 ↔ 刘备 之间实际有什么边",
                    "g.V('诸葛亮','Entity').bothE().where(otherV().hasId('刘备')).elementMap()"),
            new GapCheck("两人的官职（holds）分别是什么",
                    "g.V('诸葛亮','关羽','Entity').outE().filter(relation.equals('holds')).elementMap()"),
            new GapCheck("按关系名找 五虎将/军师/谋士（本体内有吗）",
                    "g.V('Entity').outE().filter(relation.equals('五虎将') || relation.equals('军师')"
                            + " || relation.equals('谋士')).count()"));

    /** The conversions as a Markdown table (used by the documentation). */
    public static String conversionsMarkdown() {
        StringBuilder out = new StringBuilder("| # | 用途 | Cypher | Gremlin | 说明 |\n|---|---|---|---|---|\n");
        for (int i = 0; i < CYPHER_TO_GREMLIN.size(); i++) {
            Conversion conversion = CYPHER_TO_GREMLIN.get(i);
            out.append("| ").append(i + 1).append(" | ").append(conversion.title())
                    .append(" | `").append(conversion.cypher().replace("|", "\\|"))
                    .append("` | `").append(conversion.gremlin().replace("|", "\\|"))
                    .append("` | ").append(conversion.note()).append(" |\n");
        }
        return out.toString();
    }

    /** The multi-hop queries as a Markdown table (used by the documentation). */
    public static String multiHopMarkdown() {
        StringBuilder out = new StringBuilder("| # | 用途 | Gremlin | Cypher |\n|---|---|---|---|\n");
        for (int i = 0; i < MULTI_HOP.size(); i++) {
            HopQuery hop = MULTI_HOP.get(i);
            out.append("| ").append(i + 1).append(" | ").append(hop.title()).append(" / ").append(hop.titleEn())
                    .append(" | `").append(hop.gremlin().replace("|", "\\|"))
                    .append("` | `").append(hop.cypher().replace("|", "\\|")).append("` |\n");
        }
        return out.toString();
    }
}

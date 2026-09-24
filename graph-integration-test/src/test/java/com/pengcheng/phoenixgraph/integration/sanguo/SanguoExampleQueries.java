package com.pengcheng.phoenixgraph.integration.sanguo;

import java.util.List;

/**
 * The example queries the 《三国演义》 dataset is asked for — the queries the UI
 * offers and {@code SanguoExampleQueriesTest} runs against the seeded graph.
 *
 * <p>They follow the two idioms of the relation-sharded layout:
 *
 * <ul>
 *   <li>a vertex id is {@code name_shard}, so one person is read through the ids
 *   {@code 曹操_2 … 曹操_9} (its shards);</li>
 *   <li>an edge carries {@code relation} (forward name) and {@code xrelation} (its
 *   inverse), so {@code relation.equals(R) || xrelation.equals(R)} finds the
 *   relation {@code R} from either endpoint and either direction.</li>
 * </ul>
 */
public final class SanguoExampleQueries {

    /** One documented example. */
    public record Example(String title, String titleEn, String query, boolean expectRows) {
    }

    /** Gremlin examples, in the order the UI lists them. */
    public static final List<Example> GREMLIN = List.of(
            new Example("一个分片的全部关系（曹操_3）", "All relations of one shard",
                    "g.V('曹操_3','Entity').bothE()", true),
            new Example("一个人全部分片的关系", "Every shard of one person",
                    "g.V('曹操_2','曹操_3','曹操_5','曹操_6','曹操_7','曹操_8','曹操_9','Entity').batch(20).bothE()", true),
            new Example("按关系过滤（主公）", "Filter by relation (lord)",
                    "g.V('曹操_3','Entity').bothE()"
                            + ".filter(relation.equals('lord') || xrelation.equals('lord'))", true),
            new Example("过滤后跳一跳（去重）", "Filter, then one hop, deduplicated",
                    "g.V('曹操_3','Entity').bothE().filter(relation.equals('lord')).otherV().dedup()", true),
            new Example("反向：谁是曹操的臣子", "Inverse: who serves Cao Cao",
                    "g.V('曹操_9','Entity').inE().filter(relation.equals('minister')).otherV().dedup()", true),
            new Example("姜维的师傅（师承家族）", "Jiang Wei and his master",
                    "g.V('姜维_7','Entity').bothE()"
                            + ".filter(relation.equals('apprentice') || xrelation.equals('apprentice')).otherV().dedup()", true),
            new Example("诸葛亮出场章回数", "How many chapters Zhuge Liang appears in",
                    "g.V('诸葛亮_3','Entity').bothE().filter(relation.equals('appearsIn')).otherV().count()", true),
            new Example("按关系家族聚合", "Count the relations by family",
                    "g.V('曹操_2','曹操_3','曹操_5','曹操_6','曹操_7','曹操_8','曹操_9','Entity')"
                            + ".bothE().groupCount('relationFamily')", true),
            new Example("魏有多少人效力", "How many people serve Wei",
                    "g.V('魏_8','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()", true),
            new Example("两跳：曹嵩 → 曹操 → 曹昂", "Two hops: grandfather to grandson",
                    "g.V('曹嵩_8','Entity').bothE().filter(relation.equals('father')).otherV()"
                            + ".bothE().filter(relation.equals('father')).otherV().dedup()", true));

    /** Cypher examples, in the order the UI lists them. */
    public static final List<Example> CYPHER = List.of(
            new Example("人物本体（曹操）", "Person vertex (Cao Cao)",
                    "MATCH (e:Entity {id:'曹操_3'}) RETURN e.name, e.entityType, e.gender, e.courtesyName", true),
            new Example("出边关系与中文关系名", "Outgoing relations with the Chinese label",
                    "MATCH (e:Entity {id:'曹操_3'})-[r:Entity_Entity]->(x:Entity) "
                            + "RETURN r.relation, r.relationLabel, x.name LIMIT 20", true),
            new Example("按关系过滤：曹操的主公关系", "Filter by relation: Cao Cao as lord",
                    "MATCH (e:Entity {id:'曹操_3'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' "
                            + "RETURN x.name AS subject, r.relationLabel AS relation LIMIT 20", true),
            new Example("反向关系：谁效力曹操", "Inverse: who serves Cao Cao",
                    "MATCH (e:Entity {id:'曹操_9'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relation = 'minister' "
                            + "RETURN x.name LIMIT 20", true),
            new Example("按关系家族聚合", "Aggregate by relation family",
                    "MATCH (e:Entity {id:'曹操_3'})-[r:Entity_Entity]->(x:Entity) "
                            + "RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC", true),
            new Example("两跳：曹嵩 → 曹操 → 曹昂", "Two hops: grandfather to grandson",
                    "MATCH (a:Entity {id:'曹嵩_8'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) "
                            + "RETURN DISTINCT b.name AS son, c.name AS grandson LIMIT 20", true),
            new Example("出场章回（诸葛亮）", "Chapters Zhuge Liang appears in",
                    "MATCH (p:Entity {id:'诸葛亮_3'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation = 'appearsIn' "
                            + "RETURN c.name LIMIT 25", true),
            new Example("谁效力魏", "Who serves the state of Wei",
                    "MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏_8'}) WHERE r.relation = 'serves' "
                            + "RETURN p.name, p.entityType LIMIT 20", true),
            new Example("曹操的同族（亲属家族）", "Cao Cao's clan (kindship family)",
                    "MATCH (e:Entity {id:'曹操_5'})-[r:Entity_Entity]->(x:Entity) WHERE r.relationFamily = '亲属' "
                            + "RETURN r.relationLabel AS relation, x.name LIMIT 20", true),
            new Example("曹操的子女（亲属家族）", "Cao Cao's children (kindship family)",
                    "MATCH (e:Entity {id:'曹操_6'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily = '亲属' "
                            + "RETURN r.relationLabel AS relation, x.name LIMIT 20", true));

    private SanguoExampleQueries() {
    }

    /** The examples as a Markdown table (used by the documentation). */
    public static String toMarkdown() {
        StringBuilder out = new StringBuilder();
        out.append("### Gremlin\n\n| 用途 | Purpose | Query |\n|---|---|---|\n");
        for (Example example : GREMLIN) {
            out.append("| ").append(example.title()).append(" | ").append(example.titleEn())
                    .append(" | `").append(example.query()).append("` |\n");
        }
        out.append("\n### Cypher\n\n| 用途 | Purpose | Query |\n|---|---|---|\n");
        for (Example example : CYPHER) {
            out.append("| ").append(example.title()).append(" | ").append(example.titleEn())
                    .append(" | `").append(example.query()).append("` |\n");
        }
        return out.toString();
    }
}

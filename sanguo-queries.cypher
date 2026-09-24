// ================================================================
// 《三国演义》知识图谱 · Cypher 查询清单（一行一条，可直接复制）
// 图：sanguo-kg-rocksdb-edge（点 id = 实体名）。
// 注意：本 Cypher 引擎要求 MATCH 至少有一个用 id 指定的起点；
//       「全图扫描 + 聚合」那几条只有 Gremlin 版本（见 .gremlin 清单）。
// ================================================================

// ---- 1. 问答式（有 Cypher 版本的） ----
MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) WHERE r1.relation='serves' AND r2.relation='serves' RETURN DISTINCT f.name
MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]-(b:Entity {id:'曹操'}) RETURN r.relation
MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(ch:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' RETURN count(DISTINCT ch.name) AS chapters
MATCH (a:Entity {id:'刘备'})-[r1:Entity_Entity]->(p:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT p.name
MATCH (e:Entity {id:'诸葛亮'})<-[r:Entity_Entity]-(l:Entity) WHERE r.relation='lord' RETURN l.name AS lord, r.relationLabel AS relation
MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]->(b:Entity {id:'张飞'}) RETURN r.relation AS relation, r.relationLabel AS label
MATCH (e:Entity {id:'刘备'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relation='younger_sworn_brother' RETURN x.name, r.relationLabel AS relation
MATCH (p:Entity)-[r1:Entity_Entity]->(f:Entity {id:'吴'}), (p)-[r2:Entity_Entity]->(x:Entity) WHERE r1.relation='serves' AND r2.relation='lord' RETURN DISTINCT p.name AS lord
MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation='serves' RETURN count(*) AS members
MATCH (a:Entity {id:'曹操'})-[r1:Entity_Entity]->(p:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'刘备'}) WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT p.name AS defector
MATCH (l:Entity)-[r:Entity_Entity]->(x:Entity {id:'张辽'}) WHERE r.relation='lord' RETURN l.name AS lord
MATCH (l:Entity)-[r:Entity_Entity]->(x:Entity {id:'徐庶'}) WHERE r.relation='lord' RETURN l.name AS lord, r.relationLabel AS relation
MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]-(x:Entity) WHERE r.relation='same_clan' RETURN DISTINCT x.name
MATCH (e:Entity {id:'诸葛亮'})-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily='亲属' RETURN DISTINCT x.name
MATCH (e:Entity {id:'诸葛亮'})-[r:Entity_Entity]-(x:Entity)<-[:Entity_Entity]-(l:Entity {id:'孙权'}) WHERE r.relationFamily='亲属' RETURN DISTINCT x.name
MATCH (a:Entity {id:'诸葛亮'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity {id:'司马懿'}) RETURN count(*) AS paths
MATCH (p:Entity)-[r:Entity_Entity]->(x:Entity {id:'五虎上将'}) WHERE r.relation='holds' RETURN p.name
MATCH (p:Entity)-[r:Entity_Entity]->(x:Entity {id:'五虎上将之首'}) WHERE r.relation='holds' RETURN p.name

// ---- 2. 多跳（两跳 / 三跳，12 条） ----
MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity) RETURN count(DISTINCT c.name) AS twoHopNeighbours
MATCH (a:Entity {id:'刘备'})-[r1:Entity_Entity]->(b:Entity)-[r2:Entity_Entity]->(c:Entity) WHERE r1.relation='lord' AND r2.relation='master' RETURN b.name AS mid, c.name AS end
MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(m:Entity)-[:Entity_Entity]-(b:Entity {id:'曹操'}) RETURN DISTINCT m.name
MATCH (a:Entity {id:'徐庶'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity) WHERE r1.relation='serves' AND r2.relation='serves' RETURN count(DISTINCT b.name) AS colleagues
MATCH (a:Entity {id:'诸葛亮'})-[r1:Entity_Entity]->(ch:Entity)<-[r2:Entity_Entity]-(b:Entity) WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' RETURN count(DISTINCT b.name) AS sameChapter
MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity) RETURN c.name AS name, count(*) AS paths ORDER BY paths DESC LIMIT 10
MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity {id:'曹操'}) RETURN count(*) AS paths
MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity)-[:Entity_Entity]-(d:Entity {id:'曹操'}) RETURN count(*) AS paths
MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity)-[:Entity_Entity]-(a) RETURN DISTINCT b.name AS b, c.name AS c LIMIT 5
MATCH (a:Entity {id:'魏'})<-[:Entity_Entity]-(p:Entity)-[:Entity_Entity]->(q:Entity)-[:Entity_Entity]->(r:Entity {id:'蜀'}) RETURN count(*) AS paths
MATCH (a:Entity {id:'司马懿'})<-[r1:Entity_Entity]-(s:Entity)<-[r2:Entity_Entity]-(g:Entity) WHERE r1.relation='son' AND r2.relation='son' RETURN DISTINCT g.name
MATCH (a:Entity {id:'曹操'})-[:Entity_Entity]->(b:Entity {id:'司马懿'})<-[:Entity_Entity]-(s:Entity {id:'司马昭'})<-[:Entity_Entity]-(g:Entity) RETURN DISTINCT g.name

// ---- 3. 典型查询（10 条） ----
MATCH (e:Entity {id:'曹操'}) RETURN e.name, e.entityType, e.gender, e.courtesyName
MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation, r.relationLabel, x.name LIMIT 20
MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' RETURN x.name AS subject, r.relationLabel AS relation LIMIT 20
MATCH (e:Entity {id:'马谡'})-[r:Entity_Entity]->(x:Entity) WHERE r.xrelation = 'lord' RETURN x.name AS lord, r.relationLabel AS relation
MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC
MATCH (a:Entity {id:'曹嵩'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) RETURN DISTINCT b.name AS middle, c.name AS twoHop LIMIT 20
MATCH (p:Entity {id:'诸葛亮'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation = 'appearsIn' RETURN c.name LIMIT 25
MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation = 'serves' RETURN p.name, p.entityType LIMIT 20
MATCH (a:Entity {id:'刘备'})-[r:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN r.relation AS relation, r.relationLabel AS label
MATCH (e:Entity {id:'曹操'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily = '亲属' RETURN r.relationLabel AS relation, x.name LIMIT 20

// ---- 4. Cypher → Gremlin 的 Cypher 侧（10 条） ----
MATCH (e:Entity {id:'曹操'}) RETURN e.name, e.entityType, e.gender, e.courtesyName
MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation, r.relationLabel, x.name LIMIT 20
MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' RETURN x.name AS subject, r.relationLabel AS relation LIMIT 20
MATCH (e:Entity {id:'马谡'})-[r:Entity_Entity]->(x:Entity) WHERE r.xrelation = 'lord' RETURN x.name AS lord, r.relationLabel AS relation
MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC
MATCH (a:Entity {id:'曹嵩'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) RETURN DISTINCT b.name AS middle, c.name AS twoHop LIMIT 20
MATCH (p:Entity {id:'诸葛亮'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation = 'appearsIn' RETURN c.name LIMIT 25
MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation = 'serves' RETURN p.name, p.entityType LIMIT 20
MATCH (a:Entity {id:'刘备'})-[r:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN r.relation AS relation, r.relationLabel AS label
MATCH (e:Entity {id:'曹操'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily = '亲属' RETURN r.relationLabel AS relation, x.name LIMIT 20

// ---- 5. 「关羽 和 诸葛亮 是什么关系」解题模板（11 步） ----
MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN r.relation, r.relationLabel
MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'诸葛亮'}) WHERE r1.relation='serves' AND r2.relation='serves' RETURN DISTINCT f.name
MATCH (a:Entity {id:'关羽'})<-[r1:Entity_Entity]-(l:Entity)-[r2:Entity_Entity]->(b:Entity {id:'诸葛亮'}) WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT l.name
MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]-(x:Entity) RETURN r.relationLabel AS relation, count(*) AS n ORDER BY n DESC
MATCH (a:Entity {id:'诸葛亮'})-[r:Entity_Entity]-(x:Entity) RETURN r.relationLabel AS relation, count(*) AS n ORDER BY n DESC
MATCH (a:Entity {id:'关羽'})-[:Entity_Entity]-(m:Entity)-[:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN m.entityType AS type, count(DISTINCT m.name) AS n ORDER BY n DESC
MATCH (a:Entity {id:'关羽'})-[:Entity_Entity]-(m:Entity)-[:Entity_Entity]-(b:Entity {id:'诸葛亮'}) WHERE m.entityType <> 'Chapter' RETURN DISTINCT m.name
MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(ch:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'诸葛亮'}) WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' RETURN count(DISTINCT ch.name) AS chapters
MATCH (a:Entity {id:'关羽'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity {id:'诸葛亮'}) RETURN count(*) AS twoHopPaths
MATCH (a:Entity {id:'关羽'})-[:Entity_Entity]-(m:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN count(*) AS paths
MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]-(m:Entity {id:'刘备'}) RETURN r.relation AS relation, r.relationLabel AS label

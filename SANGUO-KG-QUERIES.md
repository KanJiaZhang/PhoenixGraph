# 《三国演义》知识图谱查询手册

> 由 `SanguoRocksDbEdgeTest#writesTheQueryCookbook` 生成：**下面每一条查询都在这张图上实测跑过**，
> 「实测答案」一列是数据集的回答。图：`sanguo-kg-rocksdb-edge`（RocksDB-Edge）。
> 同一份数据还有 Aerospike 版本 `sanguo-kg`，点 id 是 `名字_shard`（例：`曹操_3`），
> 把查询里的实体名换成对应的分片 id 即可（分片 = `floorMod(relation.hashCode(),10)`）。

## 0. 约定

| | |
|---|---|
| 图 | `sanguo-kg-rocksdb-edge`（RocksDB-Edge）/ `sanguo-kg`（Aerospike） |
| 点 | 一个类型 `Entity`：人物 / 势力 / 籍贯 / 官职 / 章回，`entityType` 区分 |
| 点 id | RocksDB 版 = 实体名本身（`曹操`、`魏`、`第49回`）；Aerospike 版 = `名字_shard` |
| 边 | `Entity_Entity`（有向，head --relation--> tail），边上带 `relation` / `xrelation`（逆关系）/ `relationLabel`（中文）/ `relationFamily`（关系家族） |
| 关系家族 | 亲属 / 婚姻 / 朋友 / 君臣 / 师承 / 归降 / 招募 / 效力 / 官职 / 籍贯 / 出场 / 其他 |
| 中文关系名 | 父亲 / 儿子 / 哥哥 / 弟弟 / 妻 / 夫 / 妾 / 义兄 / 义弟 / 主公 / 臣 / 师傅 / 徒弟 / 效力 / 官职 / 籍贯 / 出场 … |

**两种语言的写法差异**（本引擎实测）：

- Gremlin 没有 `as()` / `select()`：Cypher 一行里同时投影「边 + 邻居」的，Gremlin 要写两条 —— `elementMap()` 取边、`otherV().values('name')` 取邻居。
- `path('name')` 返回整条走链 `[起点, 边, 中间点, 边, 终点]`，其中边渲染为 `null`。
- Cypher 必须有一个用 `{id:...}` 指定的起点，**不能全图扫描**；需要扫描全 `Entity` 的聚合（势力排行、部下排行）只有 Gremlin：`g.V('Entity')` + `groupCount().by('name')`。
- `groupCount().by('k')` 返回一个 map；`groupCount()` 数的是「到达的对端」，所以 `g.V('Entity').inE().filter(relation.equals('lord')).otherV().groupCount().by('name')` 就是「每个主公有多少部下」。
- 方向：`A --lord--> B` 表示 A 是 B 的主公（B 是 A 的臣）；`A --serves--> 势力` 表示 A 效力该势力。所以「谁是 X 的部下」用 `outE(lord)`，「X 是谁的部下」用 `inE(lord)`。

## 1. 快速复制 · Gremlin

```text
// ================================================================
// 《三国演义》知识图谱 · Gremlin 查询清单（一行一条，可直接复制）
// 图：sanguo-kg-rocksdb-edge（RocksDB-Edge，点 id = 实体名）。
// 换到 Aerospike 图 sanguo-kg 时把点 id 换成 名字_shard（如 曹操_3）。
// ================================================================

// ---- 1. 问答式（22 条） ----
g.V('关羽','Entity').outE().filter(relation.equals('serves')).otherV().dedup().where(inE().filter(relation.equals('serves')).otherV().hasId('曹操')).values('name')
g.V('关羽','Entity').bothE('Entity_Entity').where(otherV().hasId('曹操')).count()
g.V('关羽','Entity').outE().filter(relation.equals('appearsIn')).otherV().dedup().where(inE().filter(relation.equals('appearsIn')).otherV().hasId('曹操')).count()
g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().dedup().where(inE().filter(relation.equals('lord')).otherV().hasId('曹操')).values('name')
g.V('诸葛亮','Entity').inE().filter(relation.equals('lord')).otherV().values('name')
g.V('关羽','Entity').outE().filter(relation.equals('elder_sworn_brother')).otherV().values('name')
g.V('刘备','Entity').inE().filter(relation.equals('younger_sworn_brother')).otherV().dedup().values('name')
g.V('Entity').inE().filter(relation.equals('lord')).otherV().groupCount().by('name')
g.V('吴','Entity').inE().filter(relation.equals('serves')).otherV().dedup().where(outE().filter(relation.equals('lord'))).values('name')
g.V('魏','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()
g.V('Entity').outE().filter(relation.equals('serves')).otherV().groupCount().by('name')
g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().dedup().where(inE().filter(relation.equals('lord')).otherV().hasId('刘备')).values('name')
g.V('Entity').outE().filter(relation.equals('lord')).otherV().groupCount().by('name')
g.V('张辽','Entity').inE().filter(relation.equals('lord')).otherV().values('name')
g.V('徐庶','Entity').inE().filter(relation.equals('lord')).otherV().values('name')
g.V('Entity').inE().filter(relation.equals('serves')).otherV().groupCount().by('name')
g.V('曹操','Entity').bothE().filter(relation.equals('same_clan')).otherV().values('name')
g.V('诸葛亮','Entity').bothE().filter(relationFamily.equals('亲属')).otherV().dedup().values('name')
g.V('诸葛亮','Entity').bothE().filter(relationFamily.equals('亲属')).otherV().dedup().where(inE().filter(relation.equals('lord')).otherV().hasId('孙权')).values('name')
g.V('Entity').inE().filter(relation.equals('appearsIn')).otherV().groupCount().by('name')
g.V('Entity').outE().filter(relation.equals('appearsIn')).otherV().groupCount().by('name')
g.V('诸葛亮','Entity').repeat(both('Entity_Entity')).times(2).hasId('司马懿').count()
g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV().values('name')
g.V('五虎上将之首','Entity').inE().filter(relation.equals('holds')).otherV().values('name')

// ---- 2. 多跳（两跳 / 三跳，12 条） ----
g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').dedup().count()
g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().outE().filter(relation.equals('master')).otherV().dedup().values('name')
g.V('刘备','Entity').both('Entity_Entity').dedup().where(both('Entity_Entity').hasId('曹操')).values('name')
g.V('徐庶','Entity').outE().filter(relation.equals('serves')).otherV().inE().filter(relation.equals('serves')).otherV().dedup().count()
g.V('诸葛亮','Entity').outE().filter(relation.equals('appearsIn')).otherV().inE().filter(relation.equals('appearsIn')).otherV().dedup().count()
g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').groupCount().by('name')
g.V('刘备','Entity').repeat(both('Entity_Entity')).times(2).hasId('曹操').count()
g.V('刘备','Entity').repeat(both('Entity_Entity')).times(3).hasId('曹操').count()
g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').both('Entity_Entity').cyclicPath().path('name').limit(5)
g.V('魏','Entity').in('Entity_Entity').out('Entity_Entity').out('Entity_Entity').hasId('蜀').count()
g.V('司马懿','Entity').inE().filter(relation.equals('son')).otherV().inE().filter(relation.equals('son')).otherV().dedup().values('name')
g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().hasId('司马懿').inE().filter(relation.equals('son')).otherV().hasId('司马昭').inE().filter(relation.equals('son')).otherV().dedup().values('name')

// ---- 3. 典型查询（10 条） ----
g.V('曹操','Entity').values('name','entityType','gender','courtesyName')
g.V('曹操','Entity').bothE()
g.V('曹操','Entity').bothE().otherV().dedup()
g.V('曹操','Entity').outE().filter(relation.equals('lord'))
g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().dedup()
g.V('刘备','Entity').bothE().filter(relation.equals('lord') || xrelation.equals('lord')).otherV().dedup()
g.V('姜维','Entity').bothE().filter(relation.equals('master') || xrelation.equals('master')).otherV().dedup()
g.V('曹操','Entity').bothE().groupCount('relationFamily')
g.V('曹嵩','Entity').outE().filter(relation.equals('father')).otherV().outE().filter(relation.equals('father')).otherV().dedup()
g.V('魏','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()

// ---- 4. Cypher → Gremlin 的 Gremlin 侧（10 条） ----
g.V('曹操','Entity').values('name','entityType','gender','courtesyName')
g.V('曹操','Entity').outE('Entity_Entity').limit(20).elementMap()
g.V('曹操','Entity').outE('Entity_Entity').filter(relation.equals('lord')).limit(20).otherV().values('name')
g.V('马谡','Entity').outE('Entity_Entity').filter(xrelation.equals('lord')).otherV().values('name')
g.V('曹操','Entity').outE('Entity_Entity').groupCount().by('relationFamily')
g.V('曹嵩','Entity').out('Entity_Entity').out('Entity_Entity').dedup().path('name').limit(20)
g.V('诸葛亮','Entity').outE('Entity_Entity').filter(relation.equals('appearsIn')).limit(25).otherV().values('name')
g.V('魏','Entity').inE('Entity_Entity').filter(relation.equals('serves')).limit(20).otherV().valueMap('name','entityType')
g.V('刘备','Entity').bothE('Entity_Entity').where(otherV().hasId('诸葛亮')).values('relation','relationLabel')
g.V('曹操','Entity').inE('Entity_Entity').filter(relationFamily.equals('亲属')).limit(20).elementMap()
```

## 2. 快速复制 · Cypher

```text
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
```

## 3. 问答式查询（22 条）

| # | 问题 | Gremlin | Cypher | 实测答案 |
|---|---|---|---|---|
| Q1 | 关羽和曹操是什么关系？ | `g.V('关羽','Entity').outE().filter(relation.equals('serves')).otherV().dedup().where(inE().filter(relation.equals('serves')).otherV().hasId('曹操')).values('name')` | `MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) WHERE r1.relation='serves' AND r2.relation='serves' RETURN DISTINCT f.name` | 共同势力 = 魏、东汉（史实：降汉不降曹）。两人没有直接边 |
| Q2 | 关羽和曹操有直接关系吗？ | `g.V('关羽','Entity').bothE('Entity_Entity').where(otherV().hasId('曹操')).count()` | `MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]-(b:Entity {id:'曹操'}) RETURN r.relation` | 没有（0 条边）；他们通过共同势力 / 共同章回相连 |
| Q3 | 关羽和曹操共同出场过多少回？ | `g.V('关羽','Entity').outE().filter(relation.equals('appearsIn')).otherV().dedup().where(inE().filter(relation.equals('appearsIn')).otherV().hasId('曹操')).count()` | `MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(ch:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' RETURN count(DISTINCT ch.name) AS chapters` | 58 回 |
| Q4 | 刘备和曹操是什么关系？ | `g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().dedup().where(inE().filter(relation.equals('lord')).otherV().hasId('曹操')).values('name')` | `MATCH (a:Entity {id:'刘备'})-[r1:Entity_Entity]->(p:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'曹操'}) WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT p.name` | 没有直接边；共同的部下是 徐庶（先事刘备，后归曹操） |
| Q5 | 刘备和诸葛亮是什么关系？ | `g.V('诸葛亮','Entity').inE().filter(relation.equals('lord')).otherV().values('name')` | `MATCH (e:Entity {id:'诸葛亮'})<-[r:Entity_Entity]-(l:Entity) WHERE r.relation='lord' RETURN l.name AS lord, r.relationLabel AS relation` | 刘备是诸葛亮的主公（lord / 主公） |
| Q6 | 关羽和张飞是什么关系？ | `g.V('关羽','Entity').outE().filter(relation.equals('elder_sworn_brother')).otherV().values('name')` | `MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]->(b:Entity {id:'张飞'}) RETURN r.relation AS relation, r.relationLabel AS label` | 关羽是张飞的义兄（elder_sworn_brother / 义兄），张飞是关羽的义弟 |
| Q7 | 桃园三兄弟是谁？ | `g.V('刘备','Entity').inE().filter(relation.equals('younger_sworn_brother')).otherV().dedup().values('name')` | `MATCH (e:Entity {id:'刘备'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relation='younger_sworn_brother' RETURN x.name, r.relationLabel AS relation` | 刘备、关羽、张飞；关羽和张飞都是刘备的义弟 |
| Q8 | 三国三大主公分别是谁？ | `g.V('Entity').inE().filter(relation.equals('lord')).otherV().groupCount().by('name')` | —（需全图扫描，仅 Gremlin） | 按部下数排出：曹操=22、孙权=16、刘备=15（其后吕布=3、刘表=2）—— 即魏/吴/蜀三国主公 |
| Q9 | 刘备、曹操之外的第三国主公是谁？ | `g.V('吴','Entity').inE().filter(relation.equals('serves')).otherV().dedup().where(outE().filter(relation.equals('lord'))).values('name')` | `MATCH (p:Entity)-[r1:Entity_Entity]->(f:Entity {id:'吴'}), (p)-[r2:Entity_Entity]->(x:Entity) WHERE r1.relation='serves' AND r2.relation='lord' RETURN DISTINCT p.name AS lord` | 孙权（吴）。另：魏的主公=曹操；蜀用 serves 查不到（刘备没有文档，没有 serves 边），可用「蜀国成员的主公」推导出刘备 |
| Q10 | 魏国阵营有多少人？ | `g.V('魏','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()` | `MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation='serves' RETURN count(*) AS members` | 92 人 |
| Q11 | 哪个势力的人最多？ | `g.V('Entity').outE().filter(relation.equals('serves')).otherV().groupCount().by('name')` | —（需全图扫描，仅 Gremlin） | 魏=92、东汉=77、吴=44、西晋=38、蜀=35、少数民族=13、在野=13 … |
| Q12 | 刘备的手下有谁是从曹操那边过来的？ | `g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().dedup().where(inE().filter(relation.equals('lord')).otherV().hasId('刘备')).values('name')` | `MATCH (a:Entity {id:'曹操'})-[r1:Entity_Entity]->(p:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'刘备'}) WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT p.name AS defector` | 徐庶（先为刘备的臣，后归曹操） |
| Q13 | 谁换过主公（有 2 个以上主公）？ | `g.V('Entity').outE().filter(relation.equals('lord')).otherV().groupCount().by('name')` | —（需全图扫描，仅 Gremlin） | 张辽=2（吕布→曹操）、徐庶=2（刘备→曹操），其余 1 |
| Q14 | 张辽是从谁那里投奔曹操的？ | `g.V('张辽','Entity').inE().filter(relation.equals('lord')).otherV().values('name')` | `MATCH (l:Entity)-[r:Entity_Entity]->(x:Entity {id:'张辽'}) WHERE r.relation='lord' RETURN l.name AS lord` | 吕布（先）与曹操（后）—— 从吕布处归降曹操 |
| Q15 | 徐庶的两任主公是谁？ | `g.V('徐庶','Entity').inE().filter(relation.equals('lord')).otherV().values('name')` | `MATCH (l:Entity)-[r:Entity_Entity]->(x:Entity {id:'徐庶'}) WHERE r.relation='lord' RETURN l.name AS lord, r.relationLabel AS relation` | 刘备、曹操 |
| Q16 | 谁效力过的势力最多？ | `g.V('Entity').inE().filter(relation.equals('serves')).otherV().groupCount().by('name')` | —（需全图扫描，仅 Gremlin） | 马超=4（东汉、马腾势力、蜀、张鲁）；甘宁/黄忠/张辽/袁绍/吕布/卢植/关羽=3 |
| Q17 | 曹操的同族有谁？ | `g.V('曹操','Entity').bothE().filter(relation.equals('same_clan')).otherV().values('name')` | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]-(x:Entity) WHERE r.relation='same_clan' RETURN DISTINCT x.name` | 夏侯渊、夏侯淳、曹仁 |
| Q18 | 诸葛亮的亲属都有谁？ | `g.V('诸葛亮','Entity').bothE().filter(relationFamily.equals('亲属')).otherV().dedup().values('name')` | `MATCH (e:Entity {id:'诸葛亮'})-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily='亲属' RETURN DISTINCT x.name` | 诸葛芸、诸葛瞻（子）、诸葛瑾（兄）、诸葛珪（父）、诸葛均（弟）、章氏（母） |
| Q19 | 诸葛亮的哥哥在哪个国家？ | `g.V('诸葛亮','Entity').bothE().filter(relationFamily.equals('亲属')).otherV().dedup().where(inE().filter(relation.equals('lord')).otherV().hasId('孙权')).values('name')` | `MATCH (e:Entity {id:'诸葛亮'})-[r:Entity_Entity]-(x:Entity)<-[:Entity_Entity]-(l:Entity {id:'孙权'}) WHERE r.relationFamily='亲属' RETURN DISTINCT x.name` | 诸葛瑾，在吴国（孙权的臣） |
| Q20 | 谁出场章回最多？ | `g.V('Entity').inE().filter(relation.equals('appearsIn')).otherV().groupCount().by('name')` | —（需全图扫描，仅 Gremlin） | 曹操=93、诸葛亮=78、关羽=65、张飞=63、孙权=62、赵云=51 … |
| Q21 | 哪一回出场的人最多？ | `g.V('Entity').outE().filter(relation.equals('appearsIn')).otherV().groupCount().by('name')` | —（需全图扫描，仅 Gremlin） | 第56回=18、第60回=15、第50回=14、第48回=14 … |
| Q22 | 诸葛亮和司马懿相隔多远？ | `g.V('诸葛亮','Entity').repeat(both('Entity_Entity')).times(2).hasId('司马懿').count()` | `MATCH (a:Entity {id:'诸葛亮'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity {id:'司马懿'}) RETURN count(*) AS paths` | 2 跳（27 条路），路径多数经过章回；两人分属蜀/魏两个敌对阵营，无直接关系 |
| Q23 | 谁是五虎上将？ | `g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV().values('name')` | `MATCH (p:Entity)-[r:Entity_Entity]->(x:Entity {id:'五虎上将'}) WHERE r.relation='holds' RETURN p.name` | 关羽、张飞、赵云、马超、黄忠（数据来自本地补充 triples-extra.csv，方案 B 把五虎上将当成职位） |
| Q24 | 谁是五虎将之首？ | `g.V('五虎上将之首','Entity').inE().filter(relation.equals('holds')).otherV().values('name')` | `MATCH (p:Entity)-[r:Entity_Entity]->(x:Entity {id:'五虎上将之首'}) WHERE r.relation='holds' RETURN p.name` | 关羽（依据文档原文「刘备平定益州后，封关羽为五虎大将之首」） |

## 4. 多跳关系查询（两跳 / 三跳，12 条）

| # | 用途 | Gremlin | Cypher | 实测 |
|---|---|---|---|---|
| 1 | 两跳 · 二度人脉：刘备的两跳邻居有多少 | `g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').dedup().count()` | `MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity) RETURN count(DISTINCT c.name) AS twoHopNeighbours` | 3330  (标量) |
| 2 | 两跳 · 指定关系链：刘备 —主公→ 诸葛亮 —师傅→ 姜维 | `g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().outE().filter(relation.equals('master')).otherV().dedup().values('name')` | `MATCH (a:Entity {id:'刘备'})-[r1:Entity_Entity]->(b:Entity)-[r2:Entity_Entity]->(c:Entity) WHERE r1.relation='lord' AND r2.relation='master' RETURN b.name AS mid, c.name AS end` | 1 条: 姜维 |
| 3 | 两跳 · 共同邻居：刘备和曹操共同认识谁 | `g.V('刘备','Entity').both('Entity_Entity').dedup().where(both('Entity_Entity').hasId('曹操')).values('name')` | `MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(m:Entity)-[:Entity_Entity]-(b:Entity {id:'曹操'}) RETURN DISTINCT m.name` | 84 条: 魏 \| 东汉 \| 徐庶 \| 第97回 \| ... |
| 4 | 两跳 · 同势力同僚：徐庶的同僚有多少（经势力两跳） | `g.V('徐庶','Entity').outE().filter(relation.equals('serves')).otherV().inE().filter(relation.equals('serves')).otherV().dedup().count()` | `MATCH (a:Entity {id:'徐庶'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity) WHERE r1.relation='serves' AND r2.relation='serves' RETURN count(DISTINCT b.name) AS colleagues` | 1649  (标量) |
| 5 | 两跳 · 同回出场：诸葛亮和多少人出现在同一章回 | `g.V('诸葛亮','Entity').outE().filter(relation.equals('appearsIn')).otherV().inE().filter(relation.equals('appearsIn')).otherV().dedup().count()` | `MATCH (a:Entity {id:'诸葛亮'})-[r1:Entity_Entity]->(ch:Entity)<-[r2:Entity_Entity]-(b:Entity) WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' RETURN count(DISTINCT b.name) AS sameChapter` | 937  (标量) |
| 6 | 两跳 · 路径强度：刘备两跳能到谁、各有几条路（Top 10） | `g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').groupCount().by('name')` | `MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity) RETURN c.name AS name, count(*) AS paths ORDER BY paths DESC LIMIT 10` | 3330 组, Top6: {刘备=168, 曹操=84, 关羽=68, 诸葛亮=68, 张飞=65, 孙权=59, ...} |
| 7 | 两跳 · 可达性：刘备两跳能不能到曹操（几条路） | `g.V('刘备','Entity').repeat(both('Entity_Entity')).times(2).hasId('曹操').count()` | `MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity {id:'曹操'}) RETURN count(*) AS paths` | 84  (标量) |
| 8 | 三跳 · 路径数：刘备三跳到曹操有多少条路 | `g.V('刘备','Entity').repeat(both('Entity_Entity')).times(3).hasId('曹操').count()` | `MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity)-[:Entity_Entity]-(d:Entity {id:'曹操'}) RETURN count(*) AS paths` | 1250  (标量) |
| 9 | 三跳 · 三跳环：刘备出发又绕回自己的三跳环 | `g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').both('Entity_Entity').cyclicPath().path('name').limit(5)` | `MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity)-[:Entity_Entity]-(a) RETURN DISTINCT b.name AS b, c.name AS c LIMIT 5` | 5 条: [刘备, null, 刘弘[刘备父], null, 刘雄[刘备祖父], null, 刘弘[刘备父]] \| [刘备, null, 刘弘[刘备父], null, 刘雄[刘备祖父], null, 刘弘[刘备父]] \| [刘备,... |
| 10 | 三跳 · 跨势力：从魏出发三跳到蜀（路数） | `g.V('魏','Entity').in('Entity_Entity').out('Entity_Entity').out('Entity_Entity').hasId('蜀').count()` | `MATCH (a:Entity {id:'魏'})<-[:Entity_Entity]-(p:Entity)-[:Entity_Entity]->(q:Entity)-[:Entity_Entity]->(r:Entity {id:'蜀'}) RETURN count(*) AS paths` | 89  (标量) |
| 11 | 三跳 · 祖孙三代：司马懿 → 儿子 → 孙子（司马炎） | `g.V('司马懿','Entity').inE().filter(relation.equals('son')).otherV().inE().filter(relation.equals('son')).otherV().dedup().values('name')` | `MATCH (a:Entity {id:'司马懿'})<-[r1:Entity_Entity]-(s:Entity)<-[r2:Entity_Entity]-(g:Entity) WHERE r1.relation='son' AND r2.relation='son' RETURN DISTINCT g.name` | 3 条: 司马炎 \| 司马攸 \| 司马繇 |
| 12 | 三跳 · 混合关系链：曹操 —主公→ 司马懿 ←儿子← 司马昭 ←儿子← 司马炎 | `g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().hasId('司马懿').inE().filter(relation.equals('son')).otherV().hasId('司马昭').inE().filter(relation.equals('son')).otherV().dedup().values('name')` | `MATCH (a:Entity {id:'曹操'})-[:Entity_Entity]->(b:Entity {id:'司马懿'})<-[:Entity_Entity]-(s:Entity {id:'司马昭'})<-[:Entity_Entity]-(g:Entity) RETURN DISTINCT g.name` | 2 条: 司马炎 \| 司马攸 |

## 5. 典型查询（Gremlin 10 + Cypher 10）

| # | 语言 | 用途 | Query | 实测 |
|---|---|---|---|---|
| G1 | Gremlin | ① 实体属性（点查） | `g.V('曹操','Entity').values('name','entityType','gender','courtesyName')` | 4 条: 曹操 \| Male \| 男 \| 孟德 |
| G2 | Gremlin | ② 一个人的全部关系（一条记录，不再分片） | `g.V('曹操','Entity').bothE()` | 225 条: 边(son/儿子 -> 曹衮) \| 边(brother/兄弟 -> 曹德) \| 边(son/儿子 -> 曹彰) \| 边(son/儿子 -> 曹茂) \| ... |
| G3 | Gremlin | ③ 邻居去重（社交圈） | `g.V('曹操','Entity').bothE().otherV().dedup()` | 177 条: 点(曹衮) \| 点(曹德) \| 点(曹彰) \| 点(曹茂) \| ... |
| G4 | Gremlin | ④ 按关系过滤：曹操的主公关系 | `g.V('曹操','Entity').outE().filter(relation.equals('lord'))` | 22 条: 边(lord -> 钟会) \| 边(lord -> 郭嘉) \| 边(lord -> 邓艾) \| 边(lord -> 贾诩) \| ... |
| G5 | Gremlin | ⑤ 一跳：曹操手下的将领 | `g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().dedup()` | 22 条: 点(钟会) \| 点(郭嘉) \| 点(邓艾) \| 点(贾诩) \| ... |
| G6 | Gremlin | ⑥ 反向惯用法：刘备的臣属 | `g.V('刘备','Entity').bothE().filter(relation.equals('lord') || xrelation.equals('lord')).otherV().dedup()` | 18 条: 点(黄忠) \| 点(魏延) \| 点(马超) \| 点(马良) \| ... |
| G7 | Gremlin | ⑦ 师承：姜维的师傅 | `g.V('姜维','Entity').bothE().filter(relation.equals('master') || xrelation.equals('master')).otherV().dedup()` | 1 条: 点(诸葛亮) |
| G8 | Gremlin | ⑧ 按关系家族聚合 | `g.V('曹操','Entity').bothE().groupCount('relationFamily')` | 7 组, Top6: {出场=93, 亲属=71, 婚姻=33, 君臣=22, 官职=3, 效力=2, ...} |
| G9 | Gremlin | ⑨ 两跳：曹嵩 → 曹操 → 曹昂（祖孙） | `g.V('曹嵩','Entity').outE().filter(relation.equals('father')).otherV().outE().filter(relation.equals('father')).otherV().dedup()` | 29 条: 点(金乡公主) \| 点(曹铄) \| 点(曹衮) \| 点(曹茂) \| ... |
| G10 | Gremlin | ⑩ 势力成员数：魏 | `g.V('魏','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()` | 1286  (标量) |
| C1 | Cypher | ① 人物本体（属性） | `MATCH (e:Entity {id:'曹操'}) RETURN e.name, e.entityType, e.gender, e.courtesyName` | [e.name, e.entityType, e.gender, e.courtesyName] -> 1 行: {e.name=曹操, e.entityType=Male, e.gender=男, e.courtesyName=孟德} |
| C2 | Cypher | ② 出边关系与中文关系名 | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation, r.relationLabel, x.name LIMIT 20` | [r.relation, r.relationLabel, x.name] -> 20 行: {r.relation=son, r.relationLabel=儿子, x.name=曹嵩} \| {r.relation=serves,... |
| C3 | Cypher | ③ 按关系过滤：曹操的主公关系 | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' RETURN x.name AS subject, r.relationLabel AS relation LIMIT 20` | [subject, relation] -> 20 行: {subject=钟会, relation=主公} \| {subject=郭嘉, relation=主公} \| {subject=邓艾, relation=主公} \| ... |
| C4 | Cypher | ④ 反向：马谡的主公（用 xrelation） | `MATCH (e:Entity {id:'马谡'})-[r:Entity_Entity]->(x:Entity) WHERE r.xrelation = 'lord' RETURN x.name AS lord, r.relationLabel AS relation` | [lord, relation] -> 1 行: {lord=刘备, relation=臣} |
| C5 | Cypher | ⑤ 按关系家族聚合 | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC` | [family, n] -> 7 行: {family=出场, n=93} \| {family=亲属, n=35} \| {family=君臣, n=22} \| ... |
| C6 | Cypher | ⑥ 两跳邻居：曹嵩 → 曹操 → X | `MATCH (a:Entity {id:'曹嵩'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) RETURN DISTINCT b.name AS middle, c.name AS twoHop LIMIT 20` | [middle, twoHop] -> 20 行: {middle=曹褒, twoHop=东汉} \| {middle=曹褒, twoHop=豫州沛国谯} \| {middle=曹褒, twoHop=曹胤} \| ... |
| C7 | Cypher | ⑦ 出场章回（诸葛亮） | `MATCH (p:Entity {id:'诸葛亮'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation = 'appearsIn' RETURN c.name LIMIT 25` | [c.name] -> 25 行: {c.name=第9回} \| {c.name=第99回} \| {c.name=第98回} \| ... |
| C8 | Cypher | ⑧ 谁效力魏（跨类型：人物 → 势力） | `MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation = 'serves' RETURN p.name, p.entityType LIMIT 20` | [p.name, p.entityType] -> 20 行: {p.name=卑湛, p.entityType=Male} \| {p.name=黄邕, p.entityType=Male} \| {p.name=全翩, p.ent... |
| C9 | Cypher | ⑨ 两个实体之间有什么关系 | `MATCH (a:Entity {id:'刘备'})-[r:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN r.relation AS relation, r.relationLabel AS label` | [relation, label] -> 1 行: {relation=lord, label=主公} |
| C10 | Cypher | ⑩ 曹操的子女（亲属家族） | `MATCH (e:Entity {id:'曹操'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily = '亲属' RETURN r.relationLabel AS relation, x.name LIMIT 20` | [relation, x.name] -> 20 行: {relation=儿子, x.name=曹衮} \| {relation=兄弟, x.name=曹德} \| {relation=儿子, x.name=曹彰} \| ... |

## 6. Cypher → Gremlin 转换（10 条）

| # | 用途 | Cypher | Gremlin | 说明 |
|---|---|---|---|---|
| 1 | ① 点查：实体属性 | `MATCH (e:Entity {id:'曹操'}) RETURN e.name, e.entityType, e.gender, e.courtesyName` | `g.V('曹操','Entity').values('name','entityType','gender','courtesyName')` | 一一对应 |
| 2 | ② 出边关系 + 中文关系名 | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation, r.relationLabel, x.name LIMIT 20` | `g.V('曹操','Entity').outE('Entity_Entity').limit(20).elementMap()` | Gremlin 无 as/select：elementMap() 返回边（含 relation/relationLabel）；邻居名另查 ...otherV().values('name') |
| 3 | ③ 按关系过滤 + 邻居名 | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' RETURN x.name AS subject, r.relationLabel AS relation LIMIT 20` | `g.V('曹操','Entity').outE('Entity_Entity').filter(relation.equals('lord')).limit(20).otherV().values('name')` | filter(relation.equals(R)) 会被下推到存储；去掉 limit(20) 就是全部 22 条 |
| 4 | ④ 反向关系（xrelation） | `MATCH (e:Entity {id:'马谡'})-[r:Entity_Entity]->(x:Entity) WHERE r.xrelation = 'lord' RETURN x.name AS lord, r.relationLabel AS relation` | `g.V('马谡','Entity').outE('Entity_Entity').filter(xrelation.equals('lord')).otherV().values('name')` | xrelation = 逆关系名，与 relation 一样可下推 |
| 5 | ⑤ 按关系家族聚合 | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC` | `g.V('曹操','Entity').outE('Entity_Entity').groupCount().by('relationFamily')` | Gremlin 返回一个 map（全部家族），Cypher 返回按 n 排序的行 |
| 6 | ⑥ 两跳 + 中间点 | `MATCH (a:Entity {id:'曹嵩'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) RETURN DISTINCT b.name AS middle, c.name AS twoHop LIMIT 20` | `g.V('曹嵩','Entity').out('Entity_Entity').out('Entity_Entity').dedup().path('name').limit(20)` | path('name') 给整条走链 [起点, 边, 中间点, 边, 终点]，边渲染为 null |
| 7 | ⑦ 出场章回 | `MATCH (p:Entity {id:'诸葛亮'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation = 'appearsIn' RETURN c.name LIMIT 25` | `g.V('诸葛亮','Entity').outE('Entity_Entity').filter(relation.equals('appearsIn')).limit(25).otherV().values('name')` | 一一对应 |
| 8 | ⑧ 入边（谁效力魏） | `MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation = 'serves' RETURN p.name, p.entityType LIMIT 20` | `g.V('魏','Entity').inE('Entity_Entity').filter(relation.equals('serves')).limit(20).otherV().valueMap('name','entityType')` | 方向的写法：Cypher 用箭头指向势力，Gremlin 从势力端起 inE() |
| 9 | ⑨ 两个实体之间有什么关系 | `MATCH (a:Entity {id:'刘备'})-[r:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN r.relation AS relation, r.relationLabel AS label` | `g.V('刘备','Entity').bothE('Entity_Entity').where(otherV().hasId('诸葛亮')).values('relation','relationLabel')` | Cypher 把端点写在模式里；Gremlin 用 where(child) 把'对端是谁'当过滤条件 |
| 10 | ⑩ 亲属家族入边 + 中文标签 | `MATCH (e:Entity {id:'曹操'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily = '亲属' RETURN r.relationLabel AS relation, x.name LIMIT 20` | `g.V('曹操','Entity').inE('Entity_Entity').filter(relationFamily.equals('亲属')).limit(20).elementMap()` | elementMap() 带出 relationLabel；邻居名用 ...otherV().values('name') |

## 7. 统计

| 分组 | 条数 |
|---|---|
| 问答式（Q1–Q22） | 24 |
| 多跳（两跳/三跳） | 12 |
| 典型查询 | 20 |
| Cypher → Gremlin 转换 | 10 |
| 「A 和 B 是什么关系」解题模板 | 11 步 |
| **去重后 Gremlin 查询** | 54 |
| **去重后 Cypher 查询** | 40 |

## 8. 关系问答模板：「关羽 和 诸葛亮 是什么关系？」

> **解题思路**：① 先看有没有直接边 → ②③ 看共同阵营 / 共同上司 → ④⑤ 看两人各自的关系画像 →
> ⑥⑦ 找共同邻居（中间人）→ ⑧ 看共同出场 → ⑨ 量距离 → ⑩ 看关键链路 → ⑪ 读出「身份关系」。
> 把下面查询里的 `关羽` / `诸葛亮` 换成任意两个实体名（实体名就是点 id）就能复用这套模板。

| 步 | 这一步回答什么 | Gremlin | Cypher | 实测（关羽 / 诸葛亮） |
|---|---|---|---|---|
| ① | 有没有直接关系边（两个方向都算） | `g.V('关羽','Entity').bothE('Entity_Entity').where(otherV().hasId('诸葛亮')).elementMap()` | `MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN r.relation, r.relationLabel` | 0 条 |
| ② | 共同阵营 / 势力（两人都效力于谁） | `g.V('关羽','Entity').outE().filter(relation.equals('serves')).otherV().dedup().where(inE().filter(relation.equals('serves')).otherV().hasId('诸葛亮')).values('name')` | `MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(f:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'诸葛亮'}) WHERE r1.relation='serves' AND r2.relation='serves' RETURN DISTINCT f.name` | 1 条: 蜀 |
| ③ | 共同上司 / 主公（两人是不是同一个人的臣） | `g.V('关羽','Entity').inE().filter(relation.equals('lord')).otherV().dedup().where(outE().filter(relation.equals('lord')).otherV().hasId('诸葛亮')).values('name')` | `MATCH (a:Entity {id:'关羽'})<-[r1:Entity_Entity]-(l:Entity)-[r2:Entity_Entity]->(b:Entity {id:'诸葛亮'}) WHERE r1.relation='lord' AND r2.relation='lord' RETURN DISTINCT l.name` | 1 条: 刘备 |
| ④ | 关羽 的关系画像（都有哪些关系、各多少条） | `g.V('关羽','Entity').bothE('Entity_Entity').groupCount().by('relationLabel')` | `MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]-(x:Entity) RETURN r.relationLabel AS relation, count(*) AS n ORDER BY n DESC` | 10 组, Top6: {出场=65, 官职=5, 父亲=4, 效力=3, 主公=1, 义弟=1, ...} |
| ⑤ | 诸葛亮 的关系画像 | `g.V('诸葛亮','Entity').bothE('Entity_Entity').groupCount().by('relationLabel')` | `MATCH (a:Entity {id:'诸葛亮'})-[r:Entity_Entity]-(x:Entity) RETURN r.relationLabel AS relation, count(*) AS n ORDER BY n DESC` | 16 组, Top6: {出场=78, 兄弟=4, 官职=3, 父亲=3, 儿子=2, 效力=1, ...} |
| ⑥ | 共同邻居（谁同时认识两人）—— 按类型分组 | `g.V('关羽','Entity').both('Entity_Entity').dedup().where(both('Entity_Entity').hasId('诸葛亮')).groupCount().by('entityType')` | `MATCH (a:Entity {id:'关羽'})-[:Entity_Entity]-(m:Entity)-[:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN m.entityType AS type, count(DISTINCT m.name) AS n ORDER BY n DESC` | 3 组, Top6: {Chapter=39, Male=1, Faction=1} |
| ⑦ | 共同邻居里的“人 / 势力”（去掉章回）—— 这就是中间人 | `g.V('关羽','Entity').both('Entity_Entity').dedup().where(both('Entity_Entity').hasId('诸葛亮')).not(has('entityType','Chapter')).values('name')` | `MATCH (a:Entity {id:'关羽'})-[:Entity_Entity]-(m:Entity)-[:Entity_Entity]-(b:Entity {id:'诸葛亮'}) WHERE m.entityType <> 'Chapter' RETURN DISTINCT m.name` | 2 条: 刘备 \| 蜀 |
| ⑧ | 共同出场（两人出现在同一章回的数目） | `g.V('关羽','Entity').outE().filter(relation.equals('appearsIn')).otherV().dedup().where(inE().filter(relation.equals('appearsIn')).otherV().hasId('诸葛亮')).count()` | `MATCH (a:Entity {id:'关羽'})-[r1:Entity_Entity]->(ch:Entity)<-[r2:Entity_Entity]-(b:Entity {id:'诸葛亮'}) WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' RETURN count(DISTINCT ch.name) AS chapters` | 39  (标量) |
| ⑨ | 距离与可达性：多少条 2 跳路径（先看 1 跳是否为 0） | `g.V('关羽','Entity').repeat(both('Entity_Entity')).times(2).hasId('诸葛亮').count()` | `MATCH (a:Entity {id:'关羽'})-[:Entity_Entity]-(b:Entity)-[:Entity_Entity]-(c:Entity {id:'诸葛亮'}) RETURN count(*) AS twoHopPaths` | 42  (标量) |
| ⑩ | 关键链 ①：经“共同中间人”的两跳链（把第⑦步的人代进来） | `g.V('关羽','Entity').both('Entity_Entity').dedup().where(hasId('刘备')).both('Entity_Entity').dedup().where(hasId('诸葛亮')).count()` | `MATCH (a:Entity {id:'关羽'})-[:Entity_Entity]-(m:Entity {id:'刘备'})-[:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN count(*) AS paths` | 1  (标量) |
| ⑪ | 把两人的“身份关系”读出来（“是什么关系”的最后一步） | `g.V('关羽','Entity').bothE('Entity_Entity').where(otherV().hasId('刘备')).elementMap()` | `MATCH (a:Entity {id:'关羽'})-[r:Entity_Entity]-(m:Entity {id:'刘备'}) RETURN r.relation AS relation, r.relationLabel AS label` | 2 条: {id=刘备\|lord\|关羽, label=Entity_Entity, relation=lord, xrelation=minister, relationLabel=主公, relationFamily=君臣, y... |

### 本例的结论

关羽和诸葛亮之间**没有直接关系边**（第①步 0 条）。数据给出的关系是
「**同为刘备的臣、同为蜀汉阵营**」：

| 证据 | 查询结果 |
|---|---|
| 共同势力（第②步） | **蜀** —— 关羽 `效力` 蜀，诸葛亮 `效力` 蜀 |
| **共同上司（第③步）** | **刘备** —— 补上 `triples-extra.csv` 后，关羽、诸葛亮都是刘备的臣（`刘备 --lord--> 关羽/诸葛亮`） |
| 共同中间人（第⑦步） | **刘备**、**蜀**（另有 39 个章回把两人连在一起） |
| 身份关系（第⑪步） | 关羽 ↔ 刘备 = `lord` / **主公**（关羽是臣）**和** `younger_sworn_brother` / **义弟**；刘备 ↔ 诸葛亮 = `lord` / **主公** |
| 共同出场（第⑧步） | **39 回** |
| 距离（第⑨步） | 1 跳不可达（0 条），**2 跳 42 条路径** |

所以自然语言答案是：**关羽和诸葛亮是「同殿为臣」的关系 —— 两人都是刘备的臣、都效力蜀汉；关羽还是刘备的义弟（桃园结义）兼五虎上将之首，诸葛亮是刘备的丞相（数据只记「拜为丞相」，不记「军师」，见 §9），两人在 39 个章回里同时出场。**

> 对比上一版：在补 `triples-extra.csv` 之前，第③步是 0 条 —— 关羽与刘备之间只有「义弟」，所以只能靠「共同势力/共同中间人」说明关系；补上 `刘备 --lord--> 关羽` 之后，「同为刘备的臣」这一条直接可查，这就是方案 B 带来的差别。


## 9. 缺口诊断：为什么没有「关羽是刘备的臣 / 五虎将之首」「诸葛亮是刘备的军师 / 谋士」

### 9.1 诊断查询（先看图里有什么、缺什么）

| 看什么 | Gremlin | 实测 |
|---|---|---|
| 关羽的关系类型（有 臣/五虎将 吗） | `g.V('关羽','Entity').outE().groupCount().by('relationLabel')` | 7 组, Top6: {出场=65, 官职=5, 父亲=4, 效力=3, 义弟=1, 籍贯=1, ...} |
| 刘备的 lord 部下名单（关羽/张飞在不在） | `g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().values('name')` | 17 条: 黄忠 \| 魏延 \| 马超 \| 马良 \| ... |
| 全图 minister（臣）边有多少 | `g.V('Entity').outE().filter(relation.equals('minister')).elementMap()` | 3 条: {id=马谡\|minister\|刘备, label=Entity_Entity, relation=minister, xrelation=lord, relationLabel=臣, relationFamily=君臣... |
| 全图 colleague（同事）边有多少 | `g.V('Entity').outE().filter(relation.equals('colleague')).elementMap()` | 1 个元素: id=马谡\|colleague\|诸葛亮, label=Entity_Entity, relation=colleague, xrelation=colleague, relationLabel=同事, relatio... |
| 诸葛亮 ↔ 刘备 之间实际有什么边 | `g.V('诸葛亮','Entity').bothE().where(otherV().hasId('刘备')).elementMap()` | 1 个元素: id=刘备\|lord\|诸葛亮, label=Entity_Entity, relation=lord, xrelation=minister, relationLabel=主公, relationFamily=君臣,... |
| 两人的官职（holds）分别是什么 | `g.V('诸葛亮','关羽','Entity').outE().filter(relation.equals('holds')).elementMap()` | 8 条: {id=诸葛亮\|holds\|领司隶校尉益州牧, label=Entity_Entity, relation=holds, xrelation=holds, relationLabel=官职, relationFamily... |
| 按关系名找 五虎将/军师/谋士（本体内有吗） | `g.V('Entity').outE().filter(relation.equals('五虎将') || relation.equals('军师') || relation.equals('谋士')).count()` | 0  (标量) |

### 9.2 三层原因

一条事实要变成边，必须依次通过三层：

```
① ontology  theory.owl 里有没有这种关系类型？
       ↓ 有
② upstream  triples.csv / renwu_process_v1.csv 里写没写这条事实？
       ↓ 有，而且解析器能读到
③ parser    SanguoDataset 把它翻成边？
```

缺边必然卡在其中一层：

| 期望的边 | ① 本体 | ② 上游数据 | ③ 解析器 | 卡在哪层 |
|---|---|---|---|---|
| 关羽 —**臣**→ 刘备 | ✅ 有 `minister`/`lord` | ❌ `triples.csv` 里刘备的 `lord` 部下有 **15 人**（黄忠/魏延/马超/马良/赵云/诸葛亮/蒋琬/糜芳/糜竺/法正/沙摩柯/徐庶/庞统/孟获/姜维），**独缺关羽、张飞** | ❌ 文档派生不出人物–人物关系（关羽文档只有 `效力=东汉\|蜀\|魏`，指向的是**势力**蜀） | **② 上游漏标** |
| 关羽 —**五虎将之首**→ 刘备 | ❌ 60 个关系里没有「五虎将」 | ❌ curated 里没有 | 🟡 **文档自由文本里有原话**（见 9.3），但 `SanguoDataset` **只读 9 个结构化字段，不读 `历史`/`小说`** | **① 本体缺类型 + ③ 解析器不读文本** |
| 诸葛亮 —**军师/谋士**→ 刘备 | ❌ 没有「军师」「谋士」 | ❌ 诸葛亮自己的 bio 里**没有**这两个词（只有「蜀汉建立，**拜为丞相**」） | — | **① + ②：这条通俗说法本数据集没有记载** |
| 诸葛亮 —**臣**→ 刘备 | ✅ | ✅ `刘备,诸葛亮,lord` | ✅ | **已在图里**：`g.V('诸葛亮','Entity').inE().filter(relation.equals('lord'))`，或从刘备端 `outE(lord)`，边的 `xrelation=minister` |
| 关羽/张飞 与 赵云/马超/黄忠 **同列** | 🟡 有 `colleague`(同事)，但语义偏「同事」 | ❌ 全图只有 1 条 `colleague`（马谡→诸葛亮） | — | **① 语义不匹配 + ② 上游没标** |

### 9.3 文档原文证据（`renwu_process_v1.csv` 的自由文本）

| 人 | 字段 | 原文（截取） |
|---|---|---|
| 关羽 | 小说 | …刘备平定益州后，**封关羽为五虎大将之首**，督荆州事… |
| 张飞 | 小说 | …后**封为蜀汉五虎大将**… |
| 马超 | 小说 / 历史 | **五虎上将之一**… / …刘备为汉中王，升为左将军，**五虎上将**… |
| 诸葛亮 | 历史 | …蜀汉建立，**拜为丞相**…（全文无「军师」「谋士」） |
| 徐庶 | 小说 | …投效与刘备，**被拜为军师**…（数据集里「军师」指的是徐庶） |
| 庞统 | 官职 | **军师中郎将**（另一个人物的「军师」） |

> 统计：`renwu_process_v1.csv` 里 `五虎` 出现 **4** 次、`军师` **11** 次、`谋士` **5** 次——全部在**自由文本**里；而 `SanguoDataset` 只读 `父亲/母亲/兄弟/配偶/孩子/官职/主效/效力/章节列表` 这 9 个结构化字段。
> 换句话说：**「五虎大将之首」这句话就在数据里，只是没被抽成边。**

### 9.4 怎么补（三种方案，代价从小到大）

#### ✅ 方案 B（已落地）——把「五虎上将」当成一个**职位**，用已有的 `holds`(官职) 表达

写进了本地补充文件 [`triples-extra.csv`](graph-integration-test/src/test/resources/kg/sanguo/triples-extra.csv)：

```csv
"关羽","五虎上将","holds","官职"
"关羽","五虎上将之首","holds","官职"
"张飞","五虎上将","holds","官职"
"赵云","五虎上将","holds","官职"
"马超","五虎上将","holds","官职"
"黄忠","五虎上将","holds","官职"
```

同时补上了方案 A 的「臣」部分（`lord` ↔ `minister` 是本体互逆关系，只写一端）：

```csv
"刘备","关羽","lord","主公"
"刘备","张飞","lord","主公"
```

**为什么方案 B 比方案 A 好**：如果改用 `colleague`(同事) 表示五虎将，「**之首**」这个排序就丢了；
当成职位则两个问题都能查。实测：

| 问题 | 查询 | 答案 |
|---|---|---|
| 谁是五虎将 | `g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV().values('name')` | 关羽、张飞、赵云、马超、黄忠 |
| 谁是五虎将之首 | `g.V('五虎上将之首','Entity').inE().filter(relation.equals('holds')).otherV().values('name')` | 关羽 |
| 关羽是不是刘备的臣 | `g.V('刘备','Entity').outE().filter(relation.equals('lord')).otherV().dedup().where(hasId('关羽')).count()` | 1（关羽的 `minister` 逆边也已自动物化） |
| 关羽与马超是什么关系 | `g.V('关羽','Entity').both('Entity_Entity').hasId('马超')` + 共同职位/共同主公/共同章回 | 同列五虎上将、同为刘备的臣、共同出场 62 回（详见本文 §8 的模板） |

#### ⬜ 方案 A（只落地了「臣」部分）

还差 `colleague`(同事) 那几条（`关羽→张飞/赵云/马超/黄忠`）。**建议不加**：方案 B 的 `holds 五虎上将`
已经能回答「同列」，而 `colleague` 会把「同列武将」和「一般同事」混在一起。

#### ⬜ 方案 C（未做，需要 NLP）

让 `SanguoDataset` 也扫 `历史`/`小说` 两列，用词典/正则抽「五虎大将之首」「军师」「谋士」等模式。
代价是引入 NLP，需要校验兜底（可用本项目的 `SanguoRdfValidator` + `rdfs:domain`/`range` 检查）。

> 落地后的规模（由本次运行实测）：数据集 **2,298 条源关系 / 1,102 个实体**，
> 按本体物化后 **2,447 条边**，ABox **4,946 条 RDF 三元组**；Aerospike 1,637 条点记录，RocksDB-Edge 1,102 点 / 2,298 边。
> 补边必须**重新播种**；Aerospike 的边 bins 是 CDT map，旧数据不会自动消失 ——
> 另外把边 id 从「下标」改成了「由三元组本身导出（`head|relation|tail`）」，
> 否则插一条三元组会让后面所有边 id 平移、旧边留在 bins 里变成重复边。

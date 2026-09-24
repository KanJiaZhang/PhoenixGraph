# 《三国演义》知识图谱（`sanguo-kg`）

把一个**中文经典文档数据集**（OpenKG「四大名著人物关系知识图谱和 OWL 本体」的《三国演义》部分）
解析成 RDF、用本体校验，然后装进一个新的 PhoenixGraph 实例（Aerospike 存储，建模参考
`kg-entity-graph`：**点按 relation 的 hash 分片**，**边上存关系名**），最后给出一批实例查询。

四步各有一份可重复执行的代码与测试：

| 步骤 | 代码 | 测试 / 命令 |
|---|---|---|
| 1. 文档 → RDF，定义实体 / 关系拆分 | `SanguoOntology`、`SanguoDataset`、`SanguoRdf`、`SanguoRdfGenerator` | `SanguoRdfTest` |
| 2. 校验解析结果 | `SanguoRdfValidator` | `SanguoRdfTest#validatesTheParse` |
| 3. 写入新的 PhoenixGraph 实例 | `SanguoGraphIds`、`SanguoGraphSeeder`、`sanguo-kg.json` | `SanguoGraphIntegrationTest` |
| 4. 实例查询 | `SanguoExampleQueries` | `SanguoExampleQueriesTest` |
全部在 `graph-integration-test` 模块里，类在
`src/test/java/com/pengcheng/phoenixgraph/integration/sanguo/`。

---

## 0. 数据集

| | |
|---|---|
| 数据集 | 四大名著人物关系知识图谱和 OWL 本体 —《三国演义》部分 |
| 发布方 | OpenKG（`http://openkg.cn/dataset/ch4masterpieces`） |
| 使用的镜像 | [`wcswcswcs/sgyy`](https://github.com/wcswcswcs/sgyy) |
| 本地位置 | `graph-integration-test/src/test/resources/kg/sanguo/`（gzip，约 80 KB） |

三个文件，都是"文档"：

| 文件 | 内容 |
|---|---|
| `renwu_process_v1.csv.gz` | **331 篇人物文档**：`编号,名字,性别,字号,年龄,正史,籍贯,父亲,母亲,兄弟,配偶,孩子,官职,主效,效力,总年数,年代列表,总章节,章节列表,历史,小说,头像,链接,效力数,配偶数` |
| `theory.owl.gz` | **OWL 本体**：`owl:Class` `male`/`female` + **60 个 `owl:ObjectProperty` 关系**，带中文 `rdfs:label`、`rdfs:subPropertyOf`、`owl:inverseOf`、`rdfs:domain`/`rdfs:range` 和 OWL 特性 |
| `triples.csv.gz` | 上游抽好的 153 条关系三元组 `"head","tail","relation","label"` |
| **`triples-extra.csv`** | **本地补充三元组（8 条，明文以便修改）**：方案 B 的「五虎上将」职位 + 关羽/张飞的臣属边。上游漏标的事实补在这里，**不新增本体类型、不改解析规则**；依据与理由见 [SANGUO-KG-QUERIES.md](SANGUO-KG-QUERIES.md) §9「缺口诊断」 |

重新下载：`./tools/download-sanguo-dataset.sh`（详见该目录的 `README.md`）。

> 上游还有一个 `renwu_relate.csv`（18,045 条脚本派生的关系）。**本管线不用它**：
> 关系由 `SanguoDataset` 从人物文档的字段里重新派生，这样"文档 → RDF"这一步是解析器的职责，
> 而不是读一个现成的结果文件。

---

## 1. 文档 → RDF：实体与关系的拆分

### 1.1 实体拆分（entity split）

一个点类型 `Entity`，用 `entityType` 区分 7 个类：

| `entityType` | 中文 | 来源 | 数量 |
|---|---|---|---|
| `Male` | 男性人物 | 人物文档（`性别=男`） | 315 |
| `Female` | 女性人物 | 人物文档（`性别=女`） | 16 |
| `Person` | 人物（无文档） | 只作为关系端点出现的人（如刘备，上游导出没有他的文档） | 414 |
| `Faction` | 势力 | `效力` 字段：魏/蜀/吴/东汉/西晋/少数民族/在野/其他/起义军… | 17 |
| `Place` | 籍贯地 | `籍贯` 字段，取 `州郡(现代地名)` 的历史部分 | 128 |
| `Position` | 官职 | `官职` 字段（`|` 分隔，去掉 `谥曰…`）+ 补充的「五虎上将」「五虎上将之首」 | 92 |
| `Chapter` | 章回 | `章节列表` 字段（`第1回`…`第120回`） | 120 |

合计 **1,102 个实体**（其中两个 `Position` 来自本地补充：`五虎上将`、`五虎上将之首`）。`Male`/`Female` 在 TBox 里是 `Person` 的子类。

### 1.2 关系拆分（relation split）

- **60 个本体关系**：直接来自 `theory.owl`（`adopted_son`、`brother`、`concubine`、`elder_brother`、
  `father`、`lord`、`master`、`same_clan`、`sworn`…），保留中文 `rdfs:label`、`rdfs:subPropertyOf`
  层次、`rdfs:domain`/`rdfs:range`、OWL 特性（`Asymmetric` / `Irreflexive` / `Symmetric` / `Transitive` / …）。
- **4 个文档属性关系**（本体没有，由 `SanguoOntology.EXTENSION_PROPERTIES` 声明，所以 ABox 的每个
  谓词都在 TBox 里）：`serves`（效力）、`holds`（官职）、`nativePlace`（籍贯）、`appearsIn`（出场）。
- **4 个文档数据类型属性**（`owl:DatatypeProperty`）：`courtesyName`（字）、`historicity`（正史与虚构）、
  `historicalDescription`（历史简介）、`novelDescription`（演义简介）。
- **11 个粗粒度"关系家族" `relationFamily`**：把 64 个关系归到 `亲属 / 婚姻 / 朋友 / 君臣 / 师承 /
  归降 / 招募 / 效力 / 官职 / 籍贯 / 出场`，方便按家族聚合。

### 1.3 文档 → RDF 的映射规则

| 文档字段 | RDF | 说明 |
|---|---|---|
| `名字` | `sg:<名字> a sg:Male/Female` + `rdfs:label` | `名字[势力]` 的消歧后缀去掉；同名的两篇文档（郤俭、李邵）保留为 `郤俭（魏）`/`郤俭（东汉）` |
| `字号` / `正史` / `历史` / `小说` | `sg:courtesyName` / `sg:historicity` / `sg:historicalDescription` / `sg:novelDescription` | 语言标签 `@zh` |
| `父亲` / `母亲` | `父亲 --father--> 本人` / `母亲 --mother--> 本人` | `|` 分隔，去掉 `未知`、`暂无相关记载`、`？` |
| `兄弟` | `兄弟 --brother/sister--> 本人` | 目标有文档且为女 → `sister`，否则 `brother` |
| `配偶` | `本人 --husband/wife--> 配偶` | 本人为男 → `husband` |
| `孩子` | `孩子 --son/daughter--> 本人` | 目标为女 → `daughter` |
| `效力` | `本人 --serves--> 势力` | 势力名与某个人物同名时加 `势力` 后缀（`袁绍势力`），保证 IRI 单射、且董卓不会"效力自己" |
| `官职` | `本人 --holds--> 官职` | |
| `籍贯` | `本人 --nativePlace--> 地点` | |
| `章节列表` | `本人 --appearsIn--> 章回` | |
| `triples.csv` | 原样的三元组 | 与文档派生的关系按 `(head, relation, tail)` 去重，**curated 优先** |
| `triples-extra.csv`（本地补充） | 原样的三元组 | 优先级：curated > 补充 > 文档派生；目前 8 条（五虎上将职位 + 关羽/张飞臣属），见 §9 缺口诊断 |

**闭包（解析器的增强）**：解析完后按本体把规则物化出来——
`owl:inverseOf` 的逆边（`inverse(inverse(r)) == r`，是对合）和 `owl:SymmetricProperty` 的镜像边。
逆关系表由本体声明（`apprentice↔master`、`lord↔minister`、`wife↔husband`，其中 `husband` 的"半途声明"
来自 `wife ⊑ inverseOf(husband)` 这种匿名写法）+ 一组性别安全的兄弟/姐妹对（`elder_brother↔younger_brother` 等）。
当两个关系争同一个逆（`concubine` 与 `wife` 都声明 `husband`）时，主词胜出，`concubine` 保持自逆，
以维持对合性。

### 1.4 产物

`SanguoRdfGenerator` 生成 4 个文件，冻结在
`graph-integration-test/src/test/resources/kg/sanguo/rdf/`，并由 `SanguoRdfTest` 逐字节比对，
所以它们不会和解析器走偏：

| 文件 | 内容 | 大小 |
|---|---|---|
| `sanguo-ontology.ttl` | TBox：7 个类、64 个 `owl:ObjectProperty`、4 个 `owl:DatatypeProperty`、6 对逆关系、41 条 `rdfs:subPropertyOf`、486 条语句 | 36 KB |
| `sanguo-kg.ttl` | ABox，可读 Turtle（`sg:刘备` 这样的中文前缀名） | 287 KB |
| `sanguo-kg.nt` | ABox 的 N-Triples 机器形式（UTF-8 IRI） | 826 KB |
| `VALIDATION.md` | 第 2 步的校验报告 | 3 KB |

IRI 命名空间：`http://pengcheng.com/phoenixgraph/kg/sanguo#`（源本体的
`http://www.semanticweb.org/shen-pc/ontologies/2018/3/untitled-ontology-61#` 在 `SanguoOntology.SOURCE_NAMESPACE` 里留档）。

重新生成：

```bash
# 从明文文件（.datasets/sanguo，由下载脚本解开）生成，并覆盖冻结产物：
java -cp graph-integration-test/target/test-classes:<deps> \
     com.pengcheng.phoenixgraph.integration.sanguo.SanguoRdfGenerator \
     .datasets/sanguo target/sanguo-rdf

# 或者用测试（会先把 gz 解开到临时目录，跑同一个 main，再和冻结产物逐字节比对）：
./mvnw -o -pl graph-integration-test test -Dtest=SanguoRdfTest -Dsurefire.failIfNoSpecifiedTests=false \
       -Dsanguo.rdf.write=true        # 覆盖冻结产物
```

`.datasets/sanguo/` 是下载脚本的落地目录（`.gitignore` 已忽略，由 `tools/download-sanguo-dataset.sh` 重建）。

---

## 2. 校验：解析出的 RDF 是否正确

`SanguoRdfValidator` 拿**本体 + 人物文档 + 解析出的关系**做 10 条机器可查的规则：

| 规则 | 含义 | 结果 |
|---|---|---|
| `declared` | 每个谓词都在 TBox 里声明 | ✅ 全部已声明 |
| `no-dangling` | 每个主语/宾语都是已声明的实体 | ✅ 无悬挂 IRI |
| `domain` / `range` | `rdfs:domain`/`rdfs:range` 与实体类型一致 | ✅ **3,395 条通过，0 条违反**，523 条"无法判定"（关系端点人物没有文档，即性别未知） |
| `owl:IrreflexiveProperty` | 不自环 | ✅ |
| `owl:AsymmetricProperty` | 不同时双向成立 | ✅ |
| `inverse-closure` | 每条逆关系的逆边都物化 | ✅ |
| `symmetric-closure` | 每个对称关系的镜像边都物化 | ✅ |
| `involution` | `inverse(inverse(r)) == r` | ✅（6 对） |
| `declared-inverse` | 本体声明的逆关系没有被覆盖 | ✅（`concubine` 是有据可查的例外） |
| `duplicates` | curated 文件里的重复三元组 | 7 条（解析器已合并） |

最终：**PASS**。完整报告见 `src/test/resources/kg/sanguo/rdf/VALIDATION.md`。

### 校验真的抓到了 3 个问题（都已修）

1. **`owl:inverseOf` 漏读**：`wife`/`concubine` 把逆关系写成
   `<rdfs:subPropertyOf><rdf:Description><owl:inverseOf .../></rdf:Description></rdfs:subPropertyOf>`
   这种匿名嵌套形式，最初的 DOM 解析只找 `rdfs:subPropertyOf` 的直接子节点，于是
   `husband`/`wife` 的逆关系全丢，闭包少了 55 条边。
2. **势力与人同名**：`效力` 字段里既有国家（魏/蜀/吴），也有军阀（袁绍/刘表/董卓/袁术/刘璋），
   后者本身就是人物。`rdfs:range` 校验一次抓出 **33 条违反**——同一个 IRI 既是 `Male` 又是
   `Faction`，还有 `董卓 效力 董卓`、`张角 效力 张角` 这种自环。修法：军阀势力改名为 `袁绍势力`。
3. **ABox 谓词未声明**：`courtesyName`/`historicity`/… 这些人物属性不在原 OWL 里，
   回读时必须把它们声明成 `owl:DatatypeProperty`，否则"每个谓词都在 TBox 里"这条规则不成立。

---

## 3. 新的 PhoenixGraph 实例（Aerospike）

### 3.1 Schema：`graph-http/src/main/resources/sanguo-kg.json`

由 `node tools/generate-sanguo-schema.js` 生成（**不要手改 JSON**）。和 `kg-entity-graph` 一样只有
**一个点类型 + 一个有向边类型**：

- **点 `Entity`**：`name`（display）、`shard`、`entityType`、`gender`、`courtesyName`、`historicity`、
  `history`、`novel`。
- **边 `Entity_Entity`**（`DIRECTED`，`maxOut-/In-degree = 2000`）：`relation`（正向关系名）、
  `xrelation`（逆关系名，无逆时等于自己）、`relationLabel`（中文关系名）、`relationFamily`（关系家族）。

> 属性名刻意避开 `id` / `label` / `type` / `source` / `target`：这些是 Gremlin/Cypher 层在
> 属性 map 之前解析的元键，重名会被遮蔽（`v.type` 会返回 null），所以用 `entityType`、
> `relationLabel`。

### 3.2 点按 relation 的 hash 分片（`SanguoGraphIds`）

```text
shard    = floorMod(relation.hashCode(), 10)     // 0..9，用 floorMod 避免负 hash
vertexId = name + "_" + shard                    // 例：曹操_3、第49回_7、魏_8
label    = "Entity"
edge     = "Entity_Entity"
```

三元组 `(h, r, t)` 的**两端点用同一个 shard**（都来自 `r`），所以一个关系的所有边落在一对分片里。
1,102 个实体 → **1,637 条点记录**（同一人物在它参与的每个分片里各存一份），2,447 条边。
10 个分片里 **9 个有边**（没有一个关系的 hash 落在剩下那个分片上）。

代价与 `kg-entity-graph` 相同：一个人物存在于多个分片，所以"刘备的全部关系"要读它的所有分片
（`g.V('刘备_3','刘备_6','刘备_7','刘备_8','刘备_9','Entity')`）。

### 3.3 播种

```bash
# 全量（1,637 点 / 2,447 边，8 线程约 1.4s；已播种会自动跳过，KG_RESEED=true 强制重播）
AEROSPIKE_NAMESPACE=test ./mvnw -o -pl graph-integration-test -am test \
    -Dtest=SanguoGraphIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

| 环境变量 | 默认 | 含义 |
|---|---|---|
| `AEROSPIKE_VERTEX_SET` | `kg_sanguo_v1` | Aerospike set |
| `AEROSPIKE_NAMESPACE` | `test` | 命名空间 |
| `KG_RESEED` | `false` | 强制重播 |
| `KG_PARALLELISM` | `8` | 写线程数 |

`SanguoGraphSeeder` 与 `KnowledgeGraphSeeder` 同构：点 500/批、边 1000/批、并发写同 bin 会
generation 冲突，所以 store 用 `maxWriteAttempts=10` + 每 chunk 失败重发 4 次 + 静默日志。

### 3.4 挂到服务上

`application.yml` 的 `phoenixgraph.storage.graphs` 已经加了：

```yaml
      - schema: sanguo-kg
        vertex-set: kg_sanguo_v1
```

只想跑这张图：`--spring.profiles.active=sanguo`（`application-sanguo.yml` 覆盖成单图）。

重启服务：`tools/start-phoenixgraph.sh --with-hnsw`（脚本会先停旧服务再打包，因为运行中的 JVM
锁着 fat jar）。之后 `GET http://localhost:8081/api/v1/graphs` 里就能看到 `sanguo-kg`。

---

## 4. 实例查询

共 20 条，UI 下拉框（`graph-web` 的 `EXAMPLES['sanguo-kg']`）里直接可选；
`SanguoExampleQueries` 是它们的**可执行真源**，`SanguoExampleQueriesTest` 每条都跑一遍并断言有结果。

### 4.1 Gremlin

两条惯用法：**一个人要读它的所有分片**；**`relation.equals(R) || xrelation.equals(R)` 能从任意一端、
任意方向命中关系 `R`**。

| 用途 | Query |
|---|---|
| 一个分片的全部关系（曹操_3） | `g.V('曹操_3','Entity').bothE()` |
| 一个人全部分片的关系 | `g.V('曹操_2','曹操_3','曹操_5','曹操_6','曹操_7','曹操_8','曹操_9','Entity').batch(20).bothE()` |
| 按关系过滤（主公） | `g.V('曹操_3','Entity').bothE().filter(relation.equals('lord') \|\| xrelation.equals('lord'))` |
| 过滤后跳一跳（去重） | `g.V('曹操_3','Entity').bothE().filter(relation.equals('lord')).otherV().dedup()` |
| 反向：谁是曹操的臣子 | `g.V('曹操_9','Entity').inE().filter(relation.equals('minister')).otherV().dedup()` |
| 姜维的师傅（师承家族） | `g.V('姜维_7','Entity').bothE().filter(relation.equals('apprentice') \|\| xrelation.equals('apprentice')).otherV().dedup()` |
| 诸葛亮出场章回数 | `g.V('诸葛亮_3','Entity').bothE().filter(relation.equals('appearsIn')).otherV().count()` |
| 按关系家族聚合 | `g.V('曹操_2',…,'曹操_9','Entity').bothE().groupCount('relationFamily')` |
| 魏有多少人效力 | `g.V('魏_8','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()` |
| 两跳：曹嵩 → 曹操 → 曹昂 | `g.V('曹嵩_8','Entity').bothE().filter(relation.equals('father')).otherV().bothE().filter(relation.equals('father')).otherV().dedup()` |

实测（HTTP API，`POST /api/v1/query`）：

| Query | 结果 |
|---|---|
| 曹操_3 的全部关系 | 115 条边（其中 93 条 `appearsIn`） |
| 曹操全部分片的关系 | 220 条边，子图 224 点 / 220 边 |
| 曹操的主公关系 | 22 条（庞德、钟会…） |
| 谁是曹操的臣子 | 22 人 |
| 姜维的师傅 | 诸葛亮_7 |
| 诸葛亮出场章回数 | 78 |
| 按家族聚合 | `{亲属=44, 出场=93, 君臣=44, 婚姻=33, 籍贯=1, 官职=3, 效力=2}` |
| 魏有多少人效力 | 92 |
| 曹嵩 → 曹操 → 曹昂（两跳） | 3 人（曹操的儿子们） |

> `relationLabel` / `relationFamily` 随边返回，所以可视化界面不用再查本体就知道中文名
> (`主公`/`亲属`)。

### 4.2 Cypher

| 用途 | Query |
|---|---|
| 人物本体（曹操） | `MATCH (e:Entity {id:'曹操_3'}) RETURN e.name, e.entityType, e.gender, e.courtesyName` |
| 出边关系与中文关系名 | `MATCH (e:Entity {id:'曹操_3'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation, r.relationLabel, x.name LIMIT 20` |
| 按关系过滤：曹操的主公关系 | `MATCH (e:Entity {id:'曹操_3'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' RETURN x.name AS subject, r.relationLabel AS relation LIMIT 20` |
| 反向关系：谁效力曹操 | `MATCH (e:Entity {id:'曹操_9'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relation = 'minister' RETURN x.name LIMIT 20` |
| 按关系家族聚合 | `MATCH (e:Entity {id:'曹操_3'})-[r:Entity_Entity]->(x:Entity) RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC` |
| 两跳：曹嵩 → 曹操 → 曹昂 | `MATCH (a:Entity {id:'曹嵩_8'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) RETURN DISTINCT b.name AS son, c.name AS grandson LIMIT 20` |
| 出场章回（诸葛亮） | `MATCH (p:Entity {id:'诸葛亮_3'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation = 'appearsIn' RETURN c.name LIMIT 25` |
| 谁效力魏 | `MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏_8'}) WHERE r.relation = 'serves' RETURN p.name, p.entityType LIMIT 20` |
| 曹操的同族（亲属家族） | `MATCH (e:Entity {id:'曹操_5'})-[r:Entity_Entity]->(x:Entity) WHERE r.relationFamily = '亲属' RETURN r.relationLabel AS relation, x.name LIMIT 20` |
| 曹操的子女（亲属家族） | `MATCH (e:Entity {id:'曹操_6'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily = '亲属' RETURN r.relationLabel AS relation, x.name LIMIT 20` |

实测：`{e.name=曹操, e.entityType=Male, e.gender=男, e.courtesyName=孟德}`；
曹操的主公关系前两条 `{subject=庞德, relation=主公}`；谁效力魏共 92 人（示例 LIMIT 20）；
`{family=出场, n=93}`、`{family=君臣, n=22}`。

### 4.3 通过 HTTP API 跑

```bash
curl -s -X POST http://localhost:8081/api/v1/query \
  -H 'Content-Type: application/json' --data-binary @- <<'JSON'
{ "graphName": "sanguo-kg", "language": "cypher", "includeResult": true,
  "query": "MATCH (e:Entity {id:'曹操_3'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' RETURN x.name LIMIT 5" }
JSON
```

UI：打开 `http://localhost:8080/`，图选 `sanguo-kg`，语言选 Cypher 或 Gremlin，例句下拉框里就是上表。

---

## 5. RocksDB-Edge 版本（点不做 hash 分片）

同一份数据集、同一套实体/关系拆分，另建了一个 **RocksDB-Edge** 存储的实例：
`sanguo-kg-rocksdb-edge`。它和 Aerospike 版只差在“点怎么存、边怎么放”：

| | `sanguo-kg`（Aerospike） | `sanguo-kg-rocksdb-edge` |
|---|---|---|
| 点 id | `name_shard`（`曹操_3`），按 relation 的 hash 分 10 片 | **实体名本身**（`曹操`），不做 hash、不分片 |
| 一个人的记录数 | 最多 10 条（按参与的关系分片） | **1 条**（整个邻域） |
| 边 | 2,447 条（2,298 源关系 + 逆/对称闭包），存在点的 out/in bin 里 | **2,298 条源关系**，存在按边类分的边表里 |
| 边的可读性 | 从任一端都能命中（靠物化的逆边） | 从任一端都能命中（同一条边同时挂在源点出表和目标点入表），所以不需要复制 |
| 查询一个人 | 要把 10 个分片都作为种子 | `g.V('曹操','Entity')` 一句就够 |

### 5.1 Schema

`graph-http/src/main/resources/sanguo-kg-rocksdb-edge.json`，和 Aerospike 版
**由同一个脚本生成**（`node tools/generate-sanguo-schema.js` 同时写两个文件），共享
`Entity` / `Entity_Entity` 的定义，只去掉 `shard` 属性并加上：

```json
"storage": { "type": "RocksDB-Edge", "path": "sanguo-kg-rocksdb-edge",
             "regionCount": 512, "maxVersions": 1, "cacheEdges": true }
```

### 5.2 装载

`SanguoRocksDbLoader`（点 id = 实体名，一条关系一条边；边 id 用稳定的排序序号，重复装载幂等）：

```bash
# 装载服务要读的那份（.rocksdb/sanguo-kg-rocksdb-edge），先停服务再重载
./mvnw -o -pl graph-integration-test test -Dtest=SanguoRocksDbEdgeTest \
       -Dsurefire.failIfNoSpecifiedTests=false -DSANGUO_ROCKSDB_RESEED=true
# 或者直接跑 main
java -cp ... com.pengcheng.phoenixgraph.integration.sanguo.SanguoRocksDbLoader .rocksdb true
```

实测：**1,102 点 / 2,298 边，126 ms**。RocksDB 目录同一时刻只允许一个进程持有，所以
`SanguoRocksDbEdgeTest` 只在目录不存在时才装载（服务在跑时会跳过）。

### 5.3 20 个典型查询（RocksDB-Edge）

`SanguoRocksDbQueries` 是这 20 条的可执行真源（10 Gremlin + 10 Cypher），
`SanguoRocksDbEdgeTest#answersTheTwentyTypicalQueries` 全部跑一遍并把结果打印出来。

| # | 类别 | Gremlin |
|---|---|---|
| G1 | 点查（属性） | `g.V('曹操','Entity').values('name','entityType','gender','courtesyName')` |
| G2 | 一跳：全部关系（**一条记录**） | `g.V('曹操','Entity').bothE()` |
| G3 | 邻居去重（社交圈） | `g.V('曹操','Entity').bothE().otherV().dedup()` |
| G4 | 按关系过滤 | `g.V('曹操','Entity').outE().filter(relation.equals('lord'))` |
| G5 | 一跳：跳过去 | `g.V('曹操','Entity').outE().filter(relation.equals('lord')).otherV().dedup()` |
| G6 | 反向惯用法 | `g.V('刘备','Entity').bothE().filter(relation.equals('lord') \|\| xrelation.equals('lord')).otherV().dedup()` |
| G7 | 师承 | `g.V('姜维','Entity').bothE().filter(relation.equals('master') \|\| xrelation.equals('master')).otherV().dedup()` |
| G8 | 按关系家族聚合 | `g.V('曹操','Entity').bothE().groupCount('relationFamily')` |
| G9 | 两跳（祖孙） | `g.V('曹嵩','Entity').outE().filter(relation.equals('father')).otherV().outE().filter(relation.equals('father')).otherV().dedup()` |
| G10 | 势力成员数 | `g.V('魏','Entity').inE().filter(relation.equals('serves')).otherV().dedup().count()` |

| # | 类别 | Cypher |
|---|---|---|
| C1 | 点查（属性） | `MATCH (e:Entity {id:'曹操'}) RETURN e.name, e.entityType, e.gender, e.courtesyName` |
| C2 | 出边 + 中文关系名 | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation, r.relationLabel, x.name LIMIT 20` |
| C3 | 按关系过滤 | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) WHERE r.relation = 'lord' RETURN x.name, r.relationLabel LIMIT 20` |
| C4 | 反向（xrelation） | `MATCH (e:Entity {id:'马谡'})-[r:Entity_Entity]->(x:Entity) WHERE r.xrelation = 'lord' RETURN x.name AS lord` |
| C5 | 按关系家族聚合 | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC` |
| C6 | 两跳邻居 | `MATCH (a:Entity {id:'曹嵩'})-[:Entity_Entity]->(b:Entity)-[:Entity_Entity]->(c:Entity) RETURN DISTINCT b.name, c.name LIMIT 20` |
| C7 | 出场章回 | `MATCH (p:Entity {id:'诸葛亮'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation = 'appearsIn' RETURN c.name LIMIT 25` |
| C8 | 跨类型（人物→势力） | `MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation = 'serves' RETURN p.name, p.entityType LIMIT 20` |
| C9 | 两实体之间的关系 | `MATCH (a:Entity {id:'刘备'})-[r:Entity_Entity]-(b:Entity {id:'诸葛亮'}) RETURN r.relation, r.relationLabel` |
| C10 | 亲属家族 + 中文标签 | `MATCH (e:Entity {id:'曹操'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily = '亲属' RETURN r.relationLabel, x.name LIMIT 20` |

### 5.4 实测输出（打印出来核对）

```text
实例   : sanguo-kg-rocksdb-edge
存储   : RocksDB-Edge  (.rocksdb/sanguo-kg-rocksdb-edge)
规模   : 1102 个点 / 2298 条边（数据集 1102 实体 / 2298 关系）
点 id  : 实体名本身（不做 hash、不分片），例如 曹操 / 魏 / 第49回
边     : 每条关系一条有向边 head --relation--> tail，边上带
         relation / xrelation / relationLabel(中文) / relationFamily(家族)

[Gremlin  1] 实体属性（点查）            -> 4 条: 曹操 | Male | 男 | 孟德
[Gremlin  2] 一个人的全部关系            -> 180 条: 边(concubine/妾 -> 邹氏) | 边(son/儿子 -> 广宗殇公) | ...
[Gremlin  3] 邻居去重（社交圈）          -> 175 条: 点(邹氏) | 点(广宗殇公) | 点(安阳公主) | ...
[Gremlin  4] 按关系过滤（主公）          -> 22 条: 边(lord -> 钟会) | 边(lord -> 郭嘉) | 边(lord -> 邓艾) | ...
[Gremlin  5] 一跳：曹操手下              -> 22 条: 点(钟会) | 点(郭嘉) | 点(邓艾) | 点(贾诩) | ...
[Gremlin  6] 反向：刘备的臣属            -> 16 条: 点(黄忠) | 点(魏延) | 点(马超) | 点(马良) | ...
[Gremlin  7] 师承：姜维的师傅            -> 1 条: 点(诸葛亮)
[Gremlin  8] 按关系家族聚合              -> {婚姻=18, 亲属=41, 效力=2, 籍贯=1, 君臣=22, 官职=3, 出场=93}
[Gremlin  9] 两跳：曹嵩→曹操→曹昂        -> 2 条: 点(曹玹) | 点(曹上)
[Gremlin 10] 势力成员数：魏              -> 92  (标量)
[Gremlin 21] 子图（可视化）              -> 176 点 / 180 边

[Cypher   1] 人物本体（属性）            -> {e.name=曹操, e.entityType=Male, e.gender=男, e.courtesyName=孟德}
[Cypher   2] 出边 + 中文关系名            -> 20 行: {serves, 效力, 魏} | {serves, 效力, 东汉} | {same_clan, 同族, 夏侯渊} | ...
[Cypher   3] 按关系过滤（主公）          -> 20 行: {subject=钟会, relation=主公} | {subject=郭嘉, relation=主公} | ...
[Cypher   4] 反向：马谡的主公            -> 1 行: {lord=刘备, relation=臣}
[Cypher   5] 按关系家族聚合              -> 7 行: {出场, 93} | {君臣, 22} | {婚姻, 15} | ...
[Cypher   6] 两跳邻居                    -> 20 行: {middle=曹操, twoHop=魏} | {middle=曹操, twoHop=东汉} | ...
[Cypher   7] 出场章回（诸葛亮）          -> 25 行: 第9回 | 第99回 | 第98回 | ...
[Cypher   8] 谁效力魏                    -> 20 行: {郑小同, Male} | {赵达, Male} | {赵咨, Male} | ...
[Cypher   9] 刘备 ↔ 诸葛亮 的关系         -> 1 行: {relation=lord, relationLabel=主公}
[Cypher  10] 曹操的子女（亲属家族）      -> 20 行: {儿子, 广宗殇公} | {儿子, 安阳公主} | {女儿, 清河公主} | ...
```

### 5.5 两种存储的答案一致性（`SanguoRocksDbEdgeTest#matchesTheAerospikeGraphOnTheSharedQuestions`）

同一批问题同时发给两个实例（Aerospike 版把点 id 拼成 `name_shard`），答案必须相同：

| 问题 | Aerospike | RocksDB-Edge | 一致 |
|---|---|---|---|
| 曹操 的主公出边 (lord) | 22 | 22 | ✅ |
| 魏 的效力入边 (serves) | 92 | 92 | ✅ |
| 诸葛亮 的出场章回 (appearsIn) | 78 | 78 | ✅ |
| 曹嵩 的父亲出边 (father) | 1 | 1 | ✅ |

> 总边数不同是**故意的**：Aerospike 版 2,447 条（多出的 149 条是按本体物化的逆/对称边，
> 因为按 relation 分片时逆边会落到另一个点的记录里）；RocksDB-Edge 把一条边同时挂在
> 源点出表和目标点入表，所以 2,298 条源关系就够，`bothE()` 和 `relation \|\| xrelation`
> 的语义完全一样。

### 5.6 服务与 UI

`application.yml` 的 `graphs` 里已挂上 `sanguo-kg-rocksdb-edge`；
`GET /api/v1/graphs` 现在返回 6 个图，其中两个 RocksDB-Edge：

```text
payment-graph                [Aerospike]
payment-graph-as-edge        [Aerospike-Edge]
payment-graph-rocksdb-edge   [RocksDB-Edge]
kg-entity-graph              [Aerospike]
sanguo-kg                    [Aerospike]
sanguo-kg-rocksdb-edge       [RocksDB-Edge]
```

UI（`http://localhost:8080/`）下拉框选 `sanguo-kg-rocksdb-edge`，Gremlin 或 Cypher 例句
里就是上面这 20 条（`EXAMPLES['sanguo-kg-rocksdb-edge']`）。

---

### 5.7 十个 Cypher 语句 → Gremlin 等价写法

`SanguoRocksDbQueries.CYPHER_TO_GREMLIN`（10 条）把 §5.3 的 Cypher 全部翻译成 Gremlin，
`SanguoRocksDbEdgeTest#convertsTheTenCypherStatementsToGremlin` 两种语言都跑一遍、并排打印。

两个语言习惯不同之处：

- 本引擎 Gremlin **没有 `as()` / `select()`**，所以 Cypher 里“同时返回边和邻居名”的一行，
  Gremlin 要分两条：`elementMap()` 拿边（含 `relation`/`relationLabel`），`otherV().values('name')` 拿邻居名；
- `path('name')` 返回整条走链 `[起点, 边, 中间点, 边, 终点]`（边渲染为 `null`），
  它就是 Cypher `RETURN b.name, c.name` 两列的来源；
- `groupCount().by('k')` 返回一个 map，Cypher 返回排好序的行。

| # | 用途 | Cypher | Gremlin | 说明 |
|---|---|---|---|---|
| 1 | ① 点查：实体属性 | `MATCH (e:Entity {id:'曹操'}) RETURN e.name, e.entityType, e.gender, e.courtesyName` | `g.V('曹操','Entity').values('name','entityType','gender','courtesyName')` | 一一对应 |
| 2 | ② 出边关系 + 中文关系名 | `MATCH (e:Entity {id:'曹操'})-[r:Entity_Entity]->(x:Entity) RETURN r.relation, r.relationLabel, x.name LIMIT 20` | `g.V('曹操','Entity').outE('Entity_Entity').limit(20).elementMap()` | 无 `as/select`，邻居名另查 `...otherV().values('name')` |
| 3 | ③ 按关系过滤 + 邻居名 | `... WHERE r.relation = 'lord' RETURN x.name, r.relationLabel LIMIT 20` | `g.V('曹操','Entity').outE('Entity_Entity').filter(relation.equals('lord')).limit(20).otherV().values('name')` | `filter` 下推到存储；去 `limit` 就是全部 22 条 |
| 4 | ④ 反向关系（xrelation） | `MATCH (e {id:'马谡'})-[r:Entity_Entity]->(x:Entity) WHERE r.xrelation = 'lord' RETURN x.name` | `g.V('马谡','Entity').outE('Entity_Entity').filter(xrelation.equals('lord')).otherV().values('name')` | `xrelation` 同样可下推 |
| 5 | ⑤ 按关系家族聚合 | `... RETURN r.relationFamily AS family, count(*) AS n ORDER BY n DESC` | `g.V('曹操','Entity').outE('Entity_Entity').groupCount().by('relationFamily')` | map vs 行 |
| 6 | ⑥ 两跳 + 中间点 | `MATCH (a {id:'曹嵩'})-[:Entity_Entity]->(b)-[:Entity_Entity]->(c) RETURN DISTINCT b.name, c.name LIMIT 20` | `g.V('曹嵩','Entity').out('Entity_Entity').out('Entity_Entity').dedup().path('name').limit(20)` | 走链里边为 `null` |
| 7 | ⑦ 出场章回 | `MATCH (p {id:'诸葛亮'})-[r:Entity_Entity]->(c:Entity) WHERE r.relation='appearsIn' RETURN c.name LIMIT 25` | `g.V('诸葛亮','Entity').outE('Entity_Entity').filter(relation.equals('appearsIn')).limit(25).otherV().values('name')` | 一一对应 |
| 8 | ⑧ 入边（谁效力魏） | `MATCH (p:Entity)-[r:Entity_Entity]->(f:Entity {id:'魏'}) WHERE r.relation='serves' RETURN p.name, p.entityType LIMIT 20` | `g.V('魏','Entity').inE('Entity_Entity').filter(relation.equals('serves')).limit(20).otherV().valueMap('name','entityType')` | 从势力端起 `inE()` |
| 9 | ⑨ 两实体之间的关系 | `MATCH (a {id:'刘备'})-[r:Entity_Entity]-(b {id:'诸葛亮'}) RETURN r.relation, r.relationLabel` | `g.V('刘备','Entity').bothE('Entity_Entity').where(otherV().hasId('诸葛亮')).values('relation','relationLabel')` | 用 `where(child)` 表达“对端是谁” |
| 10 | ⑩ 亲属家族入边 + 中文标签 | `MATCH (e {id:'曹操'})<-[r:Entity_Entity]-(x:Entity) WHERE r.relationFamily='亲属' RETURN r.relationLabel, x.name LIMIT 20` | `g.V('曹操','Entity').inE('Entity_Entity').filter(relationFamily.equals('亲属')).limit(20).elementMap()` | `elementMap()` 带出 `relationLabel` |

实测对照（同一张 `sanguo-kg-rocksdb-edge` 图）：

```text
③ 曹操的主公关系   Cypher 20 行 {subject=钟会, relation=主公}...  |  Gremlin 22 条 钟会|郭嘉|邓艾|...
④ 马谡的主公         Cypher 1 行 {lord=刘备, relation=臣}        |  Gremlin 1 条 刘备
⑤ 关系家族           Cypher 7 行 {出场,93}{君臣,22}{婚姻,15}...   |  Gremlin map 7 组 {出场=93, 君臣=22, ...}
⑥ 曹嵩两跳           Cypher 20 行 {middle=曹操, twoHop=魏}...     |  Gremlin 20 条 [曹嵩,null,曹操,null,魏]...
⑨ 刘备↔诸葛亮        Cypher 1 行 {relation=lord, label=主公}      |  Gremlin 2 条 lord|主公
```

### 5.8 多跳（两跳 / 三跳）关系查询

`SanguoRocksDbQueries.MULTI_HOP`（12 条）：关系链、共同邻居、同势力同僚、同回出场、
路径强度、可达性、三跳环、跨势力三跳、祖孙三代、混合关系链。
`SanguoRocksDbEdgeTest#answersTheMultiHopRelationQueries` 两种语言都跑并打印。

**两跳（7 条）**

| # | 用途 | Gremlin | Cypher |
|---|---|---|---|
| 1 | 二度人脉：刘备两跳邻居数 | `g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').dedup().count()` | `MATCH (a:Entity {id:'刘备'})-[:Entity_Entity]-(b)-[:Entity_Entity]-(c) RETURN count(DISTINCT c.name) AS n` |
| 2 | 指定关系链：刘备—主公→诸葛亮—师傅→姜维 | `...outE().filter(relation.equals('lord')).otherV().outE().filter(relation.equals('master')).otherV().dedup().values('name')` | `...-[r1]->(b)-[r2]->(c) WHERE r1.relation='lord' AND r2.relation='master' RETURN b.name, c.name` |
| 3 | 共同邻居：刘备与曹操 | `g.V('刘备','Entity').both('Entity_Entity').dedup().where(both('Entity_Entity').hasId('曹操')).values('name')` | `MATCH (a {id:'刘备'})-[:Entity_Entity]-(m)-[:Entity_Entity]-(b {id:'曹操'}) RETURN DISTINCT m.name` |
| 4 | 同势力同僚：徐庶的同僚数 | `...outE().filter(relation.equals('serves')).otherV().inE().filter(relation.equals('serves')).otherV().dedup().count()` | `MATCH (a {id:'徐庶'})-[r1]->(f)<-[r2]-(b) WHERE r1.relation='serves' AND r2.relation='serves' RETURN count(DISTINCT b.name)` |
| 5 | 同回出场：诸葛亮同回人数 | `...outE().filter(relation.equals('appearsIn')).otherV().inE().filter(relation.equals('appearsIn')).otherV().dedup().count()` | `MATCH (a {id:'诸葛亮'})-[r1]->(ch)<-[r2]-(b) WHERE r1.relation='appearsIn' AND r2.relation='appearsIn' RETURN count(DISTINCT b.name)` |
| 6 | 路径强度：刘备两跳能到谁 | `g.V('刘备','Entity').both('Entity_Entity').both('Entity_Entity').groupCount().by('name')` | `... RETURN c.name AS name, count(*) AS paths ORDER BY paths DESC LIMIT 10` |
| 7 | 可达性：刘备两跳到曹操 | `g.V('刘备','Entity').repeat(both('Entity_Entity')).times(2).hasId('曹操').count()` | `MATCH (a {id:'刘备'})-[:Entity_Entity]-(b)-[:Entity_Entity]-(c {id:'曹操'}) RETURN count(*) AS paths` |

**三跳（5 条）**

| # | 用途 | Gremlin | Cypher |
|---|---|---|---|
| 8 | 路径数：刘备三跳到曹操 | `g.V('刘备','Entity').repeat(both('Entity_Entity')).times(3).hasId('曹操').count()` | `MATCH (a {id:'刘备'})-[:E]-(b)-[:E]-(c)-[:E]-(d {id:'曹操'}) RETURN count(*) AS paths` |
| 9 | 三跳环：刘备出发绕回自己 | `...both().both().both().cyclicPath().path('name').limit(5)` | `MATCH (a {id:'刘备'})-[:E]-(b)-[:E]-(c)-[:E]-(a) RETURN DISTINCT b.name, c.name LIMIT 5` |
| 10 | 跨势力：从魏三跳到蜀（条数） | `g.V('魏','Entity').in('Entity_Entity').out('Entity_Entity').out('Entity_Entity').hasId('蜀').count()` | `MATCH (a {id:'魏'})<-[:E]-(p)-[:E]->(q)-[:E]->(r {id:'蜀'}) RETURN count(*) AS paths` |
| 11 | 祖孙三代：司马懿→司马昭→司马炎 | `g.V('司马懿','Entity').inE().filter(relation.equals('son')).otherV().inE().filter(relation.equals('son')).otherV().dedup().values('name')` | `MATCH (a {id:'司马懿'})<-[r1]-(s)<-[r2]-(g) WHERE r1.relation='son' AND r2.relation='son' RETURN DISTINCT g.name` |
| 12 | 混合关系链：曹操—主公→司马懿←儿子←司马昭←儿子←司马炎 | `...outE().filter(relation.equals('lord')).otherV().hasId('司马懿').inE().filter(relation.equals('son')).otherV().hasId('司马昭').inE().filter(relation.equals('son')).otherV().dedup().values('name')` | `MATCH (a {id:'曹操'})-[:E]->(b {id:'司马懿'})<-[:E]-(s {id:'司马昭'})<-[:E]-(g) RETURN DISTINCT g.name` |

实测结果（打印输出）：

```text
[ 1] 两跳 · 二度人脉            Gremlin 202 | Cypher 202
[ 2] 两跳 · 关系链 刘备→诸葛亮→姜维  Gremlin 姜维 | Cypher {mid=诸葛亮, end=姜维}
[ 3] 两跳 · 共同邻居            Gremlin 徐庶 | Cypher {m.name=徐庶}
[ 4] 两跳 · 同势力同僚          Gremlin 124 | Cypher 124
[ 5] 两跳 · 同回出场            Gremlin 46 | Cypher 46
[ 6] 两跳 · 路径强度            Gremlin Top6 {刘备=23, 蜀=9, 第65回=8, 第60回=8, 第81回=7, 第73回=7}
                                Cypher Top {刘备 23}{蜀 9}{第65回 8}...
[ 7] 两跳 · 可达性              Gremlin 1 | Cypher 1
[ 8] 三跳 · 路径数              Gremlin 295 | Cypher 295
[ 9] 三跳 · 三跳环              Gremlin [刘备,null,张飞,null,敬哀皇后,null,张飞]...
                                Cypher {b=张飞, c=敬哀皇后}{b=张飞, c=刘备}...
[10] 三跳 · 跨势力              魏→蜀 3 条
[11] 三跳 · 祖孙三代            司马炎
[12] 三跳 · 混合关系链          司马炎
```

两种语言的计数对照（测试里的断言，`agree()`）：

```text
1  刘备的两跳邻居数        Gremlin 202  Cypher 202  一致
4  徐庶的同僚数           Gremlin 124  Cypher 124  一致
5  诸葛亮同回出场人数       Gremlin 46   Cypher 46   一致
7  刘备两跳到曹操的路径数    Gremlin 1    Cypher 1    一致
8  刘备三跳到曹操的路径数    Gremlin 295  Cypher 295  一致
10 魏三跳到蜀的路径数       Gremlin 3    Cypher 3    一致
11 司马懿的孙子数          Gremlin 1    Cypher 1    一致
```

UI 例句（两份 `app.js` 的 `EXAMPLES['sanguo-kg-rocksdb-edge']`）也加了这 7 条两跳/三跳；
Markdown 表由测试生成：`target/sanguo-rdf/CYPHER-TO-GREMLIN.md` 与 `MULTI-HOP-QUERIES.md`。

### 5.9 查询手册（单文件）

所有查询汇总在一个文件：[SANGUO-KG-QUERIES.md](SANGUO-KG-QUERIES.md)（38 KB）。

| 分组 | 条数 | 说明 |
|---|---|---|
| 问答式（Q1–Q22） | 22 | 自然语言问题 + Gremlin + Cypher + **实测答案** |
| 多跳（两跳/三跳） | 12 | 关系链、共同邻居、同势力、同回出场、路径强度、可达性、三跳环 |
| 典型查询 | 20 | Gremlin 10 + Cypher 10 |
| Cypher → Gremlin 转换 | 10 | 两种写法对照 |

问答式的例子：

| 问题 | 实测答案 |
|---|---|
| 关羽和曹操是什么关系？ | 没有直接边；共同势力 = 魏、东汉（“降汉不降曹”） |
| 关羽和曹操共同出场过多少回？ | 58 回 |
| 刘备和曹操是什么关系？ | 没有直接边；共同部下 = 徐庶 |
| 刘备、曹操之外的第三国主公是谁？ | 孙权（吴） |
| 三国三大主公分别是谁？ | 按部下数：曹操 22、孙权 16、刘备 15（即魏/吴/蜀） |
| 刘备的手下有谁是从曹操那边过来的？ | 徐庶 |
| 谁换过主公？ | 张辽（吕布→曹操）、徐庶（刘备→曹操） |
| 张辽是从谁那里投奔曹操的？ | 吕布 |
| 谁效力过的势力最多？ | 马超（4 个：东汉、马腾势力、蜀、张鲁） |
| 曹操的同族有谁？ | 夏侯渊、夏侯淳、曹仁 |
| 诸葛亮的哥哥在哪个国家？ | 诸葛瑾，在吴国（孙权的臣） |
| 谁出场章回最多？ | 曹操 93、诸葛亮 78、关羽 65 |
| 哪一回出场的人最多？ | 第56回 18 人、第60回 15 人 |
| 诸葛亮和司马懿相隔多远？ | 2 跳（27 条路，路径多数经过章回） |

文件开头是两份**一行一条**的纯查询清单，方便直接复制；同样内容也单独存为
`sanguo-queries.gremlin` 与 `sanguo-queries.cypher`。整份手册由
`SanguoRocksDbEdgeTest#writesTheQueryCookbook` 把目录里每一条查询跑一遍后生成（带断言），
所以手册里的“实测答案”就是数据集的真实回答；重新生成：

```bash
./mvnw -o -pl graph-integration-test test -Dtest=SanguoRocksDbEdgeTest \
       -Dsurefire.failIfNoSpecifiedTests=false -Dsanguo.queries.write=true
```

> 注意：本引擎的 Cypher **必须有锚点**（`MATCH` 里至少一个 `{id:...}`），所以需要“扫全 `Entity` 类型”
> 的聚合问题（三国主公排行、势力排行、章回排行）只有 Gremlin 版本：`g.V('Entity')` 是支持的类型扫描，
> 配合 `groupCount().by('name')` 就能按“到达的对端”聚合。

---

## 7. 方案 C：从文档自由文本抽取（新图 `sanguo-kg-text`）

前六节的解析只读人物文档的 **9 个结构化字段**；方案 C 去读被忽略的两列 —— `历史`（历史简介）和 `小说`（演义简介）。
结果是**又一张图**：`sanguo-kg-text`（也是 **RocksDB-Edge**，点 id = 实体名）。

### 7.1 两张图的规模

| | base（`sanguo-kg-rocksdb-edge`） | **text（`sanguo-kg-text`）** | 增量 |
|---|---|---|---|
| 实体 | 1,102 | **1,135** | **+33** |
| 关系 | 2,298 | **3,257** | **+959** |
| 物化边 | 2,447 | 3,422 | +975 |
| 关系家族 | 12 | 14（多 `提及`、`冲突`） | +2 |

### 7.2 抽取规则（词典 + 正则，不用模型，可复现）

| 关系 | 中文 | 怎么认 | 条数 | 新实体 |
|---|---|---|---|---|
| `mentions` | 提及 | 句子里出现**已知实体名**（最长匹配，排除本人） | **902** | 无 |
| `holds` | 官职 | `为/拜/封/迁/领/任/加/授/表/除 + 职衔`（职衔不能含已知实体名，后面不能紧跟人名） + 专门的「五虎」规则 | 22 | ✅ 18 个新职衔 |
| `fought` | 交战 | `与X大战/交战/决战/战`、`大破/击败/大败/攻破X`（若匹配前是人名则丢弃，避开远程监督误判） | 18 | ✅ |
| `killed` | 斩杀 | `为X所杀`（X 在前）、`斩/诛/斩杀/刺杀/杀死/诱杀X` | 17 | ✅ |
| `strategistOf` | 军师 | 以词典锚定：`<已知人物>辟为军师` / `<已知人物>之军师` / `投效与X…军师` | 3 | ❌ |
| `advisorOf` | 谋士 | `<已知人物>谋士<名>`（名是谋士）/ `<已知人物>谋士`（本人是谋士） | 1 | ✅ |

**单字简称还原**：传记里会用单字代称（「忠与**羽**连战三日」= 黄忠 vs 关羽）。`SanguoTextExtractor.ALIAS`
把 `羽/飞/备/操/权/亮/懿/维/超/忠/云/布/绍/术/策/瑜/蒙/逊/盖/统` 还原成全名，仅在后面跟的是动词时生效。

### 7.3 新发现

**新实体（共 33 个，其中 25 个是关系槽引入的）：**

- **18 个职衔**：襄阳太守、上党王、鲜卑王、都亭侯、平阳乡侯、太子辅义都尉、长安郡守、隃麋侯、河间王、陷阵都尉、
  广昌亭侯、都官从事、钜鹿太守、巴西太守、长沙王、博平乡侯、河阴令、东武亭侯
- **7 个新人物**：颜良、文丑（关羽斩的）、华雄、麹义、章邯、吕旷、杨怀、杨骏 —— 都不在上游的 331 篇文档里

**新关系（共 963 条）** + 两种新关系类型：`提及`(提及)、`冲突`(交战/斩杀)。

### 7.4 方案 C 能回答的问题（实测，base 图答不了）

| 问题 | 查询 | base 图 | **text 图** |
|---|---|---|---|
| 谁是五虎上将？ | `g.V('五虎上将','Entity').inE().filter(relation.equals('holds')).otherV().values('name')` | 5 人（方案 B 手工加的） | **5 人（方案 C 自动抽到）** |
| 谁是五虎将之首？ | `g.V('五虎上将之首','Entity').inE().filter(relation.equals('holds')).otherV().values('name')` | 关羽 | **关羽** |
| 刘备的军师是谁？ | `g.V('刘备','Entity').inE().filter(relation.equals('strategistOf')).otherV().values('name')` | **0 条** | **徐庶、庞统** |
| 诸葛亮是刘备的军师吗？ | 同上换成 `诸葛亮` | — | **0 条（数据说不是，见 §9）** |
| 曹操的谋士是谁？ | `g.V('曹操','Entity').inE().filter(relation.equals('advisorOf')).otherV().values('name')` | 0 条 | **司马懿** |
| 关羽斩杀过谁？ | `g.V('关羽','Entity').outE().filter(relation.equals('killed')).otherV().values('name')` | 0 条 | **颜良、文丑、华雄** |
| 谁斩杀了关羽？ | `g.V('关羽','Entity').inE().filter(relation.equals('killed')).otherV().values('name')` | 0 条 | **孙权** |
| 关羽的传记提到了谁？ | `g.V('关羽','Entity').outE().filter(relation.equals('mentions')).limit(10).otherV().values('name')` | 0 条 | **诸葛亮、曹操、文丑、张飞、孙权、周瑜、吕蒙、吕布、…** |
| 关羽与马超的关系？ | 共同职位 / 共同主公 | 五虎上将 / 刘备 | 五虎上将 / 刘备 |

> 「五虎上将」这条特别值得看：**方案 B 是人工把句子翻成边，方案 C 是自动读出来了** —— 两者在 text 图里对同一条边去重，
> 但 `SanguoTextGraphTest` 会断言**抽取器本身**已经找到了它（不依赖手工补充）。

### 7.5 装载与查询

```bash
./mvnw -o -pl graph-integration-test test -Dtest=SanguoTextGraphTest \
       -Dsurefire.failIfNoSpecifiedTests=false -Dsanguo.queries.write=true
# 或只装载服务要读的那份（先停服务）
SANGUO_ROCKSDB_RESEED=true ./mvnw -o -pl graph-integration-test test -Dtest=SanguoTextGraphTest#loadsTheInstanceTheServiceServes
```

实测：**1,135 点 / 3,257 边**（base 为 1,102 / 2,298）；目录 `.rocksdb/sanguo-kg-text`；
`application.yml` 已挂上，UI 下拉里能选（`GET /api/v1/graphs` 返回 7 个图）。

UI 例句（`EXAMPLES['sanguo-kg-text']`）里可以直接点：五虎上将、刘备的军师、关羽斩杀过谁、关羽的传记提到谁。

### 7.6 抽取报告（可按行核对）

`SanguoTextGraphTest#writesTheExtractionReport` 会写出 **[SANGUO-KG-TEXT-EXTRACTION.md](SANGUO-KG-TEXT-EXTRACTION.md)**：
规模对比、每个规则的条数与样本、新增实体清单、以及每条规则的 8 条带 provenance 的命中（`文档:字段 «原句»`）。

### 7.7 已知局限（写在报告里）

- `mentions` 只匹配**已知实体名**（词典）→ 零幻觉，但不会发现新实体。
- `holds`/`fought`/`killed` 是**远程监督**：句子的主语未必是文档本人（例：李邵的传记里写“袁绍与公孙瓒战”，这条会被规则丢弃，
  但不是所有句子都能这么处理）—— 所以计数是**上界**，provenance 保留下来供人工核对。
- 地点抽取刻意没做（没有词典会太脏）；`军师`/`谋士` 只连**人物**。
- 新人物名来自关系槽，已过滤掉 `曹将`/数字/官衔/地名结尾/否定词，但仍可能有残留噪声。

---

## 8. 复现 / 已知限制

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-26.0.2.1"
./tools/download-sanguo-dataset.sh                 # 1. 下载文档数据集
./mvnw -o -pl graph-integration-test -am test \
       -Dtest='Sanguo*Test' -Dsurefire.failIfNoSpecifiedTests=false   # 解析+校验+播种+查询
./mvnw -o -pl graph-integration-test test -Dtest=SanguoRocksDbEdgeTest \
       -Dsurefire.failIfNoSpecifiedTests=false                        # RocksDB-Edge：20 查询 + Cypher→Gremlin + 多跳（均打印）
tools/start-phoenixgraph.sh --with-hnsw            # 重启服务，UI 里选 sanguo-kg / sanguo-kg-rocksdb-edge
```

已知限制（都是数据本身的性质，不是解析错误）：

- **414 个 `Person` 没有文档**（上游导出只覆盖部分人物），它们的性别无法校验，
  所以 `domain`/`range` 报告 523 条"无法判定"；`son`/`brother`/`husband` 这类按性别分的关系对
  无文档的人也一律按男性处理（例如曹操的孩子里没有文档的会被记成 `son`）。
- **`刘备`、`关羽`、`张飞` 的文档不在导出里**（只作为 curated 三元组的端点出现），所以他们的
  配偶/子女关系只有 curated 文件里那几条。
- **9 个分片有边、1 个为空**，空分片上的点永远查不到——这是 relation 分片的正常现象。
- `relatives`/`family`/`brother_and_sister` 这些**抽象父关系没有实例边**，它们只在本体层次里用；
  查询用粗粒度的 `relationFamily` 或具体的子关系。

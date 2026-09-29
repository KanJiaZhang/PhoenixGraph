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

> **重要补充（数据集原来是截断的）**：本仓库一直用的 `renwu_process_v1.csv.gz` 只有上游的**前 331 行、且截断在半行**
> （`675,袁胤,…,豫州汝南郡汝阳(河南周口市` 没有收尾）。`curl` 被中断写出的仍是合法 gzip，所以所有测试都通过。
> 上游同一文件实际有 **4,487 人**。补齐后的对比：
>
> | | 旧（331 人） | **新（4,487 人）** | 倍数 |
> |---|---|---|---|
> | 人物文档 | 331 | **4,487** | 13.6× |
> | 实体（base） | 1,102 | **6,202** | 5.6× |
> | 关系（base，源方向） | 2,298 | **21,050** | 9.2× |
> | 物化关系（base） | 2,447 | **21,162** | 8.6× |
> | RDF 三元组（ABox） | 4,946 | **46,632** | 9.4× |
> | Male / Female | 315 / 16 | **4,241 / 246** | |
> | Place / Position / Faction | 128 / 92 / 17 | **479 / 849 / 30** | |
> | Person（仅关系端点） | 414 | **237**（↓ 主要人物都有传记了） | |
> | domain/range 检查 | 3,525 | **35,707** | |
>
> **补回来的是谁**：**刘备**（上游的排序使他正好落在截断点之后），**整个曹操谋士群**
> （郭嘉、荀彧、荀攸、贾诩、刘晔、董昭、蒋济、满宠、吕虔、毛玠、文聘），
> 以及**过五关斩六将的六将**（孔秀、韩福、孟坦、卞喜、王植、秦琪）和蔡阳、胡班、普净、郭常。
>
> 下载脚本现在会**校验行数与结尾换行**（`4,488 / 154 / 823`），截断会直接报错而不是静默缩小数据集。

### 0.1 补齐后暴露并修掉的四个真 bug

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `无` 被建成 Position 实体，产生 8 条 domain 违规 | 上游用「无」表示空，而 `PLACEHOLDERS` 只认 `未知/暂无相关记载/？` | `PLACEHOLDERS` += `无`、`不详` |
| 2 | `宣阳乡君 --holds--> 宣阳乡君` 自环 | 该人的官职与名字相同 | `deriveRelations` 丢弃自环 |
| 3 | `刘协 --husband--> 曹节` 指到**宦官**曹节（男） | 上游用 `名字[势力]` 区分 359 个同名人，而解析器把 `[……]` **剥掉**了 | 后缀**保留在实体名里**，并建引用表（`曹节[献穆皇后]` → 皇后） |
| 4 | `曹茂` 的兄弟变成一个由 24 个名字拼接成的实体 | 上游**一个单元格里空格与 `|` 混用**（`曹昂 曹丕 … 曹节[魏] 曹华\|曹植\|…`） | 分割符改为 `[\|\s　]+` |

四个都修完后，本体校验从 **11 条违规降到 1 条**；剩下那条是**上游真数据错误**：
`刘建[安国侯]` 的母亲字段写的是男性 `刘英`。它不再被过滤，而是**逐字断言**（`SanguoRdfTest` / `SanguoTextGraphTest`），
这样将来出现**新的**违规依旧会让构建失败。

### 0.2 补齐后新增的两道精度闸（文本抽取）

1. **意图不是事实** —— `种辑` 的传记写「俱受献帝密诏**诛曹操**…事泄，为操所杀」：`密诏` 是**没成功的密谋**。
   主动式 `斩/诛/杀…` 现在加否定前瞻，排除 `当|欲|谋|图|拟|密|诏|请|劝|谏|令` 前缀。
   修前 `killed` 出现双向（曹操↔种辑/吴硕/吴子兰）触发 `owl:AsymmetricProperty` 违规 6 条，修后 0。
2. **`X军师Y` 里 Y 才是军师** —— `邓贤[刘璋]` 的传记写「后刘备军师**庞统**命魏延于宴中舞剑」，
   旧规则把**传记主人**当成刘备的军师。新增 `STRATEGIST_AFTER`：军师后面跟人名时取那个人。
   五虎规则同时改为取**被册封的人**（`封关羽为五虎大将之首`），并对引号句跳过 ——
   否则 `费诗`（送封号的使者，传记写 `封"五虎上将"`）会变成第六个五虎将。

### 0.3 已知噪声（完整数据集暴露，逐条保留 provenance 供核对）

- `killed` 的动词表较宽，`斩首万级`/`获三千级` 会抽出 `斩杀`/`首万级` 这类碎片人名
  （已加 `BAD_NAME_HEAD` 过滤）；
- `holds` 的 `为/领/命/请 + 职衔` 会抽到 `仲爽为令`、`先主为汉中王` 这类从句
  （已加 `plausibleTitle` 的长度与虚词过滤）。

**同仓库里被我们刻意不用的文件**（避免下次再找一遍）：`renwu_relate.csv` 虽有 25,091 行，
但 **60.6% 的宾语是 `未知`**、关系类型只有 `cata/cated/father/mother/brother/mate` ——
它就是本文档字段（`cata`=主效、`cated`=效力）的扁平化重编码，**没有增量**。
（另有 `renwu.pkl`/`sgyy.pkl`/`renwu.zip` 是同一批记录的爬取中间件；`红楼梦/水浒传/西游记` 是别的小说。）

### 0.4 数据集来源

| | |
|---|---|
| 数据集 | 四大名著人物关系知识图谱和 OWL 本体 —《三国演义》部分 |
| 发布方 | OpenKG（`http://openkg.cn/dataset/ch4masterpieces`） |
| 使用的镜像 | [`wcswcswcs/sgyy`](https://github.com/wcswcswcs/sgyy) |
| 本地位置 | `graph-integration-test/src/test/resources/kg/sanguo/`（gzip，约 80 KB） |

三个文件，都是"文档"：

| 文件 | 内容 |
|---|---|
| `renwu_process_v1.csv.gz` | **4,487 篇人物文档**：`编号,名字,性别,字号,年龄,正史,籍贯,父亲,母亲,兄弟,配偶,孩子,官职,主效,效力,总年数,年代列表,总章节,章节列表,历史,小说,头像,链接,效力数,配偶数` |
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

一个点类型 `Entity`，用 `entityType` 区分 7 个类（text 图另有 `Event`/`Artifact`/`Idiom` 三个，见 §7.2.2）：

| `entityType` | 中文 | 来源 | 数量 |
|---|---|---|---|
| `Male` | 男性人物 | 人物文档（`性别=男`） | 4,241 |
| `Female` | 女性人物 | 人物文档（`性别=女`） | 246 |
| `Person` | 人物（无文档） | 只作为关系端点出现的人（关系里有、但没有传记；完整数据集下只剩 237 个） | 237 |
| `Faction` | 势力 | `效力` 字段：魏/蜀/吴/东汉/西晋/少数民族/在野/其他/起义军… | 30 |
| `Place` | 籍贯地 | `籍贯` 字段，取 `州郡(现代地名)` 的历史部分 | 479 |
| `Position` | 官职 | `官职` 字段（`|`/空格 分隔，去掉 `谥曰…`）+ 补充的「五虎上将」「五虎上将之首」 | 849 |
| `Chapter` | 章回 | `章节列表` 字段（`第1回`…`第120回`） | 120 |

合计 **6,202 个实体**（其中两个 `Position` 来自本地补充：`五虎上将`、`五虎上将之首`）。`Male`/`Female` 在 TBox 里是 `Person` 的子类。

### 1.2 关系拆分（relation split）

- **60 个本体关系**：直接来自 `theory.owl`（`adopted_son`、`brother`、`concubine`、`elder_brother`、
  `father`、`lord`、`master`、`same_clan`、`sworn`…），保留中文 `rdfs:label`、`rdfs:subPropertyOf`
  层次、`rdfs:domain`/`rdfs:range`、OWL 特性（`Asymmetric` / `Irreflexive` / `Symmetric` / `Transitive` / …）。
- **4 个文档属性关系**（本体没有，由 `SanguoOntology.EXTENSION_PROPERTIES` 声明，所以 ABox 的每个
  谓词都在 TBox 里）：`serves`（效力）、`holds`（官职）、`nativePlace`（籍贯）、`appearsIn`（出场）。
- **4 个文档数据类型属性**（`owl:DatatypeProperty`）：`courtesyName`（字）、`historicity`（正史与虚构）、
  `historicalDescription`（历史简介）、`novelDescription`（演义简介）。
- **11 个粗粒度"关系家族" `relationFamily`**：把 60+ 个关系归到 `亲属 / 婚姻 / 朋友 / 君臣 / 师承 /
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
6,202 个实体 → **11,418 条点记录**（同一人物在它参与的每个分片里各存一份，实测 9.2 s 播完），21,162 条边。
10 个分片里 **9 个有边**（没有一个关系的 hash 落在剩下那个分片上）。

代价与 `kg-entity-graph` 相同：一个人物存在于多个分片，所以"刘备的全部关系"要读它的所有分片
（`g.V('刘备_3','刘备_6','刘备_7','刘备_8','刘备_9','Entity')`）。

### 3.3 播种

```bash
# 全量（11,418 点 / 21,162 边，8 线程实测 9.2 s；已播种会自动跳过，KG_RESEED=true 强制重播）
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
| 边 | 21,162 条（21,050 源关系 + 逆/对称闭包），存在点的 out/in bin 里 | **21,050 条源关系**，存在按边类分的边表里 |
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

实测：**6,202 点 / 21,050 边，1.7 s**。RocksDB 目录同一时刻只允许一个进程持有，所以
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

> 总边数不同是**故意的**：Aerospike 版 21,162 条（多出的 112 条是按本体物化的逆/对称边，
> 因为按 relation 分片时逆边会落到另一个点的记录里）；RocksDB-Edge 把一条边同时挂在
> 源点出表和目标点入表，所以 21,050 条源关系就够，`bothE()` 和 `relation \|\| xrelation`
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
| 实体 | 6,202 | **6,444** | **+242** |
| 关系 | 21,050 | **44,576** | **+23,526** |
| 物化边 | 21,162 | 44,820 | +23,658 |
| 关系家族 | 12 | 14（多 `提及`、`冲突`） | +2 |
| 实体类型 | 7 | 10（多 `Event`/`Artifact`/`Idiom`） | +3 |

### 7.2 抽取规则（词典 + 正则，不用模型，可复现）

| 关系 | 中文 | 怎么认 | 条数 | 新实体 |
|---|---|---|---|---|
| `mentions` | 提及 | 句子里出现**已知实体名**（最长匹配，排除本人） + **典故表**（方案 ③） | **22,951** | 方案 ③ 的事件/器物/成语 |
| `holds` | 官职 | `为/拜/封/迁/领/任/加/授/表/除 + 职衔`（职衔不能含已知实体名，后面不能紧跟人名） + 专门的「五虎」规则 | 22 | ✅ 18 个新职衔 |
| `killed` | 斩杀 | 被动：`为X所杀` / `被X（所）斩\|杀\|诛` → **X 是凶手**；主动：`斩/诛/斩杀/刺杀/杀死/诱杀X` → 文档本人是凶手 | **19** | ✅ |
| `fought` | 交战 | `与X大战/交战/决战/战`、`大破/击败/大败/攻破X`（若匹配前是人名则丢弃，避开远程监督误判） | 18 | ✅ |
| `strategistOf` | 军师 | 以词典锚定：`<已知人物>辟为军师` / `<已知人物>之军师` / `投效与X…军师` | 3 | ❌ |
| `advisorOf` | 谋士 | `<人物>(帐下/麾下/手下/部下/之/的)?谋士<名>`（名是谋士）/ `<人物>…谋士`（本人是谋士） | **2** | ✅ |

**转述/引语句不认主动式**：`韩馥曰：“吾有上将潘凤，可斩华雄。”` 只是一句**未然的转述**（`可` = 能够），
若按主动式会得到反向的 `潘凤 --killed--> 华雄`。所以句中带 `曰`/`云：`/引号时，**只保留语法方向确定的被动式**，
主动式连同 `fought` 一起跳过。同理 `被X斩` 要先把前置副词剥掉（`又被华雄斩` 不能变成一个人叫“又华雄”）。

**单字简称还原**：传记里会用单字代称（「忠与**羽**连战三日」= 黄忠 vs 关羽）。`SanguoTextExtractor.ALIAS`
把 `羽/飞/备/操/权/亮/懿/维/超/忠/云/布/绍/术/策/瑜/蒙/逊/盖/统` 还原成全名，仅在后面跟的是动词时生效。

### 7.3 新发现

**新实体（共 33 个，其中 25 个是关系槽引入的）：**

- **18 个职衔**：襄阳太守、上党王、鲜卑王、都亭侯、平阳乡侯、太子辅义都尉、长安郡守、隃麋侯、河间王、陷阵都尉、
  广昌亭侯、都官从事、钜鹿太守、巴西太守、长沙王、博平乡侯、河阴令、东武亭侯
- **75–103 个新人物**（噪声去掉前后的区间）：颜良、文丑、华雄、麹义、章邯、吕旷、杨怀、杨骏、刘勋、贾逵、王双、张华、李肃、呼延颢… —— 这些是关系槽带出的、上游没有传记的名字

**新关系（完整数据集下 23,530 条）** + 两种新关系类型：`提及`(提及)、`冲突`(交战/斩杀)。

> 合计：`mentions` 22,951 + `holds` 111 + `killed` 300 + `fought` 136 + `strategistOf` 5 + `advisorOf` 27 = **23,530 条**。

### 7.2.1 一个反面教材：「过五关斩六将」为什么不在 `关羽 --killed-->` 里

**结论在补齐数据集后变了**（见 §0）：六将**现在都有传记**，其中两条能被规则抽到。

1. **旧版 331 篇（截断）**：六将**从未被命名** —— 关羽的 `小说` 列只写成语「过五关斩六将」，
   `孔秀`/`韩福`/`孟坦`/`卞喜`/`王植`/`秦琪`/`蔡阳` 全库 0 篇。那时文本里确实没有可抽的实体。
2. **完整 4,487 篇**：六将**全都有传记**，且传记就在讲关羽杀他们。规则抽到了 2 条：
   - `卞喜` —— 「最后**被关羽斩杀**」（被动式）
   - `蔡阳` —— 「**为羽所杀**」（`为X所杀` 的单字简称，`ALIAS` 把「羽」还原成关羽）

   剩下 5 条是**文学化叙述**，需要指代消解而不是模式：`孔秀`「羽怒，举刀杀秀」、`韩福`「挥刀斩于马下」、
   `孟坦`「砍为两段」、`秦琪`「琪头落」、`王植`「被关公拦腰一刀，砍为两段」。
   它们作为节点留在图里，缺的是那条边 —— 这是方案 C 已知的边界，不是数据缺失。
2. **规则层**：`六将` **曾**被 `斩(2-4字)` 命中，但 `plausibleName` 以「数词开头 = 泛指」为由拒绝。
   早期版本确实生成过 `关羽 --killed--> 六将`，这就是加这条过滤的原因。

对照：`斩颜良`/`诛文丑`/`温酒斩华雄` 都是**具名**，所以抽得到；成语与数量词抽不到。

> 想要六将进图只有两条路：把它当**外部知识**手工补（像方案 B，要注明出处），
> 或把「过五关斩六将」建成**典故/事件实体**（`关羽 --mentions--> 过五关斩六将`）而不虚构六个名字。
> —— 后者已经做了，见 §7.2.2。

### 7.2.2 方案 ③：典故建成事件节点（不虚构参与者）

**做法**：一张**数据驱动**的典故表（每个条目都真的在语料里出现过 —— 完整数据集下 4,487 篇，**最长匹配优先**，
所以 `败走麦城` 不会退化成子串 `走麦城`），命中后发出 `传记主人 --mentions--> 典故`，
并给典故节点一个实体类型（新增 3 个类型，只在本图的 TBox 里声明）：

| 类型 | 中文 | 数量 | 典故 |
|---|---|---|---|
| `Event` | 事件 | **21** | 过五关斩六将、挟天子以令诸侯、温酒斩华雄、三英战吕布、刮骨疗伤、放水淹七军、三顾茅庐、舌战群儒、败走麦城、割须弃袍、六出祁山、七进七出、桃园结义、赤壁之战、官渡之战、夷陵之战、长阪之战、隆中对、借东风、八阵图、托孤 |
| `Idiom` | 成语 | **4** | 一身是胆、刮目相看、连环计、美人计 |
| `Artifact` | 器物 | **3** | 赤兔马、木牛流马、青龙偃月刀 |

**关键点**：`过五关斩六将` 成为一个节点，**六个将领的姓名一个也没有出现**——
测试断言 `孔秀`/`韩福`/`孟坦`/`卞喜`/`王植`/`秦琪`/`六将` **都不在实体表里**，
也断言 28 个典故名与 base 实体**零碰撞**。这就是方案 ③ 与方案 ② 的分界线：
前者是**抽取**（只记文档写了什么），后者是**引入外部知识**。

**实测查询**：

```gremlin
// 关羽的传记提到了哪些事件（TextP/within 现已实现，见 §7.9）
g.V('关羽','Entity').outE().filter(relation.equals('mentions')).otherV()
  .has('entityType','Event').values('name')
// → 长阪之战 过五关斩六将 赤壁之战 败走麦城 温酒斩华雄 桃园结义 放水淹七军 刮骨疗伤

// 谁的传记提到过五关斩六将
g.V('过五关斩六将','Entity').inE().filter(relation.equals('mentions')).otherV().values('name')
// → 郭常子 关羽

// 关羽的器物
g.V('关羽','Entity').outE().filter(relation.equals('mentions')).otherV()
  .has('entityType','Artifact').values('name')
// → 青龙偃月刀
```

其他抽得很准的例子：`诸葛亮 → 隆中对/八阵图/托孤/三顾茅庐/舌战群儒/借东风/六出祁山`、
`赵云 → 一身是胆/七进七出`、`曹操/袁绍 → 挟天子以令诸侯`、`吕布 → 连环计`、`董卓 → 赤兔马`。

**边界（写进报告）**：典故归到**传记主人**名下（与 `mentions` 一致）。
所以郭常子的传记写「关羽过五关斩六将」时，这条归给**郭常子**而不是关羽 ——
owner-scoped 抽取的固有边界，原句保留供核对。（关羽自己的传记也写了这句，所以 `关羽 --mentions--> 过五关斩六将` 仍然存在。）

### 7.2.3 两条「半条事实」：郭嘉 vs 司马懿

有人会问：**为什么郭嘉不是曹操的谋士，而司马懿是其谋士、却不是曹操的臣？**
后半个前提是**错的**（是查询方向问题），前半个是**真的**。两者正好说明
「curated 三元组」与「文本抽取」是两条独立的供给线。

**① 司马懿确实是曹操的臣** —— 只是 RocksDB-Edge **不存逆边**：

RocksDB-Edge 只写 `dataset.relations()`（源方向，44,576 条），**不写物化闭包**（44,820 条），
因为一条边从两端都能遍历。所以 `曹操 --lord--> 司马懿` 存了，`司马懿 --minister--> 曹操` **没存**：

```gremlin
g.V('司马懿','Entity').outE().filter(relation.equals('minister')).otherV().values('name')  // → []     ✗ 逆边没存
g.V('司马懿','Entity').inE().filter(relation.equals('lord')).otherV().values('name')      // → [曹操] ✓
```

实测：`曹操 --lord--> 司马懿` = **1**，曹操的臣共 **22 人**（郭嘉、荀彧、贾诩、司马懿、程昱、张辽…），
而全图 `minister` 源边只有 **3 条**（正是 curated 里明写的那 3 条）。
**问「谁是 X 的臣」用出边 `lord`；问「X 是谁的臣」用入边 `lord`（或 `bothE`）。**

**② 郭嘉真的不是「谋士」** —— 三层原因：

| 层 | 事实 |
|---|---|
| 数据层 | **旧版 331 篇里郭嘉没有传记**（荀彧/贾诩/刘晔/蒋济/董昭 也没有），他只是一个 `Person` 节点。**完整 4,487 篇里他有传记了**（`Male`），边也长到 50+ 条（`serves` 魏/袁绍势力、`holds` 司空军祭酒/洧阳亭侯、`father`/`son`、16 个 `appearsIn` 章回、20+ 条 `mentions`）；但他的传记**一次都没用「谋士」这个词**，所以 `advisorOf` 仍然抽不到。 |
| 词表层 | `advisorOf` 规则要求**字面**出现「谋士」。完整数据集下它出现得多了一些，`advisorOf` 从 2 条涨到 **27 条**，曹操的谋士现在是 **6 人：董昭、戏志才、荀缉、程昱、司马懿、荀彧**（另加军师 `荀攸` —— 走的是 `strategistOf`）。但**郭嘉的传记没用这个词**，所以仍抽不到。 |
| 语义层 | 即使抽到，`advisorOf`(谋士) 属于 **师承/mentorship**，`minister`(臣) 属于 **君臣/allegiance** —— 是**两个关系族**，不能互推。 |

那 5 处「谋士」为什么只命中 2 条？逐句看：

| 句子 | 结果 |
|---|---|
| 司马懿「是曹操帐下**谋士**之一」 | ✅ `司马懿 --advisorOf--> 曹操` |
| 徐庶「曹操**手下谋士程昱**识破了徐庶的身份」 | ✅ `程昱 --advisorOf--> 曹操`（**本轮新抽到**） |
| 祝奥「义军首领**郭援**的谋士」 | ❌ `郭援` 不在词典里，无法锚定 |
| 马超「受**张鲁谋士杨松**诬陷」 | ❌ `张鲁` 在图里是 **Faction** 不是人物，而规则要求锚点是人物 |
| 钟会「被人**比作**西汉谋士张良」 | ❌ 这是**比喻**（且张良不在词典），不该抽 —— 刻意排除 |

**本轮修的一个真漏洞**：`曹操手下谋士程昱` 里「手下」不在允许的前缀里（原来只有 `帐下|之|的`），
所以 程昱 漏了。补上 `麾下|手下|部下` 后抽到 `程昱 --advisorOf--> 曹操`（与程昱自己传记里
「昱谏止」「昱说太祖」相印证）。**同时修掉两个副作用**：① 贪婪捕获得到 `程昱识破`，
现在走 `personTarget()` 归一成 `程昱`；② `if/else` 结构会把 `谋士之一`（解析不出人名）当成命中而
**吃掉兜底分支**，使 `司马懿` 的边消失 —— 改成「先试他指，解析不出再试自指」。`advisorOf` 1 → **2** 条。

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

实测：**6,444 点 / 44,576 边**（base 为 6,202 / 21,050）；目录 `.rocksdb/sanguo-kg-text`；
`application.yml` 已挂上，UI 下拉里能选（`GET /api/v1/graphs` 返回 7 个图）。

UI 例句（`EXAMPLES['sanguo-kg-text']`）里可以直接点：五虎上将、刘备的军师、关羽斩杀过谁、关羽的传记提到谁。

### 7.6 抽取报告（可按行核对）

`SanguoTextGraphTest#writesTheExtractionReport` 会写出 **[SANGUO-KG-TEXT-EXTRACTION.md](SANGUO-KG-TEXT-EXTRACTION.md)**：
规模对比、每个规则的条数与样本、新增实体清单、以及每条规则的 8 条带 provenance 的命中（`文档:字段 «原句»`）。

- **text 图的关系家族分布（实测）**：出场 1914、提及 1888、亲属 1020、效力 724、籍贯 432、官职 252、婚姻 136、君臣 128、冲突 90、朋友 16（各×2，因为 `bothE`）。

### 7.7 已知局限（写在报告里）



- `mentions` 只匹配**已知实体名**（词典）→ 零幻觉，但不会发现新实体。
- `holds`/`fought`/`killed` 是**远程监督**：句子的主语未必是文档本人（例：李邵的传记里写“袁绍与公孙瓒战”，这条会被规则丢弃，
  但不是所有句子都能这么处理）—— 所以计数是**上界**，provenance 保留下来供人工核对。
  已针对两类高危句式做了处理：**转述/引语**（`曰`/引号内的主动式不可信）与**被动式**（`被X斩` 方向确定，单独建规则）。
- 「过五关斩六将」这类**成语/数量词**抽不到，这是刻意的（见 §7.2.1）。
- 地点抽取刻意没做（没有词典会太脏）；`军师`/`谋士` 只连**人物**。
- 新人物名来自关系槽，已过滤掉 `曹将`/数字/官衔/地名结尾/否定词，但仍可能有残留噪声。

---

## 7.8 方案 ④：给关系加时间维度（`year` / `chapter` / `seq`）

**要回答的问题**：徐庶先是谁的军师？—— 刘备，还是曹操？

### 7.8.1 数据本身先给了一个“不”

文档里只有 **人的** 时间字段（`年代列表` `总年数` `章节列表` `总章节`），**没有关系的**。
最自然的下界——“两个端点共同出现的年份/回数” —— 实测**失效**：

| | 回数列表 |
|---|---|
| 徐庶 | 35,36,37,38,39,41,47,48 |
| 刘备 | 1,2,5,…（**96 回**） |
| 曹操 | 1,2,3,…（**93 回**） |
| 徐庶 ∩ 刘备 | 35,…48 |
| 徐庶 ∩ 曹操 | 35,…48 ← **一模一样** |

刘备和曹操几乎每回都出场，交集退化成徐庶自己的范围。更麻烦的是：**走马荐诸葛与北投曹操就发生在同一回（第36回）**，
所以“回数”粒度上它们根本无法分开 —— 区分它们是**句子级**的信息。

### 7.8.2 于是边上有三个属性（三个图都加）

| 属性 | 类型 | 含义 | 能做什么 / 不能做什么 |
|---|---|---|---|
| **`year`** | STRING | 两个端点共现的公元年区间（如 `196-208`） | ✅ 筛“两人同时代”；❌ 排序（两人交集往往相同）|
| **`chapter`** | STRING | 两个端点共现的回数区间（如 `35-48`） | ✅ 同上；❌ 上面那种同回发生的事分不开 |
| **`seq`** | LONG | **叙述序**：`字段序*1000 + 句子序`（历史=1xxx，小说=2xxx） | ✅ **能排序**（传记是编年叙述）；⚠ 历史/小说是两套平行叙述，**只能同字段内比较** |
| `provenance` | STRING | `CURATED`/`EXTRA`/`DOCUMENT`/`INVERSE`/`SYMMETRIC` | 区分边的出处 |

`year`/`chapter` 由 `SanguoDataset` 从两个端点的文档算出（`关系不知道时间，人知道`），
`seq` 由 `SanguoTextExtractor` 在扫描句子时写进每条抽取结果。schema 由
`tools/generate-sanguo-schema.js` 生成，`SanguoRocksDbLoader` 与 `SanguoGraphSeeder` 都写这四个属性。

### 7.8.3 答案

徐庶 `历史` 列的三句话：①「…客居于荆州，与诸葛亮、司马徽、庞统等人相友善」
②「**后徐庶效力于刘备**，还推荐了诸葛亮」③「长坂之战时，徐庶的母亲被曹军抓获，于是徐庶**北投曹操**…」

```gremlin
// 徐庶的关系按叙述序排（seq 越小越早）
g.V('徐庶','Entity').outE().filter(relation.equals('mentions')).order().by('seq').limit(8).elementMap()
```

结果（每条边都带了时间维度）：

| seq | 边 | year | chapter | 读作 |
|---|---|---|---|---|
| 1001 | 徐庶 → 诸葛亮 / 庞统 / 司马徽 / 荆州 | 196-208 / 208 / 207-208 / – | 35-48 / 35-48 / 35-38 / – | 客居荆州，与诸葛亮等人相友善 |
| **1002** | 徐庶 → **刘备** | 196-208 | 35-48 | **后徐庶效力于刘备** |
| **1003** | 徐庶 → **曹操** / 御史中丞 / 右中郎将 | 196-208 | 35-48 | **于是徐庶北投曹操**，在魏官至… |
| 2003 | 徐庶 --strategistOf--> **刘备** | 196-208 | 35-48 | 小说列：「被拜为军师」 |
| 2004 | 徐庶 → 吕旷 / 吕翔 / 曹仁 | 206-207 / 206-207 / 196-208 | 35-36 / 35-36 / 35-41 | 为刘备击败曹将 |
| 2005 | 徐庶 → 程昱 / 谋士 | 196-208 / – | 36-48 / – | 程昱识破徐庶身份 |
| 2008 | 徐庶 → 赤壁之战 / 马腾 / 韩遂 | – / 208 / – | – / 48 / 48 | 赤壁，主动请命散关 |

**结论：先刘备（`seq` 1002），后曹操（`seq` 1003）** —— 两条边都在 `历史` 列（同一字段内可比），
且两条边的 `year`/`chapter` **完全相同**（`196-208` / `35-48`），所以这个结论**只能由 `seq` 给出**。

> 这也解释了为什么 `order().by('chapter')` 不行：`chapter` 只告诉你“他们同代、共事于第35–48回”，
> 而 `seq` 告诉你“刘备出现在第2句，曹操出现在第3句”。

### 7.8.4 局限

- `year`/`chapter` 是**共现窗口**，不是关系的起止时间；两人交集相同是常态，不要用它排序。
- `seq` 是 **叙述序**，不是绝对时间。要得到真正的关系年份，需要外部年表（或全文按回切分后再抽取）。
- 文档里的 `年代列表`/`总年数` 以前被解析器丢掉，本方案已保留（`Document.years()`）。

---

## 7.9 方案 ⑤：别名（`alias` List<String>）与 `TextP`（已实现）

**两项补完，互相配合：**

1. **`TextP`/`within` 谓词**：`has(key, TextP.containing(…))` / `startingWith` / `endingWith` / `notContaining` / `within(…)` / `without(…)` 已实现
   （`GremlinPredicate`，全局唯一入口 `GremlinSteps.matches` —— `has`/`hasValue`/`is`/HNSW 下推都经过它）。
   修复了"未实现的谓词静默返回空"这个假阴性陷阱：`TextP.containing('')` 现在能匹配一切非空属性。
2. **`alias` 多值属性**（`List<String>`，三个图都加）：别名/号，来自三个规则——
   `道号X`（道号水镜先生）、`称<人>为X` 及其并列省略（称诸葛亮为卧龙、庞统为凤雏、司马德操为水镜）、
   `姓+字`（司马德操 = 司马 + 德操，按首字符索引扫描，连"庞德公传记里提到司马德操"也能认）。

实测（`sanguo-kg-text`）：

```gremlin
g.V('Entity').has('alias', within('司马德操')).values('name')   // → 司马徽
g.V('Entity').has('alias', TextP.containing('水镜')).values('name') // → 司马徽、庞德公
g.V('Entity').has('alias', within('卧龙')).values('name')          // → 诸葛亮
g.V('Entity').has('novel', TextP.containing('水镜')).values('name')  // → 司马徽
```

- 别名共 **107 条 / 98 人**；`alias` 是 schema 里的 `{list: STRING}`，存储层 `RocksDbPropertyCodec` 原生支持。
- 性能：`plausibleTitle` 的实体名包含检查从 O(人数) 改为**子串反向索引**（O(title²)），抽取 46s→13s；
  `姓+字` 规则用**首字符索引**扫句，避免 O(句子×数千人)。

---

## 7.10 方案 ⑪：倒排索引（`isFullText` 声明，Aerospike + RocksDB 双实现）

`history`/`novel` 两个长文本字段声明 `isFullText`，写入时自动建**倒排索引**（逐字符 n-gram
→ posting list），`g.V('Entity').has('history', TextP.containing('赤壁'))` 在索引开启时不再扫全表，
而是读候选集后**回原文精确校验**。

```
schema：  { "name": "history", "type": "STRING", "isFullText": true }
RocksDB： ftx:Entity:history 列族，key = 字符:vertexId，写入即索引（RocksDbVertexTable）
Aerospike： {set}_ftx 集合，key = Entity:history:字符，bin=ids，seed 后串行重建
查询：     g.V('Entity').has('history', TextP.containing('赤壁'))
           —— 引擎开关 fullTextIndex(true/false) 控制走索引还是全扫描
```

实测（`SanguoTextGraphTest#fullTextIndexBeatsScan`，同一查询切开关，结果一致）：

| 查询 | 命中 | 全扫描 | 倒排索引 |
|---|---|---|---|
| `history` 含「赤壁」 | 10 | 21 ms | 0 ms |
| `novel` 含「之」 | 691 | 9 ms | 4 ms |

`history` 倒排索引共 **186,320 行**（去重字符）。语义：索引是**候选生成**（字符求交不保证连续），
后续 `has` 仍是精确校验，所以开启/关闭结果字节一致。

---

## 7.11 方案 ⑫：人物角色分类（君主 / 军师 / 谋士 / 武将 / 文臣）

角色**由图谱关系推断**，不引入人工数据（TuGraph 三国场景"文臣/武将"顶点分类的落地）：

| 角色 | 推断规则 | 命中 |
|---|---|---|
| 君主 | 有 `lord` 出边（有人效忠） | 5 |
| 军师 | 有 `strategistOf` 出边 | 5 |
| 谋士 | 有 `advisorOf` 出边 | 26 |
| 武将 | 有 `killed` / `fought` 出边 | 296 |
| 文臣 | 有 `holds` 出边（任官） | 1056 |

一个人按 **君主 > 军师 > 谋士 > 武将 > 文臣** 的优先级只取一个角色，所以按角色分组不重复计数。

```gremlin
g.V('Entity').has('role','君主').values('name')  // → 刘表、吕布、曹操、孙权、刘备
g.V('Entity').has('role','军师').values('name')  // → 徐庶、卢植、荀攸、庞统、王朗[司徒]
```

**已知局限（诚实）**：`武将` 由 `killed`/`fought` 的 head 推断，所以**诸葛亮**（斩过/交战过）也被归为武将——这是
"参与战斗"而非"职业武将"的语义偏差；`文臣` 是"任过官职"（1056 人，粒度粗）；君主只有 5 个，因为 `lord` 边少。

---

## 7.12 数据源调研：还有哪些可用的三国数据

| 数据源 | 内容 | 可用性 |
|---|---|---|
| `fthux/Characters_of_the_Three_Kingdoms` | JSON：字/号/别名/乳名/性别/势力/**古今出生地对照**/生卒 | ★★★ 最适合补"籍贯现代地名 + 坐标" |
| `weiwei-uvic/sanguo` | 《三国志》全部记录人物 | ★★ 补裴注人物 |
| TuGraph 三国 demo | 5 类顶点（主公/州/文臣/武将/战役）+ 5 类边（父亲/兄长/隶属/籍贯/参战） | ★★★ 本轮已借鉴其"角色分类" |
| `KGQA_SG`、`knowle-graph-of-ThreeKingdoms` | 人物关系 KG + 问答 | ★ 参考实现 |
| `shiji-kb` | 更广历史 KG（实体词表/人物关系/帝王家谱/事件索引） | ★★ 方法与词表可借鉴 |

**后续可落地的思路**（按性价比）：① 籍贯古今地名对照 + 坐标（fthux 的独特字段），让地图从
"都城/战场"扩展到"人物来源地分布"；② 势力关系网络（敌对/同盟/吞并），回答"曹操和袁绍什么关系"；
③ 官职体系（文官/武官/军职/地方官 + 品级）；④ 事件在演义/正史的差异对照（`historicity` 已有基础）。

**本轮发现的一个性能问题**：Aerospike 的倒排索引构建是 read-modify-write（每个 n-gram 一条记录），
对 base 图（11,418 个分片副本 × 每人几百字的传记）要 8 分钟；RocksDB 的"写入即索引"没有这个代价。
base 图不支持全类型扫描，倒排索引价值有限，后续可考虑 base 图不建索引（只声明 text 图）。

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

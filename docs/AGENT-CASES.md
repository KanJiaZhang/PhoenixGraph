# 两个知识图谱驱动的 data agent 案例

> 这份文档是**可展示的完整案例**：两个不同图谱、两条不同的分析路径，每一步都给出**可直接复制的查询**和
> **当次实测的输出**。写进本文的每个数字都在 2026-10-08 这台机器上、对着 `localhost:8081` 上正在服务的实例
> 跑过一遍（复现脚本：`.logs/verify-agent-cases.mjs`，一次跑完两个案例的全部断言）。
>
> | | 案例 A | 案例 B |
> |---|---|---|
> | 图谱 | `sanguo-kg-text`（三国人物/事件，6,594 点 / 45,081 边） | `tpc-h-data` + `tpc-h-analytics`（TPC-H 数据 + 语义层） |
> | 用户问的 | 「关羽在演义里杀的那些人，正史认账吗？」 | 「哪个大区的收入最好？」 |
> | agent 交付的 | 10 条关系的**逐条取证表**，其中 7 条被自己的图谱证伪 | **拒绝**回答大区问题，并给出三条自检证据 + 一份可信的替代分析 |
> | 展示看点 | 知识图谱**自己的边**被正史文本与时间线推翻，且偏差可以用 `provenance` 字段**量化归因** | 维度退化、参照系错误、谓词类型陷阱——三种「数据没错、分析会错」的典型 |
> | 共同骨架 | 分解 → 接地（schema/语义层）→ 计划 → 执行 → **自检** → 拒答或结论 → 修复建议 | 同左 |
>
> 两个案例都刻意**不是**「把图画出来」，也不是「把数查出来」——它们展示的是 **agent 在报告之前先怀疑自己的结果**。
> 与之前工作的区别见 §3。

---

## 案例 A：史实 vs 演义 —— agent 审计知识图谱自己的边

### A.0 为什么这个案例有说服力

三国知识图谱把《三国演义》和史书两套叙述装进了同一张图：**边**（关系）和**点的两个文本字段**（`history` 正史、
`novel` 演义）是两套独立来源。于是任何一条「关羽斩杀 X」的声明都能被**同一份数据**检验：

- 有的与正史一致（颜良、庞德）；
- 有的正史明确是别人杀的（华雄→孙坚、蔡阳→刘备、文丑→曹操）；
- 有一条被**时间线**自证错误（庞会：关羽卒于 219，庞会卒于 263，晚了 44 年）；
- 而且整族 `killed` 边**全部**来自 `provenance=EXTRA`（演义来源）——偏差不是随机噪声，是**可归因、可量化**的系统性偏差。

这就是「知识图谱驱动」的价值：**不是**因为图谱比文本聪明，而是因为它把「声明」和「证据」放在了一起，让 agent 可以逐条对账。

### A.1 图谱事实（实测）

```bash
# 规模
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' \
  -d '{"graphName":"sanguo-kg-text","query":"g.V(\u0027Entity\u0027).count()","includeResult":true}'   # [6594]
  # 同法：g.E('Entity_Entity').count() -> 45081
```

| 事实 | 数值 |
|---|---|
| 点 / 边 | **6,594 / 45,081** |
| 点类型（`entityType`） | Male 2,586 · Position 647 · Place 484 · Person 211 · Female 157 · Chapter 120 · Faction 20 · **Event 21** · Idiom 2 · Artifact 2 |
| 人物性质（`historicity`） | **史实人物 4,273** · **虚构人物 214**（其余 2,107 是无该字段的地点/章回/事件等） |
| 有 `novel`（演义叙述）字段的点 | 1,343 |
| 关系家族（`relationFamily`） | 16 类（提及/效力/出场/籍贯/亲属/官职/婚姻/冲突/君臣/师承/事件/归降/招募/朋友/地缘/其他） |
| 边的来源（`provenance`） | **EXTRA 24,147**（演义/章回侧）· **DOCUMENT 20,788**（文本抽取侧）· 无值 146 |
| 带时间与章回的边 | `year` 5,917 条 · `chapter` 6,831 条（`year` 可能是区间，如 `192-200`） |
| 带生卒年的点 | `deathYear` 仅 1,081 个 · `birthYear` 仅 377 个（其余人物的纪年只在正文里） |
| `killed`（斩杀）关系 | **300 条，其中 EXTRA 300 / DOCUMENT 0** |
| 证据文本长度 | `history` 最长 **120 字**（2,743 条里 **566 条**触顶）· `novel` 最长 **120 字**（803 条里 **218 条**触顶） |

> 两个必须知道的口径：**整类扫描只在 RocksDB-Edge 这类有序区间存储上可用**（`sanguo-kg` 是 Aerospike 按键读取，
> `g.V('Entity')` 会明确报错）；**`outE()` 的默认投影只带 `relation`**，要看 `year`/`chapter` 得用 `valueMap()`。

### A.2 步骤 1：把问题分解成可执行的子问题

用户问：「关羽在演义里杀的那些人，正史认账吗？」agent 拆成五步：

1. 关羽在图谱里是哪些点？（分片 id 问题）
2. 图谱**声明**他杀了谁？（取边）
3. 每个被声明者，正史怎么说？（读 `history`）
4. 有没有一条能**一票否决**的检查？（时间线：谁的卒年晚于关羽）
5. 偏差是个案还是系统性？（按 `provenance`/`relation` 统计）

第 4 步是关键的 agent 行为：**先找能被证伪的检查，再决定要不要下结论**。

### A.3 步骤 2：实体归一 —— 分片 id 陷阱（先查存在性，别假设 id）

```bash
# 服务的 Aerospike 实例里，"关羽" 这个 id 根本不存在
-d '{"query":"g.V(\u0027关羽\u0027,\u0027Entity\u0027)"}'          # 0 行
-d '{"query":"g.V(\u0027关羽_3\u0027,\u0027Entity\u0027)"}'        # 1 行  -> {"id":"关羽_3","name":"关羽","role":"文臣"}
-d '{"query":"g.V(\u0027曹操\u0027,\u0027Entity\u0027)"}'          # 0 行
-d '{"query":"g.V(\u0027曹操_3\u0027,\u0027Entity\u0027)"}'        # 1 行
-d '{"query":"g.V(\u0027曹操_9\u0027,\u0027Entity\u0027)"}'        # 1 行  -> 同一个人被拆成多片
```

实测输出（`sanguo-kg`）：

| 查询 | 结果 |
|---|---|
| `g.V('关羽','Entity')` | **0 行** |
| `g.V('关羽_3','Entity')` | 1 行（`role=文臣`，注意与文本图谱的 `武将` 不一致：分片副本的属性本身就是另一个副本） |
| `g.V('曹操','Entity')` | 0 行 |
| `g.V('曹操_3','Entity')` / `g.V('曹操_9','Entity')` | 各 1 行（同一人物的两片：出边在 `_3`，入边在 `_9`） |
| `g.V('关羽_3','Entity').outE('Entity_Entity').filter(relation.equals('killed'))` | **0 条**（斩杀边不在这一片） |

**agent 的判断**：id 带 `_<n>` 后缀是**关系分片**，同一人物有多片；分析必须**按 `name` 聚合**，不能按 id 假设唯一。
本案例后续统一在 `sanguo-kg-text`（分片已合并、且可整类扫描）上做，这与「先确认用哪个实例才能回答这个问题」是同一个决策。

### A.4 步骤 3：抽取图谱声明（关羽的 `killed` 边）

```bash
-d '{"graphName":"sanguo-kg-text","query":"g.V(\u0027关羽\u0027,\u0027Entity\u0027).outE(\u0027Entity_Entity\u0027).filter(relation.equals(\u0027killed\u0027)).otherV().values(\u0027name\u0027)","includeResult":true}'
# -> ["颜良","郭图","蔡阳","管亥","沮授","文丑","庞德","庞会","卞喜","华雄"]

# 同一遍历换 valueMap()：默认投影丢掉的 year/chapter 在这里（顺序与上一行一一对应）
# -> [{"year":"192-200","chapter":"5-77"},...,{"year":"219","chapter":"74"},{"year":"190","chapter":"5"}]
```

10 条声明，全部 `provenance=EXTRA`，且每条都带演义章回与年份区间（例如 华雄：`year=190 chapter=5`；庞会：`year=219 chapter=74`）。

### A.5 步骤 4：逐条与正史对照（agent 的取证表）

对每个被声明者读一次它的点属性：`g.V('<name>','Entity')`，取 `historicity` / `deathYear` / `history` / `novel`。

| # | 图谱声明 | 演义年份/章回 | 正史（`history` 原文） | agent 判定 |
|---|---|---|---|---|
| 1 | 关羽 → killed → **颜良** | 192-200 · ch.5-77 | 「…颜良仓猝逆战，**遂被关羽击斩**」 | ✅ **一致** |
| 2 | 关羽 → killed → **庞德** | 211-219 · ch.65-79 | 由庞会条目「父庞德…**德为关羽所杀**」证实 | ✅ **一致** |
| 3 | 关羽 → killed → **华雄** | **190** · ch.5 | 「初平元年（191）…孙坚与卓军战于阳人，**雄为坚所斩**」 | ❌ **正史另有其人（孙坚）** |
| 4 | 关羽 → killed → **蔡阳** | 200 · ch.26-28 | 「太祖遣阳击都，不利，军破，**阳为备所杀**」 | ❌ **正史另有其人（刘备）** |
| 5 | 关羽 → killed → **文丑** | 192-200 · ch.5-77 | 「文丑兵乱，**遂被操击破而死**」 | ❌ **正史另有其人（曹操军）** |
| 6 | 关羽 → killed → **管亥** | 193 · ch.11 | 「…围城的黄巾军听说救兵到来，解围散走」（**无死亡记载**） | ⚠️ **仅演义**（正史未载其死） |
| 7 | 关羽 → killed → **沮授** | 199-200 · ch.7-31 | 证据字段 120 字截断，**未见「为关羽所杀」** | ⚠️ **证据不足，需人工复核** |
| 8 | 关羽 → killed → **郭图** | 199-202 · ch.22-31 | 同上（截断） | ⚠️ **证据不足，需人工复核** |
| 9 | 关羽 → killed → **庞会** | **219** · ch.74 | 庞会条目自述：**景元四年（263）随镇西将军钟会伐蜀**；其父庞德 219 年被关羽所杀 | ❌ **时间线自证错误**（晚 44 年） |
| 10 | 关羽 → killed → **卞喜** | 200 · ch.27-77 | 「**虚构人物**，出自三国演义」 | 🏷️ **图谱自标虚构**（演义原创人物） |

**顺带发现的反向缺口**（正史承认、图谱没有的边）：

```bash
-d '{"query":"g.V(\u0027孙坚\u0027,\u0027Entity\u0027).outE(\u0027Entity_Entity\u0027).filter(relation.equals(\u0027killed\u0027)).otherV().values(\u0027name\u0027)"}'
# -> ["王叡","曹寅","张咨"]      孙坚斩杀三人里有记录，却没有正史里他斩的华雄
-d '... .filter(relation.equals(\u0027killed\u0027)).otherV().filter(name.equals(\u0027华雄\u0027))'
# -> []                          孙坚 --killed--> 华雄 这条边不存在
```

即：**图谱不是漏了几条边，而是这一族边整体只从演义侧抽取**——这正是下一步要量化的东西。

### A.6 步骤 5：量化系统性偏差（`provenance` 归因）

```bash
-d '{"query":"g.E(\u0027Entity_Entity\u0027).has(\u0027relation\u0027,\u0027killed\u0027).has(\u0027provenance\u0027,\u0027EXTRA\u0027).count()"}'     # [300]
-d '{"query":"g.E(\u0027Entity_Entity\u0027).has(\u0027relation\u0027,\u0027killed\u0027).has(\u0027provenance\u0027,\u0027DOCUMENT\u0027).count()"}'  # [0]
# 对照组（同类统计）
#   serves : EXTRA 0     / DOCUMENT 4912
#   father : EXTRA 0     / DOCUMENT 1351
#   brother: EXTRA 0     / DOCUMENT 2628
#   mentions: EXTRA 22951/ DOCUMENT 0
```

**结论**：`killed` 与 `mentions` 是**演义侧**关系（EXTRA 100%），`serves/father/brother` 是**文本侧**关系
（DOCUMENT 100%）。所以「关羽斩杀名单」中的错误不是数据脏，而是**问错了来源**：

> 用图谱做史实判断时，**必须把 `provenance` 当成断言的一部分**——`killed (EXTRA)` 回答的是「演义怎么讲」，
> `serves (DOCUMENT)` 回答的才是「文献怎么记」。

### A.7 步骤 6：时间线检查（一票否决型证据）

```bash
-d '{"query":"g.V(\u0027关羽\u0027,\u0027Entity\u0027)"}'   # deathYear = 219
-d '{"query":"g.V(\u0027庞会\u0027,\u0027Entity\u0027)"}'   # deathYear = 263
-d '{"query":"g.V(\u0027华雄\u0027,\u0027Entity\u0027)"}'   # deathYear = 191
-d '{"query":"g.V(\u0027温酒斩华雄\u0027,\u0027Entity\u0027)"}' # entityType=Event, eventYear=190, (34.8, 113.3)
```

- 庞会：**263 − 219 = 44 年**，一个人不可能被 44 年后才去世的人杀掉 → `关羽→killed→庞会` **必须**从任何
  「史实关系」结果里剔除（而它在图谱里存在，来源是演义第 74 回）。
  注意这条证据**不是** `deathYear` 属性：庞会没有这个字段（全库只有 **1,081** 个点有 `deathYear`、**377** 个有
  `birthYear`，且文本本身在 120 字处截断），纪年是从他的 `history` 正文里读出来的（「景元四年，会随镇西将军钟会伐蜀」）
  ——**agent 必须能从正文取纪年，而不是只会比字段**。
- 温酒斩华雄：事件年 **190**，而华雄的 `deathYear` 是 **191** → 二者相差一年，属于**待核**级别
  （这类"事件年 vs 卒年"的轻微矛盾不构成一票否决，agent 应标注而不是断言）。
- 赤壁之战 `eventYear=208`、`(29.72, 113.9)`：作为对照，时间与坐标都自洽。

### A.8 步骤 7：证据完整性自检（知道自己看不见什么）

```bash
# 扫描 + 本地统计长度（max/触顶条数）
#   history: 2743 条，最长 120 字，其中 566 条触顶
#   novel  :  803 条，最长 120 字，其中 218 条触顶
```

**这是本案例最容易被忽略、也最能说明 agent 水平的一步**：`history` 字段在 120 字处被截断，所以
「正史没写」与「摘录被切掉了」在当前数据上**无法区分**。因此表中第 7、8 条（沮授、郭图）只能给
「证据不足」，不能给「演义虚构」。**agent 把证据的边界一起报出来，而不是把不确定包装成结论。**

### A.9 步骤 8：结论与修复建议（agent 的交付物）

| 判定 | 条数 | 占比 |
|---|---|---|
| ✅ 与正史一致 | 2 | 20% |
| ❌ 正史另有其人 / 时间线自证错误 | 4 | 40% |
| ⚠️ 仅演义或证据不足 | 3 | 30% |
| 🏷️ 图谱自标虚构 | 1 | 10% |

**给用户的一句话**：关羽的 `killed` 关系里只有 **2/10** 能当史实用；这 10 条**全部**来自演义侧
（`provenance=EXTRA`，全库 300 条 `killed` 边无一例外），所以「关羽杀了谁」在**史实口径**下应当改为查询
`DOCUMENT` 侧的关系（效力、亲属、籍贯等），或者把 `killed` 明确标注为「演义情节」。

**给图谱维护者的修复建议**（agent 不只报错，还给可执行的修法）：

1. **拆分关系语义**：把 `killed` 拆成 `killed`（DOCUMENT 侧）与 `novelKilled`（EXTRA 侧），或强制每条边携带
   `provenance` 并在查询层默认按来源过滤；
2. **加时间一致性约束**：`killed(A,B)` 要求 `deathYear(B) ≥ deathYear(A)`（本例 1 条违反）；
3. **对 `history`/`novel` 做全文入库或标注截断**（566/2,743 触顶），否则「未记载」不可判定；
4. **补正史侧缺失边**：把 `孙坚→killed→华雄` 这类正史明确的关系补进来（当前 0 条）。

### A.10 案例 A 的完整复现脚本

```bash
# 1) 规模与分布
for q in "g.V('Entity').count()" "g.E('Entity_Entity').count()" \
         "g.V('Entity').has('historicity','虚构人物').count()" \
         "g.E('Entity_Entity').has('relation','killed').has('provenance','EXTRA').count()"; do
  curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' \
    -d "{\"graphName\":\"sanguo-kg-text\",\"query\":\"$q\",\"includeResult\":true}"; echo; done

# 2) 取证表（10 条声明 + 每个被声明者的属性）
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' \
  -d '{"graphName":"sanguo-kg-text","query":"g.V(\u0027关羽\u0027,\u0027Entity\u0027).outE(\u0027Entity_Entity\u0027).filter(relation.equals(\u0027killed\u0027)).otherV().values(\u0027name\u0027)","includeResult":true}'

# 3) 一键跑完全部断言（本文件引用的每个数字）
node .logs/verify-agent-cases.mjs
```

---

## 案例 B：维度不可用 —— agent 用三道自检把「大区排名」退回给用户

### B.0 为什么这个案例有说服力

用户问的是一个**看起来最普通**的问题：「哪个大区的收入最好？1996 年在涨吗？」
数据也**真的能返回一个排名**——这正是危险所在：**能返回**不等于**有意义**。
案例展示 agent 在交付前跑三道自检，结果三道全部报警，最终**拒绝**给出大区结论，改用可信维度交付一份真实洞察。

### B.1 图谱事实（实测）

```bash
-d '{"graphName":"tpc-h-data","query":"g.V(\u0027Sale\u0027).count()"}'
# Sale 8000 · Customer 500 · Nation 25 · Region 5
# 边类型：ofOrder / ofPart / ofSupplier / placedBy / customerNation / supplierNation / nationRegion
```

`Sale` 点把订单行**反范式化**成一张宽表（21 个属性）：`extendedprice, discount, tax, quantity, revenue, gross,
discountAmount, taxAmount, lateDelivery, returned, region, nation, segment, priority, shipmode, partType, brand,
supplierName, orderYear, orderMonth`。另一半是**语义层** `tpc-h-analytics`：18 个指标、11 个维度、8 张表，节点类型
`Metric / Dimension / Table / Column / Domain / Query`。

### B.2 步骤 1：自然语言 → 语义层落地（agent 的"接地"）

| 用户的话 | 落到的图谱节点 | 实测属性 |
|---|---|---|
| 「收入」 | `metric/revenue` | `expression = SUM(l_extendedprice * (1 - l_discount))`，`unit = 元`，说明「净收入：订单行的金额扣掉折扣后的收入」 |
| 「大区」 | `dim/region --dimensionColumn--> column/region.r_name` | 说明「地区：国家上卷到五大区」 |
| 「市场细分」 | `dim/mktsegment --dimensionColumn--> column/customer.c_mktsegment` | — |
| 「晚交付 / 退货」 | `metric/late_delivery_count` / `metric/return_rate` | `COUNT(CASE WHEN l_receiptdate > l_commitdate THEN 1 END)` / `100 * SUM(CASE WHEN l_returnflag = 'R' THEN 1 ELSE 0 END) / COUNT(*)` |

**指标血缘与 join 路径由图谱生成，不靠人写**（`tools/semantic-sql.mjs` 沿 `computedFrom`/`usesColumn`/`references` 拼装）：

```sql
-- node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/region
SELECT region.r_name AS region,
       SUM(lineitem.l_extendedprice * (1 - lineitem.l_discount)) AS revenue
FROM lineitem
JOIN supplier ON lineitem.l_suppkey = supplier.s_suppkey
JOIN nation   ON supplier.s_nationkey = nation.n_nationkey
JOIN region   ON nation.n_regionkey = region.r_regionkey
GROUP BY region.r_name ORDER BY region;
```

> 附带一个真实结论：`dim/region` 的血缘把收入接到 **supplier** 的 region（`l_suppkey → supplier → nation → region`），
> 不是 customer 的。**「大区」这个词在这里本来就是两种口径**，agent 必须先问清是"供货大区"还是"客户大区"。

### B.3 步骤 2：执行『最直觉的查询』（它会给你一个像样的答案）

```bash
-d '{"graphName":"tpc-h-data","query":"g.V(\u0027Sale\u0027).group().by(\u0027region\u0027).by(values(\u0027revenue\u0027).sum())","includeResult":true}'
```

| 大区 | 收入 |
|---|---|
| ASIA | 39,515,305.18 |
| EUROPE | 39,187,823.75 |
| AMERICA | 38,884,837.91 |
| MIDDLE EAST | 38,474,258.73 |
| AFRICA | 38,430,001.37 |

看起来完全可用：ASIA 领先 EUROPE 约 32.7 万（0.8%）。**如果 agent 到这里就写报告，用户会拿到一个错误结论。**

### B.4 自检 1：参照一致性（把结果对回 TPC-H 的公开定义）

```bash
-d '{"graphName":"tpc-h-data","query":"g.V(\u0027Nation\u0027).limit(30)","includeResult":true}'
# nation_24 UNITED STATES -> region MIDDLE EAST   ✗ (TPC-H: AMERICA)
# nation_21 VIETNAM       -> region AMERICA       ✗ (TPC-H: ASIA)
# nation_18 CHINA         -> region EUROPE        ✗ (TPC-H: ASIA)
```

把 25 个国家逐个对回 TPC-H 标准映射：

| 检查 | 结果 |
|---|---|
| 国家落在大区正确位置 | **7 / 25** |
| 不一致 | **18 / 25**（美国→MIDDLE EAST、中国→EUROPE、印度→EUROPE、巴西→ASIA、法国→AMERICA、沙特→AFRICA、肯尼亚→MIDDLE EAST …） |

**判定**：`nation → region` 的赋值是**轮转式**的，不是 TPC-H 语义。既然大区的定义域本身错了，
上面那张「大区收入排名」**再自洽也没有意义** → 这一步就足以拒答。

### B.5 自检 2：维度冗余（`region` 与 `segment` 是不是同一个东西？）

```bash
-d '{"query":"g.V(\u0027Sale\u0027).group().by(\u0027segment\u0027).by(values(\u0027revenue\u0027).sum())"}'
```

| 大区（region） | 收入 | 市场细分（segment） | 收入 |
|---|---|---|---|
| AFRICA | 38,430,001.37 | BUILDING | **38,430,001.37** |
| AMERICA | 38,884,837.91 | AUTOMOBILE | **38,884,837.91** |
| ASIA | 39,515,305.18 | MACHINERY | **39,515,305.18** |
| EUROPE | 39,187,823.75 | HOUSEHOLD | **39,187,823.75** |
| MIDDLE EAST | 38,474,258.73 | FURNITURE | **38,474,258.73** |

**五个数字到分位完全相等** → 两个维度是同一份数据的两个标签（加载器用同一个轮转下标同时生成 region 与 segment）。
这意味着：**任何"按大区"和"按细分"的对比、交叉、下钻都是自我循环**，不能作为独立证据。

同时验证「反范式字段与图路径是否一致」：`Sale.region` 与 `Sale→ofOrder→Order→placedBy→Customer→customerNation→Nation→nationRegion→Region`
对 400 条 1996 年记录**全部一致（400/400）**——所以**不是**反范式写错了，而是**源头那张 `nation→region` 表错了**。
（这条区分很重要：修的地方不一样。）

### B.6 自检 3：谓词类型（先怀疑自己的问句）

```bash
-d '{"query":"g.V(\u0027Sale\u0027).has(\u0027lateDelivery\u0027,true).count()"}'    # [0]     ← "没有晚交付"
-d '{"query":"g.V(\u0027Sale\u0027).has(\u0027lateDelivery\u0027).count()"}'          # [8000]  ← 字段全都有值
-d '{"query":"g.V(\u0027Sale\u0027).values(\u0027lateDelivery\u0027).dedup()"}'       # [0, 1]  ← 存的是整数
-d '{"query":"g.V(\u0027Sale\u0027).has(\u0027lateDelivery\u0027,1).count()"}'        # [771]   ← 真相
-d '{"query":"g.V(\u0027Sale\u0027).has(\u0027returned\u0027,1).count()}'             # [351]
```

**这是本案例最尖锐的一步**：`has('lateDelivery', true)` 静默返回 0（不报错、不警告），
如果 agent 照单全收，就会得出「本数据没有延迟交付」——而实际有 **771/8,000 = 9.64%**。
加载器把布尔写成了 `0/1` 整数，谓词类型不匹配 → **空集**。修复后的口径才是可分析的：

| 指标 | 值 |
|---|---|
| 晚交付行数 / 占比 | 771 / **9.64%** |
| 退货行数 / 占比 | 351 / **4.39%** |
| 缺失该字段的行 | 0 |

> 这条自检也修正了本仓库早先的一条**旧结论**（曾据布尔查询写成「lateDelivery/returned 全为空」）——
> 错的是问句的类型，不是数据。更正后的数据注记见 [USE-CASES.md §2.3](USE-CASES.md#23-analysis-layer-from-the-numbers-to-the-explanation)
> 与 [DATA-QUALITY.md](DATA-QUALITY.md)。

### B.7 步骤 3：给出**可信**的答案（拒答之后仍然交付价值）

被否掉的是「大区」这个维度，不是整个问题。agent 换成**每条销售记录自带的、不依赖轮转 id 的维度**重新分析：

**① 规模与增长（`orderYear`）**

| 年度 | 收入 | 折扣额 | 销量 | 行数 | 晚交付收入 |
|---|---|---|---|---|---|
| 1995 | 95,612,794.05 | 4,897,014.95 | 100,485 | 3,952 | 8,945,352.26（9.36%） |
| 1996 | 98,879,432.89 | 5,180,314.11 | 104,099 | 4,048 | 9,853,656.02（9.97%） |
| 同比 | **+3.42%** | **+5.79%** | +3.59% | +2.43% | **+10.15%** |

**可交付结论 1**：收入增长 3.42%，而**折扣额增长 5.79%、晚交付金额增长 10.15%**——增长是"打折换来的"，
且**履约问题在放大**。另外平均单行金额几乎没动（24,193.52 → 24,426.74，**+0.96%**），说明增长来自**行数**（+2.43%），不是客单价。

**② 晚交付率按运输方式（`shipmode`，独立维度）**

| 运输方式 | 晚交付 / 总数 | 晚交付率 |
|---|---|---|
| RAIL | 133 / 1,275 | **10.43%** |
| SHIP | 141 / 1,390 | 10.14% |
| REG AIR | 133 / 1,336 | 9.96% |
| AIR | 125 / 1,294 | 9.66% |
| TRUCK | 120 / 1,298 | 9.24% |
| MAIL | 119 / 1,407 | **8.46%** |

**可交付结论 2**：MAIL 最可靠（8.46%），RAIL/SHIP 最差（10.43%/10.14%），差距约 **2 个百分点**——
这是可以落地的运营结论（且它不依赖任何轮转 id）。

**③ 一个"没有信号"的诚实结论（`priority`）**

| 优先级 | 退货率 |
|---|---|
| 1-URGENT / 2-HIGH / 3-MEDIUM / 4-NOT SPECIFIED / 5-LOW | 4.13% / 4.06% / 4.44% / 4.69% / 4.63% |

**可交付结论 3**：退货率与订单优先级**基本无关**（4.06%–4.69%，各组 1,600 行）。agent 不编故事——
"没有信号"本身就是要报的结果。

**④ 季节性的量级（`orderMonth`，两年合并）**

1 月 13,567,510.77 · 4 月 17,610,641.76 · **7 月 18,211,062.47** · 10 月 13,568,867.50 · 12 月 16,819,517.12
→ 峰值/谷值 ≈ **+34%**，属于存在但不算剧烈的季节波动（给出量级，不下过度解读）。

### B.8 结论与修复清单

**给用户的一句话**：这个实例**不能**按大区分析——(1) 25 个国家里 18 个的大区归属与 TPC-H 不符；
(2) `region` 与 `segment` 是同一份数据的两个标签（五组数字到分位相等）；
(3) 谓词类型陷阱让"没有晚交付"这种结论很容易被静默地产生。**可以**按年/月/运输方式/优先级分析，
上面三条结论都成立。

**给数据负责人的修复清单**（按修复成本排序）：

1. 重播 `tpc-h-data` 时按 **TPC-H 标准 `nation → region`** 赋值（当前 7/25 正确）；
2. `region` 与 `segment` 用**独立**的取值来源（现在同一个轮转下标）；
3. 把 `lateDelivery`/`returned` 统一成**布尔**，或在使用侧统一按 `0/1` 查（并考虑让引擎对
   `has(key, true)` 匹配整数 `1` 时给出提示，避免静默空集）；
4. `promotionRevenue` 属性缺失：语义层里若仍有 `metric/promotion_revenue` 与 `metric/promotion_effect`，
   要么补数据，要么把指标标为不可用（现在会出现"指标有定义、数据没有列"的悬空血缘）。

### B.9 案例 B 的完整复现脚本

```bash
# 三道自检 + 可信分析，一条命令跑完
node .logs/verify-agent-cases.mjs

# 手工抽查
curl -s -X POST localhost:8081/api/v1/query -H 'content-type: application/json' \
  -d '{"graphName":"tpc-h-data","query":"g.V(\u0027Sale\u0027).group().by(\u0027region\u0027).by(values(\u0027revenue\u0027).sum())","includeResult":true}'
node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/region
node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/order_date --grain month
```

---

## 3. 两个案例共同展示的 agent 骨架（可以直接当模板复用）

| 步骤 | 案例 A（三国） | 案例 B（TPC-H） |
|---|---|---|
| **分解** | 五个子问题，先找"一票否决"的检查 | 需求 → 指标/维度/口径（谁的大区？） |
| **接地** | 先确认 id 形态与可用实例（分片 + 可扫描性） | 语义层节点给出表达式、单位、血缘、join 路径 |
| **计划** | 取边 → 取端点属性 → 对照 → 统计 | 生成 SQL（由血缘自动拼装）→ 落到图谱查询 |
| **执行** | 10 条 `killed` 边 + 10 份正史文本 | 大区排名 + 年度/运输方式/优先级聚合 |
| **自检** | 时间线（庞会 263 vs 关羽 219）、来源分布（300/300 EXTRA）、证据完整性（120 字截断 566 条） | 参照一致性（7/25）、维度冗余（region ≡ segment）、谓词类型（true=0 vs 1=771） |
| **拒答 / 结论** | 「史实口径下 10 条只剩 2 条可用」 | 「大区维度不可用；改用年/月/运输方式，三条结论成立」 |
| **修复建议** | 拆关系语义、加时间约束、全文入库、补正史边 | 修 `nation→region`、拆 region/segment、统一布尔、处理悬空指标 |

一句话总结两者的差别：**案例 A 检验的是"关系本身可不可信"，案例 B 检验的是"维度本身可不可用"**；
两者共用的动作都是——**在给用户结论之前，先找到能推翻自己的那个检查**。

与之前工作的区别：[USE-CASES.md](USE-CASES.md) 里的语义层故事停在「生成 SQL 并复现 1996 vs 1995 的数字」；
本文两个案例都**不止于执行**——A 会让图谱反驳自己（并给出可量化的偏差来源），
B 会在三道自检全红之后**拒绝**回答原问题并交付替代分析。这正是"data agent"与"报表"的分界。

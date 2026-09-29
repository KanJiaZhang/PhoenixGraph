# 企业数据分析知识图谱（`tpc-h-analytics`）—— data agent 的语义层

> 一个**语义层 / 指标层**知识图谱：把数据仓库的表、字段、指标、维度、业务域和查询建模成图谱，
> 让 data agent 能**定义指标、生成准确查询、做归因分析**，而不是把 schema 塞进提示词里猜。

数据源：[**TPC-H**](https://www.tpc.org/tpch/)（公开、可复现的零售/订单业务决策支持基准：8 张表、22 个标准查询）。
选它的理由：schema 权威且稳定、查询是"业务问题"的标准形式、结果可复现，不像私有数据集那样不可验证。

---

## 1. 为什么需要这个图谱（对齐 data agent 的四件事）

| data agent 要做的 | 图谱里对应什么 |
|---|---|
| **分析业务** | `Table` / `Column` / `Domain` 节点 + `hasColumn` / `references` / `belongsTo` 边 |
| **定义指标** | `Metric` 节点带 `aggFunction` / `expression` / `unit` / `metricKind` |
| **生成准确查询** | `computedFrom`（取自哪些表）+ `usesColumn`（用哪些字段）+ `references`（join 路径）+ `definedBy`（标准 SQL） |
| **分析结果与归因** | `contributesTo`（哪个基础指标驱动哪个派生指标）+ `groupedBy`（按什么维度下钻） |

关键点：**join 路径从 `references` 边读出来，SQL 是组装的，不是猜的**。

---

## 2. 图谱结构

**6 类节点**（顶点类型 `Node`，用 `entityType` 区分）：

| 类型 | 数量 | 例子 |
|---|---|---|
| `Table` | 8 | `table/lineitem`（订单行明细）、`table/orders`（订单） |
| `Column` | 61 | `column/lineitem.l_extendedprice`（金额）、`column/orders.o_custkey`（外键） |
| `Metric` | 17 | `metric/revenue`（净收入）、`metric/profit`（利润） |
| `Dimension` | 11 | `dim/region`、`dim/mktsegment`、`dim/order_date` |
| `Domain` | 6 | `domain/sales`、`domain/supplier`、`domain/shipping` |
| `Query` | 12 | `query/q3`（Shipping Priority，含 SQL） |

**10 类关系**（边类型 `Relation`，带 `relation` / `relationLabel` / `relationFamily` / `xrelation`）：

| 关系 | 家族 | 含义 |
|---|---|---|
| `hasColumn` | 结构 | 表 → 字段 |
| `references` | 结构 | 外键 → 主键（join 路径） |
| `computedFrom` | 血缘 | 指标 → 来源表 |
| `usesColumn` | 血缘 | 指标 → 使用字段 |
| `readsTable` | 血缘 | 查询 → 读取表 |
| `groupedBy` | 维度 | 指标 → 可切分的维度 |
| `dimensionColumn` | 维度 | 维度 → 对应字段 |
| `definedBy` | 指标 | 指标 → 定义它的标准查询 |
| `belongsTo` | 指标 | 指标 → 业务域 |
| `contributesTo` | **归因** | 基础指标 → 派生指标（谁驱动谁） |

**规模**：115 节点 / 264 关系。

---

## 3. data agent 的典型问题（都由图谱回答）

```gremlin
# ① 怎么定义 revenue？
g.V('metric/revenue','Node').values('aggFunction','expression','unit')
# → SUM | SUM(l_extendedprice * (1 - l_discount)) | 元

# ② revenue 从哪些表来？（血缘）
g.V('metric/revenue','Node').outE().filter(relation.equals('computedFrom')).otherV().values('name')
# → table/lineitem

# ③ revenue 用哪些字段？（生成 SQL 需要的列）
g.V('metric/revenue','Node').outE().filter(relation.equals('usesColumn')).otherV().values('name')
# → column/lineitem.l_extendedprice, column/lineitem.l_discount

# ④ revenue 能按什么维度看？（下钻）
g.V('metric/revenue','Node').outE().filter(relation.equals('groupedBy')).otherV().values('name')
# → dim/order_date, dim/nation, dim/region, dim/mktsegment

# ⑤ 归因：revenue 由哪些指标驱动？
g.V('metric/revenue','Node').inE().filter(relation.equals('contributesTo')).otherV().values('name')
# → gross_revenue, discount_amount, tax_amount, promotion_revenue, order_count, avg_order_value

# ⑥ 归因链：profit 的二阶驱动
g.V('metric/profit','Node').inE().filter(relation.equals('contributesTo')).otherV()
   .inE().filter(relation.equals('contributesTo')).otherV().dedup().values('name')
# → 展开到最底层的基础指标

# ⑦ 生成 join：revenue 的外键指向哪些主键？
g.V('metric/revenue','Node').outE().filter(relation.equals('computedFrom')).otherV()
   .outE().filter(relation.equals('hasColumn')).otherV().has('columnRole','FK')
   .outE().filter(relation.equals('references')).otherV().values('name')

# ⑧ 反向血缘：某字段被哪些指标使用？
g.V('column/lineitem.l_extendedprice','Node').inE().filter(relation.equals('usesColumn')).otherV().values('name')
# → 10 个指标
```

**归因的语义**（`contributesTo`）：`revenue = SUM(extendedprice × (1 − discount))`，所以
`gross_revenue`、`discount_amount`、`tax_amount` 都指向 `revenue`；`revenue × 占比 = market_share`；
`revenue − supply_cost = profit`。agent 读到"revenue 下降"时，可以顺着这些边拆成**毛收入 / 折扣让利 /
促销结构 / 订单数 × 客单价**，而不是只报一个数字。

---

## 4. 端到端：把数字跑出来（数据实例 `tpc-h-data`）

语义层只说"指标怎么算"，要看到数字还需要行数据。`tpc-h-data` 是配套的**数据实例**：
**8,000 行订单行事实**（TPC-H 的 1 订单 : 4 行的比例，固定种子确定性生成）+ **7 类维度顶点**
（Part/Supplier/Customer/Nation/Region/Order）+ **7 类星型边**（ofPart / ofSupplier / ofOrder /
placedBy / customerNation / supplierNation / nationRegion），共 11,130 顶点 / 26,625 边。

两个建模决定，都是被引擎逼出来的：
- **指标预计算**：`revenue = extendedprice × (1-discount)`、`taxAmount`、`discountAmount` 都作为属性
  存好。因为 Gremlin 表达式只有 `+`，没有 `-`/`*`（实测 `map{ a - b }` 报 `unexpected '-'`）。
- **维度反规范化**：`region`/`nation`/`segment`/`priority`/`partType`/`supplierName` 直接落在事实行上，
  所以 `group().by('region')` 是一次扫描，而不是四跳遍历。这是数仓会建的星型模型。

**跑出来的真实数字**（`TpchDataGraphTest#answersBusinessQuestionsWithRealNumbers`，并与 Java 直算对账）：

| 查询 | 结果 |
|---|---|
| ① 大区净收入 | EUROPE 4,118.6万 / AFRICA 4,096.5万 / ASIA 4,016.3万 / MIDDLE EAST 3,694.7万 / AMERICA 3,458.9万 |
| ② 促销收入 | 7,500.0万（占净收入 **38.69%**） |
| ③ 客单价（按市场细分） | HOUSEHOLD 4,118.6万 … AUTOMOBILE 3,458.9万 |
| ④ 迟到行数（按优先级） | 3-MEDIUM 176 / 2-HIGH 169 / 1-URGENT 143 … |
| ⑤ 供应商净收入 Top1 | Supplier#90 265.4万 |
| ⑥ **归因：净收入拆解** | 毛收入 20,414.7万 − 折扣额 1,029.7万 = 净收入 19,385.0万（误差 0） |
| ⑦ **归因：退货率** | 411 / 8,000 = **5.14%** |
| ⑧ 图的 join | `sale_0 --ofPart--> Part#263`；雪花链 `sale→order→customer→nation→region → AFRICA` |

**对账**：Gremlin 聚合的净收入合计 = Java 直接遍历的合计（193,850,362.75，1e-3 容差），
所以图上的聚合与关系代数结果一致。

```gremlin
g.V('Sale').group().by('region').by(values('revenue').sum())   // 大区净收入
g.V('Sale').values('revenue').sum()                           // 净收入合计
g.V('sale_0','Sale').out('ofOrder').out('placedBy')           // 雪花链（图的 join）
    .out('customerNation').out('nationRegion').values('name')
```

---

## 5. 复现

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-26.0.2.1"

# 生成 schema（graph-http/src/main/resources/tpc-h-analytics.json）
node tools/generate-tpch-schema.js

# 构建 + 用图谱回答 agent 的问题（13 个断言）
./mvnw -o -pl graph-integration-test test -Dtest='TpchDataAgentGraphTest' -Dsurefire.failIfNoSpecifiedTests=false

# 装载服务要读的那份（先停服务）
TPCH_ROCKSDB_RESEED=true ./mvnw -o -pl graph-integration-test test \
    -Dtest='TpchDataAgentGraphTest#loadsTheInstanceTheServiceServes' -Dsurefire.failIfNoSpecifiedTests=false
tools/start-phoenixgraph.sh --with-hnsw     # UI 里选 tpc-h-analytics
```

---

## 6. 一个完整的例子：净收入同比归因

> 用户（自然语言）：「**1996 年的净收入比 1995 年怎么样？变化是什么驱动的？**」
>
> 下面每一步都可直接复制执行（`tpc-h-analytics` 是语义层，`tpc-h-data` 是数据实例）。

### 步骤 1｜语义层：定位指标（不猜指标名）

```gremlin
g.V('Node').has('entityType','Metric').has('name', TextP.containing('revenue')).values('name')
// → [metric/gross_revenue, metric/total_revenue, metric/revenue, metric/promotion_revenue]
```

### 步骤 2｜读指标定义（口径写死在图谱里）

```gremlin
g.V('metric/revenue','Node').values('aggFunction','expression','unit','metricKind')
// → SUM | SUM(l_extendedprice * (1 - l_discount)) | 元 | derived
```

### 步骤 3｜读维度（能不能按时间看？）

```gremlin
g.V('metric/revenue','Node').outE().filter(relation.equals('groupedBy')).otherV().values('name')
// → [dim/mktsegment, dim/region, dim/nation, dim/order_date]
```

### 步骤 4｜读归因上游（谁驱动 revenue？）

```gremlin
// 归因上游：谁驱动 revenue
g.V('metric/revenue','Node').inE().filter(relation.equals('contributesTo')).otherV().values('name')
// → [metric/avg_order_value, metric/order_count, metric/promotion_revenue,
//    metric/tax_amount, metric/discount_amount, metric/gross_revenue]
// 顺带：这个指标属于哪个业务域
g.V('metric/revenue','Node').outE().filter(relation.equals('belongsTo')).otherV().values('name')
// → [domain/sales]
```

→ agent 因此知道：回答"为什么降"要**拆成毛收入 / 折扣额 / 订单数 / 客单价**，而不是只重复一个数。

### 步骤 5｜读血缘与 join 路径（生成 SQL 用）

```gremlin
// 取自哪些表
g.V('metric/revenue','Node').outE().filter(relation.equals('computedFrom')).otherV().values('name')
// → [table/lineitem]
// 用哪些字段
g.V('metric/revenue','Node').outE().filter(relation.equals('usesColumn')).otherV().values('name')
// → [column/lineitem.l_extendedprice, column/lineitem.l_discount]
// join 键（外键 → 主键）
g.V('column/lineitem.l_orderkey','Node').outE().filter(relation.equals('references')).otherV().values('name')
// → [column/orders.o_orderkey]
```

### 步骤 6｜生成的 SQL（每一段都来自上面的图）

> ⚠️ **本仓库里没有 SQL 数据库**（只有图查询引擎 **Gremlin** 和 **Cypher**）。这段 SQL 是
> data agent 的**生成物**，交给真正的数仓（PostgreSQL / DuckDB / StarRocks…）执行。
> **真正跑出下面数字的是图查询**（步骤 7 的 Gremlin / Cypher）——SQL 与图查询是同一个分析意图的两种表达。

| SQL 片段 | 来自图谱 |
|---|---|
| `SUM(l_extendedprice * (1 - l_discount))` | `metric/revenue.expression` |
| `FROM lineitem` | `computedFrom` |
| `JOIN orders ON l_orderkey = o_orderkey` | `references` |
| `GROUP BY EXTRACT(YEAR FROM o_orderdate)` | `groupedBy → dimensionColumn` |

```sql
SELECT EXTRACT(YEAR FROM o_orderdate)             AS year,
       SUM(l_extendedprice)                       AS gross,           -- 驱动因子
       SUM(l_extendedprice * l_discount)          AS discount_amount, -- 驱动因子
       SUM(l_extendedprice * (1 - l_discount))    AS revenue
FROM lineitem
JOIN orders ON lineitem.l_orderkey = orders.o_orderkey
GROUP BY EXTRACT(YEAR FROM o_orderdate)
ORDER BY year;
```

### 步骤 7｜执行（数据实例上的真实数字）

图上的等价查询（`tpc-h-data`，因为指标已物化，一次扫描即可）：

```gremlin
g.V('Sale').group().by('orderYear').by(values('revenue').sum())
g.V('Sale').group().by('orderYear').by(values('gross').sum())
g.V('Sale').group().by('orderYear').by(values('discountAmount').sum())
```

也可以用 **Cypher**（本仓库的声明式图查询，从 anchor 出发走雪花链 join）：

```cypher
MATCH (r:Region {id:'region_0'})<-[:nationRegion]-(n:Nation)<-[:customerNation]-(c:Customer)
      <-[:placedBy]-(o:Order)<-[:ofOrder]-(s:Sale)
RETURN sum(s.revenue) AS revenue
```

两套查询跑出**同一组数字**（已对账：图的 4 跳 join 结果与反规范化属性逐个大区一致）：

| 年 | revenue | gross | discountAmount |
|---|---|---|---|
| 1995 | 95,612,794 | 100,509,809 | 4,897,015 |
| 1996 | 98,879,433 | 104,059,747 | 5,180,314 |
| **Δ** | **+3,266,639** | **+3,549,938** | **+283,299** |

### 步骤 8｜归因：Δ 的对账

```
Δrevenue = Δgross − Δdiscount
+3,266,639 = +3,549,938 − (+283,299)   ✓
```

**结论**：净收入同比增长 **3.27M（+3.4%）**，主因是**毛收入增长（+3.55M，卖得更多）**；
折扣让利也在增加（+0.28M），抵消了一小部分。

### 步骤 9｜下钻：是哪个大区？是「量」还是「价」？

```gremlin
g.V('Sale').has('orderYear',1995).group().by('region').by(values('revenue').sum())
g.V('Sale').has('orderYear',1996).group().by('region').by(values('revenue').sum())
// 再拆一层：Δgross / Δquantity / 均价
g.V('Sale').has('orderYear',1995).has('region','AMERICA').values('quantity').sum()
g.V('Sale').has('orderYear',1996).has('region','AMERICA').values('quantity').sum()
```

| 大区 | Δrevenue | Δgross | Δquantity | 均价 |
|---|---|---|---|---|
| **AMERICA** | **+2,261,797** | +2,375,691 | +2,355 | 999 → 1000 |
| **AFRICA** | **+1,270,487** | +1,333,329 | +1,400 | 1003 → 1000 |
| EUROPE | +152,652 | +215,365 | +192 | 998 → 999 |
| ASIA | −7,489 | +48,028 | −5 | 1001 → 1003 |
| **MIDDLE EAST** | **−410,808** | −422,475 | −328 | 1001 → 996 |

**量价分解**：AMERICA / AFRICA 的增长来自**销量（均价几乎不变）**；MIDDLE EAST 是**销量下滑**。

### 步骤 10｜决策建议（agent 给业务的话）

1. 净收入同比 **+3.4%**，增长来自 **AMERICA（+226 万）和 AFRICA（+127 万）**，两区都是**量增价稳**。
2. **MIDDLE EAST 是唯一负增长（−41 万）**，也是量降（均价还微降），建议作为下一个排查对象。
3. ASIA 基本持平（−0.7 万），可观察。
4. 折扣让利在上升（+28 万），需监控它是否稀释了增长质量（毛利率）。

---

## 7. 已知边界

- **它不是执行引擎**：图谱给出"指标怎么算、用哪些表/字段、怎么 join、受什么驱动"，实际 SQL 由查询层
  按 `references`/`usesColumn` 组装；TPC-H 的 22 个查询只收了 12 个代表性的。
- **归因是结构性的，不是统计性的**：`contributesTo` 表达"定义上谁构成谁"（收入 = 毛收入 − 折扣），
  不是相关性/因果推断。真正的贡献度分解仍要跑数据（按维度聚合再对比）。
- **指标口径是人写的**：`expression` 是策展的标准口径（TPC-H 规范），换一家公司口径不同——这正是
  语义层要显式建模的原因（口径写进图谱，而不是散在每个人的 SQL 里）。

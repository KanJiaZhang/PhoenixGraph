# 设计：给引擎加"带状态的遍历器"（`Traverser{value, path, labels, sack}`）以支持 `as/select/project/match/sack/...`

> 背景：`CONTEXT.md` §87 的复核结论里，最大的一族未支持 step 都需要**每个遍历器自己的状态**：
> `as select match where('a',eq('b')) project sack withSack aggregate store sideEffect cap subgraph tree
> group().by().by() branch emit until loops`。
> 这份文档是那件事的设计，**目标之一是不影响现有功能**：现有管线（列表里一个值）与全部现有 step/测试保持原样，
> 新能力走**第二条执行路径**，由编译器按查询内容选择。

## 1. 今天为什么做不到（现状）

* 管线是 `List<Object>`：一个 step 是 `List<Object> -> List<Object>`（`GremlinStep.apply(ctx, in)`），
  **值本身不携带来历**——没有"这个值是从哪个点走过来的""它带着哪些标签""它的 sack 是多少"。
* 唯一例外是 `path()`：它走的是**另一条执行路径**（`runPathCompiled`），用 `List<List<Object>>`（每条 walk 一个列表）
  人工保存"走过什么"。也就是说，**"带记忆"的能力已经有一个先例**：不改进程内列表语义，而是换一条执行路径。
* 因此 `as/select/...` 不是"多加一个 step"，而是需要一个新的执行模型；硬塞进现有列表会让
  `values/has/filter` 这些大批量、可批读的 step 全部变慢（它们现在靠"扁平列表 + 按顶点 id 批量读"取胜，§78）。

## 2. 设计原则

1. **两条路径，编译器选**：查询里**没有任何**需要遍历器状态的 step → 走今天这条快路径（**一个字节都不变**）；
   有 → 走新的 `traverser` 路径。判据在编译期算好（`CompiledQuery.traverserMode()`），运行期不判断。
2. **新路径复用旧步骤**：不需要状态的 step（`has/filter/values/id/label/hasLabel/hasId/count/order/...`）在 traverser 路径上
   通过一个**适配器**直接调用今天的 `GremlinStep`（把 traverser 的值拆出来、跑、再按原顺序包回去）。只有**需要状态的 step**
   才写新实现。这样新路径的行为与旧路径天然一致（同一段代码），也把新增代码量压到最小。
3. **批读不许退化**：跳步在 traverser 路径上仍然**按顶点 id 批量读**（复用 `readEdgesByVertex` / `GremlinContext.readEdgesCached`），
   不许"每条 traverser 一次存储调用"（那是 §78 修过的坑）。
4. **默认不启用**：新路径只在这些 step 出现时启用；`profile()` 能看出走的是哪条，便于对比与回归。

## 3. 数据结构

```java
/** 一个遍历器：值 + 它的来历 + 它的标签 + 它的 sack。 */
record GremlinTraverser(Object value,          // vertex / edge / value（与今天一致）
                        List<Object> path,      // 走过的点与边（lazy：只有 path()/as() 需要时才记录）
                        Map<String, Object> labels, // as('a') 绑定的值（存的是当时的 value）
                        Object sack,            // withSack(seed) 的累加器
                        long bulk)              // 合并后的条数（默认 1；sack/aggregate 的优化空间）
{ }

/** 一条执行路径的上下文：副作用容器 + 计数器。 */
final class TraverserContext {
    private final GremlinContext elements;        // 现有的读路径、缓存、子图收集（复用！）
    private final Map<String, List<Object>> sideEffects;  // aggregate/store/cap 的容器
    private final Map<String, Long> counters;             // loops() 等
}
```

* `path` 用 **lazy** 记录：`as()` 只需要"当前值"，`path()`/`select()` 需要来历；由一个 `needsPath` 标志决定是否 append（省内存）。
* `labels` 存"值"而不是"下标"，`select('a')` 直接返回它；TinkerPop 的 `Pop`（首个/最后/全部）先只支持"最后"（默认），其余报错。
* `sideEffects` 直接等价于今天的 `Subgraph` 收集思路：一个名字到容器（list/map）的映射；`cap('x')` 取值。

## 4. 执行路径

```
runCompiled(context, compiled)
  ├─ compiled.traverserMode() == false → 今天这条（不变）
  └─ true → runTraverserCompiled(context, compiled)
       1. 起点 → 把元素包成 GremlinTraverser（批量读、投影照旧）
       2. 顺序跑每个 step：
          * 需要状态 → GremlinTraverserStep.apply(tctx, traversers)
          * 不需要 → 适配器：values() → 跑旧 GremlinStep → 重新包回 traverser（保持顺序与 path/labels）
       3. 收尾：答案仍是"值列表"（把 traverser.value 取出来），与今天返回的形状完全一致
```

* **跳步的批读**：`TraverserStep` 版的 `out/in/both/outE/...` 收集所有 traverser 的当前顶点 id →
  一次批量读（今天的 `readEdgesByVertex`）→ 按 traverser 顺序展开成新 traverser（各自 append 边与点、复制 labels、保留 sack）。
* **`path()`/`simplePath`/`cyclicPath`**：现有的 `runPathCompiled` 与新的 traverser 路径**合并**：traverser 的 `path` 就是它的 walk，
  于是 `runPathCompiled` 变成 traverser 路径的一个特例（这一步留到第 3 批，先保留两套，保证回归安全）。
* **`repeat()/emit()/until()/loops()`**：需要"同一批 traverser 反复跑一个子链"，做成 `TraverserStep` 的循环实现：
  `while (cond) { apply(child); emit 收集; }`，`loops` 从 `counters` 读。上限仍用 `MAX_LOOP_ITERATIONS` 同款保护。

## 5. 分批落地（每批都必须让现有测试保持全绿）

| 批次 | 内容 | 新增/改动 | 验收 |
|---|---|---|---|
| **1** | `as/select/project`、`where('a',eq('b'))`、`by()` 调制器扩展到 `project/select` | 新文件 `GremlinTraverser`、`TraverserContext`、`GremlinTraverserSteps`（3 个 step + `by`）；`GremlinGraphQuery` 加 `traverserMode` 判定与 `runTraverserCompiled`；`GremlinScript`/`values` 不变 | 现有 **275** 用例全绿；新增"同一查询两条路径答案一致"的对照用例；`g.V('Account').as('a').out('Payment').select('a','b')` 与手写 join 结果一致 |
| **2** | `sack/withSack`、`aggregate/store/sideEffect`、`cap` | `TraverserContext.sideEffects` + 4 个 step；`sack(assign/sum/min/max).by(...)` | 同上；`sideEffect(count())`/`aggregate('x').cap('x')` 结果与脚本层手工累积一致 |
| **3** | `match`、`branch/choose().option()`、`emit/until/loops`、`path().from()/to()`、`subgraph/tree` | 把 `runPathCompiled` 并入 traverser 路径（保留旧实现直到对照全绿）；`subgraph` 用 `TraverserContext` 的副作用容器 | 同上；`path()` 的**全部**现有用例在两条路径下都跑一遍 |
| **4**（可选） | `group().by().by()`、`math` 的变量形式、`by(traversal)` 全量 | 复用 1–3 的机制 | 同上 |

## 6. 对现有功能的影响（明确清单）

| 现有部分 | 影响 | 说明 |
|---|---|---|
| 列表快路径（所有现有查询） | **无** | 编译器判定"不需要状态"时走的还是今天的代码路径 |
| 现有 step 实现 | **无改动** | 新路径通过适配器调用它们 |
| `path()` 模式 | 第 3 批才合并 | 第 1–2 批保留两套；合并时有"两条路径答案一致"的对照用例 |
| 批读与缓存（§78 的优化） | **必须保持** | 跳步在 traverser 路径上同样按 id 批量读；性能对照用 `profile()` |
| 分布式执行（§85/§86 的 `DIST`） | 明确**互斥** | 第 1 批里 `DIST` + 状态 step 直接报错（"distributed execution has no per-traverser labels"），不做半吊子支持 |
| 子图收集（UI 画布） | 无变化 | `recordVertex/recordEdge` 沿用；`subgraph()` 第 3 批按"把收集到的子图当一个值"实现 |
| 脚本层（§90/§91） | 无变化 | 脚本跑的还是这些 step；新 step 出现在脚本里自然可用 |
| 写入家族 / `with*` | 维持不支持 | 与本次设计无关 |

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| 新路径悄悄变慢（对象多、批读退化） | 每批都跑 `profile()` 与现有基准（`.logs/bench-*`）；批读路径复用同一个 `readEdgesByVertex` |
| 两条路径行为不一致 | 适配器调用**同一个** step 实现；每条新能力都加"同一查询两条路径答案一致"的对照用例 |
| `path()` 合并引入回归 | 第 3 批才动，且 `GremlinPathTest` 全部用例在两条路径下各跑一遍 |
| 内存（path/labels 复制） | `path` lazy；labels 存值引用；必要时加"超过 N 个 traverser 就报错"的保护（现有 `scanLimit` 同款） |
| 与 `DIST` 混用 | 明确互斥并报错（见上表） |

## 8. 工作量估计（按本仓库的粒度）

* 第 1 批：新增约 350–450 行（traverser 数据结构 + 适配器 + 3 个 step + 判定与执行路径 + 用例），改动集中在
  `GremlinGraphQuery`（新增方法，不改现有方法体）与 3 个新文件；
* 第 2 批：约 250 行；第 3 批：约 400 行（含 path 合并）；
* 每一批都可独立提交、独立回滚（新路径没有启用时等价于没有它）。

## 9. 结论

`as/select/project/...` 这一族值得做，但**不是"再加几个 step"**：它要的是给遍历器带上状态，然后让编译器按查询选择执行路径。
本设计把它做成**加法**（新路径 + 适配器）而不是**改造**（改列表语义），所以：
现有查询、现有 step、现有批读优化、UI 与脚本层**都不受影响**；每一步都有"两条路径答案一致"的对照用例兜底。
建议按 §5 的批次一件一件来，第 1 批（`as/select/project` + `where('a',eq('b'))`）就能覆盖绝大多数真实查询。

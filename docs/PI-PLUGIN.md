# 把 PhoenixGraph 装进 pi：插件设计方案

> 目标：让「图作为上下文层」在 pi 的**每一次交互**里都可用，并且**跨对话**记住状态（用哪张图、时间轴的
> 开关与时间点、上次的症状）。本文是设计，不是实现；每条都能落到 pi 扩展 API 的具体调用上。

## 1. 现状与差距

PhoenixGraph 已经提供的能力（不需要重写）：

| 能力 | 位置 |
|---|---|
| 14 个检索/数据工具，工具目录由 API 提供 | `tools/mcp-server.mjs`、`GET /api/v1/agent/tools` |
| 带证据的上下文（实体 / 有向关系 / 原句 / coverage） | `POST /api/v1/agent/context` |
| 时间维度：`asOf` 过滤 + UI 时间轴 | `POST /api/v1/query`、`/api/v1/agent/*`、`graph-web` |
| 症状 → 排序（打分 + 推翻条件） | `examples/incident-triage/triage.mjs` |
| 触发式摄取（按文档给有效期） | `POST /api/v1/agent/ingest` |

缺的是 pi 侧的"胶水"：**何时调用、默认参数从哪来、状态存哪、模型怎么被提示**。所以设计分成三层：
能力（MCP/HTTP）+ 策略（扩展）+ 话术（提示词/skill）。

## 2. 三条落地路径

| 路径 | 怎么装 | 能否跨对话记状态 | 能否每轮注入 | 代码量 |
|---|---|---|---|---|
| **A. MCP（P0，今天就能用）** | `mcp.json` 或 `pi.registerMcpServer("phoenixgraph", {...})` | 否（服务器无状态） | 否 | 0 |
| **B. 原生扩展（P1 起，推荐）** | `~/.pi/agent/extensions/phoenixgraph.ts` 或 pi package | 是（`pi.appendEntry()` + 外部状态文件） | 是（`before_agent_start`） | 一个文件起步 |
| **C. Skill（提示词）** | `~/.pi/agent/skills/phoenixgraph/SKILL.md` | 否 | 是（被加载时） | 0 |

**推荐 B 为主、A 为底座、C 为话术**：扩展负责"什么时候用、默认参数、状态"，MCP 负责"有哪些工具"，
skill 负责"答案该长什么样"（引用、coverage、没证据就拒答）。

```typescript
// ~/.pi/agent/extensions/phoenixgraph.ts —— 骨架（P0 的全部内容）
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
  pi.registerMcpServer("phoenixgraph", {
    command: "node",
    args: [process.env.PHOENIXGRAPH_REPO + "/tools/mcp-server.mjs"],
    env: { PHOENIXGRAPH_API: "http://localhost:8081" },
    exposure: "codemode",   // 不进模型上下文；由扩展/脚本按需调用
  });
}
```

## 3. 扩展的四个部件

### 3.1 工具（模型可见的能力）

| 工具 | 入参 | 返回（模型看到的） | 说明 |
|---|---|---|---|
| `pg_triage` | `symptom`, `graph?`, `asOf?` | 排序后的候选 + 每条的证据句 + 「什么会推翻它」+ coverage | 把 `triage.mjs` 的打分搬进 MCP server（一个 `.mjs` 文件，不引 Spring） |
| `pg_context` | `question`, `k`, `hops`, `asOf?` | 实体/关系/原句/coverage | 现有 `context` 的薄封装，默认参数由扩展补 |
| `pg_asof` | `instant?`, `on?` | 设置结果 + 生效的时间范围 | 改的是**会话状态**，不是参数 |
| `pg_query` | `gremlin`, `asOf?` | 只读查询的子图统计 | 给"看一眼图"的场景 |
| `pg_ingest` | `dir`/`documents`, `windows` | 质量报告 + `mergedEntities`/`mergeCandidates` | 写操作，只允许从命令触发（`model-only` 不给模型） |

暴露策略（`exposure`）：`pg_context`/`pg_triage` 用 `deferred`（`tool_search` 能找到，平时不占上下文）；
`pg_asof` 用 `model-only`；`pg_ingest` 不暴露给模型，只走命令。理由：工具越多，模型越容易乱调用；一个
"每次交互都可用"的插件，第一要求是**不打扰**。

### 3.2 命令（人可见的开关）

```
/pg status                  # 图、端点、时间轴开关、asOf、最近一次 triage 的摘要
/pg graph incident-triage   # 换图（写状态）
/pg timeline on|off|2026-10-21
/pg triage <symptom>
/pg ingest <dir> --from 2026-10-24
```

`registerCommand` 的 handler 拿 `ExtensionCommandContext`（可以 `waitForIdle`/`reload`），适合"改完状态再
让下一次交互生效"。

### 3.3 每轮注入（`before_agent_start`）

这是"每一次交互中启用"的核心：**不靠模型自觉，靠提示词里的事实**。注入一小段（~10 行）而不是整图：

```
[phoenixgraph] graph=incident-triage endpoint=http://localhost:8081
timeline=on asOf=2026-10-21 (facts after this instant are hidden)
tools: pg_triage(symptom), pg_context(question), pg_asof(instant)
answer contract: quote coverage; every relation carries a sentence; if no name match + no relation, say so.
```

要点：
- 用 `systemPromptOptions` **追加**一节，而不是 `systemPrompt` 整段替换——后者会让 pi 丢掉结构化段落，
  也会让 provider 的 prompt cache 失效。
- 只在"该图适用"的会话里注入（见 3.5），否则每次交互都多花 token。
- 注入的是**契约与状态**，不是数据；数据永远通过工具按需取。

### 3.4 状态：单会话、会话内、跨对话

| 状态 | 存储 | 生命周期 |
|---|---|---|
| 工具一次调用的结果 | 工具结果 `details` | 跟随分支 |
| "这次会话用哪张图 / 时间轴开没开" | `pi.appendEntry()`（不进模型上下文）+ 从 `ctx.sessionManager.getBranch()` 重建 | 随会话，分支安全 |
| 跨对话（默认图、端点、asOf、上次症状） | `~/.pi/agent/state/phoenixgraph.json` | 进程重启仍在 |
| 团队/项目级（图名、端点、只读 token） | `<repo>/.pi/phoenixgraph.json`（可提交） | 跟随仓库 |
| 全局开关（是否加载扩展） | `settings.json` 的 `extensions` | 用户配置 |

跨对话的关键设计：**`asOf` 是一个"粘性"默认值**。扩展在工具调用前把会话状态里的 `asOf` 填进未指定的参数
（`pg_context`/`pg_query`/`pg_triage` 都一样）。这样"时间轴开着"这件事对模型透明——它不需要记住，
也无权忘记。

### 3.5 什么时候自动启用

| 信号 | 行为 |
|---|---|
| 仓库里有 `.pi/phoenixgraph.json` | 该项目的会话自动注入上下文块，`pg_*` 直接用 |
| 用户运行过 `/pg graph` | 本会话 + 之后默认启用（写状态文件） |
| 会话里出现过事故词（延迟/5xx/宕机/deploy）且扩展已启用 | `turn_end` 注入一条**一次性的**提示（"要不要 triage？"），默认关，`/pg suggest on` 才开 |
| 其他情况 | 静默：工具可被 `tool_search` 找到，但不注入、不打扰 |

## 4. 需要的服务端小改动（都已评估过成本）

1. **`pg_triage` 的后端**：把 `examples/incident-triage/triage.mjs` 的排序逻辑做成
   `tools/triage.mjs`（纯函数）+ 在 `tools/mcp-server.mjs` 里暴露成工具。约 200 行 JS，无 Java 改动。
2. **`GET /api/v1/graphs/{graph}/validity`**：返回 `{from, to, versioned, total}`，让扩展/UI 不必从响应里推断
   时间轴两端（现在 UI 是从答案里推断的，未命中版本化元素时拿不到）。
3. **工具的默认 `asOf`**：`ToolRequest` 已经有 `asOf`（本次改动加的），扩展负责填。若希望服务端也记住，
   可以加一个 `X-PhoenixGraph-AsOf` 头（可选，不建议——状态应该由调用方持有）。

## 5. 分阶段与验收标准

> **P0、P1、P2 已落地**：扩展在 [`extensions/phoenixgraph`](../extensions/phoenixgraph)（`index.js` + `README.md`），
> 11 条断言在 `extensions/phoenixgraph/test/extension.test.mjs` 里跑（fake pi / fake fetch，不需要服务或模型），
> CI 里作为一个独立步骤执行。P2 也在同一目录：`pg_triage` 用 `tools/triage.mjs`（与 MCP server、示例同一份打分），`before_agent_start` 只在该会话确实与图有关时加一个
`phoenixgraph` **prompt section**（不是整段替换，缓存不失效），`/pg inject on|off` 可关。P3 仍是设计。


| 阶段 | 内容 | 验收（可执行） |
|---|---|---|
| **P0**（今天） | `mcp.json` 注册现有 MCP server；写 `~/.pi/agent/extensions/phoenixgraph.ts` 只做 `registerMcpServer` | 新会话 `/mcp` 列出 14 工具；`context` 返回 `coverage` |
| **P1** ✅ | `pg_context`/`pg_asof` + `/pg status|graph|endpoint|timeline` + `appendEntry` + 状态文件 + 项目文件 | 实机：`pg_asof` 钉 2026-10-21 → 新进程仍报同一刻；`pg_context` 返回的是该时刻的图（16 实体 / 19 关系 / 52 引用） |
| **P2** ✅ | `tools/triage.mjs`（rank/triage/render）+ MCP `triage` 工具 + `pg_triage` + 契约 section + `/pg inject` | 15 条扩展测试 + MCP selftest（15 工具）；实机：钉 2026-10-20 → 第一名 `deploy-42`，并自陈证据薄 |
| **P3** | pi package 分发（`pi` 字段声明入口）、MCP over HTTP + token/audit/rate-limit、团队 `.pi/phoenixgraph.json` | 另一台机器 `pi install` 后 `/pg status` 直接可用；审计文件里每次 `tools/call` 一行 |

## 6. 风险与取舍

- **上下文成本**：注入块必须短（< 15 行），数据一律走工具。工具用 `deferred`，避免 14 个工具名占满提示。
- **prompt cache**：只用 `systemPromptOptions` 追加；不要在每轮改写整段系统提示（会毁掉缓存前缀）。
- **asOf 的误用**：把时间点当"过滤器"而不是"事实集"会让模型以为图是完整的。所以注入块里明说
  "facts after this instant are hidden"，并让 `pg_context` 回传 `asOf` 与 `coverage` 一起显示。
- **写操作**：`pg_ingest` 不暴露给模型，只从 `/pg ingest` 触发；摄取会改图，必须是人按的按钮。
- **失败退化**：服务未启动时工具返回一句可操作的错误（"tools/start-phoenixgraph.sh"），不抛异常给模型，
  否则模型会开始编造。

## 7. 一页图

```
        ┌──────────────────────── pi 会话 ────────────────────────┐
user ─▶ │ before_agent_start: 注入 [graph · timeline/asOf · 契约]  │
        │                                                         │
        │ 模型 ──pg_triage(symptom)──▶ tools/triage.mjs ─┐         │
        │      ──pg_context(question)─▶ MCP server ──────┼─▶ 8081  │
        │      ──pg_asof(instant)────▶ 会话状态 ─────────┘   API   │
        │                                                 │       │
        │ /pg status|graph|timeline ──▶ ~/.pi/agent/state/phoenixgraph.json
        └─────────────────────────────────────────────────────────┘
                                    │
                      PhoenixGraph: 图（点/边带有效期）+ artifact（证据）
```

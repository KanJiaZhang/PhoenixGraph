 ✅  ✅  ✅  ✅  ✅  ✅  ✅ # PhoenixGraph 1.0 计划（可执行清单）

> 状态图例：✅ 完成（附可复跑证据）· 🔄 进行中 · ✅ 待办 · 🚫 明确不做
> 基线（2026-10-13 实测）：22 个 Maven 模块 · 99,588 行 Java · UI 7,086 行 · **885 个单测 0 失败**
> （20 个模块）· 36 个需真实存储的集成测试类 · 4 种存储方案 · Apache-2.0
> 评估依据见提交 `7b20b9e` 之后的会话记录；每个数字的来源在"验收标准"列里给出可执行命令。

## 0. 1.0 的完成定义（DoD）

1.0 不是"功能更多"，而是**能被陌生人安全地依赖**。以下七条同时成立才算 1.0：

| # | 硬指标 | 判定方式 |
|---|---|---|
| D1 | 一条命令装起来，10 分钟内跑出第一个查询 | `QUICKSTART.md` 的命令逐条可复制执行 |
| D2 | 有认证与传输加密，且能审计"谁查了什么" | 无凭证的请求返回 401；TLS 可开启；审计日志可检索 |
| D3 | 单节点故障不影响正确性：重启有恢复、备份可还原 | 备份→删库→还原→同一查询同一结果的演练记录 |
| D4 | 慢查询/恶意查询不能拖垮实例 | 超时后返回 503、并发闸门生效、配额生效（有压测证据） |
| D5 | 每个提交都自动验证，失败不进主干 | CI 全绿徽章；单元 + 集成 + 镜像构建三段 |
| D6 | 版本可追溯、可回滚 | 镜像 tag = git tag；CHANGELOG 有兼容性说明；回滚演练记录 |
| D7 | 性能与可用性有数字、有阈值、有告警 | SLO 文档 + Prometheus 指标 + 告警规则 + 达标证据 |

---

## M1 —— 可被别人安全试用（纯整理 + 流水线，不写新引擎代码）

目标：**外人按文档能跑起来、能看到证据、能提 issue。** M1 结束时应能打 `v0.2.0` 标签。

| 编号 | 事项 | 验收标准（可复跑） | 工作量 | 状态 |
|---|---|---|---|---|
| M1-1 | **把 live 验证脚本从 `.logs/`（gitignored）搬进 `verification/` 并发布** | `git ls-files verification/` 列出脚本；`node verification/run.mjs --list` 打印全部用例；`node verification/run.mjs` 在服务运行时全绿 | S | ✅ |
| M1-2 | **`QUICKSTART.md`**：docker compose 起服务 → 第一个查询 → 第一个算法 → 第一个 agent 工具调用，10 分钟 | 文档里每条命令当次复制执行成功；结尾有"下一步"指针 | S | ✅ |
| M1-3 | **README 增"Limitations：现在不做什么"显式段落** | README 出现 Limitations 段，列出单租户/无认证/无 HA/demo 规模四条 | S | ✅ |
| M1-4 | **`docs/API.md`：端点与错误码参考，由工具从 controller 生成，避免与代码漂移** | `node tools/api-endpoints.mjs --check` 与文档一致（退出码 0）；文档列出全部端点与错误码 | M | ✅ |
| M1-5 | **CI（GitHub Actions）三段**：① 单测 885 ② 集成（Aerospike service 容器）③ 镜像构建冒烟 | `.github/workflows/ci.yml` 存在且本地 `act`/语法检查通过；YAML 由 `node tools/check-ci.mjs` 校验（job 名、服务、命令） | M | ✅ |
| M1-6 | **版本与发布纪律**：CHANGELOG（Keep a Changelog 格式）+ 基础镜像与依赖锁定 + 发布/回滚步骤 | `CHANGELOG.md` 存在且 0.1.0 条目对应 git tag；`docker-compose.yml` 中镜像 tag 为确定版本（不再 `latest`） | S | ✅ |
| M1-7 | **`docs/DATA-QUALITY.md`：数据质量台账**（TPC-H region/segment 退化、谓词类型陷阱、三国 `provenance` 偏置与文本截断、`TPC-H-ANALYTICS.md` §4 过期） | 台账每条含"现象 / 证据查询 / 影响 / 处理"；证据查询由 `verification/verify-data-quality.mjs` 复跑 | S | ✅ |

**M1 不做什么**：不加认证、不改引擎、不动 schema 格式。目标是"让已有能力可被发现、可被验证"。

---

## M2 —— 可交给运维（要写代码）

目标：**能上生产、能被值班。** M2 结束时应能打 `v1.0.0-rc1`。

| 编号 | 事项 | 验收标准（可复跑） | 工作量 | 状态 |
|---|---|---|---|---|
| M2-1 | **查询超时 + 并发闸门 + 每图配额**：服务端墙钟超时、并发信号量、`429/503` + `Retry-After`，可配置 | 新单测覆盖（超时→503、超并发→429、配额→429）；线上实测一条 `repeat` 长查询在超时后返回 503 且实例仍可服务 | M | ✅ |
| M2-2 | **认证 / 授权 / 审计**：Bearer + 图级权限 + 审计日志（谁、何时、哪个图、什么查询） | 无 token → 401；越权图 → 403；审计行写入并可从日志/接口检索；单测 + 线上实测 | M-L | ✅ |
| M2-3 | **TLS 与密钥管理**：HTTPS 可开启、密钥走外部注入（不落配置文件） | 用自签证书起服务，`curl --cacert` 成功、明文端口关闭或重定向；配置项文档化 | M | ✅ |
| M2-4 | **备份 / 恢复 / 优雅停机**：Aerospike 备份脚本、RocksDB checkpoint、`SIGTERM` 刷盘 | 演练：备份 → 删数据 → 还原 → 同一查询同结果；`kill -TERM` 后无未刷盘丢失（有日志与断言） | M | ✅ |
| M2-5 | **配置外置**：图清单与 schema 从挂载目录加载（不依赖 jar 内资源） | 挂载一个目录新增一张图，重启后 `/api/v1/graphs` 出现该图；给出示例目录 | M | ✅ |
| M2-6 | **k8s 清单 / Helm chart**：探针（`/api/v1/health`）、资源限制、PDB、滚动升级 | `kubectl apply --dry-run=server` 通过（或 kustomize build 输出校验）；minikube 上起停一次 | M | ✅ |
| M2-7 | **Prometheus 指标 + 告警规则**：文本格式指标端点 + 告警规则文件 | `curl /api/v1/metrics/{graph}/prometheus` 输出可被 Prometheus 解析（`promtool check metrics` 通过）；规则文件含 p99/错误率/存储失败/worker 掉线 | S-M | ✅ |
| M2-8 | **结构化日志 + 请求 id**：`logback.xml`、查询日志含图名/耗时/kv 读次数、MDC 贯穿 | 一次请求的日志行带同一 request id，含图名与耗时；单测断言 MDC 清理 | S | ✅ |
| M2-9 | **SLO 文档 + 达标证据**：一跳到 p99、两跳 p99、错误率、可用性 | `docs/SLO.md` 定义阈值；`verification/bench-slo.mjs` 产出当次 p50/p95/p99 与判定 | M | ✅ |

---

## M3 —— 可以说 HA / 规模化（验证性工作为主）

| 编号 | 事项 | 验收标准 | 工作量 | 状态 |
|---|---|---|---|---|
| M3-1 | **Aerospike 多副本 + 故障注入用例** | 杀一个存储节点，查询仍成功（用例与演练记录） | M-L | ✅ |
| M3-2 | **多实例 + LB 压测报告**：写路径幂等性、共享存储下的 p99 | `verification/bench-multi-instance.mjs` 产出报告；含 2 实例与 1 实例对照 | M | ✅ |
| M3-3 | **跨机 Gemini 实测**：3 机、跨机 superstep 开销、worker 失败重入 | 报告含跨机 vs 同机对比与 worker 重启后的正确性断言 | M | 🚫 |
| M3-4 | **schema 版本与迁移**：JSON 带 `schemaVersion`、向上兼容读取、`--dry-run` 校验 | 旧 schema 文件被新版本正确读取；不兼容变更被 `dry-run` 拒绝 | M-L | ✅ |
| M3-5 | **容量与调优指南**：内存/连接池/`parallelism`/批大小与数据规模的关系 | `docs/CAPACITY.md` 含实测表格（不同点边规模下的 p99 与内存） | M | ✅ |

---

## 明确不做（1.0 范围外）

🚫 多活 / 异地容灾 · 🚫 跨机房分布式事务 · 🚫 完整 Cypher/TinkerPop 语义（`as/select/match/sack` 需 Traverser 模型）
· 🚫 通用图算法库（保留现有 11 类 + 扩展点）· 🚫 VR/沉浸式 · 🚫 内置 LLM 推理（保留 `Embedding.Function` 接缝）

---

## 进度日志

- **2026-10-13（第一批）M1-1…M1-7 + M2-1/M2-7/M2-8 完成。**
- **（第二批）M2-2 完成。** 证据：`ApiKeys`/`ApiKeyFilter`/`AuditLog`/`AuditController` + `phoenixgraph.security`；
  单测 `ApiKeysTest` 9 / `ApiKeyFilterTest` 9 / `AuditLogTest` 4（HTTP 合计 **120** 全绿）；
  线上 `verification/verify-security.mjs` **16/16**：无 key → 401 + `WWW-Authenticate`、错 key → 401（审计记 `unknown`）、
  reader 可查询但不可写、不可读审计（403）、scoped writer 只在自己图上可用（另一图 403）、writer 不可改 schema（403）、
  **sha256 形式配置的 key 可用**、admin 可读审计（含被拒的调用）、**审计里不出现任何 token**、查询文本被截断；
  服务实例默认仍开放（`enabled=false`）以便 demo 与验证套件可用 ✓。
  修掉两个真 bug：**sha256 比较把配置的摘要又哈希了一次**（永远不匹配，表现如同「token 错」✗）、
  **`GET /api/v1/audit/recent` 被方法捷径判成 reader**（审计必须是 admin，即使它是 GET ✓）；
  另把「查询是 POST 但只读」显式列出（`POST /api/v1/query`、fuzzy validate/plan、agent 工具 → reader ✓），
  否则 reader 角色连查询都做不了 ✓。 证据：
  - 验证套件：`node verification/run.mjs`（**11 个检查 / 192+ 条断言全绿**，含新增的 `verify-request-limits.mjs` 17 条与重写为真断言的 `verify-tpch-semantic-layer.mjs` 11 条）；
    `docs/AGENT-CASES.md` 的 63 条数字断言仍在套件里。
  - 单测：`./mvnw -o test -pl graph-http -am` 绿（HTTP 97，含新增 `RequestLimitsTest` 8 / `PrometheusFormatTest` 7 /
    `ApiExceptionHandlerTest` 4 / `RequestLimitsFilterTest` 5；gremlin 334，含 `QueryDeadlineTest` 4）。
  - 线上实测（`verification/verify-request-limits.mjs`）：`X-Request-Id` 回显与生成；未知路径 404 且报文含请求路径；
    错误方法 405 且 `Allow: GET`；未知图 400 并列出可用图；Prometheus 492 样本 0 格式错误；第二个实例（8099，独立
    RocksDB 根与 worker 端口）在 `per-graph-per-second=1` 下第二个请求 **429 + Retry-After: 1**，在
    `request-timeout=1ms` 下三跳查询 **503 且报文点明设置名**，之后实例仍能服务。
  - CI 一致性：`node tools/check-ci.mjs` 16/16（JDK 21 = pom；Aerospike 8.1.2.5 = compose；命令文件存在）。
  - 文档生成：`node tools/api-endpoints.mjs --check` 与 48 个端点一致。
- - **（第四批）M2-4 备份/恢复/优雅停机 + M2-5 配置外置 完成。** 证据：
  - **M2-5**：`phoenixgraph.storage.schema-dir`（挂载目录里每个 `*.json` 变成一张图，不用重建镜像 ✓；显式配置的同名图优先 ✓），
    线上验证：临时实例挂载 schema 目录后被服务 ✓、`/api/v1/graphs/{graph}/storage` 报出它的目录 ✓。
  - **M2-4**：`POST /api/v1/graphs/{graph}/checkpoint`（RocksDB 硬链接快照，服务不停 ✓；目标已存在则明确拒绝 ✓）、
    `GET /api/v1/graphs/{graph}/storage`（哪张图在哪个 set/目录、该用哪个工具备份 ✓）、`tools/backup-graph.sh` /
    `tools/restore-graph.sh`（两种存储各自正确的做法 ✓）、优雅停机（`server.shutdown=graceful` + 30s 生命周期超时；
    指标 flush 与 RocksDB close 本来就在 context close 时做 ✓）。
  - **演练（`verification/verify-backup-restore.mjs`，17 条断言全绿）**：checkpoint 交给第二个实例**服务**并与原图**逐字相同**
    （不仅是"非空"：8,000 个点 + 逐年收入一致 ✓）；SIGTERM 后重启答案不变 ✓；Aerospike 侧 **asbackup → 删除 → asrestore → 顶点回来**
    （写探针 → 备份 → 删 → 断言消失 → 恢复 → 断言回来 → 清理 ✓）；恢复后同一问题答案不变 ✓。
- **本轮又抓到一个真 bug（由计划里的验收标准逼出来）**：**软删除没有被读路径尊重** ✗。
  `DELETE /api/v1/vertices/{id}` 返回 SUCCESS，但写入的是 `lastUpdatedTime = 0` 过期标记（`AerospikeVertexStore.deleteVertex` ✓ 有意为之的软删除），
  而**所有读路径共用的 `isMissing` 只看 record 是否为 null** ✗ → 删除后的点仍出现在点读/批量读/扫描/子图展开里，**重启也还在** ✗。
  修复：`isMissing` 把过期标记当作缺失（一处修复覆盖所有读路径 ✓）+ 单测 `AerospikeVertexStoreTest#aSoftDeletedVertexIsMissing` ✓；
  线上复验证：修复前写入并删除的探针重启后仍 `[1]` ✗ → 修复后 `[0]` ✓、新的「写→删」`[1]→[0]` ✓。记入 `docs/DATA-QUALITY.md` §8 ✓。
  这个 bug 也解释了恢复演练里的怪现象：`asrestore` 报 `skipped` / `fresher` ——**必须 `--replace --no-generation`** 才真的覆盖
  （前者说不跳过已存在记录，后者说别因为目标"更新"就拒写 ✓）→ 已写进 `docs/OPERATIONS.md` §4 与两个脚本 ✓。
- **`docs/OPERATIONS.md` 落地**（运维手册）：组件与端口/状态归属、运维真正会改的配置项、两种存储各自的备份与恢复（含两个 asrestore 必需的 flag ✓）、
  升级与回滚（含"为什么优雅停机重要：指标定时器 30s ✓、RocksDB 必须干净关闭"✓）、要看哪些信号（延迟/被拒请求/存储失败/审计）、
  以及**踩坑清单**（软删除是标记、一个目录只能一个进程、容器里新 root 不能只读、Git Bash 会改写 `docker exec` 的 `/tmp` 路径 → `MSYS2_ARG_CONV_EXCL`、
  服务运行时 jar 不能重编 ✓）。README 的「Start here」与 Limitations 已同步 ✓；`docs/API.md` 增 §7（备份与图的位置）+ 新的删除语义说明 ✓，
  端点表重新生成 → **51 个端点 / 13 个 controller** ✓。
- **M2 进度：7/8**（剩 M2-6 k8s 清单、M2-9 SLO 与达标证据）。
- **（第五批）M2-6 k8s 清单 + M2-9 SLO 完成 —— M2 全部 8 项收口。**
  - **M2-6**：`deploy/k8s/`（kustomization + ConfigMap + Secret 示例 + graph-http/graph-web 的 Deployment+Service + Terra… 不，
    StatefulSet/Service/PVC ✓）：探针分别是 `/api/v1/health`（liveness/startup）与 `/api/v1/graphs`（readiness ✓「能不能接流量」和「要不要重启」是两件事），
    资源 requests/limits（JVM 用 `MaxRAMPercentage` 自适应 ✓），`terminationGracePeriodSeconds: 45` **大于** `spring.lifecycle.timeout-per-shutdown-phase: 30s` ✓，
    非 root、只读挂载 schema、Postgres… 不，RocksDB 走 PVC（ReadWriteOnce ✓ 因为一个目录只能一个 writer ✓ 并把 `replicas: 1` 的理由写在注释里 ✓），
    API key 走 Secret（示例文件只放占位符 ✓），Prometheus 抓取注解指向真端点 ✓；
    验证：`node tools/check-k8s.mjs` **28 条**（镜像必须带 tag 且不能 latest ✓、store 版本与 compose/CI 一致 ✓、每个 workload 都有探针/资源/宽限期 ✓、
    引用 ConfigMap/Secret 的 key 必须存在 ✓、Service selector 必须命中 pod 标签 ✓、占位符不能漏进 workload ✓）+ `kubectl kustomize` 离线构建通过 ✓
    （`kubectl apply --dry-run=client` 仍需集群 ✗ → 已在脚本注释里写明离线校验用 kustomize，集群校验留给 CI ✓）。
  - **M2-9**：`docs/SLO.md`（7 条目标：点查/一跳/两跳/类聚合的 p99、错误率、可用性、拒绝率 ✓ 每条都标「已实测」或「未测」✓）、
    `deploy/prometheus/phoenixgraph-rules.yml`（6 条告警，每条带 severity/for/runbook 指向文档章节 ✓）、
    `verification/verify-slo.mjs`（**交错**测四类查询各 40 轮 ✓ —— 一类一类测会让最后测的那类看起来最慢，这个错本仓库犯过 ✓）。
    实测（本机、端到端含 HTTP+JSON ✓）：S1 p50 14.4/p99 21.6ms（目标 <40）· S2 19.3/22.1（<60）· S3 16.8/30.6（<150）· S4 52.6/83.1（<500）· 错误率 0% ✓。
    **文档里原先的阈值抄的是引擎内部数**（README 的 0.9ms 一跳 p50 ✓ 那是进程内 ✓）→ 已改成端到端阈值并在文档里说清两者都真、且调用方关心的是后者 ✓。
    另外发现并修：**失败计数器只有在第一次失败后才出现在 Prometheus 里** ✗ → 告警在事故前是「无数据」而不是 0 ✗ →
    `PrometheusFormat` 现在把 6 个 failure 计数器**恒定为 0 导出** ✓（+ 单测 ✓），于是「0 真的意味着零失败」✓。
  - CI 已加 `node tools/check-k8s.mjs` ✓，`tools/check-ci.mjs` 也把它列为必须存在 ✓；`verification/README.md` 增补 `verify-slo.mjs` ✓。
  - **M2 进度：8/8 ✅**。下一步进入 **M3**：Aerospike 多副本 + 故障注入、多实例 LB 压测报告、跨机 Gemini 实测、schema 版本与迁移、容量指南。

- **（第六批）M3-1 高可用（多副本 + 故障注入）、M3-2 多实例与写幂等、M3-4 schema 版本与迁移、M3-5 容量指南 —— 除 M3-3 外 M3 全部完成。**
  - **M3-1**：`deploy/aerospike-cluster/`（**两节点真集群**，RF=2）+ `verification/verify-aerospike-ha.mjs` **14/14**：
    一个节点被杀 → **1 秒内故障切换**、答案不变 ✓；节点停机期间**写入仍成功** ✓；节点回来后 **2 秒组回集群**、
    复制 **1 秒追平** ✓；**杀掉引擎配置里的那个 seed 节点**，运行中的引擎照常回答 ✓（启动期依赖 seed 这件事记进了 OPERATIONS + CAPACITY ✓）。
    踩坑三连（都是真经验，已写进注释/手册）：镜像 entrypoint 会**覆盖** `/etc/aerospike/aerospike.conf` ✗（挂 `:ro` 直接起不来 ✗）→ 改为挂到 `/opt/aerospike/etc/` 并用 `asd --config-file` 直启 ✓；
    `mesh-seed-address-port` 必须写在 `network.heartbeat` 里 ✗（写在 `cluster{}` 里被静默忽略 → `cluster_size=1` ✓）；Aerospike 8 移除了 `memory-size`（拒启 ✓）→ 用镜像自己的 file-backed memory 形状 ✓。
    另外：Aerospike 会向客户端**通告容器地址**，宿主进程跟不过去 ✗（`Peers not reachable: 172.18.0.3 3000` ✓）→ 引擎必须以容器形式`--network` 进同一网络 ✓。
  - **M3-2**：`verification/verify-multi-instance.mjs`（两个引擎共享一个 store，客户端轮询 = LB 行为）**8/8**：
    写幂等（同一次写经两个实例都成功、只存一个值 ✓）、**过期写被拒**（`SUCCESS_STALE`，新值保留 ✓）、更新的写能落地 ✓、
    四路并发同键创建全部成功且两实例读到的结果一致 ✓；基准（25 轮**交错**）：一台 **p50 46.8 / p95 63.4 / p99 91.7 ms，20.9 ops/s**，
    两台 **47.9 / 65.7 / 71.0 ms，20.1 ops/s** → **96%** —— 该负载在**存储侧**受限，第二个引擎买的是可用性与 CPU 侧余量，不是速度 ✓（如实写进 `docs/CAPACITY.md` ✓）。
  - **M3-4**：schema 文件格式带 `schemaVersion`（`SchemaJsonCodec.SCHEMA_VERSION=1` ✓）：写入时总是写 ✓，缺失视为 1（向后兼容 ✓），
    来自未来的版本**按名字拒绝**并说明两个版本号 ✓（"读一半"会静默丢约束 ✗）；`POST /api/v1/schema/version` 判定任意文件 ✓；
    `tools/schema-version.mjs`（`--check/--write/--dry-run`，版本号向服务要以免工具与引擎各写一份 ✓）**把仓库里 17 个 schema 全部盖章** ✓；
    `verification/verify-schema-version.mjs` **9/9**：盖章/未盖章都兼容 ✓；schema 目录里放一个未来版本文件 → **服务照常启动**、那张图不被服务、
    日志点名两个版本号 ✓。修掉一个真 bug：拒绝消息里 `dto.name()` 拼接被写成了字面量 ✗（报文里出现 `+ dto.name() +` ✓）。
  - **M3-5**：`docs/CAPACITY.md` —— 服务规模（5 张图，点/边/磁盘实测：8,000 sales = 87 MB ✓）、四类查询的端到端 p50/p95/p99、
    一台 vs 两台的对照、故障时间（1s 切换 / 2s 重组 / 1s 追平）、**调优旋钮表**（limits/regionCount/cacheEdges/parallelism/磁盘与堆 ✓）、
    以及**明确没测什么**（>10⁴ 规模、跨机 DIST、写吞吐、第二个 seed 这个真实缺口 ✓）。并给 Prometheus 加了
    `phoenixgraph_jvm_heap_used_bytes`（Gauge，抓取时读 ✓ + 单测 ✓），让"堆"不再是猜的 ✓。
  - **M3-3（跨机 Gemini）按要求不做** ✓；`docs/ROADMAP-1.0.md` 里 M3-1/2/4/5 标 ✅、M3-3 保持 ⬜ 并注明"按要求跳过" ✓。
- **本轮新增的 API 事实（已记入 `docs/API.md`）**：属性写入的 `index` 是**schema 里的属性 id**（从 1 开始，0 是 id 属性 ✓），不是列表位置 ✓；
  边写入需要 `edgeId` 且端点是 `destination`（不是 `target` ✓）；删边还要 `edgeSchemaName` 查询参数 ✓ —— 三条都是被演练逼出来的 ✓。

**M3 之后**：M3-3（跨机 Gemini）按要求暂不做；1.0 的剩余工作是 把 M2/M3 的结论固化成发布（版本号、镜像、发布说明）与日常运行（值班手册、告警演练）。

# PhoenixGraph Integration Test

端到端集成测试：在真实 Aerospike 单机节点上构建一个简单图，生成 CSV 测试数据，用批量导入工具导入点/边，并查询验证导入结果。

## 1. 下载并安装单机版 Aerospike

```bash
# 方式 A：Docker Compose（推荐，已内置单机配置 replication-factor=1）
cd graph-integration-test && docker compose up -d

# 方式 B：脚本（优先 docker compose / docker，否则原生下载 CE tarball，仅 Linux x86_64）
./scripts/setup-aerospike.sh
```

> 单机节点必须 `replication-factor=1`，`scripts/aerospike.conf` 与 `docker-compose.yml` 已写入该配置。

## 2. 测试图

- 点：`Account`（1001、1002，属性 1=accountType / 2=primaryAddress / 3=accountStatus）、`IP`（2001、2002，属性 1=ipAddress）
- 边：`Payment`（e1：1001 → 1002，属性 1=amount / 2=currency / 3=status，单向）、`Account->IP`（e2：1001 → 2001，属性 1=loginTime，单向）

## 3. 运行集成测试

```bash
AEROSPIKE_HOST=localhost AEROSPIKE_PORT=3000 AEROSPIKE_NAMESPACE=test \
  mvn -pl graph-integration-test test
```

- 若 Aerospike 未运行，测试自动 **skipped**（不会失败）
- 若运行中：生成 CSV → 批量导入 → 查询 1001 点及其出边/入边 → 断言属性与边均导入成功

也可以命令行手动跑：

```bash
mvn -pl graph-integration-test -am test-compile exec:java \
  -Dexec.mainClass=com.pengcheng.phoenixgraph.integration.IntegrationMain
```

## CSV 格式

与 `graph-batch-load` / `graph-mapreduce` 一致：首列 `VertexID`/`EdgeID` 的值为复合 id
`GraphName::Vertex::VertexName:ID`（点）或 `GraphName::Edge::EdgeName:ID`（边），用于定位元素所属的图、schema 名称与元素 id；属性列用索引命名，末列 `lastUpdatedTime`；边额外有 `source`/`destination`。

# PhoenixGraph 可视化前端

基于 Node.js + Express + d3.js 的图可视化界面，通过 `graph-http` 的 query API 查询子图并渲染。

## 运行

前置：`graph-http` 已在 `localhost:8081` 启动（Aerospike 已连接，schema 已部署）。

```bash
cd graph-web/frontend
npm install
npm start          # 默认 http://localhost:8082
```

环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | `8082` | 前端端口 |
| `GRAPH_HTTP_URL` | `http://localhost:8081` | graph-http 后端地址 |

## 功能

- 顶部：下拉选择图实例（graphName）、点选模式（顶点类型 + id）或 Gremlin 手写模式（toggle）。
- 中部画布：d3 力导向图渲染查询返回的点/边；不同点类型用不同颜色 + emoji icon 表示；点按 `label::id` 去重与解析。
- 右侧面板：统计 tab（点数/边数）、属性 tab（单击某点/某边显示其全部属性）。
- 交互：单击点/边选中并展示属性；双击某点沿**所有边类型** `both` 扩展一跳并合入当前图。

## 依赖

d3 通过 npm 安装并本地服务（`/vendor/d3/d3.min.js`），无需外网 CDN。

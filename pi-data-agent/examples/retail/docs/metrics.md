# 营销数据语义层（示例）

这份文档是 pi-data-agent 的示例输入：一份给业务看的指标口径，一份 DBA 导出的 DDL。构建器会把两份
合到一起，并把每条定义的**原句**记在节点上。

## 指标

| 指标 | 定义 | 单位 | 别名 | 图属性 | 说明 |
|---|---|---|---|---|---|
| 营收 | SUM(revenue) | 元 | 收入、营业额、revenue | revenue | 营销带来的成交金额 |
| 花费 | SUM(spend) | 元 | 成本、投放费用 | spend | 媒体投放花费 |
| 订单数 | COUNT(*) | 单 | 订单量、orders | count | 成交订单行数 |
| 新客数 | SUM(newCustomers) | 人 | 新用户、new customers | newCustomers | 首次成交客户数 |
| 曝光量 | SUM(impressions) | 次 | 展现量、impressions | impressions | 广告曝光次数 |
| 点击量 | SUM(clicks) | 次 | 点击数、clicks | clicks | 广告点击次数 |
| 点击率 | 100 * SUM(clicks) / SUM(impressions) | % | CTR、点击通过率 | | 比率指标：没有单一事实属性，图上不执行，SQL 可导出 |

口径约定：营收按**成交日**归集，跨年对比使用自然年；花费按投放日归集。金额单位为人民币元。

## 维度

| 维度 | 字段 | 类型 | 图属性 | 说明 |
|---|---|---|---|---|
| 渠道 | channel | VARCHAR(20) | channel | 搜索/社交/联盟/邮件 |
| 地区 | region | VARCHAR(20) | region | 华北/华东/华南 |
| 活动 | campaign | VARCHAR(20) | campaign | 品牌/效果/大促 |
| 日期 | date | DATE | year=year,month=month | 自然年/月 |

## 术语

| 术语 | 说明 | 别名 |
|---|---|---|
| 成交 | 用户完成支付且未退款的订单 | GMV 口径、成交额 |
| 投放 | 一次广告计划在一个渠道上的曝光 | 广告投放 |

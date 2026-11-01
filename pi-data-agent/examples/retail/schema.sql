-- 表：marketing 营销每日汇总
-- 说明：一行 = 一个自然日 × 渠道 × 地区 × 活动
CREATE TABLE marketing (
  id VARCHAR(64) NOT NULL,
  year INT NOT NULL COMMENT '自然年',
  month INT NOT NULL COMMENT '自然月',
  channel VARCHAR(20) NOT NULL COMMENT '渠道',
  region VARCHAR(20) NOT NULL COMMENT '地区',
  campaign VARCHAR(20) NOT NULL COMMENT '活动',
  revenue DECIMAL(18,2) COMMENT '营收',
  spend DECIMAL(18,2) COMMENT '花费',
  impressions BIGINT COMMENT '曝光量',
  clicks BIGINT COMMENT '点击量',
  new_customers DECIMAL(18,2) COMMENT '新客数',
  PRIMARY KEY (id)
) COMMENT='营销每日汇总';

-- name: 各渠道营收
SELECT channel, SUM(revenue) AS revenue
FROM marketing
GROUP BY channel;

-- name: 各活动花费
SELECT campaign, SUM(spend) AS spend
FROM marketing
GROUP BY campaign;

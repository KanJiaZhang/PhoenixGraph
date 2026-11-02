-- The fact table the data-agent's semantic layer is bound to.
-- One row = one day × channel × region × campaign. `net_revenue` exists because 营收 changed definition on
-- 2026-10-15 (see docs/metrics-2026-10.md): from that day on it is the net amount, before it was the gross one.

CREATE TABLE sales_day (
  id            VARCHAR(96)     NOT NULL COMMENT 'surrogate key',
  year          INT             NOT NULL COMMENT '自然年',
  month         INT             NOT NULL COMMENT '自然月',
  channel       VARCHAR(20)     NOT NULL COMMENT '渠道: APP / WEB / MINI / STORE',
  region        VARCHAR(20)     NOT NULL COMMENT '地区: 华东 / 华南 / 华北',
  campaign      VARCHAR(32)     NOT NULL COMMENT '活动: 新客季 / 会员日',
  orders        BIGINT          NOT NULL COMMENT '订单数',
  customers     BIGINT          NOT NULL COMMENT '客户数',
  revenue       DECIMAL(18,2)   NOT NULL COMMENT '成交金额（毛）',
  net_revenue   DECIMAL(18,2)   NOT NULL COMMENT '成交金额（扣除退款后的净额）',
  cost          DECIMAL(18,2)   NOT NULL COMMENT '投放与履约成本',
  PRIMARY KEY (id)
);

CREATE TABLE channel_dim (
  channel       VARCHAR(20)     NOT NULL COMMENT '渠道编码',
  display_name  VARCHAR(32)     NOT NULL COMMENT '渠道名称',
  PRIMARY KEY (channel)
);

ALTER TABLE sales_day ADD CONSTRAINT fk_sales_channel FOREIGN KEY (channel) REFERENCES channel_dim (channel);

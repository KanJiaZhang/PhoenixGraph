# 变更窗口 — 2026-10-24 10:00 — payment service 缓存配置

今天上午 10:00 上了 deploy-48，只改了 payment service 的缓存配置，没有动 checkout-api 的代码。

deploy-48 is deployed on payment service.

deploy-48 replaced cache-key-prefix-v2.

cache-key-prefix-v2 replaced cache-key-prefix-v1.

cache-key-prefix-v2 is part of redis-cache.

deploy-48 caused cache-key-prefix-churn.

cache-key-prefix-churn affects redis-cache.

deploy-48 affects checkout-api.

deploy-48 affects redis-cache.

deploy-48 affects payment service.

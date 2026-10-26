# 服务拓扑：谁调用谁、谁依赖哪个存储

checkout-api depends on payment-service.

checkout-api depends on order-service.

checkout-api depends on search-api.

payment service depends on redis-cache.

PaymentService uses rabbitmq.

order-service depends on postgres-orders.

search-api depends on redis-cache.

notification-service uses rabbitmq.

payments-service depends on redis-cache.

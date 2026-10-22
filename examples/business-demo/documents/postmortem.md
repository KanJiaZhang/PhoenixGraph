# Postmortem — checkout latency

Alice owns the payment-service.
Bob is assigned to the checkout-api.
Deploy 42 replaced the old cache.
The cache leak blocked Redis.
The bad config affects the checkout-api.
The outage was caused by Deploy 42.
The payment-service is owned by Alice.
The checkout-api is deployed on the payment-service.

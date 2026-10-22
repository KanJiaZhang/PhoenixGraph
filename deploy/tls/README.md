# TLS in front of PhoenixGraph

The service can serve HTTPS itself (`server.ssl.*`, see `docs/API.md §2` and `verification/verify-tls.mjs`).
For a container deployment, terminate TLS in Caddy: it obtains a Let's Encrypt certificate for a real
domain, or an internal certificate for `localhost`.

```bash
export PHOENIXGRAPH_DASHBOARD_TOKEN="$(openssl rand -hex 24)"
export PHOENIXGRAPH_DOMAIN=graph.example.com      # or leave unset for localhost + internal CA
docker compose -f docker-compose.yml -f docker-compose.secure.yml -f docker-compose.tls.yml up --build
```

- Caddy listens on `8443` (mapped to 443), proxies `/api/*` to `graph-http:8081` with the API key, and
  everything else to the UI on `graph-web:8080`.
- With `localhost` Caddy uses its internal CA: import/trust `caddy_data`'s root certificate, or use
  `curl -k` for a local check.
- With a real domain and ports 80/443 published, Caddy obtains and renews the certificate automatically.
- The UI itself is not authenticated; expose it through Caddy on a trusted network or add SSO in front.

The Caddyfile is `deploy/tls/Caddyfile`; the overlay is `docker-compose.tls.yml`.

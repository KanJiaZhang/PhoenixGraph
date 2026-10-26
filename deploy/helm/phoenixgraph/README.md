# Helm chart

A production-shaped install: secure profile, audit on a persistent volume, one engine replica (RocksDB is
single-writer), two UI replicas, and probes that check the API can reach its store.

```bash
cat > security.yml <<'EOF'
phoenixgraph:
  security:
    enabled: true
    keys:
      - name: dashboard
        token: replace-with-a-long-random-token
        role: admin
EOF

kubectl create secret generic phoenixgraph-secrets \
  --from-file=security.yml=security.yml \
  --from-literal=dashboard-token=replace-with-a-long-random-token

helm upgrade --install phoenixgraph deploy/helm/phoenixgraph \
  --set image.http=ghcr.io/<owner>/phoenixgraph-http:1.1.2 \
  --set image.web=ghcr.io/<owner>/phoenixgraph-web:1.1.2
```

- The Secret carries the Spring `security.yml` (mounted read-only at `/etc/phoenixgraph`) and the token the
  UI forwards. The same token must appear in `security.yml` and `dashboard-token`.
- The engine PVC is `persistence.size` (20 GiB by default); set `persistence.storageClass` for your cluster.
- `storage.host` defaults to `aerospike`; set it to your store's service or use `phoenixgraph.storage.seeds`
  in the ConfigMap for a multi-seed cluster.
- For a local trial without authentication:
  `--set springProfiles=docker --set security.enabled=false`.
- TLS: put a reverse proxy or Ingress in front; the chart does not terminate TLS.

`helm lint deploy/helm/phoenixgraph` and `helm template phoenixgraph deploy/helm/phoenixgraph` should pass.

# Secure deployment overlay

```bash
cp deploy/secure/security.example.yml deploy/secure/security.yml
export PHOENIXGRAPH_DASHBOARD_TOKEN="$(openssl rand -hex 24)"
docker compose -f docker-compose.yml -f docker-compose.secure.yml up --build
```

- `security.yml` is mounted read-only at `/etc/phoenixgraph/security.yml` and is gitignored.
- The token is read from `PHOENIXGRAPH_DASHBOARD_TOKEN`; `graph-web` forwards the same token to `graph-http`,
  so the UI keeps working.
- The audit trail is `/data/audit/audit.jsonl` (named volume), rotated and reloaded on restart.
- TLS is not part of this overlay: put a reverse proxy in front, or configure `server.ssl.*` with a
  keystore. The UI is not authenticated; expose it only on a trusted network or behind your SSO proxy.
- Add `tenant: <name>` to a key to group keys for `per-tenant-concurrent` / `per-tenant-per-second`.

The full checklist is in [docs/OPERATIONS.md §5b](../../docs/OPERATIONS.md#5b-running-with-authentication-and-a-durable-audit).

# OpenAPI

[`openapi.json`](openapi.json) is generated from the same controller scan as the endpoint table in
[API.md](API.md):

```bash
node tools/openapi.mjs           # regenerate
node tools/openapi.mjs --check   # what CI runs
```

**What is exact:** every path, method, operation id (`Controller_method`), path parameter, the Java
controller/method (as `x-phoenixgraph-*`) and the request-body Java type. That is enough for a client
generator to produce a typed method per endpoint, and for an agent to discover the surface.

**What is generic:** the request/response *fields*. Those live in Java DTOs without OpenAPI annotations;
a generated field list would guess and could lie. `docs/API.md` remains the field-level reference. When the
DTOs get annotations, this generator can emit real schemas without changing the paths.

The spec assumes bearer auth (`Authorization: Bearer <key>`), which is correct for a secure instance; a
local open instance ignores the scheme. Error bodies are `{error, exception, hint, requestId}` (API.md §3).

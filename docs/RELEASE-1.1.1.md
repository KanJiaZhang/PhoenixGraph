# PhoenixGraph 1.1.1 — release notes

**Tag:** `v1.1.1` · **Image:** `phoenixgraph-http:1.1.1` / `phoenixgraph-web:1.1.1` · **Jar:** `dist/phoenixgraph.jar` (+ `.sha256`)
**From:** `v1.1.0` · **Branch:** `develop` (a patch release; see [`RELEASE-PROCESS.md`](RELEASE-PROCESS.md))

A patch release for the pipeline around the 1.1.0 tag. **No API change, no change to a query or an answer**: the
1.1.0 artifact was correct, and what was wrong was the machinery that is supposed to prove it - which is the
part that had been reporting red for weeks without anyone reading it.

## Why this exists

Cutting 1.1.0 turned the CI on `develop` from "red for days" into "red for a reason". Four defects came out in
the order they blocked each other:

1. **The Linux job could not start.** `mvnw` and the eleven `tools/*.sh` scripts were committed without the
   executable bit, so the runner failed with exit 126 - not a test failure, a permission one. Windows does not
   carry the bit the same way, so the project built and released locally the whole time.
2. **Then a test bound privileged ports.** `GeminiCluster.start()` asked worker *i* for `firstPort + i` while the
   configuration meant "let the OS choose"; workers 2-4 asked for ports 1, 2 and 3. Windows grants those, Linux
   refuses them.
3. **Then a test measured the hardware.** A seed-batch test bounded the number of storage requests by
   `DEFAULT_PARALLELISM`, so it passed on eight cores and failed on four.
4. **And the container job's stack never came up**: the Aerospike health check used `asinfo -v -c status`,
   which is not `asinfo` syntax, so the store reported unhealthy forever. The same wrong probe was in the
   Kubernetes storage manifest, where readiness could never have passed.

`develop` is green now, for the first time in the visible history of the workflow: unit tests + generated docs,
the container path (build, start, smoke, the three-minute demo), and both SDK jobs.

## Fixed

- **The Aerospike health check is a command `asinfo` accepts** (`asinfo -v status`: `ok`, exit 0). Fixed in
  `docker-compose.yml`, `deploy/aerospike-cluster`, `deploy/k8s/storage.yaml` and the CI HA job. If you run the
  compose stack, this is the one that changes your day: `docker compose up` now waits for a healthy store and
  then starts the API, instead of timing out on a container that was fine all along.
- **`mvnw` and `tools/*.sh` carry their executable bit** in the repository.
- **Gemini workers get their own ephemeral port** when the configuration asks for one, instead of ports 1-3.
- **`publish-python` no longer fails a server release.** The client version is independent of the server tag;
  when neither `PYPI_API_TOKEN` nor PyPI trusted publishing is configured, the wheel is built, the publish step
  is skipped and the run stays green. Configure one of the two to publish the client.
- **The seed-batch test asserts the split, not the runner.** With `parallelism(4)` pinned, 100 seeds are four
  requests of 25 - and `GremlinContext.seedBatches` now documents what that means: a seed read happens before
  the steps run, so `batch(n)` sizes a fan-out, while `parallelism` sizes the seed read.

## Changed

- **`docs/API.md` and `docs/openapi.json` are regenerated** and now list `POST /api/v1/agent/merge`: the scanner
  walks the whole `http` package, so controllers in their own packages are no longer invisible. 60 endpoints,
  60 paths, 15 controllers; CI checks both.
- **The container job is diagnosable**: it waits for the UI before probing it (the UI starts after the API is
  healthy, so the immediate `curl` raced it), and a failure now prints the container states, their health values
  and Aerospike's own log.

## Verifying this release

```bash
tools/start-phoenixgraph.sh --no-build
node verification/verify-ai-context.mjs      # the three 1.1.0 cases, against the running service
node verification/run.mjs                    # everything this machine can run
node tools/check-release.mjs                 # the repository agrees with itself about 1.1.1
docker compose up -d && docker compose ps    # the store reports healthy, the API starts
```

The 1.1.0 release notes ([`RELEASE-1.1.0.md`](RELEASE-1.1.0.md)) still describe what is in the product; nothing
in them changed.

## Known limits

The list in the [README](../README.md#status) is the current one and did not change: extraction is a dictionary
plus rule packs, the default embedder is an offline hashing baseline, the demo data is 10²-10⁴ vertices, the
demo profile has authentication off, and failover is manual.

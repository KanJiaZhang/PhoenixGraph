# Service level objectives

What "good" means for this service, how each number is measured, and what to do when one is missed. Written so
that an alert can be traced back to a sentence here, and so a reader can tell an SLO from a wish: every
threshold below is either **measured on this machine** (marked, with the command that measured it) or
**declared** (and then it is a target, not a claim).

> **These numbers are end to end** (HTTP request to parsed response, including the server's JSON), measured by the check below. They are therefore larger than the engine's internal latency in the README (a one-hop p50 of 0.9 ms *inside* the JVM): both are true, and the objective a caller cares about is this one.
>
> Measured numbers come from `node verification/verify-slo.mjs`, which runs the query classes below against a
> running instance and prints p50/p95/p99 next to these thresholds. Re-run it after a change that could move
> them; the numbers in this file are the ones that run produced.

## 1. The objectives

| # | Objective | Target | Window | Measured (demo instance, 2026-10-13) |
|---|---|---|---|---|
| S1 | **Keyed point query** (`g.V(id)`) p99 | < 40 ms | 5 min | p50 **15.2 ms**, p99 **25.2 ms** ✅ |
| S2 | **One hop** (`bothE('Payment').limit(50)`) p99 | < 60 ms | 5 min | p50 **19.4 ms**, p99 **22.3 ms** ✅ |
| S3 | **Two hops** (`both().both().dedup().limit(200)`) p99 | < 150 ms | 5 min | p50 **16.8 ms**, p99 **29.0 ms** ✅ |
| S4 | **Aggregate over a class** (`g.V('Sale').group().by('orderYear')...`, 8,000 rows) p99 | < 500 ms | 15 min | p50 **84.5 ms**, p99 **106.2 ms** ✅ |
| S5 | **Error rate** (5xx of all `/api` requests) | < 0.5% | 5 min | 0% in the runs above ✅ |
| S6 | **Availability** (`/api/v1/health` answering) | 99.5% monthly | 30 days | not measured (no long-running instance; M3) |
| S7 | **Refusals are deliberate** (`429`/`503` from the gate) | 0 outside saturation | 5 min | 0 at the demo's load ✅ |

The thresholds above are the ones the *demo* instance on this machine meets with room to spare. They are a
baseline, not a promise for other hardware or other data: a deployment sets its own numbers and keeps the
method.

What is deliberately **not** an SLO here: write throughput, ingest rate, and cross-machine algorithm latency
(the `DIST` steps). Those need a deployment with real load and more than one machine to say anything honest
about; M3 is where they belong.

## 2. How each one is measured

| Objective | Prometheus | Where it comes from |
|---|---|---|
| S1-S4 | `phoenixgraph_query_latency_language_gremlin_p99_millis{graph="..."}` | the engine's own latency histogram, exported at `/api/v1/metrics/prometheus` |
| S5 | `phoenixgraph_query_failed_language_*_total` / `phoenixgraph_query_count_language_*_total` | the same registry, per language and caller |
| S6 | `up{job="phoenixgraph"}` (the scrape itself) plus a blackbox check on `/api/v1/health` | the scraper |
| S7 | `phoenixgraph_requests_refused_total`, `phoenixgraph_requests_admitted_total` | the request gate's counters |
| storage health | `phoenixgraph_storage_*_failed_total` | per-graph storage counters |

The latency histograms are **per graph and per language**, so an SLO better than "the service is fast" is
possible: S4 above is the analytical graph, and a slow *class scan* should not page somebody about the keyed
graph's latency. When a deployment's graphs have different contracts, split the objectives the same way.

## 3. Alerts

`deploy/prometheus/phoenixgraph-rules.yml` holds them, and `node verification/verify-slo.mjs` checks two things
that drift: that every metric a rule names **exists in the live exposition**, and that the thresholds agree with
this document. Each alert carries a `runbook` annotation pointing at the section here that says what to do.

| Alert | Fires when | First thing to look at |
|---|---|---|
| `PhoenixGraphQueryLatencyHigh` | keyed/hop p99 > 60 ms for 10 min | `GET /api/v1/metrics/{graph}` (which language? which storage counters moved?), then the audit trail for the slow query |
| `PhoenixGraphErrorRateHigh` | 5xx > 0.5% for 5 min | the error body (it carries the message and the stack) and `X-Request-Id` in the response |
| `PhoenixGraphRefusingRequests` | any `429`/`503` for 5 min | the gate counters and `phoenixgraph.limits.*`: is the load real, or is one caller hammering? |
| `PhoenixGraphAggregateLatencyHigh` | class-scan p99 > 500 ms for 15 min | the query shape: is it a whole-class aggregate, and does it need a time window? |
| `PhoenixGraphStorageFailures` | any `storage.*_failed_total` rises (they are exported as 0, so 0 really means zero) | the store (disk, network, schema mismatch) - and whether a restore is needed (`docs/OPERATIONS.md` §4) |
| `PhoenixGraphDown` | the scrape fails for 2 min | `docs/OPERATIONS.md` §1 (what runs where) and the container logs |

## 4. What the numbers are *not*

- They are **not** a capacity plan. "p99 < 150 ms at 8,000 rows" says nothing about 8 million; the storage
  guide says which scheme scans, and M3 is where a size-vs-latency table belongs.
- They are **not** end-to-end. The browser's rendering time, the network to the client and the UI's own work
  are outside every number here; the UI is verified for correctness (`verification/verify-*.mjs`) rather than
  for speed.
- They do **not** cover the `DIST` paths. A distributed run pays superstep synchronisation, and on a demo-sized
  graph it is *slower* than the in-process one - that is expected and documented, not an SLO miss.

> **Latency percentiles are windowed** (the last five minutes), not cumulative: a threshold alert clears when
> the window is fast again. The bucket grid still carries the cumulative history for the daily rollup. The
> on-call drill and the fix are recorded in [DATA-QUALITY.md §12](DATA-QUALITY.md#12-从存储恢复的延迟百分位被当成当前延迟已修复2026-10-20).

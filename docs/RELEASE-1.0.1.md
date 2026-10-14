# PhoenixGraph 1.0.1 — release notes

**Tag:** `v1.0.1` · **Image:** `phoenixgraph-http:1.0.1` / `phoenixgraph-web:1.0.1` · **Jar:** `dist/phoenixgraph.jar` (+ `.sha256`)
**From:** `v1.0.0` · **Branch:** `release/1.0` (see [`RELEASE-PROCESS.md`](RELEASE-PROCESS.md))

A patch release with what the on-call drill found. **No API change**: the request and response shapes, the
status codes and the endpoints are the same as 1.0.0.

## What the drill is, and why it earned a release

The 1.0.0 milestone list said "the alerts exist". The drill asked the next question: *does the number each alert
reads move when the condition it watches is true?* `verification/verify-alert-drill.mjs` reproduces every
condition and reads the metric through the same exposition Prometheus would scrape:

| Alert | How the drill raises it | What it observed |
|---|---|---|
| `PhoenixGraphRefusingRequests` | an instance with a one-request-per-second budget | `phoenixgraph_requests_refused_total` **0 → 3** |
| `PhoenixGraphStorageFailures` | an instance whose store is unreachable | the storage failure counters **0 → 2** (and 65 of 66 series still 0) |
| `PhoenixGraphErrorRateHigh` | a rejected request | the error counters move |
| `PhoenixGraphQueryLatencyHigh` | concurrent three-hop queries | the p99 series is live and stays under the threshold |
| `PhoenixGraphAggregateLatencyHigh` | an 8,000-row aggregate | measured against the 500 ms threshold in `docs/SLO.md` |
| `PhoenixGraphDown` | — | the rule's job label, the scrape path and the liveness endpoint have to agree |

## Fixed

- **The alert rules referenced a scrape job that did not exist.** `PhoenixGraphDown` selects
  `up{job="phoenixgraph"}`, and nothing in the repository defined that job: six alerts that could never fire, on
  a system that claimed to be watched. `deploy/prometheus/prometheus.yml` now defines it, and the drill asserts
  the rule and the configuration agree.
- **Latency percentiles never decayed.** They were cumulative since the process started, so one slow query (a
  cold plan, a benchmark, a loaded machine) raised the p99 permanently and `PhoenixGraphQueryLatencyHigh` could
  never clear - which made the runbook in `docs/SLO.md` wrong. `LatencyStats` now keeps both views:
  `percentileMillis` reads a five-minute window (five one-minute slots, rotated on record *and* on read, because
  a quiet instance would otherwise keep reporting its last minute forever - a Prometheus scrape is not a query),
  and `cumulativePercentileMillis` reads everything since the process started. Restored snapshots deliberately
  do not enter the window: a value read back from an earlier process is not current latency. Ten unit tests,
  including "the window forgets and the cumulative view does not" and "a quiet graph stops reporting the old
  percentile".

## Known issue carried into 1.0.x

**The `*_p99_millis` series is still cumulative**, so the latency alert still cannot clear. The exposition
renders a persisted snapshot, and a snapshot is rebuilt from its bucket grid by the stores (`RocksDbMetricsStore.load`),
which cannot carry an in-memory window: a graph restored from storage reported 113 ms while nobody had queried
it, identical across restarts, which is how the drill caught it.

- The evidence and the analysis are in [`DATA-QUALITY.md`](DATA-QUALITY.md) §12.
- [`SLO.md`](SLO.md) says to treat that alert as noisy until it is fixed.
- The drill asserts the defect as an explicit `XFAIL`: when that line starts failing, the fix has landed and the
  line becomes an ordinary assertion.

The windowed view is already implemented and unit-tested; what is missing is the plumbing from the live
`LatencyStats` to the exposition. That is the first item for **1.0.2**.

## Verifying this release

```bash
git checkout v1.0.1
node tools/check-release.mjs          # versions, tag, changelog, images and the release note all agree
export JAVA_HOME=...                  # JDK 21+
tools/start-phoenixgraph.sh           # builds and serves: UI 8080, API 8081
node verification/run.mjs             # 21 checks, ~380 assertions (Docker adds the HA drill)
sha256sum -c dist/phoenixgraph.jar.sha256   # if you downloaded the jar
```

`docs/RELEASE-PROCESS.md` is the branch and tag policy this release follows:
`main` = the released line, `release/1.0` = the maintenance line patches are cut from, `develop` = integration.

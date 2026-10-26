# Incident triage: from a symptom to the most likely cause

A worked case of PhoenixGraph as the context layer for **on-call triage**. The corpus is a small but realistic
ops estate - five services, two datastores, five hosts, six metrics, five releases, four RCAs - and the question
is the one an on-call engineer asks at 14:32:

> checkout-api 的 p99 从 180ms 涨到 4.2s，error-rate-5xx 到 2.3%，同时 redis-cpu-percent 95%、
> redis-evictions-rate 高频抖动。最可能的原因是什么？

```bash
tools/start-phoenixgraph.sh --no-build          # 8081 API (Aerospike write path for the ingest)
node examples/incident-triage/triage.mjs        # ingest documents/ on the first run, then answer
node examples/incident-triage/triage.mjs --symptom "your symptom text"
node examples/incident-triage/triage.mjs --reingest   # rebuild after editing documents/
node examples/incident-triage/triage.mjs --json       # the same computation as data (what the check asserts on)
```

## What it answers, and how

The answer to the symptom above is **deploy-48, through `cache-key-prefix-churn`** - not because a model guessed
it, but because five facts in the graph line up and the script prints which ones:

```
#1 deploy-48 -> cache-key-prefix-churn   score 20
    the failure mode acts on redis-cache, which the symptom's metrics observe (+6)
    a change window that touches checkout-api and redis-cache (+6)
    reaches checkout-api and redis-cache within 1 hop(s) (+4)
    INC-1042 (deploy-42 -> cache-eviction-storm) already failed on redis-cache (+2)
    the newest change in the corpus (+2)
    deploy-48 -caused-> cache-key-prefix-churn   "deploy-48 caused cache-key-prefix-churn."
    cache-key-prefix-churn -affects-> redis-cache   "cache-key-prefix-churn affects redis-cache."
#2 deploy-42 -> cache-eviction-storm   score 10
```

The method is four steps, and every step is a call to the same API an agent uses:

| Step | Call | Why it is there |
|---|---|---|
| 1. What does the symptom name? | `POST /api/v1/agent/find-entities` | only `via: "name"` matches become seeds - a vector-only neighbour is a guess, and a symptom that names nothing gets a refusal (see below) |
| 2. Which components do those metrics watch? | the artifact's `affects` edges | `redis-cpu-percent affects redis-cache`, so a metric spike names a suspect component instead of a number |
| 3. Which causes can explain an observed component? | the artifact's `caused` edges, scored | the arithmetic below, printed line by line |
| 4. Which evidence would overturn it? | the same edges, read the other way | the deploy diff, the minute the signal started, the runner-up that explains a different slice |

The score is a readable sum, not a hidden weight:

| Component | Points | What it means operationally |
|---|---|---|
| failure mode acts on an observed component | +6 | "the thing that broke is what this change breaks" |
| failure mode moves an observed metric | +4 | partial credit: the metric moved, the component link is one hop away |
| failure mode acts on something with no signal | -3 | this is what rules the DNS incident out on a checkout day |
| change window touching observed components | +3 each, cap 6 | who shipped near the failing thing |
| reachable from the cause within 2 hops | +2 per component, cap 4 | topology: the cause is one `depends_on`/`uses`/`deployed_on` away |
| a documented incident failed the same way on the same component | +2 | the recurrence signal, and it names the incident |
| newest / second-newest release in the corpus | +2 / +1 | recency, stated as what it is: a tiebreak |

## Three runs, three different behaviours

**The fault.** `deploy-48` scores 20, `deploy-42` (the same failure shape, three releases earlier) scores 10 and
is named as the runner-up, and the DNS incident scores -3 because `notification-service` and `rabbitmq` show no
signal. The report also says what would flip it: *"deploy-48 did not actually ship the change it claims"*,
*"the redis-cache signal started before deploy-48 landed"*, *"the errors concentrate on a code path that never
touches cache-key-prefix-churn"*.

```bash
node examples/incident-triage/triage.mjs --symptom "2026-10-20 18:10 起 checkout-api 的 error-rate-5xx 从 0.2%
到 2.1%，checkout-p99-latency 到 1.9s；缓存与数据库侧无任何抖动。"
```

**A different day, a different answer.** With the cache side quiet, the same corpus answers `deploy-47`
(checkout deploy regression, score 7) with `deploy-45` (connection-pool exhaustion, 6) as the honest runner-up -
both move the two metrics that moved, and the graph says so instead of picking one silently. That is the point
of running the ranking on a graph: the disagreement is visible.

**A question the corpus cannot answer.**

```bash
node examples/incident-triage/triage.mjs --symptom "支付网关的 TLS 证书是不是快过期了？"
   nothing: no entity in the graph is named by this symptom.
   Refusing to name a cause. … no name match and no relation is a finding, not a prompt to guess.
```

Approval records are the same story: the corpus has no `approved_by` edge for `deploy-48`, so "who approved it"
returns nothing, and the report says the graph cannot answer it rather than inventing a name.

## The corpus

`documents/` is written the way real ops text arrives: five releases numbered `deploy-42` … `deploy-48`, four RCA
documents, a topology file, a host/pool file, a metric-to-component file and one change window. Two traps are
deliberate:

- **The same metric signature from two different causes.** `INC-1042` (Redis eviction storm) and `INC-1051`
  (Postgres pool exhaustion) both move `checkout-p99-latency` and `error-rate-5xx`. A ranker that matches metrics
  alone cannot separate them; this one separates them on the component the metric belongs to.
- **One service in three spellings, one near-duplicate.** `payment-service`, `payment service` and
  `PaymentService` merge into one entity (the ingest reports `merged: payment service <- PaymentService,
  payment-service`), while `payments-service` stays a separate one-edit candidate in the review list - the
  difference between a deterministic merge and a guess.

The facts are single sentences, one paragraph each, in the vocabulary the shipped `incident` rule pack knows
(`caused`, `depends on`, `uses`, `deployed on`, `replaced`, `affects`, `part of`, `fixed`, `owns`, `reports`).
That is the honest constraint: **extraction is a dictionary plus rules**, so a fact the pack cannot read is a
fact the graph does not have. Adding a domain means adding vocabulary (a gazetteer and, where the verbs differ, a
rule pack in `phoenixgraph.agent.rules-dir`), not prompting a model.

`affects` is doing double duty in this corpus: a metric `affects` the component it observes-and a failure mode
`affects` what it breaks. The pack has no `observes` verb, and inventing one would have meant a rule pack for a
demo - the script treats `metric -> affects -> component` as "observes" and says so.

## What this is not

- **Not a diagnosis.** It ranks hypotheses the corpus supports, with the sentence behind each. The bottom of the
  report is the list of things that would make the top answer wrong.
- **Not a pager.** It does not read live metrics or call an incident API; it reads the graph you ingested.
- **Not statistical.** There is no model and no learning: two people with the same corpus and the same weights get
  the same report, which is what makes it arguable in a postmortem. Change a weight and the disagreement is
  explicit.
- **Not complete.** A metric named in the symptom to say it is *normal* is still a name; quote the signals that
  moved, not the whole dashboard. This limitation is the script's, not the graph's.

## Files

| File | What it is |
|---|---|
| `documents/` | the corpus: topology, hosts, metrics, four RCAs, one change window |
| `dictionary.json` | the gazetteer (43 names, typed: Service, Datastore, Host, Pool, Metric, Deploy, FailureMode, Config, Person, Team, Incident) |
| `symptom.txt` | the fault this run answers |
| `triage.mjs` | ingest + rank + report, `--json` for the same result as data (no dependencies, ~350 lines) |
| `../../verification/verify-incident-triage.mjs` | the same three symptoms asserted against a running service |
| `incident-triage.agent.json` | the artifact the run writes (gitignored: regenerable from `documents/`) |

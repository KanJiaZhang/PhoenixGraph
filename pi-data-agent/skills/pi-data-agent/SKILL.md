---
name: pi-data-agent
description: Build and use a data agent's metadata and semantic layer in PhoenixGraph. Use when asked to read data definitions from PDF, Markdown, wiki, Word or database SHOW/DDL output, to find metrics/dimensions and their formulas, to add or correct metadata and relations, or to produce a dashboard or a change attribution.
---

# pi-data-agent

The package has two halves: **building the model** (documents + database dumps -> PhoenixGraph) and **using
the model** (dashboard + attribution). The two rules that decide whether a number is allowed to exist:

1. A metric needs an **expression**. A definition without one is a term needing review, never a guessed
   formula.
2. A metric/dimension needs a **graphProperty** that the fact vertex type actually declares. Without it the
   answer is SQL-only; the graph execution is refused.

## Build

1. Run `pg_data_scan` with the files/directories/wiki URLs and the fact graph/vertex type:
   `pg_data_scan({sources:["docs/","schema.sql"], dataGraph:"demo-data", vertexType:"MarketingDay"})`.
2. Read the `review` list. For every metric without an expression, ask for the missing definition instead of
   inventing it. For every unbound metric/dimension, either pass an explicit `bindings` map
   (`{"metric/revenue":"revenue","dim/date":"year=year,month=month"}`) or accept SQL-only.
3. Run `pg_data_build` with the same sources. Report what was written, the snapshot path and the remaining
   review items. A build is an upsert; it does not delete what it did not see.
4. Run `pg_data_model` and quote the metric expressions and bindings when the user asks "what does the model
   say".

## Correct

Use `pg_data_edit` with validated operations:

```json
[
  {"op":"update-node","id":"metric/revenue","properties":{"description":"评审后的口径"}},
  {"op":"set-binding","id":"dim/date","graphProperty":"year=year,month=month"},
  {"op":"add-alias","id":"metric/revenue","alias":"销售收入"},
  {"op":"upsert-edge","edge":{"head":"metric/revenue","relation":"groupedBy","tail":"dim/region"}},
  {"op":"upsert-node","node":{"id":"term/激活用户","entityType":"Term","properties":{"name":"激活用户","description":"首次有效成交"}}}
]
```

Deletes need `confirm:true`; a node with edges also needs `cascade:true`. The result is audited; cite the
snapshot/audit paths when reporting.

## Read

- `pg_data_dashboard({year:"2025", topN:5})` writes an HTML dashboard under `.pi/data-agent/dashboards/` and
  returns the same tiles as markdown. Tiles that cannot execute are refusals with SQL, not empty charts.
- `pg_data_attribute({metric:"营收", by:"渠道", from:"2024", to:"2025"})` decomposes the change. Quote the
  totals, the top movers, `decompositionAddsUp`, and the **descriptive, not causal** limit.

## Answer contract

- Quote the sentence/row behind a definition when asked why; every node carries `evidence`.
- A missing relation or a missing binding is a finding, not a gap to fill.
- Never turn a zero from "the data does not cover that period" into a measured zero.
- Do not call `pg_data_build` with sources you have not scanned and discussed when the request is exploratory.

---
description: Review a data-agent model end to end - coverage, metric expressions and bindings, dimensions, relations and duplicates - and list the edits to make before anyone trusts a number from it
argument-hint: "[model-graph]"
---

# Review a data-agent model

Read the model with `pg_data_model` (graph `${1:-the session graph}`, or the snapshot under
`.pi/data-agent/models/`). Review it in this order, and report findings rather than silently fixing them.

1. **Coverage** — which tables/columns came from the DDL or `SHOW` output, and which source documents
   contributed descriptions. Name the sources; a table with no evidence is a finding.
2. **Metrics** — for each metric: expression, unit, synonyms, source table/columns, graphProperty. A metric
   with no expression is a term needing review. A metric with no graphProperty is SQL-only; do not call it
   executable.
3. **Dimensions** — the column behind each dimension, the data type, the graphProperty (including
   `year=…,month=…` for a date). A dimension whose binding is missing cannot slice anything at execution time.
4. **Relations** — `hasColumn`, `references`, `computedFrom`, `usesColumn`, `groupedBy`, `dimensionColumn`,
   `definedBy`, `readsTable`, `contributesTo`, `belongsTo`. Quote the evidence sentence; a relation without one
   is a candidate, not a fact.
5. **Duplicates** — the builder merges a metric/dimension when the definition and binding agree and records
   the merge as a warning. Check that each merge is right; an alias can be split with `pg_data_edit` if not.

Then ask two questions before declaring the layer usable:

- Can each metric be executed (expression + graphProperty + fact graph/vertex type), or is it knowingly
  SQL-only?
- Does the fact vertex type actually carry the properties the model names? If not, the refusal is the answer;
  do not substitute a similar-looking column.

Close with a table of `id | expression | binding | evidence | status`, and the list of edits you recommend -
as `pg_data_edit` operations when they are unambiguous, so the reviewer can apply them without retyping.

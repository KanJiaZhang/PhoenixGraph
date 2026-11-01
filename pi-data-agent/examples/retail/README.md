# Retail example

Four sources that together describe one marketing model:

| file | what it exercises |
|---|---|
| `docs/metrics.md` | business definitions as a Markdown table (metrics, dimensions, terms) |
| `wiki/indicator.html` | the same definitions exported from a wiki (HTML tables) - the build merges the duplicates and records aliases |
| `schema.sql` | DDL: table/columns/types/comments/PK, plus two reference queries (`SUM(...) AS ...`, `GROUP BY`) |
| `show_tables.txt` | a `SHOW TABLES` result set |

The model aligns with the served fact graph `demo-data` / vertex type `MarketingDay`:

```bash
# preview
node src/cli.mjs scan examples/retail --data-graph demo-data --vertex-type MarketingDay

# write (from the repository root, or pass --schema-dir ../.pida-schemas)
node src/cli.mjs build examples/retail --graph pi-data-agent-demo \
  --data-graph demo-data --vertex-type MarketingDay

# read
node src/cli.mjs model --graph pi-data-agent-demo
node src/cli.mjs dashboard --graph pi-data-agent-demo --year 2025 --top 3
node src/cli.mjs attribute --graph pi-data-agent-demo \
  --metric 营收 --by 渠道 --from 2024 --to 2025
```

What to notice:

- `metric/点击率` is a ratio, so it has an expression but no single fact property. The model keeps it, the
  dashboard refuses to draw it, and the SQL is exported.
- `metric/订单数` is `COUNT(*)`; it binds to `count`, not to a column.
- `dim/日期` binds `year=year,month=month`, which is what makes the monthly trend work.
- `metric/营收` and `metric/revenue` (from the SQL alias) are the same definition + binding; the build merges
  them and records the merge as a warning. `metric/营收` keeps `revenue` as an alias.

An example edit file (`edits.json`) shows the reviewer operations; apply it with:

```bash
node src/cli.mjs edit examples/retail/edits.json --graph pi-data-agent-demo --dry-run
node src/cli.mjs edit examples/retail/edits.json --graph pi-data-agent-demo
```

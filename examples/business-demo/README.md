# Business demo: an incident corpus

The 三国 demo proves the pipeline; this one proves the vocabulary is **data**. Two short incident
documents are ingested with the `incident` rule pack and a business dictionary, without touching the
extractor.

## Run it

```bash
# start the service first (tools/start-phoenixgraph.sh)

node tools/ingest-docs.mjs examples/business-demo/documents \
  --graph incident-demo \
  --rule-pack incident \
  --dictionary examples/business-demo/dictionary.json \
  --apply
```

Without `--apply` it is a dry run: the extraction and quality report are printed, nothing is written.
Then ask the graph:

```bash
cat > /tmp/incident.json <<'EOF'
{"graph":"incident-demo","question":"Why did the checkout-api go out?","k":4,"hops":1}
EOF
curl -s -X POST localhost:8081/api/v1/agent/context \
  -H 'content-type: application/json' --data-binary @/tmp/incident.json
```

The answer contains the entities, the relations with the sentence each was read from, and a coverage
block. Expected relations include:

| head | relation | tail |
| --- | --- | --- |
| Deploy 42 | caused | checkout-api |
| bad config | caused | Deploy 42 |
| payment-service | depends_on | Redis |
| Alice | fixed | cache leak |
| Bob | reported | latency spike |
| Alice | owns | payment-service |
| checkout-api | deployed_on | payment-service |
| cache leak | blocked | Redis |

## How the vocabulary is selected

`--rule-pack incident` makes the server load `agent-rules/incident.json` from the `graph-agent` classpath.
The default is `sanguo`. A new domain means a new JSON pack (relation words, regex rules, optional window
verbs), not a new extractor:

```json
{
  "name": "incident",
  "relationWords": {},
  "rules": [
    { "name": "caused", "relation": "caused", "between": "^(?:caused|causes|leads? to|led to)(?: the| a| an)?$" },
    { "name": "was-caused-by", "relation": "caused", "between": "^(?:was|is) caused by(?: the| a| an)?$", "swap": true }
  ],
  "windows": []
}
```

`swap: true` reverses the direction, which is what a passive sentence needs.

## Files

- `documents/incident-2026-10-18.md` — the incident report.
- `documents/postmortem.md` — the postmortem.
- `dictionary.json` — the known entities and their types. A relation rule needs both ends to be known;
  the business pack deliberately has no window rules, so the dictionary is the entity source.

Use `--aliases aliases.json` to map variants to a canonical name before extraction (manual entity
resolution). For Confluence or Notion, use `tools/ingest-confluence.mjs` / `tools/ingest-notion.mjs`; they
feed the same ingestion API.

## Limits

- PDF requires `pdftotext` (poppler) on the machine running the CLI; the server never parses PDFs itself.
- A custom pack can be placed under `phoenixgraph.agent.rules-dir` (or `PHOENIXGRAPH_AGENT_RULES_DIR`) as
  `<name>.json`; it wins over the classpath and needs no rebuild. The name is limited to
  `[a-z0-9_-]+`.
- The extractor reads the text between two known mentions. It is deterministic and inspectable, not a
  language model; sentences that do not name both ends will not produce a relation.

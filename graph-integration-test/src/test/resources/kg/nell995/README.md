# NELL-995 knowledge-graph dataset (test resource)

These files are the triple dataset used by `KnowledgeGraphIntegrationTest` to
populate the `kg-entity-graph` schema.

## Provenance

| | |
|---|---|
| Dataset | NELL-995 (the 995-th iteration of the Never-Ending Language Learning KB) |
| Benchmark copy | [thunlp/OpenKE](https://github.com/thunlp/OpenKE) branch `OpenKE-PyTorch`, `benchmarks/NELL-995` |
| Source files | `entity2id.txt`, `relation2id.txt`, `train2id.txt` |
| Contents | 75,492 entities, 200 relations, 149,678 triples |

Each file is the upstream file verbatim, gzip-compressed (5.1 MB → 1.5 MB):

| file | format |
|---|---|
| `entity2id.txt.gz` | first line = entity count, then `name<TAB>id` |
| `relation2id.txt.gz` | first line = relation count, then `name<TAB>id` |
| `train2id.txt.gz` | first line = triple count, then `headId<TAB>tailId<TAB>relationId` |

## Regenerating

```bash
./tools/download-nell995-dataset.sh        # re-downloads and re-compresses into this directory
```

The dataset is only read by `KnowledgeGraphDataset`; no other module depends on
its layout. Triples are mapped onto the 10 `Entity0..Entity9` vertex schemas by
`KnowledgeGraphIds.composeId(name) = floorMod(name.hashCode(), 10) + "_" + name`,
so the type a given entity lands in does not depend on the dataset.

# 《三国演义》knowledge-graph dataset (test resource)

These files are the *document* dataset used by the Sanguo tests
(`SanguoRdfTest`, `SanguoGraphIntegrationTest`) to build an RDF view of
《三国演义》 and then to populate the `sanguo-kg` PhoenixGraph schema.

## Provenance

| | |
|---|---|
| Dataset | 四大名著人物关系知识图谱和 OWL 本体 — 《三国演义》 part |
| Publisher | OpenKG (`http://openkg.cn/dataset/ch4masterpieces`) |
| Mirror used | [`wcswcswcs/sgyy`](https://github.com/wcswcswcs/sgyy) (《三国演义》知识图谱) |
| Contents | **4,487 person documents**, 1 OWL ontology (60 object properties + 2 classes), 153 curated relation triples, plus the local 8-triple supplement |

Each file is the upstream file verbatim, gzip-compressed:

| file | upstream path | format |
|---|---|---|
| `renwu_process_v1.csv.gz` | `data/renwu_process_v1.csv` | one **document** per person: `编号,名字,性别,字号,年龄,正史,籍贯,父亲,母亲,兄弟,配偶,孩子,官职,主效,效力,总年数,年代列表,总章节,章节列表,历史,小说,头像,链接,效力数,配偶数` — 4,488 lines (header + **4,487 persons**, 2.7 MB raw / 1 MB gzipped) |
| `theory.owl.gz` | `data/data/三国演义/theory.owl` | the **OWL ontology**: `owl:Class` `male`/`female`, 60 `owl:ObjectProperty` relations with Chinese `rdfs:label`, `rdfs:subPropertyOf`, `owl:inverseOf`, `rdfs:domain`/`rdfs:range` and the OWL property characteristics |
| `triples.csv.gz` | `data/data/三国演义/triples.csv` | the curated relation extraction: `"head","tail","relation","label"` (English relation name + Chinese label) |
| **`triples-extra.csv`** | local (not upstream) | the **local supplement**: facts the upstream file left out but the ontology can express — the 五虎上将 offices and the 关羽/张飞 臣属 edges. Plain text so it can be edited; `#` lines are comments. Precedence: curated > extra > document. See [SANGUO-KG-QUERIES.md](../../../../../SANGUO-KG-QUERIES.md) §9 for the gap analysis behind it |

### ⚠ This file used to be truncated

The copy in this repository held only the **first 331 rows** of `renwu_process_v1.csv`, cut off mid-line — a
partial `curl` writes valid gzip, so every test passed and nothing noticed. It cost real coverage: the
missing 4,156 persons include 刘备 (the export happens to be ordered so that he fell after the cut) and
the whole 曹操 谋士 group (郭嘉/荀彧/荀攸/贾诩/刘晔/董昭…). `tools/download-sanguo-dataset.sh` now
**shape-checks every download** (line count + newline-terminated) so a truncated file fails loudly.

### The files this repository deliberately does *not* use

The upstream repository（[`wcswcswcs/sgyy`](https://github.com/wcswcswcs/sgyy)）also ships:

| file | why not |
|---|---|
| `data/renwu_relate.csv` | 25,091 rows, but **60.6 % of them have `未知` as the object** and the only relation types are `cata`/`cated`/`father`/`mother`/`brother`/`mate` — a flat re-encoding of the same document columns (`cata` = 主效, `cated` = 效力), not an enrichment |
| `data/renwu.pkl`, `data/sgyy.pkl`, `data/renwu.zip` | the crawler's raw/pickled intermediates of the same records |
| `data/data/{红楼梦,水浒传,西游记}/` | the other three 名著 — a different graph, not this dataset |

The relations are re-derived from the documents by `SanguoDataset` instead of being read from a
pre-baked file, so that the document → RDF step is the parser's job.

## Regenerating

```bash
./tools/download-sanguo-dataset.sh        # re-downloads, re-compresses and shape-checks into this directory
```

It verifies the line count of each file (4,488 / 154 / 823) and that it ends with a newline, so a
truncated download fails the script instead of silently shrinking the dataset.

The dataset is only read by `SanguoDataset` / `SanguoOntology`; no other module
depends on its layout. The RDF the parser produces is frozen under `rdf/` (see
its own header) and checked against a fresh run by `SanguoRdfTest`.

package com.pengcheng.phoenixgraph.integration.sanguo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>方案 C</b>'s graph: the base dataset plus everything {@link SanguoTextExtractor}
 * reads out of the document free text, destined for its own <b>RocksDB-Edge</b>
 * instance ({@code sanguo-kg-text}).
 *
 * <p>Composition (in precedence order, as everywhere in this pipeline):
 *
 * <pre>{@code
 * base = the 153 upstream curated triples + the document-derived links      (sanguo-kg-rocksdb-edge)
 * text = base + the manual supplement triples-extra.csv                      (方案 B)
 *             + the text-extracted relations                                 (方案 C)
 * }</pre>
 *
 * <p>Both supplements are passed as {@link SanguoDataset.RelationSource#EXTRA}, so the
 * base graph's own numbers are untouched. The extractor runs against the <em>base</em>
 * entity set (its gazetteer), so a mention is always a known name; the new entities it
 * finds are the ones the relation slots introduce (offices such as {@code 隃麋侯}, people
 * such as {@code 麹义}).
 */
public final class SanguoTextGraph {

    /** The graph name / schema of the text instance. */
    public static final String GRAPH = "sanguo-kg-text";

    /** The default RocksDB root, relative to the repository root. */
    public static final String DEFAULT_ROOT = ".rocksdb";

    private SanguoTextGraph() {
    }

    /** The text dataset plus the extraction result it was built from. */
    public record Built(SanguoDataset dataset, SanguoTextExtractor.Result extraction) {

        /** How many entities/relations the text graph adds on top of the base graph. */
        public String describe() {
            return String.format("%d entities, %d relations (%d materialized) — extracted %d relations, "
                            + "%d new offices, %d new people, %d allusions",
                    dataset.entities().size(), dataset.relations().size(), dataset.closedRelations().size(),
                    extraction.relations().size(), extraction.newPositions().size(),
                    extraction.newPersons().size(), extraction.allusions().size());
        }
    }

    /** Builds the text dataset from the base one (which is re-read from the classpath). */
    public static Built build() {
        return build(SanguoDataset.load());
    }

    /** Builds the text dataset from a given base dataset. */
    public static Built build(SanguoDataset base) {
        SanguoTextExtractor.Result extraction = SanguoTextExtractor.extract(base);

        // 方案 B's manual supplement, when it is on the classpath, plus 方案 C's extraction
        List<SanguoDataset.Relation> supplements = new ArrayList<>(readManualSupplement());
        // 策展边（方案 ⑨）：外部知识，像 triples-extra.csv 一样 —— 部将关系本体已声明、传记无原句。
        // 都城（capital）暂不能加：成都/建业/洛阳/邺城不在实体表，许昌则被一个同名人物占名。
        for (String[] curated : CURATED_EXTRA_RELATIONS) {
            supplements.add(new SanguoDataset.Relation(curated[0], curated[1], curated[2],
                    SanguoDataset.RelationSource.EXTRA));
        }
        for (SanguoTextExtractor.Extracted extracted : extraction.relations()) {
            supplements.add(new SanguoDataset.Relation(extracted.head(), extracted.relation(), extracted.tail(),
                    SanguoDataset.RelationSource.EXTRA));
        }

        SanguoOntology ontology = base.ontology().withTextProfile();
        // 方案 ③'s allusion entities carry a class no relation implies; 方案 ⑨'s 都城 place entities
        // likewise, so both travel in the typedEntities map
        Map<String, SanguoDataset.EntityType> typedEntities = new LinkedHashMap<>(extraction.allusions());
        typedEntities.putAll(CURATED_PLACES);
        for (String place : extraction.newPlaces()) {
            typedEntities.putIfAbsent(place, SanguoDataset.EntityType.PLACE);
        }
        // the narrative order (which sentence each relation was read at) travels with them, so a
        // relation can be ordered in time — see SANGUO-KG.md §7.8
        SanguoDataset dataset = SanguoDataset.of(base.documents(), base.curatedRelations(), supplements, ontology,
                typedEntities, extraction.narrativeOrder(), extraction.aliases(), extraction.eventYears(),
                extraction.eventYearEnds(), CURATED_GEO);
        return new Built(dataset, extraction);
    }

    /**
     * 策展边（方案 ⑨）：{head, relation, tail}，外部知识 —— 本体已声明但传记无对应原句的关系。
     * 方向：{@code subordinate} 的 head 是部将、tail 是上司（黄盖是周瑜的部将）；
     * {@code capital} 的 head 是君主、tail 是都城（曹操的都城是许昌城）。
     */
    private static final List<String[]> CURATED_EXTRA_RELATIONS = List.of(
            new String[] {"黄盖", "subordinate", "周瑜"},
            new String[] {"甘宁", "subordinate", "周瑜"},
            new String[] {"程普", "subordinate", "周瑜"},
            new String[] {"曹操", "capital", "许昌城"},
            new String[] {"刘备", "capital", "成都"},
            new String[] {"孙权", "capital", "建业"},
            new String[] {"董卓", "capital", "长安城"});

    /**
     * 都城作为 Place 实体（方案 ⑨）：成都/建业/洛阳不是人物名可直接用；许昌/长安与人物或错误
     * Person 实体重名，加「城」消歧 —— 与 {@code 曹节[献穆皇后]} 同一条消歧原则。
     */
    private static final Map<String, SanguoDataset.EntityType> CURATED_PLACES = Map.of(
            "成都", SanguoDataset.EntityType.PLACE,
            "建业", SanguoDataset.EntityType.PLACE,
            "洛阳", SanguoDataset.EntityType.PLACE,
            "许昌城", SanguoDataset.EntityType.PLACE,
            "长安城", SanguoDataset.EntityType.PLACE);

    /**
     * 地理坐标（方案 ⑩）：{name → [纬度, 经度]}，WGS84，策展表（外部知识）。
     * 都城与主要战场才有坐标 —— 地图可视化只画这些有据可查的点，不猜籍贯州郡的坐标。
     */
    private static final Map<String, double[]> CURATED_GEO = Map.ofEntries(
            // 都城
            Map.entry("许昌城", new double[] {34.03, 113.85}),
            Map.entry("成都", new double[] {30.57, 104.06}),
            Map.entry("建业", new double[] {32.06, 118.79}),
            Map.entry("洛阳", new double[] {34.62, 112.45}),
            Map.entry("长安城", new double[] {34.34, 108.94}),
            // 主要城市
            Map.entry("襄阳", new double[] {32.01, 112.12}),
            Map.entry("荆州", new double[] {30.33, 112.24}),
            Map.entry("徐州", new double[] {34.20, 117.28}),
            Map.entry("合肥", new double[] {31.82, 117.23}),
            Map.entry("长沙", new double[] {28.23, 112.94}),
            Map.entry("汉中", new double[] {33.07, 107.02}),
            Map.entry("南阳", new double[] {33.00, 112.53}),
            Map.entry("邺城", new double[] {36.13, 114.38}),
            Map.entry("江夏", new double[] {30.59, 114.31}),
            // 战场 / 事件发生地
            Map.entry("赤壁之战", new double[] {29.72, 113.90}),
            Map.entry("官渡之战", new double[] {34.71, 113.83}),
            Map.entry("夷陵之战", new double[] {30.70, 111.28}),
            Map.entry("长阪之战", new double[] {30.65, 111.80}),
            Map.entry("七进七出", new double[] {30.65, 111.80}),
            Map.entry("六出祁山", new double[] {34.50, 105.20}),
            Map.entry("割须弃袍", new double[] {34.54, 110.20}),
            Map.entry("水淹七军", new double[] {32.02, 112.12}),
            Map.entry("败走麦城", new double[] {30.35, 111.20}),
            Map.entry("三顾茅庐", new double[] {32.00, 112.00}),
            Map.entry("隆中对", new double[] {32.00, 112.00}),
            Map.entry("桃园结义", new double[] {39.49, 115.96}),
            Map.entry("温酒斩华雄", new double[] {34.80, 113.30}),
            Map.entry("三英战吕布", new double[] {34.80, 113.30}),
            Map.entry("过五关斩六将", new double[] {34.03, 113.85}),
            Map.entry("挟天子以令诸侯", new double[] {34.03, 113.85}),
            Map.entry("借东风", new double[] {29.72, 113.90}),
            Map.entry("舌战群儒", new double[] {29.72, 113.90}),
            Map.entry("八阵图", new double[] {29.72, 113.90}),
            Map.entry("刮骨疗伤", new double[] {30.30, 112.20}),
            Map.entry("托孤", new double[] {30.70, 108.80}));

    /** The manual supplement if it ships with the tests, otherwise nothing. */
    private static List<SanguoDataset.Relation> readManualSupplement() {
        try (InputStream in = SanguoTextGraph.class.getResourceAsStream("/kg/sanguo/triples-extra.csv")) {
            if (in == null) {
                return List.of();
            }
            return SanguoDataset.parseExtraTriples(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read triples-extra.csv", e);
        }
    }

    /** The schema of the text instance. */
    public static com.pengcheng.phoenixgraph.schema.GraphSchema loadSchema() {
        return SanguoRocksDbLoader.loadSchema(GRAPH);
    }

    /** Loads the text graph into {@code .rocksdb/sanguo-kg-text}. */
    public static SanguoRocksDbLoader.Result load(String rocksDbRoot, boolean fresh) {
        return load(build(), rocksDbRoot, fresh);
    }

    /** Loads a built text graph into {@code rocksDbRoot}. */
    public static SanguoRocksDbLoader.Result load(Built built, String rocksDbRoot, boolean fresh) {
        com.pengcheng.phoenixgraph.schema.GraphSchema schema = loadSchema();
        String root = rocksDbRoot == null || rocksDbRoot.isBlank() ? DEFAULT_ROOT : rocksDbRoot;
        java.nio.file.Path path = java.nio.file.Path.of(root).resolve(schema.storage().requireRocksDbPath());
        return SanguoRocksDbLoader.load(schema, built.dataset(), path, fresh);
    }

    /** Whether the text graph directory already holds a database. */
    public static boolean isLoaded(String rocksDbRoot) {
        java.nio.file.Path path = java.nio.file.Path.of(
                        rocksDbRoot == null || rocksDbRoot.isBlank() ? DEFAULT_ROOT : rocksDbRoot)
                .resolve(loadSchema().storage().requireRocksDbPath());
        return java.nio.file.Files.exists(path.resolve("CURRENT"));
    }

    /** A summary of the extraction, per rule, with a sample each (used by the report). */
    public static Map<String, String> summarise(SanguoTextExtractor.Result extraction) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> rules = extraction.countByRule().keySet();
        for (String rule : rules) {
            out.put(rule, extraction.samplesByRule().getOrDefault(rule, List.of()).stream()
                    .findFirst().orElse(""));
        }
        return out;
    }
}

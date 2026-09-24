package com.pengcheng.phoenixgraph.integration.sanguo;

/**
 * The vertex-id scheme of the {@code sanguo-kg} graph — the same <em>relation
 * sharding</em> as {@code kg-entity-graph}.
 *
 * <p>There is a single vertex schema ({@value #VERTEX_LABEL}) and a single edge
 * schema ({@value #EDGE_LABEL}). An entity is stored once per <em>relation
 * shard</em> it takes part in:
 *
 * <pre>{@code
 * shard    = floorMod(relation.hashCode(), 10)     // 0 .. 9 (always positive)
 * vertexId = entityName + "_" + shard              // e.g. "刘备_3", "第49回_7"
 * }</pre>
 *
 * <p>Because the shard comes from the relation, every edge of one relation lands
 * in a single pair of shards, so the ~2,400 relations spread over ten disjoint
 * groups of {@code Entity} records instead of piling up on one record per
 * person. The trade-off (identical to the NELL-995 graph) is that an entity with
 * relations in several shards exists several times, so a query for "everything
 * about 刘备" reads the ten ids {@code 刘备_0 … 刘备_9} — which is what the
 * multi-seed idiom {@code g.V('刘备_0', … ,'刘备_9','Entity')} is for.
 *
 * <p>{@link Math#floorMod(int, int)} is used instead of {@code hashCode() % 10}
 * so negative hash codes still map into {@code 0..9}; the seeder and every
 * example query share {@link #composeId}, so they always agree.
 */
public final class SanguoGraphIds {

    /** Name of the single vertex schema. */
    public static final String VERTEX_LABEL = "Entity";

    /** Name of the single edge schema. */
    public static final String EDGE_LABEL = "Entity_Entity";

    /** Number of relation shards. */
    public static final int SHARD_COUNT = 10;

    /** Separator between the entity name and the relation shard. */
    public static final String SEPARATOR = "_";

    private SanguoGraphIds() {
    }

    /** The relation shard (0..9) a relation name maps to. */
    public static int shard(String relation) {
        if (relation == null) {
            throw new IllegalArgumentException("relation must not be null");
        }
        return Math.floorMod(relation.hashCode(), SHARD_COUNT);
    }

    /** The stored vertex id of an entity name in the shard of the given relation. */
    public static String composeId(String entityName, String relation) {
        return composeId(entityName, shard(relation));
    }

    /** The stored vertex id of an entity name in the given shard. */
    public static String composeId(String entityName, int shard) {
        if (entityName == null) {
            throw new IllegalArgumentException("entity name must not be null");
        }
        if (shard < 0 || shard >= SHARD_COUNT) {
            throw new IllegalArgumentException("shard out of range: " + shard);
        }
        return entityName + SEPARATOR + shard;
    }

    /** The entity name a composed vertex id belongs to. */
    public static String nameOf(String vertexId) {
        int cut = vertexId.lastIndexOf(SEPARATOR);
        return cut < 0 ? vertexId : vertexId.substring(0, cut);
    }

    /** The shard of a composed vertex id. */
    public static int shardOf(String vertexId) {
        int cut = vertexId.lastIndexOf(SEPARATOR);
        return cut < 0 ? -1 : Integer.parseInt(vertexId.substring(cut + 1));
    }
}

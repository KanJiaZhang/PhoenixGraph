package com.pengcheng.phoenixgraph.integration;

/**
 * The knowledge graph's vertex-id scheme.
 *
 * <p>There is a single vertex schema ({@value #VERTEX_LABEL}) and a single edge
 * schema ({@value #EDGE_LABEL}). An entity is <em>sharded by the relation</em>:
 * for a triple {@code (head, relation, tail)} both endpoints are stored under
 *
 * <pre>{@code
 * shard    = floorMod(relation.hashCode(), 10)     // 0 .. 9 (always positive)
 * vertexId = entityName + "_" + shard              // e.g. "concept:athlete_5"
 * }</pre>
 *
 * <p>Because the shard comes from the relation, all edges of one relation land in
 * a single pair of shards and the relations are spread over ten disjoint groups
 * of {@code Entity} vertices instead of piling up on one record per entity.
 *
 * <p>{@link Math#floorMod(int, int)} is used instead of {@code hashCode() % 10}
 * so that negative hash codes still map into {@code 0..9}. The same helper is
 * used when seeding and when composing a query id, so the two always agree.
 */
public final class KnowledgeGraphIds {

    /** Name of the single vertex schema. */
    public static final String VERTEX_LABEL = "Entity";

    /** Name of the single edge schema. */
    public static final String EDGE_LABEL = "Entity_Entity";

    /** Number of relation shards. */
    public static final int SHARD_COUNT = 10;

    /** Separator between the entity name and the relation shard. */
    public static final String SEPARATOR = "_";

    private KnowledgeGraphIds() {
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
        return entityName + SEPARATOR + shard;
    }
}

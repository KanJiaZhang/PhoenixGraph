package com.pengcheng.phoenixgraph.integration;

/**
 * The knowledge graph's vertex-id scheme.
 *
 * <p>Every entity is identified by its <em>name</em>, but is stored under a
 * composed id that folds the name into one of the {@value #ENTITY_COUNT}
 * {@code Entity0..Entity9} vertex schemas:
 *
 * <pre>{@code
 * slot      = floorMod(name.hashCode(), 10)      // 0 .. 9  (always positive)
 * vertexId  = slot + "_" + name                  // e.g. "3_concept:athleteplayssport"
 * label     = "Entity" + slot                    // e.g. "Entity3"
 * }</pre>
 *
 * <p>{@link Math#floorMod(int, int)} is used instead of {@code hashCode() % 10}
 * so that negative hash codes still map into {@code 0..9}. The same helpers are
 * used when seeding and when composing a query id, so the two always agree.
 */
public final class KnowledgeGraphIds {

    /** Number of {@code Entity0..Entity9} vertex schemas. */
    public static final int ENTITY_COUNT = 10;

    /** Separator between the type slot and the name inside a composed id. */
    public static final String SEPARATOR = "_";

    private KnowledgeGraphIds() {
    }

    /** The {@code Entity} slot (0..9) an entity name maps to. */
    public static int slot(String name) {
        if (name == null) {
            throw new IllegalArgumentException("entity name must not be null");
        }
        return Math.floorMod(name.hashCode(), ENTITY_COUNT);
    }

    /** The vertex schema name holding the entity, e.g. {@code Entity3}. */
    public static String vertexLabel(String name) {
        return "Entity" + slot(name);
    }

    /** The stored vertex id of an entity name, e.g. {@code 3_concept:athlete}. */
    public static String composeId(String name) {
        return slot(name) + SEPARATOR + name;
    }

    /** The edge schema name connecting two entity names, e.g. {@code Entity3_Entity7}. */
    public static String edgeLabel(String sourceName, String targetName) {
        return vertexLabel(sourceName) + SEPARATOR + vertexLabel(targetName);
    }
}

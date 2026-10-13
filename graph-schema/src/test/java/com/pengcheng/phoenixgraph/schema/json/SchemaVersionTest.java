package com.pengcheng.phoenixgraph.schema.json;

import com.pengcheng.phoenixgraph.schema.GraphSchema;
import com.pengcheng.phoenixgraph.schema.PropertyType;
import com.pengcheng.phoenixgraph.schema.VertexPropertySchema;
import com.pengcheng.phoenixgraph.schema.VertexSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema file format's version field: written by this build, assumed 1 when absent, refused when it comes
 * from the future.
 *
 * <p>The refusal matters more than the writing. A reader that accepts a newer file "as far as it can" drops
 * whatever the newer build added, and the graph that results looks fine and behaves differently - so an
 * unknown version is an error that names both numbers.
 *
 * <p>The fixtures are built with the codec rather than typed by hand: a hand-written schema JSON in a test is a
 * second definition of the format, and it is the one that goes stale.
 */
class SchemaVersionTest {

    private final SchemaJsonCodec codec = new SchemaJsonCodec();

    /** A small but real schema, serialized by this build (so it carries the current version). */
    private String currentFile() {
        GraphSchema schema = new GraphSchema(1, "version-test", "a schema for the version tests")
                .vertex(new VertexSchema(1, "Thing", "a thing")
                        .property(new VertexPropertySchema(1, "label", "label", PropertyType.ScalarType.STRING)));
        return codec.toJson(schema);
    }

    /** The same file as an older build would have written it: without the version field at all. */
    private static String withoutVersion(String json) {
        String stripped = json.replaceAll("(?m)^\\s*\"schemaVersion\"\\s*:\\s*\\d+,?\\R", "");
        stripped = stripped.replaceAll(",\\s*\\n(\\s*}\\s*)$", "\n$1");
        assertFalse(stripped.contains("schemaVersion"), stripped);
        return stripped;
    }

    @Test
    void writingAddsTheCurrentVersion() {
        String json = currentFile();

        assertTrue(json.contains("\"schemaVersion\" : " + SchemaJsonCodec.SCHEMA_VERSION), json);
        assertEquals(SchemaJsonCodec.SCHEMA_VERSION, codec.versionOf(json));
        // a round trip keeps it: what this build writes, this build reads back
        assertEquals(SchemaJsonCodec.SCHEMA_VERSION, codec.versionOf(codec.toJson(codec.fromJson(json))));
    }

    @Test
    void aFileWithoutTheFieldIsVersionOneAndStillLoads() {
        String old = withoutVersion(currentFile());

        assertEquals(1, codec.versionOf(old));
        assertTrue(codec.canRead(old));
        GraphSchema parsed = codec.fromJson(old);
        assertEquals("version-test", parsed.name());
        assertEquals(1, parsed.vertexSchemas().size());
        assertEquals("label", parsed.vertexSchema("Thing").property(1).name());
    }

    @Test
    void aFileFromTheFutureIsRefusedByName() {
        String future = currentFile().replace("\"schemaVersion\" : 1", "\"schemaVersion\" : 99");

        assertFalse(codec.canRead(future));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> codec.fromJson(future));
        assertTrue(failure.getMessage().contains("schemaVersion 99"), failure.getMessage());
        assertTrue(failure.getMessage().contains("reads up to " + SchemaJsonCodec.SCHEMA_VERSION), failure.getMessage());
        // the version is still *readable*: that is how a tool reports what it found before refusing to load it
        assertEquals(99, codec.versionOf(future));
    }

    @Test
    void theCurrentVersionIsAcceptedExplicitly() {
        String same = currentFile().replace("\"schemaVersion\" : 1", "\"schemaVersion\" : " + SchemaJsonCodec.SCHEMA_VERSION);
        assertTrue(codec.canRead(same));
        assertEquals("version-test", codec.fromJson(same).name());
    }
}

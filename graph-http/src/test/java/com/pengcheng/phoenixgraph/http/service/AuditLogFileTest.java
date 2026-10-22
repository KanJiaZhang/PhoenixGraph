package com.pengcheng.phoenixgraph.http.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit file survives a restart and rotates instead of growing without bound. */
class AuditLogFileTest {

    @Test
    void theFileIsReloadedOnStart(@TempDir Path directory) {
        Path file = directory.resolve("audit.jsonl");
        AuditLog first = new AuditLog(file.toString(), 1_000_000, 3);
        first.record("dash", "writer", "POST", "/api/v1/query", "payment-graph", "g.V(1)", 200, 3);
        first.close();

        AuditLog second = new AuditLog(file.toString(), 1_000_000, 3);
        try {
            assertEquals(1, second.size());
            AuditLog.Entry entry = second.recent(1).get(0);
            assertEquals("dash", entry.key());
            assertEquals("payment-graph", entry.graph());
            assertEquals("g.V(1)", entry.query());
            assertEquals(200, entry.status());
            assertTrue((boolean) second.view(1).get("durable"));
        } finally {
            second.close();
        }
    }

    @Test
    void theFileRotates(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("audit.jsonl");
        AuditLog audit = new AuditLog(file.toString(), 300, 2);
        try {
            for (int i = 0; i < 20; i += 1) {
                audit.record("k", "reader", "GET", "/api/v1/graphs", null, null, 200, 1);
            }
        } finally {
            audit.close();
        }
        assertTrue(Files.exists(Path.of(file + ".1")), "the rotated file exists");
        assertTrue(Files.size(file) <= 300, "the live file stays under the limit");
        assertTrue(Files.exists(Path.of(file + ".2")), "the configured number of old files is kept");
        assertTrue(!Files.exists(Path.of(file + ".3")), "and no more than that");
    }
}

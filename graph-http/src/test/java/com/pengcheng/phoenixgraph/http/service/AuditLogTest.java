package com.pengcheng.phoenixgraph.http.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit buffer: newest first, bounded, and never holding a token.
 */
class AuditLogTest {

    @Test
    void entriesComeBackNewestFirstWithTheKeyName() {
        AuditLog audit = new AuditLog();
        audit.record("dash", "writer", "POST", "/api/v1/query", "payment-graph", "g.V(1)", 200, 3);
        audit.record("ops", "admin", "GET", "/api/v1/graphs", null, null, 200, 1);

        List<AuditLog.Entry> recent = audit.recent(10);
        assertEquals(2, recent.size());
        assertEquals("ops", recent.get(0).key());
        assertEquals("dash", recent.get(1).key());
        assertEquals("payment-graph", recent.get(1).graph());
        assertEquals("g.V(1)", recent.get(1).query());
    }

    @Test
    void theBufferIsBoundedAndTheOldestEntriesFallOut() {
        AuditLog audit = new AuditLog();
        for (int i = 0; i < 2500; i += 1) {
            audit.record("k", "reader", "GET", "/api/v1/graphs", null, null, 200, 1);
        }
        assertEquals(2000, audit.size(), "an audit buffer that grows with traffic is a memory leak");
        assertEquals(2000, audit.recent(5000).size(), "recent() is capped by what the buffer holds");
    }

    @Test
    void aLongQueryIsClippedAndAnAbsentOneIsMarked() {
        AuditLog audit = new AuditLog();
        audit.record("k", "writer", "POST", "/api/v1/query", "g", "g".repeat(500), 200, 1);
        audit.record("k", "reader", "GET", "/api/v1/graphs", null, null, 200, 1);

        List<AuditLog.Entry> recent = audit.recent(2);
        assertTrue(recent.get(1).query().length() <= 201, recent.get(1).query().length() + " chars");
        assertTrue(recent.get(1).query().endsWith("…"), recent.get(1).query());
        assertEquals("-", recent.get(0).query());
    }

    @Test
    void theViewSaysWhatItIs() {
        AuditLog audit = new AuditLog();
        audit.record("k", "reader", "GET", "/api/v1/graphs", null, null, 200, 1);

        Map<String, Object> view = audit.view(10);
        assertEquals(1, view.get("returned"));
        assertEquals(1, view.get("buffered"));
        assertEquals(2000, view.get("capacity"));
        assertTrue(String.valueOf(view.get("note")).contains(AuditLog.AUDIT_LOGGER), String.valueOf(view.get("note")));
        assertTrue(String.valueOf(view.get("entries")).contains("key=k"), String.valueOf(view.get("entries")));
    }

    @Test
    void entriesCarryTheTenant() {
        AuditLog audit = new AuditLog();
        audit.record("acme", "k", "reader", "GET", "/api/v1/graphs", null, null, 200, 1);
        assertEquals("acme", audit.recent(1).get(0).tenant());
        assertTrue(audit.view(1).get("entries").toString().contains("tenant=acme"));
    }
}

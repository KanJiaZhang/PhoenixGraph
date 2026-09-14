package com.pengcheng.phoenixgraph.storage;

import com.pengcheng.phoenixgraph.storage.log.InMemoryBusinessLog;
import com.pengcheng.phoenixgraph.storage.log.InMemoryErrorLog;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryingWriterTest {

    @Test
    void logsBusinessSuccessOnFirstAttempt() {
        InMemoryBusinessLog businessLog = new InMemoryBusinessLog();
        InMemoryErrorLog errorLog = new InMemoryErrorLog();
        RetryingWriter writer = new RetryingWriter(businessLog, errorLog);

        WriteOutcome outcome = writer.execute("putVertex", "v1", Map.of(), () -> { });

        assertEquals(WriteOutcome.SUCCESS, outcome);
        assertEquals(1, businessLog.entries().size());
        assertTrue(businessLog.entries().get(0).success());
        assertTrue(errorLog.entries().isEmpty());
    }

    @Test
    void retriesUntilSuccessWithinMaxAttempts() {
        InMemoryBusinessLog businessLog = new InMemoryBusinessLog();
        InMemoryErrorLog errorLog = new InMemoryErrorLog();
        RetryingWriter writer = new RetryingWriter(businessLog, errorLog);

        AtomicInteger attempts = new AtomicInteger();
        WriteOutcome outcome = writer.execute("updateVertexProperty", "v1", Map.of(), () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("transient");
            }
        });

        assertEquals(WriteOutcome.SUCCESS, outcome);
        assertEquals(3, attempts.get());
        assertEquals(1, businessLog.entries().size());
        assertTrue(businessLog.entries().get(0).success());
        assertTrue(errorLog.entries().isEmpty());
    }

    @Test
    void recordsErrorAfterThreeFailures() {
        InMemoryBusinessLog businessLog = new InMemoryBusinessLog();
        InMemoryErrorLog errorLog = new InMemoryErrorLog();
        RetryingWriter writer = new RetryingWriter(businessLog, errorLog);

        AtomicInteger attempts = new AtomicInteger();
        WriteOutcome outcome = writer.execute("putVertex", "v1", Map.of("k", "v"), () -> {
            attempts.incrementAndGet();
            throw new RuntimeException("boom");
        });

        assertEquals(WriteOutcome.FAILED, outcome);
        assertEquals(3, attempts.get());

        assertEquals(1, errorLog.entries().size());
        BusinessLogEntry error = errorLog.entries().get(0);
        assertEquals("putVertex", error.operation());
        assertEquals("v1", error.target());
        assertTrue(error.details().get("error").toString().contains("boom"));
        assertEquals("v", error.details().get("k")); // payload retained for replay

        assertEquals(1, businessLog.entries().size());
        assertFalse(businessLog.entries().get(0).success());
    }

    @Test
    void conflictIsNotRetriedAndNotErrorLogged() {
        InMemoryBusinessLog businessLog = new InMemoryBusinessLog();
        InMemoryErrorLog errorLog = new InMemoryErrorLog();
        RetryingWriter writer = new RetryingWriter(businessLog, errorLog);

        AtomicInteger attempts = new AtomicInteger();
        WriteOutcome outcome = writer.execute("updateVertexProperty", "v1", Map.of(), () -> {
            attempts.incrementAndGet();
            throw new WriteConflictException("stale");
        });

        assertEquals(WriteOutcome.CONFLICT, outcome);
        assertEquals(1, attempts.get()); // no retry
        assertTrue(errorLog.entries().isEmpty()); // conflict is not an error
        assertEquals(1, businessLog.entries().size());
        assertFalse(businessLog.entries().get(0).success());
        assertEquals(Boolean.TRUE, businessLog.entries().get(0).details().get("conflict"));
    }

    @Test
    void honorsConfiguredMaxAttempts() {
        InMemoryBusinessLog businessLog = new InMemoryBusinessLog();
        InMemoryErrorLog errorLog = new InMemoryErrorLog();
        RetryingWriter writer = new RetryingWriter(5, businessLog, errorLog);

        AtomicInteger attempts = new AtomicInteger();
        WriteOutcome outcome = writer.execute("removeVertex", "v1", Map.of(), () -> {
            attempts.incrementAndGet();
            throw new RuntimeException("boom");
        });

        assertEquals(WriteOutcome.FAILED, outcome);
        assertEquals(5, attempts.get());
        assertEquals(1, errorLog.entries().size());
    }
}

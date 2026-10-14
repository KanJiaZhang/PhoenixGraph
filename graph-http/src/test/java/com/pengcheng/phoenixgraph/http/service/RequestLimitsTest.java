package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.http.config.LimitsProperties;
import com.pengcheng.phoenixgraph.query.QueryDeadline;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admission gate, without a servlet: the three verdicts it can give, and the two things it must never
 * leak (a permit, or a deadline left behind on a pooled thread).
 */
class RequestLimitsTest {

    private static LimitsProperties properties(int maxConcurrent, int perGraph, int perSecond, Duration queueWait) {
        return new LimitsProperties(true, maxConcurrent, queueWait, perGraph, perSecond, Duration.ofMillis(200), 1, 1 << 20, 65536);
    }

    @Test
    void admitsWhileThereIsRoomAndRefusesWhenTheGraphIsBusy() {
        RequestLimits limits = new RequestLimits(properties(64, 1, 0, Duration.ofMillis(50)));

        assertEquals(RequestLimits.Admission.ADMITTED, limits.admit("g"));
        // the graph is at its own limit: a second request for the same graph is refused immediately, not
        // queued - waiting would only move the queue
        assertEquals(RequestLimits.Admission.BUSY, limits.admit("g"));
        // another graph is unaffected: the limit is per graph, on top of the instance-wide one
        assertEquals(RequestLimits.Admission.ADMITTED, limits.admit("other"));

        limits.release("g");
        assertEquals(RequestLimits.Admission.ADMITTED, limits.admit("g"));
        limits.release("other");
        limits.release("g");
    }

    @Test
    void refusesTheInstanceWhenEverySlotIsHeldAndTheWaitIsOver() {
        RequestLimits limits = new RequestLimits(properties(1, 8, 0, Duration.ofMillis(30)));

        assertEquals(RequestLimits.Admission.ADMITTED, limits.admit("g"));
        long started = System.nanoTime();
        assertEquals(RequestLimits.Admission.BUSY, limits.admit("other"));
        long waited = (System.nanoTime() - started) / 1_000_000;
        assertTrue(waited >= 25, "it waited its budget before giving up: " + waited + "ms");
        limits.release("g");
    }

    @Test
    void spendsThePerSecondBudgetAndRefillsInTheNextWindow() throws InterruptedException {
        RequestLimits limits = new RequestLimits(properties(64, 8, 3, Duration.ofMillis(10)));

        for (int i = 0; i < 3; i += 1) {
            assertEquals(RequestLimits.Admission.ADMITTED, limits.admit("g"), "request " + i);
            limits.release("g");
        }
        assertEquals(RequestLimits.Admission.RATE_LIMITED, limits.admit("g"),
                "the fourth request in the same second is refused before it takes a slot");

        // the window is one second: a short sleep is enough to be allowed again
        Thread.sleep(1100);
        assertEquals(RequestLimits.Admission.ADMITTED, limits.admit("g"));
        limits.release("g");
    }

    @Test
    void aDisabledGateAdmitsEverything() {
        RequestLimits limits = new RequestLimits(new LimitsProperties(false, 1, Duration.ZERO, 1, 1,
                Duration.ofMillis(50), 1, 1 << 20, 65536));
        for (int i = 0; i < 10; i += 1) {
            assertEquals(RequestLimits.Admission.ADMITTED, limits.admit("g"));
        }
    }

    @Test
    void anAdmittedRequestCarriesADeadlineAndReleaseClearsIt() {
        RequestLimits limits = new RequestLimits(properties(4, 4, 0, Duration.ofMillis(10)));

        assertEquals(RequestLimits.Admission.ADMITTED, limits.admit("g"));
        assertFalse(QueryDeadline.expired(), "a fresh budget is not spent");
        assertTrue(QueryDeadline.remainingNanos() > 0, "there is time left");

        limits.release("g");
        assertEquals(-1, QueryDeadline.remainingNanos(), "the pooled thread carries nothing over");
    }

    @Test
    void theSlotComesBackEvenWhenTheWorkThrows() throws Exception {
        RequestLimits limits = new RequestLimits(properties(1, 1, 0, Duration.ofMillis(20)));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch done = new CountDownLatch(1);
        try {
            pool.submit(() -> {
                limits.admit("g");
                try {
                    throw new IllegalStateException("the query failed");
                } catch (IllegalStateException expected) {
                    // the caller (the filter) releases in a finally block; this mimics that contract
                } finally {
                    limits.release("g");
                    done.countDown();
                }
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(RequestLimits.Admission.ADMITTED, limits.admit("g"), "the permit is available again");
            limits.release("g");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyThreadsNeverOverAdmitTheInstanceWideLimit() throws Exception {
        int slots = 4;
        RequestLimits limits = new RequestLimits(properties(slots, slots, 0, Duration.ofMillis(5)));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(8);
        try {
            for (int i = 0; i < 8; i += 1) {
                pool.submit(() -> {
                    started.countDown();
                    if (limits.admit("g") == RequestLimits.Admission.ADMITTED) {
                        if (inside.incrementAndGet() > slots) violations.incrementAndGet();
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        inside.decrementAndGet();
                        limits.release("g");
                    }
                });
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(200); // let the admitted ones pile up
            release.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(0, violations.get(), "never more requests inside than there are slots");
        assertEquals(RequestLimits.Admission.ADMITTED, limits.admit("g"));
        limits.release("g");
    }

    @Test
    void theCounterTellsHowManyWereRefused() {
        RequestLimits limits = new RequestLimits(properties(1, 1, 0, Duration.ofMillis(5)));
        limits.admit("g");
        limits.admit("g"); // refused
        assertEquals(1L, limits.counters().get("requests.admitted"));
        assertEquals(1L, limits.counters().get("requests.refused"));
        limits.release("g");
    }
}

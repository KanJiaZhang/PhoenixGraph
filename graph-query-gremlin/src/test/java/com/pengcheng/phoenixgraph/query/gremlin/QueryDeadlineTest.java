package com.pengcheng.phoenixgraph.query.gremlin;

import com.pengcheng.phoenixgraph.query.QueryDeadline;
import com.pengcheng.phoenixgraph.query.QueryTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget a request runs under. Two properties matter: a thread with no budget is never interrupted (the
 * engine is used by tests, tools and the batch loaders as well as by HTTP), and a thread that had one gets it
 * taken away again, because the next request on that pooled thread must not inherit it.
 */
class QueryDeadlineTest {

    @AfterEach
    void clear() {
        QueryDeadline.clear();
    }

    @Test
    void withoutABudgetNothingExpires() {
        assertFalse(QueryDeadline.expired());
        assertEquals(-1, QueryDeadline.remainingNanos());
        assertDoesNotThrow(QueryDeadline::check);
    }

    @Test
    void aSpentBudgetThrowsWithTheSettingInTheMessage() throws InterruptedException {
        QueryDeadline.set(Duration.ofMillis(20));
        assertFalse(QueryDeadline.expired());
        Thread.sleep(40);
        assertTrue(QueryDeadline.expired());
        QueryTimeoutException failure = assertThrows(QueryTimeoutException.class, QueryDeadline::check);
        assertTrue(failure.getMessage().contains("phoenixgraph.limits.request-timeout"), failure.getMessage());
    }

    @Test
    void clearingItMakesTheThreadCleanAgain() {
        QueryDeadline.set(Duration.ofMillis(1));
        QueryDeadline.clear();
        assertFalse(QueryDeadline.expired());
        assertEquals(-1, QueryDeadline.remainingNanos());
    }

    @Test
    void aZeroOrNegativeBudgetMeansNoBudget() {
        QueryDeadline.set(Duration.ZERO);
        assertEquals(-1, QueryDeadline.remainingNanos());
        QueryDeadline.set(Duration.ofSeconds(-5));
        assertEquals(-1, QueryDeadline.remainingNanos());
        QueryDeadline.set(null);
        assertEquals(-1, QueryDeadline.remainingNanos());
    }

}

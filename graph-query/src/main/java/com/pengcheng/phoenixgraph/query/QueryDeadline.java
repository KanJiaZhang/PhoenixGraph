package com.pengcheng.phoenixgraph.query;

import java.time.Duration;

/**
 * How long the current thread may still spend on a query - a deadline the engine checks, not a timer that
 * kills anything.
 *
 * <p>A servlet thread cannot be stopped from outside: interrupting it only helps if the code it is in checks
 * for interruption, and a thread that is inside a storage call or a tight hop loop ignores both. So the
 * deadline is *cooperative*: whoever admits the request sets it ({@code RequestLimits} in graph-http), the
 * engine asks between steps, and a query that has spent its budget fails with a clear message instead of
 * running until the client gives up.
 *
 * <p>Two boundaries worth knowing, both deliberate:
 * <ul>
 *   <li>a single storage call is not preempted - the socket timeout of the storage client is the outer bound,
 *       and the check happens before and after each step;</li>
 *   <li>a {@code DIST} run hands work to mapper threads, which do <b>not</b> inherit a thread-local deadline
 *       (the launcher's reduce phase still checks between its steps).</li>
 * </ul>
 *
 * <p>A thread-local rather than a parameter because the step implementations are registered once and shared:
 * threading a deadline through every step signature would touch the whole engine for a value that is only
 * ever set per request.
 */
public final class QueryDeadline {

    /** Set when a deadline is exceeded; the message names the setting, because that is what an operator edits. */
    public static final String MESSAGE =
            "The query exceeded phoenixgraph.limits.request-timeout and was stopped; "
            + "narrow the query (a limit, a time window, fewer seeds) or raise the limit";

    private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();

    private QueryDeadline() {
    }

    /** Starts (or restarts) a budget for the current thread. */
    public static void set(Duration budget) {
        if (budget == null || budget.isZero() || budget.isNegative()) {
            clear();
            return;
        }
        DEADLINE_NANOS.set(System.nanoTime() + budget.toNanos());
    }

    /** Ends the budget: the request is over, and the thread goes back to the pool. */
    public static void clear() {
        DEADLINE_NANOS.remove();
    }

    /** True when this thread has a budget and it is spent. */
    public static boolean expired() {
        Long deadline = DEADLINE_NANOS.get();
        return deadline != null && System.nanoTime() > deadline;
    }

    /** Throws {@link QueryTimeoutException} when the budget is spent. Called by the engine between steps. */
    public static void check() {
        if (expired()) {
            throw new QueryTimeoutException(MESSAGE);
        }
    }

    /** The remaining budget in nanoseconds, or -1 when the thread has none (for the profile output). */
    public static long remainingNanos() {
        Long deadline = DEADLINE_NANOS.get();
        return deadline == null ? -1 : deadline - System.nanoTime();
    }
}

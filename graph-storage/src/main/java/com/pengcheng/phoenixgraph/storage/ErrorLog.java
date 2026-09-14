package com.pengcheng.phoenixgraph.storage;

/**
 * Error log: a specialised {@link BusinessLog} that records failed write
 * operations to a separate sink so they can be inspected and enriched/replayed
 * later.
 *
 * <p>It shares the {@link BusinessLogEntry} format, but is stored independently
 * (its own logger, table or queue) so that failed writes can be checked and
 * re-issued without mixing them into the normal business-log stream.
 */
public interface ErrorLog extends BusinessLog {
}

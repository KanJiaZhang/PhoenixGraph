package com.pengcheng.phoenixgraph.storage.log;

import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;
import com.pengcheng.phoenixgraph.storage.ErrorLog;

import java.util.logging.Logger;

/**
 * Default {@link ErrorLog} backed by {@link java.util.logging} (JUL). Uses a
 * dedicated logger name so failures can be inspected separately from the
 * business-log stream.
 */
public class JulErrorLog implements ErrorLog {

    private static final Logger LOG = Logger.getLogger("phoenixgraph.error");

    @Override
    public void record(BusinessLogEntry entry) {
        LOG.warning(entry.toString());
    }
}

package com.pengcheng.phoenixgraph.storage.log;

import com.pengcheng.phoenixgraph.storage.BusinessLog;
import com.pengcheng.phoenixgraph.storage.BusinessLogEntry;

import java.util.logging.Logger;

/**
 * Default {@link BusinessLog} backed by {@link java.util.logging} (JUL).
 */
public class JulBusinessLog implements BusinessLog {

    private static final Logger LOG = Logger.getLogger("phoenixgraph.business");

    @Override
    public void record(BusinessLogEntry entry) {
        LOG.info(entry.toString());
    }
}

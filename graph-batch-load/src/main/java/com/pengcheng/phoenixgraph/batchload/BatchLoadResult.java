package com.pengcheng.phoenixgraph.batchload;

import java.util.List;

/**
 * Summary of a CSV batch import.
 *
 * @param type      {@code VERTEX} or {@code EDGE}
 * @param total     number of rows imported
 * @param succeeded rows written successfully
 * @param conflicts rows rejected by a CAS/version conflict
 * @param failed    rows that failed after retries
 * @param results   per-row results
 */
public record BatchLoadResult(String type, int total, int succeeded, int conflicts, int failed,
                              List<ItemResult> results) {

    public static BatchLoadResult of(String type, List<ItemResult> results) {
        int succeeded = 0;
        int conflicts = 0;
        int failed = 0;
        for (ItemResult result : results) {
            switch (result.status()) {
                case "SUCCESS" -> succeeded++;
                case "CONFLICT" -> conflicts++;
                default -> failed++;
            }
        }
        return new BatchLoadResult(type, results.size(), succeeded, conflicts, failed, results);
    }
}

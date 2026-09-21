package com.pengcheng.phoenixgraph.storage.aerospike;

import com.pengcheng.phoenixgraph.schema.EdgeSchema;
import com.pengcheng.phoenixgraph.storage.TimeRange;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The offline entry point of the Aerospike-Edge tiers - the daily job the spec describes.
 *
 * <p>One run for one (vertex, edge class, direction):
 *
 * <ol>
 *   <li><b>volume</b>: writes the monthly/yearly totals (computed by the caller over the cold data
 *       it already holds) into the volume store;</li>
 *   <li><b>near-line pre-partition</b>: from the latest month's volume it computes how many KVs the
 *       near-line day is cut into ({@link AerospikeNearline#partitionCount}) and writes the day's
 *       index KV with that many bins;</li>
 *   <li><b>cold delta</b>: applies the latest day's deltas to the cold tables
 *       ({@link AerospikeEdgeChunkStore#rewriteWindow});</li>
 *   <li><b>big-vertex test</b>: {@link EdgeTiering#isBig} of the offline degree against the
 *       schema's per-direction cap - the very definition of a big vertex.</li>
 * </ol>
 *
 * <p>The job only writes the near-line <em>index</em>; the data KVs are appended to by the
 * ordinary near-line write path as edges arrive, each into the partition the index names. The
 * online tier needs nothing from the job: the inline adjacency list already keeps the newest few
 * edges bounded by the degree cap.
 */
public class AerospikeEdgeOfflineJob {

    private final AerospikeVolumeStore volume;
    private final AerospikeNearlineStore nearline;
    private final AerospikeEdgeChunkStore cold;
    private final int chunkSize;

    public AerospikeEdgeOfflineJob(AerospikeVolumeStore volume, AerospikeNearlineStore nearline,
                                   AerospikeEdgeChunkStore cold, int chunkSize) {
        this.volume = Objects.requireNonNull(volume, "volume");
        this.nearline = Objects.requireNonNull(nearline, "nearline");
        this.cold = Objects.requireNonNull(cold, "cold");
        this.chunkSize = chunkSize <= 0 ? AerospikeEdgeChunks.DEFAULT_CHUNK_SIZE : chunkSize;
    }

    /** What one daily run wrote. */
    public record Result(long degree, long latestMonthlyVolume, int nearlinePartitions,
                         int coldRecords, boolean big) {
    }

    /**
     * Runs the daily job for one vertex, edge class and direction.
     *
     * @param directionCode     {@link AerospikeEdgeStore#EDGE_DIR_IN}/{@code _OUT}/{@code _BOTH}
     * @param dayStartMillis    the start of the day the deltas belong to (inclusive)
     * @param dayEndMillis      the end of that day (inclusive)
     * @param deltaEdges        the day's deltas (each edge id once, newest copy); the cold tables
     *                          merge them over the day's existing chunks
     * @param volumeByBin       the monthly/yearly totals (bin names {@code m_yyyyMM}/{@code y_yyyy})
     * @param totalDegree       the vertex's offline out/in degree (for the big-vertex test)
     * @param maxDegree         the schema's cap ({@code <= 0} = unlimited)
     */
    public Result daily(EdgeSchema edgeSchema, int directionCode, Object vertexId,
                        long dayStartMillis, long dayEndMillis,
                        List<AerospikeEdgeChunks.EdgeEntry> deltaEdges,
                        Map<String, Long> volumeByBin, long totalDegree, int maxDegree) {
        Objects.requireNonNull(edgeSchema, "edgeSchema");
        Objects.requireNonNull(vertexId, "vertexId");
        volume.put(edgeSchema.id(), directionCode, vertexId, volumeByBin);
        long latestMonthly = volume.latestMonthlyVolume(edgeSchema.id(), directionCode, vertexId);
        int partitions = AerospikeNearline.partitionCount(latestMonthly);
        nearline.prePartition(vertexId, edgeSchema.id(), directionCode,
                AerospikeNearline.dayOf(dayStartMillis), partitions);
        int records = cold.rewriteWindow(edgeSchema, directionCode, vertexId,
                TimeRange.of(dayStartMillis, dayEndMillis), deltaEdges, chunkSize);
        return new Result(totalDegree, latestMonthly, partitions, records,
                EdgeTiering.isBig(totalDegree, maxDegree));
    }
}

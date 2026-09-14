package com.pengcheng.phoenixgraph.storage.aerospike;

import com.aerospike.client.ResultCode;
import com.aerospike.client.policy.BatchWritePolicy;
import com.aerospike.client.policy.GenerationPolicy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import com.pengcheng.phoenixgraph.storage.WriteOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AerospikeVertexStoreTest {

    @Test
    void propertyBinUsesVpPrefixAndIndex() {
        assertEquals("vp_0", AerospikeVertexStore.propertyBin(0));
        assertEquals("vp_42", AerospikeVertexStore.propertyBin(42));
    }

    @Test
    void propertyTimeBinUsesVptPrefixAndIndex() {
        assertEquals("vpt_0", AerospikeVertexStore.propertyTimeBin(0));
        assertEquals("vpt_42", AerospikeVertexStore.propertyTimeBin(42));
    }

    @Test
    void propertyBinsIncludeValueTimeAndMetaBins() {
        assertArrayEquals(new String[]{"vp_1", "vpt_1", "vp_3", "vpt_3", "vp_-2", "vp_-1"},
                AerospikeVertexStore.propertyBins(List.of(1, 3)));
    }

    @Test
    void isNewerComparesPropertyLastModifiedTime() {
        assertTrue(AerospikeVertexStore.isNewer(201L, 200L));  // newer
        assertFalse(AerospikeVertexStore.isNewer(200L, 200L)); // equal
        assertFalse(AerospikeVertexStore.isNewer(100L, 200L)); // older
    }

    @Test
    void binNameConstantsAreStable() {
        assertEquals("vp_", AerospikeVertexStore.PROPERTY_BIN_PREFIX);
        assertEquals("vpt_", AerospikeVertexStore.PROPERTY_TIME_BIN_PREFIX);
        assertEquals(-2, AerospikeVertexStore.CREATED_TIME_INDEX);
        assertEquals(-1, AerospikeVertexStore.LAST_UPDATED_TIME_INDEX);
        assertEquals("vp_-2", AerospikeVertexStore.CREATED_TIME_BIN);
        assertEquals("vp_-1", AerospikeVertexStore.LAST_UPDATED_TIME_BIN);
    }

    @Test
    void createOnlyPolicyUsesCreateOnlyRecordExistsAction() {
        WritePolicy policy = AerospikeVertexStore.createOnlyPolicy();
        assertEquals(RecordExistsAction.CREATE_ONLY, policy.recordExistsAction);

        BatchWritePolicy batch = AerospikeVertexStore.createOnlyBatchPolicy();
        assertEquals(RecordExistsAction.CREATE_ONLY, batch.recordExistsAction);
    }

    @Test
    void generationPolicyUsesExpectedGeneration() {
        WritePolicy policy = AerospikeVertexStore.generationPolicy(7);
        assertEquals(GenerationPolicy.EXPECT_GEN_EQUAL, policy.generationPolicy);
        assertEquals(7, policy.generation);

        BatchWritePolicy batch = AerospikeVertexStore.generationBatchPolicy(7);
        assertEquals(GenerationPolicy.EXPECT_GEN_EQUAL, batch.generationPolicy);
        assertEquals(7, batch.generation);
    }

    @Test
    void outcomeMapping() {
        assertEquals(WriteOutcome.SUCCESS, AerospikeVertexStore.toOutcome(ResultCode.OK));
        assertEquals(WriteOutcome.CONFLICT, AerospikeVertexStore.toOutcome(ResultCode.FILTERED_OUT));
        assertEquals(WriteOutcome.CONFLICT, AerospikeVertexStore.toOutcome(ResultCode.KEY_EXISTS_ERROR));
        assertEquals(WriteOutcome.CONFLICT, AerospikeVertexStore.toOutcome(ResultCode.GENERATION_ERROR));
        assertEquals(WriteOutcome.FAILED, AerospikeVertexStore.toOutcome(ResultCode.TIMEOUT));
    }

    @Test
    void expiredSentinelIsEpochZero() {
        // soft-delete flag: lastUpdatedTime 1970-01-01T00:00:00Z (epoch 0)
        assertEquals(0L, AerospikeVertexStore.EXPIRED_LAST_UPDATED_TIME);
    }
}

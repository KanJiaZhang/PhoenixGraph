package com.pengcheng.phoenixgraph.http.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoragePropertiesTest {

    @Test
    void aSeedListOverridesTheSingleHost() {
        StorageProperties properties = new StorageProperties("localhost", 3000, "test", "set", "graph",
                ".rocksdb", null, List.of(), List.of("aerospike-1:3000", "aerospike-2:3000"));
        assertEquals(List.of("aerospike-1:3000", "aerospike-2:3000"), properties.seedHosts());
    }

    @Test
    void withoutASeedListTheHostAndPortAreTheSeed() {
        StorageProperties properties = new StorageProperties("store", 3100, "test", "set", "graph",
                ".rocksdb", null, List.of(), List.of());
        assertEquals(List.of("store:3100"), properties.seedHosts());
    }
}

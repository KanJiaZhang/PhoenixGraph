package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.HttpReranker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RerankerFactoryTest {

    @Test
    void noneKeepsTheHybridOrder() {
        RerankerFactory factory = new RerankerFactory("none", "", "", "model", 5);
        assertInstanceOf(NoopReranker.class, factory.current());
    }

    @Test
    void httpBuildsTheProviderReranker() {
        RerankerFactory factory = new RerankerFactory("http", "http://localhost:9999/v1", "key", "model", 5);
        assertInstanceOf(HttpReranker.class, factory.current());
    }
}

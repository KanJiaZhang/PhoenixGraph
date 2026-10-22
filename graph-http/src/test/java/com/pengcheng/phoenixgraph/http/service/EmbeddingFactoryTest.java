package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.Embedding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingFactoryTest {

    @Test
    void defaultsToTheHashingBaseline() {
        EmbeddingFactory factory = new EmbeddingFactory("hashing", "", "", "model", 64, 5);
        Embedding embedding = factory.current();
        assertEquals(64, embedding.dimensions());
        assertEquals("hashing-64", embedding.name());
    }

    @Test
    void rebuildsTheEmbedderAnArtifactNames() {
        EmbeddingFactory factory = new EmbeddingFactory("hashing", "", "", "model", 64, 5);
        Embedding embedding = factory.forIndex("hashing-32");
        assertEquals(32, embedding.dimensions());
        assertEquals("hashing-32", embedding.name());
    }

    @Test
    void buildsAnHttpEmbedderFromTheDescriptor() {
        EmbeddingFactory factory = new EmbeddingFactory("http", "http://localhost:9999/v1", "key", "model", 64, 5);
        Embedding embedding = factory.forIndex("http:small:16");
        assertEquals(16, embedding.dimensions());
        assertEquals("http:small:16", embedding.name());
    }

    @Test
    void aModelNameMayContainAColon() {
        EmbeddingFactory factory = new EmbeddingFactory("hashing", "http://localhost:9999/v1", "", "m", 64, 5);
        Embedding embedding = factory.forIndex("http:llama3:embed:8");
        assertEquals(8, embedding.dimensions());
        assertEquals("http:llama3:embed:8", embedding.name());
    }
}

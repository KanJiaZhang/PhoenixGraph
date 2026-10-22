package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.agent.RulePack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulePackRegistryTest {

    @Test
    void aPackInTheDirectoryWins(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("support.json"), """
                {
                  "name": "support",
                  "relationWords": {},
                  "rules": [
                    { "name": "supports", "relation": "supports", "between": "^supports$" }
                  ],
                  "windows": []
                }
                """);
        RulePackRegistry registry = new RulePackRegistry(directory.toString());

        RulePack pack = registry.resolve("support");

        assertEquals("support", pack.name());
        assertEquals(1, pack.rules().size());
        assertTrue(registry.available().contains("support"));
        assertTrue(registry.available().contains("incident"));
    }

    @Test
    void theBuiltInPacksStillResolve() {
        RulePackRegistry registry = new RulePackRegistry("");
        assertEquals("sanguo", registry.resolve(null).name());
        assertEquals("incident", registry.resolve("incident").name());
    }

    @Test
    void aNameCannotEscapeTheDirectory() {
        RulePackRegistry registry = new RulePackRegistry("");
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("../secret"));
    }
}

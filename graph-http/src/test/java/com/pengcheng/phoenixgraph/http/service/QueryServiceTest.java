package com.pengcheng.phoenixgraph.http.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryServiceTest {

    @Test
    void substituteReplacesPlaceholdersFromParams() {
        String query = "g.V(${id}, 'Account').out('Payment').has('status', '${status}')";

        String substituted = QueryService.substitute(query, Map.of("id", "1001", "status", "SUCCESS"));

        assertEquals("g.V(1001, 'Account').out('Payment').has('status', 'SUCCESS')", substituted);
    }

    @Test
    void substituteLeavesQueryUnchangedWhenNoParams() {
        String query = "g.V(1001, 'Account').count()";

        assertEquals(query, QueryService.substitute(query, null));
        assertEquals(query, QueryService.substitute(query, Map.of()));
    }

    @Test
    void substituteThrowsOnMissingParameter() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryService.substitute("g.V(${id}, 'Account')", Map.of("other", "x")));
    }
}

package com.pengcheng.phoenixgraph.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhoenixGraphExceptionTest {

    @Test
    void carriesMessageOnly() {
        PhoenixGraphException ex = new PhoenixGraphException("boom");
        assertEquals("boom", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void carriesMessageAndCause() {
        PhoenixGraphException ex = new PhoenixGraphException("boom", new IllegalStateException());
        assertEquals("boom", ex.getMessage());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }
}

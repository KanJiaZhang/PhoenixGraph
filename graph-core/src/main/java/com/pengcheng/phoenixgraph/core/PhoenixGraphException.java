package com.pengcheng.phoenixgraph.core;

/**
 * Base runtime exception for all PhoenixGraph failures.
 */
public class PhoenixGraphException extends RuntimeException {

    public PhoenixGraphException(String message) {
        super(message);
    }

    public PhoenixGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}

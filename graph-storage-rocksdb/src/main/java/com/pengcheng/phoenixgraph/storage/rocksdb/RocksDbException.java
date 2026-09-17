package com.pengcheng.phoenixgraph.storage.rocksdb;

/**
 * Raised when a RocksDB operation fails or a stored value cannot be decoded.
 *
 * <p>Unchecked on purpose: the storage layer is called from query and load paths
 * that do not expect checked IO exceptions, and a failing RocksDB call is never
 * recoverable at the call site (the process-wide store is the unit of recovery).
 */
public class RocksDbException extends RuntimeException {

    public RocksDbException(String message) {
        super(message);
    }

    public RocksDbException(String message, Throwable cause) {
        super(message, cause);
    }
}

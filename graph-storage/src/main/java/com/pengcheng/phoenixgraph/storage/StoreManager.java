package com.pengcheng.phoenixgraph.storage;

import java.util.Collection;

/**
 * Registry of available {@link StorageEngine} implementations.
 */
public interface StoreManager {

    void register(StorageEngine engine);

    StorageEngine engine(String name);

    Collection<StorageEngine> engines();
}

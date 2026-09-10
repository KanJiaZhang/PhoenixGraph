package com.pengcheng.phoenixgraph.storage.memory;

import com.pengcheng.phoenixgraph.storage.ColumnStore;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link ColumnStore} reference implementation (not thread-safe).
 */
public class InMemoryColumnStore implements ColumnStore {

    private final Map<Object, Map<String, Object>> records = new HashMap<>();

    @Override
    public void put(Object key, Map<String, Object> columns) {
        records.put(key, new HashMap<>(columns));
    }

    @Override
    public Map<String, Object> get(Object key) {
        Map<String, Object> columns = records.get(key);
        return columns == null ? null : Collections.unmodifiableMap(new HashMap<>(columns));
    }

    @Override
    public void putColumn(Object key, String column, Object value) {
        records.computeIfAbsent(key, k -> new HashMap<>()).put(column, value);
    }

    @Override
    public Object getColumn(Object key, String column) {
        Map<String, Object> columns = records.get(key);
        return columns == null ? null : columns.get(column);
    }

    @Override
    public void removeColumn(Object key, String column) {
        Map<String, Object> columns = records.get(key);
        if (columns != null) {
            columns.remove(column);
        }
    }

    @Override
    public void remove(Object key) {
        records.remove(key);
    }
}

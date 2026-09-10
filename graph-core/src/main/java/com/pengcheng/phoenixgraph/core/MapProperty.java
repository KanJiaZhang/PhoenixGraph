package com.pengcheng.phoenixgraph.core;

import java.util.Map;

/**
 * A {@link Property} whose value is a map keyed by string. Each entry carries its
 * own typed value and timestamps, and entries can be added or removed
 * individually (unlike a list property, which is replaced as a whole).
 */
public interface MapProperty extends Property {

    /** Immutable view of all entries. */
    Map<String, MapEntryValue> entries();

    /** Returns the entry for the given key, or {@code null}. */
    MapEntryValue get(String key);

    /** Adds or replaces an entry; its created/last-updated timestamps are set to now. */
    MapEntryValue put(String key, Object value);

    /** Removes and returns the entry, or {@code null} if absent. */
    MapEntryValue remove(String key);

    boolean containsKey(String key);
}

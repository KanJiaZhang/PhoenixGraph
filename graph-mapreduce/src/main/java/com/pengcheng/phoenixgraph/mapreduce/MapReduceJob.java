package com.pengcheng.phoenixgraph.mapreduce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A lightweight, in-process MapReduce job.
 *
 * <p>The mapper transforms each input line into key/value pairs, values are
 * grouped by key (an in-memory shuffle bounded by {@code spillSize}), and the
 * reducer merges each group into a single output which is handed to the writer.
 * Groups are reduced and written incrementally as they spill, so the whole input
 * is never held in memory at once.
 */
public final class MapReduceJob<K, V, O> {

    /** Supplies input lines to the map phase. */
    @FunctionalInterface
    public interface LineSource {
        void forEachLine(Consumer<String> consumer) throws IOException;
    }

    /** Transforms one input line into zero or more (key, value) pairs. */
    @FunctionalInterface
    public interface Mapper<K, V> {
        void map(String line, Emitter<K, V> emitter);
    }

    /** Emits a key/value pair from the mapper. */
    @FunctionalInterface
    public interface Emitter<K, V> {
        void emit(K key, V value);
    }

    /** Combines all values of one key into a single output. */
    @FunctionalInterface
    public interface Reducer<K, V, O> {
        O reduce(K key, List<V> values);
    }

    /** Receives reduced outputs. */
    @FunctionalInterface
    public interface OutputWriter<O> {
        void write(O output);
    }

    private final Mapper<K, V> mapper;
    private final Reducer<K, V, O> reducer;
    private final OutputWriter<O> writer;
    private final int spillSize;

    public MapReduceJob(Mapper<K, V> mapper, Reducer<K, V, O> reducer,
                        OutputWriter<O> writer, int spillSize) {
        if (spillSize < 1) {
            throw new IllegalArgumentException("spillSize must be >= 1");
        }
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.reducer = Objects.requireNonNull(reducer, "reducer");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.spillSize = spillSize;
    }

    /** Runs the job over the given line sources. */
    public void run(List<LineSource> sources) throws IOException {
        Map<K, List<V>> groups = new LinkedHashMap<>();
        for (LineSource source : sources) {
            source.forEachLine(line -> {
                mapper.map(line, (key, value) ->
                        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(value));
                if (groups.size() >= spillSize) {
                    flush(groups);
                    groups.clear();
                }
            });
        }
        flush(groups);
    }

    private void flush(Map<K, List<V>> groups) {
        for (Map.Entry<K, List<V>> entry : groups.entrySet()) {
            writer.write(reducer.reduce(entry.getKey(), entry.getValue()));
        }
    }
}

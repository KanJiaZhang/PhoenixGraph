package com.pengcheng.phoenixgraph.mapreduce;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link MapReduceJob.LineSource} reading a CSV file and skipping the header
 * line.
 */
public final class CsvFileLineSource implements MapReduceJob.LineSource {

    private final Path file;

    public CsvFileLineSource(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    @Override
    public void forEachLine(Consumer<String> consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.accept(line);
            }
        }
    }
}

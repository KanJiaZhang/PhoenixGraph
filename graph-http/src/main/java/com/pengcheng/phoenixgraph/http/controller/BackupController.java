package com.pengcheng.phoenixgraph.http.controller;

import com.pengcheng.phoenixgraph.http.config.RocksDbGraphStores;
import com.pengcheng.phoenixgraph.http.config.StorageProperties;
import com.pengcheng.phoenixgraph.http.service.GraphBinding;
import com.pengcheng.phoenixgraph.http.service.GraphResolver;
import com.pengcheng.phoenixgraph.schema.GraphSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Point-in-time copies of a graph's directory, for the backup half of a restore drill.
 *
 * <p>A RocksDB graph *is* its directory, so a backup is RocksDB's own checkpoint: a consistent snapshot made of
 * hard links, which costs a moment and a few kilobytes and does not stop the service. Restoring is "point a
 * service at the copy", which is why this endpoint returns the path rather than a stream: the copy is a
 * filesystem object an operator can move to another disk, and no import step can go wrong.
 *
 * <p>An Aerospike graph has no directory here: its backup belongs to the database ({@code asbackup}), and the
 * endpoint says so instead of pretending. See {@code docs/OPERATIONS.md}.
 */
@RestController
@RequestMapping("/api/v1/graphs/{graph}")
public class BackupController {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupController.class);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final RocksDbGraphStores rocksDbStores;
    private final StorageProperties properties;
    private final GraphResolver graphs;

    public BackupController(RocksDbGraphStores rocksDbStores, StorageProperties properties, GraphResolver graphs) {
        this.rocksDbStores = rocksDbStores;
        this.properties = properties;
        this.graphs = graphs;
    }

    /**
     * Snapshots the graph now.
     *
     * @param graph the graph (must be an existing graph served by a RocksDB scheme)
     * @param dir   where the copy goes; default {@code <rocksdb-root>/checkpoints/<graph>-<utc stamp>}
     */
    @PostMapping(value = "/checkpoint", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> checkpoint(@PathVariable("graph") String graph,
                                          @RequestParam(name = "dir", required = false) String dir) {
        GraphSchema schema = graphs.require(graph).schema();
        if (!rocksDbStores.canCheckpoint(schema.name())) {
            throw new IllegalArgumentException("Graph '" + schema.name() + "' is not served by RocksDB, so it has no "
                    + "directory to checkpoint; back up an Aerospike graph with asbackup (docs/OPERATIONS.md)");
        }
        String source = rocksDbStores.pathOf(schema.name());
        Path target = dir == null || dir.isBlank()
                ? defaultTarget(schema.name())
                : Path.of(dir);
        int files = rocksDbStores.checkpoint(schema.name(), target);
        long bytes = sizeOf(target);
        LOGGER.info("checkpoint of graph '{}' written to {} ({} files, {} bytes)", schema.name(), target, files, bytes);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("graph", schema.name());
        answer.put("checkpoint", target.toAbsolutePath().toString());
        answer.put("source", source);
        answer.put("files", files);
        answer.put("bytes", bytes);
        answer.put("restore", "start an instance whose schema points at this directory, or copy it back over "
                + source + " with the service stopped (docs/OPERATIONS.md)");
        return answer;
    }

    /**
     * Where this graph lives, and how to copy it.
     *
     * <p>The graph list says which graphs exist; this says which Aerospike set or which directory holds one, so
     * an operator (or `tools/backup-graph.sh`) does not have to read a schema or a configuration file to find
     * out. `backup` states the method that applies - a checkpoint for a directory, `asbackup` for a set -
     * because "back up a graph" means two different things here and pretending otherwise would be the bug.
     */
    @GetMapping(value = "/storage", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> storage(@PathVariable("graph") String graph) {
        GraphBinding binding = graphs.require(graph);
        GraphSchema schema = binding.schema();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("graph", schema.name());
        answer.put("storage", schema.storage() == null ? "Aerospike" : schema.storage().type().configName());
        answer.put("vertexSet", binding.vertexSet());
        answer.put("namespace", binding.namespace() == null ? properties.namespace() : binding.namespace());
        boolean directory = rocksDbStores.canCheckpoint(schema.name());
        answer.put("directory", directory ? rocksDbStores.pathOf(schema.name()) : null);
        answer.put("backup", directory
                ? "POST /api/v1/graphs/" + schema.name() + "/checkpoint (a hard-linked RocksDB snapshot)"
                : "asbackup -n " + properties.namespace() + " -s " + binding.vertexSet() + " (see docs/OPERATIONS.md)");
        return answer;
    }

    private Path defaultTarget(String graph) {
        String root = properties.rocksdbRoot();
        Path base = root == null || root.isBlank() ? Path.of(".") : Path.of(root);
        return base.resolve("checkpoints").resolve(graph + "-" + STAMP.format(Instant.now()));
    }

    private static long sizeOf(Path directory) {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong((file) -> {
                try {
                    return Files.size(file);
                } catch (IOException unreadable) {
                    return 0L;
                }
            }).sum();
        } catch (IOException unreadable) {
            return 0L;
        }
    }
}

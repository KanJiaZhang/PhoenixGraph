package com.pengcheng.phoenixgraph.http.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who did what: one entry per API call, kept in a bounded in-memory view and, when configured, an append-only
 * JSONL file that survives a restart.
 *
 * <p>"Who queried what" is the question an audit has to answer, and a log line alone answers it only if
 * somebody kept the log. So the entry goes to three places: a dedicated logger
 * ({@code com.pengcheng.phoenixgraph.audit}, which a deployment routes to its own collector), an optional
 * JSONL file ({@code phoenixgraph.audit.file}) that is reloaded on start, and a bounded ring buffer, so an
 * operator can ask the running instance for the last N calls ({@code GET /api/v1/audit/recent}) without
 * shipping logs anywhere.
 *
 * <p>The file rotates at {@code max-bytes} and keeps {@code keep-files} old files. A durable audit that grows
 * without bound is a disk leak with a compliance story; the logger and the rotated files are the record, the
 * ring is a convenience view.
 *
 * <p>What is recorded: the key's *name*, never its token; the method and path (a query string is not part of
 * the path and is not stored); the graph; the query text, clipped, because a query is the thing an analyst
 * asks about and it is also the thing that could carry a secret - which is why the clip is short and
 * documented.
 */
@Component
public class AuditLog {

    /** The logger a deployment routes wherever its audit trail has to live. */
    public static final String AUDIT_LOGGER = "com.pengcheng.phoenixgraph.audit";

    private static final Logger AUDIT = LoggerFactory.getLogger(AUDIT_LOGGER);
    private static final Logger LOG = LoggerFactory.getLogger(AuditLog.class);
    private static final int CAPACITY = 2000;
    private static final int QUERY_CLIP = 200;

    /** One call. */
    public record Entry(Instant at, String key, String role, String method, String path, String graph,
                        String query, int status, long millis, String tenant) {
    }

    private final Deque<Entry> recent = new ArrayDeque<>(CAPACITY);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    private final long maxBytes;
    private final int keepFiles;
    private BufferedWriter writer;
    private long bytesWritten;

    /** In-memory only: the tests and a deployment that forwards the logger. */
    public AuditLog() {
        this("", 0, 1);
    }

    @Autowired
    public AuditLog(@Value("${phoenixgraph.audit.file:}") String file,
                    @Value("${phoenixgraph.audit.max-bytes:10485760}") long maxBytes,
                    @Value("${phoenixgraph.audit.keep-files:5}") int keepFiles) {
        this.file = file == null || file.isBlank() ? null : Path.of(file).toAbsolutePath();
        this.maxBytes = Math.max(0, maxBytes);
        this.keepFiles = Math.max(1, keepFiles);
        if (this.file != null) {
            try {
                open();
                loadRecent();
            } catch (IOException e) {
                LOG.warn("Audit file {} is not writable ({}); the trail is in the logger only", this.file,
                        e.getMessage());
            }
        }
    }

    /** Records a call for the default tenant (keeps older call sites source-compatible). */
    public void record(String key, String role, String method, String path, String graph, String query,
                       int status, long millis) {
        record("default", key, role, method, path, graph, query, status, millis);
    }

    /** Records a call (logger, file and the recent view). */
    public void record(String tenant, String key, String role, String method, String path, String graph,
                       String query, int status, long millis) {
        String clipped = clip(query);
        Entry entry = new Entry(Instant.now(), key, role, method, path, graph, clipped, status, millis,
                tenant == null || tenant.isBlank() ? "default" : tenant);
        synchronized (recent) {
            if (recent.size() >= CAPACITY) {
                recent.removeFirst();
            }
            recent.addLast(entry);
        }
        AUDIT.info("key={} role={} {} {} graph={} status={} in {}ms query={}", key, role, method, path,
                graph == null ? "-" : graph, status, millis, clipped);
        append(entry);
    }

    /** The most recent entries, newest first. */
    public List<Entry> recent(int limit) {
        synchronized (recent) {
            List<Entry> all = new ArrayList<>(recent);
            int size = Math.max(0, Math.min(limit, all.size()));
            List<Entry> newestFirst = new ArrayList<>(size);
            for (int i = all.size() - 1; i >= all.size() - size; i -= 1) {
                newestFirst.add(all.get(i));
            }
            return List.copyOf(newestFirst);
        }
    }

    /** How many entries the buffer currently holds. */
    public int size() {
        synchronized (recent) {
            return recent.size();
        }
    }

    /** The audit view: newest first, with a count and what "recent" means. */
    public Map<String, Object> view(int limit) {
        List<Entry> entries = recent(limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", entries.stream().map(AuditLog::viewOf).toList());
        out.put("returned", entries.size());
        out.put("buffered", size());
        out.put("capacity", CAPACITY);
        out.put("durable", file != null && writer != null);
        out.put("file", file == null ? "-" : file.toString());
        out.put("note", "the durable record is the '" + AUDIT_LOGGER + "' logger"
                + (file == null ? "" : " and " + file) + "; this buffer is a view of it");
        return out;
    }

    @PreDestroy
    public void close() {
        synchronized (this) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    LOG.warn("Cannot close the audit file {}", file, e);
                }
                writer = null;
            }
        }
    }

    private static Map<String, Object> viewOf(Entry entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", entry.at().toString());
        out.put("key", entry.key());
        out.put("tenant", entry.tenant());
        out.put("role", entry.role());
        out.put("method", entry.method());
        out.put("path", entry.path());
        out.put("graph", entry.graph() == null ? "-" : entry.graph());
        out.put("query", entry.query());
        out.put("status", entry.status());
        out.put("millis", entry.millis());
        return out;
    }

    private synchronized void append(Entry entry) {
        if (writer == null) {
            return;
        }
        try {
            String line = mapper.writeValueAsString(viewOf(entry)) + "\n";
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            if (maxBytes > 0 && bytesWritten + bytes.length > maxBytes) {
                rotate();
            }
            writer.write(line);
            writer.flush();
            bytesWritten += bytes.length;
        } catch (IOException e) {
            LOG.warn("Cannot append to the audit file {}; the logger still has the entry", file, e);
        }
    }

    private void open() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        bytesWritten = Files.exists(file) ? Files.size(file) : 0;
    }

    private void rotate() throws IOException {
        writer.close();
        writer = null;
        // keep .1 ... .keepFiles, oldest last
        for (int i = keepFiles; i >= 1; i -= 1) {
            Path source = Path.of(file + "." + i);
            if (!Files.exists(source)) {
                continue;
            }
            if (i == keepFiles) {
                Files.delete(source);
            } else {
                Files.move(source, Path.of(file + "." + (i + 1)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, Path.of(file + ".1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        open();
    }

    private void loadRecent() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Entry entry = parse(line);
                if (entry == null) {
                    continue;
                }
                synchronized (recent) {
                    if (recent.size() >= CAPACITY) {
                        recent.removeFirst();
                    }
                    recent.addLast(entry);
                }
            }
        }
    }

    private Entry parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(line);
            String graph = node.path("graph").asText("-");
            return new Entry(
                    Instant.parse(node.path("at").asText()),
                    node.path("key").asText("-"),
                    node.path("role").asText("-"),
                    node.path("method").asText("-"),
                    node.path("path").asText("-"),
                    "-".equals(graph) ? null : graph,
                    node.path("query").asText("-"),
                    node.path("status").asInt(),
                    node.path("millis").asLong(),
                    node.path("tenant").asText("default"));
        } catch (RuntimeException | IOException e) {
            LOG.warn("Skipping a malformed audit line: {}", e.getMessage());
            return null;
        }
    }

    private static String clip(String query) {
        if (query == null || query.isBlank()) return "-";
        return query.length() <= QUERY_CLIP ? query : query.substring(0, QUERY_CLIP) + "…";
    }
}

package com.pengcheng.phoenixgraph.gemini;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A worker as a program: {@code java -cp <jar> GeminiWorkerMain --worker 0 --port 19100 --workers 4
 * [--load layout.json]}.
 *
 * <p>This is the standalone path of the design: a container (YARN, or a helper script starting four
 * of them on a laptop) starts the process, the launcher posts the layout over HTTP, and the worker
 * answers on its own port from then on. {@code --load} is for a machine that has the layout in a
 * file and wants the worker to be ready before anyone talks to it.
 */
public final class GeminiWorkerMain {

    private GeminiWorkerMain() {
    }

    /** Starts a worker and waits; a shutdown hook closes the port. */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        int port = Integer.parseInt(options.getOrDefault("port",
                String.valueOf(19100 + Integer.parseInt(options.getOrDefault("worker", "0")))));
        int workerId = Integer.parseInt(options.getOrDefault("worker", "0"));
        GeminiWorkerServer server = new GeminiWorkerServer(port);
        if (options.containsKey("load")) {
            Path file = Path.of(options.get("load"));
            ObjectNode document = (ObjectNode) GeminiWire.mapper().readTree(Files.readString(file));
            server.load(document);
        }
        server.start();
        System.out.println("[gemini] worker " + workerId + " listening on http://127.0.0.1:" + port
                + (options.containsKey("load") ? " (loaded)" : " (waiting for a layout)"));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }

    /** {@code --name value} pairs, the way a container would pass them. */
    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String name = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    options.put(name, args[++i]);
                } else {
                    options.put(name, "true");
                }
            }
        }
        return options;
    }
}

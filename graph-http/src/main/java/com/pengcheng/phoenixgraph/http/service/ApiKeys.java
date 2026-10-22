package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.http.config.SecurityProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The keys that may call, and the two questions a request has to answer: *who is this* and *may they do
 * this*.
 *
 * <p>Deliberately not Spring Security. What this service needs is three roles, a per-graph allowlist and an
 * audit line - not a filter chain with CSRF, form login, session fixation and a servlet-container integration
 * to configure around. A hundred lines that a reader can check against the documented rules is a better
 * security story at this size than a framework whose defaults have to be reasoned about endpoint by
 * endpoint. (If roles, users and an identity provider ever matter, this class is the seam to replace.)
 *
 * <p>Rules, in one place:
 * <ul>
 *   <li>the role is a ladder: {@code reader} &lt; {@code writer} &lt; {@code admin};</li>
 *   <li>reads (GET/HEAD) need {@code reader}, other methods need {@code writer};</li>
 *   <li>schema authoring, ingestion, index rebuilds, metric resets and the audit view need {@code admin} -
 *       they change what the service *is*, not just what it holds;</li>
 *   <li>a key with a non-empty graph list may only name those graphs.</li>
 * </ul>
 *
 * <p>A token never reaches the log: what is logged (and returned by the audit view) is the key's name.
 */
@Component
public class ApiKeys {

    /** The authenticated caller, or the anonymous one when security is off. */
    public record Principal(String name, String role, List<String> graphs, String tenant) {

        /** The three-argument form keeps the old call sites source-compatible. */
        public Principal(String name, String role, List<String> graphs) {
            this(name, role, graphs, "default");
        }

        public boolean isAnonymous() {
            return "anonymous".equals(name);
        }

        boolean atLeast(String needed) {
            return rank(role) >= rank(needed);
        }

        private static int rank(String role) {
            return switch (role == null ? "" : role.toLowerCase(Locale.ROOT)) {
                case "admin" -> 3;
                case "writer" -> 2;
                case "reader" -> 1;
                default -> 0;
            };
        }
    }

    /** Thrown when the caller is not identified: the filter answers 401. */
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }

    /** Thrown when the caller is identified but not allowed: the filter answers 403. */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    private static final Principal ANONYMOUS = new Principal("anonymous", "admin", List.of(), "default");

    private final SecurityProperties properties;

    public ApiKeys(SecurityProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** How many keys are configured (the startup log says so, without printing any of them). */
    public int keyCount() {
        return properties.keys().size();
    }

    /** The caller for a token, or the anonymous principal when security is off. */
    public Principal authenticate(String token) {
        if (!properties.enabled()) {
            return ANONYMOUS;
        }
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("This instance requires an API key: send 'Authorization: Bearer <key>'");
        }
        for (SecurityProperties.Key key : properties.keys()) {
            if (matches(key, token)) {
                return new Principal(key.name(), key.role(), key.graphs(), key.tenant());
            }
        }
        throw new UnauthorizedException("Unknown API key");
    }

    /**
     * Refuses the request when the caller may not do this.
     *
     * @param permission what the endpoint needs ({@code reader}, {@code writer} or {@code admin})
     * @param graph      the graph the request names, or null
     */
    public void require(Principal principal, String permission, String graph) {
        if (!properties.enabled() && principal.isAnonymous()) {
            return; // open instance: the operator has accepted that (README, Limitations)
        }
        if (!principal.atLeast(permission)) {
            throw new ForbiddenException("This key is a '" + principal.role() + "' key and the request needs '"
                    + permission + "'");
        }
        if (graph != null && !principal.graphs().isEmpty() && !principal.graphs().contains(graph)) {
            throw new ForbiddenException("This key may only use the graphs " + principal.graphs()
                    + "; the request names '" + graph + "'");
        }
    }

    /**
     * What an endpoint needs, from its method and path. Public so the filter and the tests agree.
     *
     * <p>The method is not the whole story: a query is a {@code POST} because it carries a body, and a reader
     * role that could not query would be useless - so the paths that *read* while using POST are listed
     * explicitly. Getting this list wrong in the other direction would let a reader write, which is why it is
     * a list of exact prefixes and not a rule about "looks like a read".
     */
    public static String permissionFor(String method, String uri) {
        String upper = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        String path = uri == null ? "" : uri;
        // the audit view is an admin endpoint *even though it is a GET*: it lists what everybody asked for,
        // so the method-based shortcut below must not answer for it first
        if (path.startsWith("/api/v1/audit")) {
            return "admin";
        }
        boolean read = upper.equals("GET") || upper.equals("HEAD") || upper.equals("OPTIONS")
                || isReadOverPost(path);
        if (read) {
            return "reader";
        }
        if (path.startsWith("/api/v1/schema") || path.contains("/vector-index") || path.contains("/ingest")
                || path.endsWith("/reset")) {
            return "admin";
        }
        return "writer";
    }

    /** Endpoints that read even though they use POST: a query, a pattern preview, a plan, the agent tools. */
    private static boolean isReadOverPost(String path) {
        if (path.equals("/api/v1/query") || path.startsWith("/api/v1/fuzzy/validate")) {
            return true;
        }
        if (path.startsWith("/api/v1/agent/")) {
            // every agent tool is a read; ingest is not (it is matched by the admin rule before this)
            return !path.contains("/ingest");
        }
        return path.endsWith("/plan") && path.startsWith("/api/v1/fuzzy/");
    }

    /**
     * Constant-time comparison of the presented token with the configured one.
     *
     * <p>Two forms, and the difference matters: a configured {@code token} is compared as text, while a
     * configured {@code sha256} is compared with the digest of the presented token - hashing the configured
     * digest again (the first version of this method) means no digest ever matches, and the failure looks
     * exactly like a wrong token.
     */
    private static boolean matches(SecurityProperties.Key key, String token) {
        if (key.token() != null && !key.token().isBlank()) {
            return MessageDigest.isEqual(key.token().getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8));
        }
        return MessageDigest.isEqual(key.sha256().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                sha256(token).getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 in lower-case hex; a configured digest may be given in any case. */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}

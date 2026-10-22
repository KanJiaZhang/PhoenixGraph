package com.pengcheng.phoenixgraph.http.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Who may call this instance, and what they may do.
 *
 * <p>Off by default so the demo, the verification suite and a local `docker compose up` keep working without
 * ceremony - but a deployment that is reachable by anything other than its own operator must turn it on, and
 * the README says so in the Limitations section. With {@code enabled: true} a request without a valid key is
 * a 401 before any work happens.
 *
 * @param enabled whether a key is required (true = every {@code /api} call must carry one)
 * @param keys    the keys that may call, each with a role and optionally a list of graphs
 */
@ConfigurationProperties(prefix = "phoenixgraph.security")
public record SecurityProperties(@DefaultValue("false") boolean enabled, List<Key> keys) {

    public SecurityProperties {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    /**
     * One caller.
     *
     * @param name   what the audit log calls them
     * @param token  the bearer token in clear (fine for a dev instance, and what {@code PG_..._TOKEN} injects)
     * @param sha256 the token's SHA-256 in hex, for the case where the configuration file itself is readable
     *               by more people than the token should be
     * @param role   {@code reader} (reads and queries), {@code writer} (also writes), {@code admin} (also
     *               schema authoring, ingestion, index rebuilds and the audit view)
     * @param graphs the graphs this key may name; empty means every graph
     * @param tenant the tenant this key belongs to (the audit trail and the optional per-tenant quotas)
     */
    public record Key(String name, String token, String sha256,
                      @DefaultValue("reader") String role, List<String> graphs,
                      @DefaultValue("default") String tenant) {

        public Key {
            graphs = graphs == null ? List.of() : List.copyOf(graphs);
            tenant = tenant == null || tenant.isBlank() ? "default" : tenant;
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a phoenixgraph.security.keys entry needs a name");
            }
            if ((token == null || token.isBlank()) && (sha256 == null || sha256.isBlank())) {
                throw new IllegalArgumentException("the key '" + name + "' needs a token or a sha256");
            }
            if (role == null || !List.of("reader", "writer", "admin").contains(role)) {
                throw new IllegalArgumentException("the key '" + name + "' has role '" + role
                        + "'; use reader, writer or admin");
            }
        }
    }
}

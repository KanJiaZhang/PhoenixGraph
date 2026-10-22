package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.http.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of "who may do what", without a servlet: the role ladder, the graph allowlist, the permission a
 * method and path imply, and the two ways a token can be configured (clear or hashed).
 */
class ApiKeysTest {

    private static SecurityProperties properties(SecurityProperties.Key... keys) {
        return new SecurityProperties(true, List.of(keys));
    }

    private static SecurityProperties.Key key(String name, String role, String... graphs) {
        return new SecurityProperties.Key(name, name + "-token", null, role, List.of(graphs), "default");
    }

    @Test
    void anOpenInstanceAdmitsEveryoneAsAnonymous() {
        ApiKeys open = new ApiKeys(new SecurityProperties(false, List.of()));
        ApiKeys.Principal principal = open.authenticate(null);
        assertTrue(principal.isAnonymous());
        open.require(principal, "admin", "anything"); // no exception: the operator accepted this
        assertFalse(open.enabled());
    }

    @Test
    void aMissingOrUnknownTokenIsUnauthorized() {
        ApiKeys keys = new ApiKeys(properties(key("dash", "reader")));
        ApiKeys.UnauthorizedException missing = assertThrows(ApiKeys.UnauthorizedException.class,
                () -> keys.authenticate(null));
        assertTrue(missing.getMessage().contains("Authorization: Bearer"), missing.getMessage());
        assertThrows(ApiKeys.UnauthorizedException.class, () -> keys.authenticate("guess"));
    }

    @Test
    void aConfiguredTokenIdentifiesItsKey() {
        ApiKeys keys = new ApiKeys(properties(key("dash", "writer", "payment-graph")));
        ApiKeys.Principal principal = keys.authenticate("dash-token");
        assertEquals("dash", principal.name());
        assertEquals("writer", principal.role());
        assertEquals(List.of("payment-graph"), principal.graphs());
    }

    @Test
    void aTokenCanBeConfiguredAsItsSha256InsteadOfInClear() {
        String token = "s3cret-token";
        SecurityProperties.Key hashed = new SecurityProperties.Key("ops", null, ApiKeys.sha256(token), "admin", List.of(), "default");
        ApiKeys keys = new ApiKeys(properties(hashed));
        // the digest is lower-case hex of the UTF-8 bytes; a config may give it in any case
        assertEquals("ops", keys.authenticate(token).name());
        SecurityProperties.Key upper = new SecurityProperties.Key("ops2", null, ApiKeys.sha256(token).toUpperCase(), "admin", List.of(), "default");
        assertEquals("ops2", new ApiKeys(properties(upper)).authenticate(token).name());
        assertThrows(ApiKeys.UnauthorizedException.class, () -> keys.authenticate("s3cret-toke"));
    }

    @Test
    void aKeyWithoutATokenOrDigestIsAConfigurationError() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new SecurityProperties.Key("broken", " ", null, "reader", List.of(), "default"));
        assertTrue(failure.getMessage().contains("token or a sha256"), failure.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityProperties.Key("broken", "t", null, "root", List.of(), "default"));
    }

    @Test
    void theRoleLadderIsEnforced() {
        ApiKeys keys = new ApiKeys(properties(key("reader", "reader")));
        ApiKeys.Principal principal = keys.authenticate("reader-token");

        keys.require(principal, "reader", "g");
        ApiKeys.ForbiddenException forbidden = assertThrows(ApiKeys.ForbiddenException.class,
                () -> keys.require(principal, "writer", "g"));
        assertTrue(forbidden.getMessage().contains("'reader'"), forbidden.getMessage());
        assertThrows(ApiKeys.ForbiddenException.class, () -> keys.require(principal, "admin", "g"));
    }

    @Test
    void aGraphAllowlistIsEnforcedButAnEmptyOneMeansEveryGraph() {
        ApiKeys keys = new ApiKeys(properties(key("scoped", "writer", "payment-graph"), key("global", "writer")));
        ApiKeys.Principal scoped = keys.authenticate("scoped-token");
        keys.require(scoped, "writer", "payment-graph");
        keys.require(scoped, "reader", null); // a request that names no graph is not graph-scoped
        ApiKeys.ForbiddenException forbidden = assertThrows(ApiKeys.ForbiddenException.class,
                () -> keys.require(scoped, "writer", "tpc-h-data"));
        assertTrue(forbidden.getMessage().contains("tpc-h-data"), forbidden.getMessage());
        keys.require(keys.authenticate("global-token"), "writer", "any-graph");
    }

    @Test
    void thePermissionOfAnEndpointFollowsItsMethodAndPath() {
        assertEquals("reader", ApiKeys.permissionFor("GET", "/api/v1/graphs"));
        assertEquals("reader", ApiKeys.permissionFor("HEAD", "/api/v1/metrics/payment-graph"));
        // a query is a POST but a read; a vertex upsert is a write (see the dedicated test below)
        assertEquals("reader", ApiKeys.permissionFor("POST", "/api/v1/query"));
        assertEquals("writer", ApiKeys.permissionFor("POST", "/api/v1/graphs/payment-graph/vertices"));
        assertEquals("writer", ApiKeys.permissionFor("DELETE", "/api/v1/vertices/1000"));
        assertEquals("admin", ApiKeys.permissionFor("POST", "/api/v1/schema"));
        assertEquals("admin", ApiKeys.permissionFor("POST", "/api/v1/agent/ingest"));
        assertEquals("admin", ApiKeys.permissionFor("POST", "/api/v1/vector-index/rebuild"));
        assertEquals("admin", ApiKeys.permissionFor("POST", "/api/v1/metrics/payment-graph/reset"));
        // the audit trail lists what everybody asked for: reading it is an admin act
        assertEquals("admin", ApiKeys.permissionFor("GET", "/api/v1/audit/recent"));
    }

    @Test
    void aQueryIsAReadEvenThoughItUsesPost() {
        // a reader role that could not query would be useless: a query carries a body, so it is a POST
        assertEquals("reader", ApiKeys.permissionFor("POST", "/api/v1/query"));
        assertEquals("reader", ApiKeys.permissionFor("POST", "/api/v1/fuzzy/validate"));
        assertEquals("reader", ApiKeys.permissionFor("POST", "/api/v1/fuzzy/graphs/payment-graph/plan"));
        assertEquals("reader", ApiKeys.permissionFor("POST", "/api/v1/agent/context"));
        assertEquals("reader", ApiKeys.permissionFor("POST", "/api/v1/agent/path"));
        // and the ones that write through POST still need more
        assertEquals("admin", ApiKeys.permissionFor("POST", "/api/v1/agent/ingest"));
        assertEquals("writer", ApiKeys.permissionFor("POST", "/api/v1/fuzzy/graphs/payment-graph/links"));
        assertEquals("writer", ApiKeys.permissionFor("POST", "/api/v1/vertices"));
    }

    @Test
    void theDigestIsTheStandardSha256Hex() {
        // the well-known vector for "abc", so a deployment can compute the value with sha256sum and compare
        // (echo -n abc | sha256sum)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApiKeys.sha256("abc"));
    }

    @Test
    void aKeyCarriesItsTenant() {
        SecurityProperties keys = properties(new SecurityProperties.Key("ops", "ops-token", null, "admin",
                List.of(), "acme"));
        assertEquals("acme", new ApiKeys(keys).authenticate("ops-token").tenant());
    }
}

package com.pengcheng.phoenixgraph.http.service;

import com.pengcheng.phoenixgraph.http.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Says at startup which mode the instance is in. An open instance is a deliberate choice for a local demo,
 * but it must be visible in the log; a secured instance with no keys is fail-closed, and the operator should
 * know that before the first request gets a 401.
 */
@Component
public class SecurityStartupCheck implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityStartupCheck.class);

    private final SecurityProperties properties;

    public SecurityStartupCheck(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            LOG.warn("PhoenixGraph security is OFF: every /api call is open. Use SPRING_PROFILES_ACTIVE=secure "
                    + "or phoenixgraph.security.enabled=true before exposing this port.");
        } else if (properties.keys().isEmpty()) {
            LOG.error("PhoenixGraph security is ON but no keys are configured: every /api call will be 401. "
                    + "Add phoenixgraph.security.keys (docs/API.md §2).");
        } else {
            LOG.info("PhoenixGraph security is ON with {} key(s)", properties.keys().size());
        }
    }
}

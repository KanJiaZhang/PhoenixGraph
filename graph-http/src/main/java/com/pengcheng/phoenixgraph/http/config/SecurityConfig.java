package com.pengcheng.phoenixgraph.http.config;

import com.pengcheng.phoenixgraph.http.filter.ApiKeyFilter;
import com.pengcheng.phoenixgraph.http.service.ApiKeys;
import com.pengcheng.phoenixgraph.http.service.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts the API key filter in front of everything, including the request gate.
 *
 * <p>Order 0 for a reason: a caller who is not allowed to make a request should not first spend one of the
 * instance's request slots on being refused. The two filters are otherwise independent - one answers "who
 * are you and may you", the other "how much of this instance may you use".
 *
 * <p>Registered as a {@code FilterRegistrationBean} (not {@code @Component}) so the {@code @WebMvcTest}
 * slices keep testing controllers: a slice has no limits or key beans, and a filter that needs them would
 * fail the context in every one of them.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public ApiKeys apiKeys(SecurityProperties properties) {
        ApiKeys keys = new ApiKeys(properties);
        if (keys.enabled()) {
            LOGGER.info("API keys are required ({} configured): every /api call needs 'Authorization: Bearer <key>'",
                    keys.keyCount());
        } else {
            LOGGER.warn("API keys are DISABLED: every endpoint is open to anyone who can reach this port "
                    + "(set phoenixgraph.security.enabled=true before exposing it)");
        }
        return keys;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeys keys, AuditLog audit,
                                                            LimitsProperties limits) {
        // the body limit is passed in because this filter runs first: it is the one that reads a POST body to
        // learn which graph the request names, so it is the one that can answer 413 for an oversized one
        FilterRegistrationBean<ApiKeyFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyFilter(keys, audit, limits.maxBodyBytes()));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        registration.setName("apiKeyFilter");
        return registration;
    }
}

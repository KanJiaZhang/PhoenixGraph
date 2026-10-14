package com.pengcheng.phoenixgraph.http.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.pengcheng.phoenixgraph.http.service.WriteValidation;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The write-path policy and the JSON-strictness policy, both read from configuration.
 *
 * <p><b>Why the JSON strictness is here.</b> Jackson's default is to coerce a scalar of one type into
 * another: {@code "graphName": 123} becomes the string {@code "123"} and the query runs against a graph named
 * {@code 123}; {@code "index": "1"} becomes the number 1. That leniency exists for Java beans that are hard to
 * change, not for a JSON API with a published schema: it turns a client's type error into a request the server
 * tries to serve, and the error - if any - arrives from somewhere else entirely. Refusing the coercion makes
 * the same mistake a 400 that names the field. Dynamic fields stay dynamic: {@code id}, {@code source} and
 * {@code destination} are {@code Object}, so a number or a string is accepted as whatever the client sent,
 * which is exactly what an id has to be.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RequestPolicyConfig.WriteProperties.class)
public class RequestPolicyConfig {

    /**
     * The write policy (see {@link WriteValidation}): edges must point at vertices that exist, and a single
     * property value has a size ceiling. Both defaults are the strict ones; a deployment that needs otherwise
     * (an importer that writes edges first, an oversized document property) says so explicitly.
     */
    @Bean
    public WriteValidation writeValidation(WriteProperties properties) {
        return new WriteValidation(properties.requireExistingEndpoints(), properties.maxPropertyValueBytes());
    }

    /** No silent scalar type conversion: a wrong JSON type is a rejected request, not a guess. */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictJsonTypes() {
        return builder -> builder.postConfigurer(mapper -> {
            // coercion is configured per target type; the defaults apply to every property
            var coercion = mapper.coercionConfigDefaults();
            coercion.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
            coercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            coercion.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        });
    }

    /**
     * The write-side knobs.
     *
     * @param requireExistingEndpoints refuse an edge whose endpoints are not in the graph (default true)
     * @param maxPropertyValueBytes    largest single property value accepted (default 512 KiB)
     */
    @ConfigurationProperties(prefix = "phoenixgraph.write")
    public static record WriteProperties(
            @DefaultValue("true") boolean requireExistingEndpoints,
            @DefaultValue("524288") int maxPropertyValueBytes) {

        public WriteProperties {
            if (maxPropertyValueBytes < 1) {
                throw new IllegalArgumentException("phoenixgraph.write.max-property-value-bytes must be >= 1");
            }
        }
    }
}

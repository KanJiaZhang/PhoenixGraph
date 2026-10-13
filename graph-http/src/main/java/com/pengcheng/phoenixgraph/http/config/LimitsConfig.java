package com.pengcheng.phoenixgraph.http.config;

import com.pengcheng.phoenixgraph.http.filter.RequestLimitsFilter;
import com.pengcheng.phoenixgraph.http.service.RequestLimits;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the request gate on the API paths only.
 *
 * <p>A {@code FilterRegistrationBean} rather than {@code @Component} on the filter itself, for two reasons.
 * The registration is explicit about *which* paths are gated - the static UI is never rate limited, because a
 * user refreshing the page is not load - and the filter stays out of the `@WebMvcTest` slices, which test
 * controllers and would otherwise need the limits bean mocked in every one of them.
 */
@Configuration
public class LimitsConfig {

    @Bean
    public FilterRegistrationBean<RequestLimitsFilter> requestLimitsFilter(RequestLimits limits) {
        FilterRegistrationBean<RequestLimitsFilter> registration = new FilterRegistrationBean<>(new RequestLimitsFilter(limits));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1); // before anything that could do work on the request
        registration.setName("requestLimitsFilter");
        return registration;
    }
}

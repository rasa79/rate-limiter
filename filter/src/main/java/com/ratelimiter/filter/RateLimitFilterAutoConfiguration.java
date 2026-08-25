package com.ratelimiter.filter;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration that registers the {@link RateLimitFilter} once an
 * application configures {@code ratelimiter.filter.service-url}.
 *
 * <p>Only activates when the service URL is set, so a client app opts in
 * explicitly. It wires a real {@link CheckApiClient}, a Resilience4j
 * {@link CircuitBreaker}, and the filter bean (which Spring Boot registers into
 * the servlet filter chain). Consumers can override any of these beans.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ratelimiter.filter", name = "service-url")
@EnableConfigurationProperties(FilterProperties.class)
public class RateLimitFilterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RateLimitChecker.class)
    public RateLimitChecker rateLimitChecker(FilterProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMillis()))
                .build());
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMillis()));
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getServiceUrl())
                .requestFactory(factory)
                .build();
        return new CheckApiClient(restClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreaker rateLimitCircuitBreaker(FilterProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.getCircuitBreakerFailureRateThreshold())
                .slidingWindowSize(properties.getCircuitBreakerSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofMillis(properties.getCircuitBreakerWaitDurationMillis()))
                .build();
        return CircuitBreaker.of("rateLimiter", config);
    }

    @Bean
    @ConditionalOnMissingBean(RateLimitFilter.class)
    public RateLimitFilter rateLimitFilter(RateLimitChecker checker, FilterProperties properties,
            CircuitBreaker circuitBreaker) {
        return new RateLimitFilter(checker, properties, circuitBreaker);
    }
}

package com.ratelimiter.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link RateLimitFilter}. The {@link RateLimitChecker} is a stub
 * (only the network client is mocked — never the service's store).
 */
class RateLimitFilterTest {

    private FilterProperties props;

    @BeforeEach
    void setUp() {
        props = new FilterProperties();
        props.setKeyHeader("X-Api-Key");
        props.setRuleHeader("X-Rate-Limit-Rule");
        props.setDefaultRule("default");
    }

    private RateLimitFilter filter(RateLimitChecker checker, FailMode mode, CircuitBreaker breaker) {
        props.setFailMode(mode);
        return new RateLimitFilter(checker, props, breaker);
    }

    private static final class Result {
        final AtomicBoolean chained = new AtomicBoolean(false);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        Result(RateLimitFilter filter, String key, String rule) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
            if (key != null) {
                request.addHeader("X-Api-Key", key);
            }
            if (rule != null) {
                request.addHeader("X-Rate-Limit-Rule", rule);
            }
            filter.doFilter(request, response, (r, s) -> chained.set(true));
        }
    }

    @Test
    void passesThroughWhenAllowed() throws Exception {
        Result r = new Result(filter((k, rule) -> new CheckResult(true, 0), FailMode.OPEN, breaker()), "k1", "free");
        assertThat(r.chained).isTrue();
        assertThat(r.response.getStatus()).isEqualTo(200);
    }

    @Test
    void shortCircuits429WithRetryAfterHeader() throws Exception {
        Result r = new Result(filter((k, rule) -> new CheckResult(false, 3), FailMode.OPEN, breaker()), "k1", "free");
        assertThat(r.chained).isFalse();
        assertThat(r.response.getStatus()).isEqualTo(429);
        assertThat(r.response.getHeader("Retry-After")).isEqualTo("3");
    }

    @Test
    void failOpenOnLimiterTimeout() throws Exception {
        Result r = new Result(filter((k, rule) -> {
            throw new RuntimeException("timeout");
        }, FailMode.OPEN, breaker()), "k1", "free");
        assertThat(r.chained).isTrue();
        assertThat(r.response.getStatus()).isNotEqualTo(429);
    }

    @Test
    void failClosedWhenConfigured() throws Exception {
        Result r = new Result(filter((k, rule) -> {
            throw new RuntimeException("timeout");
        }, FailMode.CLOSED, breaker()), "k1", "free");
        assertThat(r.chained).isFalse();
        assertThat(r.response.getStatus()).isEqualTo(429);
    }

    @Test
    void circuitBreakerOpensAfterRepeatedFailures() throws Exception {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(1)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .build();
        CircuitBreaker breaker = CircuitBreaker.of("test", config);
        RateLimitFilter filter = filter((k, rule) -> {
            throw new RuntimeException("boom");
        }, FailMode.OPEN, breaker);

        for (int i = 0; i < 3; i++) {
            new Result(filter, "k1", "free");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Once open, the breaker short-circuits: the filter fails open (OPEN mode).
        Result r = new Result(filter, "k1", "free");
        assertThat(r.chained).isTrue();
    }

    private CircuitBreaker breaker() {
        return CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
    }
}

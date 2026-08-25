package com.ratelimiter.filter;

/**
 * The abstraction the {@link RateLimitFilter} uses to ask the rate-limiter
 * service for a decision. Implementations hit the {@code POST /v1/check} API
 * ({@link CheckApiClient}); tests substitute a stub (only the network client is
 * mocked — never the service's store).
 */
public interface RateLimitChecker {

    /**
     * Asks the rate limiter whether a request should be allowed.
     *
     * @param key the rate-limit key (e.g. an API key)
     * @param rule the rule name to enforce
     * @return the decision
     * @throws RuntimeException if the limiter cannot be reached
     */
    CheckResult check(String key, String rule);
}

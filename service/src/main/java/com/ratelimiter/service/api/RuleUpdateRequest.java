package com.ratelimiter.service.api;

import com.ratelimiter.service.rules.Algorithm;

/**
 * The body of a {@code PUT /v1/rules/{name}} request (the rule name comes from
 * the path).
 *
 * @param algorithm the algorithm to enforce
 * @param limit the capacity (token bucket) or max events per window (sliding window)
 * @param refillPerSecond the refill rate (token bucket only)
 * @param windowMillis the rolling window (sliding window only)
 */
public record RuleUpdateRequest(Algorithm algorithm, double limit, double refillPerSecond,
        long windowMillis) {
}

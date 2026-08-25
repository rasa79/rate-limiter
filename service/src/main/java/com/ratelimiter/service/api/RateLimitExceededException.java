package com.ratelimiter.service.api;

import com.ratelimiter.service.algorithm.Decision;

/**
 * Thrown when a {@code /v1/check} request is rejected. Carries the decision so
 * the exception handler can emit the contract-shaped body and the
 * {@code Retry-After} header.
 */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Decision decision;

    /**
     * @param decision the rejected decision
     */
    public RateLimitExceededException(Decision decision) {
        super("rate limit exceeded");
        this.decision = decision;
    }

    /**
     * @return the rejected decision (allowed is always {@code false})
     */
    public Decision decision() {
        return decision;
    }
}

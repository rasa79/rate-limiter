package com.ratelimiter.filter;

/**
 * What the filter does when the rate limiter is unavailable (timeout, breaker
 * open, or an unexpected response).
 *
 * <p>{@link #OPEN} lets the request through (availability bias, the common case);
 * {@link #CLOSED} rejects it (fail-safe for money paths). ADR-0001 makes this
 * per-filter configurable.
 */
public enum FailMode {
    /** Fail open: allow the request when the limiter cannot be consulted. */
    OPEN,
    /** Fail closed: reject the request when the limiter cannot be consulted. */
    CLOSED
}

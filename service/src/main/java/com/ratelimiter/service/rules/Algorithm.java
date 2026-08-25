package com.ratelimiter.service.rules;

/**
 * The rate-limit algorithms the service supports.
 *
 * <p>{@link #TOKEN_BUCKET} allows a burst of up to {@code capacity} units and
 * refills continuously; {@link #SLIDING_WINDOW} allows at most {@code limit}
 * events in any rolling window of {@code windowMillis}. They are selected per
 * rule, and the HTTP contract ({@code allowed, remaining, reset_at,
 * retry_after_seconds}) is algorithm-independent.
 */
public enum Algorithm {
    /** Continuous refill, burst capacity. */
    TOKEN_BUCKET,
    /** Rolling log-based window (sorted set). */
    SLIDING_WINDOW
}

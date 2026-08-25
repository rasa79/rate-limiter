package com.ratelimiter.service.algorithm;

/**
 * The outcome of a single rate-limit decision, independent of any particular
 * algorithm (token bucket, sliding window).
 *
 * <p>It is produced by algorithm implementations that receive an injected time
 * ({@code nowMillis}); nothing in the {@code algorithm} package reads the wall
 * clock. This keeps decisions deterministic and lets the HTTP layer and the
 * Lua scripts share exactly the same contract.
 *
 * @param allowed whether the request was permitted
 * @param remaining the number of units (tokens) still available after this
 *                  decision, in the range {@code [0, capacity]}
 * @param resetAtMillis epoch-millis at which the counter/token bucket will next
 *                      be full again (clients use this to compute "quota
 *                      resets at"); {@code Long.MAX_VALUE} means "never"
 * @param retryAfterSeconds how long, in whole seconds rounded up, the client
 *                          should wait before the requested units become
 *                          available; {@code 0} when {@link #allowed()} is
 *                          {@code true}
 */
public record Decision(boolean allowed, double remaining, long resetAtMillis, long retryAfterSeconds) {

    /**
     * Returns a "no limit" decision used when a rule is configured to allow
     * everything: consume nothing, nothing to retry.
     *
     * @return an always-allowed decision
     */
    public static Decision unlimited() {
        return new Decision(true, Double.MAX_VALUE, Long.MAX_VALUE, 0);
    }
}

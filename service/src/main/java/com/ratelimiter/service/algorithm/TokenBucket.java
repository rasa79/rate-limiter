package com.ratelimiter.service.algorithm;

/**
 * A token-bucket rate-limit algorithm that is fully time-injected: every method
 * receives {@code nowMillis} explicitly and no code path here reads the wall
 * clock.
 *
 * <p>This class is <em>stateless as a decision engine</em>: the mutable bucket
 * state (current token balance and the timestamp of the last refill) lives in an
 * immutable {@link State} record that the caller owns and passes back in. This
 * makes the math trivially unit-testable, deterministic, and directly comparable
 * to the Lua script (M4) that implements the identical logic inside Valkey. The
 * HTTP/backend layers hold the {@code State} in their store.
 *
 * <p>Model: the bucket holds up to {@code capacity} tokens and refills
 * continuously at {@code refillTokensPerSecond}. A request asks for
 * {@code requestedTokens} and is allowed only if at least that many tokens are
 * present after refilling up to {@code nowMillis}. On success the requested
 * tokens are removed; on failure nothing is consumed.
 *
 * // RATIONALE: time is injected (never read from a clock) so the algorithm is a
 * pure function of (state, requested, now). Deterministic unit and property
 * tests can then drive arbitrary timelines, and the same code can be mirrored
 * 1:1 in Lua, where "now" is also supplied by the caller. This is what makes the
 * Java/Lua differential test in M5 meaningful.
 */
public final class TokenBucket {

    /** Tolerance used when deciding whether the bucket is "full" up to floating error. */
    private static final double EPSILON = 1e-9;

    private final double capacity;
    private final double refillTokensPerSecond;
    private final double refillTokensPerMilli;

    /**
     * Creates a token bucket configuration.
     *
     * @param capacity the maximum number of tokens the bucket can hold (must be
     *                 strictly positive)
     * @param refillTokensPerSecond the continuous refill rate in tokens per
     *                              second (must be non-negative); {@code 0}
     *                              yields a bucket that never refills
     * @throws IllegalArgumentException if {@code capacity <= 0} or
     *                                  {@code refillTokensPerSecond < 0}
     */
    public TokenBucket(double capacity, double refillTokensPerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, was " + capacity);
        }
        if (refillTokensPerSecond < 0) {
            throw new IllegalArgumentException("refillTokensPerSecond must be >= 0, was " + refillTokensPerSecond);
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.refillTokensPerMilli = refillTokensPerSecond / 1000.0;
    }

    /**
     * The immutable bucket state as held by the caller between decisions.
     *
     * @param tokens the current token balance, guaranteed in {@code [0, capacity]}
     * @param lastRefillMillis epoch-millis of the last refill/observation
     */
    public record State(double tokens, long lastRefillMillis) {

        /**
         * Returns the state of a bucket that starts full at the given instant.
         *
         * @param capacity the capacity of the bucket
         * @param startMillis epoch-millis at which the bucket is full
         * @return a full bucket state
         */
        public static State full(double capacity, long startMillis) {
            return new State(capacity, startMillis);
        }
    }

    /**
     * A decision together with the resulting bucket state, so a caller can feed
     * the returned {@link State} straight into the next call.
     *
     * @param decision the rate-limit decision
     * @param state the bucket state after applying this decision
     */
    public record Result(Decision decision, State state) {
    }

    /**
     * Applies a candidate request against this bucket configuration.
     *
     * @param state the current bucket state
     * @param requestedTokens the number of tokens the request requires (must be
     *                        strictly positive)
     * @param nowMillis the current time, in epoch millis; must not be earlier
     *                  than {@code state.lastRefillMillis()}
     * @return the decision and the post-decision state
     * @throws IllegalArgumentException if {@code requestedTokens <= 0} or if time
     *                                  moves backwards relative to the state
     */
    public Result consume(State state, double requestedTokens, long nowMillis) {
        if (requestedTokens <= 0) {
            throw new IllegalArgumentException("requestedTokens must be > 0, was " + requestedTokens);
        }
        if (nowMillis < state.lastRefillMillis()) {
            throw new IllegalArgumentException(
                    "time moved backwards: nowMillis " + nowMillis + " < lastRefillMillis " + state.lastRefillMillis());
        }

        long elapsedMillis = nowMillis - state.lastRefillMillis();
        double refill = Math.max(0, elapsedMillis) * refillTokensPerMilli;
        // RATIONALE: clamp to [0, capacity] in both directions. The upper clamp
        // enforces "refill never exceeds capacity"; the lower clamp defends
        // against a corrupted state ever producing a negative balance.
        double available = Math.max(0, Math.min(capacity, state.tokens() + refill));

        boolean allowed = available >= requestedTokens;
        double remaining = allowed ? available - requestedTokens : available;

        // RATIONALE: reset_at is "when the bucket is next full", which depends on
        // the balance AFTER this decision (remaining), not the pre-consumption
        // balance. For an allowed request that consumed tokens, using 'available'
        // would understate the time-to-full.
        long resetAtMillis = resetAtMillis(remaining, nowMillis);
        long retryAfterSeconds = allowed ? 0 : retryAfterSeconds(requestedTokens, available);

        State newState = new State(remaining, nowMillis);
        return new Result(new Decision(allowed, remaining, resetAtMillis, retryAfterSeconds), newState);
    }

    /**
     * Epoch-millis at which the bucket will next be full. When the bucket is
     * already (numerically) full this is {@code nowMillis}; when it never refills
     * ({@code rate == 0}) it is {@link Long#MAX_VALUE}.
     */
    private long resetAtMillis(double available, long nowMillis) {
        if (refillTokensPerMilli == 0) {
            return Long.MAX_VALUE;
        }
        double toFull = capacity - available;
        if (toFull <= EPSILON) {
            return nowMillis;
        }
        return nowMillis + (long) Math.ceil(toFull / refillTokensPerMilli);
    }

    /**
     * Whole seconds (rounded up, minimum 1) until {@code requestedTokens}
     * become available, or {@link Long#MAX_VALUE} if the bucket never refills.
     */
    private long retryAfterSeconds(double requestedTokens, double available) {
        if (refillTokensPerMilli == 0) {
            return Long.MAX_VALUE;
        }
        double missing = requestedTokens - available;
        double millis = missing / refillTokensPerMilli;
        long seconds = (long) Math.ceil(millis / 1000.0);
        return Math.max(1, seconds);
    }

    /**
     * @return the maximum token capacity of this bucket configuration
     */
    public double capacity() {
        return capacity;
    }

    /**
     * @return the refill rate in tokens per second
     */
    public double refillTokensPerSecond() {
        return refillTokensPerSecond;
    }
}

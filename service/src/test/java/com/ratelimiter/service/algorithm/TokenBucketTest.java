package com.ratelimiter.service.algorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Deterministic, clock-free unit tests for {@link TokenBucket}.
 *
 * <p>Time is injected as an explicit {@code nowMillis} argument, so every test
 * drives its own timeline and none of them depend on the wall clock. The flag
 * of these tests is proving the pure token-bucket math is correct <em>before</em>
 * any network or store exists (M3 wire it up, M4 moves it into Lua).
 */
class TokenBucketTest {

    private static final double EPS = 1e-9;

    @Test
    void rejectsWhenBucketEmpty() {
        TokenBucket bucket = new TokenBucket(5, 10);
        TokenBucket.State state = TokenBucket.State.full(5, 0);

        // Drain the full bucket exactly at t = 0.
        TokenBucket.Result drained = bucket.consume(state, 5, 0);
        assertThat(drained.decision().allowed()).isTrue();
        assertThat(drained.decision().remaining()).isCloseTo(0.0, within(EPS));

        // Nothing refilled (t is still 0), so any further request is rejected.
        TokenBucket.Result next = bucket.consume(drained.state(), 1, 0);
        assertThat(next.decision().allowed()).isFalse();
        assertThat(next.decision().remaining()).isCloseTo(0.0, within(EPS));
    }

    @Test
    void allowsExactlyAtLimitBoundary() {
        int limit = 5;
        TokenBucket bucket = new TokenBucket(limit, 10);
        TokenBucket.State state = TokenBucket.State.full(limit, 0);

        // The Nth request is the last one allowed: requests 1..limit each take
        // a single token and are permitted; the (limit+1)th is rejected.
        for (int i = 1; i <= limit; i++) {
            TokenBucket.Result result = bucket.consume(state, 1, 0);
            assertThat(result.decision().allowed())
                    .as("request %d of %d should be allowed", i, limit)
                    .isTrue();
            state = result.state();
        }

        TokenBucket.Result overLimit = bucket.consume(state, 1, 0);
        assertThat(overLimit.decision().allowed()).isFalse();
        assertThat(overLimit.decision().remaining()).isCloseTo(0.0, within(EPS));
    }

    @Test
    void partialRefillAfterElapsedTime() {
        // 10 tokens/second.
        TokenBucket bucket = new TokenBucket(5, 10);
        TokenBucket.State state = TokenBucket.State.full(5, 0);

        TokenBucket.Result drained = bucket.consume(state, 5, 0);
        assertThat(drained.decision().remaining()).isCloseTo(0.0, within(EPS));

        // 250 ms later we accumulate 250 * (10/1000) = 2.5 fractional tokens.
        TokenBucket.Result refilled = bucket.consume(drained.state(), 1, 250);
        assertThat(refilled.decision().allowed()).isTrue();
        assertThat(refilled.decision().remaining()).isCloseTo(1.5, within(EPS));
    }

    @Test
    void refillNeverExceedsCapacity() {
        TokenBucket bucket = new TokenBucket(5, 10);
        TokenBucket.State state = TokenBucket.State.full(5, 0);

        TokenBucket.Result drained = bucket.consume(state, 5, 0);
        assertThat(drained.decision().remaining()).isCloseTo(0.0, within(EPS));

        // A huge elapsed time would refill 1000 * 10 = 10000 tokens, far beyond
        // capacity; it must be clamped at 5 before consuming the request.
        TokenBucket.Result refilled = bucket.consume(drained.state(), 1, 1_000_000);
        assertThat(refilled.decision().allowed()).isTrue();
        assertThat(refilled.decision().remaining()).isCloseTo(4.0, within(EPS));
        assertThat(refilled.decision().remaining()).isLessThanOrEqualTo(5.0);
        assertThat(refilled.state().tokens()).isLessThanOrEqualTo(5.0);
    }

    @Test
    void zeroElapsedTimeDoesNotRefill() {
        TokenBucket bucket = new TokenBucket(5, 10);
        TokenBucket.State state = TokenBucket.State.full(5, 0);

        TokenBucket.Result drained = bucket.consume(state, 5, 0);
        assertThat(drained.decision().remaining()).isCloseTo(0.0, within(EPS));

        // Same instant again: elapsed is zero, so nothing refills and the
        // request is rejected — this is the "now < lastRefill" sibling test for
        // the equal-time boundary.
        TokenBucket.Result stillEmpty = bucket.consume(drained.state(), 1, 0);
        assertThat(stillEmpty.decision().allowed()).isFalse();
        assertThat(stillEmpty.decision().remaining()).isCloseTo(0.0, within(EPS));
    }
}

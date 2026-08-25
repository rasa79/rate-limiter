package com.ratelimiter.service.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.rules.Algorithm;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Proves Lua atomicity under real parallel load: even with many threads slamming
 * a single hot key, exactly {@code limit} requests are allowed — never more, and
 * never a negative remaining balance.
 *
 * <p>The suite runs against both algorithms (token bucket and sliding window).
 */
class HotKeyConcurrencyIT extends ConcurrencyTestBase {

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void exactlyLimitRequestsAllowed_underFullContention(Algorithm algorithm) throws Exception {
        String rule = "hot-" + algorithm.name().toLowerCase();
        double limit = 1000;
        createRule(rule, algorithm, limit);

        // 64 threads x 100 attempts = 6400 requests against one key > limit.
        RaceResult result = race("hot-key", rule, 64, 100);

        // RATIONALE: EQUALS, not <= — Lua runs the read-modify-write atomically, so
        // the count is EXACT. A "<=" assertion would hide an over-grant (double-drain)
        // or a lost-token bug. It also checks no response ever reported a negative
        // remaining balance.
        assertThat(result.allowed()).isEqualTo((long) limit);
        assertThat(result.negativeRemaining()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void noRemainingEverNegative(Algorithm algorithm) throws Exception {
        String rule = "hot-neg-" + algorithm.name().toLowerCase();
        double limit = 100;
        createRule(rule, algorithm, limit);

        RaceResult result = race("hot-neg-key", rule, 32, 50);

        assertThat(result.negativeRemaining()).isZero();
        assertThat(result.allowed()).isEqualTo((long) limit);
    }
}

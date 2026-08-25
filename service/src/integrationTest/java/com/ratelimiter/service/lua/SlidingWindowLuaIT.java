package com.ratelimiter.service.lua;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.ratelimiter.service.algorithm.Decision;
import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import com.ratelimiter.service.valkey.ScriptLoader;

/**
 * Exercises the {@code sliding_window.lua} script against a real Valkey
 * container, with injected time so boundaries are deterministic.
 *
 * <p>The signature of these cases is what fixed windows get wrong: a rolling
 * window counts events across a continuously sliding horizon, never resets on a
 * rigid boundary, and prunes old entries.
 */
class SlidingWindowLuaIT extends ValkeyIntegrationTestBase {

    @Autowired
    private ScriptLoader scriptLoader;

    @Autowired
    private StatefulRedisConnection<String, String> connection;

    private Decision eval(String key, double limit, long window, long now) {
        List<String> r = scriptLoader.slidingWindow(key, limit, window, now);
        return new Decision(
                "1".equals(r.get(0)),
                Double.parseDouble(r.get(1)),
                Long.parseLong(r.get(2)),
                Long.parseLong(r.get(3)));
    }

    @Test
    void exactlyAtLimitEnforced() {
        String key = "sw-limit:" + UUID.randomUUID();
        long now = 1_800_000_000_000L;

        for (int i = 0; i < 5; i++) {
            assertThat(eval(key, 5, 1000, now).allowed())
                    .as("request %d should be allowed", i + 1)
                    .isTrue();
        }
        assertThat(eval(key, 5, 1000, now).allowed()).isFalse();
    }

    @Test
    void rollingWindowNotFixedWindow() {
        String key = "sw-rolling:" + UUID.randomUUID();
        long t0 = 1_800_000_000_000L;

        // 5 events at t0 (fits in the window). 5 more at t0+600 (0.6W).
        for (int i = 0; i < 5; i++) {
            assertThat(eval(key, 10, 1000, t0).allowed()).isTrue();
        }
        for (int i = 0; i < 5; i++) {
            assertThat(eval(key, 10, 1000, t0 + 600).allowed()).isTrue();
        }

        // Now 10 events are within the last 1000 ms -> rejected. A pair of fixed
        // windows (e.g. [0,1000) and [1000,2000)) would have allowed all 10.
        assertThat(eval(key, 10, 1000, t0 + 700).allowed()).isFalse();

        // Once the t0 events slide out (t0 < now - 1000), a slot frees up.
        assertThat(eval(key, 10, 1000, t0 + 1001).allowed()).isTrue();
    }

    @Test
    void oldEntriesArePrunedFromTheSet() {
        String key = "sw-prune:" + UUID.randomUUID();
        long t0 = 1_800_000_000_000L;

        for (int i = 0; i < 3; i++) {
            eval(key, 100, 1000, t0);
        }
        // The ZSET holds 3 members (all at t0).
        assertThat(connection.sync().zcard("rl:sw:" + key)).isEqualTo(3);

        // Advance well past the window: the old t0 events are pruned and the
        // (allowed) call adds a single new event at t0+5000.
        eval(key, 100, 1000, t0 + 5000);
        assertThat(connection.sync().zcard("rl:sw:" + key)).isEqualTo(1);
        assertThat(connection.sync().zrangeWithScores("rl:sw:" + key, 0, -1).get(0).getScore())
                .isEqualTo((double) (t0 + 5000));
    }

    @Test
    void windowRollover() {
        String key = "sw-rollover:" + UUID.randomUUID();
        long t0 = 1_800_000_000_000L;

        for (int i = 0; i < 4; i++) {
            assertThat(eval(key, 4, 1000, t0).allowed()).isTrue();
        }
        assertThat(eval(key, 4, 1000, t0).allowed()).isFalse();

        // At t0 + 999 the t0 events are still inside the window (barely).
        assertThat(eval(key, 4, 1000, t0 + 999).allowed()).isFalse();
        // At t0 + 1001 they have left the window -> capacity again.
        assertThat(eval(key, 4, 1000, t0 + 1001).allowed()).isTrue();
    }

    @Test
    void clockAtMidnight() {
        String key = "sw-midnight:" + UUID.randomUUID();
        long midnight = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        long before = midnight - 100;

        for (int i = 0; i < 3; i++) {
            assertThat(eval(key, 5, 2000, before).allowed()).isTrue();
        }
        // Crossing UTC midnight, 200 ms later, the window is purely arithmetic.
        // The 4th and 5th are allowed (count 4, then 5); the 6th is rejected.
        assertThat(eval(key, 5, 2000, midnight + 100).allowed()).isTrue();
        assertThat(eval(key, 5, 2000, midnight + 100).allowed()).isTrue();
        assertThat(eval(key, 5, 2000, midnight + 100).allowed()).isFalse();
    }
}

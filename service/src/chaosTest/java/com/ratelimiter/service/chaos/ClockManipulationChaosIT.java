package com.ratelimiter.service.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.ratelimiter.service.algorithm.Decision;
import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import com.ratelimiter.service.valkey.ScriptLoader;

/**
 * Chaos: drive the Lua decision scripts with injected clock shifts (forward jumps
 * past a window, and a backwards jump) and assert the boundaries behave correctly
 * — no negative refill, old entries expire, and the service never crashes on a
 * corrupted clock.
 */
class ClockManipulationChaosIT extends ValkeyIntegrationTestBase {

    @Autowired
    private ScriptLoader scriptLoader;

    private Decision sw(String key, double limit, long window, long now) {
        List<String> r = scriptLoader.slidingWindow(key, limit, window, now);
        return new Decision("1".equals(r.get(0)), Double.parseDouble(r.get(1)),
                Long.parseLong(r.get(2)), Long.parseLong(r.get(3)));
    }

    private Decision tb(String key, double cap, long now) {
        List<String> r = scriptLoader.tokenBucket(key, cap, 1, 1.0, now);
        return new Decision("1".equals(r.get(0)), Double.parseDouble(r.get(1)),
                Long.parseLong(r.get(2)), Long.parseLong(r.get(3)));
    }

    @Test
    void slidingWindowCrossesBeingExpiredOnForwardClockJump() {
        String key = "chaos-sw:" + UUID.randomUUID();
        long now = 1_800_000_000_000L;

        for (int i = 0; i < 5; i++) {
            assertThat(sw(key, 5, 1000, now).allowed()).isTrue();
        }
        assertThat(sw(key, 5, 1000, now).allowed()).isFalse();

        // A forward clock jump well past the window expires all events.
        assertThat(sw(key, 5, 1000, now + 10_000).allowed()).isTrue();
    }

    @Test
    void tokenBucketNeverRefillsBackwardsOnClockJump() {
        String key = "chaos-tb:" + UUID.randomUUID();
        long now = 1_800_000_000_000L;

        for (int i = 0; i < 5; i++) {
            assertThat(tb(key, 5, now).allowed()).isTrue();
        }
        assertThat(tb(key, 5, now).allowed()).isFalse();

        // A backwards clock (now goes in the past) must not produce a negative refill
        // or crash; the decision is clamped and the bucket is not over-refilled.
        Decision backward = tb(key, 5, now - 1_000_000);
        assertThat(backward.remaining()).isGreaterThanOrEqualTo(0.0);
        assertThat(backward.allowed()).isFalse();
    }
}

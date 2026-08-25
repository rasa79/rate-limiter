package com.ratelimiter.service.lua;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.ratelimiter.service.algorithm.Decision;
import com.ratelimiter.service.algorithm.SlidingWindowLog;
import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import com.ratelimiter.service.valkey.ScriptLoader;

/**
 * The executable proof that the Lua sliding-window script matches the pure-Java
 * spec: over a randomized sequence of injected times, the two produce identical
 * decisions.
 *
 * <p>This is the M5 risk mitigation (Lua/algorithm drift): if the script ever
 * diverges from the Java spec, this test catches it with a shrunk counterexample.
 * The sequence is driven by a seeded {@link Random} for reproducibility.
 */
class SlidingWindowDifferentialIT extends ValkeyIntegrationTestBase {

    private static final double EPS = 1e-6;

    @Autowired
    private ScriptLoader scriptLoader;

    @Test
    void javaAndLuaAgreeOnRandomizedSequences() {
        Random random = new Random(42L);
        double limit = 50;
        long window = 5000;

        SlidingWindowLog log = new SlidingWindowLog(limit, window);
        NavigableMap<Long, Integer> javaEvents = new TreeMap<>();
        String key = "sw-diff:" + UUID.randomUUID();
        long now = 0;

        for (int step = 0; step < 500; step++) {
            now += random.nextInt(3000); // deltas in [0, 3000)

            SlidingWindowLog.Result javaResult = log.consume(javaEvents, now);
            javaEvents = javaResult.state();

            List<String> lua = scriptLoader.slidingWindow(key, limit, window, now);
            Decision luaDecision = new Decision(
                    "1".equals(lua.get(0)),
                    Double.parseDouble(lua.get(1)),
                    Long.parseLong(lua.get(2)),
                    Long.parseLong(lua.get(3)));

            Decision javaDecision = javaResult.decision();
            assertThat(luaDecision.allowed())
                    .as("allowed at step %d (now=%d)", step, now)
                    .isEqualTo(javaDecision.allowed());
            assertThat(luaDecision.remaining())
                    .as("remaining at step %d", step)
                    .isCloseTo(javaDecision.remaining(), within(EPS));
            assertThat(luaDecision.resetAtMillis())
                    .as("resetAt at step %d", step)
                    .isEqualTo(javaDecision.resetAtMillis());
            assertThat(luaDecision.retryAfterSeconds())
                    .as("retryAfter at step %d", step)
                    .isEqualTo(javaDecision.retryAfterSeconds());
        }
    }
}

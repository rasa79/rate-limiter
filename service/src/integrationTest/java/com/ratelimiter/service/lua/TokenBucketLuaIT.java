package com.ratelimiter.service.lua;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.ratelimiter.service.algorithm.Decision;
import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import com.ratelimiter.service.valkey.ScriptLoader;

/**
 * Exercises the {@code token_bucket.lua} script against a real Valkey container,
 * with injected time so the boundaries are deterministic.
 *
 * <p>These tests prove the Lua semantics — not the Java math (M2) and not the
 * HTTP path (ValkeyCheckApiIT) — and are the executable spec the Lua is expected
 * to satisfy. The concurrency test proves atomicity: racing calls can never grant
 * more than the limit, even though the read-modify-write spans a read and a
 * write.
 */
class TokenBucketLuaIT extends ValkeyIntegrationTestBase {

    private static final double EPS = 1e-6;

    @Autowired
    private ScriptLoader scriptLoader;

    @Autowired
    private StatefulRedisConnection<String, String> connection;

    /**
     * Executes the script through the (non-racing) loader and maps the result
     * back to a {@link Decision}.
     */
    private Decision eval(String key, double capacity, double rate, double requested, long now) {
        List<String> r = scriptLoader.tokenBucket(key, capacity, rate, requested, now);
        return new Decision(
                "1".equals(r.get(0)),
                Double.parseDouble(r.get(1)),
                Long.parseLong(r.get(2)),
                Long.parseLong(r.get(3)));
    }

    @Test
    void allowsExactlyAtLimit_thenRejectsNext() {
        String key = "lua-limit:" + UUID.randomUUID();
        double capacity = 5;
        long now = 1_800_000_000_000L;

        for (int i = 0; i < 5; i++) {
            Decision decision = eval(key, capacity, 1, 1.0, now);
            assertThat(decision.allowed()).as("request %d should be allowed", i + 1).isTrue();
        }

        Decision rejected = eval(key, capacity, 1, 1.0, now);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void windowRolloverRefillsCorrectlyAtInjectedTime() {
        String key = "lua-refill:" + UUID.randomUUID();
        double capacity = 5;
        long now = 1_800_000_000_000L;

        for (int i = 0; i < 5; i++) {
            eval(key, capacity, 1, 1.0, now); // drain the bucket (1 token/sec)
        }

        // 2000 ms later: 2000 * (1/1000) = 2 tokens refilled.
        Decision refilled = eval(key, capacity, 1, 1.0, now + 2000);
        assertThat(refilled.allowed()).isTrue();
        assertThat(refilled.remaining()).isCloseTo(1.0, within(EPS));
    }

    @Test
    void clockAtMidnightBoundary() {
        String key = "lua-midnight:" + UUID.randomUUID();
        double capacity = 5;
        double rate = 2; // 2 tokens/second = 0.002/ms
        long midnight = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        long before = midnight - 100;
        long after = midnight + 100;

        for (int i = 0; i < 4; i++) {
            eval(key, capacity, rate, 1.0, before); // leave 1 token
        }

        // Despite crossing 00:00 UTC, refill is purely elapsed time: 200ms * 0.002 = 0.4.
        Decision crossed = eval(key, capacity, rate, 1.0, after);
        assertThat(crossed.allowed()).isTrue();
        assertThat(crossed.remaining()).isCloseTo(0.4, within(EPS));
    }

    @Test
    void concurrentScriptCallsConsumeExactlyOnce() {
        String key = "lua-race:" + UUID.randomUUID();
        double capacity = 100;
        int threads = 32;
        int attemptsPerThread = 20;
        long now = 1_800_000_000_000L;

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int a = 0; a < attemptsPerThread; a++) {
                        if (allowedAsync(key, capacity, 1000, now)) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            executor.shutdownNow();
        }

        // RATIONALE: EQUALS, not <= — Lua atomicity makes the count exact, so a
        // "<=" assertion would hide a correctness bug (double-drain or a lost
        // token). Exactly the limit must have been granted.
        assertThat(allowed.get()).isEqualTo((int) capacity);
    }

    /**
     * Runs a single EVALSHA call on the thread-safe async API.
     */
    @SuppressWarnings("unchecked")
    private boolean allowedAsync(String key, double capacity, double rate, long now) {
        try {
            List<String> r = (List<String>) connection.async()
                    .evalsha(scriptLoader.tokenBucketSha(), ScriptOutputType.MULTI,
                            new String[] { "rl:tc:" + key },
                            new String[] {
                                    String.valueOf(capacity),
                                    String.valueOf(rate),
                                    "1.0",
                                    String.valueOf(now)
                            })
                    .get();
            return "1".equals(r.get(0));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

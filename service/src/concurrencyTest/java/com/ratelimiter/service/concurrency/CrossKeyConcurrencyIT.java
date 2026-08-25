package com.ratelimiter.service.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.concurrency.ConcurrencyTestBase.RaceResult;
import com.ratelimiter.service.rules.Algorithm;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Proves that independent hot keys under parallel load do not interfere: each key
 * independently allows exactly its own limit, and no key's outcome is affected by
 * another's contention. Runs against both algorithms.
 */
class CrossKeyConcurrencyIT extends ConcurrencyTestBase {

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void parallelHotKeysDoNotInterfere(Algorithm algorithm) throws Exception {
        String rule = "cross-" + algorithm.name().toLowerCase();
        double limit = 50;
        int keys = 10;
        int threadsPerKey = 16;
        int attempts = 100; // 16 * 100 = 1600 attempts per key > limit 50

        createRule(rule, algorithm, limit);

        AtomicInteger wrongCount = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(keys);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int k = 0; k < keys; k++) {
                String key = "cross-key-" + k;
                futures.add(executor.submit(() -> {
                    start.await();
                    RaceResult result = race(key, rule, threadsPerKey, attempts);
                    if (result.allowed() != (long) limit || result.negativeRemaining() != 0) {
                        wrongCount.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(wrongCount.get()).isZero();
    }
}

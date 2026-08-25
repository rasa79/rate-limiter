package com.ratelimiter.service.concurrency;

import com.ratelimiter.service.rules.Algorithm;
import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Base for the exactly-N concurrency suite. Runs real, parallel HTTP requests
 * against the real Valkey-backed store (the shared container + Lua scripts) and
 * counts how many were allowed.
 */
public abstract class ConcurrencyTestBase extends ValkeyIntegrationTestBase {

    /**
     * The outcome of racing many requests at a single key.
     *
     * @param allowed how many requests were granted
     * @param negativeRemaining how many responses reported a negative remaining
     */
    protected record RaceResult(long allowed, long negativeRemaining) {
    }

    /**
     * Creates a rule of the given algorithm/limit via the admin API.
     *
     * @param name rule name
     * @param algorithm the algorithm
     * @param limit the limit
     */
    protected void createRule(String name, Algorithm algorithm, double limit) {
        String body = switch (algorithm) {
            case TOKEN_BUCKET ->
                    "{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":" + limit + ",\"refillPerSecond\":1,\"windowMillis\":0}";
            case SLIDING_WINDOW ->
                    "{\"algorithm\":\"SLIDING_WINDOW\",\"limit\":" + limit + ",\"refillPerSecond\":0,\"windowMillis\":3600000}";
        };
        http.put().uri("/v1/rules/" + name).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(code -> true, (req, resp) -> {
                }).toBodilessEntity();
    }

    /**
     * Races {@code threads} concurrent workers, each firing {@code attempts}
     * requests at one key under {@code rule}, and counts outcomes.
     */
    protected RaceResult race(String key, String rule, int threads, int attempts) throws Exception {
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger negativeRemaining = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int a = 0; a < attempts; a++) {
                        JsonNode body = check(key, rule);
                        if (body != null) {
                            if (body.path("allowed").asBoolean(false)) {
                                allowed.incrementAndGet();
                            }
                            if (body.path("remaining").asDouble(0) < 0) {
                                negativeRemaining.incrementAndGet();
                            }
                        }
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
        return new RaceResult(allowed.get(), negativeRemaining.get());
    }

    private JsonNode check(String key, String rule) {
        ResponseEntity<JsonNode> response = http.post().uri("/v1/check")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"key\":\"" + key + "\",\"rule\":\"" + rule + "\"}")
                .retrieve()
                .onStatus(code -> true, (req, resp) -> {
                })
                .toEntity(JsonNode.class);
        return response.getBody();
    }
}

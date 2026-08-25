package com.ratelimiter.service.backend;

import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.ratelimiter.service.algorithm.Decision;
import com.ratelimiter.service.algorithm.SlidingWindowLog;
import com.ratelimiter.service.algorithm.TokenBucket;
import com.ratelimiter.service.rules.Algorithm;
import com.ratelimiter.service.rules.Rule;

/**
 * An in-memory {@link RateLimitStore} that delegates the algorithm math to the
 * pure-Java implementations and keeps per-key state in {@link ConcurrentHashMap}s.
 *
 * <p>This is the M3 tracer bullet / local-dev backend: it proves the full request
 * path HTTP → controller → store → algorithm → response without a network store.
 * The production backend (M4+) is the {@link ValkeyRateLimitStore}, but they share
 * the same {@link Rule} and {@link Decision} contracts, so swapping implementations
 * is invisible to the controller.
 *
 * <p>// RATIONALE: per-key read-modify-write goes through a single
 * {@link ConcurrentHashMap#compute} call, atomic with respect to other ops on the
 * same key, so a burst of check requests cannot interleave and over-drain a
 * bucket/window. State lives in this instance only; a restart loses it, which is
 * acceptable here because the production backend owns durable state in Valkey.
 */
@Component
@ConditionalOnProperty(name = "ratelimiter.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryRateLimitStore implements RateLimitStore {

    private final ConcurrentHashMap<String, TokenBucket.State> tokenBucketStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NavigableMap<Long, Integer>> windowStates = new ConcurrentHashMap<>();

    @Override
    public Decision check(String key, Rule rule, double requestedTokens, long nowMillis) {
        return switch (rule.algorithm()) {
            case TOKEN_BUCKET -> tokenBucket(key, rule, requestedTokens, nowMillis);
            case SLIDING_WINDOW -> slidingWindow(key, rule, nowMillis);
        };
    }

    private Decision tokenBucket(String key, Rule rule, double requestedTokens, long nowMillis) {
        TokenBucket bucket = new TokenBucket(rule.limit(), rule.refillPerSecond());
        final Decision[] result = new Decision[1];
        tokenBucketStates.compute(key, (k, previous) -> {
            TokenBucket.State current = previous == null
                    ? TokenBucket.State.full(rule.limit(), nowMillis)
                    : previous;
            TokenBucket.Result r = bucket.consume(current, requestedTokens, nowMillis);
            result[0] = r.decision();
            return r.state();
        });
        return result[0];
    }

    private Decision slidingWindow(String key, Rule rule, long nowMillis) {
        SlidingWindowLog log = new SlidingWindowLog(rule.limit(), rule.windowMillis());
        final Decision[] result = new Decision[1];
        windowStates.compute(key, (k, previous) -> {
            NavigableMap<Long, Integer> events = previous == null ? new TreeMap<>() : previous;
            SlidingWindowLog.Result r = log.consume(events, nowMillis);
            result[0] = r.decision();
            return r.state();
        });
        return result[0];
    }
}

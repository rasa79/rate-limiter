package com.ratelimiter.service.backend;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import com.ratelimiter.service.algorithm.Decision;
import com.ratelimiter.service.algorithm.TokenBucket;
import com.ratelimiter.service.rules.Rule;

/**
 * An in-memory {@link RateLimitStore} that delegates the token-bucket math to
 * {@link TokenBucket} and keeps per-key state in a {@link ConcurrentHashMap}.
 *
 * <p>This is the M3 tracer bullet: it proves the full request path
 * HTTP → controller → store → algorithm → response without any network store.
 * It is not the production backend — M4 replaces it with a Valkey-backed
 * store — but it shares the same {@link Rule} and {@link Decision} contracts, so
 * swapping the implementation is invisible to the controller.
 *
 * <p>// RATIONALE: the per-key read-modify-write goes through a single
 * {@link ConcurrentHashMap#compute} call, which is atomic with respect to other
 * ops on the same key, so a burst of check requests cannot interleave and
 * over-drain a bucket. State lives in this instance only; a restart loses it,
 * which is acceptable here because the production backend (M4) owns durable
 * state in Valkey.
 */
@Component
public class InMemoryRateLimitStore implements RateLimitStore {

    private final ConcurrentHashMap<String, TokenBucket.State> states = new ConcurrentHashMap<>();

    @Override
    public Decision check(String key, Rule rule, double requestedTokens, long nowMillis) {
        TokenBucket bucket = new TokenBucket(rule.capacity(), rule.refillPerSecond());
        final Decision[] result = new Decision[1];
        states.compute(key, (k, previous) -> {
            TokenBucket.State current = previous == null
                    ? TokenBucket.State.full(rule.capacity(), nowMillis)
                    : previous;
            TokenBucket.Result r = bucket.consume(current, requestedTokens, nowMillis);
            result[0] = r.decision();
            return r.state();
        });
        return result[0];
    }
}

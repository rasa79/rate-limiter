package com.ratelimiter.service.rules;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import com.ratelimiter.service.config.RateLimiterProperties;

/**
 * In-memory, TTL-refreshed cache over a {@link RuleStore} — the read path used by
 * {@code /v1/check}.
 *
 * <p>// RATIONALE: pub/sub gives a fast, immediate consistency path, but it is
 * lossy (a message can be dropped, or an instance can miss one during a
 * disconnect). The TTL refresh is the belt-and-braces safety net: even if the
 * invalidation message never arrives, the cache re-reads the store within a
 * bounded time and converges. Together they give "eventual within a bound, and
 * immediate in the common case."
 */
@Component
public class RuleCache {

    private final RuleStore store;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * @param store the source-of-truth rule store
     * @param properties the bootstrap properties (cache TTL)
     */
    public RuleCache(RuleStore store, RateLimiterProperties properties) {
        this.store = store;
        // RATIONALE: the cache TTL is a real-time convergence bound, NOT algorithm
        // logic, so it uses the wall clock (unlike the algorithm classes which are
        // strictly time-injected). Using the injected Clock would freeze the TTL in
        // tests that pin the clock.
        this.ttlMillis = TimeUnit.SECONDS.toMillis(properties.cacheTtlSeconds());
    }

    /**
     * Resolves a rule, refreshing from the store on cache-miss or TTL expiry.
     *
     * @param name the rule name
     * @return the rule, or empty if unknown
     */
    public Optional<Rule> resolve(String name) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(name);
        if (cached != null && now - cached.loadedAtMillis < ttlMillis) {
            return Optional.of(cached.rule);
        }
        Optional<Rule> found = store.find(name);
        if (found.isPresent()) {
            cache.put(name, new Cached(found.get(), now));
        } else {
            cache.remove(name);
        }
        return found;
    }

    /**
     * Drops a cached entry so the next resolve reads the store afresh (called on a
     * local write and on a pub/sub invalidation message).
     *
     * @param name the rule name to invalidate
     */
    public void invalidate(String name) {
        cache.remove(name);
    }

    /**
     * @return all rules directly from the store (bypassing the cache)
     */
    public Map<String, Rule> all() {
        return store.findAll();
    }

    /**
     * A cached rule with the instant it was loaded.
     */
    private record Cached(Rule rule, long loadedAtMillis) {
    }
}

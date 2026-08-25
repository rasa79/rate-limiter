package com.ratelimiter.service.backend;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.ratelimiter.service.algorithm.Decision;
import com.ratelimiter.service.rules.Rule;
import com.ratelimiter.service.valkey.ScriptLoader;

/**
 * The production {@link RateLimitStore}: every decision is executed atomically
 * inside Valkey by the Lua script (see {@code lua/token_bucket.lua}).
 *
 * <p>The controller is unchanged from M3 — only this bean is wired in instead of
 * the in-memory one (selected by {@code ratelimiter.store=valkey}). The store is
 * stateless from the instance's point of view; all counter state lives in Valkey,
 * so any number of instances can share it.
 *
 * <p>// RATIONALE: atomicity is the store's job, not the application's. Because
 * {link ScriptLoader} runs the read-modify-write as a single Lua script, a crash
 * or a concurrent burst cannot leave a partially consumed counter (ADR-0003).
 */
@Component
@ConditionalOnProperty(name = "ratelimiter.store", havingValue = "valkey")
public class ValkeyRateLimitStore implements RateLimitStore {

    private final ScriptLoader scriptLoader;

    /**
     * @param scriptLoader executes the token-bucket Lua script
     */
    public ValkeyRateLimitStore(ScriptLoader scriptLoader) {
        this.scriptLoader = scriptLoader;
    }

    @Override
    public Decision check(String key, Rule rule, double requestedTokens, long nowMillis) {
        List<String> result = scriptLoader.tokenBucket(
                key, rule.capacity(), rule.refillPerSecond(), requestedTokens, nowMillis);

        boolean allowed = "1".equals(result.get(0));
        double remaining = Double.parseDouble(result.get(1));
        long resetAtMillis = Long.parseLong(result.get(2));
        long retryAfterSeconds = Long.parseLong(result.get(3));

        return new Decision(allowed, remaining, resetAtMillis, retryAfterSeconds);
    }
}

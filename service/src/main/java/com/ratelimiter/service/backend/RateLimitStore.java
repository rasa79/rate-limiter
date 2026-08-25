package com.ratelimiter.service.backend;

import com.ratelimiter.service.algorithm.Decision;
import com.ratelimiter.service.rules.Rule;

/**
 * Storage abstraction for rate-limit state.
 *
 * <p>The check path depends only on this interface, so the concrete store can be
 * swapped without touching the controller: M3 ships an in-memory implementation
 * (the tracer-bullet), M4 replaces it with a Valkey-backed one whose decisions
 * run inside Lua. A decision is produced from {@code (key, rule, requested,
 * now)} where {@code nowMillis} is injected by the caller — the store never reads
 * a clock itself, preserving the determinism rule from §2.5.
 */
public interface RateLimitStore {

    /**
     * Evaluates a request against the current state of a key under a rule.
     *
     * @param key the rate-limit key (e.g. an API key or tenant id)
     * @param rule the rule governing the key
     * @param requestedTokens how many units the request requires
     * @param nowMillis the injected current time, in epoch millis
     * @return the decision (allowed/remaining/reset/retry) for the request
     */
    Decision check(String key, Rule rule, double requestedTokens, long nowMillis);
}

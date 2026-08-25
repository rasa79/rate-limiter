package com.ratelimiter.service.api;

import java.time.Clock;
import com.ratelimiter.service.algorithm.Decision;
import com.ratelimiter.service.backend.RateLimitStore;
import com.ratelimiter.service.rules.Rule;
import com.ratelimiter.service.rules.RuleCache;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hot path: evaluates a single rate-limit decision.
 *
 * <p>It is deliberately thin — resolve the rule, ask the {@link RateLimitStore}
 * for a decision using an injected {@link Clock}, and let the exception handler
 * turn a rejection into a 429 with a {@code Retry-After} header. Nothing here
 * knows which store (in-memory now, Valkey from M4) or which algorithm the rule
 * uses, so the path stays stable as the backend is swapped.
 */
@RestController
@RequestMapping("/v1")
public class CheckController {

    private static final double DEFAULT_REQUEST_TOKENS = 1.0;

    private final RateLimitStore store;
    private final RuleCache ruleCache;
    private final Clock clock;

    /**
     * @param store the rate-limit state store (the component that will be
     *              swapped for a Valkey backend in M4)
     * @param ruleCache resolves a request's rule name, refreshing from the store
     * @param clock supplies "now" to the decision
     */
    public CheckController(RateLimitStore store, RuleCache ruleCache, Clock clock) {
        this.store = store;
        this.ruleCache = ruleCache;
        this.clock = clock;
    }

    /**
     * Evaluates the keyed request under the named rule.
     *
     * @param request the request body ({@code key}, {@code rule})
     * @return the decision when allowed (HTTP 200)
     * @throws IllegalArgumentException if {@code key} or {@code rule} is blank
     * @throws UnknownRuleException if the named rule is not defined
     * @throws RateLimitExceededException if the request is rejected (mapped to
     *                                    HTTP 429 by the exception handler)
     */
    @PostMapping(value = "/check", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CheckResponse check(@RequestBody CheckRequest request) {
        if (request.key() == null || request.key().isBlank()
                || request.rule() == null || request.rule().isBlank()) {
            throw new IllegalArgumentException("'key' and 'rule' are required");
        }
        Rule rule = ruleCache.resolve(request.rule())
                .orElseThrow(() -> new UnknownRuleException(request.rule()));

        long nowMillis = clock.millis();
        Decision decision = store.check(request.key(), rule, DEFAULT_REQUEST_TOKENS, nowMillis);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision);
        }
        return CheckResponse.from(decision);
    }
}

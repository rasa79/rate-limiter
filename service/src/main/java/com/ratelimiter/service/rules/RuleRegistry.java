package com.ratelimiter.service.rules;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import com.ratelimiter.service.config.RateLimiterProperties;

/**
 * Resolves a rule by name from the bootstrap set loaded at startup.
 *
 * <p>This is the read path used by {@code /v1/check}. For M3 the registry is an
 * immutable snapshot of {@link RateLimiterProperties}; M6 replaces/invalidates
 * it via admin API writes plus pub/sub and a TTL-refresh cache, but the resolve
 * contract (name-to-rule) stays unchanged, so the check path is unaffected.
 */
@Component
public class RuleRegistry {

    private final Map<String, Rule> rules;

    /**
     * Constructs the registry from the bootstrap properties.
     *
     * @param properties the bound bootstrap configuration
     */
    public RuleRegistry(RateLimiterProperties properties) {
        this.rules = Collections.unmodifiableMap(properties.rules());
    }

    /**
     * Looks up a rule by name.
     *
     * @param name the rule name; must not be {@code null}
     * @return the matching rule, or {@link Optional#empty()} if unknown
     */
    public Optional<Rule> resolve(String name) {
        return Optional.ofNullable(rules.get(name));
    }

    /**
     * @return an unmodifiable view of all known rules, keyed by name
     */
    public Map<String, Rule> all() {
        return rules;
    }
}

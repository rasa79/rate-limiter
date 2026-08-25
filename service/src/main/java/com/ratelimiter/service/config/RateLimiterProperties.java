package com.ratelimiter.service.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.ratelimiter.service.rules.Rule;

/**
 * Binds the static, bootstrap rule set and the rule-cache TTL from configuration.
 *
 * <p>Precedence (fixed across the project, documented in the README) is:
 * file &lt; env &lt; admin API. This properties record holds the two lowest
 * tiers — the rules defined in {@code application.yml} / environment — which the
 * {@code BootstrapRulesLoader} seeds into the {@code RuleStore} on startup and
 * which the mutable admin API later overrides.
 *
 * @param rules the bootstrap rules keyed by name
 * @param cacheTtlSeconds secondary freshness bound for the rule cache (seconds)
 */
@ConfigurationProperties(prefix = "ratelimiter")
public record RateLimiterProperties(Map<String, Rule> rules, long cacheTtlSeconds) {

    /**
     * Defensive defaults: never expose a mutable map, and never allow a zero/negative TTL.
     *
     * @param rules the configured rule map
     * @param cacheTtlSeconds the cache TTL
     */
    public RateLimiterProperties {
        rules = rules == null ? Map.of() : Map.copyOf(rules);
        cacheTtlSeconds = cacheTtlSeconds <= 0 ? 30 : cacheTtlSeconds;
    }
}

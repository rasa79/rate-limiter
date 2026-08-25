package com.ratelimiter.service.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.ratelimiter.service.rules.Rule;

/**
 * Binds the static, bootstrap rule set from configuration.
 *
 * <p>Precedence (fixed across the project, documented in the README) is:
 * file &lt; env &lt; admin API. This properties record holds the lowest tier —
 * the rules defined in {@code application.yml} / environment — which M6 seeds
 * into the rule store on startup and which later becomes one bootstrap source
 * behind the mutable admin API.
 *
 * <p>Each entry of {@code ratelimiter.rules.<name>} yields a {@link Rule} keyed
 * by that name. Binding uses record constructor binding, so no boilerplate.
 *
 * @param rules the bootstrap rules keyed by rule name
 */
@ConfigurationProperties(prefix = "ratelimiter")
public record RateLimiterProperties(Map<String, Rule> rules) {

    /**
     * Defensive copy: never expose the mutable backing map.
     *
     * @param rules the configured rule map
     */
    public RateLimiterProperties {
        rules = rules == null ? Map.of() : Map.copyOf(rules);
    }
}

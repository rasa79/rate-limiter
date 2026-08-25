package com.ratelimiter.service.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import com.ratelimiter.service.rules.Rule;
import com.ratelimiter.service.rules.RuleStore;

/**
 * Seeds the {@link RuleStore} with the bootstrap (file/env) rules on startup.
 *
 * <p>// RATIONALE: application is IDEMPOTENT — a rule already present in the store
 * is left untouched, so re-running the bootstrap (e.g. on restart) neither
 * duplicates nor overwrites a rule that an operator changed via the admin API.
 * This is what enforces the precedence file &lt; env &lt; admin API: the two
 * lower tiers are applied once, and any admin change thereafter wins.
 */
@Component
public class BootstrapRulesLoader implements ApplicationRunner {

    private final RuleStore store;
    private final RateLimiterProperties properties;

    /**
     * @param store the source-of-truth rule store
     * @param properties the bootstrap properties
     */
    public BootstrapRulesLoader(RuleStore store, RateLimiterProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Rule rule : properties.rules().values()) {
            if (store.find(rule.name()).isEmpty()) {
                store.save(rule);
            }
        }
    }
}

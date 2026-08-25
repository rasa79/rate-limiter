package com.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.api.AdminController;
import com.ratelimiter.service.api.RuleUpdateRequest;
import com.ratelimiter.service.config.BootstrapRulesLoader;
import com.ratelimiter.service.rules.Algorithm;
import com.ratelimiter.service.rules.Rule;
import com.ratelimiter.service.rules.RuleCache;
import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Proves the bootstrap contract: rules from {@code application.yml} are applied
 * on startup, and re-applying the bootstrap is idempotent (an operator's admin
 * change survives).
 */
class BootstrapConfigIT extends ValkeyIntegrationTestBase {

    @Autowired
    private RuleCache cache;

    @Autowired
    private BootstrapRulesLoader loader;

    @Autowired
    private AdminController adminController;

    @Test
    void yamlRulesAppliedOnStartup() {
        assertThat(cache.resolve("standard")).isPresent();
        assertThat(cache.resolve("strict")).isPresent();
        assertThat(cache.resolve("rolling-hour")).isPresent();
        assertThat(cache.resolve("burst")).isPresent();
    }

    @Test
    void reapplicationIsIdempotent() {
        // An operator lowers 'strict' via the admin API...
        adminController.put("strict", new RuleUpdateRequest(Algorithm.TOKEN_BUCKET, 1.0, 1.0, 0));
        try {
            // ...and re-running the bootstrap must NOT overwrite it (idempotent
            // apply, precedence file < env < admin).
            loader.run(new DefaultApplicationArguments(new String[0]));
            assertThat(cache.resolve("strict").map(Rule::limit).orElse(-1.0)).isEqualTo(1.0);
        } finally {
            // Restore the config value so this shared store isn't polluted for later tests.
            adminController.put("strict", new RuleUpdateRequest(Algorithm.TOKEN_BUCKET, 5.0, 1.0, 0));
        }
    }
}

package com.ratelimiter.service.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests that exercise the real Valkey-backed store.
 *
 * <p>Selects {@code ratelimiter.store=valkey} and points the connection at the
 * shared Testcontainers container, so the application boots with the Lettuce +
 * Lua backend (ScriptLoader, ValkeyRateLimitStore, ValkeyReadinessIndicator)
 * wired in.
 */
public abstract class ValkeyIntegrationTestBase extends IntegrationTestBase {

    /**
     * Routes the application to the shared container and enables the Valkey store.
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void valkeyProperties(DynamicPropertyRegistry registry) {
        registry.add("ratelimiter.store", () -> "valkey");
        registry.add("ratelimiter.valkey.host", () -> "localhost");
        registry.add("ratelimiter.valkey.port", () -> valkey.getMappedPort(6379));
    }
}

package com.ratelimiter.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test proving the Spring context boots cleanly from a minimal skeleton.
 *
 * <p>This is intentionally the most basic verification in the project: it
 * catches wiring mistakes (bad auto-configuration, unresolvable beans, missing
 * properties) that would otherwise surface only at runtime in a deployment.
 */
@SpringBootTest
class ApplicationContextTest {

    /**
     * Asserts the entire application context loads without error.
     */
    @Test
    void contextLoads() {
        // No assertion needed: a context that fails to boot throws before this
        // returns, so the test either passes or fails on context construction.
    }
}

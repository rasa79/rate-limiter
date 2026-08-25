package com.ratelimiter.example.saas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Example 1: a SaaS app whose per-tenant tier is enforced by the
 * {@code RateLimitFilter}, and whose "billing" service can upgrade a tenant's
 * tier at runtime via the rate-limiter admin API (no restart).
 */
@SpringBootApplication
public class SaasTiersApplication {

    /**
     * Boots the example app.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(SaasTiersApplication.class, args);
    }
}

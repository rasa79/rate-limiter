package com.ratelimiter.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the distributed rate-limiter service.
 *
 * <p>The application is a conventional, stateless Spring Boot deployment: every
 * instance is interchangeable and holds no durable local state. All rules and
 * counters live in an external store (Valkey from milestone 4 onward). This
 * class is deliberately tiny — it only boots the Spring context and lets
 * auto-configuration and the explicitly declared components do the work.
 *
 * <p>Runtime contract:
 * <ul>
 *   <li>{@code GET /actuator/health/liveness} — the JVM is alive (not a
 *       dependency indicator).</li>
 *   <li>{@code GET /actuator/health/readiness} — the instance can serve
 *       traffic; until an external store is wired this reports UP.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RateLimiterApplication {

    /**
     * Boots the application context as a Spring Boot application.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }
}

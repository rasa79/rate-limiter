package com.ratelimiter.service.valkey;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bindings for the Valkey connection (host/port/timeout).
 *
 * <p>Bound from {@code ratelimiter.valkey.*}; the integration tests override
 * host/port via {@code @DynamicPropertySource} to point at a Testcontainers
 * instance. Defaults are sensible for a local {@code valkey/valkey:8} on 6379.
 *
 * @param host the Valkey host
 * @param port the Valkey port
 * @param timeoutMillis the command timeout, in milliseconds
 */
@ConfigurationProperties(prefix = "ratelimiter.valkey")
public record ValkeyProperties(String host, int port, long timeoutMillis) {

    /**
     * Applies sane defaults for unset values.
     *
     * @param host the host
     * @param port the port
     * @param timeoutMillis the timeout
     */
    public ValkeyProperties {
        host = (host == null || host.isBlank()) ? "localhost" : host;
        port = port <= 0 ? 6379 : port;
        timeoutMillis = timeoutMillis <= 0 ? 2000 : timeoutMillis;
    }
}

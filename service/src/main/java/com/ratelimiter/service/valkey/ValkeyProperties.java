package com.ratelimiter.service.valkey;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bindings for the Valkey connection (host/port/timeout).
 *
 * <p>Bound from {@code ratelimiter.valkey.*}; the integration tests override
 * host/port via {@code @DynamicPropertySource} to point at a Testcontainers
 * instance. Defaults are sensible for a local {@code valkey/valkey:8} on 6379.
 *
 * @param host the Valkey host (or the sentinel host when using Sentinel)
 * @param port the Valkey port (or the sentinel port)
 * @param timeoutMillis the command timeout, in milliseconds
 * @param sentinelMasterId when set, enables Sentinel discovery and names the
 *                         master group; {@code null} for a direct connection
 */
@ConfigurationProperties(prefix = "ratelimiter.valkey")
public record ValkeyProperties(String host, int port, long timeoutMillis, String sentinelMasterId) {

    /**
     * Applies sane defaults for unset values.
     *
     * @param host the host
     * @param port the port
     * @param timeoutMillis the timeout
     * @param sentinelMasterId the Sentinel master-group name
     */
    public ValkeyProperties {
        host = (host == null || host.isBlank()) ? "localhost" : host;
        port = port <= 0 ? 6379 : port;
        timeoutMillis = timeoutMillis <= 0 ? 2000 : timeoutMillis;
    }
}

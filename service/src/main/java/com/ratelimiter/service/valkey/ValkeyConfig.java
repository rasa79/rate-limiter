package com.ratelimiter.service.valkey;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires a raw Lettuce client and a shared {@link StatefulRedisConnection}.
 *
 * <p>Only created when {@code ratelimiter.store=valkey}, so the in-memory store
 * (and the unit/context tests that use it) never touch Lettuce.
 *
 * // RATIONALE: Lettuce is chosen over Jedis because it is async, thread-safe,
 * supports native script (EVALSHA/EVAL) calls with the {@code RedisNoScriptException}
 * fallback we need, and has first-class Sentinel support (M10). We use the
 * connection-level API directly rather than Spring Data Redis templates so the
 * Lua path is under our control.
 */
@Configuration
@ConditionalOnProperty(name = "ratelimiter.store", havingValue = "valkey")
public class ValkeyConfig {

    /**
     * Creates and configures the Lettuce client.
     *
     * @param properties the Valkey connection properties
     * @return the client (shuts down with the context)
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(ValkeyProperties properties) {
        RedisURI uri = RedisURI.builder()
                .withHost(properties.host())
                .withPort(properties.port())
                .withTimeout(Duration.ofMillis(properties.timeoutMillis()))
                .build();
        return RedisClient.create(uri);
    }

    /**
     * @param client the Lettuce client
     * @return a shared connection (closed with the context)
     */
    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> redisConnection(RedisClient client) {
        return client.connect();
    }
}

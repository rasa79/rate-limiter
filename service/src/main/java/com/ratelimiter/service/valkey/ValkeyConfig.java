package com.ratelimiter.service.valkey;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
        // RATIONALE: with a Sentinel master-group id configured, the client
        // discovers the current primary through Sentinel (auto-follows failover);
        // otherwise it connects directly to host:port. Either way decisions are
        // atomic, so a failover never leaves partial state (ADR-0003).
        RedisURI.Builder builder = RedisURI.builder()
                .withTimeout(Duration.ofMillis(properties.timeoutMillis()));
        if (properties.sentinelMasterId() != null && !properties.sentinelMasterId().isBlank()) {
            builder.withSentinel(properties.host(), properties.port())
                    .withSentinelMasterId(properties.sentinelMasterId());
        } else {
            builder.withHost(properties.host()).withPort(properties.port());
        }
        return RedisClient.create(builder.build());
    }

    /**
     * @param client the Lettuce client
     * @return a shared connection (closed with the context)
     */
    @Bean(destroyMethod = "close")
    @Primary
    public StatefulRedisConnection<String, String> redisConnection(RedisClient client) {
        return client.connect();
    }

    /**
     * A dedicated pub/sub connection.
     *
     * <p>// RATIONALE: subscribing on the shared command connection would put it
     * into pub/sub mode, where normal commands are rejected. Rule invalidation
     * therefore uses its own connection, leaving the command connection free.
     *
     * @param client the Lettuce client
     * @return a dedicated pub/sub connection (closed with the context)
     */
    @Bean(destroyMethod = "close")
    public StatefulRedisPubSubConnection<String, String> pubSubConnection(RedisClient client) {
        return client.connectPubSub();
    }
}

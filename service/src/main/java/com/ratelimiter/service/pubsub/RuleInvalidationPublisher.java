package com.ratelimiter.service.pubsub;

import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes a rule-invalidation notification on the pub/sub channel so every
 * other instance drops the stale rule from its cache immediately.
 */
@Component
@ConditionalOnProperty(name = "ratelimiter.store", havingValue = "valkey")
public class RuleInvalidationPublisher {

    /**
     * The invalidation channel. Rule updates/deletes publish the rule name.
     */
    public static final String CHANNEL = "ratelimiter:rules:invalidate";

    private final StatefulRedisConnection<String, String> connection;

    /**
     * @param connection the shared Valkey connection
     */
    public RuleInvalidationPublisher(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    /**
     * Notifies subscribers that a rule changed.
     *
     * @param ruleName the rule name that changed
     */
    public void publish(String ruleName) {
        connection.sync().publish(CHANNEL, ruleName);
    }
}

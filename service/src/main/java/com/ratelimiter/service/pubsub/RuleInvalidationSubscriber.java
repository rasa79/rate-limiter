package com.ratelimiter.service.pubsub;

import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.ratelimiter.service.rules.RuleCache;

/**
 * Subscribes to the invalidation channel and drops the stale rule from the
 * {@link RuleCache} when another instance changes it — the fast consistency path.
 *
 * <p>It uses a dedicated pub/sub connection (not the command connection, which
 * must stay free for normal commands). Messages arrive on a Netty event-loop
 * thread; the work done here is a single thread-safe map removal.
 */
@Component
@ConditionalOnProperty(name = "ratelimiter.store", havingValue = "valkey")
public class RuleInvalidationSubscriber {

    private final StatefulRedisPubSubConnection<String, String> pubSub;
    private final RuleCache cache;

    /**
     * @param pubSub the dedicated pub/sub connection
     * @param cache the rule cache to invalidate
     */
    public RuleInvalidationSubscriber(StatefulRedisPubSubConnection<String, String> pubSub,
            RuleCache cache) {
        this.pubSub = pubSub;
        this.cache = cache;
    }

    /**
     * Registers the listener and subscribes to the channel at startup.
     */
    @PostConstruct
    public void subscribe() {
        pubSub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String ruleName) {
                cache.invalidate(ruleName);
            }

            @Override
            public void message(String channel, String pattern, String message) {
                cache.invalidate(message);
            }
        });
        pubSub.sync().subscribe(RuleInvalidationPublisher.CHANNEL);
    }
}

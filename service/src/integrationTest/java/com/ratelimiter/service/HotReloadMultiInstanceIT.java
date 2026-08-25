package com.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.api.AdminController;
import com.ratelimiter.service.api.RuleUpdateRequest;
import com.ratelimiter.service.config.RateLimiterProperties;
import com.ratelimiter.service.pubsub.RuleInvalidationSubscriber;
import com.ratelimiter.service.rules.Algorithm;
import com.ratelimiter.service.rules.Rule;
import com.ratelimiter.service.rules.RuleCache;
import com.ratelimiter.service.rules.RuleStore;
import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves a rule change made on one "instance" becomes visible to another without
 * a restart, via the shared Valkey {@link RuleStore} and the pub/sub invalidation
 * channel — with a TTL safety net if a message is lost.
 *
 * <p>Instance B is modeled as a second {@link RuleCache} + subscriber (a separate
 * view) sharing the same store and pub/sub connection, which is the observable
 * behavior of a second running instance. A short cache TTL (1s) is used so the
 * TTL fallback is testable.
 */
@TestPropertySource(properties = "ratelimiter.cache-ttl-seconds=1")
class HotReloadMultiInstanceIT extends ValkeyIntegrationTestBase {

    @Autowired
    private AdminController adminController;

    @Autowired
    private RuleStore store;

    @Autowired
    private RuleCache instanceA;

    @Autowired
    private RateLimiterProperties properties;

    @Autowired
    private StatefulRedisPubSubConnection<String, String> pubSub;

    @Test
    void ruleUpdatePropagatesToSecondInstanceWithoutRestart() throws Exception {
        // A second "instance": its own cache + subscriber on the same store/channel.
        RuleCache instanceB = new RuleCache(store, properties);
        new RuleInvalidationSubscriber(pubSub, instanceB).subscribe();

        // Instance A creates a rule (limit 5) ...
        adminController.put("op-rule", new RuleUpdateRequest(Algorithm.TOKEN_BUCKET, 5.0, 1.0, 0));
        // ...and instance B warms its cache with it.
        assertThat(instanceB.resolve("op-rule").map(Rule::limit).orElse(-1.0)).isEqualTo(5.0);

        // Instance A lowers the limit to 1 and publishes an invalidation.
        adminController.put("op-rule", new RuleUpdateRequest(Algorithm.TOKEN_BUCKET, 1.0, 1.0, 0));

        // Instance B converges without a restart: pub/sub dropped its stale copy.
        await(() -> instanceB.resolve("op-rule").map(Rule::limit).orElse(0.0) == 1.0, 5_000);
    }

    @Test
    void ttlRefreshEventuallyConsistentWithoutPubsub() throws Exception {
        RuleCache instanceB = new RuleCache(store, properties);

        // Seed the store directly (no pub/sub) with the "old" value and warm B's cache.
        store.save(Rule.tokenBucket("ttl-rule", 5.0, 1.0));
        assertThat(instanceB.resolve("ttl-rule").map(Rule::limit).orElse(-1.0)).isEqualTo(5.0);

        // Change the store DIRECTLY, as if the invalidation message were lost.
        store.save(Rule.tokenBucket("ttl-rule", 1.0, 1.0));

        // The cache still serves the stale value until the TTL elapses...
        assertThat(instanceB.resolve("ttl-rule").map(Rule::limit).orElse(-1.0)).isEqualTo(5.0);

        // ...then the TTL safety net refreshes it and it converges.
        await(() -> instanceB.resolve("ttl-rule").map(Rule::limit).orElse(0.0) == 1.0, 10_000);
    }

    private void await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("condition not met within " + timeoutMillis + " ms");
    }
}

package com.ratelimiter.service.rules;

import io.lettuce.core.api.StatefulRedisConnection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The production {@link RuleStore}: rules live in a single Valkey hash, so every
 * instance reads/writes the same shared state and a change made on one instance
 * is visible to all.
 */
@Component
@ConditionalOnProperty(name = "ratelimiter.store", havingValue = "valkey")
public class ValkeyRuleStore implements RuleStore {

    private static final String RULES_KEY = "ratelimiter:rules";
    private static final String SEP = "|";

    private final StatefulRedisConnection<String, String> connection;

    /**
     * @param connection the shared Valkey connection
     */
    public ValkeyRuleStore(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public void save(Rule rule) {
        connection.sync().hset(RULES_KEY, rule.name(), encode(rule));
    }

    @Override
    public Optional<Rule> find(String name) {
        String value = connection.sync().hget(RULES_KEY, name);
        return value == null ? Optional.empty() : Optional.of(decode(name, value));
    }

    @Override
    public Map<String, Rule> findAll() {
        Map<String, Rule> result = new LinkedHashMap<>();
        connection.sync().hgetall(RULES_KEY)
                .forEach((name, value) -> result.put(name, decode(name, value)));
        return result;
    }

    @Override
    public void delete(String name) {
        connection.sync().hdel(RULES_KEY, name);
    }

    private String encode(Rule rule) {
        return rule.algorithm() + SEP + rule.limit() + SEP + rule.refillPerSecond() + SEP + rule.windowMillis();
    }

    private Rule decode(String name, String value) {
        String[] parts = value.split("\\" + SEP);
        return new Rule(name, Algorithm.valueOf(parts[0]), Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]), Long.parseLong(parts[3]));
    }
}

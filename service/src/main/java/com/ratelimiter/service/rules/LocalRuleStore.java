package com.ratelimiter.service.rules;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link RuleStore} used when {@code ratelimiter.store=in-memory}.
 *
 * <p>This is a single-instance store (no shared state), so hot reload only applies
 * within one JVM; cross-instance propagation requires the Valkey backend.
 */
@Component
@ConditionalOnProperty(name = "ratelimiter.store", havingValue = "in-memory", matchIfMissing = true)
public class LocalRuleStore implements RuleStore {

    private final ConcurrentHashMap<String, Rule> rules = new ConcurrentHashMap<>();

    @Override
    public void save(Rule rule) {
        rules.put(rule.name(), rule);
    }

    @Override
    public Optional<Rule> find(String name) {
        return Optional.ofNullable(rules.get(name));
    }

    @Override
    public Map<String, Rule> findAll() {
        return Map.copyOf(rules);
    }

    @Override
    public void delete(String name) {
        rules.remove(name);
    }
}

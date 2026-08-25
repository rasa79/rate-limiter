package com.ratelimiter.service.rules;

import java.util.Map;
import java.util.Optional;

/**
 * Persistence for rate-limit rules — the source of truth behind the cache.
 *
 * <p>The check path never reads this directly; it reads {@link RuleCache}, which
 * refreshes from the store on a TTL or an invalidation. Implementations:
 * {@code ValkeyRuleStore} (Valkey hash, production) and {@code LocalRuleStore}
 * (in-memory, tests/dev).
 */
public interface RuleStore {

    /**
     * Upserts a rule.
     *
     * @param rule the rule to persist
     */
    void save(Rule rule);

    /**
     * @param name the rule name
     * @return the rule, or empty if unknown
     */
    Optional<Rule> find(String name);

    /**
     * @return all rules keyed by name
     */
    Map<String, Rule> findAll();

    /**
     * Deletes a rule.
     *
     * @param name the rule name
     */
    void delete(String name);
}

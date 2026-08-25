package com.ratelimiter.service.api;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ratelimiter.service.pubsub.RuleInvalidationPublisher;
import com.ratelimiter.service.rules.Rule;
import com.ratelimiter.service.rules.RuleCache;
import com.ratelimiter.service.rules.RuleStore;
import com.ratelimiter.service.rules.RuleValidator;

/**
 * Admin REST API for managing rules at runtime.
 *
 * <p>A rule change is applied without any restart: it is written to the
 * {@link RuleStore} (source of truth), the local {@link RuleCache} is invalidated
 * immediately, and — in valkey mode — an invalidation is published so every other
 * instance drops its stale copy too.
 */
@RestController
@RequestMapping("/v1/rules")
public class AdminController {

    private final RuleStore store;
    private final RuleCache cache;
    private final RuleValidator validator;
    private final ObjectProvider<RuleInvalidationPublisher> publisher;

    /**
     * @param store the source-of-truth rule store
     * @param cache the rule cache to invalidate on write
     * @param validator validates incoming rules
     * @param publisher (valkey mode) cross-instance invalidation; absent in in-memory
     */
    public AdminController(RuleStore store, RuleCache cache, RuleValidator validator,
            ObjectProvider<RuleInvalidationPublisher> publisher) {
        this.store = store;
        this.cache = cache;
        this.validator = validator;
        this.publisher = publisher;
    }

    /**
     * Creates or replaces a rule.
     *
     * @param name the rule name (from the path)
     * @param request the rule definition
     * @return the persisted rule
     */
    @PutMapping("/{name}")
    public Rule put(@PathVariable String name, @RequestBody RuleUpdateRequest request) {
        Rule rule = new Rule(name, request.algorithm(), request.limit(),
                request.refillPerSecond(), request.windowMillis());
        validator.validate(rule);
        store.save(rule);
        cache.invalidate(name);
        RuleInvalidationPublisher pub = publisher.getIfAvailable();
        if (pub != null) {
            pub.publish(name);
        }
        return rule;
    }

    /**
     * @param name the rule name
     * @return the rule
     * @throws RuleNotFoundException if the rule does not exist (mapped to 404)
     */
    @GetMapping("/{name}")
    public Rule get(@PathVariable String name) {
        return cache.resolve(name).orElseThrow(() -> new RuleNotFoundException(name));
    }

    /**
     * @return all rules keyed by name
     */
    @GetMapping
    public Map<String, Rule> list() {
        return cache.all();
    }

    /**
     * Deletes a rule.
     *
     * @param name the rule name
     * @return 204 No Content
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        store.delete(name);
        cache.invalidate(name);
        RuleInvalidationPublisher pub = publisher.getIfAvailable();
        if (pub != null) {
            pub.publish(name);
        }
        return ResponseEntity.noContent().build();
    }
}

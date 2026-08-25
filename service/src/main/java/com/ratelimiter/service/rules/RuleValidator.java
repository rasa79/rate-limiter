package com.ratelimiter.service.rules;

import org.springframework.stereotype.Component;

/**
 * Validates a {@link Rule} before it is persisted via the admin API (and is the
 * source of the 400 response for malformed rules).
 */
@Component
public class RuleValidator {

    /**
     * Validates a rule, throwing {@link IllegalArgumentException} on violation.
     *
     * @param rule the rule to validate
     * @throws IllegalArgumentException if the rule is malformed
     */
    public void validate(Rule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule is required");
        }
        if (rule.algorithm() == null) {
            throw new IllegalArgumentException("algorithm is required");
        }
        if (rule.limit() <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        switch (rule.algorithm()) {
            case TOKEN_BUCKET -> {
                if (rule.refillPerSecond() <= 0) {
                    throw new IllegalArgumentException("refillPerSecond must be > 0 for TOKEN_BUCKET");
                }
            }
            case SLIDING_WINDOW -> {
                if (rule.windowMillis() <= 0) {
                    throw new IllegalArgumentException("windowMillis must be > 0 for SLIDING_WINDOW");
                }
            }
        }
    }
}

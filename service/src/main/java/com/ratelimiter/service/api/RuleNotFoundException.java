package com.ratelimiter.service.api;

/**
 * Thrown when an admin operation references a rule that does not exist.
 */
public class RuleNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param ruleName the missing rule name
     */
    public RuleNotFoundException(String ruleName) {
        super("rule not found: " + ruleName);
    }
}

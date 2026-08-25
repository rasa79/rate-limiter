package com.ratelimiter.service.api;

/**
 * Thrown when a {@code /v1/check} request names a rule that is not defined.
 */
public class UnknownRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String ruleName;

    /**
     * @param ruleName the unknown rule name
     */
    public UnknownRuleException(String ruleName) {
        super("unknown rule: " + ruleName);
        this.ruleName = ruleName;
    }

    /**
     * @return the unknown rule name
     */
    public String ruleName() {
        return ruleName;
    }
}

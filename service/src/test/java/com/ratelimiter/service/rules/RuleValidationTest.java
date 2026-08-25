package com.ratelimiter.service.rules;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RuleValidator}.
 */
class RuleValidationTest {

    private final RuleValidator validator = new RuleValidator();

    @Test
    void rejectsMalformedRule() {
        // Unknown/missing algorithm.
        assertThatThrownBy(() -> validator.validate(new Rule("r", null, 10, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);

        // Non-positive limit.
        assertThatThrownBy(() -> validator.validate(Rule.tokenBucket("r", 0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(Rule.slidingWindow("r", -1, 1000)))
                .isInstanceOf(IllegalArgumentException.class);

        // Token bucket requires a positive refill rate.
        assertThatThrownBy(() -> validator.validate(Rule.tokenBucket("r", 10, 0)))
                .isInstanceOf(IllegalArgumentException.class);

        // Sliding window requires a positive window.
        assertThatThrownBy(() -> validator.validate(Rule.slidingWindow("r", 10, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

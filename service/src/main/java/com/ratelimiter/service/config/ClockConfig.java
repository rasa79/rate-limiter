package com.ratelimiter.service.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the single {@link Clock} the service uses to timestamp rate-limit
 * decisions.
 *
 * <p>// RATIONALE: the algorithms themselves never read a clock (they receive
 * {@code nowMillis}); the clock lives at the HTTP boundary where we translate
 * "now" into the injected millis the algorithm needs. Keeping the clock here —
 * rather than inside the algorithm — is what makes the algorithm deterministic,
 * and it lets integration tests replace this bean with a fixed clock (a
 * {@code @Primary} test bean) so the whole request path is reproducible.
 */
@Configuration
public class ClockConfig {

    /**
     * The default, real-time clock.
     *
     * @return a system-zone clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}

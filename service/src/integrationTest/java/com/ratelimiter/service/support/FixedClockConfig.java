package com.ratelimiter.service.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test-only {@link Clock} used by the integration suite to make the whole check
 * path deterministic.
 *
 * <p>It is a {@code @Primary} {@code Clock} bean so it wins over the production
 * {@link com.ratelimiter.service.config.ClockConfig#clock()} bean. It is
 * imported explicitly (not left to the nested-{@code @TestConfiguration}
 * auto-detection, which Spring is deprecating) and being a
 * {@code @TestConfiguration} it is excluded from the application's component
 * scan.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfig {

    /** A fixed instant, chosen arbitrarily to be far from epoch boundaries. */
    private static final long FIXED_MILLIS = 1_800_000_000_000L;

    /**
     * @return a clock pinned to a fixed instant in UTC
     */
    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC);
    }
}

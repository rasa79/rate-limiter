package com.ratelimiter.service.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Central Micrometer instrumentation for the rate-limiter service.
 *
 * <p>Metrics names are chosen to be Prometheus-friendly; all per-request meters are
 * tagged by the {@code rule} only — never by the {@code key} — so cardinality is
 * bounded regardless of how many distinct clients exist.
 */
@Component
public class Metrics {

    private final MeterRegistry registry;
    private final Timer valkeyLatency;

    /**
     * @param registry the Micrometer registry (Prometheus in deployment)
     */
    public Metrics(MeterRegistry registry) {
        this.registry = registry;
        this.valkeyLatency = Timer.builder("ratelimiter_valkey_latency")
                .description("Latency of a single rate-limit store decision")
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * Records a check outcome, tagged by rule and result.
     *
     * @param rule the rule name (never the key, to keep cardinality bounded)
     * @param result {@code allowed}, {@code rejected}, or {@code error}
     */
    public void recordCheck(String rule, String result) {
        registry.counter("ratelimiter_checks_total", "rule", rule, "result", result).increment();
    }

    /**
     * Records that a store operation failed (e.g. Valkey unreachable).
     */
    public void recordStoreError() {
        registry.counter("ratelimiter_redis_unavailable_total").increment();
    }

    /**
     * @return the Valkey latency timer (callers wrap the store call)
     */
    public Timer valkeyLatency() {
        return valkeyLatency;
    }

    /**
     * Records an admin API request duration.
     *
     * @param method the HTTP method (PUT/GET/DELETE)
     * @param duration the duration
     */
    public void recordAdmin(String method, Duration duration) {
        registry.timer("ratelimiter_admin_requests", "method", method).record(duration);
    }
}

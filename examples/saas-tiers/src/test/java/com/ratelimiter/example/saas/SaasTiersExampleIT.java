package com.ratelimiter.example.saas;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.RateLimiterApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Example 1 acceptance: a Free-tier tenant hits its limit (429), the mock billing
 * service upgrades the tier via the admin API (runtime change, no restart), and
 * the SAME client immediately succeeds (200).
 *
 * <p>It boots the real rate-limiter service (in-memory store, no container) and
 * the example app, pointing the filter at the service. Config is passed
 * explicitly so each context is self-contained (the two modules each ship an
 * {@code application.yml} on a shared test classpath).
 */
class SaasTiersExampleIT {

    @Test
    void freeTierHitLimit_thenUpgrade_thenWorks() {
        // Config is passed as command-line args (highest precedence) so neither
        // context is affected by whichever shared application.yml happens to load.
        ConfigurableApplicationContext service = new SpringApplicationBuilder(RateLimiterApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.application.name=rate-limiter-service",
                        "--ratelimiter.store=in-memory",
                        "--ratelimiter.rules.free.name=free",
                        "--ratelimiter.rules.free.algorithm=TOKEN_BUCKET",
                        "--ratelimiter.rules.free.limit=5",
                        "--ratelimiter.rules.free.refill-per-second=1");
        int servicePort = ((ServletWebServerApplicationContext) service).getWebServer().getPort();

        ConfigurableApplicationContext app = new SpringApplicationBuilder(SaasTiersApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.application.name=saas-tiers-example",
                        "--ratelimiter.filter.service-url=http://localhost:" + servicePort,
                        "--ratelimiter.filter.default-rule=free",
                        "--ratelimiter.filter.key-header=X-Api-Key",
                        "--ratelimiter.filter.rule-header=X-Rate-Limit-Rule");
        int appPort = ((ServletWebServerApplicationContext) app).getWebServer().getPort();

        try {
            RestClient client = RestClient.builder().baseUrl("http://localhost:" + appPort).build();

            // Free tier (default rule 'free', limit 5): the first five pass, the sixth 429s.
            for (int i = 0; i < 5; i++) {
                assertThat(getStatus(client, "/api/data")).isEqualTo(200);
            }
            assertThat(getStatus(client, "/api/data")).isEqualTo(429);

            // Upgrade the tenant's tier: billing calls the admin API to raise the 'free' limit.
            client.post().uri("/api/billing/upgrade/tenant1").retrieve().toBodilessEntity();

            // Same client, same rule: now allowed (limit raised at runtime, no restart).
            assertThat(getStatus(client, "/api/data")).isEqualTo(200);
        } finally {
            app.close();
            service.close();
        }
    }

    private int getStatus(RestClient client, String path) {
        ResponseEntity<Void> response = client.get().uri(path)
                .header("X-Api-Key", "tenant1")
                .retrieve()
                .onStatus(code -> true, (request, resp) -> {
                })
                .toBodilessEntity();
        return response.getStatusCode().value();
    }
}

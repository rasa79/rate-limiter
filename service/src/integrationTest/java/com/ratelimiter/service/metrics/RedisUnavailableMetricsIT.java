package com.ratelimiter.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.support.FixedClockConfig;
import com.ratelimiter.service.support.ValkeyContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the {@code redis_unavailable} counter increments when Valkey goes away:
 * a request that can no longer reach the store is recorded as an error without the
 * JVM being affected.
 *
 * <p>Uses its own dedicated container (the other tests share one container and
 * must not have it stopped out from under them).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig.class)
class RedisUnavailableMetricsIT {

    private static final String STORE_IMAGE =
            System.getenv().getOrDefault("RATE_LIMITER_IMAGE", "valkey/valkey:8");

    @Container
    private static GenericContainer<?> valkey =
            new ValkeyContainer(STORE_IMAGE).withFixedPort(26380, 6379);

    @LocalServerPort
    private int port;

    private RestClient http;

    @DynamicPropertySource
    static void valkeyProperties(DynamicPropertyRegistry registry) {
        registry.add("ratelimiter.store", () -> "valkey");
        registry.add("ratelimiter.valkey.host", () -> "localhost");
        registry.add("ratelimiter.valkey.port", () -> valkey.getMappedPort(6379));
    }

    @BeforeEach
    void initHttpClient() {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void redisUnavailableCounterIncrementsWhenValkeyDown() {
        // Warm path: create a rule and make a successful check.
        http.put().uri("/v1/rules/m9-unavail")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":100,\"refillPerSecond\":1,\"windowMillis\":0}")
                .retrieve().toBodilessEntity();
        assertThat(postCheck("m9-unavail-key", "m9-unavail")).isNotZero();

        double before = counter("ratelimiter_redis_unavailable_total");

        // Kill Valkey, then issue a check: it must fail (500 -> error) and bump the counter.
        valkey.stop();
        try {
            assertThat(postCheck("m9-unavail-key", "m9-unavail")).isEqualTo(500);
        } finally {
            valkey.start();
        }

        double after = counter("ratelimiter_redis_unavailable_total");
        assertThat(after).isGreaterThan(before);
    }

    private int postCheck(String key, String rule) {
        return http.post().uri("/v1/check")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"key\":\"" + key + "\",\"rule\":\"" + rule + "\"}")
                .retrieve()
                .onStatus(code -> true, (req, resp) -> {
                })
                .toBodilessEntity()
                .getStatusCode().value();
    }

    private double counter(String metric) {
        String body = http.get().uri("/actuator/prometheus")
                .retrieve().toEntity(String.class).getBody();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(metric + " ")) {
                return Double.parseDouble(trimmed.split("\\s+")[trimmed.split("\\s+").length - 1]);
            }
        }
        return 0.0;
    }
}

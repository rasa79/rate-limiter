package com.ratelimiter.service.valkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.ratelimiter.service.support.FixedClockConfig;
import com.ratelimiter.service.support.ValkeyContainer;
import tools.jackson.databind.JsonNode;

/**
 * Proves the M4 readiness contract with the Valkey backend: readiness is UP while
 * Valkey is reachable, flips DOWN when it is stopped, and returns UP on restart.
 *
 * <p>This test uses its OWN dedicated container with a fixed host port (see
 * {@link ValkeyContainer}) because stopping/restarting the shared container would
 * disturb the other tests, and a randomized mapped port would leave the
 * application pointing at a stale port after a restart.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig.class)
class ValkeyReadinessChangeIT {

    private static final String STORE_IMAGE =
            System.getenv().getOrDefault("RATE_LIMITER_IMAGE", "valkey/valkey:8");

    @Container
    private static GenericContainer<?> readinessValkey =
            new ValkeyContainer(STORE_IMAGE).withFixedPort(26379, 6379);

    @LocalServerPort
    private int port;

    private RestClient http;

    @DynamicPropertySource
    static void valkeyProperties(DynamicPropertyRegistry registry) {
        registry.add("ratelimiter.store", () -> "valkey");
        registry.add("ratelimiter.valkey.host", () -> "localhost");
        registry.add("ratelimiter.valkey.port", () -> readinessValkey.getMappedPort(6379));
    }

    @BeforeEach
    void initHttpClient() {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void readinessFlipsDownWhenValkeyStops_thenUpWhenRestarts() throws Exception {
        assertThat(readiness()).isEqualTo("UP");

        readinessValkey.stop();
        await(() -> readiness().equals("DOWN"), 30_000);

        readinessValkey.start();
        // A fresh container lost BOTH the Lua scripts AND the bootstrap rules
        // (the rule store lives in Valkey). Retry the re-seed and a check each
        // poll: the re-seed writes the rule, the check re-registers the scripts
        // via the EVALSHA -> NOSCRIPT -> EVAL fallback, after which the 'scripts
        // loaded' readiness condition is satisfied.
        await(() -> {
            try {
                putStandardRule();
            } catch (RuntimeException ignored) {
                // connection may still be re-establishing; retry next poll
            }
            try {
                check();
            } catch (RuntimeException ignored) {
                // connection may still be re-establishing; retry next poll
            }
            return readiness().equals("UP");
        }, 60_000);
    }

    private void putStandardRule() {
        http.put().uri("/v1/rules/standard")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":100,\"refillPerSecond\":10,\"windowMillis\":0}")
                .retrieve().onStatus(code -> true, (request, resp) -> {
                }).toBodilessEntity();
    }

    private void check() {
        http.post().uri("/v1/check")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"key\":\"rt-key\",\"rule\":\"standard\"}")
                .retrieve()
                .onStatus(code -> true, (request, resp) -> {
                })
                .toBodilessEntity();
    }

    private String readiness() {
        ResponseEntity<JsonNode> response = http.get().uri("/actuator/health/readiness")
                .retrieve()
                .onStatus(code -> true, (request, resp) -> {
                })
                .toEntity(JsonNode.class);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        return body.get("status").asText();
    }

    private void await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("condition not met within " + timeoutMillis + " ms");
    }
}

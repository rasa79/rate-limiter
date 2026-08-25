package com.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The HTTP contract tests re-run against the sliding-window algorithm, through
 * the real Valkey-backed store (sorted-set Lua script).
 *
 * <p>Time is fixed by the base class, so all requests land at the same instant;
 * the rolling window still enforces the per-key limit atomically.
 */
class SlidingWindowCheckApiIT extends ValkeyIntegrationTestBase {

    private ResponseEntity<JsonNode> post(String body) {
        return http.post().uri("/v1/check")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(code -> true, (request, response) -> {
                })
                .toEntity(JsonNode.class);
    }

    @Test
    void exactlyAtLimit_thenRejected_viaSlidingWindow() {
        for (int i = 0; i < 5; i++) {
            ResponseEntity<JsonNode> response =
                    post("{\"key\":\"sw-client\",\"rule\":\"burst\"}");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("allowed").asBoolean()).isTrue();
        }

        ResponseEntity<JsonNode> rejected =
                post("{\"key\":\"sw-client\",\"rule\":\"burst\"}");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getBody().get("allowed").asBoolean()).isFalse();
        assertThat(rejected.getBody().get("retry_after_seconds").asLong()).isGreaterThan(0);
    }

    @Test
    void independentKeysHaveIndependentWindows() {
        for (int i = 0; i < 5; i++) {
            assertThat(post("{\"key\":\"sw-a\",\"rule\":\"burst\"}").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(post("{\"key\":\"sw-a\",\"rule\":\"burst\"}").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        for (int i = 0; i < 5; i++) {
            assertThat(post("{\"key\":\"sw-b\",\"rule\":\"burst\"}").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(post("{\"key\":\"sw-b\",\"rule\":\"burst\"}").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}

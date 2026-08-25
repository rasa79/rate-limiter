package com.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The M3 check contract re-run against the real Valkey-backed store: the request
 * travels HTTP -> controller -> Lettuce -> Lua -> Valkey -> response.
 *
 * <p>Time is fixed by the base class, so the token budget is deterministic; the
 * store's per-key state lives in shared Valkey rather than the instance, so the
 * limit is enforced atomically and exactly.
 */
class ValkeyCheckApiIT extends ValkeyIntegrationTestBase {

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
    void underLimit200_overLimit429_throughFullStack() {
        for (int i = 0; i < 5; i++) {
            ResponseEntity<JsonNode> response =
                    post("{\"key\":\"vk-full\",\"rule\":\"strict\"}");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("allowed").asBoolean()).isTrue();
        }

        ResponseEntity<JsonNode> rejected =
                post("{\"key\":\"vk-full\",\"rule\":\"strict\"}");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getBody().get("allowed").asBoolean()).isFalse();
        assertThat(rejected.getBody().get("retry_after_seconds").asLong()).isGreaterThan(0);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    @Test
    void independentKeysHaveIndependentLimits() {
        // Drain key A completely...
        for (int i = 0; i < 5; i++) {
            assertThat(post("{\"key\":\"vk-a\",\"rule\":\"strict\"}").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(post("{\"key\":\"vk-a\",\"rule\":\"strict\"}").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // ...but key B starts with its own full budget: all five are allowed and
        // the sixth still hits its OWN limit, not A's.
        for (int i = 0; i < 5; i++) {
            assertThat(post("{\"key\":\"vk-b\",\"rule\":\"strict\"}").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(post("{\"key\":\"vk-b\",\"rule\":\"strict\"}").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}

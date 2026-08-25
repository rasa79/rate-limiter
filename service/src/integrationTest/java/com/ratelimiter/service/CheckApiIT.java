package com.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.support.IntegrationTestBase;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end tests for {@code POST /v1/check} against the real HTTP server.
 *
 * <p>These go HTTP → controller → (in-memory) store → algorithm → response. The
 * store is the in-memory tracer-bullet (M3); the assertions read the raw
 * {@code JsonNode} so they verify the exact wire contract (snake_case keys):
 * {@code {allowed, remaining, reset_at, retry_after_seconds}}. Time is fixed by
 * the base class, so the token budget is fully deterministic.
 */
class CheckApiIT extends IntegrationTestBase {

    /**
     * POSTs the given body and returns the response as JSON, without throwing on
     * non-2xx so 429/400 responses can be asserted.
     */
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
    void underLimitReturns200WithRemaining() {
        ResponseEntity<JsonNode> response =
                post("{\"key\":\"client-1\",\"rule\":\"strict\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("allowed").asBoolean()).isTrue();
        // capacity is 5, this first request consumed one token.
        assertThat(body.get("remaining").asDouble()).isEqualTo(4.0);
        assertThat(body.has("reset_at")).isTrue();
        assertThat(body.get("retry_after_seconds").asLong()).isZero();
    }

    @Test
    void overLimitReturns429WithPositiveRetryAfter() {
        ResponseEntity<JsonNode> last = null;
        for (int i = 0; i < 5; i++) {
            last = post("{\"key\":\"client-2\",\"rule\":\"strict\"}");
            assertThat(last.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<JsonNode> rejected =
                post("{\"key\":\"client-2\",\"rule\":\"strict\"}");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        JsonNode body = rejected.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("allowed").asBoolean()).isFalse();
        assertThat(body.get("retry_after_seconds").asLong()).isGreaterThan(0);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    @Test
    void malformedBodyReturns400() {
        ResponseEntity<JsonNode> response = post("{not-valid-json");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code").asText()).isEqualTo("bad_request");
    }

    @Test
    void unknownRuleReturns400() {
        ResponseEntity<JsonNode> response =
                post("{\"key\":\"client-3\",\"rule\":\"does-not-exist\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code").asText()).isEqualTo("unknown_rule");
    }
}

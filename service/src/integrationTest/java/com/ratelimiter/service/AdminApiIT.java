package com.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end tests for the rule admin API (CRUD + validation) against the real
 * Valkey-backed store.
 */
class AdminApiIT extends ValkeyIntegrationTestBase {

    private ResponseEntity<JsonNode> put(String url, String body) {
        return http.put().uri(url).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(code -> true, (request, resp) -> {
                }).toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String url) {
        return http.get().uri(url).retrieve()
                .onStatus(code -> true, (request, resp) -> {
                }).toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> delete(String url) {
        return http.delete().uri(url).retrieve()
                .onStatus(code -> true, (request, resp) -> {
                }).toEntity(JsonNode.class);
    }

    @Test
    void crudRoundTrip() {
        ResponseEntity<JsonNode> created = put("/v1/rules/op-1",
                "{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":10,\"refillPerSecond\":2,\"windowMillis\":0}");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().get("limit").asDouble()).isEqualTo(10.0);

        ResponseEntity<JsonNode> fetched = get("/v1/rules/op-1");
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("algorithm").asText()).isEqualTo("TOKEN_BUCKET");

        ResponseEntity<JsonNode> list = get("/v1/rules");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> deleted = delete("/v1/rules/op-1");
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get("/v1/rules/op-1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void validationErrorsReturn400() {
        ResponseEntity<JsonNode> bad = put("/v1/rules/op-bad",
                "{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":-5,\"refillPerSecond\":1,\"windowMillis\":0}");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

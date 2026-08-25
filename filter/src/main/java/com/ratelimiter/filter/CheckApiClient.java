package com.ratelimiter.filter;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * The real {@link RateLimitChecker}: calls {@code POST <serviceUrl>/v1/check}.
 *
 * <p>Both a 200 (allowed) and a 429 (rejected) carry the same contract body, so
 * this client treats 429 as a normal "not allowed" result rather than an error;
 * any other status, or a connection failure, is surfaced as a
 * {@link RuntimeException} for the filter to fail-open (or fail-closed) on.
 */
public class CheckApiClient implements RateLimitChecker {

    private final RestClient restClient;
    private final FilterProperties properties;

    /**
     * @param restClient a client bound to the rate-limiter base URL
     * @param properties the filter configuration
     */
    public CheckApiClient(RestClient restClient, FilterProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public CheckResult check(String key, String rule) {
        ResponseEntity<JsonNode> response = restClient.post()
                .uri(properties.getCheckPath())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("key", key, "rule", rule))
                .retrieve()
                .onStatus(code -> code.isError(), (request, resp) -> {
                })
                .toEntity(JsonNode.class);
        JsonNode body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("rate limiter returned an empty body");
        }
        boolean allowed = body.path("allowed").asBoolean(false);
        long retryAfter = body.path("retry_after_seconds").asLong(allowed ? 0 : 1);
        return new CheckResult(allowed, retryAfter);
    }
}

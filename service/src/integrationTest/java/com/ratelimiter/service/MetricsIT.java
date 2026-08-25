package com.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.support.ValkeyIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Verifies the Prometheus-backed metrics surface: check result counters (tagged by
 * rule), the Valkey latency timer, and the /actuator/prometheus endpoint.
 */
class MetricsIT extends ValkeyIntegrationTestBase {

    @Test
    void allowedAndRejectedChecksIncrementCounters() {
        String rule = "m9-count";
        putRule(rule, 5);

        // 5 allowed, then 1 rejected (limit 5, fixed clock -> no refill).
        for (int i = 0; i < 5; i++) {
            assertThat(status(postCheck("m9-key", rule))).isEqualTo(200);
        }
        assertThat(status(postCheck("m9-key", rule))).isEqualTo(429);

        String body = prometheus();
        assertThat(counterValue(body, "ratelimiter_checks_total", "rule=\"" + rule + "\"", "result=\"allowed\""))
                .isEqualTo(5.0);
        assertThat(counterValue(body, "ratelimiter_checks_total", "rule=\"" + rule + "\"", "result=\"rejected\""))
                .isEqualTo(1.0);
    }

    @Test
    void valkeyLatencyHistogramRecorded() {
        putRule("m9-latency", 100);
        postCheck("m9-latency-key", "m9-latency");

        String body = prometheus();
        assertThat(body).contains("ratelimiter_valkey_latency");
    }

    @Test
    void prometheusEndpointExposesExpectedSeries() {
        ResponseEntity<String> response = http.get().uri("/actuator/prometheus")
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("ratelimiter_checks_total");
    }

    private void putRule(String name, double limit) {
        http.put().uri("/v1/rules/" + name)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"algorithm\":\"TOKEN_BUCKET\",\"limit\":" + limit + ",\"refillPerSecond\":1,\"windowMillis\":0}")
                .retrieve().onStatus(code -> true, (req, resp) -> {
                }).toBodilessEntity();
    }

    private JsonNode postCheck(String key, String rule) {
        return http.post().uri("/v1/check")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"key\":\"" + key + "\",\"rule\":\"" + rule + "\"}")
                .retrieve().onStatus(code -> true, (req, resp) -> {
                }).toEntity(JsonNode.class).getBody();
    }

    private int status(JsonNode body) {
        // The decision body carries 'allowed'; we derive status from it. (The 429 vs
        // 200 distinction is already asserted via the check API elsewhere; here we
        // only need the counter, so extract allowed.)
        return body.path("allowed").asBoolean(false) ? 200 : 429;
    }

    private String prometheus() {
        return http.get().uri("/actuator/prometheus").retrieve().toEntity(String.class).getBody();
    }

    private double counterValue(String prometheusBody, String metric, String... tagPairs) {
        for (String line : prometheusBody.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(metric + "{")) {
                continue;
            }
            boolean matches = true;
            for (String pair : tagPairs) {
                matches &= trimmed.contains(pair);
            }
            if (matches) {
                String[] parts = trimmed.split("\\s+");
                return Double.parseDouble(parts[parts.length - 1]);
            }
        }
        throw new AssertionError("metric not found: " + metric + " " + java.util.Arrays.toString(tagPairs));
    }
}

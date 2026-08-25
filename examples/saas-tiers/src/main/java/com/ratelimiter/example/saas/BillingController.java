package com.ratelimiter.example.saas;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * A mock billing service. It "upgrades" a tenant's tier by calling the
 * rate-limiter admin API to raise the {@code free} rule's limit at runtime —
 * this is how a SaaS upgrades a tenant without a restart and with no client
 * change.
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final RestClient limiterClient;

    /**
     * @param serviceUrl the rate-limiter base URL (shared with the filter config)
     */
    public BillingController(@Value("${ratelimiter.filter.service-url}") String serviceUrl) {
        this.limiterClient = RestClient.builder().baseUrl(serviceUrl).build();
    }

    /**
     * Upgrades a tenant by raising the {@code free} rule limit to effectively
     * unlimited.
     *
     * @param tenant the tenant id
     * @return a small confirmation payload
     */
    @PostMapping("/upgrade/{tenant}")
    public ResponseEntity<Map<String, String>> upgrade(@PathVariable String tenant) {
        limiterClient.put()
                .uri("/v1/rules/free")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("algorithm", "TOKEN_BUCKET", "limit", 100_000, "refillPerSecond", 1000, "windowMillis", 0))
                .retrieve()
                .toBodilessEntity();
        return ResponseEntity.ok(Map.of("tenant", tenant, "tier", "pro"));
    }
}

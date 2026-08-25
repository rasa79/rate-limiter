package com.ratelimiter.service.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ratelimiter.service.algorithm.Decision;

/**
 * The response body for a {@code POST /v1/check} request (returned for both the
 * 200-allowed and 429-rejected cases).
 *
 * <p>The wire contract is {@code {allowed, remaining, reset_at,
 * retry_after_seconds}}; the two multi-word keys are pinned explicitly with
 * {@link JsonProperty} so the contract holds regardless of the configured naming
 * strategy.
 *
 * @param allowed whether the request was permitted
 * @param remaining the number of units still available after this request
 * @param resetAt epoch-millis at which the quota is next fully available
 * @param retryAfterSeconds how many seconds to wait before the requested units
 *                          are available ({@code 0} when allowed)
 */
public record CheckResponse(
        boolean allowed,
        double remaining,
        @JsonProperty("reset_at") long resetAt,
        @JsonProperty("retry_after_seconds") long retryAfterSeconds) {

    /**
     * Wraps an algorithm decision into the HTTP response shape.
     *
     * @param decision the algorithm decision
     * @return a response carrying that decision
     */
    public static CheckResponse from(Decision decision) {
        return new CheckResponse(
                decision.allowed(),
                decision.remaining(),
                decision.resetAtMillis(),
                decision.retryAfterSeconds());
    }
}

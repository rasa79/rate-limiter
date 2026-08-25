package com.ratelimiter.service.api;

/**
 * The body of a {@code POST /v1/check} request.
 *
 * @param key the rate-limit key (e.g. an API key or tenant id) whose state is
 *            evaluated
 * @param rule the name of the rule to enforce against {@code key}
 */
public record CheckRequest(String key, String rule) {
}

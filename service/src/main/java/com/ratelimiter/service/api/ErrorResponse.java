package com.ratelimiter.service.api;

/**
 * A compact error body for non-429 failures (malformed bodies, unknown rules).
 *
 * @param code a stable machine-readable error code
 * @param message a human-readable description
 */
public record ErrorResponse(String code, String message) {
}

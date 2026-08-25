package com.ratelimiter.filter;

/**
 * The outcome of a rate-limit decision as seen by the client/filter.
 *
 * @param allowed whether the request should be permitted
 * @param retryAfterSeconds how many seconds to back off (relevant when rejected)
 */
public record CheckResult(boolean allowed, long retryAfterSeconds) {
}

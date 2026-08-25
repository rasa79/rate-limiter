package com.ratelimiter.service.rules;

/**
 * A rate-limit rule definition, identified by a name and fully self-describing.
 *
 * <p>For M3 the rule is the (immutable) configuration fed to the token-bucket
 * algorithm: how many tokens the bucket holds ({@code capacity}) and how fast it
 * refills ({@code refillPerSecond}). Later milestones extend this record with an
 * explicit algorithm selector (M5) and a persisted store with hot reload (M6),
 * but the shape used by the {@code /v1/check} path stays name-keyed.
 *
 * @param name the unique rule name (used to resolve it from a request)
 * @param capacity the maximum number of tokens the bucket can hold
 * @param refillPerSecond the continuous refill rate, in tokens per second
 */
public record Rule(String name, double capacity, double refillPerSecond) {
}

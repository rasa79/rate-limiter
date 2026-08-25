package com.ratelimiter.service.rules;

/**
 * A rate-limit rule, identified by a name, tagged with an {@link Algorithm}, and
 * carrying the parameters that algorithm needs.
 *
 * <p>For {@link Algorithm#TOKEN_BUCKET}: {@code limit} is the bucket capacity and
 * {@code refillPerSecond} the continuous refill rate ({@code windowMillis} is
 * unused). For {@link Algorithm#SLIDING_WINDOW}: {@code limit} is the max events
 * per window and {@code windowMillis} the rolling window ({@code refillPerSecond}
 * is unused).</p>
 *
 * <p>Users construct rules via the named factories so the parameters that belong
 * to the other algorithm are set to neutral values.
 *
 * @param name the unique rule name
 * @param algorithm the algorithm that enforces the rule
 * @param limit the capacity (token bucket) or max events per window (sliding window)
 * @param refillPerSecond the refill rate in tokens/second (token bucket only)
 * @param windowMillis the rolling window in millis (sliding window only)
 */
public record Rule(String name, Algorithm algorithm, double limit, double refillPerSecond, long windowMillis) {

    /**
     * Creates a token-bucket rule.
     *
     * @param name the rule name
     * @param capacity the bucket capacity
     * @param refillPerSecond the continuous refill rate (tokens/second)
     * @return a token-bucket rule
     */
    public static Rule tokenBucket(String name, double capacity, double refillPerSecond) {
        return new Rule(name, Algorithm.TOKEN_BUCKET, capacity, refillPerSecond, 0);
    }

    /**
     * Creates a sliding-window rule.
     *
     * @param name the rule name
     * @param limit the maximum events per window
     * @param windowMillis the rolling window length
     * @return a sliding-window rule
     */
    public static Rule slidingWindow(String name, double limit, long windowMillis) {
        return new Rule(name, Algorithm.SLIDING_WINDOW, limit, 0, windowMillis);
    }
}

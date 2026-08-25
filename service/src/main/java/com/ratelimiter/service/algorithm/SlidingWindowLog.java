package com.ratelimiter.service.algorithm;

import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A log-based sliding-window rate-limiter, fully time-injected.
 *
 * <p>Each request appends an event timestamp to a log; an event is "in the
 * window" at time {@code now} if {@code t >= now - windowMillis}. A request is
 * allowed only if the number of in-window events is below {@code limit} — so at
 * most {@code limit} events are ever allowed in any rolling window of length
 * {@code windowMillis}. This is exactly what fixed windows cannot express (e.g.,
 * a rolling daily upstream quota).
 *
 * <p>// RATIONALE: the state is a {@link SortedMap} of timestamp to per-timestamp
 * count. Counting identical timestamps separately matches the Lua script, which
 * stores one unique member per request (so two requests in the same millisecond
 * both count). Time is injected (never read from a clock) so the class is
 * deterministic and can be diff-tested against the Lua script.
 */
public final class SlidingWindowLog {

    private final double limit;
    private final long windowMillis;

    /**
     * @param limit the maximum events allowed in any window
     * @param windowMillis the rolling window length
     */
    public SlidingWindowLog(double limit, long windowMillis) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0, was " + limit);
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be > 0, was " + windowMillis);
        }
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    /**
     * Evaluates a request.
     *
     * @param events the current per-timestamp event counts (may be empty)
     * @param nowMillis the injected current time
     * @return the decision and the updated event counts
     */
    public Result consume(NavigableMap<Long, Integer> events, long nowMillis) {
        // Keep only events within the window: t >= now - windowMillis.
        // (Matches ZREMRANGEBYSCORE key -inf "(now-window)" which is exclusive of
        // the lower bound, i.e. keeps scores >= now-window.)
        NavigableMap<Long, Integer> inWindow = new TreeMap<>(events.tailMap(nowMillis - windowMillis, true));

        long count = inWindow.values().stream().mapToLong(Integer::longValue).sum();
        boolean allowed = count < limit;
        if (allowed) {
            inWindow.merge(nowMillis, 1, Integer::sum);
            count++;
        }

        double remaining = allowed ? limit - count : 0;
        long oldest = inWindow.isEmpty() ? -1 : inWindow.firstKey();

        long resetAt;
        long retryAfter = 0;
        if (oldest >= 0) {
            resetAt = oldest + windowMillis;
            if (!allowed) {
                long millis = (oldest + windowMillis) - nowMillis;
                retryAfter = Math.max(1, (long) Math.ceil(millis / 1000.0));
            }
        } else {
            resetAt = nowMillis;
        }

        return new Result(new Decision(allowed, remaining, resetAt, retryAfter), inWindow);
    }

    /**
     * The result of a decision: the {@link Decision} and the updated window state.
     *
     * @param decision the rate-limit decision
     * @param state the updated per-timestamp event counts
     */
    public record Result(Decision decision, NavigableMap<Long, Integer> state) {
    }
}

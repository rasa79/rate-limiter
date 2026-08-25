package com.ratelimiter.service.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Deterministic, clock-free unit tests for {@link SlidingWindowLog}.
 *
 * <p>Time is injected as {@code nowMillis}, and the window logic is purely
 * time-based — there is no calendar/session notion, so boundaries (including UTC
 * midnight) behave purely as arithmetic on a timestamp.
 */
class SlidingWindowLogTest {

    @Test
    void rejectsAtExactWindowBoundary() {
        SlidingWindowLog log = new SlidingWindowLog(5, 1000);
        NavigableMap<Long, Integer> events = new TreeMap<>();
        long t0 = 1_000_000L;

        // Fill the window with 5 events at t0.
        for (int i = 0; i < 5; i++) {
            events = log.consume(events, t0).state();
        }

        // At exactly t0 + window, t0 is still in the window (t >= now-window),
        // so the 6th request is rejected.
        SlidingWindowLog.Result r = log.consume(events, t0 + 1000);
        assertThat(r.decision().allowed()).isFalse();
        assertThat(r.decision().retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void entriesExpireAsWindowSlides() {
        SlidingWindowLog log = new SlidingWindowLog(2, 1000);
        NavigableMap<Long, Integer> events = new TreeMap<>();
        long t0 = 1_000_000L;

        events = log.consume(events, t0).state();
        events = log.consume(events, t0).state();
        // At capacity (2 in window) -> reject.
        assertThat(log.consume(events, t0).decision().allowed()).isFalse();

        // Advance past the window: the two t0 events are now older than
        // now - window, so they are pruned and a new request is allowed.
        SlidingWindowLog.Result r = log.consume(events, t0 + 1001);
        assertThat(r.decision().allowed()).isTrue();
    }

    @Test
    void midnightBoundaryCorrect() {
        SlidingWindowLog log = new SlidingWindowLog(3, 2000);
        NavigableMap<Long, Integer> events = new TreeMap<>();
        long midnight = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        long before = midnight - 100;
        long after = midnight + 100;

        // Two events just before UTC midnight...
        events = log.consume(events, before).state();
        events = log.consume(events, before).state();
        // ...still in the window 200 ms later (200ms < 2000ms window), crossing
        // midnight -> the third request is allowed, and the fourth is rejected.
        SlidingWindowLog.Result r3 = log.consume(events, after);
        assertThat(r3.decision().allowed()).isTrue();
        assertThat(log.consume(r3.state(), after).decision().allowed()).isFalse();
    }

    @Test
    void emptyWindowAllowsImmediately() {
        SlidingWindowLog log = new SlidingWindowLog(10, 1000);
        SlidingWindowLog.Result r = log.consume(new TreeMap<>(), 1_000_000L);
        assertThat(r.decision().allowed()).isTrue();
        assertThat(r.decision().remaining()).isEqualTo(9.0);
    }
}

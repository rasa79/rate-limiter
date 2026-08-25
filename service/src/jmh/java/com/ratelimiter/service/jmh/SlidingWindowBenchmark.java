package com.ratelimiter.service.jmh;

import com.ratelimiter.service.algorithm.SlidingWindowLog;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * JMH micro-benchmark of the sliding-window <em>decision</em> cost (pure,
 * time-injected math over a small event map).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SlidingWindowBenchmark {

    private static final long NOW = 1_800_000_000_000L;

    private SlidingWindowLog log;
    private NavigableMap<Long, Integer> events;

    @Setup(Level.Invocation)
    public void setUp() {
        log = new SlidingWindowLog(100, 1000);
        events = new TreeMap<>();
    }

    @Benchmark
    public void decide() {
        events = log.consume(events, NOW).state();
    }
}

package com.ratelimiter.service.jmh;

import com.ratelimiter.service.algorithm.TokenBucket;
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
 * JMH micro-benchmark of the token-bucket <em>decision</em> cost (the pure,
 * time-injected math). Records a single {@code ns/op} figure reproducible via
 * {@code ./mvnw -pl service jmh:benchmark -Djmh.includes=TokenBucketBenchmark}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
// TODO(review): JMH is run in-process (-f 0) because jmh-core is 'provided' so a
// forked JVM can't find ForkedMain — the committed numbers are not forked — tracked
// in KNOWN_LIMITATIONS.md
public class TokenBucketBenchmark {

    private static final long NOW = 1_800_000_000_000L;

    private TokenBucket bucket;
    private TokenBucket.State state;

    @Setup(Level.Invocation)
    public void setUp() {
        bucket = new TokenBucket(100, 100);
        state = TokenBucket.State.full(100, NOW);
    }

    @Benchmark
    public void decide() {
        state = bucket.consume(state, 1.0, NOW).state();
    }
}

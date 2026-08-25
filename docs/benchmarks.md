# Benchmarks

This is an **evidence track**, not a pass/fail gate. Numbers are committed so a
reviewer can reproduce them and judge for themselves; nothing here blocks a PR.

## Algorithm decision cost (JMH)

The pure, time-injected algorithm math — how many nanoseconds a single token-bucket
or sliding-window decision costs with no I/O.

| Benchmark | Mode | Score | Error | Units |
|---|---|---|---|---|
| `TokenBucketBenchmark.decide` | avgt | 36.9 | ± 0.36 | ns/op |
| `SlidingWindowBenchmark.decide` | avgt | 69.2 | ± 2.69 | ns/op |

Run (in-process, quick):
```shell
./mvnw -pl service clean test-compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=org.openjdk.jmh.Main -Dexec.classpathScope=test \
  -Dexec.args="com.ratelimiter.service.jmh.TokenBucketBenchmark com.ratelimiter.service.jmh.SlidingWindowBenchmark -f 0 -wi 3 -i 5"
```
> The committed numbers are from a **`-f 0` (in-process, no-fork)** run for speed; a
> proper forked run (`-f 1`) requires `jmh-core` on the runtime classpath and yields
> tighter error bars. The order of magnitude is representative.

**Interpretation:** the in-process decision is ~37–69 ns — the algorithm math is
effectively free compared to the ~1–2 ms dominated by the network/Valkey round trip
(the actual hot path). Algorithm choice is therefore not a latency concern.

## End-to-end latency/throughput (k6)

These require the compose environment up (`cd deploy && docker compose up --build`),
then:

```shell
k6 run loadtest/k6/check-throughput.js   # 500 rps, 60s, via nginx -> 3 instances -> Valkey
k6 run loadtest/k6/check-latency.js       # p50/p90/p99 end-to-end
```
> Results vary by hardware/Docker settings; commit them here after a run on a
> documented host (see "Environment" below).

## Environment (commit with results)

Document the exact machine + Docker settings used for a numbers run so a third party
can reproduce within an order of magnitude. Example:
- CPU / cores / RAM
- OS + kernel
- JDK 25, Maven 3.9, Docker Compose version
- instance count, Valkey AOF settings (`appendfsync everysec`)
- k6 version + `ulimit -n`

## How to reproduce within an order of magnitude

1. Bring up the compose topology.
2. Run the k6 scripts above with your own machine's load.
3. Re-run the JMH benchmarks as above.
4. Record the numbers + environment here and commit.

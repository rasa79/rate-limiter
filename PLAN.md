# Distributed Rate Limiter Service — Implementation Plan

## Working protocol for the AI harness
- Implement ONE milestone per instruction, then STOP and report.
- Never start the next milestone without explicit user approval.
- Before reporting done: all tests must pass (`./mvnw verify`), show the output.
- After user approval, create a git commit: "M<N>: <milestone name>".
- JavaDoc on all public APIs; rationale comments prefixed with // RATIONALE:.
- Whenever you weaken, skip, or defer any behavior, test, or assertion, you MUST add an entry to KNOWN_LIMITATIONS.md (and a matching `TODO(review)` comment at the code location) in the same commit.

**Status:** planning document — no implementation code in this file.
**Scope:** a centralized, stateless, distributed rate-limiting service (Java 25 + Spring Boot + Maven + Valkey), delivered as a sequence of independently mergeable milestones.

---

## 1. Purpose and how to read this plan

This plan decomposes the project brief into 15 sequential milestones. Each milestone is a vertical slice: it compiles, passes its own tests, and is mergeable to `main` on its own. No milestone leaves the build red or leaves stub code behind.

Each milestone specifies:

- **Goal** — the one thing the milestone proves.
- **Files / modules touched** — concrete paths, so the diff is predictable.
- **Tests to write** — named test classes and named test methods. Tests are the primary portfolio artifact; they are written in vertical slices (one behavior test → minimal implementation → next test), never all up front.
- **Acceptance criteria** — objective, checkable conditions.
- **Demonstration** — how a reviewer sees the milestone working (command + expected observable result).

Cross-cutting decisions that apply to every milestone (architecture, testing philosophy, code style) are fixed in §2–§6 and are **not** renegotiated per milestone.

---

## 2. Fixed architecture decisions (non-negotiable)

1. **Centralized service topology.** N stateless Spring Boot instances behind an nginx load balancer. Not a library, not a sidecar. All shared state lives in Valkey.
2. **Statelessness.** Rules and counters live only in Valkey. Each instance holds an in-memory rule cache refreshed by TTL and invalidated immediately via Valkey pub/sub. Crash recovery = restart + re-warm. No WAL, no leader election.
3. **Atomicity via Lua.** Every counter operation is an atomic Lua script executed in Valkey using `EVALSHA` with fallback to `EVAL` (on `NOSCRIPT`). Scripts are idempotent and atomic: a request either fully executes or not at all.
4. **Two algorithms:** token bucket **and** log-based sliding window (sorted sets). Sliding window exists because rolling-window quotas (e.g., rolling daily upstream limits) cannot be expressed with fixed windows.
5. **Time is injected.** All algorithm logic takes `nowMillis` (or equivalent) as a parameter. Nothing inside algorithm code calls the clock. This makes algorithms deterministic and unit-testable, and enables clock-manipulation fault tests.
6. **Client: Lettuce** — async, thread-safe, native script support, Sentinel support.
7. **Java 25 virtual threads** for request handling (`spring.threads.virtual.enabled=true`). Rationale: the workload is high-concurrency and network-bound (each HTTP request blocks on a Valkey round trip); virtual threads give thread-per-request simplicity without reactive-stack complexity, and keep the code readable for portfolio review. This rationale is recorded in the code as a `// RATIONALE:` comment and in the README design-tradeoff section.

### 2.1 Public interfaces

| Interface | Contract |
|---|---|
| Check API (hot path) | `POST /v1/check`, JSON body `{key, rule}` → `{allowed, remaining, reset_at, retry_after_seconds}`. HTTP/JSON only, no gRPC. Target ~1–2 ms round trip on the hot path. |
| Admin API | REST CRUD for rules: `PUT /v1/rules/{name}`, `GET`, `DELETE`, list. Changes take effect at runtime without restart, propagated to all instances via pub/sub invalidation. |
| Bootstrap config | YAML file + environment variables. Precedence: **file < env < admin API**. Documented in README. |
| Health | Actuator with **separate** probes: `/actuator/health/liveness` (JVM alive) and `/actuator/health/readiness` (Valkey reachable, Lua scripts loaded, rule cache warmed). |
| Metrics | `/actuator/prometheus` via Micrometer Prometheus registry: checks-by-result counters, Valkey latency histograms, `redis_unavailable` counter, per-rule stats. |
| Web UI | Explicitly out of scope. |

### 2.2 Failure semantics (recorded as ADRs in `/docs/adr/`)

- **ADR-0001 Fail-open vs fail-closed per rule.** Configurable per rule, default **fail-open** with a metrics ping. Clients decide via middleware, not the service. Rationale: availability bias for the common case, explicit opt-in to fail-closed for money paths.
- **ADR-0002 Valkey durability trade-off.** AOF `appendonly yes` + `appendfsync everysec`: accept ≤1 s of counter loss on host death; explicitly **not** `always`. Counters are ephemeral by nature — windows expire — and `always` costs ~10× latency for zero meaningful benefit. This ADR is a deliberate portfolio artifact.
- **ADR-0003 Lua atomicity vs resume semantics.** Lua atomicity replaces "resume where it stopped": because each check is a single atomic script, a crash mid-request leaves no partial state to resume from.
- **ADR-0004 Valkey licensing rationale.** Valkey is BSD-licensed with vendor-neutral Linux Foundation governance. The README wording must be precise: Redis 8+ offers AGPLv3 (among its licenses); do **not** claim Redis "isn't open source."
- **ADR-0005 `least_conn` load balancing.** Checks are latency-sensitive; route around slow instances. No sticky sessions — the service is stateless, so affinity buys nothing and hurts rebalance.

---

## 3. Repository layout

```
rate-limiter/
├── pom.xml                          # Maven parent (multi-module)
├── service/                         # the rate limiter Spring Boot app
│   ├── src/main/java/com/ratelimiter/service/
│   │   ├── api/                     # CheckController, AdminController, DTOs, error mapping
│   │   ├── algorithm/               # pure time-injected TokenBucket, SlidingWindowLog
│   │   ├── backend/                 # store SPI: InMemoryStore (M3), ValkeyStore (M4+)
│   │   ├── valkey/                  # Lettuce wiring, ScriptLoader, EVALSHA/EVAL fallback
│   │   ├── rules/                   # Rule model, RuleCache (TTL), RuleRegistry
│   │   ├── pubsub/                  # rule-invalidation publisher/subscriber
│   │   ├── config/                  # bootstrap YAML/env resolution, precedence logic
│   │   ├── metrics/                 # Micrometer instrumentation helpers
│   │   └── health/                  # custom readiness indicators
│   ├── src/main/resources/lua/      # token_bucket.lua, sliding_window.lua
│   ├── src/test/java/               # unit + property tests
│   ├── src/integrationTest/java/    # Testcontainers ITs (*IT)
│   ├── src/concurrencyTest/java/    # concurrency suite (*ConcurrencyIT)
│   ├── src/chaosTest/java/          # fault-injection suite (*ChaosIT) — separate source set
│   └── src/jmh/java/                # JMH microbenchmarks
├── filter/                          # reference Servlet filter module (publishable artifact)
├── examples/
│   ├── saas-tiers/                  # Example 1 (Java/Spring)
│   ├── flash-sale/                  # Example 2 (Java)
│   └── python-worker/               # Example 3 (Python script)
├── deploy/
│   ├── docker-compose.yml           # the primary deliverable environment
│   ├── nginx/nginx.conf             # least_conn, readiness health checks
│   ├── valkey/valkey-primary.conf, valkey-replica.conf, sentinel.conf
│   ├── prometheus/prometheus.yml, alerts.yml, alertmanager.yml
│   └── grafana/provisioning/        # datasource + dashboard, works out of the box
├── loadtest/k6/                     # k6 scripts + committed results
├── docs/adr/                        # ADR-0001 … ADR-0005
└── README.md
```

Build: Maven 3.9+, Java 25, Spring Boot 4.x. Test stacks: JUnit 5 + AssertJ (unit), jqwik on the JUnit platform (properties), Testcontainers 1.21+ with `valkey/valkey:8` (integration/concurrency/chaos), Toxiproxy via Testcontainers (chaos), JMH (micro), k6 (end-to-end load).

---

## 4. Testing strategy (applies to all milestones)

**Philosophy (fixed):**

- Tests are the proof the system works and the primary portfolio artifact. They must read like a specification of system behavior.
- Test behavior through public interfaces (HTTP API, admin API, metrics endpoint), never internals.
- **No mocks of Valkey anywhere.** Every test that touches storage uses a real containerized instance. Mocks would prove the mock, not the Lua script.
- Vertical slices: one behavior test → minimal implementation → next test. Never write all tests first.
- Integration/concurrency/chaos suites run against a container image parameterized by an environment variable (`RATE_LIMITER_IMAGE`, default `valkey/valkey:8`) so the CI matrix (M14) can substitute `redis:8` without code changes.

**Six layers:**

1. **Unit (JUnit 5 + AssertJ).** Pure logic, no I/O, no threads. Algorithm math, rule parsing/validation, config precedence. Time injected.
2. **Property-based (jqwik).** State invariants attacked with random inputs + shrinking:
   - *P1: never more than N allowed in any window*, for random request interleavings and random limits.
   - *P2: refill never exceeds capacity* regardless of elapsed time or event sequence.
   - *P3: remaining + consumed accounting is always consistent with the limit.*
   Property tests are the highest-credibility artifact of this project because they demonstrate the invariants hold for **classes of inputs the author did not hand-pick** — the framework generates adversarial cases (including at boundaries) and shrinks failures to minimal counterexamples. For a correctness-under-concurrency portfolio piece, a shrunk jqwik counterexample (or its absence over 100k tries) is stronger evidence than any curated test list.
3. **Integration (Testcontainers + real Valkey).** Full path HTTP → service → Lua → Valkey → response. Concrete list in M3–M6.
4. **Concurrency.** N threads × M attempts on one hot key via real HTTP; assert **exactly** `limit` allowed (`EQUALS`, never `≤` — Lua atomicity makes the count exact, so a `≤` assertion would hide correctness bugs). `CountDownLatch` starting gun for simultaneous contention.
5. **Fault-injection / chaos.** Toxiproxy between service and Valkey plus container kill/restart. Separate source set, nightly CI, **not** PR-gating.
6. **Benchmarks.** JMH (algorithm decision cost) + k6 (end-to-end p50/p99, max throughput). Evidence track, not pass/fail gates; committed results + exact reproduction methodology in README; numbers must be honest and reproducible.

**CI (GitHub Actions):**

- PR gate: Maven build, unit + property + integration + concurrency suites, JaCoCo report (target ~85%+ on core packages — quality of tests over the number).
- Nightly: chaos suite, results posted.
- Weekly/manual: benchmark job, results committed.
- Matrix (from M14): integration suite against both `valkey/valkey:8` and `redis:8`. README wording: "verified portable across Valkey and Redis."

---

## 5. Observability (core, not stretch)

- Micrometer counters/timers in the check handler and admin API; `/actuator/prometheus`.
- Prometheus container scraping all instances; alert rule files: instance down > 2 m, `redis_unavailable` increments, rejection-rate spikes. Routing rule: **auto-recovered events → dashboard/log only; human-decision events → alerts.**
- Alertmanager: routing/dedup config (Slack or email).
- Grafana: pre-provisioned datasource + dashboard (checks/sec, rejection rate, p99 latency, instances up, Valkey latency/unavailable events) working out of the box on `docker compose up`.
- README states the observability principle: **"observability must outlive the observed"** — monitoring runs in separate containers/hosts so it survives and reports the crash.

---

## 6. Code style (mandatory, enforced from milestone 1)

- JavaDoc on **all** public classes, interfaces, and public methods (what it does, params, returns, throws).
- Inline rationale comments prefixed `// RATIONALE:` throughout, explaining *why* (why EVALSHA with EVAL fallback, why `least_conn`, why time is injected, why exactly-equals assertions in concurrency tests, why no sticky sessions, why AOF `everysec`). These are learning aids for the owner during review and will be removed later; they must be visually distinguishable from JavaDoc.
- Production-shaped code: no TODO stubs, no dead code, meaningful names, small focused classes.

---
---

## 7. Milestones

### Milestone 1 — Repo skeleton: Maven project, Spring Boot app, Actuator, health endpoints, CI scaffold

**Goal:** A buildable, testable, CI-gated skeleton with separate liveness/readiness probes.

**Files / modules touched:**
- `pom.xml` (parent), `service/pom.xml` (Spring Boot 4.x, Actuator, test deps)
- `service/src/main/java/com/ratelimiter/service/RateLimiterApplication.java`
- `service/src/main/resources/application.yml` (Actuator endpoint exposure, health groups `liveness`/`readiness`, virtual threads enabled)
- `service/src/test/java/.../ApplicationContextTest.java`, `.../HealthEndpointsTest.java`
- `.github/workflows/ci.yml` (PR gate scaffold: `mvn verify`, JaCoCo report)

**Tests to write:**
- `ApplicationContextTest.contextLoads()`
- `HealthEndpointsTest.livenessIsUpWhenJvmAlive()`
- `HealthEndpointsTest.readinessIsUpWithNoExternalDependenciesYet()`

**Acceptance criteria:**
- `mvn verify` green locally and in CI.
- `GET /actuator/health/liveness` and `GET /actuator/health/readiness` both return 200 with independent group status.
- JavaDoc/`// RATIONALE:` conventions visible in the first classes (sets the pattern for all later milestones).

**Demonstration:** `./mvnw spring-boot:run`, then `curl localhost:8080/actuator/health/liveness` and `.../readiness`; screenshot/paste of CI green check on the PR.

---

### Milestone 2 — In-memory token bucket (time-injected) + unit + jqwik property tests

**Goal:** The token-bucket algorithm, proven correct in isolation, before any network or storage exists.

**Files / modules touched:**
- `service/src/main/java/com/ratelimiter/service/algorithm/TokenBucket.java` — pure logic: `consume(tokens, nowMillis)` style API, all state passed in/returned (or held in a small immutable state record), clock never called internally.
- `service/src/main/java/com/ratelimiter/service/algorithm/Decision.java` — result record (allowed, remaining, reset/retry).
- `service/src/test/java/.../algorithm/TokenBucketTest.java`
- `service/src/test/java/.../algorithm/TokenBucketProperties.java` (jqwik)

**Tests to write (unit):**
- `rejectsWhenBucketEmpty`
- `allowsExactlyAtLimitBoundary` (request N when limit N → last one allowed, N+1 rejected)
- `partialRefillAfterElapsedTime` (fractional tokens accumulated correctly at injected times)
- `refillNeverExceedsCapacity` (huge elapsed time → bucket capped at capacity)
- `zeroElapsedTimeDoesNotRefill`

**Tests to write (property, jqwik):**
- `neverMoreThanLimitAllowedInAnySequence` (P1)
- `refillNeverExceedsCapacityForAnyElapsedTime` (P2)
- `remainingPlusConsumedEqualsLimitInvariant` (P3)

**Acceptance criteria:**
- All unit + property tests green; jqwik reports ≥ 10 000 tries per property with no counterexample.
- No `System.currentTimeMillis` / `Instant.now` / `Clock` reference inside `algorithm/` (verified by a grep-style architecture check or ArchUnit rule if added cheaply).
- `// RATIONALE:` comment in `TokenBucket` explaining time injection.

**Demonstration:** `mvn -pl service test -Dtest='TokenBucket*'` output showing jqwik statistics.

---

### Milestone 3 — HTTP `/v1/check` endpoint (first end-to-end tracer bullet)

**Goal:** A request can travel HTTP → controller → (in-memory) backend → response. This is the tracer bullet that validates the whole request path; the backend is swapped for Valkey in M4 without touching the path.

**Files / modules touched:**
- `service/src/main/java/com/ratelimiter/service/api/CheckController.java`, DTOs `CheckRequest`, `CheckResponse`
- `service/src/main/java/com/ratelimiter/service/api/ApiExceptionHandler.java` (429 + `Retry-After` header mapping, 400 for malformed bodies)
- `service/src/main/java/com/ratelimiter/service/backend/RateLimitStore.java` (SPI), `InMemoryRateLimitStore.java` (ConcurrentHashMap of bucket states, delegates math to M2 `TokenBucket`)
- `service/src/integrationTest/java/.../CheckApiIT.java`, `.../support/IntegrationTestBase.java` (Spring boot test on random port + shared Testcontainers Valkey container started here — used for a connectivity smoke test now and by the real backend in M4)
- `service/pom.xml`: `integrationTest` source set wired to failsafe

**Tests to write:**
- `CheckApiIT.underLimitReturns200WithRemaining()`
- `CheckApiIT.overLimitReturns429WithPositiveRetryAfter()`
- `CheckApiIT.malformedBodyReturns400()`
- `CheckApiIT.unknownRuleReturns400OrConfiguredDefault()` (behavior pinned down here, documented in the test name)
- `ValkeyConnectivityIT.containerIsReachable()` (smoke test proving the Testcontainers harness works before M4 depends on it)

**Acceptance criteria:**
- Full HTTP round trip works with deterministic time via a test clock bean; responses match the §2.1 contract shape.
- Integration tests run with `mvn verify` and require only Docker.
- The `RateLimitStore` SPI means M4 adds a Valkey implementation without modifying the controller.

**Demonstration:** `mvn verify`; show the `CheckApiIT` run against the real HTTP server and real Valkey container.

---

### Milestone 4 — Valkey backend: Lettuce + Lua token bucket + Lua boundary tests

**Goal:** The correctness core goes live: token-bucket decisions executed atomically inside Valkey via Lua.

**Files / modules touched:**
- `service/src/main/resources/lua/token_bucket.lua` — KEYS: bucket state hash; ARGV: capacity, refill rate, requested tokens, `now` (injected). Returns allowed, remaining, reset/retry. Idempotent: same `(key, now)` inputs produce no double-consumption.
- `service/src/main/java/com/ratelimiter/service/valkey/ScriptLoader.java` — loads scripts, `EVALSHA` first, falls back to `EVAL` on `NOSCRIPT` and re-caches the SHA.
- `service/src/main/java/com/ratelimiter/service/valkey/ValkeyConfig.java` (Lettuce client wiring, timeouts)
- `service/src/main/java/com/ratelimiter/service/backend/ValkeyRateLimitStore.java` (implements M3 SPI)
- `service/src/integrationTest/java/.../lua/TokenBucketLuaIT.java`, `.../ValkeyCheckApiIT.java`
- Readiness indicator: Lua scripts loaded + Valkey reachable → readiness green (`health/ValkeyReadinessIndicator.java`)

**Tests to write:**
- `TokenBucketLuaIT.allowsExactlyAtLimit_thenRejectsNext()`
- `TokenBucketLuaIT.windowRolloverRefillsCorrectlyAtInjectedTime()`
- `TokenBucketLuaIT.clockAtMidnightBoundary()` (injected timestamps straddling 00:00 UTC)
- `TokenBucketLuaIT.concurrentScriptCallsConsumeExactlyOnce` (direct EVALSHA calls racing on one key)
- `ValkeyCheckApiIT.underLimit200_overLimit429_throughFullStack()` (M3 tests now run against the Valkey store)
- `ValkeyCheckApiIT.independentKeysHaveIndependentLimits()`
- `ScriptLoaderIT.noscriptFallsBackToEvalAndRecovers()` (flush script cache, assert transparent recovery)

**Acceptance criteria:**
- `POST /v1/check` is served end-to-end through Lettuce → Lua → Valkey container.
- Readiness goes DOWN when the Valkey container is stopped and UP when restarted (asserted in an IT).
- `// RATIONALE:` comments: why EVALSHA-with-EVAL-fallback (SHA avoids re-uploading scripts; fallback survives `SCRIPT FLUSH` and failover to a replica that never saw the script).

**Demonstration:** `mvn verify`; then manually `docker stop` the IT-visible behavior replicated in a scratch container: readiness flips, recovery on restart.

---

### Milestone 5 — Sliding window algorithm (log-based, sorted sets) + tests

**Goal:** The second algorithm lands: rolling windows for quota use cases fixed windows cannot serve.

**Files / modules touched:**
- `service/src/main/java/com/ratelimiter/service/algorithm/SlidingWindowLog.java` — pure time-injected math mirroring the Lua logic (for unit/property tests and as the executable spec of the script).
- `service/src/main/resources/lua/sliding_window.lua` — sorted-set log: `ZREMRANGEBYSCORE key -inf (now-window)`, `ZCARD`, conditionally `ZADD key now <unique-member>`, set `PEXPIRE`. Unique member per request (e.g., `now:seq`) to survive identical timestamps.
- `service/src/main/java/com/ratelimiter/service/rules/Algorithm.java` (enum: `TOKEN_BUCKET`, `SLIDING_WINDOW`) + rule model field selecting the algorithm.
- `service/src/integrationTest/java/.../lua/SlidingWindowLuaIT.java`, `.../SlidingWindowCheckApiIT.java`
- `service/src/test/java/.../algorithm/SlidingWindowLogTest.java`, `.../SlidingWindowProperties.java`

**Tests to write:**
- Unit: `rejectsAtExactWindowBoundary`, `entriesExpireAsWindowSlides`, `midnightBoundaryCorrect`, `emptyWindowAllowsImmediately`
- Property: `neverMoreThanNAllowedInAnySlidingWindow` (P1 for sliding window), `accountingConsistentWithLimit` (P3)
- Lua/IT: `exactlyAtLimitEnforced`, `rollingWindowNotFixedWindow` (bursts at 0.9w and 1.1w behave correctly — the case fixed windows get wrong), `oldEntriesArePrunedFromTheSet`, `windowRollover`, `clockAtMidnight`
- `SlidingWindowCheckApiIT` — the same HTTP contract tests with `algorithm: sliding_window`

**Acceptance criteria:**
- Both algorithms selectable per rule; the HTTP response contract is algorithm-independent.
- Pure-Java `SlidingWindowLog` and the Lua script agree on a randomized differential test (same injected time sequences → identical decisions) — this is the executable proof the Lua matches the spec.

**Demonstration:** `mvn verify`; README snippet updated with a two-algorithm example `curl`.

---

### Milestone 6 — Admin API + rule cache + pub/sub hot reload

**Goal:** Rules change at runtime across **all** instances without restart.

**Files / modules touched:**
- `service/src/main/java/com/ratelimiter/service/api/AdminController.java` — `PUT/GET/DELETE /v1/rules/{name}`, list; validation (algorithm enum, positive limit/window).
- `service/src/main/java/com/ratelimiter/service/rules/Rule.java`, `RuleCache.java` (in-memory, TTL refresh), `RuleStore.java` (Valkey hash as source of truth)
- `service/src/main/java/com/ratelimiter/service/pubsub/RuleInvalidationPublisher.java`, `RuleInvalidationSubscriber.java` (channel `ratelimiter:rules:invalidate`)
- `service/src/main/java/com/ratelimiter/service/config/BootstrapRulesLoader.java` (YAML/env bootstrap, idempotent apply)
- `service/src/integrationTest/java/.../AdminApiIT.java`, `.../HotReloadMultiInstanceIT.java`, `.../BootstrapConfigIT.java`
- `service/src/test/java/.../rules/RuleValidationTest.java`, `.../config/ConfigPrecedenceTest.java`

**Tests to write:**
- `RuleValidationTest.rejectsMalformedRule` (unknown algorithm, non-positive limit, negative window)
- `ConfigPrecedenceTest.fileThenEnvThenAdminApi` (precedence file < env < admin API)
- `AdminApiIT.crudRoundTrip`, `AdminApiIT.validationErrorsReturn400`
- `HotReloadMultiInstanceIT.ruleUpdatePropagatesToSecondInstanceWithoutRestart` — two Spring application contexts (two instances) against one Valkey container; PUT rule on instance A; assert instance B enforces the new rule within a bounded wait.
- `HotReloadMultiInstanceIT.ttlRefreshEventuallyConsistentWithoutPubsub` (pub/sub message lost → TTL catches up)
- `BootstrapConfigIT.yamlRulesAppliedOnStartup`, `BootstrapConfigIT.reapplicationIsIdempotent`

**Acceptance criteria:**
- Rule change on any instance is enforced by all instances without restart (pub/sub fast path + TTL safety net).
- Bootstrap: rules from YAML applied on startup; re-application idempotent; precedence order documented in README and pinned by tests.
- `// RATIONALE:` comment: why pub/sub + TTL belt-and-braces (pub/sub is fast but lossy; TTL is slow but guaranteed).

**Demonstration:** run two instances locally, `curl -X PUT` a rule on one, immediately exceed the new lower limit on the other → 429.

---

### Milestone 7 — Reference Servlet filter middleware module + Example 1 (SaaS tiers)

**Goal:** The reference integration exists as a reusable artifact, proven by a real example app.

**Files / modules touched:**
- `filter/` Maven module: `RateLimitFilter extends OncePerRequestFilter` — extracts API key, calls `POST /v1/check` (configurable timeout), short-circuits 429 with `Retry-After`, configurable fail-open/fail-closed with a Resilience4j circuit breaker; `FilterProperties`, Spring Boot autoconfigure registration.
- `filter/src/test/...` — filter unit tests with a stubbed HTTP client (mocking the *network client* is allowed; only Valkey is never mocked).
- `examples/saas-tiers/` — Spring app: Free/Pro/Enterprise tiers → three rules; filter integration; a mock billing service that calls the admin API to change a tenant's tier at runtime.
- `deploy/docker-compose.yml` gains the example-1 service (or a compose override file).

**Tests to write:**
- Filter: `passesThroughWhenAllowed`, `shortCircuits429WithRetryAfterHeader`, `failOpenOnLimiterTimeout`, `failClosedWhenConfigured`, `circuitBreakerOpensAfterRepeatedFailures`
- Example IT (`SaasTiersExampleIT`): free tier hits limit → 429; mock billing upgrades tier via admin API; same client succeeds immediately after — **no restart anywhere**.

**Acceptance criteria:**
- `filter/` builds as an independent, publishable artifact with its own JavaDoc.
- Example 1 runs inside the compose environment.

**Demonstration:** compose up, run the example's demo script: burst as Free → 429s; trigger mock billing upgrade; burst again → 200s.

---

### Milestone 8 — Concurrency test suite (exactly-N proof)

**Goal:** Prove Lua atomicity under real parallel load through the public HTTP API.

**Files / modules touched:**
- `service/src/concurrencyTest/java/.../HotKeyConcurrencyIT.java`, `.../CrossKeyConcurrencyIT.java`
- `service/pom.xml`: `concurrencyTest` source set + failsafe execution (PR-gated)
- `.github/workflows/ci.yml`: add concurrency suite to PR gate

**Tests to write:**
- `HotKeyConcurrencyIT.exactlyLimitRequestsAllowed_underFullContention` — e.g., 64 threads × 100 attempts against one key with limit 1000; `CountDownLatch` starting gun; assert allowed count **equals** 1000, not ≤. `// RATIONALE:` comment on the exact-equals assertion.
- `HotKeyConcurrencyIT.noRemainingEverNegative`
- `CrossKeyConcurrencyIT.parallelHotKeysDoNotInterfere` — K keys × contention; each key independently allows exactly its limit.
- Both suites parameterized to run against token bucket **and** sliding window.

**Acceptance criteria:**
- Exact-equality assertions pass repeatably (suite run 5× locally without flake before merge).
- Suite added to the PR gate.

**Demonstration:** CI job log showing the exact-equality assertion passing.

---

### Milestone 9 — Observability: Micrometer metrics, Prometheus, Grafana, Alertmanager

**Goal:** The system explains itself; dashboards and alerts work out of the box.

**Files / modules touched:**
- `service/src/main/java/com/ratelimiter/service/metrics/` — check counters (`result=allowed|rejected|error`), per-rule counters (bounded cardinality: only rule name, never the key), Valkey latency `Timer`, `redis_unavailable` counter, admin API timers.
- `service/src/integrationTest/java/.../MetricsIT.java`
- `deploy/prometheus/prometheus.yml` (scrape all instances), `deploy/prometheus/alerts.yml` (instance down > 2 m, `redis_unavailable` increments, rejection-rate spikes), `deploy/prometheus/alertmanager.yml` (routing/dedup, Slack or email)
- `deploy/grafana/provisioning/datasources/`, `deploy/grafana/provisioning/dashboards/` (checks/sec, rejection rate, p99 latency, instances up, Valkey latency/unavailable)
- `deploy/docker-compose.yml`: prometheus, grafana, alertmanager services

**Tests to write:**
- `MetricsIT.allowedAndRejectedChecksIncrementCounters`
- `MetricsIT.valkeyLatencyHistogramRecorded`
- `MetricsIT.redisUnavailableCounterIncrementsWhenValkeyDown` (stop the container, drive checks, assert counter)
- `MetricsIT.prometheusEndpointExposesExpectedSeries`

**Acceptance criteria:**
- On `docker compose up`, Grafana shows live data with **zero manual setup**.
- Alert rule files load (promtool check in CI); Alertmanager routes test alert.
- README gains the "observability must outlive the observed" principle.

**Demonstration:** paste Grafana dashboard screenshot while `k6` smoke traffic runs; trigger one alert by stopping an instance.

---

### Milestone 10 — Full docker-compose environment: nginx LB, multi-instance, Valkey HA, autoheal

**Goal:** The primary deliverable: one command starts the whole production-shaped topology.

**Files / modules touched:**
- `deploy/docker-compose.yml` (complete): nginx + 3 service instances + valkey-primary + valkey-replica + 3 sentinels + prometheus + grafana + alertmanager + autoheal + example apps
- `deploy/nginx/nginx.conf` — `least_conn`, no sticky sessions, readiness-based upstream health checks; comments explaining each choice
- `deploy/valkey/*.conf` — AOF `appendonly yes`, `appendfsync everysec`; replica config; sentinel configs (quorum 2 of 3)
- `service/Dockerfile` (multi-stage, JRE 25); `restart: unless-stopped` + `HEALTHCHECK` against Actuator liveness on each instance; `willfarrell/autoheal` container
- `service/src/main/java/com/ratelimiter/service/valkey/ValkeyConfig.java` — Lettuce Sentinel support
- `service/src/integrationTest/java/.../SentinelFailoverIT.java`
- README: failover walkthrough + autoheal/Kubernetes note + VPS deployment section

**Tests to write:**
- `SentinelFailoverIT.primaryKilled_sentinelPromotesReplica_serviceKeepsAnswering` — Testcontainers-orchestrated primary+replica+sentinel; kill primary; assert checks resume within a bounded time and counters are consistent (replication lag within the documented AOF/everysec trade-off).
- `ComposeSmokeIT` (optional, CI-feasible subset): `docker compose config` validation + nginx config lint in CI.

**Acceptance criteria:**
- `docker compose up` from a clean checkout starts the full environment; checks pass through nginx.
- Walkthrough works: `docker stop valkey-primary` → sentinel promotes replica → service keeps answering.
- Stopping one service instance removes it from nginx rotation (readiness), autoheal restarts it if unhealthy.
- README notes Kubernetes replaces autoheal in real deployments (liveness probe = restart, readiness = LB rotation), and documents the VPS layout principle: **"things that fail together shouldn't live together"** (LB, N service VPSs, Valkey primary/replicas on different machines, monitoring separate).

**Demonstration:** recorded/annotated terminal session of the failover walkthrough in the README.

---

### Milestone 11 — Chaos / fault-injection suite + nightly CI

**Goal:** Failure semantics are proven, not asserted.

**Files / modules touched:**
- `service/src/chaosTest/java/.../ValkeyUnreachableChaosIT.java`, `.../LatencyInjectionChaosIT.java`, `.../PrimaryKillChaosIT.java`, `.../InstanceKillChaosIT.java`, `.../ClockManipulationChaosIT.java`
- `service/pom.xml`: `chaosTest` source set, **excluded** from `mvn verify` default and PR gate
- `.github/workflows/chaos-nightly.yml`
- Testcontainers Toxiproxy wiring between service and Valkey

**Tests to write:**
- `ValkeyUnreachableChaosIT.failOpenRule_allowsWithMetricPing_whenValkeyDown` and `failClosedRule_rejects_whenValkeyDown` (cut via Toxiproxy; assert documented behavior + `redis_unavailable` increments)
- `LatencyInjectionChaosIT.plus5sLatency_timeoutsTrigger_noHangs_boundedResponse` (Toxiproxy +5 s latency; assert client timeout behavior and max response-time bound)
- `PrimaryKillChaosIT.kill9PrimaryUnderTraffic_failoverWithinSeconds_countersConsistent`
- `InstanceKillChaosIT.serviceKilledMidRequest_noHalfAppliedCounterState` — the Lua atomicity proof: kill an instance during a burst, then audit counters via a second path (direct Valkey read) and assert every check either fully consumed or not at all.
- `ClockManipulationChaosIT.windowBoundariesUnderInjectedClockShifts`

**Acceptance criteria:**
- Nightly workflow runs the chaos suite and posts results; failures page the README badge, not the PR gate.
- Every chaos test's expected behavior matches a written ADR/README claim — the suite is the executable half of the failure-semantics documentation.

**Demonstration:** nightly run link + a deliberately-run chaos scenario narrated in the README.

---

### Milestone 12 — Benchmarks (JMH + k6) + committed results + methodology

**Goal:** Honest, reproducible numbers.

**Files / modules touched:**
- `service/src/jmh/java/.../TokenBucketBenchmark.java`, `.../SlidingWindowBenchmark.java` (algorithm decision cost)
- `loadtest/k6/check-throughput.js`, `loadtest/k6/check-latency.js` — end-to-end through nginx → instances → Valkey; p50/p99 latency, max throughput at target error rate
- `docs/benchmarks.md` — results + exact environment (hardware, Docker settings, instance counts) + exact reproduction commands
- `.github/workflows/benchmark.yml` (weekly/manual)
- README benchmark section

**Tests to write:** none as pass/fail gates — this is an evidence track. A CI smoke variant (30 s k6 run) only verifies the scripts still execute.

**Acceptance criteria:**
- JMH and k6 results committed with hardware/methodology notes; a third party can reproduce within an order of magnitude by following `docs/benchmarks.md`.
- Numbers presented plainly (no cherry-picked percentiles); if p99 is mediocre, it says so and explains why.

**Demonstration:** `docs/benchmarks.md` with tables and reproduction commands.

---

### Milestone 13 — Examples 2 (flash sale) and 3 (Python worker)

**Goal:** Two more integration stories proving the design's range: fail-closed money path and polyglot consumption.

**Files / modules touched:**
- `examples/flash-sale/` (Java): dual-key strategy — per-user fairness key + global payment-gateway key; fail-closed **only** for the payment key; fail-open for per-user keys; a demo script that tightens rules at runtime via the admin API mid-sale.
- `examples/python-worker/` — data-warehouse upstream quota: rolling-daily quota via sliding window; workers re-queue with `delay = retry_after_seconds`; plain `requests`/`httpx` against the HTTP API (proves no SDK needed).
- `deploy/docker-compose.yml` (or override) wiring both examples
- `examples/*/README.md` per example

**Tests to write:**
- `FlashSaleExampleIT.paymentKeyFailClosed_whenLimiterDown`, `FlashSaleExampleIT.perUserKeysFailOpen_whenLimiterDown`, `FlashSaleExampleIT.runtimeRuleTighteningTakesEffectMidBurst`
- `PythonWorkerExampleIT` (compose-level smoke): worker respects rolling daily quota and re-queues for exactly `retry_after` — asserted via worker logs/API responses.

**Acceptance criteria:**
- Both examples runnable via the compose environment with one command each.
- Example 2 demonstrates the per-rule fail-open/fail-closed asymmetry end-to-end; example 3 demonstrates polyglot consumption.

**Demonstration:** per-example demo scripts narrated in their READMEs.

---

### Milestone 14 — Dual Valkey/Redis CI matrix

**Goal:** Portability verified, not claimed.

**Files / modules touched:**
- `.github/workflows/ci.yml` — matrix: `image: [valkey/valkey:8, redis:8]` for the integration suite; container image selected via the existing `RATE_LIMITER_IMAGE` parameterization from §4.
- README portability wording + ADR-0004 final wording check

**Tests to write:** none new — the existing integration suite runs twice, once per image. (Chaos/concurrency stay Valkey-only in CI for runtime, noted in the README.)

**Acceptance criteria:**
- Integration suite green on both images.
- README says exactly: "verified portable across Valkey and Redis," with the licensing wording per ADR-0004 (BSD + Linux Foundation governance for Valkey; Redis 8+ offers AGPLv3 — no "Redis isn't open source" claims).

**Demonstration:** matrix CI run link with both legs green.

---

### Milestone 15 — Documentation pass: README, ADRs, deployment topology docs

**Goal:** The portfolio reads as well as it runs.

**Files / modules touched:**
- `README.md` (final): architecture diagram (ASCII acceptable), quickstart (`docker compose up`), failover walkthrough, benchmark numbers + methodology, design-tradeoff summary ("which systems need which durability guarantee and why"), client integration stories, config precedence (file < env < admin API), build/run instructions assuming only Docker + JDK 25.
- `docs/adr/ADR-0001-fail-open-fail-closed.md`, `ADR-0002-aof-durability-tradeoff.md`, `ADR-0003-lua-atomicity-vs-resume.md`, `ADR-0004-valkey-licensing.md`, `ADR-0005-least-conn.md` — each reviewed against the behavior the test suites actually prove.
- `docs/deployment.md` — real-world VPS layout and failure-domain separation; Kubernetes mentioned as future work only.
- Final sweep: JavaDoc coverage check, `// RATIONALE:` comments present at all the decision points listed in §6, no TODO stubs, no dead code.

**Tests to write:**
- `DocumentationLinksIT`-style cheap checks where valuable (e.g., README commands executed in CI smoke job; `docker compose config` validity).

**Acceptance criteria:**
- A reader with Docker + JDK 25 can go from clone to running demo in ≤ 5 commands.
- Every claim in the README/ADRs is backed by a test, a benchmark, or an explicit "trade-off accepted" statement.
- No Kubernetes manifests (future-work mention only); no web UI.

**Demonstration:** the README itself, walked top to bottom against a clean checkout.

---

## 8. Milestone dependency notes

- M2→M3→M4 is the tracer-bullet spine; nothing else may start until M4 is green.
- M5 depends only on M4's scripting infrastructure.
- M6 requires M4/M5 (rules must name an algorithm).
- M7–M9 are parallelizable after M6; M10 requires M6 (hot reload) and M9 (monitoring in compose); M11 requires M10 (Sentinel topology); M12 requires M10 (realistic topology for numbers); M13 requires M7's filter and M10's environment; M14 requires M4 (integration suite) and is cheap to land earlier if desired; M15 is last but is continuously drafted from M1 onward (ADRs are written in the milestone that makes the decision, finalized in M15).

## 9. Risk register (top items)

| Risk | Mitigation |
|---|---|
| Lua/algorithm drift (script ≠ spec) | Differential test in M5: pure-Java algorithm vs Lua over randomized injected-time sequences. |
| Concurrency suite flakiness under CI load | Exact-equals assertions with generous wall-clock budgets; starting-gun latch; 5× local stability runs before merge (M8). |
| Sentinel failover timing variance in tests | Bounded waits with polling, generous-but-explicit timeouts; chaos suite is nightly, not PR-gating. |
| Metrics cardinality blowup from per-key labels | Per-rule labels only; never the key (M9). |
| Benchmark credibility | Methodology committed with results; honest numbers (M12). |

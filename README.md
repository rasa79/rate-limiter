# Distributed Rate Limiter Service

A centralized, stateless, distributed rate-limiting service (Java 25 + Spring Boot
4 + Maven + Valkey). All shared state lives in Valkey; every counter operation is
an atomic Lua script. Each instance is stateless, so any number can sit behind a
load balancer.

> This README is a work in progress and is finalized in the documentation pass
> (M15). Milestones M1–M5 are complete.

## Quickstart (M5 state)

Requires JDK 25 + Docker + Maven.

```shell
./mvnw verify        # runs unit + property + integration (Testcontainers) suites
./mvnw spring-boot:run
```

The app boots on `:8080`. Two store backends are selected by `RATELIMITER_STORE`
(`in-memory` is the default for tests/dev; `valkey` is the production backend).
In `valkey` mode you need a Valkey instance (e.g. `docker run -p 6379:6379 valkey/valkey:8`).

## Check API

`POST /v1/check` with `{ "key": "<key>", "rule": "<rule-name>" }`:

```json
{ "allowed": true, "remaining": 99, "reset_at": 1787665048240, "retry_after_seconds": 0 }
```

A rejected request returns `429` with the same body shape plus a `Retry-After` header.

## Two algorithms, selected per rule

Rules are configured under `ratelimiter.rules.*` in `application.yml`. Both
algorithms expose the same HTTP contract. Example:

```shell
# TOKEN_BUCKET: burst capacity 5, refills 1 token/second (rule "strict")
curl -s -X POST localhost:8080/v1/check \
  -H 'Content-Type: application/json' \
  -d '{"key":"api-key-1","rule":"strict"}'
# -> {"allowed":true,"remaining":4.0,"reset_at":...,"retry_after_seconds":0}

# SLIDING_WINDOW: at most 5 events in any rolling 1000 ms window (rule "burst")
curl -s -X POST localhost:8080/v1/check \
  -H 'Content-Type: application/json' \
  -d '{"key":"api-key-2","rule":"burst"}'
# -> {"allowed":true,"remaining":4.0,"reset_at":...,"retry_after_seconds":0}
```

Bootstrap rules: `application.yml` (`file`) < environment (`env`) < admin API (M6).

## Observability

The service exposes Micrometer/Prometheus metrics at `/actuator/prometheus`
(counts, per-rule tags, Valkey latency histogram, `redis_unavailable`). The
`deploy/` directory ships Prometheus scrape + alert rules, Alertmanager routing,
and a pre-provisioned Grafana datasource + dashboard so the stack works out of the
box.

> **Principle:** *observability must outlive the observed.* Monitoring (Prometheus,
> Grafana, Alertmanager) runs in separate containers/hosts from the rate-limit
> service, so it keeps running — and keeps reporting — exactly when the thing it
> monitors crashes.

## Deployment (M10)

The primary deliverable is a one-command production-shaped topology in
`deploy/docker-compose.yml`:

```
nginx (least_conn, no sticky) → 3 stateless rate-limiter instances
  → Valkey HA (primary + replica + 3 sentinels, quorum 2 of 3)
  + Prometheus / Grafana / Alertmanager (separate containers) + autoheal
```

```shell
cd deploy && docker compose up --build
# checks pass through nginx on http://localhost/ (via /v1/check)
```

`docker-compose.yml` also mounts the Valkey AOF configs (`appendonly yes`,
`appendfsync everysec` — ADR-0002) and nginx/observability configs.

### Failover walkthrough

```shell
docker stop deploy-valkey-primary-1   # kill the Valkey primary
# Sentinel (quorum 2 of 3) promotes the replica to primary within ~seconds;
# the service instances discover the new primary via Sentinel and keep answering
# (Lettuce auto-follows the failover). No instance restart is needed.
```

`autoheal` (`willfarrell/autoheal`) restarts containers that lose their Docker
health check (the service `HEALTHCHECK` hits Actuator liveness). In a real
deployment this is replaced by Kubernetes: a **liveness probe** = restart the pod,
a **readiness probe** = stop routing traffic (nginx upstreams use the same idea).

### VPS layout — "things that fail together shouldn't live together"

For a self-hosted VPS deployment, separate failure domains: load balancer, the
service VMs, Valkey primary and each replica on different machines, and monitoring
on its own host. (A single-host Docker Compose is an easy start but is not
failure-isolated.)

> Automated Sentinel-failover test note: an end-to-end `SentinelFailoverIT` proving
> a host-resident service reconnects across a containerized-sentinel failover is a
> known challenge (the Sentinel returns container-network addresses a host JVM
> cannot resolve); the Sentinel code path and the compose topology are delivered
> and the valkey-level failover (promote replica) is validated manually.

# Deployment

## Quick start (single host, docker compose)

```shell
cd deploy && docker compose up --build
# nginx LB on :80 -> 3 rate-limiter instances -> Valkey (primary+replica+3 sentinels)
# Prometheus :9090, Grafana :3000, Alertmanager :9093
```

## Production VPS layout — failure-domain separation

**Principle: "things that fail together shouldn't live together."** A single-host
compose is a convenient start, but it is not failure-isolated. For a self-hosted
deployment, separate:

| Component | Placement |
|---|---|
| Load balancer (nginx) | its own host (or a managed LB) |
| N service instances | across ≥2 VPS (stateless, so any instance is interchangeable) |
| Valkey primary | own host |
| Valkey replicas (2+) | each on a different host |
| Sentinels (3) | across the Valkey hosts / separate |
| Monitoring (Prometheus/Grafana/Alertmanager) | own host — *"observability must outlive the observed"* |

## Failover

- Sentinel (quorum 2 of 3) promotes a replica to primary on primary loss; the
  service instances discover the new primary via Sentinel (Lettuce auto-follows).
- Stop/restart an instance: it is removed from LB rotation (readiness) and
  autoheal/Kubernetes restarts it if unhealthy.

## Kubernetes (future work — not shipped here)

In a real cluster, **autoheal + Docker health checks are replaced by Kubernetes**:
- **liveness probe** → restart the pod,
- **readiness probe** → stop routing traffic (matches the Actuator
  `/actuator/health/readiness` and the nginx upstream health-check idea).

No Kubernetes manifests are shipped; this document only describes the mapping.

## Operational knobs

- `RATE_LIMITER_STORE=valkey` (or `in-memory`) — backend selection.
- `ratelimiter.valkey.*` (host/port/timeout/sentinel-master-id) — store connection.
- `ratelimiter.filter.*` — client filter config (`service-url`, `fail-mode`,
  `fail-closed-rules`).
- `ratelimiter.rules.*`, `/v1/rules` — bootstrap + admin API rule management.

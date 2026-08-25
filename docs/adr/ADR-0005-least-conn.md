# ADR-0005: `least_conn` load balancing, no sticky sessions

**Status:** accepted
**Date:** M10 (finalized M15)

## Context

The topology is N stateless service instances behind a load balancer. The LB must
pick an instance for each check request.

## Decision

Use **`least_conn`** (route to the instance with the fewest active connections)
and **keep no sticky sessions**.

## Rationale

- **Latency-sensitive**: checks block on a Valkey round trip, so routing away from
  a slow/draining instance matters — `least_conn` does exactly that.
- **Statelessness ⇒ no affinity**: all shared state lives in Valkey; an instance
  holds no request-specific state, so pinning a client to one instance buys
  nothing and would hurt rebalancing (and failover) by keeping a client on a
  slow or being-drained instance.

## What this is proven by / how

- The service is stateless (M4: all counters/rules in Valkey); the nginx config
  uses `least_conn` with no `sticky`/`ip_hash`.
- Readiness-based rotation: an unhealthy instance is removed (passive health
  checks; active rotation via orchestrator readiness probe in real deployments).

## Consequences

- No cross-request state on any instance; a request can go to any instance.
- The load balancer must be configured with the right health checks (nginx
  passive in the compose; Kubernetes readiness probe for real deployments).

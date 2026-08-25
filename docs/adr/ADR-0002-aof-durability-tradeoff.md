# ADR-0002: Valkey durability trade-off (AOF `appendfsync everysec`)

**Status:** accepted
**Date:** M2 (finalized M15)

## Context

A counter's durability depends on how Valkey persists writes (AOF `fsync` policy).

## Decision

Use **AOF on** (`appendonly yes`) with **`appendfsync everysec`** — **not** `always`.

## Rationale

- **≤ 1 second of counter loss** is acceptable on host death. Counters are
  ephemeral by nature: windows expire, the limit is a rolling/average bound, so a
  ~1s rollback doesn't change a client's real-world budget materially.
- `appendfsync always` costs ~10× latency for zero meaningful benefit here — the
  hot path is latency-sensitive and the durability gain is not worth it.

## What this is proven by / how

- The Valkey configs (`deploy/valkey/*.conf`) set `appendonly yes` +
  `appendfsync everysec`; the compose topology mounts them.

## Consequences

- On a host where both the service and the single Valkey primary die together,
  up to ~1s of counters is lost (acceptable). Replication (Sentinel) reduces the
  scope of loss.
- This is a deliberate trade-off, not an oversight.

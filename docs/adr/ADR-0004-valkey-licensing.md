# ADR-0004: Why Valkey, and the licensing wording we must use

**Status:** accepted
**Date:** M2 (finalized M14)

## Context

The store for the service's counters/rules must be a Redis-compatible key/value
store with script (Lua) support, sorted sets, and pub/sub. The two candidates are
Valkey and Redis.

## Decision

Use **Valkey** as the default/reference store image, while keeping the service
**portable across Valkey and Redis** (the integration suite is parameterized over
`RATE_LIMITER_IMAGE` and runs against both in CI — M14).

## Rationale

- Valkey is **BSD-licensed** and governed by the vendor-neutral **Linux Foundation**.
- It is a fork of Redis that maintains wire/command/script (Lua) compatibility, so
  a single codebase and script set work on both.

## The licensing wording we MUST use (and must not use)

- ✅ "The store is **verified portable across Valkey and Redis**" (this is what the
  dual-image CI matrix proves).
- ✅ State that **Redis 8+ offers AGPLv3** among its licenses.
- ❌ Do **not** claim Redis "isn't open source" — Redis is open source (just under a
  different license / with additional terms).
- ❌ Do **not** imply Valkey merely "replaces" Redis as a drop-in with no nuance.

## Consequences

- The reference compose topology ships `valkey/valkey:8`; CI also proves `redis:8`.
- Any README/ADR wording about the licensing relationship must follow the rules
  above.

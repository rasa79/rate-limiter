# ADR-0003: Lua atomicity vs "resume" semantics

**Status:** accepted
**Date:** M4 (finalized M15)

## Context

A rate-limit decision is a read-modify-write (refill + consume). If a request is
interrupted mid-way (crash, concurrency), the counter must not be left in a
half-applied state.

## Decision

Make each check a **single atomic Lua script** (via `EVALSHA`/`EVAL`), so a request
either fully executes or not at all. There is **no** "resume where it stopped"
mechanism — and none is needed, because the whole decision is atomic in-store.

## Rationale

- Atomicity replaces durability-of-progress: since a crash happens *between*
  whole requests (never inside one), there is no partial state to resume from.
- The script is idempotent and atomic: concurrent requests on one key can never
  interleave and over-drain.

## What this is proven by / how

- `HotKeyConcurrencyIT` / `CrossKeyConcurrencyIT`: **exactly** `limit` requests are
  allowed under simultaneous contention (exact-equality, not `≤`), for both
  algorithms.
- `SlidingWindowDifferentialIT`: the Lua script matches the pure-Java spec exactly
  over randomized injected-time sequences.

## Consequences

- A crash mid-request leaves no half-applied counter — the "instance killed during
  a burst" scenario leaves every prior request fully consumed or fully skipped.
- The correctness lives in the Lua script; the store is otherwise a dumb
  key/value store.

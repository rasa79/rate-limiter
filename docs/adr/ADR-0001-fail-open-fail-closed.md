# ADR-0001: Fail-open vs fail-closed per rule

**Status:** accepted
**Date:** M2 (finalized M15)

## Context

When the rate limiter is unavailable (store down, timeout, circuit breaker open),
a decision must be made: allow the request (fail-open) or reject it (fail-closed).

## Decision

Make it **per rule** and configurable, with a **default of fail-open**. The
decision is made by the **client middleware** (the reference `RateLimitFilter`),
not the service — the service simply returns an error (`500`) when it cannot
evaluate.

## Rationale

- **Availability bias for the common case**: for most endpoints, letting the
  request through when the limiter is down is better than blocking users.
- **Money paths opt in to fail-closed**: the filter's `failClosedRules` lets a
  rule (e.g. a payment-gateway key) fail closed — a purchase must not proceed
  unchecked (Example 2, flash-sale).
- Keeping it client-side means the service stays simple and stateless; a client
  that wants differs chooses the mode.

## What this is proven by / how

- `RateLimitFilterTest.failOpenOnLimiterTimeout`, `failClosedWhenConfigured`,
  `failClosedListedRuleRejectsWhenLimiterDown` — the per-rule asymmetric behavior.
- The flash-sale example configures `fail-closed-rules: [payment]` (fail-closed
  money path) with per-user keys failing open.

## Consequences

- A client must be installed/configured to get the fail mode; raw HTTP consumers
  get the service's `500` on store failure.

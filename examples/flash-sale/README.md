# Example 2: flash sale

A flash-sale app demonstrating the **fail-open / fail-closed asymmetry (ADR-0001)**
with a dual-key strategy:

- a **per-user fairness key** — the filter limits by the `user` rule, which fails
  **open** (availability bias),
- a **shared payment-gateway key** — the filter limits by the `payment` rule, which
  fails **closed** (money path must not proceed unchecked).

It also shows a rule being **tightened at runtime** via the admin API mid-sale.

```shell
# Run against the rate-limiter service (see deploy compose).
RATE_LIMITER_URL=http://localhost:8080 mvn spring-boot:run

# Purchase (per-user 'user' rule) and pay (payment rule, fail-closed):
curl -X POST localhost:8082/api/purchase -H "X-Api-Key: user-1" -H "X-Rate-Limit-Rule: user"
curl -X POST localhost:8082/api/pay       -H "X-Rate-Limit-Rule: payment"
```

Verified end-to-end by `FlashSaleExampleIT` (fail-open on per-user key, fail-closed
on payment key when the limiter is down, runtime tightening mid-sale).

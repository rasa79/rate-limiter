# Example 1: SaaS tiers

A SaaS app whose per-tenant tier is enforced by the reference `RateLimitFilter`,
and whose "billing" service can upgrade a tenant's tier at runtime via the
rate-limiter admin API — no restart, no client change.

```shell
# Against the rate-limiter service (see deploy compose):
RATE_LIMITER_URL=http://localhost:8080 mvn spring-boot:run

# Tenant requests default to the 'free' tier (limit 5):
curl -X POST localhost:8081/api/data -H "X-Api-Key: acme"          # -> 429 after 5
# Upgrade the tenant's tier (raises the 'free' rule limit):
curl -X POST localhost:8081/api/billing/upgrade/acme               # -> {"tier":"pro"}
curl -X POST localhost:8081/api/data -H "X-Api-Key: acme"          # -> 200
```

Verified end-to-end by `SaasTiersExampleIT`.

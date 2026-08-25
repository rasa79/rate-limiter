# Example 3: Python data-warehouse worker

Proves **polyglot consumption**: a Python worker talks to the rate-limiter over
plain HTTP/JSON — no SDK, no vendored library.

It pulls jobs and enforces a **rolling-daily upstream quota** (a sliding-window
rule). When the quota is exhausted, the worker re-queues each job for exactly
`retry_after_seconds`.

```shell
# Run against the compose environment (rate-limiter service on :8080).
python worker.py --url http://localhost:8080 --rule rolling-hour --jobs 20
```

The `rolling-hour` rule (sliding window, 100 req / hour) is in the service's
config. The worker sends `{ "key": "warehouse-upstream", "rule": "rolling-hour" }`;

- 200 `{allowed: true, ...}` → process the job,
- 429 `{allowed: false, retry_after_seconds: N}` → requeue for N seconds.

Requires: `pip install requests`.

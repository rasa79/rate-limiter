#!/usr/bin/env python3
"""Example worker: prove polyglot consumption against the rate-limiter HTTP API.

A data-warehouse worker pulls jobs and checks a rolling-daily upstream quota
(sliding-window rule) via plain `requests`. If the quota is exhausted
(retry_after_seconds), it re-queues the job for exactly that long.

No SDK, no vendored library — just the HTTP contract. Run against the compose
environment (the rate-limiter service at $RATE_LIMITER_URL, default
http://localhost:8080), which has the `rolling-hour` sliding-window rule.

Usage:
    python worker.py [--url http://localhost:8080] [--jobs 20] [--rule rolling-hour]
"""

import argparse
import time
from urllib.parse import urljoin

import requests


def rate_limit_check(url: str, key: str, rule: str) -> dict:
    resp = requests.post(
        urljoin(url, "v1/check"),
        json={"key": key, "rule": rule},
        timeout=5,
    )
    resp.raise_for_status()
    return resp.json()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--jobs", type=int, default=20)
    parser.add_argument("--rule", default="rolling-hour")
    parser.add_argument("--key", default="warehouse-upstream")
    args = parser.parse_args()

    for job in range(args.jobs):
        decision = rate_limit_check(args.url, args.key, args.rule)
        if decision["allowed"]:
            print(f"job {job}: processed (remaining={decision['remaining']})")
            continue

        delay = max(1, int(decision["retry_after_seconds"]))
        print(f"job {job}: quota exhausted, requeueing for {delay}s")
        time.sleep(delay)  # re-queue exactly retry_after_seconds


if __name__ == "__main__":
    main()

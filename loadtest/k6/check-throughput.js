import http from 'k6/http';
import { check } from 'k6';

// End-to-end throughput test through nginx -> 3 instances -> Valkey.
// Target the load balancer; a real run needs the compose environment up
// (see docs/benchmarks.md). Adjust the rate to your hardware/host.
export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: 500,          // requests/sec (target)
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 50,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const res = http.post(
    'http://localhost/v1/check',
    JSON.stringify({ key: 'k6-' + __VU, rule: 'standard' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  // 200 (allowed) and 429 (rejected) are both a valid, intended outcome.
  check(res, { 'status is 200 or 429': (r) => r.status === 200 || r.status === 429 });
}

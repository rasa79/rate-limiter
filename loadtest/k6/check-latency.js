import http from 'k6/http';
import { Trend } from 'k6/metrics';

// End-to-end latency test (p50/p99) through the load balancer. Uses per-VU
// iterations so the latency distribution is meaningful. Run with the compose env
// up (see docs/benchmarks.md).
export const options = {
  scenarios: {
    load: {
      executor: 'per-vu-iterations',
      vus: 20,
      iterations: 1000,
    },
  },
  summaryTrendStats: ['p(50)', 'p(90)', 'p(99)', 'avg', 'max'],
};

const latency = new Trend('check_latency_ms', true);

export default function () {
  const start = Date.now();
  http.post(
    'http://localhost/v1/check',
    JSON.stringify({ key: 'k6-lat-' + __VU + '-' + __ITER, rule: 'standard' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  latency.add(Date.now() - start);
}

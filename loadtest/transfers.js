// Load test for POST /transfers. Run with: docker compose --profile loadtest run --rm k6 run /loadtest/transfers.js
//
// WALLETS controls contention: with 2 wallets every request fights over the same two rows,
// with 1000 wallets requests almost never touch the same row.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_KEY = __ENV.ADMIN_KEY || 'dev-admin-key';
const WALLETS = parseInt(__ENV.WALLETS || '1000');
const VUS = parseInt(__ENV.VUS || '32');
const DURATION = __ENV.DURATION || '60s';

export const options = {
  scenarios: {
    transfers: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  thresholds: {
    // Declaring thresholds on the tagged metric makes k6 report percentiles for transfers only
    // (setup requests are excluded). The limits are loose on purpose: this is a measurement, not a gate.
    'http_req_duration{name:transfer}': ['p(99)<10000'],
    'http_req_failed{name:transfer}': ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const json = (key) => ({ headers: { 'Content-Type': 'application/json', 'X-API-Key': key } });

export function setup() {
  const created = http.post(
    `${BASE_URL}/admin/clients`,
    JSON.stringify({ name: `load-${Date.now()}` }),
    { headers: { 'Content-Type': 'application/json', 'X-Admin-Key': ADMIN_KEY } },
  );
  if (created.status !== 201) {
    throw new Error(`could not create client: ${created.status} ${created.body}`);
  }
  const apiKey = created.json('apiKey');

  const wallets = [];
  for (let i = 0; i < WALLETS; i++) {
    const w = http.post(`${BASE_URL}/wallets`, JSON.stringify({ owner: `w${i}`, currency: 'INR' }), json(apiKey));
    const id = w.json('id');
    // Large balance and 1-paisa transfers: no insufficient-funds noise, we measure contention only.
    http.post(`${BASE_URL}/wallets/${id}/deposit`, JSON.stringify({ amount: 1000000000 }), json(apiKey));
    wallets.push(id);
  }
  return { apiKey, wallets };
}

export default function (data) {
  const n = data.wallets.length;
  const from = Math.floor(Math.random() * n);
  let to = Math.floor(Math.random() * n);
  if (to === from) to = (to + 1) % n;

  const params = json(data.apiKey);
  params.headers['Idempotency-Key'] = `${__VU}-${__ITER}-${Date.now()}`;
  params.tags = { name: 'transfer' };

  const res = http.post(
    `${BASE_URL}/transfers`,
    JSON.stringify({ fromWalletId: data.wallets[from], toWalletId: data.wallets[to], amount: 1 }),
    params,
  );
  check(res, { 'status is 201': (r) => r.status === 201 });
}

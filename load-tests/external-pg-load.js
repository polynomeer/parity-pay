// M-019~M-023: 외부 PG 결제 부하 + 외부와 무관한 조회를 같이 걸어, 기관이 느려질 때 무관한 API가
// 언제 무너지는지 봅니다. 근거: reports/11 M-019, ADR-014
//
// 두 시나리오가 동시에 돕니다.
//   pg_payments   EXTERNAL_PG 결제를 초당 PAY_RPS건 (기관을 거칩니다)
//   balance_reads GET /wallets/me 를 초당 READ_RPS건 (기관과 무관합니다)
//
// 계정은 setup()에서 ACCOUNTS개를 만들어 VU가 나눠 씁니다. 매 반복 가입하면 셋업이 부하가 됩니다.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PAY_RPS = Number(__ENV.PAY_RPS || 10);
const READ_RPS = Number(__ENV.READ_RPS || 20);
const DURATION = __ENV.DURATION || '60s';
const ACCOUNTS = Number(__ENV.ACCOUNTS || 20);
const MERCHANT_ID = __ENV.MERCHANT_ID || '3f1b7c64-9a2e-4c3d-8f11-5a7e2b9d4c60';
const PASSWORD = 'isolation-load-password';

const payDuration = new Trend('pg_payment_duration', true);
const readDuration = new Trend('balance_read_duration', true);
const payOutcome = new Counter('pg_payment_outcome');
const readFailed = new Counter('balance_read_failed');

export const options = {
  scenarios: {
    pg_payments: {
      executor: 'constant-arrival-rate',
      rate: PAY_RPS,
      timeUnit: '1s',
      duration: DURATION,
      // 기관이 30초씩 붙잡으면 VU가 그만큼 쌓입니다. 부족하면 k6가 목표 속도를 못 내고, 그것은
      // 서버가 아니라 부하 도구의 한계가 됩니다.
      preAllocatedVUs: PAY_RPS * 40,
      maxVUs: PAY_RPS * 60,
      exec: 'payment',
    },
    balance_reads: {
      executor: 'constant-arrival-rate',
      rate: READ_RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: READ_RPS * 10,
      maxVUs: READ_RPS * 20,
      exec: 'read',
    },
  },
};

function json(token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

export function setup() {
  const accounts = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const email = `isolation-${uuidv4()}@example.com`;
    const member = http
      .post(`${BASE_URL}/api/v1/members`, JSON.stringify({ email, password: PASSWORD }), { headers: json() })
      .json();
    const token = http
      .post(`${BASE_URL}/api/v1/auth/tokens`, JSON.stringify({ email, password: PASSWORD }), { headers: json() })
      .json('accessToken');
    accounts.push({ walletId: member.walletId, token });
  }
  return { accounts };
}

export function payment(data) {
  const account = data.accounts[__VU % data.accounts.length];
  const key = `iso-pay-${uuidv4()}`;
  const response = http.post(
    `${BASE_URL}/api/v1/payments`,
    JSON.stringify({
      orderId: `order-${key}`,
      walletId: account.walletId,
      merchantId: MERCHANT_ID,
      amount: 10000,
      currency: 'KRW',
      method: 'EXTERNAL_PG',
    }),
    { headers: { ...json(account.token), 'Idempotency-Key': key }, timeout: '180s', tags: { scenario: 'pg_payments' } },
  );
  payDuration.add(response.timings.duration, { scenario: 'pg_payments' });
  let outcome = `http_${response.status}`;
  if (response.status === 201 || response.status === 202) {
    const body = response.json();
    outcome = `${response.status}_${body.status}${body.failureReason ? '_' + body.failureReason : ''}`;
  }
  payOutcome.add(1, { outcome });
}

export function read(data) {
  const account = data.accounts[__VU % data.accounts.length];
  const response = http.get(`${BASE_URL}/api/v1/wallets/me`, {
    headers: json(account.token),
    timeout: '30s',
    tags: { scenario: 'balance_reads' },
  });
  readDuration.add(response.timings.duration, { scenario: 'balance_reads' });
  if (response.status !== 200) readFailed.add(1, { status: String(response.status) });
}

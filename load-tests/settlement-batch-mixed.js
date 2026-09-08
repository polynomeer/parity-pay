// P-004 정산 배치·대사와 API 부하 혼합
//
// 같은 부하를 두 번 겁니다. 한 번은 배치 없이(baseline), 한 번은 정산 계산과 대사가 도는 동안
// (mixed). 두 구간의 지연을 따로 재서 배치가 API에 얼마나 영향을 주는지 봅니다. 한 구간만 재면
// "느리다"는 말은 할 수 있어도 "무엇 때문에"는 말할 수 없습니다.
//
// 근거: docs/10-test-strategy.md §7, reports/11 P-004
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 40);
const WINDOW = __ENV.WINDOW || '60s';
const PAYMENT_AMOUNT = Number(__ENV.PAYMENT_AMOUNT || 1000);
const MERCHANT_COUNT = Number(__ENV.MERCHANTS || 4);
const PASSWORD = 'load-test-password';
const OPS_EMAIL = __ENV.OPS_EMAIL || 'ops-operator@paritypay.local';
const OPS_PASSWORD = __ENV.OPS_PASSWORD || 'local-ops-password';

// 구간별로 지표를 나눕니다. k6 기본 요약은 태그별로 쪼개 주지 않으므로 지표 자체를 나눕니다.
const payBaseline = new Trend('pay_baseline_ms', true);
const payMixed = new Trend('pay_mixed_ms', true);
const confirmBaseline = new Trend('confirm_baseline_ms', true);
const confirmMixed = new Trend('confirm_mixed_ms', true);
const settlementDuration = new Trend('batch_settlement_ms', true);
const reconciliationDuration = new Trend('batch_reconciliation_ms', true);

const approved = new Counter('payments_approved');
const apiErrors = new Counter('api_errors');
const settlementsCreated = new Counter('batch_settlements_created');
const settlementsEmpty = new Counter('batch_settlements_empty');
const reconciliationRuns = new Counter('batch_reconciliation_runs');

// MODE=baseline이면 API만, MODE=mixed면 API와 배치를 함께 돌립니다. 한 번의 실행에 두 구간을
// 넣지 않는 이유는 조건을 맞추기 위해서입니다. 배치가 할 일의 양(정산 대상 항목 수)은 이벤트
// 소비가 얼마나 따라왔는지에 달려 있어서, 실행 안에서 재면 구간마다 다른 조건을 재게 됩니다.
// 실행을 나누면 호출자가 두 실행 사이에 소비가 끝나기를 기다릴 수 있습니다.
const MODE = __ENV.MODE || 'baseline';

const apiScenario = {
  executor: 'constant-arrival-rate',
  startTime: '25s',
  rate: RATE,
  timeUnit: '1s',
  duration: WINDOW,
  preAllocatedVUs: 20,
  maxVUs: 100,
  exec: 'apiTraffic',
  env: { PHASE: MODE },
};

const scenarios = {
  warmup: { executor: 'constant-vus', vus: 2, duration: '20s', exec: 'apiTraffic', env: { PHASE: 'warmup' } },
  api: apiScenario,
};

if (MODE === 'mixed') {
  scenarios.batch = {
    executor: 'constant-vus',
    startTime: '25s',
    vus: 1,
    duration: WINDOW,
    exec: 'batchWork',
  };
}

export const options = {
  scenarios,
  thresholds: {
    'http_req_failed{setup:true}': ['rate<0.10'],
  },
};

function accountNumber() {
  return `110${Math.floor(Math.random() * 1e12).toString().padStart(12, '0')}`;
}

const json = (token) => {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
};

function provisionAccount() {
  const email = `p004-${uuidv4()}@example.com`;
  const member = http
    .post(`${BASE_URL}/api/v1/members`, JSON.stringify({ email, password: PASSWORD }), {
      headers: json(),
      tags: { setup: 'true' },
    })
    .json();
  const token = http
    .post(`${BASE_URL}/api/v1/auth/tokens`, JSON.stringify({ email, password: PASSWORD }), {
      headers: json(),
      tags: { setup: 'true' },
    })
    .json('accessToken');
  const bankAccountId = http
    .post(
      `${BASE_URL}/api/v1/bank-accounts`,
      JSON.stringify({ bankCode: '004', accountNumber: accountNumber(), initialBalance: 100000000 }),
      { headers: json(token), tags: { setup: 'true' } },
    )
    .json('bankAccountId');
  http.post(
    `${BASE_URL}/api/v1/top-ups`,
    JSON.stringify({ walletId: member.walletId, bankAccountId, amount: 50000000, currency: 'KRW' }),
    { headers: { ...json(token), 'Idempotency-Key': `p004-topup-${uuidv4()}` }, tags: { setup: 'true' } },
  );
  return { walletId: member.walletId, token };
}

// VU 로컬 상태입니다. 매 반복마다 가입하면 셋업이 부하의 대부분이 됩니다.
let vuAccount = null;

export function setup() {
  const opsToken = http
    .post(`${BASE_URL}/api/v1/auth/tokens`, JSON.stringify({ email: OPS_EMAIL, password: OPS_PASSWORD }), {
      headers: json(),
      tags: { setup: 'true' },
    })
    .json('accessToken');

  const merchants = [];
  for (let i = 0; i < MERCHANT_COUNT; i++) {
    merchants.push(uuidv4());
  }
  return { opsToken, merchants, today: new Date().toISOString().slice(0, 10) };
}

export function apiTraffic(data) {
  if (vuAccount === null) {
    vuAccount = provisionAccount();
  }
  const phase = __ENV.PHASE;
  const merchantId = data.merchants[Math.floor(Math.random() * data.merchants.length)];
  const idempotencyKey = `p004-pay-${uuidv4()}`;

  const payment = http.post(
    `${BASE_URL}/api/v1/payments`,
    JSON.stringify({
      orderId: `order-${idempotencyKey}`,
      walletId: vuAccount.walletId,
      merchantId,
      amount: PAYMENT_AMOUNT,
      currency: 'KRW',
      method: 'PAY_MONEY',
    }),
    { headers: { ...json(vuAccount.token), 'Idempotency-Key': idempotencyKey }, tags: { operation: 'pay' } },
  );

  if (phase === 'baseline') {
    payBaseline.add(payment.timings.duration);
  } else if (phase === 'mixed') {
    payMixed.add(payment.timings.duration);
  }

  if (payment.status !== 201) {
    // 잔액 부족(409)은 정상 응답입니다. 그 외만 오류로 셉니다.
    if (payment.status !== 409) {
      apiErrors.add(1);
    }
    return;
  }
  approved.add(1);

  // 구매확정을 해야 정산 대상 항목이 생깁니다. 배치가 할 일이 있어야 이 실험이 성립합니다.
  const paymentId = payment.json('paymentId');
  const confirmation = http.post(
    `${BASE_URL}/api/v1/payments/${paymentId}/confirmation`,
    null,
    { headers: json(vuAccount.token), tags: { operation: 'confirm' } },
  );
  if (phase === 'baseline') {
    confirmBaseline.add(confirmation.timings.duration);
  } else if (phase === 'mixed') {
    confirmMixed.add(confirmation.timings.duration);
  }
  if (confirmation.status !== 200) {
    apiErrors.add(1);
  }
}

/** 운영 배치입니다. 정산 계산을 판매자마다 돌리고 대사를 한 번 돌리는 것을 반복합니다. */
let batchRound = 0;

export function batchWork(data) {
  const headers = json(data.opsToken);

  for (const merchantId of data.merchants) {
    // 회차 기간은 (merchant, start, end)로 유일합니다. 라운드마다 시작일을 바꿔 같은 회차를
    // 다시 만들지 않게 합니다. 대상 항목은 종료일 기준으로 모입니다.
    const periodStart = new Date(Date.UTC(2026, 0, 1 + batchRound)).toISOString().slice(0, 10);
    const response = http.post(
      `${BASE_URL}/api/v1/admin/settlements`,
      JSON.stringify({ merchantId, periodStart, periodEnd: data.today }),
      { headers, tags: { operation: 'settlement_calculate' } },
    );
    settlementDuration.add(response.timings.duration);
    if (response.status === 201) {
      settlementsCreated.add(1);
    } else if (response.status === 400) {
      // 이번 라운드에 새로 확정된 항목이 없었습니다. 실패가 아닙니다.
      settlementsEmpty.add(1);
    } else {
      apiErrors.add(1);
    }
  }

  const reconciliation = http.post(`${BASE_URL}/api/v1/admin/reconciliation/runs`, null, {
    headers,
    tags: { operation: 'reconciliation_run' },
  });
  reconciliationDuration.add(reconciliation.timings.duration);
  if (reconciliation.status === 200) {
    reconciliationRuns.add(1);
  } else {
    apiErrors.add(1);
  }
  batchRound++;
}

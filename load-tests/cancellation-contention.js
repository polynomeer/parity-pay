// M-009 동일 지갑 취소 경합
// 결제 승인은 지갑 잔액을 줄이고 취소는 되돌립니다. 둘 다 같은 지갑 행을 잠그므로,
// 한 지갑에서 결제와 취소가 섞이면 그 행이 직렬화 지점입니다.
// VU마다 **다른 결제**를 취소하므로 payment 행 잠금은 공유되지 않고, 공유되는 것은 지갑 행뿐입니다.
// 근거: reports/11 M-009, docs/04-payment-policy.md §6
import http from 'k6/http';
import { check, group } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SAME_WALLET = (__ENV.SAME_WALLET || 'true') === 'true';
const AMOUNT = Number(__ENV.PAYMENT_AMOUNT || 1000);
const PASSWORD = 'load-test-password';

const canceled = new Counter('paritypay_cancellations_completed');
const cancelDuration = new Trend('paritypay_cancel_duration', true);

export const options = {
  scenarios: {
    warmup: { executor: 'constant-vus', vus: 2, duration: '20s', tags: { phase: 'warmup' } },
    measured: {
      executor: 'ramping-vus',
      startTime: '25s',
      startVUs: 5,
      stages: [
        { duration: '20s', target: 20 },
        { duration: '40s', target: 20 },
        { duration: '10s', target: 0 },
      ],
      tags: { phase: 'measured' },
    },
  },
  thresholds: { 'http_req_failed{phase:measured}': ['rate<0.05'] },
};

function accountNumber() {
  return `110${Math.floor(Math.random() * 1e12).toString().padStart(12, '0')}`;
}

const json = (token) => {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
};

function provisionAccount() {
  const email = `cancel-${uuidv4()}@example.com`;
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
    { headers: { ...json(token), 'Idempotency-Key': `cancel-topup-${uuidv4()}` }, tags: { setup: 'true' } },
  );
  return { walletId: member.walletId, token };
}

let vuAccount = null;

export function setup() {
  return { shared: provisionAccount(), merchantId: uuidv4() };
}

export default function (data) {
  if (!SAME_WALLET && vuAccount === null) {
    vuAccount = provisionAccount();
  }
  const account = SAME_WALLET ? data.shared : vuAccount;

  // 1. 취소할 결제를 만듭니다. 이 요청도 같은 지갑 행을 잠급니다.
  const payKey = `cancel-pay-${uuidv4()}`;
  const approve = http.post(
    `${BASE_URL}/api/v1/payments`,
    JSON.stringify({
      orderId: `order-${payKey}`,
      walletId: account.walletId,
      merchantId: data.merchantId,
      amount: AMOUNT,
      currency: 'KRW',
      method: 'PAY_MONEY',
    }),
    { headers: { ...json(account.token), 'Idempotency-Key': payKey }, tags: { operation: 'approve_payment' } },
  );
  if (approve.status !== 201) {
    return;
  }
  const paymentId = approve.json('paymentId');

  // 2. 전액 취소. 이 실험이 재려는 것은 이쪽입니다.
  group('cancel payment', () => {
    const cancelKey = `cancel-key-${uuidv4()}`;
    const response = http.post(
      `${BASE_URL}/api/v1/payments/${paymentId}/cancellations`,
      JSON.stringify({ amount: AMOUNT, currency: 'KRW', reason: 'load test' }),
      { headers: { ...json(account.token), 'Idempotency-Key': cancelKey }, tags: { operation: 'cancel_payment' } },
    );
    cancelDuration.add(response.timings.duration);
    if (response.status === 201) {
      canceled.add(1);
    }
    check(response, { 'cancellation accepted': (r) => r.status === 201 });
  });
}

// P-001 서로 다른 지갑 결제 기준선 / P-002 동일 지갑 경합
// 근거: docs/10-test-strategy.md §7, reports/11 P-001·P-002
import http from 'k6/http';
import { check, group } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// true이면 모든 VU가 하나의 지갑을 공유해 잔액 행 경합을 만듭니다(P-002).
const SAME_WALLET = (__ENV.SAME_WALLET || 'false') === 'true';
const PAYMENT_AMOUNT = Number(__ENV.PAYMENT_AMOUNT || 1000);

const approved = new Counter('paritypay_payments_approved');
const insufficient = new Counter('paritypay_payments_insufficient');
const approveDuration = new Trend('paritypay_payment_duration', true);

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      vus: 2,
      duration: '20s',
      tags: { phase: 'warmup' },
    },
    measured: {
      executor: 'ramping-vus',
      startTime: '20s',
      startVUs: 5,
      stages: [
        { duration: '30s', target: 20 },
        { duration: '60s', target: 20 },
        { duration: '10s', target: 0 },
      ],
      tags: { phase: 'measured' },
    },
  },
  thresholds: {
    // 임계치는 기준선을 측정한 뒤 확정합니다. 지금은 명백한 회귀만 잡습니다.
    'http_req_failed{phase:measured}': ['rate<0.05'],
  },
};

function register() {
  const email = `load-${uuidv4()}@example.com`;
  const member = http.post(
    `${BASE_URL}/api/v1/members`,
    JSON.stringify({ email, password: 'password1234' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const body = member.json();

  const bankAccount = http.post(
    `${BASE_URL}/api/v1/bank-accounts`,
    JSON.stringify({ bankCode: '004', accountNumber: uuidv4(), initialBalance: 100000000 }),
    { headers: { 'Content-Type': 'application/json', 'X-Member-Id': body.memberId } },
  );

  http.post(
    `${BASE_URL}/api/v1/top-ups`,
    JSON.stringify({
      walletId: body.walletId,
      bankAccountId: bankAccount.json().bankAccountId,
      amount: 50000000,
      currency: 'KRW',
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Member-Id': body.memberId,
        'Idempotency-Key': `load-topup-${uuidv4()}`,
      },
    },
  );

  return { memberId: body.memberId, walletId: body.walletId };
}

export function setup() {
  // 동일 지갑 시나리오에서는 모든 VU가 이 지갑을 씁니다.
  return { shared: register(), merchantId: uuidv4() };
}

export default function (data) {
  const account = SAME_WALLET ? data.shared : (__VU_ACCOUNT = __VU_ACCOUNT || register());

  group('approve payment', () => {
    const idempotencyKey = `load-pay-${uuidv4()}`;
    const response = http.post(
      `${BASE_URL}/api/v1/payments`,
      JSON.stringify({
        orderId: `order-${idempotencyKey}`,
        walletId: account.walletId,
        merchantId: data.merchantId,
        amount: PAYMENT_AMOUNT,
        currency: 'KRW',
        method: 'PAY_MONEY',
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          'X-Member-Id': account.memberId,
          'Idempotency-Key': idempotencyKey,
        },
      },
    );

    approveDuration.add(response.timings.duration);
    if (response.status === 201) {
      approved.add(1);
    } else if (response.status === 409) {
      // 잔액 부족은 정상 응답입니다. 실패율에 섞지 않습니다.
      insufficient.add(1);
    }

    check(response, {
      'approved or rejected by a business rule': (r) => r.status === 201 || r.status === 409,
    });
  });
}

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
const PASSWORD = 'load-test-password';

const approved = new Counter('paritypay_payments_approved');
const insufficient = new Counter('paritypay_payments_insufficient');
// 승인 요청만 따로 재는 지표입니다. 셋업 요청이 섞이면 지연 분포가 왜곡됩니다.
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
  thresholds: {
    // 임계치는 기준선을 측정한 뒤 확정합니다. 지금은 명백한 회귀만 잡습니다.
    'http_req_failed{phase:measured}': ['rate<0.05'],
  },
};

/** 계좌번호는 길이 제한이 있습니다. uuid를 그대로 쓰면 400입니다. */
function accountNumber() {
  return `110${Math.floor(Math.random() * 1e12)
    .toString()
    .padStart(12, '0')}`;
}

const json = (token) => {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
};

/** 회원을 만들고 로그인해 토큰을 받은 뒤, 계좌를 연결하고 충전까지 마칩니다. */
function provisionAccount() {
  const email = `load-${uuidv4()}@example.com`;
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
    JSON.stringify({
      walletId: member.walletId,
      bankAccountId,
      amount: 50000000,
      currency: 'KRW',
    }),
    {
      headers: { ...json(token), 'Idempotency-Key': `load-topup-${uuidv4()}` },
      tags: { setup: 'true' },
    },
  );

  return { memberId: member.memberId, walletId: member.walletId, token };
}

// k6는 VU마다 모듈을 따로 초기화하므로, 모듈 스코프 변수가 곧 VU 로컬 상태입니다.
// 선언 없이 전역에 대입하면 매 반복이 ReferenceError로 끝나고 요청이 하나도 나가지 않습니다.
let vuAccount = null;

export function setup() {
  // 동일 지갑 시나리오에서는 모든 VU가 이 지갑을 씁니다.
  return { shared: provisionAccount(), merchantId: uuidv4() };
}

export default function (data) {
  // VU마다 계정을 한 번만 만들고 재사용합니다. 매 반복마다 가입하면 셋업이 부하의 대부분이 됩니다.
  if (!SAME_WALLET && vuAccount === null) {
    vuAccount = provisionAccount();
  }
  const account = SAME_WALLET ? data.shared : vuAccount;

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
        headers: { ...json(account.token), 'Idempotency-Key': idempotencyKey },
        tags: { operation: 'approve_payment' },
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

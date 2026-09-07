// P-003 Outbox 적체와 해소
// 충전을 몰아넣어 이벤트를 쌓은 뒤, 발행기가 적체를 얼마나 빨리 해소하는지 봅니다.
// 적체 지표는 /actuator/prometheus 의 paritypay_outbox_* 로 관찰합니다.
// 근거: docs/10-test-strategy.md §7, reports/11 P-003
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    burst: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 50),
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
};

export function setup() {
  const email = `outbox-${uuidv4()}@example.com`;
  const member = http
    .post(`${BASE_URL}/api/v1/members`, JSON.stringify({ email, password: 'password1234' }), {
      headers: { 'Content-Type': 'application/json' },
    })
    .json();

  const bankAccount = http
    .post(
      `${BASE_URL}/api/v1/bank-accounts`,
      JSON.stringify({ bankCode: '004', accountNumber: uuidv4(), initialBalance: 1000000000 }),
      { headers: { 'Content-Type': 'application/json', 'X-Member-Id': member.memberId } },
    )
    .json();

  return { ...member, bankAccountId: bankAccount.bankAccountId };
}

export default function (data) {
  const response = http.post(
    `${BASE_URL}/api/v1/top-ups`,
    JSON.stringify({
      walletId: data.walletId,
      bankAccountId: data.bankAccountId,
      amount: 1000,
      currency: 'KRW',
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Member-Id': data.memberId,
        'Idempotency-Key': `outbox-load-${uuidv4()}`,
      },
    },
  );

  check(response, { 'top-up accepted': (r) => r.status === 201 || r.status === 202 });
}

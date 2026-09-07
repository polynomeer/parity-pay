# ParityPay DB·API·이벤트 명세서 (DOC-08)

> **에이전트 지침**
> - **읽는 시점**: 마이그레이션·컨트롤러·이벤트 클래스를 작성할 때.
> - **이 문서가 정하는 것**: 테이블과 제약, 인덱스 원칙, HTTP 계약, 오류 코드, 이벤트 envelope와 카탈로그, 호환성 규칙.
> - **강제 규칙**: 여기 정의된 제약(CHECK, UNIQUE)을 마이그레이션에서 빠뜨리지 않습니다. 애플리케이션 검증만으로 대체하지 않습니다. 이벤트 필드 삭제·타입 변경은 새 버전이며, 구현이 이 문서와 달라지면 문서를 먼저 갱신합니다.

## 1. 공통 규칙

- DB 이름은 `snake_case`, Java 타입은 `UpperCamelCase`·`lowerCamelCase`를 사용합니다.
- 외부 ID는 UUID 계열, 금액은 KRW 원 단위 `BIGINT`, 통화는 `VARCHAR(3)`, 시각은 `TIMESTAMPTZ`입니다.
- 모든 쓰기 API는 필요 시 `Idempotency-Key`를 요구합니다.
- 오류 응답은 `code`, `message`, `traceId`, `details`를 포함합니다.
- API 버전은 URL `/api/v1`, 이벤트 버전은 envelope의 `eventVersion`으로 관리합니다.

## 2. 핵심 DB 스키마

### member

| 컬럼 | 타입 | 제약 |
|---|---|---|
| member_id | UUID | PK |
| email | VARCHAR(255) | UNIQUE, NOT NULL |
| password_hash | VARCHAR(255) | NOT NULL |
| status | VARCHAR(20) | NOT NULL |
| created_at | TIMESTAMPTZ | NOT NULL |

### wallet / wallet_balance

| 테이블 | 주요 컬럼 | 핵심 제약 |
|---|---|---|
| wallet | wallet_id, member_id, currency, status, created_at | UNIQUE(member_id, currency) |
| wallet_balance | wallet_id, available_amount, pending_amount, version, updated_at | PK(wallet_id), amount >= 0 |

### top_up

`top_up_id`, `wallet_id`, `bank_account_id`, `requested_amount`, `completed_amount`, `currency`, `status`, `idempotency_key`, `external_reference_id`, `requested_at`, `completed_at`, `version`

핵심 제약: `requested_amount > 0`, `completed_amount >= 0`, 외부 참조가 있으면 기관 범위에서 유일합니다.

### payment

| 컬럼 | 타입 | 비고 |
|---|---|---|
| payment_id | UUID | PK |
| order_id | UUID/VARCHAR | NOT NULL |
| member_id | UUID | NOT NULL |
| wallet_id | UUID | NOT NULL |
| merchant_id | UUID | NOT NULL |
| requested_amount | BIGINT | > 0 |
| approved_amount | BIGINT | >= 0 |
| completed_cancellation_amount | BIGINT | >= 0 |
| processing_cancellation_amount | BIGINT | >= 0 |
| currency | VARCHAR(3) | KRW |
| method | VARCHAR(30) | PAY_MONEY 등 |
| status | VARCHAR(30) | NOT NULL |
| idempotency_key | VARCHAR(100) | NOT NULL |
| external_reference_id | VARCHAR(100) | nullable |
| version | BIGINT | optimistic lock |
| created_at / updated_at / approved_at | TIMESTAMPTZ | 시각 |

핵심 제약:

```sql
CHECK (requested_amount > 0)
CHECK (approved_amount >= 0)
CHECK (completed_cancellation_amount >= 0)
CHECK (processing_cancellation_amount >= 0)
CHECK (completed_cancellation_amount + processing_cancellation_amount <= approved_amount)
UNIQUE (member_id, idempotency_key)
```

마지막 CHECK가 `INV-005`의 DB 방어선입니다. 주문당 성공 결제 유일성은 부분 인덱스 또는 별도 payment_intent 키로 보장합니다.

### payment_cancellation

`cancellation_id`, `payment_id`, `requested_amount`, `completed_amount`, `reason`, `status`, `idempotency_key`, `external_reference_id`, `requested_at`, `completed_at`, `version`

### ledger

| 테이블 | 주요 컬럼 | 제약 |
|---|---|---|
| ledger_account | account_id, code, owner_type, owner_id, class, normal_balance, currency, status | owner·code·currency 유일성 |
| ledger_transaction | transaction_id, type, reference_type, reference_id, currency, status, reversal_of_id, effective_at, created_at | 업무 참조·유형 유일성 |
| ledger_entry | entry_id, transaction_id, account_id, direction, amount, created_at | amount > 0 |

차변·대변 합계는 단일 행 CHECK로 검증할 수 없으므로 전기 서비스와 지연 제약·트리거 또는 검증 쿼리를 조합합니다.

### reliability / operations

| 테이블 | 목적 | 핵심 제약 |
|---|---|---|
| idempotency_record | 요청 해시·상태·응답 저장 | UNIQUE(principal_id, operation, key) |
| outbox_event | 발행할 이벤트 저장 | PK(event_id), status index |
| consumed_event | 소비 중복 방지 | UNIQUE(consumer_name, event_id) |
| external_request | 외부 요청·응답·조회 기록 | external id index |
| audit_log | 운영자 작업 감사 | append-only |
| reconciliation_run | 대사 실행 단위 | 날짜·기관·유형 index |
| reconciliation_mismatch | 불일치 상세 | 상태·유형 index |

## 3. 인덱스 원칙

- 결제 조회: `(order_id)`, `(wallet_id, created_at desc, payment_id desc)`
- 미확정 복구: `(status, updated_at)` where status=`UNKNOWN`
- 취소 조회: `(payment_id, requested_at)`
- 원장 계정 조회: `(account_id, effective_at, entry_id)`
- Outbox 폴링: `(status, next_attempt_at, occurred_at)`
- 대사 운영: `(resolution_status, mismatch_type, detected_at)`
- 인덱스는 예상 쿼리와 `EXPLAIN ANALYZE` 결과로 확정하며 무조건 추가하지 않습니다.

## 4. API 명세

### POST /api/v1/top-ups

Header: `Idempotency-Key: <unique-key>`

```json
{
  "walletId": "wal_...",
  "bankAccountId": "bank_...",
  "amount": 100000,
  "currency": "KRW"
}
```

성공 `201 Created`:

```json
{
  "topUpId": "top_...",
  "status": "SUCCEEDED",
  "requestedAmount": 100000,
  "completedAmount": 100000,
  "currency": "KRW",
  "createdAt": "2026-09-03T00:00:00Z"
}
```

외부 결과 불명확 시 `202 Accepted`와 `Location`을 반환합니다.

### POST /api/v1/payments

```json
{
  "orderId": "ord_...",
  "walletId": "wal_...",
  "merchantId": "mer_...",
  "amount": 30000,
  "currency": "KRW",
  "method": "PAY_MONEY"
}
```

성공 `201 Created`:

```json
{
  "paymentId": "pay_...",
  "orderId": "ord_...",
  "status": "APPROVED",
  "requestedAmount": 30000,
  "approvedAmount": 30000,
  "canceledAmount": 0,
  "currency": "KRW",
  "approvedAt": "2026-09-03T00:00:01Z"
}
```

### GET /api/v1/payments/{paymentId}

결제 소유자 또는 허용된 운영자만 조회합니다. `ETag` 또는 버전을 선택적으로 제공합니다.

### POST /api/v1/payments/{paymentId}/cancellations

```json
{
  "amount": 10000,
  "reason": "PARTIAL_RETURN"
}
```

응답은 `cancellationId`, `paymentId`, `status`, 요청·완료 금액과 결제의 누적 취소액을 포함합니다.

### POST /api/v1/transfers

```json
{
  "sourceWalletId": "wal_a",
  "targetWalletId": "wal_b",
  "amount": 20000,
  "currency": "KRW"
}
```

### 관리자 API

```text
GET  /api/v1/admin/transactions/{referenceId}/timeline
GET  /api/v1/admin/payments?status=UNKNOWN&before=...
GET  /api/v1/admin/ledger-transactions/{transactionId}
GET  /api/v1/admin/outbox-events?status=FAILED
POST /api/v1/admin/outbox-events/{eventId}/retry
GET  /api/v1/admin/reconciliation/mismatches
POST /api/v1/admin/reconciliation/mismatches/{id}/resolve
POST /api/v1/admin/adjustments
```

금액 보정 API는 요청자·승인자 분리, 사유와 외부 근거를 요구합니다.

## 5. 오류 응답

```json
{
  "code": "INSUFFICIENT_BALANCE",
  "message": "결제에 사용할 수 있는 잔액이 부족합니다.",
  "traceId": "01J...",
  "details": {}
}
```

오류 코드는 `INVALID_AMOUNT`, `WALLET_NOT_ACTIVE`, `INSUFFICIENT_BALANCE`, `IDEMPOTENCY_KEY_REUSED`, `INVALID_STATE_TRANSITION`, `CANCELLATION_AMOUNT_EXCEEDED`, `LIMIT_EXCEEDED`, `RESULT_PENDING`, `EXTERNAL_TEMPORARY_ERROR`, `INTERNAL_ERROR`를 포함합니다.

## 6. 이벤트 Envelope

```json
{
  "eventId": "evt_...",
  "eventType": "PaymentApproved",
  "eventVersion": 1,
  "aggregateType": "Payment",
  "aggregateId": "pay_...",
  "partitionKey": "pay_...",
  "occurredAt": "2026-09-03T00:00:01Z",
  "traceId": "01J...",
  "payload": {}
}
```

- `eventId`는 전역 유일합니다.
- 이벤트 시간은 업무 사실 발생 시각입니다.
- 민감정보와 전체 계좌번호를 payload에 포함하지 않습니다.
- 소비자가 필요로 하는 안정된 사실만 제공하며 내부 엔티티 전체를 직렬화하지 않습니다.

## 7. 이벤트 카탈로그

| 이벤트 | 파티션 키 | 주요 payload | 소비자 |
|---|---|---|---|
| WalletCreated v1 | walletId | walletId, memberId, currency | projection |
| TopUpCompleted v1 | walletId | topUpId, walletId, amount, currency, ledgerTransactionId | notification, projection |
| PaymentApproved v1 | paymentId | paymentId, orderId, merchantId, walletId, amount, currency, ledgerTransactionId | order, settlement, notification, projection |
| PaymentFailed v1 | paymentId | paymentId, orderId, failureCode | order, notification |
| PaymentResultUnknown v1 | paymentId | paymentId, externalRequestId, nextCheckAt | recovery |
| PaymentCancellationCompleted v1 | paymentId | cancellationId, paymentId, walletId, merchantId, amount, currency, ledgerTransactionId | order, settlement, projection |
| OrderConfirmed v1 | paymentId | orderId, paymentId, merchantId, settleableAmount, currency, confirmedAt | settlement |
| SettlementCreated v1 | settlementId | settlementId, merchantId, period, netAmount | operations |
| SettlementPaid v1 | settlementId | settlementId, netAmount, externalReferenceId | notification, reconciliation |
| ReconciliationMismatchDetected v1 | mismatchId | mismatchId, runId, type, referenceType, referenceId, amountDifference, currency | operations |

### 전달 구조

이벤트는 토픽 하나(`paritypay.events`)로 발행하고 파티션 키로 Aggregate 순서를 보장합니다. 순서가
필요한 단위는 Aggregate이지 토픽 전체가 아닙니다. 소비자는 자신이 관심 없는 이벤트 타입을 무시하되
소비 이력에는 남겨, 재처리 대상에서 빠지게 합니다.

## 8. 이벤트 호환성

- 기존 필드는 의미를 바꾸거나 삭제하지 않습니다.
- 선택 필드 추가는 같은 버전에서 허용할 수 있지만 소비자 계약 테스트를 거칩니다.
- 필수 필드 삭제·타입 변경·의미 변경은 새 이벤트 버전입니다.
- 생산자는 구·신 버전을 병행 발행할 수 있는 마이그레이션 기간을 둡니다.
- 소비자는 알 수 없는 선택 필드를 무시합니다.

## 9. 페이지네이션과 정렬

거래내역은 `(createdAt desc, id desc)` 커서를 사용합니다. 금액 변동 중 offset 기반 페이지네이션으로 중복·누락이 생기지 않도록 합니다. 커서는 불투명 문자열로 인코딩하며 서버가 검증합니다.

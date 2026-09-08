# ParityPay MVP 범위 정의서 (DOC-03)

> **에이전트 지침**
> - **읽는 시점**: 작업을 시작하기 전에 "지금 이걸 만들어도 되는가"를 판단할 때.
> - **이 문서가 정하는 것**: MVP 포함·제외 기능, API 표면, 완료 기준(`DoD-`), 품질 게이트.
> - **강제 규칙**: "제외 범위"의 기능은 MVP 완료 전에 구현하지 않습니다. 사용자가 요청하면 MVP 범위 밖임을 알리고 진행 여부를 확인합니다. 새 엔드포인트를 만들기 전에 "MVP API 표면"에 있는지 확인합니다.

## 1. MVP 목표

MVP는 "정상적인 결제 데모"가 아니라 페이머니의 충전·결제·취소가 중복과 동시성 상황에서도 정확하게 기록됨을 증명하는 최소 시스템입니다.

## 2. 포함 범위

### 사용자와 지갑

- 이메일 기반 가입·로그인 또는 단순 개발용 인증
- 사용자별 단일 KRW 지갑 자동 생성
- 가용 잔액과 보류 잔액 조회
- 커서 기반 지갑 거래내역 조회

### Mock Bank와 충전

- Mock 계좌 생성·연결
- 계좌 잔액 검증과 충전 승인·실패
- 같은 멱등성 키의 중복 충전 방지
- 충전 성공 분개와 `TopUpCompleted` Outbox 기록

### 결제와 취소

- 테스트 주문 생성 또는 외부 주문 ID 수용
- 페이머니 결제 승인
- 잔액 부족 실패
- 승인 결제의 전액 취소
- 결제·취소 조회
- 동일 지갑 동시 결제 제어

### 원장

- 원장 계정, 원장 거래와 원장 항목
- 차변·대변 균형 검증
- 확정 원장의 불변성
- 원장 기반 잔액 계산과 지갑 스냅샷 비교

### 이벤트와 운영

- Transactional Outbox 저장·발행
- 이벤트 소비 이력과 중복 방지
- 결제·원장·Outbox 거래 타임라인 조회
- 실패 Outbox의 자동 재시도와 운영자 조회
- 구조화 로그와 핵심 메트릭

## 3. 제외 범위

MVP 완료 전에는 다음 기능을 구현하지 않습니다.

- 실제 카드·은행 연결
- 부분 취소, 출금, 송금
- 판매자 정산과 대사
- 위험 점수와 추가 인증
- 다중 통화와 포인트·쿠폰
- CDC 기반 Outbox와 서비스별 독립 DB
- 복잡한 사용자용 프론트엔드

## 4. MVP API 표면

호출자는 `Authorization: Bearer` 토큰으로 자신을 밝힙니다(Phase 7, 2026-09-07). `X-Member-Id`
헤더는 제거했습니다. 지갑 경로는 아직 `me` 대신 `{walletId}`를 사용합니다.

```text
POST /api/v1/members
POST /api/v1/auth/tokens
GET  /api/v1/wallets/{walletId}
GET  /api/v1/wallets/{walletId}/transactions
POST /api/v1/bank-accounts
POST /api/v1/top-ups
GET  /api/v1/top-ups/{topUpId}
POST /api/v1/payments
GET  /api/v1/payments/{paymentId}
POST /api/v1/payments/{paymentId}/cancellations
GET  /api/v1/admin/transactions/{referenceId}/timeline
GET  /api/v1/admin/outbox-events
POST /api/v1/admin/outbox-events/{eventId}/retry
```

## 5. MVP 핵심 데이터

- `member`, `wallet`, `wallet_balance`
- `bank_account`, `top_up`
- `payment`, `payment_cancellation`
- `ledger_account`, `ledger_transaction`, `ledger_entry`
- `idempotency_record`, `outbox_event`, `consumed_event`
- `audit_log`

## 6. 완료 기준

| ID | 완료 기준 | 증거 |
|---|---|---|
| DoD-01 | 정상 충전·결제·전액 취소 흐름이 동작합니다. | API 통합 테스트 |
| DoD-02 | 모든 확정 원장 거래가 균형을 이룹니다. | DB 제약·불변조건 테스트 |
| DoD-03 | 동일 요청 100회가 한 번만 금액에 영향을 줍니다. | 멱등성 동시 테스트 |
| DoD-04 | 잔액 50,000원에서 40,000원 동시 결제 2건 중 1건만 승인됩니다. | 동시성 테스트 |
| DoD-05 | DB 커밋 후 발행기 종료에도 이벤트가 유실되지 않습니다. | Outbox 장애 테스트 |
| DoD-06 | 같은 이벤트 재전달이 결과를 중복 생성하지 않습니다. | 소비자 멱등 테스트 |
| DoD-07 | 주문·결제·원장·이벤트를 식별자로 추적합니다. | 운영 조회 데모 |
| DoD-08 | 로컬 환경을 문서대로 재현할 수 있습니다. | 새 환경 실행 기록 |

## 7. 품질 게이트

PR을 올리기 전에 아래를 확인합니다.

- 단위·통합 테스트가 모두 통과합니다.
- 금융 불변조건 테스트 실패가 0건입니다.
- DB 마이그레이션을 빈 DB와 이전 버전 DB에서 검증합니다.
- OpenAPI가 구현과 일치합니다. 명세는 구현에서 생성해 [docs/api/openapi.json](api/openapi.json)에
  스냅샷으로 두고, 차이가 생기면 `OpenApiSnapshotTest`가 실패합니다.
- 로그에 비밀번호, 토큰, 전체 계좌번호가 없습니다.
- 코드와 문서의 상태명·오류 코드·이벤트명이 일치합니다.
- 알려진 한계와 미구현 항목을 README에 공개합니다.

## 8. MVP 이후 우선순위

1. `UNKNOWN` 외부 승인 복구
2. 부분 취소
3. 판매자 정산
4. 외부·내부 대사
5. 규칙 기반 위험 판단
6. 성능 개선과 서비스 분리 실험

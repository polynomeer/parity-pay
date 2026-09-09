# ParityPay 구현 체크리스트 (DOC-13)

> **에이전트 지침**
> - **읽는 시점**: "다음에 뭘 하지?"를 정할 때, 작업을 마치고 진행 상태를 갱신할 때.
> - **이 문서가 정하는 것**: Phase별 작업 순서와 완료 상태.
> - **강제 규칙**: 앞 Phase의 항목이 남아 있으면 뒤 Phase를 먼저 구현하지 않습니다 (사용자가 명시적으로 요청한 경우 제외). 항목을 완료하면 같은 커밋에서 체크박스를 갱신하고, 실제로 테스트가 통과한 것만 체크합니다.

## Phase 0. 기반

- [x] Gradle 멀티모듈과 Java 21 설정 (`modules/shared-kernel`, `modules/ledger`, `modules/wallet`, `apps/pay-api`)
- [x] Docker Compose: PostgreSQL, Redpanda, Redis — 관측성 스택(Prometheus·Grafana)은 Phase 6에서 추가합니다
- [x] Flyway와 Testcontainers 기반 테스트 환경 (`AbstractIntegrationTest`, 실제 PostgreSQL 사용)
- [x] 공통 ID, Money, Clock과 오류 응답 (`shared-kernel`, `ErrorCode`, `ApiExceptionHandler`)
- [x] ArchUnit 모듈 경계 검사 (`ModuleBoundaryTest`)

## Phase 1. 원장과 충전

- [x] member, wallet, wallet_balance 스키마 (`V1__core_member_wallet.sql`)
- [x] ledger_account, ledger_transaction, ledger_entry 스키마 (`V2__ledger.sql`)
- [x] 원장 불변조건의 DB 강제 (`V3__ledger_invariants.sql`: INV-001 지연 제약 트리거, INV-006 append-only 트리거)
- [x] 원장 전기 서비스와 분개 카탈로그 테스트 — JE-001·역분개까지. JE-003 이후는 Phase 2
- [x] Mock Bank 계좌·출금 API — 2026-09-08에 `apps/mock-bank` 별도 프로세스로 분리했습니다
- [x] 충전 유스케이스와 멱등성 (`TopUpService`, `JdbcIdempotencyStore`, 동시 20건 테스트 통과)
- [x] 원장 재생 잔액과 스냅샷 검증 (`WalletService.verifyAgainstLedger`, INV-010)
- [x] 충전 UNKNOWN 복구 작업 (Phase 4에서 완료: `TopUpRecoveryService`)

### Phase 1에서 남긴 부채

- ~~인증·인가가 없습니다~~: Phase 7(2026-09-07)에서 해소했습니다. `X-Member-Id` 헤더를 제거했고
  사용자 식별은 서명된 토큰에서만 옵니다.
- ~~Mock Bank가 같은 프로세스 안에 있어 실제 네트워크 지연·연결 끊김을 주입할 수 없습니다~~:
  2026-09-08에 분리했습니다. Mock PG도 같은 날 `apps/mock-pg`로 분리했습니다.
- ~~거래내역 조회(FR-008)와 Outbox(FR-010)는 아직 없습니다~~: 둘 다 Phase 3에서 구현했습니다.

## Phase 2. 결제와 취소

- [x] payment와 cancellation Aggregate (`Payment`, `PaymentCancellation`, `CancellationCapacity`)
- [x] payment·payment_cancellation 스키마와 제약 (`V7__payment.sql`, INV-005를 CHECK로 강제)
- [x] 조건부 원자 잔액 차감 (`WalletFundsUseCase.debit`, AC-003 동시 결제 테스트 통과)
- [x] 결제 승인·조회·전액 취소 API (`PaymentController`, JE-003·JE-004 전기)
- [x] 동일 키·다른 본문 충돌 (`IDEMPOTENCY_KEY_REUSED`)
- [x] 동일 지갑 경합 테스트 (AC-002 동시 30건, AC-003 동시 결제)
- [x] 부분 취소와 동시 취소 예약 (예약→확정 2단계 조건부 UPDATE, T-007 동시 6건 테스트 통과)
- [x] 주문당 성공 결제 유일성 (부분 유니크 인덱스)
- [x] 인증·인가 (2026-09-07, Phase 7) — 서명된 토큰으로 대체하고 `X-Member-Id` 헤더는 제거했습니다
- [x] 지갑 거래내역 조회 (FR-008) — Phase 3에서 프로젝션과 커서 조회로 구현했습니다(아래 Phase 3 참고)

### Phase 2에서 남긴 부채

- 확정 실패(잔액 부족 등)한 결제 시도는 행으로 남지 않고 롤백됩니다. 외부 PG 결제를 도입하는
  Phase 4에서 실패·UNKNOWN 시도를 보존하도록 바꿉니다.
- `PaymentStatus.UNKNOWN` 전이표는 있지만 페이머니 결제에서는 도달하지 않습니다(외부 호출이 없음).

## Phase 3. 이벤트

- [x] outbox_event와 발행기 (`V8__outbox_and_projection.sql`, `OutboxPublisher`, `FOR UPDATE SKIP LOCKED`)
- [x] 이벤트 envelope·schema (`EventEnvelope`, WalletCreated·TopUpCompleted·PaymentApproved·PaymentCancellationCompleted)
- [x] consumed_event 기반 멱등 소비 (`JdbcConsumedEventStore`, 업무 유니크 키가 두 번째 방어선)
- [x] 발행·소비 중단 장애 테스트 (F-003 재시작 발행, F-004·T-008 중복 전달, T-003 롤백 시 이벤트 없음)
- [x] 지수 백오프·jitter와 최대 시도 후 FAILED 전환
- [x] Outbox 적체 메트릭 (`paritypay.outbox.pending`, `.failed`, `.oldest_pending_age_seconds`)
- [x] 거래내역 프로젝션과 커서 조회 (FR-008)
- [x] consumer lag 대시보드 (2026-09-08) — `kafka_consumer_fetch_manager_records_lag_max`와 재분배
      지표를 패널·경보로 붙였습니다. 지표 이름은 실행 중인 앱의 `/actuator/prometheus`에서 확인한
      것입니다. 소비 적체는 Outbox 적체와 다른 사고입니다
- [x] 운영자 Outbox 조회·재처리 API (2026-09-08) — `GET /api/v1/admin/outbox-events`,
      `POST .../{eventId}/retry`. 상태만 되돌리고 발행은 발행기가 합니다. 뒤 형제가 이미 나갔으면
      순서가 뒤집힌다는 사실을 응답이 알려 줍니다

### Phase 3에서 남긴 부채

- ~~소비자는 프로젝션 하나뿐입니다~~: 정산 소비자를 Phase 5에서 추가했습니다.
- ~~이벤트 스키마 계약 테스트(JSON Schema)는 아직 없습니다~~: Phase 8에서 추가했습니다.
- ~~발행기·소비자를 여러 대 띄워본 적이 없습니다~~: 2026-09-08에 실험했습니다(M-001·M-002).
  발행기 다중화에서 순서 결함을 하나 찾았습니다(reports/11 결함 F).

## Phase 4. 외부 결과 복구

- [x] 외부 장애 시나리오 주입 API — Mock Bank의 출금 동작 4종과 조회 API 가용성을 독립 제어
- [x] 상태 조회 복구 작업 (`TopUpRecoveryService`: 선점 → 트랜잭션 밖 조회 → 건별 확정)
- [x] 재시작으로 `PROCESSING`에 남은 거래 복구 — 아무도 건드리지 않던 구멍을 복구 대상에 포함
- [x] 백오프·jitter·수동 검토 전환 (`top_up_recovery`, `requires_manual_review`)
- [x] 승인 응답 유실 테스트 (F-006 수렴, F-009 조회 장애 후 에스컬레이션)
- [x] 운영자 API와 감사 로그 (미확정 목록, 재조회 요청, append-only `audit_log`)
- [x] Payment UNKNOWN (2026-09-08) — 외부 PG 결제를 도입해 도달 경로가 생겼습니다. 승인 후 응답
      유실은 202와 `UNKNOWN`으로 보존되고, 복구가 조회로 확정합니다(`PaymentRecoveryService`)
- [x] Cancellation UNKNOWN (2026-09-08) — 외부 PG 환불(JE-014)로 도달 경로가 생겼습니다.
      미확정 환불은 예약을 유지한 채 보존되고 `CancellationRecoveryService`가 조회로 확정합니다
- [x] Mock Bank 프로세스 분리 (2026-09-08) — `apps/mock-bank`로 떼어내고 HTTP로 호출합니다.
      타임아웃이 우리가 던지는 예외가 아니라 실제 읽기 타임아웃이 되었고, 프로세스를 죽여 연결
      거부도 만들 수 있습니다. 통합 테스트도 기관을 실제로 띄웁니다
- [x] Mock PG 프로세스 분리 (2026-09-08) — `apps/mock-pg`로 분리했습니다. 은행과 별도 프로세스라
      한쪽만 죽이는 실험이 됩니다. 승인·환불 동작을 각각 제어합니다

### 복구 규칙 요약

| 외부 조회 결과 | 처리 |
|---|---|
| 성공 기록 있음 | 정상 흐름과 같은 트랜잭션 메서드로 확정 (원장·잔액·이벤트 함께 커밋) |
| 실패 기록 있음 | FAILED로 확정 |
| 기록 없음 | 한 번으로 단정하지 않고 연속 확인 후 FAILED로 확정 (기본 3회) |
| 조회 불가 | 아무것도 확정하지 않고 백오프 재시도, 최대 시도 초과 시 수동 검토로 전환 |

## Phase 5. 정산과 대사

- [x] 구매확정과 정산 대상 이벤트 (`order_confirmation`, `OrderConfirmed`, 멱등)
- [x] 수수료·조정·정산 계산 (`SettlementCalculator`, 만분율 정수 수수료, INV-008·INV-009를 CHECK로 강제)
- [x] 지급과 UNKNOWN 복구 (`SettlementPayoutService`, `SettlementRecoveryService`, F-010)
- [x] 정산 후 취소 처리 (JE-009 판매자 미수금 + 다음 회차 조정 항목)
- [x] 보류(HELD)와 해제, 실패 지급 재시도
- [x] 내부·외부 대사와 불일치 분류 (`ReconciliationMatcher`, 6종 전수 단위 테스트)
- [x] 운영자 해결·보정 분개 (JE-012, 요청자≠승인자 이중 승인, 사유 필수)
- [x] 미해결 불일치 중복 방지 (부분 유니크 인덱스)와 `ReconciliationMismatchDetected` 이벤트

### 대사 설계 메모

- 비교 규칙은 I/O 없는 도메인 서비스(`ReconciliationMatcher`)입니다. 6종 분류를 단위 테스트로 전수
  검증할 수 있어야 하기 때문입니다.
- 외부 참조 키로 업무 ID를 그대로 씁니다. 외부기관에 보내는 멱등 키가 업무 ID이므로, 우리 쪽 상태가
  미확정이어도 짝을 찾을 수 있습니다.
- 지연 허용 시간 안의 차이는 불일치로 올리지 않습니다. 정상 지연이 전부 알림이 되면 운영자가
  노이즈에 묻힙니다.
- 해결했더라도 근본 원인이 남아 있으면 다음 대사에서 다시 열립니다. 상태만 바꿔 문제를 덮을 수
  없습니다.
- 금액을 직접 고치는 API는 없습니다. 보정은 근거(불일치 ID)·사유·요청자·승인자를 갖춘 새 원장
  분개로만 가능합니다.

### 정산 설계 메모

- 수수료율은 basis point 정수입니다. 비율 계산에 부동소수점을 쓰지 않기 위해서입니다(BR-001).
- 정산 항목 금액은 부호가 있습니다. 원장이 아니므로 방향을 부호로 표현하며, 순액은 항목 합입니다
  (이것이 INV-008의 검증식입니다).
- 취소가 이미 지급된 회차의 결제에 대한 것이면 그 회차를 고치지 않고 다음 회차 조정 항목을
  만듭니다. 원장에서는 판매자 지급예정금이 남아 있지 않으므로 부족분이 판매자 미수금이 됩니다.
- 순액이 음수인 회차는 만들지 않습니다. 회수할 금액이 더 큰 상황은 이월·회수 절차의 대상입니다.

## Phase 6. 운영과 증명

- [x] 통합 거래 타임라인 (`GET /api/v1/admin/transactions/{referenceId}/timeline`, DoD-07)
- [x] 관리자 API와 감사 로그 — 역할 기반 권한은 인증 도입 시(아래 미완 항목)
- [x] Prometheus·Grafana 대시보드와 불변조건 경보 규칙 (`deploy/observability/`)
- [x] 불변조건 상시 지표 (`paritypay.invariant.*`, 임계치는 전부 0)
- [x] OpenTelemetry 트레이스 (OTLP → Jaeger, 로그에 traceId)
- [x] k6 부하 시나리오 작성 (`load-tests/`)
- [x] ADR-004 비교 측정과 확정 (조건부 갱신 vs 비관적 잠금 vs JPA 경로)
- [x] ADR 9건 전부 `Accepted` 확정 — 각 ADR의 Outcome에 검증한 것과 미측정 항목 명시
- [x] 성능·장애 보고서에 실측값 기록 (자동화 검증 결과 + ADR-004 벤치마크)
- [x] 포트폴리오 기술 보고서 갱신 (TBD 0건)
- [x] README 실행법·상태 갱신
- [x] **부하 실험 실행** (2026-09-07) — P-001·P-002·P-003 실측. 결과와 발견한 결함 2건은
      reports/11에 있습니다
- [x] 속성 기반 테스트(jqwik)로 임의 거래 시퀀스 검증 (2026-09-07) — 취소 시퀀스·분개 시퀀스·금액
      연산 3종. 검증 두 겹을 일부러 지웠을 때 속성 3개가 실패하는 것으로 유효성을 확인했습니다
- [x] 스냅샷 손상 후 원장 재구축 실험 (2026-09-07) — 재구축 API·승인·감사 로그를 만들고 손상을
      주입해 탐지→복구→원장 불변까지 확인. 결과는 reports/11 F-010
- [x] 선점 쿼리가 적체 전체를 훑던 원인 규명·수정 (2026-09-09) — 정렬 노드가 LIMIT의 약 1.3배를
      당겨오는 탓이었습니다. 정렬 키에서 `event_id`를 빼 파티션 키 100종에서 1,284.7 → 2,769.8건/초.
      실행 계획 회귀 시험(`OutboxClaimPlanTest`)을 함께 넣었습니다. 결과는 reports/11 M-004
- [x] 파티션 키별 적체 관측 (2026-09-09) — `paritypay.outbox.max_partition_pending` 지표와
      `GET /api/v1/admin/outbox-events/backlog`. M-003이 만든 위험(한 지갑에 몰리면 26.6건/초)을
      실제로 볼 수 있게 한 것입니다. 지표에는 파티션 키를 라벨로 붙이지 않습니다
- [x] 순서 보장의 head-of-line 비용 측정 (2026-09-08) — 적체가 한 지갑에 몰리면 발행이 2,503.6 →
      26.6건/초로 떨어집니다. 설정으로 3배까지 되돌아오고 그 뒤는 배치 왕복 비용이 상한입니다.
      기본값은 바꾸지 않았습니다. 결과는 reports/11 M-003
- [x] 발행기·소비자 다중 인스턴스 실험 (2026-09-08) — JVM을 1·2·4대 띄워 브로커에 실제로 들어간
      것을 읽었습니다. 유실·중복은 0건이었고 **순서 결함**을 찾았습니다(발행기 4대에서 20,000건 중
      50건 역전 → 정산 금액이 달라짐). 선점 쿼리를 파티션 키별 선두만 집어가게 고쳐 9회 실행 전부
      역전 0건. 소비자 쪽은 결함 없음. 결과는 reports/11 M-001·M-002
- [x] 프로세스 강제 종료 기반 F-001·F-002 재현 (2026-09-08) — 트래픽 중 `SIGKILL`, 재시작, 같은
      멱등 키로 재전송. 3회 실행 전부 정확히 1회. 실험이 발행 어댑터의 결함 하나를 찾았습니다
      (reports/11 결함 C)

## Phase 7. 인증·인가 (2026-09-07)

- [x] JWT 액세스 토큰과 리프레시 토큰 (수명·철회 정책 분리, 교환 시 회전)
- [x] 역할 모델 6종과 경로별 권한 (`SecurityConfig`)
- [x] `X-Member-Id`·`X-Operator-Id` 헤더 제거 — 사용자 식별은 서명된 토큰에서만 옵니다
- [x] 이중 승인 실질화 — 승인자는 실제로 존재하는 `OPS_APPROVER`여야 하고 요청자와 달라야 합니다
- [x] 로그인 실패 잠금 (실패 기록을 별도 트랜잭션에 커밋)
- [x] 운영자 계정 부트스트랩 (로컬·테스트 전용 설정)
- [x] 판매자(`MERCHANT`) 역할 활용 (2026-09-08) — `merchant` 테이블로 계정과 판매자를 연결하고
      `/api/v1/merchant/**` 조회 경로 추가. 조회 범위는 요청이 아니라 토큰에서 결정됩니다
- [x] 비밀번호 변경·재설정 흐름 (2026-09-08) — 변경 시 세션 전부 철회, 1회용·만료 있는 해시 저장
      재설정 토큰, 계정 존재 여부 비노출. **전달 어댑터(이메일·SMS)는 없습니다**

## Phase 8. CI (2026-09-07)

- [x] PR·main 파이프라인 (빌드, 전체 테스트, 리포트 아티팩트)
- [x] 마이그레이션 검증 테스트 (체크섬, 버전 연속성, 트리거 설치 여부)
- [x] 문서 링크와 불변조건 추적 검사 스크립트
- [x] 비밀값 검사(gitleaks)와 Dependabot
- [x] 벤치마크를 PR 게이트에서 분리하고 주간 실행으로 이동
- [x] 정적 분석 도구 도입 (2026-09-07) — Spotless + palantir-java-format(포맷),
      Error Prone(린트, 7개 검사를 오류로 승격)
- [x] OpenAPI 명세 생성과 변경 검사 (springdoc, 스냅샷 비교 테스트)
- [x] 이벤트 JSON Schema 계약 검사 (2026-09-07) — 이벤트 8종의 스키마, 기록 시점 검사(테스트·로컬),
      코드·스키마·명세서 카탈로그 일치 검사

## 전 단계에 걸쳐 남은 것

- ~~**외부기관 프로세스 분리**~~: Mock Bank·Mock PG 모두 완료(2026-09-08). 남은 결합은 기관이 아직
  우리 데이터베이스를 공유하고, 대사가 기관 표를 직접 읽는다는 것입니다(docs/05 §10).
- ~~**외부 PG 결제·환불**~~: 승인(JE-013)·환불(JE-014)과 두 복구 작업을 붙였습니다(2026-09-08).
  결제·취소의 `UNKNOWN` 경로와 F-006·F-007이 열렸습니다.
- ~~**P-004 부하 시나리오**~~: 실행했습니다(2026-09-08). 지속 가능한 부하에서는 배치가 API 지연을
  눈에 띄게 올리지 않았고, 포화 부하에서 결함 두 개를 찾았습니다(reports/11 결함 D·E).
- ~~**Outbox 발행 처리량**~~: 배치 전송과 적체 시 연속 배치로 처리했습니다. 같은 조건에서
  56.9 → 536.7건/초(P-005). ACK 확인 시점은 그대로입니다.

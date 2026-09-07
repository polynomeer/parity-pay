# ParityPay 성능·장애 테스트 보고서 (RPT-01)

> 상태: 일부 실측. 자동화 테스트로 검증한 항목은 결과가 채워져 있고, 부하 실험은 아직 실행하지
> 않아 `TBD`입니다. 측정하지 않은 수치를 채우지 않는 것이 이 문서의 규칙입니다.

> **에이전트 지침**
> - **읽는 시점**: 부하·장애 실험을 실행하고 결과를 기록할 때.
> - **강제 규칙**: `TBD`를 추정치로 채우지 않습니다. 값은 실제 실행 결과로만 채우고, 실행 명령·커밋 SHA·원본 결과 파일 경로를 함께 남깁니다. 실험을 하지 않았다면 하지 않았다고 씁니다. 실패한 실험 결과도 그대로 기록합니다.

## 1. 보고서 메타데이터

| 항목 | 값 |
|---|---|
| 보고서 버전 | 0.1 (Phase 6 중간) |
| Git commit SHA | 미커밋 작업 트리 |
| 실행 일시 | 2026-09-06 |
| 작성자·검토자 | 미지정 |
| 대상 환경 | 개발 노트북 (아래 §2) |
| 관련 ADR | ADR-004 (확정), ADR-005·ADR-006·ADR-007 (장애 시나리오) |

## 2. 실행 환경

> 아래는 **자동화 테스트를 실행한 환경**입니다. 부하 실험(§3 P-001·P-003·P-004)은 아직 이 환경에서
> 실행하지 않았습니다. 부하 결과를 기록할 때는 그 실행 환경을 따로 적어야 합니다.

### 하드웨어·런타임

| 항목 | 값 |
|---|---|
| CPU / 제한 | Apple Silicon (aarch64), 10 cores |
| Memory / 제한 | 미기록 |
| OS / Kernel | macOS (Darwin 25.6.0) |
| Java / JVM options | Corretto 21.0.7, Gradle 기본 옵션 |
| Docker / Container runtime | Docker 28.0.4 (Docker Desktop) |

### 의존성

| 구성요소 | 버전·설정 |
|---|---|
| PostgreSQL | 17-alpine (Testcontainers, 단일 인스턴스) |
| Redpanda | v24.3.6 (Testcontainers, 단일 노드) |
| Redis | 미사용 |
| 애플리케이션 인스턴스 | 1 (테스트 JVM 내 Spring 컨텍스트) |

### 데이터셋

- 테스트마다 스키마를 TRUNCATE 후 회원·지갑·계좌를 새로 만듭니다.
- 누적 데이터 없이 빈 테이블에서 시작하므로, 데이터가 쌓였을 때의 인덱스·쿼리 성능은 이
  측정으로 알 수 없습니다.

## 3. 성능 실험

### P-001 서로 다른 지갑 결제 기준선

**상태: 미실행.** 시나리오 스크립트는 `load-tests/payment-baseline.js`에 준비되어 있습니다.
애플리케이션을 띄우고 k6로 실행해야 합니다.

| 결과 | 측정값 |
|---|---:|
| TPS | TBD |
| p50 / p95 / p99 | TBD |
| 오류율 | TBD |
| DB CPU / lock wait | TBD |
| Outbox lag | TBD |

### P-002 동일 지갑 경합 — 실측

**가설:** 조건부 원자 업데이트는 비관적 잠금보다 특정 경합 구간에서 락 대기를 줄이지만 재시도율이 증가할 수 있습니다.

**측정 방법:** 하나의 지갑에 8 스레드 × 5 결제(총 40건)를 동시에 보냅니다. 초기 잔액은 절반만
승인 가능한 금액이라 경합과 거절이 함께 발생합니다. warm-up 1회를 버리고 3회 측정하며, 전략을
번갈아 실행해 실행 순서 편차를 상쇄합니다. HTTP를 거치지 않고 유스케이스를 직접 호출합니다.

**실행:** `./gradlew :apps:pay-api:test --tests "*BalanceStrategyBenchmarkTest"`
**원본 결과:** `apps/pay-api/build/reports/adr-004-balance-strategy.txt`

| 구현 | median | min | max | 승인/전체 | 최종 잔액 |
|---|---:|---:|---:|---:|---:|
| 조건부 원자 UPDATE (JDBC) | 1,463 ms | 969 ms | 1,975 ms | 20/40 | 0원 |
| 비관적 잠금 `SELECT FOR UPDATE` | 2,133 ms | 1,998 ms | 2,311 ms | 20/40 | 0원 |
| 조건부 UPDATE (JPA `@Modifying`) | 1,569 ms | 1,048 ms | 3,242 ms | 20/40 | 0원 |

**분석**

- **정확성은 세 구현이 같습니다.** 매 라운드 승인 합계가 정확히 초기 잔액과 일치했고 최종 잔액이
  0원이었습니다. 잔액을 넘겨 승인된 건은 없었습니다(INV-003).
- 이 환경에서는 조건부 갱신이 median 기준 약 1.46배 빠릅니다. 다만 전체 라운드의 최대/최소
  비율이 3.3배라 편차가 크므로, median 차이를 신호로 보되 강한 결론으로 쓰지 않습니다.
- JPA 경로는 median은 비슷하지만 꼬리가 가장 깁니다(max 3,242 ms). 매 호출마다 영속성 컨텍스트를
  flush·clear하기 때문으로 보입니다.
- **측정하지 않은 것**: TPS(HTTP 기준), p50/p95/p99, DB 락 대기 시간, 충돌 재시도율. 이 값들은
  P-002를 k6로 다시 실행해야 얻을 수 있습니다.

**ADR-004 결정 반영:** 조건부 원자 UPDATE 유지로 확정했습니다(2026-09-06). 근거와 한계는
[ADR-004](../docs/adr/004-atomic-balance-update.md) Outcome 절에 있습니다.

**측정 중 발견한 것:** 첫 측정에서 조건부 갱신이 3.7배 느리게 나왔는데, 원인은 잠금 전략이 아니라
조건부 갱신만 JPA `@Modifying(clearAutomatically)` 경로였던 것이었습니다. 두 구현을 JDBC로 맞추자
순서가 뒤집혔습니다. 기본 구현을 JDBC로 교체했습니다.

### P-003 Outbox 적체 복구

**상태: 미실행.** 시나리오는 `load-tests/topup-outbox-backlog.js`에 있고, 적체는
`paritypay_outbox_pending`·`paritypay_outbox_oldest_pending_age_seconds`로 관찰합니다.

- 사전 적체량: TBD
- 발행기 인스턴스·배치 크기: TBD
- 완전 해소 시간: TBD
- 최고·평균 event age: TBD
- 중복 발행과 소비 효과: TBD

### P-004 정산·대사와 API 혼합

**상태: 미실행.**

- 배치 규모: TBD
- API 부하: TBD
- API p99 변화: TBD
- DB I/O·커넥션 풀 영향: TBD
- 격리 또는 throttling 필요성: TBD

## 4. 장애 실험

각 실험은 `Given / When / Then`, 주입 방법, 타임라인, 로그·메트릭·트레이스와 원장 검증 결과를 포함합니다. 시나리오 정의는 [테스트 전략서](../docs/10-test-strategy.md) §6에 있습니다.

장애 시나리오는 프로세스를 실제로 죽이는 대신, 같은 경계에서 결과가 유실되도록 주입해 자동화
테스트로 검증했습니다. 프로세스 강제 종료 실험은 아직 하지 않았습니다.

### F-001 DB 커밋 전 종료 — 검증됨 (자동화)

- 기대: 업무·원장·Outbox 모두 롤백
- 실제: 잔액 부족으로 결제가 확정 실패하면 결제 행·원장 거래·Outbox 이벤트가 모두 남지 않음
- 증거: `PaymentIntegrationTest.insufficientBalanceIsRejected`,
  `OutboxIntegrationTest.rolledBackBusinessLeavesNoEvent`

### F-002 커밋 후 HTTP 응답 전 종료 — 부분 검증

- 기대: 재요청이 기존 결과 반환, 금액 효과 1회
- 실제: 같은 멱등 키로 100회 반복·동시 20~30건 요청 시 업무 효과 1회, 같은 ID 반환
- 증거: `TopUpIntegrationTest`, `PaymentIntegrationTest.concurrentRequestsWithSameKeyApproveOnce`
- **미검증**: 응답 직전 프로세스를 실제로 죽이는 실험

### F-003 Outbox 발행 전 종료 — 검증됨 (자동화)

- 기대: 재시작 후 발행, 소비 결과 1회
- 실제: 발행기를 돌리지 않은 상태에서 이벤트가 PENDING으로 남고, 이후 발행기 실행 시 발행되어
  소비까지 도달
- 증거: `OutboxIntegrationTest.publisherResumesAfterRestart`

### F-004 브로커·소비 ACK 유실 — 검증됨 (자동화)

- 기대: 중복 전달되지만 업무 결과 1회
- 실제: 같은 봉투 3회 전달 → 거래내역 1줄, 소비 이력 1건. 소비 이력을 지우고 재소비해도 업무
  유니크 키가 중복을 차단
- 증거: `OutboxIntegrationTest.duplicateDeliveryProducesSingleRow`,
  `businessUniqueKeyIsTheSecondDefence`

### F-006 승인 후 응답 유실 — 검증됨 (자동화)

- 기대: `PROCESSING → UNKNOWN → SUCCEEDED`
- 실제: Mock Bank가 출금 후 응답을 유실하면 충전이 `UNKNOWN`(202 응답)으로 보존되고, 잔액·원장에
  아무 효과가 없음. 복구 작업이 조회로 `SUCCEEDED` 확정, 3회 반복 실행해도 잔액 1회·원장 1건·외부
  출금 1건
- 복구 시간: 스케줄 주기 설정값(운영 기본 5초). 실제 지연 분포는 미측정
- 증거: `TopUpRecoveryIntegrationTest.unknownAfterWithdrawalConvergesToSucceeded`,
  `repeatedRecoveryIsIdempotent`

### F-007 취소 성공 후 응답 유실 — 미검증

페이머니 취소는 외부 호출이 없어 이 경로가 존재하지 않습니다. 외부 PG 결제를 도입할 때 검증합니다.

### F-008 중복·역순 웹훅 — 미검증

웹훅 수신 경로를 아직 구현하지 않았습니다.

### F-009 조회 API 장애 — 검증됨 (자동화)

- 기대: 백오프 후 수동 검토 전환
- 실제: 조회가 불가하면 아무것도 확정하지 않고 재시도 예약. 최대 시도(테스트 설정 3회) 초과 시
  `requires_manual_review`로 전환되어 자동 배치가 더 이상 집어가지 않음
- 증거: `TopUpRecoveryIntegrationTest.repeatedQueryFailuresEscalateToManualReview`

### F-010 정산 지급 응답 유실 / 원장·스냅샷 차이 — 부분 검증

- 지급 응답 유실: `UNKNOWN` 보존 후 조회로 `PAID` 수렴, 외부 지급은 1회
  (증거: `SettlementIntegrationTest.lostPayoutResponseConvergesToPaid`)
- 원장·스냅샷 차이: 탐지 수단은 구현됨(`ledger-verification` API,
  `paritypay.invariant.balance_snapshot_drift` 지표). **스냅샷을 손상시킨 뒤 원장으로 재구축하는
  실험은 아직 하지 않았습니다.**

### 재시작으로 PROCESSING에 갇힌 거래 — 검증됨 (설계 문서에 없던 경로)

- 실제: 외부 호출 직전에 중단되어 `PROCESSING`으로 남은 충전을 복구 작업이 조회로 확정
- 증거: `TopUpRecoveryIntegrationTest.abandonedProcessingTopUpIsRecovered`

## 5. 불변조건 종합

자동화 테스트 스위트 전체: **167개 테스트, 실패 0건, 실행 시간 39초**
(`./gradlew build --rerun-tasks --no-build-cache`, 2026-09-06).

| 불변조건 | 강제 수단 | 검증 |
|---|---|---|
| 차변=대변 (INV-001) | 애플리케이션 + DB 지연 제약 트리거 | 불균형 삽입이 커밋 시점에 거부됨 |
| 항목 금액>0 (INV-002) | DB CHECK | 0원 항목 삽입 거부 |
| 가용잔액≥0 (INV-003) | 조건부 UPDATE + DB CHECK | 동시 결제·경합 테스트에서 초과 승인 0건 |
| 동일 참조 1회 (INV-004) | 원장 유니크 제약 + 멱등 레코드 | 같은 참조 100회 전기 → 거래 1건 |
| 취소 누적액≤승인액 (INV-005) | 조건부 UPDATE + DB CHECK | 동시 취소 6건 중 3건만 성공 |
| 확정 원장 불변 (INV-006) | DB 트리거 | UPDATE·DELETE 모두 거부 |
| 단일 통화 (INV-007) | 도메인 생성자 | 혼합 통화 분개 생성 불가 |
| 정산 항목 합=순액 (INV-008) | 도메인 + DB CHECK | 계산 결과와 항목 합 일치 |
| 지급 완료=외부 참조 보유 (INV-009) | DB CHECK | 참조 없는 PAID 생성 불가 |
| 스냅샷=원장 (INV-010) | 검증 API + 상시 지표 | 모든 시나리오에서 일치 확인 |

운영 중 위반 감시는 `paritypay.invariant.*` 지표와 Prometheus 경보 규칙
(`deploy/observability/rules/invariants.yml`)이 담당합니다. 임계치는 전부 0입니다.

## 6. 개선 전후 비교

부하 기준선을 아직 측정하지 않아 전후 비교표를 채울 수 없습니다. 유일하게 비교한 것은 잔액 차감
구현 세 가지이며 §3 P-002에 있습니다.

| 지표 | 기준선 | 변경 후 | 변화 | 해석 |
|---|---:|---:|---:|---|
| 결제 TPS | TBD | TBD | TBD | 부하 실험 미실행 |
| p99 | TBD | TBD | TBD | 부하 실험 미실행 |
| 경합 재시도율 | TBD | TBD | TBD | 부하 실험 미실행 |
| Outbox 최고 지연 | TBD | TBD | TBD | 부하 실험 미실행 |

## 7. 결론

**검증된 주장**

- 중복 요청, 동시 결제, 외부 응답 유실, 이벤트 중복 전달 상황에서 금액이 중복 이동하지 않습니다.
  147개 테스트가 이를 매 실행마다 확인합니다.
- 불변조건은 애플리케이션 밖에서도 지켜집니다. 원장 불균형·초과 취소·확정 원장 변경은 DB가
  거부하므로, 애플리케이션 버그나 운영자 SQL로도 깨지지 않습니다.
- 결과를 모르는 거래는 실패로 덮이지 않고 조회로 수렴합니다. 자동 확정이 안전하지 않으면 사람에게
  넘어갑니다.

**반증되거나 수정된 가정**

- ADR-004의 "비관적 잠금이 락 대기로 느릴 것"은 이 환경에서 방향은 맞았지만, 첫 측정은 잠금
  전략이 아니라 JPA flush·clear 차이를 재고 있었습니다. 비교 대상을 같은 조건으로 맞춘 뒤에야
  의미 있는 값이 나왔습니다.
- 설계 문서에 없던 실패 경로가 있었습니다. 외부 호출 직전에 중단되면 거래가 `UNKNOWN`이 아니라
  `PROCESSING`으로 남고, 어떤 복구 작업도 이를 보지 않았습니다.
- 정산 지급 후 취소가 들어오면 판매자 지급예정금이 음수가 됩니다. JE-009가 필요한 시점이
  문서에 명시되어 있지 않았습니다.

**남은 병목·위험 (측정 전이므로 가설입니다)**

- 동일 지갑 경합에서 처리량이 잔액 행 잠금에 묶입니다. 인기 판매자·이벤트 상황에서 문제가 될 수
  있으나 임계점은 모릅니다.
- 데이터가 쌓였을 때의 원장 집계·거래내역 조회 성능을 측정하지 않았습니다.
- Mock Bank가 같은 프로세스에 있어 실제 네트워크 지연·연결 끊김을 재현하지 못합니다.

**변경된 ADR**: ADR-001~009 전부 `Accepted`로 확정(2026-09-06). ADR-004는 비교 측정 결과를 근거로,
나머지는 구현·장애 테스트 결과를 근거로 했습니다. 각 ADR의 Outcome 절에 미측정 항목이 명시되어
있습니다.

**다음 실험**

1. k6로 P-001(서로 다른 지갑)과 P-002(동일 지갑) HTTP 기준선 측정 — TPS, p50/p95/p99, DB 락 대기
2. P-003 Outbox 적체 해소 시간 측정
3. 스냅샷 손상 후 원장 재구축 실험 (F-010)
4. 프로세스 강제 종료 기반 F-001·F-002 재현
5. Mock Bank 프로세스 분리 후 실제 타임아웃 주입

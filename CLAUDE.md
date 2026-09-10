# CLAUDE.md — ParityPay

이 저장소에서 Claude Code가 따르는 작업 지침입니다. 상세 설계는 `docs/`에 있고, 이 파일은 **매 작업마다 지켜야 하는 규칙**과 **어떤 문서를 언제 읽어야 하는지**만 담습니다.

## 1. 프로젝트

ParityPay는 플랫폼 내장형 페이머니 결제·원장 서비스입니다. Java 21 / Spring Boot 4 / PostgreSQL 기반 모듈러 모놀리스이며, 충전·결제·취소·정산·대사를 이중부기 원장 위에서 처리합니다. 고객 앱과 운영 콘솔(React·TypeScript)이 같은 저장소에 있습니다.

이 프로젝트의 목표는 "정상 결제가 되는 것"이 아니라 **중복 요청, 동시 잔액 차감, 외부 승인 후 응답 유실, 이벤트 중복 전달, 프로세스 재시작 상황에서도 금융 불변조건이 깨지지 않는 것**입니다. 모든 구현 판단은 이 기준으로 합니다.

현재 상태: **Phase 0~9 구현 완료** (2026-09-10). 백엔드 299 테스트·프론트엔드 46 테스트·E2E 3건이
실패 없이 돕니다. 부하·장애 실험 25종을 실행해 결함 10건(A~J)을 찾아 고쳤고, 결과는
[reports/11](reports/11-performance-failure-report-template.md)에 있습니다.

## 2. 절대 규칙 (INV) — 어떤 코드도 이것을 깰 수 없습니다

| ID | 불변조건 |
|---|---|
| INV-001 | POSTED 원장 거래의 차변 합계 = 대변 합계 |
| INV-002 | 원장 항목 금액 > 0 (음수 금액으로 방향을 표현하지 않음) |
| INV-003 | 지갑 가용 잔액 >= 0 |
| INV-004 | 동일 업무 참조의 금융 효과는 정확히 1회 |
| INV-005 | 취소 완료액 + 처리중 취소액 <= 승인액 |
| INV-006 | 확정(POSTED) 원장 항목은 UPDATE·DELETE 금지 |
| INV-007 | 하나의 원장 거래는 하나의 통화만 사용 |
| INV-008 | 정산 항목 순액 합계 = 정산 헤더 순액 |
| INV-009 | 지급 완료 정산은 외부 지급 참조를 가짐 |
| INV-010 | 잔액 스냅샷 = 동일 컷오프의 원장 계산값 |

불변조건을 깨는 변경이 필요해 보이면 구현하지 말고 사용자에게 보고합니다.

## 3. 절대 금지

- **타임아웃을 실패로 단정하지 않습니다.** 외부 호출 타임아웃은 `UNKNOWN`이며, 상태 조회로 확정합니다.
- **확정 원장 행을 UPDATE·DELETE하지 않습니다.** 취소는 역분개, 오류는 보정 분개입니다.
- **멱등성 없이 외부 승인·취소·지급을 재시도하지 않습니다.** 재전송보다 상태 조회가 우선입니다.
- **잔액을 SQL로 직접 수정하지 않습니다.** 원장 전기를 통해서만 변경합니다.
- **메시지가 정확히 한 번 전달된다고 가정하지 않습니다.** 전달은 at-least-once입니다.
- **원장 불균형을 조정 계정으로 은폐하지 않습니다.**
- **DB 트랜잭션 안에서 외부 네트워크 호출을 하지 않습니다.**
- **금액에 `float`·`double`을 쓰지 않습니다.** KRW 원 단위 `long` / `BIGINT`입니다.
- **로그·이벤트 payload에 비밀번호, 토큰, 전체 계좌번호, 민감 개인정보를 남기지 않습니다.**
- **비밀값에 기본값을 두지 않습니다.** 없으면 뜨지 않아야 합니다. 기본값이 있으면 배포가 알려진
  값으로 조용히 뜹니다. 근거: ADR-011
- **CORS 설정을 추가하지 않습니다.** 필요해졌다면 배포 형태를 어긴 것입니다. 근거: ADR-011
- **측정하지 않은 성능 수치나 테스트 통과 결과를 문서에 쓰지 않습니다.** 미측정 항목은 `TBD`입니다.

## 4. 작업별로 읽을 문서

먼저 관련 문서를 읽고 구현합니다. 문서와 코드가 어긋나면 문서를 고칠지 코드를 고칠지 사용자에게 확인합니다.

| 작업 | 먼저 읽을 문서 |
|---|---|
| 무엇을 만들지 판단 | [docs/03-mvp-scope.md](docs/03-mvp-scope.md), [docs/02-prd.md](docs/02-prd.md) |
| 금액·한도·취소·오류 규칙 | [docs/04-payment-policy.md](docs/04-payment-policy.md) |
| 모듈 배치·계층·트랜잭션 경계 | [docs/05-technical-design.md](docs/05-technical-design.md) |
| 상태 전이·Aggregate·도메인 이벤트 | [docs/06-domain-state-design.md](docs/06-domain-state-design.md) |
| 분개 생성·계정 선택 | [docs/07-ledger-journal-catalog.md](docs/07-ledger-journal-catalog.md) |
| 테이블·API·이벤트 계약 | [docs/08-db-api-event-spec.md](docs/08-db-api-event-spec.md) |
| 멱등성·동시성·Outbox·복구 | [docs/09-consistency-recovery.md](docs/09-consistency-recovery.md) |
| 테스트 작성 | [docs/10-test-strategy.md](docs/10-test-strategy.md) |
| 다음에 할 일 | [docs/13-implementation-checklist.md](docs/13-implementation-checklist.md) |
| 클라이언트 계약·앱 구분 | [docs/14-frontend-design.md](docs/14-frontend-design.md) |
| 화면에 무엇을 보여줄지 | [docs/15-ui-screen-plan.md](docs/15-ui-screen-plan.md) |
| UI 구현 순서·백엔드 격차 | [docs/16-ui-implementation-plan.md](docs/16-ui-implementation-plan.md) |
| 배포 형태·오리진·비밀값 | [docs/adr/011-deployment-shape.md](docs/adr/011-deployment-shape.md) |
| 왜 이렇게 결정했는지 | [docs/adr/README.md](docs/adr/README.md) |

전체 문서 관계는 [docs/00-document-map.md](docs/00-document-map.md)에 있습니다.

## 5. 구현 규칙

### 계층과 모듈

```text
domain/          순수 도메인 모델, 상태 전이, 정책 (Spring·JPA 의존 최소화)
application/     유스케이스, 트랜잭션 경계, 포트
adapter/in/      REST, 이벤트 소비, 배치 진입점
adapter/out/     DB, 메시지, Mock 기관 클라이언트
```

- 트랜잭션 경계는 **애플리케이션 서비스**가 소유합니다. 컨트롤러와 도메인에 `@Transactional`을 두지 않습니다.
- 모듈 간 쓰기 테이블 공유를 금지합니다. 공개 포트 또는 이벤트만 사용합니다.
- `ledger` 모듈은 다른 업무 모듈을 참조하지 않고 `referenceType` + `referenceId`만 저장합니다.
- 엔티티를 API 응답으로 직접 노출하지 않습니다.
- 모듈 의존 규칙은 ArchUnit으로 강제합니다.

### 돈과 시간

- 금액: KRW 원 단위 양의 정수 (`long` / `BIGINT`). 방향은 `DEBIT`/`CREDIT`으로 표현합니다.
- 시각: 서버는 UTC(`TIMESTAMPTZ`)로 저장하고, 시간 의존 로직에는 `Clock`을 주입합니다.
- 외부 노출 ID: UUIDv7 또는 동등한 추측 불가 식별자.

### 쓰기 요청

모든 금융 쓰기 API는 다음을 갖춥니다.

1. `Idempotency-Key` 헤더 처리 — 키 범위는 `principalId + operation + key`
2. 같은 키·같은 본문 해시 → 저장된 결과 반환 / 같은 키·다른 해시 → `409 IDEMPOTENCY_KEY_REUSED`
3. 업무 유니크 제약을 두 번째 방어선으로 사용 (멱등 레코드만 믿지 않음)
4. 업무 상태 + 원장 + 잔액 스냅샷 + Outbox를 **하나의 로컬 트랜잭션**에 기록

### 새 금융 유스케이스를 추가할 때 체크리스트

- [ ] `04-payment-policy.md`에 해당 업무 규칙(BR)이 있는가
- [ ] `06-domain-state-design.md`의 상태 전이표에 명령이 정의됐는가
- [ ] `07-ledger-journal-catalog.md`에 분개(JE)가 있는가, 차변=대변인가
- [ ] 멱등 키 범위와 업무 유니크 제약을 정했는가
- [ ] 실패·타임아웃 시 `UNKNOWN` 처리와 복구 작업이 있는가
- [ ] Outbox 이벤트와 소비자 멱등성을 정의했는가
- [ ] 불변조건 테스트(INV-xxx)와 동시성 테스트를 추가했는가

## 6. 테스트

- 테스트 이름 또는 메타데이터에 요구사항 ID(`FR-`, `INV-`, `T-`, `F-`)를 연결합니다.
- 통합 테스트는 Testcontainers로 실제 PostgreSQL·Kafka를 사용합니다. 인메모리 DB로 대체하지 않습니다.
- 커버리지 수치보다 **금융 분기와 상태 전이의 전수 검증**을 우선합니다.
- 랜덤·속성 기반 테스트는 seed를 출력하고, 실패 반례는 회귀 테스트로 승격합니다.
- 실패한 테스트를 `@Disabled`나 skip으로 덮지 않습니다. 원인을 고치거나 사용자에게 보고합니다.

## 7. 개발 명령어

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # Gradle 8.14.2는 Java 21에서 실행됩니다
./gradlew build                      # 컴파일 + 테스트
./gradlew test                       # 전체 테스트
./gradlew :modules:ledger:test       # 모듈 단위 테스트
./gradlew test --tests "*TopUpIntegrationTest"
./gradlew :apps:pay-api:bootRun      # 로컬 실행
./gradlew spotlessApply              # 포맷 정리 (커밋 전)
./gradlew spotlessCheck              # 포맷 검사만
docker compose up -d                 # PostgreSQL, Redpanda, Redis
```

프론트엔드는 pnpm입니다.

```bash
pnpm install
pnpm generate                        # docs/api/openapi.json → TypeScript 타입 (생성물은 커밋합니다)
pnpm -r test                         # 프론트엔드 전체 테스트
pnpm typecheck
pnpm --filter @paritypay/web-customer dev    # 고객 앱 (5173)
pnpm --filter @paritypay/web-ops dev         # 운영 콘솔 (5174)
load-tests/run-e2e.sh                # 실제 스택 E2E. 스택을 띄우고 돌리고 정리합니다 (약 1분)
deploy/run.sh                        # 배포 형태로 띄웁니다 (이미지 빌드 포함). down으로 정리
```

- 백엔드 API를 바꾸면 `UPDATE_OPENAPI_SNAPSHOT=1 ./gradlew :apps:pay-api:test --tests "*OpenApiSnapshotTest*"`로
  스냅샷을 갱신하고 `pnpm generate`를 돌립니다. 둘이 어긋나면 CI가 막습니다.
- E2E는 PR 게이트가 아니라 주간·수동 실행입니다. 실제 스택 전부가 필요해 무겁기 때문입니다.

- 항상 `./gradlew` 래퍼를 사용합니다. 기본 `JAVA_HOME`이 Java 21이 아니면 위처럼 지정합니다.
- 통합 테스트는 Testcontainers가 PostgreSQL을 직접 띄우므로 `docker compose` 없이도 실행됩니다.
- 테스트 결과가 캐시(`FROM-CACHE`)로 표시되면 실제로 실행된 것이 아닙니다. 결과를 보고하기 전에
  `--rerun-tasks --no-build-cache`로 다시 실행합니다.
- 변경 후 최소한 `./gradlew test`를 실행하고 결과를 있는 그대로 보고합니다.
- 코드를 고쳤으면 커밋 전에 `./gradlew spotlessApply`를 돌립니다. 포맷은 Spotless가 정하며 손으로
  맞추지 않습니다. 린터(Error Prone)는 컴파일 중에 돌고 위반은 경고가 아니라 오류입니다.
- 데이터 초기화는 `local` 프로필에서만 수행합니다.

### 현재 모듈 구조

```text
modules/shared-kernel   Money, 타입 ID, 오류 코드, 멱등성·이벤트 포트 (순수 자바)
modules/ledger          이중부기 원장: 도메인·전기 서비스·조회 API·JPA 어댑터
modules/wallet          지갑·잔액 스냅샷·충전·복구·거래내역 프로젝션
modules/payment         결제·취소·구매확정
modules/settlement      정산 계산·판매자 지급·지급 복구
modules/reconciliation  내부·외부 대사와 보정
apps/pay-api            조립 지점: 마이그레이션 20개, 인증, Outbox, 운영 API
apps/mock-bank          외부 은행 대역 — 별도 프로세스, 자기 데이터베이스
apps/mock-pg            카드 PG 대역 — 별도 프로세스, 자기 데이터베이스

apps/web-customer       고객 앱 (Shop · My Pay · 판매자 정산)
apps/web-ops            운영 콘솔 (거래 검색 · 원장 · 대사 · 장애 시뮬레이터)
apps/e2e                Playwright — 목 없이 실제 스택을 도는 유일한 시험
packages/api-client     생성된 API 타입 + 멱등 키·토큰·폴링 계약
```

프론트엔드는 pnpm 워크스페이스이며 Gradle과 분리되어 있습니다. 클라이언트가 지켜야 하는 계약은
[DOC-14](docs/14-frontend-design.md) §3에 있고, 어기면 서버의 INV-004가 무의미해집니다.

## 8. 커밋 규칙 (Conventional Commits)

```
<type>(<scope>): <subject>

<body>

<footer>
```

**type**

| type | 용도 |
| --- | --- |
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `docs` | 문서만 변경 |
| `style` | 동작에 영향 없는 포매팅 |
| `refactor` | 기능 변화 없는 구조 개선 |
| `perf` | 성능 개선 |
| `test` | 테스트 추가·수정 |
| `build` | 빌드·의존성 변경 (Gradle 등) |
| `ci` | CI 설정 변경 |
| `chore` | 그 외 잡무 |
| `revert` | 이전 커밋 되돌리기 |

**scope**: `wallet`, `payment`, `ledger`, `settlement`, `reconciliation`, `risk`, `operations`, `outbox`, `api`, `web`, `e2e`, `docs`, `deps`

**subject**: 영문 소문자, 명령형 현재시제, 마침표 없음, 50자 이내 (헤더 전체 72자 이하)

**body**: *무엇을*보다 *왜*. 헤더와 빈 줄로 구분.

**footer**: `Closes #123` / 파괴적 변경은 `feat(api)!:` + `BREAKING CHANGE: <설명>`

예시:

```
feat(payment): reserve cancellation amount on request

Concurrent partial cancellations could exceed the approved amount
because capacity was checked without reserving. The request now
increments processingCancellationAmount in the same transaction.

Closes #142
Refs INV-005
```

```
fix(ledger): reject journal with mixed currencies (INV-007)
```

**규칙**

- 커밋 하나에 논리적 변경 하나. 무관한 변경은 나눕니다.
- 커밋 전 빌드·테스트가 통과해야 합니다.
- 요구사항 ID(`FR-`, `INV-`, `ADR-`)와 관련된 변경은 footer에 남깁니다.
- Claude가 만드는 커밋에는 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` 트레일러를 붙입니다.

## 9. 브랜치와 커밋 진행 방식

- **작업을 마치면 요청을 기다리지 말고 커밋합니다.** 논리적 변경 하나가 끝날 때마다 커밋하며,
  여러 단계를 진행했다면 단계별로 나눠 커밋합니다.
- 커밋 전에 `./gradlew test`가 통과해야 합니다. 통과하지 못하면 커밋하지 말고 보고합니다.
- **`main`에서 바로 작업합니다** (2026-09-10 사용자 지시로 변경). 그전에는 `<type>/<설명>`
  브랜치에서 작업하고 사용자가 병합했습니다. 혼자 쓰는 저장소에서 브랜치·PR·머지 왕복이
  실제로 막아 주는 것보다 비용이 컸습니다.
  - `main`이 배포 가능한 상태여야 한다는 것은 **그대로입니다.** 브랜치가 사라졌으므로 그 보장을
    커밋 전 `./gradlew test`와 CI가 전부 짊어집니다. 통과하지 못하면 커밋하지 않습니다.
  - 되돌리기 어렵거나 위험한 변경은 여전히 브랜치에서 하고 사용자에게 알립니다.
- **푸시와 PR 생성은 사용자가 요청할 때만** 합니다. 커밋과 달리 외부에 나가는 동작이기 때문입니다.
- `git push --force`를 쓰지 않습니다. 필요하면 `--force-with-lease`를 사용자 승인 후에 사용합니다.

## 10. 문서 갱신 규칙

- 요구사항을 바꾸면 영향받는 정책·상태·DB·API·이벤트·테스트·ADR을 함께 검토합니다.
- ADR은 구현·실험으로 확인한 뒤에만 `Proposed` → `Accepted`로 바꿉니다.
- 성능·장애 결과는 [reports/11-performance-failure-report-template.md](reports/11-performance-failure-report-template.md)에 환경·커밋 SHA·원본 결과 경로와 함께 기록합니다.
- 구현이 설계와 달라지면 문서를 사실로 교체합니다. 문서를 이상적인 상태로 남겨두지 않습니다.

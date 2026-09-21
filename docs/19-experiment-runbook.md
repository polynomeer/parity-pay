# ParityPay 실험 실행 안내서 (DOC-19)

> **에이전트 지침**
> - **읽는 시점**: 부하·장애 시나리오를 **실행**하려 할 때. 결과를 읽는 법은 [DOC-20](20-experiment-result-interpretation.md)입니다.
> - **이 문서가 정하는 것**: 실험을 돌리는 절차와 명령, 사전 조건, 결과가 어디에 남는지. 실험의 목적·설계는 각 스크립트 머리말과 [reports/11](../reports/11-performance-failure-report.md)이 정하고, 이 문서는 그것을 **처음 보는 사람이 재실행할 수 있게** 모아 둔 것입니다.
> - **강제 규칙**: 여기 적힌 소요 시간·결과 수치는 실측된 것만 옮겼습니다. 새 실험을 추가하면 §6 카탈로그와 `load-tests/README.md` 표에 같이 적습니다.

## 1. 한 줄 답: 무엇이 자동이고 무엇이 아닌가

**시나리오를 실행하고, 장애를 주입하고, 결과를 세어서 파일로 남기는 것까지는 자동입니다.
그 파일을 읽고 보고서 문장을 쓰는 것은 사람이 합니다.**

| 단계 | 자동 여부 | 무엇이 하는가 |
|---|---|---|
| 스택 기동 (컨테이너·기관 대역·pay-api) | 자동 | `scripts/dev.sh`, 또는 실험 스크립트가 직접 jar를 띄움 |
| 장애 주입 (기관 모드 변경, `SIGKILL`, 브로커 정지, Redis 정지, 설정 변형) | 자동 | 실험 스크립트 |
| 부하 (k6, 스레드 풀) | 자동 | k6 스크립트·실험 스크립트 |
| 측정 (API 폴링, DB 읽기, `/actuator/prometheus`, `pg_stat_*`, 로그 카운트) | 자동 | 실험 스크립트 |
| 판정 (원장 1회인가, 불변조건 0인가, 증명했는가) | 자동 | 스크립트가 `PASS/FAIL`, `proved`, 기대 상태 비교를 출력 |
| 원본 결과 파일 (`raw.json`, `summary.json`, `report.txt`, k6 JSON, 로그) | 자동 | `/tmp/<실험>/…` |
| **보고서 문장·표·해석** ([reports/11](../reports/11-performance-failure-report.md), [reports/13](../reports/13-failure-scenario-matrix.md)) | **수동** | 사람이 원본 파일을 읽고 §7의 절차로 씀 |
| 보고서 생성기 | **없음** | 만들지 않았습니다. 이유는 §7 끝에 있습니다 |

그러니 "시나리오를 실행하고 결과를 리포트로 받는다"는 문장에서 **"리포트"가 원본 결과 파일과 콘솔 요약이면 예,
Markdown 보고서면 아니오**입니다.

## 2. 실행 층 네 가지

같은 "장애 시나리오"라도 네 층에서 다르게 돕니다. 무엇을 알고 싶은지에 따라 고릅니다.

| 층 | 어디 | 무엇을 하나 | 누가 판정하나 | 언제 쓰나 |
|---|---|---|---|---|
| **A. 장애 시뮬레이터** | 운영 콘솔 `https://ops.localhost:5174` → 장애 시뮬레이터 | 기관 모드를 바꾸는 버튼 9개 + 불변조건 카드 | 사람이 눈으로 | 처음 이해할 때, 시연할 때 |
| **B. 실험 하니스** | `load-tests/*.py`, `*.sh`, `*.js` | 앱을 띄우고 장애를 만들고 부하를 걸고 세어서 파일로 남김 | 스크립트가 세고, 사람이 해석 | 수치가 필요할 때 (reports/11의 P·F·M 전부) |
| **C. 통합 테스트** | `./gradlew test` (Testcontainers) | 경계를 흉내 낸 장애(F-003~F-011 등)를 매번 검증 | JUnit | 커밋마다. 사람이 손댈 것 없음 |
| **D. E2E** | `load-tests/run-e2e.sh`, `apps/e2e` | 실제 스택·실제 브라우저로 Shop→결제→장애→복구 한 바퀴 | Playwright | 주간·수동, 화면 계약이 바뀌었을 때 |

여기에 **관측 스택**이 걸쳐 있습니다 — `scripts/dev.sh --observability`로 띄우면 Prometheus(9090)·Grafana(3000)·
Jaeger(16686)가 함께 뜨고, `deploy/observability/rules/invariants.yml`의 경보 규칙이 A·B 어느 층에서 만든 장애든 같은
눈으로 봅니다. 실험 대부분은 관측 스택 없이 `/actuator/prometheus`를 직접 긁으므로 필수는 아닙니다.

## 3. 준비물

| 도구 | 왜 | 확인 |
|---|---|---|
| Java 21 | pay-api·기관 대역 실행 | `export JAVA_HOME=$(/usr/libexec/java_home -v 21); java -version` |
| Docker Desktop | PostgreSQL 17, Redpanda, mock-bank·mock-pg 컨테이너, `docker exec psql` | `docker info` |
| k6 | HTTP 부하 (P-001~P-004, M-007, M-009, M-013, M-019~M-023) | `brew install k6; k6 version` |
| Python 3 | 실험 하니스. **표준 라이브러리만** 씁니다 — pip 설치 없음 | `python3 --version` |
| pnpm + Playwright | E2E(D 층)만 | `pnpm install; pnpm --filter @paritypay/e2e exec playwright install chromium` |

모든 실험은 **한 노트북에서 부하 도구·앱·DB가 CPU를 나눠 쓰는** 구성으로 실행됐습니다(reports/11 §2). 다른 것을
같이 돌리면 수치가 흔들립니다. M-013은 실행 중에 누군가 `docker compose down`을 해서 한 번 처음부터 다시
했습니다. **실험 중에는 이 저장소의 컨테이너를 건드리지 않습니다.**

## 4. 스택을 띄우는 세 가지 방법과 실험이 원하는 것

실험마다 "무엇이 떠 있어야 하는가"가 다릅니다. 이것을 틀리면 실험이 실패하는 것이 아니라 **틀린 것을 잽니다**.

### 4.1 의존성만 띄우고 스크립트가 pay-api를 직접 띄움 (B 층 대부분)

```bash
docker compose up -d postgres redpanda mock-bank mock-pg
./gradlew :apps:pay-api:bootJar :apps:mock-bank:bootJar :apps:mock-pg:bootJar
```

스크립트는 `--jar`로 받은 jar를 자기 포트(8085~8093, 8180~)에 띄우고, 끝나면 내립니다. 앱을 스크립트가 띄우는
이유는 **실험마다 설정을 바꿔야 하기 때문**입니다(읽기 타임아웃, 커넥션 풀, `experiment-*` 프로필, 인스턴스 수).

> 기관 컨테이너는 `apps/mock-bank/build/libs`를 마운트해 실행합니다. **jar가 없으면 컨테이너가 뜨자마자 죽습니다.**
> 로컬에는 보통 있어서 오래 드러나지 않았던 함정입니다.

### 4.2 `scripts/dev.sh` — 사람이 쓰는 전체 스택 (A 층, 시나리오 매트릭스, k6 원시 실행)

```bash
scripts/dev.sh                # 컨테이너 → 기관 → pay-api(local 프로필) → 고객 앱 5173 · 운영 콘솔 5174
scripts/dev.sh status         # 무엇이 어느 포트에 떠 있는지
scripts/dev.sh down           # 전부 내림 (볼륨은 남김)
```

잡힌 포트는 자동으로 우회하고 우회한 값을 관련된 곳 전부에 넣습니다(ADR-013). 우회됐다면 `status`가 보여 주는
포트를 스크립트 인자에 넘겨야 합니다 — 특히 `PARITYPAY_DB_PORT`(4.4).

### 4.3 `deploy/run.sh` — 배포 형태 (nginx·TLS·비밀값 주입)

```bash
deploy/run.sh                 # 이미지 빌드 포함. https://app.localhost:8181, https://ops.localhost:8182
deploy/run.sh down
```

실험용이 아니라 ADR-011의 검증용입니다. E2E의 `deploy` 잡이 이 스택으로 돕니다.

### 4.4 서로 부딪히는 것

| 상황 | 무엇이 잘못되나 | 규칙 |
|---|---|---|
| `dev.sh`의 pay-api가 떠 있는데 4.1 실험을 돌림 | 두 pay-api가 **같은 DB·같은 토픽**을 씁니다. 소비자가 둘이 되어 재분배·중복·처리량이 전부 오염됩니다 | Kafka·외부격리 실험은 발견하면 **먼저 내립니다**. 나머지는 내리지 않으니 `scripts/dev.sh down`을 먼저 하십시오 |
| DB 호스트 포트가 5432가 아님 | 스크립트 기본 `--db-url`이 5432입니다 | `PARITYPAY_DB_PORT=<n>`을 넘기거나 `--db-url`을 명시합니다 |
| `docker compose down`을 실험 중에 함 | 실험이 중간에 죽고, 그때까지의 `raw.json`만 남습니다 | 실험 중에는 손대지 않습니다 |
| 이전 실험이 기관 모드를 `TIMEOUT_*`로 남김 | 다음 실험의 "정상" 결과가 전부 202 | 스크립트는 끝에 `NORMAL`로 되돌립니다. 중간에 죽였다면 `POST /api/v1/admin/mock-bank/mode`로 직접 되돌립니다 |
| 이전 실험이 남긴 이벤트·적체 | 소비자가 그것부터 읽어 이번 실험의 정산 항목이 늦게 생김 | Outbox·Kafka 실험은 `TRUNCATE outbox_event, consumed_event`와 토픽 삭제를 스스로 합니다. 다른 실험 앞에는 `docker compose down -v`가 가장 확실합니다 |

## 5. 처음이라면 이 순서로 (약 30분)

1. **버튼으로 본다 (A 층, 5분).** `scripts/dev.sh` → 운영 콘솔 로그인(`ops-operator@paritypay.local` /
   `local-ops-password`) → 장애 시뮬레이터에서 "은행이 출금 뒤에 응답을 끊음" 적용 → 고객 앱(5173)에서 충전.
   202를 받고 약 35초 뒤 `SUCCEEDED`가 되는 것, 그동안 불변조건 카드 넷이 전부 "정상"인 것을 봅니다.
2. **같은 것을 스크립트로 잰다 (B 층, 2분).**
   ```bash
   python3 load-tests/scenario-matrix.py --only bank-timeout-after --runs 1
   ```
   콘솔에 `run 0: HTTP 202 UNKNOWN → SUCCEEDED in 34.1s, attempts=None, ledger=1, Δbalance=10000 …` 같은 줄과 불변조건
   게이지가 찍히고 `/tmp/scenario-matrix/<UTC>/summary.json`이 생깁니다. 1번에서 눈으로 본 것이 이 숫자입니다(34.1초는 reports/13의
   실측 예이며 실행마다 몇 초 다릅니다).
3. **프로세스를 죽여 본다 (B 층, 3분).** `scripts/dev.sh down` 후
   ```bash
   ./gradlew :apps:pay-api:bootJar :apps:mock-bank:bootJar :apps:mock-pg:bootJar
   docker compose up -d postgres redpanda mock-bank mock-pg
   python3 load-tests/crash-recovery-experiment.py --jar apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar
   ```
   트래픽 중 `SIGKILL`, 재시작, 재전송 뒤 검증 14개가 `[PASS]`로 찍힙니다. 하나라도 `[FAIL]`이면 종료 코드 1입니다.
4. **읽는 법을 배운다.** [DOC-20](20-experiment-result-interpretation.md) §4.2·§4.3이 방금 본 두 출력의 각 줄을 설명합니다.

## 6. 실험 카탈로그

ID는 reports/11의 절 이름입니다. "소요"는 실측이며 `--runs`·`--sizes`에 비례합니다. 결과 위치는 기본값이고
`--out`으로 바꿉니다. "기록"은 결과가 적힌 보고서 절입니다.

### 6.1 성능 (P)

| ID | 무엇을 재나 | 실행 | 사전 조건 | 소요 | 결과 | 기록 |
|---|---|---|---|---|---|---|
| P-001 | 서로 다른 지갑 결제 처리량 기준선 | `k6 run load-tests/payment-baseline.js` | 4.2 스택 (`BASE_URL` 기본 8080) | 약 2분 | k6 콘솔 요약 (`--summary-export`로 JSON) | reports/11 §3 |
| P-002 | 동일 지갑 경합 | `k6 run -e SAME_WALLET=true load-tests/payment-baseline.js` | 위와 같음 | 약 2분 | 위와 같음 | §3 |
| P-003 | 충전 부하 뒤 Outbox 적체 해소 | `k6 run load-tests/topup-outbox-backlog.js` + `/actuator/prometheus`의 `paritypay_outbox_*` 관찰 | 위와 같음 | 약 2분 + 해소 시간 | k6 요약 + 지표 | §3 |
| P-004 | 정산 배치·대사가 도는 동안 API 지연 | `RATE=12 JAR=<bootJar> RUNS=3 load-tests/run-p004.sh` | 4.1 (스크립트가 앱을 띄움), k6 | 실행당 약 4분 | `/tmp/p004/` (k6 JSON, 앱 로그, 풀 샘플) | §3 |
| P-005 | 발행 경로만 떼어낸 적체 해소율 | `EVENTS=20000 RUNS=3 load-tests/outbox-drain-benchmark.sh` | 앱이 떠 있고 발행기 활성 | 실행당 1분 내 | 콘솔 (초당 건수) | §3 |

P-004의 `RATE`는 **기준선 구간에서 오류가 없는 값으로 먼저 보정**합니다. 오류가 나는 부하로 비교하면 차이가
잡음에 묻힙니다(40으로 시작했다가 12로 내렸습니다).

### 6.2 장애 (F)

| ID | 무엇 | 어디서 검증되나 | 실행 |
|---|---|---|---|
| F-001·F-002 | 커밋 전/후 `SIGKILL` → 재시작·재전송 → 정확히 1회 | **실제 프로세스 종료** | `python3 load-tests/crash-recovery-experiment.py --jar <bootJar>` (4.1, 약 3분, 콘솔 `[PASS]/[FAIL]`, 로그 `/tmp/crash-experiment.log`) |
| F-003 | Outbox 발행 전 종료 | 통합 테스트 `OutboxIntegrationTest` | `./gradlew :apps:pay-api:test --tests "*OutboxIntegrationTest*"` |
| F-004 | 브로커·소비 ACK 유실 (중복 전달) | `OutboxIntegrationTest` | 위와 같음 |
| F-006 | 승인 후 응답 유실 | `TopUpRecoveryIntegrationTest` | `./gradlew test --tests "*TopUpRecoveryIntegrationTest*"` |
| F-007 | 취소(환불) 성공 후 응답 유실 | `PgRefundIntegrationTest` | `./gradlew test --tests "*PgRefundIntegrationTest*"` |
| F-008 | 중복·역순 웹훅 | `PgWebhookIntegrationTest` | `./gradlew test --tests "*PgWebhookIntegrationTest*"` |
| F-009 | 조회 API 장애 → 백오프 → 수동 검토 | `PaymentRecoveryIntegrationTest` | `./gradlew test --tests "*PaymentRecoveryIntegrationTest*"` |
| F-010 | 정산 지급 응답 유실 / 원장·스냅샷 차이 | `SettlementIntegrationTest`, `BalanceSnapshotRebuildTest` | `./gradlew test --tests "*SettlementIntegrationTest*"` |
| F-011 | 기관 명세를 못 받은 날의 대사 | `ReconciliationIntegrationTest` | `./gradlew test --tests "*ReconciliationIntegrationTest*"` |

F-003~F-011은 **매 커밋 `./gradlew test`에 포함**되어 있습니다. 따로 돌릴 것은 F-001·F-002뿐입니다. 캐시된 결과
(`FROM-CACHE`)는 실행이 아니므로 보고할 때는 `--rerun-tasks --no-build-cache`를 붙입니다.

### 6.3 측정 (M) — 실험 하니스

| ID | 무엇 | 실행 | 사전 조건 | 소요 | 결과 | 기록 |
|---|---|---|---|---|---|---|
| M-001 | 발행기 1·2·4대가 같은 적체를 비울 때 유실·중복·순서 | `python3 load-tests/multi-instance-experiment.py --jar <bootJar>` | 4.1 | 대수×3회, 약 10분 | 콘솔 표 | §4 M-001 |
| M-002 | 소비자 다중 인스턴스 경쟁 | `ConsumerMultiInstanceTest` (통합 테스트) | — | 테스트 | JUnit | §4 M-002 |
| M-003 | 순서 보장의 head-of-line 비용 | `multi-instance-experiment.py --partition-keys …` | 4.1 | 위와 같음 | 콘솔 표 | §4 M-003 |
| M-004 | 선점 쿼리가 적체 전체를 훑던 원인 | `EXPLAIN` 손 실행 (스크립트 없음) | postgres | — | reports/11 본문 | §4 M-004 |
| M-005 | 실제 재분배 중 중복 전달 | `python3 load-tests/rebalance-experiment.py --jar <bootJar>` | 4.1 | 약 2분 | 콘솔, 종료 코드 | §4 M-005 |
| M-006 | 원장 집계 비용 (계정 하나 / 전 지갑 불일치) | `python3 load-tests/ledger-aggregation-benchmark.py` | postgres만. **표를 비우고 합성 데이터로 채움** | 약 5분 | 콘솔 (EXPLAIN ANALYZE ms) | §4 M-006 |
| M-007 | 동일 지갑 경합의 원인 (문장·대기 이벤트·풀) | `python3 load-tests/lock-wait-experiment.py --jar <bootJar>` | 4.1 + k6 + `pg_stat_statements`(compose에 있음) | 모드당 약 2분 | `/tmp/m007/` (`k6-*.json`, 콘솔 표) | §4 M-007 |
| M-007 | 결제 한 건의 문장 순서·잠금 보유 구간 | `python3 load-tests/transaction-timeline.py --port 8080` | 4.2 스택 | 초 단위 | 콘솔 | §4 M-007·M-010 |
| M-008 | 거래내역 커서 조회의 깊은 페이지 | `python3 load-tests/transaction-history-benchmark.py --rows 1000000` | postgres만. **`wallet_transaction`을 비움 — 로컬 전용** | 약 5분 | 콘솔 | §4 M-008 |
| M-009 | 취소 경로의 잠금 보유 구간 | `lock-wait-experiment.py --modes same-wallet --script load-tests/cancellation-contention.js --count-statement "insert into payment_cancellation "` | M-007과 같음 | 약 2분 | `/tmp/m007/` | §4 M-009 |
| M-011 | 미확정 한 건이 확정되기까지 (충전·카드 결제) | `python3 load-tests/recovery-latency-experiment.py --jar <bootJar> --runs 12 --target both` | 4.1 | 건당 35~45초, 12건×2경로 약 17분 | `/tmp/m011/report.txt`, `raw.json` | §4 M-011 |
| M-012 | 미확정 수백 건이 쌓였을 때 확정 지연 | `python3 load-tests/recovery-backlog-experiment.py --jar <bootJar> --sizes 50,200,500 --target all` | 4.1 | 크기·경로당 1~3분, 전부 약 30분 | `/tmp/m012/report.txt`, `raw.json` | §4 M-012 |
| M-013 | VU를 올리면 DB가 어디에서 먼저 막히는가 | `python3 load-tests/db-saturation-experiment.py --jar <bootJar> --vus 10,20,40,80 --pools 20,40` | M-007과 같음 | 점당 약 2분, 8점 약 20분 | `/tmp/m013/report.txt`, `raw.json` | §4 M-013 |
| M-014 | 장애 시뮬레이터 시나리오 9개 전부 | `python3 load-tests/scenario-matrix.py --runs 3` | **4.2 스택** (`dev.sh`) | 3회 약 13분 | `/tmp/scenario-matrix/<UTC>/summary.json`, `results.json` | **reports/13** |
| M-015 | 소비자 커밋 방식 (BATCH vs 자동) + 커밋 전 죽음 | `python3 load-tests/kafka-failure-experiment.py duplicate --jar $J --commit batch,auto --runs 3` | 4.1 (dev.sh pay-api는 자동으로 내림) | 변형당 약 5분 | `/tmp/kafka-failure/duplicate-<UTC>.json` | §4 M-015 |
| M-016 | producer acks + 브로커 `SIGKILL` | `kafka-failure-experiment.py loss --jar $J --acks all,1,0 --runs 3` | 위와 같음 | 변형당 약 5분 | `loss-<UTC>.json` | §4 M-016 |
| M-017 | 파티션 키 Aggregate vs 랜덤 | `kafka-failure-experiment.py order --jar $J --key aggregate,random --runs 3` | 위와 같음 | 변형당 약 5분 | `order-<UTC>.json` | §4 M-017 |
| M-018 | poison message가 파티션을 얼마나 멈추나 | `kafka-failure-experiment.py poison --jar $J --runs 3` | 위와 같음 | 약 5분 | `poison-<UTC>.json` | §4 M-018 |
| M-019 | 타임아웃 없음 + 기관 30초 지연 (플랫폼 vs 가상 스레드) | `python3 load-tests/external-isolation-experiment.py exhaust --jar $J --runs 3` | 4.1 + k6 (dev.sh pay-api 자동으로 내림) | 변형당 75초×3 | `/tmp/external-isolation/exhaust-<UTC>.json`, jstack | §4 M-019 |
| M-020 | 타임아웃 + 재시도 (없음/즉시/지수+지터) | `external-isolation-experiment.py retry --jar $J --runs 3` | 위와 같음 | 변형당 30초×3 | `retry-<UTC>.json` | §4 M-020 |
| M-021 | 차단기 상태 전이·회복 | `external-isolation-experiment.py circuit --jar $J --runs 3` | 위와 같음 | 변형당 90초×3 | `circuit-<UTC>.json` | §4 M-021 |
| M-022 | 기관 TPS 상한 알고리즘 셋 | `external-isolation-experiment.py ratelimit --jar $J --runs 3` | 위와 같음 | 변형당 20초×3 | `ratelimit-<UTC>.json` | §4 M-022 |
| M-023 | 벌크헤드 50이 붕괴를 막는가 | `external-isolation-experiment.py bulkhead --jar $J --runs 3` | 위와 같음 | 75초×3 | `bulkhead-<UTC>.json` | §4 M-023 |
| M-024 | 분산락 lease 만료 → 이중 소유 | `python3 load-tests/lock-lease-experiment.py lease --jar $J --runs 3` | 4.1 + Redis(스크립트가 띄우고 내림) | 60초×3 | `/tmp/lock-lease/lease-<UTC>.json` | §4 M-024 |
| M-025 | Watchdog 자동 연장 / `SIGSTOP` | `lock-lease-experiment.py watchdog --jar $J --runs 3` | 위와 같음 | 변형당 60초×3 | `watchdog-<UTC>.json` | §4 M-025 |
| M-026 | Fencing token 두 방식 | `lock-lease-experiment.py fencing --jar $J --runs 3` | 위와 같음 | 변형당 60초×3 | `fencing-<UTC>.json` | §4 M-026 |
| M-027 | 대조군: 분산락 없이 조건부 UPDATE | `lock-lease-experiment.py control --jar $J --runs 3` | 위와 같음 | 60초×3 | `control-<UTC>.json` | §4 M-027 |
| M-028 | 부하 중 Redis kill (fail-closed/open/fencing/대조군) | `lock-lease-experiment.py redisdown --jar $J --runs 3` | 위와 같음 | 변형당 60초×3 | `redisdown-<UTC>.json` | §4 M-028 |
| M-010 | 차감 직전 명시적 flush | `transaction-timeline.py` (문장 순서로 판정) | 4.2 스택 | 초 단위 | 콘솔 | §4 M-010 |

`$J`는 `apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar`입니다. M-015~M-028은 **`experiment-*` 프로필과
`EXPERIMENT_*` 환경변수로만** 기본값에서 벗어나며, 스크립트가 넣어 줍니다. 그 프로필은 실험 뒤 채택하지 않은 경로
(재시도, 분산락, 랜덤 파티션 키)를 **일부러 만들어 대조**하는 것이라 운영 설정에 없습니다.

### 6.4 E2E

| 무엇 | 실행 | 소요 |
|---|---|---|
| 실제 스택·브라우저로 7건 (Shop→결제→장애→복구, 세션 쿠키, 두 탭 재발급, 비밀번호 재설정 메일) | `load-tests/run-e2e.sh` (스택을 띄우고 돌리고 내림) | 약 1분 + 빌드 |
| 배포 형태로 같은 것 | `deploy/run.sh` 후 `E2E_CUSTOMER_URL=https://app.localhost:8181 E2E_OPS_URL=https://ops.localhost:8182 E2E_API_URL=https://localhost:8182 E2E_MAILPIT_URL=http://localhost:8125 pnpm --filter @paritypay/e2e e2e` | 약 2분 + 이미지 빌드 |
| CI | `.github/workflows/e2e.yml` — 주간 + 수동 (`gh workflow run e2e.yml`) | 약 10분 |

## 7. 결과를 보고서로 옮기는 절차

스크립트는 파일을 남기고 끝납니다. 그 뒤는 다음 순서입니다. reports/11 §1의 강제 규칙("측정하지 않은 값을
채우지 않는다")이 이 절차의 이유입니다.

1. **원본을 저장소에 복사합니다.** `/tmp`는 사라집니다.
   ```bash
   cp /tmp/scenario-matrix/20260915T142536Z/summary.json reports/data/13-scenario-matrix-20260915T142536Z.json
   ```
   이름은 `<보고서 번호>-<실험 ID>-<무엇>-<날짜 또는 UTC 시각>.json`입니다. 로그에서 파생한 값은 `-log-derived.txt`로
   같이 둡니다(reports/data에 예가 있습니다).
2. **환경을 적습니다.** 커밋 SHA(`git rev-parse --short HEAD` — 스크립트 대부분이 JSON에 `commit`으로 넣어 둡니다),
   기계, 스택 구성(dev.sh인지 스크립트가 띄웠는지), 바꾼 설정. reports/11 §2 표를 따릅니다.
3. **절을 씁니다.** reports/11의 M-0xx 절이 전부 같은 뼈대입니다 — **목적 · 방법 · 실행(명령) · 결과 표 · 분석 ·
   미측정 · 원본 경로.** 새 절도 그 뼈대로 씁니다. 표의 숫자는 원본 파일에서 옮기고, 3회 실행이면 세 값을 다 적거나
   중앙값과 편차를 적습니다. 실패한 실행, 증명하지 못한 실행도 그대로 적습니다.
4. **해석은 [DOC-20](20-experiment-result-interpretation.md)의 판정 순서**로 씁니다 — 불변조건 → 정확히 1회 →
   시간·처리량. "빠르다/느리다"보다 먼저 "돈이 맞는가"입니다.
5. **결함을 찾았으면** 다음 문자(현재 A~N)를 붙여 "M-0xx가 찾아낸 결함 X" 절을 만들고, 고친 커밋과 **고친 뒤 재측정**을
   같은 절에 적습니다. 결함 K·L·M·N 절이 본보기입니다. 고치기 전 수치를 지우지 않습니다 — 전후 비교가 곧 증거입니다.
6. **다른 문서에 파급을 반영합니다.** 상수가 바뀌면(복구 주기, 폴링 상한) DOC-14·DOC-09를, 결정이 확정되면 ADR의
   Outcome을, 요약 수치(실험 수·결함 수)가 바뀌면 CLAUDE.md §1·README·reports/12를 고칩니다.
7. `python3 scripts/check-docs-links.py`로 링크를 확인하고 `docs:` 커밋으로 남깁니다.

**보고서 생성기를 만들지 않은 이유.** 스크립트 출력은 실험마다 다른 모양이고, 값 표는 이미 스크립트가 만듭니다
(`report.txt`). 보고서에서 사람이 하는 일은 표를 옮기는 것이 아니라 **"이 숫자가 왜 이 모양인가"를 로그·설정·산술과
맞춰 보는 것**이고(예: 43초 = grace 30 + 백오프 2+4 + 틱), 그 부분이 자동화되면 측정하지 않은 해석이 문서에 들어갑니다.
값이 필요한 곳(문서 §1 수치, 결함 수)은 `scripts/check-invariant-coverage.py`처럼 **검사기**로 어긋남을 잡는 쪽을
택했습니다.

## 8. 흔한 함정 — 전부 실제로 겪은 것

| 함정 | 증상 | 근거 |
|---|---|---|
| 두 빌드를 순차 비교 (A 3회 뒤 B 3회) | 기계 상태 변화가 B의 성과로 보임. 코드와 무관한 **4.8배**가 그렇게 나왔음 | reports/11 M-007 |
| 기준선에서 이미 오류 나는 부하로 비교 | 무엇을 바꿔도 차이가 잡음에 묻힘 | P-004 |
| 처리량을 상위 15개 문장 목록에서 찾음 | 취소 문장이 목록 밖으로 밀려 처리량 **0**으로 보고 — 값이 아니라 측정 실패 | M-009 (`--count-statement`) |
| 재현하려던 현상이 0건인데 "통과"로 셈 | 중복이 안 생겼으면 멱등성을 증명한 것이 아님 | M-005·M-015~M-018의 `proved` |
| 실행 중 컨테이너 정리 | 실험이 죽고 처음부터 | M-013 |
| `dev.sh` pay-api를 둔 채 Kafka 실험 | 같은 토픽을 두 소비자가 읽어 수치 오염 | M-015 (스크립트가 내리게 함) |
| 기관 대역의 Tomcat 200 스레드 × 응답 붙잡기 | 주입이 기관에서 막혀 "적체"가 아니라 "기관 포화"를 잼 | M-012 (읽기 타임아웃 500 ms, 주입 워커 50) |
| 로컬 스택의 기관에 웹훅 주소가 없음 | 웹훅 시나리오 셋이 실제로는 알림을 받지 않았는데 화면은 "적용했습니다" | reports/13 발견 1 (고침) |
| 캐시된 테스트 결과 보고 | `FROM-CACHE`는 실행이 아님 | CLAUDE.md §7 |
| macOS `ps %cpu`는 수명 평균 | CPU 곡선이 평평하게 보임 | M-013 (`cputime` 차분으로 바꿈) |
| 첫 확인이 `localhost:8181`·`:8182` | 쿠키는 포트를 구분하지 않아 세션 분리를 확인 못 함 | ADR-011 |

## 9. 정리

- 스크립트가 띄운 pay-api·Redis는 스크립트가 내립니다. 죽였다면 `pkill -f pay-api-0.1.0-SNAPSHOT.jar`.
- 기관 모드는 스크립트가 `NORMAL`로 되돌립니다. 중간에 죽였다면 운영 콘솔 장애 시뮬레이터에서 "정상 처리"를 적용하거나,
  운영자 토큰으로 `POST /api/v1/admin/mock-bank/mode {"mode":"NORMAL"}`·`POST /api/v1/admin/mock-pg/mode
  {"mode":"NORMAL","webhookMode":"NORMAL","statusQueryAvailable":true}`를 보냅니다.
- 컨테이너는 **자기가 띄운 것만** 내립니다. `docker compose down -v`는 데이터까지 지웁니다 — 다음 실험 전에 빈 스키마가
  필요할 때만.
- `/tmp/<실험>/`은 보고서에 옮긴 뒤에는 지워도 됩니다. 옮기기 전에는 지우지 않습니다.

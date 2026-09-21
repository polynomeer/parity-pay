# ParityPay 실행·데모 안내서 (DOC-21)

> **에이전트 지침**
> - **읽는 시점**: 스택을 처음 띄울 때, API로 한 바퀴 돌려 보고 싶을 때, 장애를 손으로 재현해 보고 싶을 때.
> - **이 문서가 정하는 것**: 아무것도 정하지 않습니다. 실행 방법의 기준은 [ADR-013](adr/013-local-dev-stack-script.md)(로컬 스택)·[ADR-011](adr/011-deployment-shape.md)(배포 형태)이고, API 계약의 기준은 [DOC-08](08-db-api-event-spec.md)과 생성된 [OpenAPI](api/openapi.json)입니다.
> - **강제 규칙**: 여기 적힌 명령이 문서와 어긋나면 이 문서를 고칩니다. 실험 실행은 [DOC-19](19-experiment-runbook.md)가 담당합니다.

이 문서는 [README](../README.md)의 "실행 방법"을 자세히 풀어 쓴 것입니다. README는 "무엇을 왜 만들었고 어떻게 검증했나"를,
이 문서는 "그것을 내 기계에서 어떻게 돌려 보나"를 맡습니다.

## 1. 필요한 것

| 도구 | 버전 | 용도 |
|---|---|---|
| JDK | 21 | 백엔드 빌드·실행. Gradle 8.14.2가 Java 21에서 돕니다 |
| Docker (Desktop) | 28+ | PostgreSQL·Redpanda·기관 대역·관측성 스택 |
| Node + pnpm | Node 20+, pnpm 9+ | 고객 앱·운영 콘솔·E2E (화면까지 보려면) |
| k6 | 1.0+ | 부하 실험 (선택) |
| jq, curl | — | 아래 데모 명령 |

`JAVA_HOME`이 다른 버전을 가리키면 먼저 바꿉니다.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

## 2. 가장 빠른 길 — 한 줄로 전부 띄우기

컨테이너(PostgreSQL·Redpanda·Mailpit) → 기관 대역(Mock Bank·Mock PG) → `pay-api` → 고객 앱·운영 콘솔 순서로 올리고
주소를 출력합니다. 기본 포트가 다른 프로젝트에 잡혀 있으면 빈 포트로 우회하고, 그 포트를 알아야 하는 쪽(Redpanda
광고 주소, Vite 프록시, 재설정 메일 링크) 전부에 같은 값을 넣습니다. 근거: [ADR-013](adr/013-local-dev-stack-script.md)

```bash
scripts/dev.sh                 # up. --observability로 Grafana·Jaeger까지, --no-build로 jar 재빌드 생략
scripts/dev.sh status
scripts/dev.sh logs api        # api | customer | ops 는 호스트 프로세스 로그, 그 외는 compose 서비스 이름(mock-bank, redpanda …)
scripts/dev.sh down            # -v를 붙이면 볼륨까지 지웁니다
```

뜨면 아래 주소입니다(포트를 우회했으면 스크립트가 출력한 값을 씁니다).

| 무엇 | 주소 | 로그인 |
|---|---|---|
| 고객 앱 | http://localhost:5173 | 가입해서 씁니다 |
| 운영 콘솔 | http://localhost:5174 | `ops-operator@paritypay.local` / `local-ops-password` (`local` 프로필이 만듭니다) |
| API (Swagger UI) | http://localhost:8080/swagger-ui.html | 운영자 토큰 필요 |
| Mailpit (재설정 메일) | http://localhost:8025 | — |

운영 콘솔의 **장애 시뮬레이터**에서 시나리오를 적용하고 고객 앱에서 충전·결제를 실행하면, 미확정 거래가 복구되는
동안 **불변조건 카드가 계속 정상으로 유지되는 것**을 볼 수 있습니다. 시나리오 9개의 실측은
[reports/13](../reports/13-failure-scenario-matrix-report.md)에 있습니다.

## 3. 손으로 밟는 길

무엇이 무엇에 의존하는지 보고 싶을 때의 순서입니다.

```bash
# 1. 컨테이너. 기관용 데이터베이스는 볼륨이 비어 있을 때 만들어집니다 — 이미 쓰던 환경이면 한 번 비웁니다.
docker compose down -v
docker compose up -d postgres redpanda mailpit

# 2. 외부기관 둘은 별도 프로세스이고 자기 데이터베이스를 씁니다. 먼저 떠 있어야 충전·결제·정산 지급이 됩니다.
./gradlew :apps:mock-bank:bootJar :apps:mock-pg:bootJar
docker compose up -d mock-bank mock-pg

# 3. pay-api. local 프로필이 서명 키와 운영자 계정을 만듭니다. 프로필 없이는 비밀값이 없어 뜨지 않습니다(ADR-011).
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:pay-api:bootRun

# 4. 화면 (다른 터미널). 두 앱은 서로 다른 오리진입니다 — 운영 콘솔 코드가 고객 브라우저로 내려갈 이유가 없습니다.
pnpm install
pnpm --filter @paritypay/web-customer dev    # http://localhost:5173
pnpm --filter @paritypay/web-ops dev         # http://localhost:5174
```

운영 환경에서는 `PARITYPAY_JWT_SECRET`을 주입하고 `paritypay.security.bootstrap-operators`를 비웁니다. 배포 형태 그대로
띄워 보려면 `deploy/run.sh`입니다 — 이미지를 빌드하고 두 호스트이름(`app.localhost`·`ops.localhost`), TLS, 생성된
비밀값으로 올립니다. 근거: [ADR-011](adr/011-deployment-shape.md)

## 4. 테스트

```bash
./gradlew test                                   # 백엔드 전체 (Testcontainers가 PostgreSQL·Redpanda를 직접 띄움)
./gradlew test --rerun-tasks --no-build-cache    # 캐시(FROM-CACHE) 없이 실제로 다시 실행
./gradlew :modules:ledger:test                   # 모듈 하나
pnpm -r test                                     # 프론트엔드
load-tests/run-e2e.sh                            # 실제 스택 E2E (스택을 띄우고 돌리고 정리, 약 1분)
```

통합 테스트는 외부 기관도 별도 Spring 컨텍스트로 **실제로** 띄웁니다. 기관에는 자기 데이터베이스가 따로 만들어지므로
우리 코드가 기관의 표를 조인할 수 없고, 타임아웃은 흉내가 아니라 진짜 읽기 타임아웃입니다. 그래서 `docker compose`
없이도 실행됩니다.

## 5. API로 한 바퀴 — 충전·결제·부분 취소

```bash
# 1. 가입 (공개)
MEMBER=$(curl -s -X POST localhost:8080/api/v1/members \
  -H 'Content-Type: application/json' \
  -d '{"email":"buyer@example.com","password":"password1234"}')
WALLET_ID=$(echo "$MEMBER" | jq -r .walletId)

# 2. 로그인해서 토큰을 받습니다. 이후 모든 호출에 필요합니다.
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/tokens \
  -H 'Content-Type: application/json' \
  -d '{"email":"buyer@example.com","password":"password1234"}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"

# 3. 계좌 연결과 충전. 금융 쓰기에는 Idempotency-Key가 필수입니다 — 같은 키로 다시 보내면 같은 결과가 옵니다.
BANK_ID=$(curl -s -X POST localhost:8080/api/v1/bank-accounts \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"bankCode":"004","accountNumber":"110-1234-5678","initialBalance":1000000}' | jq -r .bankAccountId)

curl -s -X POST localhost:8080/api/v1/top-ups \
  -H "$AUTH" -H 'Idempotency-Key: demo-top-up-0001' -H 'Content-Type: application/json' \
  -d "{\"walletId\":\"$WALLET_ID\",\"bankAccountId\":\"$BANK_ID\",\"amount\":100000,\"currency\":\"KRW\"}"

# 4. 결제 후 부분 취소
PAYMENT_ID=$(curl -s -X POST localhost:8080/api/v1/payments \
  -H "$AUTH" -H 'Idempotency-Key: demo-payment-0001' -H 'Content-Type: application/json' \
  -d "{\"orderId\":\"order-1\",\"walletId\":\"$WALLET_ID\",\"merchantId\":\"$(uuidgen)\",\"amount\":30000,\"currency\":\"KRW\",\"method\":\"PAY_MONEY\"}" | jq -r .paymentId)

curl -s -X POST "localhost:8080/api/v1/payments/$PAYMENT_ID/cancellations" \
  -H "$AUTH" -H 'Idempotency-Key: demo-cancel-0001' -H 'Content-Type: application/json' \
  -d '{"amount":10000,"currency":"KRW","reason":"PARTIAL_RETURN"}'

# 5. 잔액과 거래내역
curl -s "localhost:8080/api/v1/wallets/$WALLET_ID" -H "$AUTH"
curl -s "localhost:8080/api/v1/wallets/$WALLET_ID/transactions?limit=10" -H "$AUTH"

# 6. 운영자로 로그인하면 타임라인, 미확정 거래, 원장 검증을 볼 수 있습니다.
OPS=$(curl -s -X POST localhost:8080/api/v1/auth/tokens \
  -H 'Content-Type: application/json' \
  -d '{"email":"ops-operator@paritypay.local","password":"local-ops-password"}' | jq -r .accessToken)
curl -s "localhost:8080/api/v1/admin/transactions/$PAYMENT_ID/timeline" -H "Authorization: Bearer $OPS"
curl -s "localhost:8080/api/v1/wallets/$WALLET_ID/ledger-verification" -H "Authorization: Bearer $OPS"
```

마지막 호출이 이 프로젝트의 핵심 검사입니다: 지갑 스냅샷과 원장 계산값이 같은가(INV-010).

## 6. 장애를 손으로 재현하기

### 외부 승인 후 응답 유실 → `UNKNOWN` 보존 → 조회로 확정

```bash
# 1. 은행은 출금을 처리하지만 응답이 유실되도록 설정 (mock-bank 제어는 OPS_OPERATOR 권한)
curl -s -X POST localhost:8080/api/v1/admin/mock-bank/mode \
  -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d '{"mode":"TIMEOUT_AFTER_WITHDRAWAL"}'

# 2. 충전을 보내면 실패(4xx/5xx)가 아니라 202 UNKNOWN으로 응답합니다. 돈이 움직였는지 아직 모르기 때문입니다.
# 3. 미확정 목록에서 확인
curl -s "localhost:8080/api/v1/admin/top-ups?status=UNKNOWN" -H "Authorization: Bearer $OPS"

# 4. 은행을 정상으로 되돌리면 복구 작업이 조회로 확정합니다 (기본 5초 주기)
curl -s -X POST localhost:8080/api/v1/admin/mock-bank/mode \
  -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' -d '{"mode":"NORMAL"}'

# 즉시 확정을 요청할 수도 있습니다. 사유가 필수이며 감사 로그로 남습니다.
curl -s -X POST "localhost:8080/api/v1/admin/top-ups/$TOP_UP_ID/resolve" \
  -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
  -d '{"reason":"고객 문의"}'
```

### 기관 프로세스를 죽여 연결 거부 만들기

```bash
docker compose stop mock-bank    # 충전 요청이 UNKNOWN으로 보존되는지 확인
docker compose start mock-bank   # 복구 작업이 조회로 확정합니다

docker compose stop mock-pg      # 카드 결제만 막힙니다. 페이머니 결제와 충전은 계속 됩니다
docker compose start mock-pg
```

### 잔액 스냅샷이 원장과 어긋났을 때

스냅샷을 원장으로 되돌립니다. 원장은 진실이므로 읽기만 합니다. 쓸 수 있는 값은 원장 계산값 하나뿐이라 이 경로로
없는 돈을 만들 수 없고, 자동으로 돌지 않습니다.

```bash
# 1. 차이 확인 (지표로도 보입니다: paritypay_invariant_balance_snapshot_drift)
curl -s "localhost:8080/api/v1/wallets/$WALLET_ID/ledger-verification" -H "Authorization: Bearer $OPS"

# 2. 원인을 먼저 조사한 뒤 재구축합니다. 요청자와 다른 OPS_APPROVER의 승인이 필요합니다.
curl -s -X POST "localhost:8080/api/v1/admin/wallets/$WALLET_ID/balance-rebuild" \
  -H "Authorization: Bearer $OPS" -H 'X-Approver-Id: ops-approver@paritypay.local' \
  -H 'Content-Type: application/json' -d '{"reason":"스냅샷 드리프트 복구"}'
```

절차는 [DOC-09 §12](09-consistency-recovery.md)에 있습니다.

## 7. 관측

`scripts/dev.sh --observability` 또는 `docker compose up -d prometheus grafana jaeger`.

| 도구 | 주소 | 용도 |
|---|---|---|
| Grafana | http://localhost:3000 | 불변조건·적체 대시보드 (익명 조회 허용) |
| Prometheus | http://localhost:9090 | 지표와 경보 규칙 (`deploy/observability/rules/`) |
| Jaeger | http://localhost:16686 | 분산 트레이스 |

핵심 지표는 `paritypay_invariant_*`입니다. **평소에 전부 0이어야 하고, 0이 아니면 시스템이 스스로 규칙을 어긴 것이므로
한 건이라도 즉시 경보합니다.**

## 8. 부하·장애 실험

```bash
k6 run load-tests/payment-baseline.js                      # P-001 서로 다른 지갑
k6 run -e SAME_WALLET=true load-tests/payment-baseline.js  # P-002 동일 지갑 경합
```

41종 전부의 실행 명령·사전 조건·결과 위치는 [DOC-19 실험 실행 안내서](19-experiment-runbook.md)에, 출력을 읽는 법은
[DOC-20 결과 해석 안내서](20-experiment-result-interpretation.md)에 있습니다. 결과 기록 규칙은
[load-tests/README.md](../load-tests/README.md)입니다.

## 9. 코드 스타일과 API 명세

포맷은 Spotless + palantir-java-format이 정합니다. 손으로 맞추지 않고 도구를 돌립니다. 린터는 Error Prone이며 컴파일 중에
돌고, 승격한 검사의 위반은 경고가 아니라 빌드 실패입니다.

```bash
./gradlew spotlessApply   # 포맷 정리
./gradlew build           # 포맷 검사 + 린트 + 테스트
```

API 명세는 손으로 쓰지 않고 구현에서 생성합니다. [docs/api/openapi.json](api/openapi.json)이 스냅샷이며, API를 바꾸면
`OpenApiSnapshotTest`가 차이를 알려 줍니다. 프론트엔드 타입은 그 스냅샷에서 생성합니다(`pnpm generate`) — 둘이 어긋나면
CI가 막습니다.

```bash
UPDATE_OPENAPI_SNAPSHOT=1 ./gradlew :apps:pay-api:test --tests "*OpenApiSnapshotTest*"
pnpm generate
```

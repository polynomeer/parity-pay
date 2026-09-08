# 부하 테스트

k6로 실행합니다. 설치 없이 컨테이너로도 돌릴 수 있습니다.

```bash
# 발행 처리량만 재는 실험 (애플리케이션과 postgres 컨테이너가 떠 있어야 합니다)
EVENTS=20000 RUNS=3 load-tests/outbox-drain-benchmark.sh
```

```bash
# P-004 정산 배치와 API 부하 혼합. 스크립트가 앱을 띄우고, 기준선 → 소비 대기 → 혼합 순서로
# 돌립니다. RATE는 이 기기에서 기준선이 깨끗한 값으로 정합니다(측정 전 보정 필요).
./gradlew :apps:pay-api:bootJar
RATE=12 JAR=apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar RUNS=3 load-tests/run-p004.sh
```

```bash
# 크래시 실험. 스크립트가 앱을 직접 띄우고 죽이고 다시 띄웁니다.
# docker compose up -d 로 postgres·redpanda가 떠 있어야 하고, bootJar가 필요합니다.
./gradlew :apps:pay-api:bootJar
python3 load-tests/crash-recovery-experiment.py \
  --jar apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar
```

```bash
# 애플리케이션과 의존성을 먼저 띄웁니다.
docker compose up -d
./gradlew :apps:pay-api:bootRun

# 시나리오 실행 (호스트에 k6가 있는 경우)
k6 run load-tests/payment-baseline.js
k6 run -e SAME_WALLET=true load-tests/payment-baseline.js

# 컨테이너로 실행
docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -e BASE_URL=http://host.docker.internal:8080 \
  grafana/k6:latest run - < load-tests/payment-baseline.js
```

## 시나리오

| 파일 | 목적 | 대응 실험 |
|---|---|---|
| `payment-baseline.js` | 서로 다른 지갑 결제 처리량 기준선 | P-001 |
| `payment-baseline.js` (`SAME_WALLET=true`) | 동일 지갑 경합 | P-002 |
| `topup-outbox-backlog.js` | 충전 부하 후 Outbox 적체 해소 | P-003 |
| `outbox-drain-benchmark.sh` | 발행 경로만 떼어낸 적체 해소율 (k6 없이 SQL로 적체 생성) | P-005 |
| `crash-recovery-experiment.py` | 트래픽 중 `SIGKILL` 후 재시작·재전송으로 "정확히 1회" 확인 | F-001·F-002 |
| `settlement-batch-mixed.js` + `run-p004.sh` | 정산 배치·대사가 도는 동안 API 지연 변화 | P-004 |

## 결과를 기록할 때

- 측정 전 warm-up 구간을 분리하고, 최소 3회 실행해 중앙값과 편차를 남깁니다.
- **부하율을 먼저 보정합니다.** 기준선 구간에서 이미 오류가 나는 부하로 비교하면, 무엇을 바꿔도
  차이가 환경 잡음에 묻힙니다(P-004에서 RATE=40으로 시작했다가 겪었습니다).
- 실패한 요청을 빼고 지연을 계산하지 않습니다.
- 장비·컨테이너 제한·JVM 옵션·데이터 규모를 함께 적습니다.
- 결과는 [성능·장애 테스트 보고서](../reports/11-performance-failure-report-template.md)에 원본 파일
  경로와 함께 기록합니다. 근거: docs/10-test-strategy.md §7

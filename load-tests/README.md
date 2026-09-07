# 부하 테스트

k6로 실행합니다. 설치 없이 컨테이너로도 돌릴 수 있습니다.

```bash
# 발행 처리량만 재는 실험 (애플리케이션과 postgres 컨테이너가 떠 있어야 합니다)
EVENTS=20000 RUNS=3 load-tests/outbox-drain-benchmark.sh
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

## 결과를 기록할 때

- 측정 전 warm-up 구간을 분리하고, 최소 3회 실행해 중앙값과 편차를 남깁니다.
- 실패한 요청을 빼고 지연을 계산하지 않습니다.
- 장비·컨테이너 제한·JVM 옵션·데이터 규모를 함께 적습니다.
- 결과는 [성능·장애 테스트 보고서](../reports/11-performance-failure-report-template.md)에 원본 파일
  경로와 함께 기록합니다. 근거: docs/10-test-strategy.md §7

#!/usr/bin/env bash
# P-004 실행 도우미: DB를 비우고 앱을 띄운 뒤 혼합 시나리오를 돌리고, 도는 동안 커넥션 풀을 샘플링합니다.
#
# 사용: JAR=<bootJar> RUNS=3 load-tests/run-p004.sh
# 근거: reports/11 P-004
set -euo pipefail

JAR=${JAR:?bootJar 경로를 JAR로 지정하세요}
RUNS=${RUNS:-3}
PORT=${PORT:-8085}
DB_URL=${DB_URL:-jdbc:postgresql://localhost:5435/paritypay}
KAFKA=${KAFKA:-localhost:9092}
CONTAINER=${PG_CONTAINER:-paritypay-postgres}
OUT=${OUT:-/tmp/p004}
JAVA_BIN=${JAVA:-java}

mkdir -p "$OUT"
psql_run() { docker exec -i "$CONTAINER" psql -U paritypay -d paritypay -tA -q -c "$1"; }

for run in $(seq 1 "$RUNS"); do
  echo "########## run $run"
  # 앞 실행의 프로세스가 살아 있으면 두 앱이 같은 DB를 두드립니다. 커넥션 풀도 스케줄러도
  # 두 벌이 되어 측정이 무의미해집니다.
  pkill -f "$(basename "$JAR")" 2>/dev/null || true
  sleep 5
  # 이전 실행이 남긴 이벤트를 소비자가 먼저 읽으면 이번 실행의 정산 항목이 늦게 생깁니다.
  docker exec "${BROKER_CONTAINER:-paritypay-redpanda}" rpk topic delete "${TOPIC:-paritypay.events}" >/dev/null 2>&1 || true
  SPRING_PROFILES_ACTIVE=local PARITYPAY_PORT="$PORT" PARITYPAY_DB_URL="$DB_URL" \
    PARITYPAY_KAFKA_SERVERS="$KAFKA" PARITYPAY_TRACE_SAMPLING=0 \
    nohup "$JAVA_BIN" -jar "$JAR" > "$OUT/app-run$run.log" 2>&1 &
  APP_PID=$!
  for _ in $(seq 1 60); do
    grep -q "Started ParityPayApplication" "$OUT/app-run$run.log" && break
    sleep 2
  done

  # 실행마다 같은 조건에서 시작합니다. 데이터가 쌓인 채로 다음 실행을 하면 뒤 실행이 불리합니다.
  # 앱을 먼저 띄우는 이유는 마이그레이션 때문입니다. member는 비우지 않습니다. 비우면 부트스트랩
  # 운영자 계정까지 사라져 배치를 호출할 수 없습니다. 지난 실행이 만든 회원 행은 지갑이 없는
  # 껍데기로 남고 이 실험의 측정 대상이 아닙니다.
  psql_run "
    TRUNCATE refresh_token, login_attempt, audit_log, password_reset_token,
             reconciliation_mismatch, reconciliation_run,
             settlement_item, settlement, order_confirmation, merchant,
             ledger_entry, ledger_transaction, ledger_account,
             idempotency_record, payment_cancellation, payment, top_up,
             outbox_event, consumed_event, wallet_transaction,
             mock_bank_withdrawal, mock_bank_account,
             wallet_balance, bank_account, wallet CASCADE" >/dev/null

  # 커넥션 풀은 실행 중에만 볼 수 있습니다. 2초 간격으로 남깁니다.
  (
    while kill -0 "$APP_PID" 2>/dev/null; do
      ts=$(date +%s)
      curl -s "http://localhost:$PORT/actuator/prometheus" \
        | grep -E '^hikaricp_connections(_active|_pending|_idle)?\{' \
        | sed "s/^/$ts /" >> "$OUT/pool-run$run.txt" || true
      sleep 2
    done
  ) &
  POOL_PID=$!

  # 1) API만. 이 구간이 기준선입니다.
  BASE_URL="http://localhost:$PORT" MODE=baseline k6 run \
    --summary-export "$OUT/baseline-run$run.json" \
    load-tests/settlement-batch-mixed.js > "$OUT/baseline-run$run.txt" 2>&1 || true

  # 2) 이벤트 소비가 따라올 때까지 기다립니다. 정산 항목은 OrderConfirmed 소비로 만들어지므로,
  #    기다리지 않으면 배치가 할 일의 양이 실행마다 달라져 비교가 무의미해집니다.
  prev=-1
  for _ in $(seq 1 100); do
    pending=$(psql_run "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'")
    current=$(psql_run "SELECT count(*) FROM settlement_item WHERE status = 'ELIGIBLE'")
    if [ "$pending" = "0" ] && [ "$current" = "$prev" ] && [ "$current" != "0" ]; then
      break
    fi
    prev=$current
    sleep 3
  done
  echo "  배치 시작 시점의 정산 대기 항목: $prev"
  echo "$prev" > "$OUT/backlog-run$run.txt"

  # 3) 같은 부하에 배치를 얹습니다.
  BASE_URL="http://localhost:$PORT" MODE=mixed k6 run \
    --summary-export "$OUT/mixed-run$run.json" \
    load-tests/settlement-batch-mixed.js > "$OUT/mixed-run$run.txt" 2>&1 || true

  kill "$POOL_PID" 2>/dev/null || true
  kill "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  echo "  결과: $OUT/baseline-run$run.txt, $OUT/mixed-run$run.txt"
  # 다음 실행이 앞 실행의 여파 위에서 시작하지 않게 합니다.
  sleep 20
done

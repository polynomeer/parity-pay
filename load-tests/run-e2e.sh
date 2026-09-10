#!/usr/bin/env bash
# E2E 스택을 띄우고 Playwright를 돌립니다.
#
# 목을 쓰지 않는 시험이라 실제 스택이 전부 필요합니다 — postgres·redpanda·기관 둘·pay-api·앱 둘.
# 순서가 있어서 셸이 다룹니다: 기관 DB가 없으면 기관이 뜨지 않고, 기관이 없으면 충전이 안 됩니다.
#
# 사용: load-tests/run-e2e.sh
# 근거: reports/11 결함 J, docs/14-frontend-design.md §9
set -euo pipefail

cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
OUT=${OUT:-/tmp/paritypay-e2e}
mkdir -p "$OUT"

cleanup() {
  echo "== 정리"
  kill "${API_PID:-}" "${CUSTOMER_PID:-}" "${OPS_PID:-}" 2>/dev/null || true
  wait "${API_PID:-}" 2>/dev/null || true
}
trap cleanup EXIT

echo "== 의존성"
docker compose up -d postgres redpanda mock-bank mock-pg
# 기관 데이터베이스는 볼륨이 비어 있을 때만 만들어집니다. 이미 있는 볼륨이면 직접 만듭니다.
docker exec paritypay-postgres psql -U paritypay -d postgres -c \
  "CREATE DATABASE paritypay_bank OWNER paritypay" >/dev/null 2>&1 || true
docker exec paritypay-postgres psql -U paritypay -d postgres -c \
  "CREATE DATABASE paritypay_pg OWNER paritypay" >/dev/null 2>&1 || true
docker compose up -d mock-bank mock-pg

echo "== pay-api"
./gradlew :apps:pay-api:bootJar -q
SPRING_PROFILES_ACTIVE=local PARITYPAY_PORT=8080 \
  PARITYPAY_DB_URL=jdbc:postgresql://localhost:5432/paritypay \
  "$JAVA_HOME/bin/java" -jar apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar > "$OUT/api.log" 2>&1 &
API_PID=$!
for _ in $(seq 1 60); do
  grep -q "Started ParityPayApplication" "$OUT/api.log" && break
  sleep 3
done
grep -q "Started ParityPayApplication" "$OUT/api.log" || { echo "pay-api가 뜨지 않았습니다: $OUT/api.log"; exit 1; }

echo "== 프론트엔드"
pnpm --filter @paritypay/web-customer dev > "$OUT/customer.log" 2>&1 &
CUSTOMER_PID=$!
pnpm --filter @paritypay/web-ops dev > "$OUT/ops.log" 2>&1 &
OPS_PID=$!
for _ in $(seq 1 40); do
  grep -q "Local:" "$OUT/customer.log" 2>/dev/null && grep -q "Local:" "$OUT/ops.log" 2>/dev/null && break
  sleep 2
done

echo "== Playwright"
pnpm --filter @paritypay/e2e exec playwright test "$@"

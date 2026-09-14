#!/usr/bin/env bash
# 로컬 개발 스택 전부를 한 번에 띄웁니다.
#
#   의존 컨테이너(postgres·redpanda·mailpit) → 기관 대역(mock-bank·mock-pg) → pay-api → 고객 앱·운영 콘솔
#
# 이 순서는 필수입니다. 기관 DB가 없으면 기관이 뜨지 않고, 기관이 없으면 충전·결제가 안 되며,
# pay-api가 없으면 Vite 프록시가 502를 돌려줍니다. load-tests/run-e2e.sh와 같은 순서이되,
# 그 스크립트는 Playwright를 돌리고 바로 내리지만 이것은 사람이 쓸 수 있게 남겨 둡니다.
#
# 포트 우회: 이 호스트에는 다른 프로젝트의 컨테이너가 많아 5432·6379·4318 같은 기본 포트가 자주
# 잡혀 있습니다. 기본 포트가 쓰이고 있으면 비어 있는 다음 포트로 옮기고, 그 포트를 아는 쪽
# (compose의 호스트 매핑, Redpanda 광고 주소, pay-api의 의존성 URL, Vite 프록시, 재설정 메일 링크)
# 전부에 같은 값을 넣습니다. 이미 떠 있는 우리 컨테이너가 잡고 있는 포트는 그대로 씁니다 —
# 다시 만들 이유가 없습니다.
#
# 사용:
#   scripts/dev.sh [up]              전부 띄우고 주소를 출력합니다
#   scripts/dev.sh down              띄운 것을 전부 내립니다 (컨테이너는 내리되 볼륨은 남깁니다)
#   scripts/dev.sh down -v           볼륨까지 지웁니다
#   scripts/dev.sh status            무엇이 어느 포트에 떠 있는지 보여줍니다
#   scripts/dev.sh logs [api|customer|ops]   호스트 프로세스의 로그를 따라갑니다
#
# 선택:
#   --observability                  prometheus·grafana·jaeger까지 띄웁니다
#   --no-build                       jar를 다시 빌드하지 않습니다 (코드가 안 바뀌었을 때)
#   PARITYPAY_*_PORT=<n>             특정 서비스의 시작 포트를 바꿉니다 (그것도 잡혀 있으면 우회합니다)
#
# 근거: README §로컬 실행, docs/14-frontend-design.md §2 (두 앱은 다른 오리진), ADR-011
set -euo pipefail

cd "$(dirname "$0")/.."
OUT=${OUT:-/tmp/paritypay-dev}
mkdir -p "$OUT"
PIDS="$OUT/pids"

# ---------- 공통 ----------

# macOS에서는 java_home으로 찾고, CI(Linux)에서는 setup-java가 이미 넣어 줍니다.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
export JAVA_HOME

# 이 실행에서 이미 배정한 포트입니다. 아직 아무것도 듣지 않으므로 lsof만으로는 두 앱이 같은
# 포트를 받을 수 있습니다.
# pick_port는 $(...) 안에서 돌아 변수로는 전해지지 않으므로 파일에 적습니다.
RESERVED="$OUT/reserved-ports"
port_free() {
  grep -qx "$1" "$RESERVED" 2>/dev/null && return 1
  ! lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

# 컨테이너 $1이 실행 중이면 컨테이너 포트 $2에 매핑된 호스트 포트를 출력합니다.
own_running_port() {
  docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -q true || return 0
  docker port "$1" "$2/tcp" 2>/dev/null | head -1 | sed 's/.*://'
}

# $1 이름, $2 시작 포트, $3 컨테이너 이름(호스트 프로세스면 빈 값), $4 컨테이너 포트.
# 결정한 포트를 출력하고, 우회했으면 stderr에 알립니다.
pick_port() {
  local name=$1 want=$2 container=${3:-} cport=${4:-}
  local port
  if [ -n "$container" ]; then
    port=$(own_running_port "$container" "$cport")
    if [ -n "$port" ]; then
      echo "$port" >> "$RESERVED"
      echo "$port"
      return
    fi
  fi
  port=$want
  while ! port_free "$port"; do
    port=$((port + 1))
  done
  if [ "$port" != "$want" ]; then
    echo "   $name: $want 사용 중 → $port 로 우회" >&2
  fi
  echo "$port" >> "$RESERVED"
  echo "$port"
}

# 이전 실행이 남긴 호스트 프로세스를 내립니다. 남아 있으면 두 pay-api가 같은 DB를 두드리고
# 스케줄러·Outbox 릴레이가 두 벌이 됩니다.
kill_host_processes() {
  [ -f "$PIDS" ] || return 0
  while read -r name pid; do
    if kill -0 "$pid" 2>/dev/null; then
      echo "   $name (pid $pid) 종료"
      kill "$pid" 2>/dev/null || true
    fi
  done < "$PIDS"
  # Vite는 pnpm → node 순으로 자식이 있어 부모만 죽이면 리스너가 남습니다.
  while read -r name pid; do
    pkill -P "$pid" 2>/dev/null || true
  done < "$PIDS"
  sleep 1
  rm -f "$PIDS"
}

wait_for_log() { # $1 로그, $2 패턴, $3 시도 횟수, $4 간격(초)
  local i
  for i in $(seq 1 "$3"); do
    grep -q "$2" "$1" 2>/dev/null && return 0
    sleep "$4"
  done
  return 1
}

# ---------- 명령 ----------

cmd_down() {
  echo "== 호스트 프로세스"
  kill_host_processes
  echo "== 컨테이너"
  docker compose down "$@"
}

cmd_status() {
  echo "== 컨테이너"
  docker compose ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}' 2>/dev/null || true
  echo "== 호스트 프로세스"
  if [ -f "$PIDS" ]; then
    while read -r name pid; do
      if kill -0 "$pid" 2>/dev/null; then
        echo "   $name  pid $pid  실행 중"
      else
        echo "   $name  pid $pid  죽음"
      fi
    done < "$PIDS"
  else
    echo "   없음"
  fi
  if [ -f "$OUT/urls" ]; then
    echo "== 주소"
    cat "$OUT/urls"
  fi
}

cmd_logs() {
  local which=${1:-api}
  case "$which" in
    api|customer|ops) tail -f "$OUT/$which.log" ;;
    *) docker compose logs -f "$which" ;;
  esac
}

cmd_up() {
  local observability=0 build=1
  for arg in "$@"; do
    case "$arg" in
      --observability) observability=1 ;;
      --no-build) build=0 ;;
      *) echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
    esac
  done

  command -v docker >/dev/null || { echo "docker가 필요합니다" >&2; exit 1; }
  command -v pnpm >/dev/null || { echo "pnpm이 필요합니다" >&2; exit 1; }
  docker info >/dev/null 2>&1 || { echo "Docker 데몬이 떠 있지 않습니다" >&2; exit 1; }

  echo "== 이전 실행 정리"
  kill_host_processes

  echo "== 포트 결정"
  : > "$RESERVED"
  DB_PORT=$(pick_port postgres "${PARITYPAY_DB_PORT:-5432}" paritypay-postgres 5432)
  KAFKA_PORT=$(pick_port redpanda "${PARITYPAY_KAFKA_PORT:-9092}" paritypay-redpanda 9092)
  MAIL_PORT=$(pick_port mailpit-smtp "${PARITYPAY_MAIL_PORT:-1025}" paritypay-mailpit 1025)
  MAIL_UI_PORT=$(pick_port mailpit-ui "${PARITYPAY_MAIL_UI_PORT:-8025}" paritypay-mailpit 8025)
  BANK_PORT=$(pick_port mock-bank "${PARITYPAY_MOCK_BANK_PORT:-8090}" paritypay-mock-bank 8090)
  PG_PORT=$(pick_port mock-pg "${PARITYPAY_MOCK_PG_PORT:-8091}" paritypay-mock-pg 8091)
  API_PORT=$(pick_port pay-api "${PARITYPAY_PORT:-8080}")
  CUSTOMER_PORT=$(pick_port web-customer "${PARITYPAY_CUSTOMER_PORT:-5173}")
  # 두 앱은 반드시 다른 포트여야 합니다(예약 목록이 보장합니다). 같은 오리진이면 운영 콘솔
  # 쿠키가 고객 앱으로 넘어갑니다.
  OPS_PORT=$(pick_port web-ops "${PARITYPAY_OPS_PORT:-5174}")

  # 서비스에 따라 이름이 다른 환경변수를 compose가 읽습니다. 이미 실행 중인 컨테이너의 포트를
  # 그대로 넣으므로 compose가 "설정이 바뀌었다"고 다시 만들지 않습니다.
  export PARITYPAY_DB_PORT=$DB_PORT PARITYPAY_KAFKA_PORT=$KAFKA_PORT
  export PARITYPAY_MAIL_PORT=$MAIL_PORT PARITYPAY_MAIL_UI_PORT=$MAIL_UI_PORT
  export PARITYPAY_MOCK_BANK_PORT=$BANK_PORT PARITYPAY_MOCK_PG_PORT=$PG_PORT

  local compose_extra=()
  if [ "$observability" = 1 ]; then
    PROM_PORT=$(pick_port prometheus "${PARITYPAY_PROMETHEUS_PORT:-9090}" paritypay-prometheus 9090)
    GRAFANA_PORT=$(pick_port grafana "${PARITYPAY_GRAFANA_PORT:-3000}" paritypay-grafana 3000)
    JAEGER_UI_PORT=$(pick_port jaeger-ui "${PARITYPAY_JAEGER_UI_PORT:-16686}" paritypay-jaeger 16686)
    OTLP_GRPC_PORT=$(pick_port otlp-grpc "${PARITYPAY_OTLP_GRPC_PORT:-4317}" paritypay-jaeger 4317)
    OTLP_HTTP_PORT=$(pick_port otlp-http "${PARITYPAY_OTLP_HTTP_PORT:-4318}" paritypay-jaeger 4318)
    export PARITYPAY_PROMETHEUS_PORT=$PROM_PORT PARITYPAY_GRAFANA_PORT=$GRAFANA_PORT
    export PARITYPAY_JAEGER_UI_PORT=$JAEGER_UI_PORT
    export PARITYPAY_OTLP_GRPC_PORT=$OTLP_GRPC_PORT PARITYPAY_OTLP_HTTP_PORT=$OTLP_HTTP_PORT
    compose_extra=(prometheus grafana jaeger)
  fi

  if [ "$build" = 1 ]; then
    echo "== jar 빌드 (mock-bank · mock-pg · pay-api)"
    # 기관 컨테이너는 build/libs를 마운트해 실행합니다. 빌드하지 않으면 뜨자마자 죽습니다.
    ./gradlew :apps:mock-bank:bootJar :apps:mock-pg:bootJar :apps:pay-api:bootJar -q
  fi

  echo "== 의존 컨테이너"
  docker compose up -d postgres redpanda mailpit ${compose_extra[@]+"${compose_extra[@]}"}
  # 기관 데이터베이스는 볼륨이 비어 있을 때만 init 스크립트가 만듭니다. 이미 쓰던 볼륨이면 직접 만듭니다.
  for _ in $(seq 1 30); do
    docker exec paritypay-postgres pg_isready -U paritypay -d paritypay >/dev/null 2>&1 && break
    sleep 1
  done
  docker exec paritypay-postgres psql -U paritypay -d postgres -c \
    "CREATE DATABASE paritypay_bank OWNER paritypay" >/dev/null 2>&1 || true
  docker exec paritypay-postgres psql -U paritypay -d postgres -c \
    "CREATE DATABASE paritypay_pg OWNER paritypay" >/dev/null 2>&1 || true

  echo "== 기관 대역"
  docker compose up -d mock-bank mock-pg
  # 기관 대역에는 actuator가 없습니다. 포트가 열리면 Spring이 다 뜬 것입니다.
  for port in "$BANK_PORT" "$PG_PORT"; do
    for _ in $(seq 1 40); do
      nc -z localhost "$port" >/dev/null 2>&1 && break
      sleep 2
    done
    nc -z localhost "$port" >/dev/null 2>&1 || { echo "기관 대역(:$port)이 뜨지 않았습니다: docker compose logs mock-bank mock-pg" >&2; exit 1; }
  done

  echo "== pay-api (:$API_PORT)"
  : > "$OUT/api.log"
  local api_env=(
    SPRING_PROFILES_ACTIVE=local
    PARITYPAY_PORT="$API_PORT"
    PARITYPAY_DB_URL="jdbc:postgresql://localhost:$DB_PORT/paritypay"
    PARITYPAY_KAFKA_SERVERS="localhost:$KAFKA_PORT"
    PARITYPAY_MOCK_BANK_URL="http://localhost:$BANK_PORT"
    PARITYPAY_MOCK_PG_URL="http://localhost:$PG_PORT"
    PARITYPAY_MAIL_PORT="$MAIL_PORT"
    # 서버가 앱의 오리진을 아는 유일한 자리입니다 (ADR-011). 앱 포트가 바뀌면 여기도 바뀌어야
    # 메일의 링크가 열립니다.
    PARITYPAY_PASSWORD_RESET_LINK="http://app.localhost:$CUSTOMER_PORT/reset?token={token}"
  )
  if [ "$observability" = 1 ]; then
    api_env+=(PARITYPAY_OTLP_ENDPOINT="http://localhost:$OTLP_HTTP_PORT/v1/traces")
  else
    # 트레이스 백엔드가 없으면 내보내기 실패 로그만 쌓입니다.
    api_env+=(PARITYPAY_TRACE_SAMPLING=0)
  fi
  env "${api_env[@]}" "$JAVA_HOME/bin/java" -jar apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar \
    > "$OUT/api.log" 2>&1 &
  echo "api $!" >> "$PIDS"
  wait_for_log "$OUT/api.log" "Started ParityPayApplication" 60 3 || {
    echo "pay-api가 뜨지 않았습니다. 로그: $OUT/api.log" >&2
    tail -30 "$OUT/api.log" >&2
    exit 1
  }

  echo "== 프론트엔드 (:$CUSTOMER_PORT · :$OPS_PORT)"
  [ -d node_modules ] || pnpm install
  # --strictPort: Vite는 포트가 잡혀 있으면 스스로 다음 포트로 가는데, 그러면 위에서 결정한 값과
  # 재설정 메일 링크가 어긋납니다. 우회는 여기서 이미 했으므로 Vite가 다시 하지 않게 합니다.
  PARITYPAY_API_URL="http://localhost:$API_PORT" \
    pnpm --filter @paritypay/web-customer dev --port "$CUSTOMER_PORT" --strictPort \
    > "$OUT/customer.log" 2>&1 &
  echo "customer $!" >> "$PIDS"
  PARITYPAY_API_URL="http://localhost:$API_PORT" \
    pnpm --filter @paritypay/web-ops dev --port "$OPS_PORT" --strictPort \
    > "$OUT/ops.log" 2>&1 &
  echo "ops $!" >> "$PIDS"
  wait_for_log "$OUT/customer.log" "Local:" 40 2 || { echo "고객 앱이 뜨지 않았습니다: $OUT/customer.log" >&2; exit 1; }
  wait_for_log "$OUT/ops.log" "Local:" 40 2 || { echo "운영 콘솔이 뜨지 않았습니다: $OUT/ops.log" >&2; exit 1; }

  {
    echo "   고객 앱     http://app.localhost:$CUSTOMER_PORT"
    echo "   운영 콘솔   http://ops.localhost:$OPS_PORT"
    echo "   pay-api     http://localhost:$API_PORT/swagger-ui.html"
    echo "   재설정 메일 http://localhost:$MAIL_UI_PORT"
    echo "   mock-bank   http://localhost:$BANK_PORT   mock-pg http://localhost:$PG_PORT"
    echo "   postgres    localhost:$DB_PORT   redpanda localhost:$KAFKA_PORT"
    if [ "$observability" = 1 ]; then
      echo "   grafana     http://localhost:$GRAFANA_PORT   prometheus http://localhost:$PROM_PORT   jaeger http://localhost:$JAEGER_UI_PORT"
      if [ "$API_PORT" != 8080 ]; then
        echo "   (주의) prometheus는 host.docker.internal:8080을 긁습니다. deploy/observability/prometheus.yml을 $API_PORT 로 바꾸세요."
      fi
    fi
  } > "$OUT/urls"
  echo "== 준비됨"
  cat "$OUT/urls"
  echo
  echo "   운영자 ops-operator@paritypay.local / local-ops-password"
  echo "   로그: $OUT/{api,customer,ops}.log   내리기: scripts/dev.sh down"
}

# 인자 없이 부르면 up입니다. 인자가 없을 때 `shift`는 실패하고 set -e가 아무 말 없이 스크립트를
# 끝내므로, 인자가 있을 때만 떼어 냅니다.
COMMAND=${1:-up}
[ $# -gt 0 ] && shift
case "$COMMAND" in
  up) cmd_up "$@" ;;
  down) cmd_down "$@" ;;
  status) cmd_status ;;
  logs) cmd_logs "$@" ;;
  *) sed -n '2,28p' "$0"; exit 2 ;;
esac

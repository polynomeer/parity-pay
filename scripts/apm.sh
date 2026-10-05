#!/usr/bin/env bash
# APM 백엔드를 바꿔 끼웁니다.
#
#   scripts/apm.sh up <name>     백엔드를 띄우고, pay-api가 쓸 환경변수를 .apm/env 에 적습니다
#   scripts/apm.sh down          띄운 APM 백엔드를 내립니다 (의존 컨테이너는 건드리지 않습니다)
#   scripts/apm.sh status        지금 무엇이 켜져 있는지
#   scripts/apm.sh agents        에이전트 jar를 내려받습니다 (저장소에 담지 않습니다)
#
# 이름: jaeger | tempo | signoz | skywalking | pinpoint | datadog
#
# 애플리케이션 코드는 어떤 APM도 알지 못합니다. 두 가지 경로만 있습니다.
#
#   OTLP 계열 (jaeger·tempo·signoz·datadog)
#       앱 --(OTLP 4317/4318)--> otel-collector --(exporter)--> 백엔드
#       바뀌는 것은 컬렉터 설정 파일 하나입니다 (deploy/observability/otel/collector-<name>.yaml).
#
#   자체 에이전트 계열 (skywalking·pinpoint, 그리고 상용 일부)
#       앱 JVM 에 -javaagent 를 바꿔 끼웁니다. OTel 에이전트는 끕니다 — 둘을 같이 달면
#       같은 호출이 두 번 계측되고 오버헤드 비교가 무의미해집니다.
#
# scripts/dev.sh 는 .apm/env 가 있으면 읽어서 pay-api 에 넘깁니다. 그래서 전환은
# "apm.sh up <name> → dev.sh" 두 줄입니다.
#
# 근거: ADR-017, reports/14(측정)
set -euo pipefail

cd "$(dirname "$0")/.."
APM_DIR=.apm
AGENT_DIR="$APM_DIR/agents"
ENV_FILE="$APM_DIR/env"
ACTIVE_FILE="$APM_DIR/active"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.apm.yml)

OTEL_AGENT_VERSION=${OTEL_AGENT_VERSION:-2.12.0}
SKYWALKING_AGENT_VERSION=${SKYWALKING_AGENT_VERSION:-9.4.0}

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

# 이 호스트에는 다른 프로젝트의 관측 도구가 이미 떠 있습니다 — 실제로 16686·4318 이 다른
# Jaeger 에게 잡혀 있었습니다. 그래서 dev.sh 와 같은 규칙으로 비어 있는 포트를 찾습니다(ADR-013).
port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

pick_port() {
  local port=$1
  while port_busy "$port"; do port=$((port + 1)); done
  if [ "$port" != "$1" ]; then
    echo "   ($1 이 잡혀 있어 $port 로 옮깁니다)" >&2
  fi
  printf '%s' "$port"
}

# 백엔드별 호스트 포트를 정하고 내보냅니다. compose 와 .apm/env 가 같은 값을 봅니다.
assign_ports() {
  export PARITYPAY_OTLP_GRPC_PORT=${PARITYPAY_OTLP_GRPC_PORT:-$(pick_port 4317)}
  export PARITYPAY_OTLP_HTTP_PORT=${PARITYPAY_OTLP_HTTP_PORT:-$(pick_port 4318)}
  export PARITYPAY_JAEGER_UI_PORT=${PARITYPAY_JAEGER_UI_PORT:-$(pick_port 16686)}
  export PARITYPAY_TEMPO_PORT=${PARITYPAY_TEMPO_PORT:-$(pick_port 3200)}
  export PARITYPAY_SIGNOZ_PORT=${PARITYPAY_SIGNOZ_PORT:-$(pick_port 8080)}
  export PARITYPAY_SKYWALKING_UI_PORT=${PARITYPAY_SKYWALKING_UI_PORT:-$(pick_port 18080)}
  export PARITYPAY_SKYWALKING_GRPC_PORT=${PARITYPAY_SKYWALKING_GRPC_PORT:-$(pick_port 11800)}
  export PARITYPAY_PINPOINT_WEB_PORT=${PARITYPAY_PINPOINT_WEB_PORT:-$(pick_port 8081)}
}

# 프로필마다 띄울 서비스입니다. 이름을 주지 않으면 compose 가 기본 파일 전체를 띄웁니다.
backend_services() {
  case "$1" in
    jaeger) echo "otel-collector jaeger" ;;
    tempo) echo "otel-collector tempo" ;;
    signoz) echo "otel-collector signoz-clickhouse signoz" ;;
    datadog) echo "otel-collector datadog-agent" ;;
    skywalking) echo "skywalking-oap skywalking-ui" ;;
    pinpoint) echo "pinpoint-hbase pinpoint-collector pinpoint-web" ;;
    *) echo "" ;;
  esac
}

otlp_based() {
  case "$1" in
    jaeger | tempo | signoz | datadog) return 0 ;;
    *) return 1 ;;
  esac
}

download_otel_agent() {
  local jar="$AGENT_DIR/opentelemetry-javaagent-$OTEL_AGENT_VERSION.jar"
  if [ ! -f "$jar" ]; then
    mkdir -p "$AGENT_DIR"
    echo "== OpenTelemetry Java 에이전트 $OTEL_AGENT_VERSION 내려받기"
    curl -fsSL -o "$jar" \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$OTEL_AGENT_VERSION/opentelemetry-javaagent.jar"
  fi
  printf '%s' "$jar"
}

download_skywalking_agent() {
  local dir="$AGENT_DIR/skywalking-agent-$SKYWALKING_AGENT_VERSION"
  if [ ! -d "$dir" ]; then
    mkdir -p "$AGENT_DIR"
    echo "== SkyWalking Java 에이전트 $SKYWALKING_AGENT_VERSION 내려받기"
    local tgz="$AGENT_DIR/skywalking-agent.tgz"
    curl -fsSL -o "$tgz" \
      "https://archive.apache.org/dist/skywalking/java-agent/$SKYWALKING_AGENT_VERSION/apache-skywalking-java-agent-$SKYWALKING_AGENT_VERSION.tgz"
    mkdir -p "$dir"
    tar -xzf "$tgz" -C "$dir" --strip-components=1
    rm -f "$tgz"
  fi
  printf '%s' "$dir/skywalking-agent.jar"
}

write_env() {
  # pay-api 가 쓸 환경변수입니다. dev.sh 가 이 파일을 읽습니다.
  mkdir -p "$APM_DIR"
  : > "$ENV_FILE"
  local name=$1
  if otlp_based "$name"; then
    local jar
    jar=$(download_otel_agent)
    {
      echo "# scripts/apm.sh up $name 이 만든 파일입니다. 직접 고치지 마십시오."
      echo "PARITYPAY_APM=$name"
      # Spring 쪽 OTLP 내보내기는 끕니다. 에이전트가 같은 일을 하므로 켜 두면 트레이스가 두 벌입니다.
      echo "MANAGEMENT_TRACING_ENABLED=false"
      echo "JAVA_TOOL_OPTIONS=-javaagent:$PWD/$jar"
      echo "OTEL_SERVICE_NAME=pay-api"
      echo "OTEL_TRACES_EXPORTER=otlp"
      echo "OTEL_METRICS_EXPORTER=none"
      echo "OTEL_LOGS_EXPORTER=none"
      echo "OTEL_EXPORTER_OTLP_PROTOCOL=grpc"
      echo "OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:${PARITYPAY_OTLP_GRPC_PORT:-4317}"
      # 실험은 전량 샘플링입니다. 도구별 기본 샘플링 비교는 보고서에 따로 적습니다.
      echo "OTEL_TRACES_SAMPLER=always_on"
    } >> "$ENV_FILE"
  elif [ "$name" = "skywalking" ]; then
    local jar
    jar=$(download_skywalking_agent)
    {
      echo "# scripts/apm.sh up $name 이 만든 파일입니다."
      echo "PARITYPAY_APM=$name"
      echo "MANAGEMENT_TRACING_ENABLED=false"
      echo "JAVA_TOOL_OPTIONS=-javaagent:$PWD/$jar"
      echo "SW_AGENT_NAME=pay-api"
      echo "SW_AGENT_COLLECTOR_BACKEND_SERVICES=localhost:${PARITYPAY_SKYWALKING_GRPC_PORT:-11800}"
    } >> "$ENV_FILE"
  elif [ "$name" = "pinpoint" ]; then
    {
      echo "# scripts/apm.sh up $name 이 만든 파일입니다."
      echo "PARITYPAY_APM=$name"
      echo "MANAGEMENT_TRACING_ENABLED=false"
      # Pinpoint 에이전트는 버전이 Collector 와 맞아야 합니다. apm.sh agents pinpoint 가 적어 줍니다.
      echo "JAVA_TOOL_OPTIONS=${PINPOINT_JAVA_TOOL_OPTIONS:-}"
    } >> "$ENV_FILE"
  elif [ "$name" = "none" ]; then
    {
      echo "# 에이전트 없음 — 오버헤드 비교의 대조군입니다."
      echo "PARITYPAY_APM=none"
      echo "MANAGEMENT_TRACING_ENABLED=false"
    } >> "$ENV_FILE"
  else
    echo "모르는 이름: $name" >&2
    exit 2
  fi
  printf '%s' "$name" > "$ACTIVE_FILE"
}

up() {
  local name=${1:-}
  [ -n "$name" ] || { usage; exit 2; }
  if [ "$name" = "none" ]; then
    write_env none
    echo "== 에이전트 없음(대조군)으로 설정했습니다. scripts/dev.sh 로 앱을 다시 띄우십시오."
    return
  fi
  if [ "$name" = "datadog" ] && [ -z "${DD_API_KEY:-}" ]; then
    echo "DD_API_KEY 가 없습니다. 체험판 계정의 키를 직접 넣으십시오:" >&2
    echo "  DD_API_KEY=<키> scripts/apm.sh up datadog" >&2
    echo "이 스크립트는 키를 저장하지 않습니다." >&2
    exit 2
  fi
  # 앞선 백엔드를 **먼저** 내리고 그 뒤에 포트를 고릅니다. 순서를 바꾸면 아직 살아 있는 컬렉터가
  # 4317 을 쥐고 있어 다음 실행이 4319·4320 으로 밀려나고 앱 설정까지 따라 바뀝니다
  # (실제로 그렇게 한 번 깨졌습니다).
  local previous
  previous=$(cat "$ACTIVE_FILE" 2>/dev/null || echo "")
  if [ -n "$previous" ] && [ "$previous" != "$name" ] && [ "$previous" != "none" ]; then
    echo "== 앞선 백엔드 정리: $previous"
    # shellcheck disable=SC2086
    "${COMPOSE[@]}" rm -sf $(backend_services "$previous") >/dev/null 2>&1 || true
  fi
  assign_ports
  write_env "$name"
  echo "== APM 백엔드 기동: $name"
  # 서비스를 이름으로 지정합니다. 프로필만 주면 compose 가 기본 파일의 모든 서비스(postgres·기관 등)를
  # 함께 띄웁니다 — 그 스택은 dev.sh 의 것이고, 여기서 건드리면 남의 컨테이너를 내리게 됩니다.
  local services
  services=$(backend_services "$name")
  # shellcheck disable=SC2086
  PARITYPAY_APM="$name" "${COMPOSE[@]}" --profile "apm-$name" up -d $services
  status
  case "$name" in
    jaeger) echo "   UI http://localhost:$PARITYPAY_JAEGER_UI_PORT" ;;
    tempo) echo "   Tempo API http://localhost:$PARITYPAY_TEMPO_PORT (화면은 Grafana 에서 봅니다)" ;;
    signoz) echo "   UI http://localhost:$PARITYPAY_SIGNOZ_PORT" ;;
    skywalking) echo "   UI http://localhost:$PARITYPAY_SKYWALKING_UI_PORT" ;;
    pinpoint) echo "   UI http://localhost:$PARITYPAY_PINPOINT_WEB_PORT" ;;
  esac
  echo
  echo "다음: scripts/dev.sh (또는 이미 떠 있으면 scripts/dev.sh down && scripts/dev.sh)"
}

down() {
  local name
  name=$(cat "$ACTIVE_FILE" 2>/dev/null || echo "")
  # stop/rm 을 서비스 이름으로 합니다. `down` 은 파일 전체를 내리므로 dev.sh 의 컨테이너까지 갑니다.
  local targets="otel-collector tempo signoz signoz-clickhouse datadog-agent skywalking-oap skywalking-ui pinpoint-hbase pinpoint-collector pinpoint-web"
  if [ -n "$name" ] && [ "$name" != "none" ]; then
    targets=$(backend_services "$name")
  fi
  # shellcheck disable=SC2086
  "${COMPOSE[@]}" rm -sf $targets >/dev/null 2>&1 || true
  # Jaeger 는 기본 스택의 일부일 수 있으므로 APM 실험이 띄운 경우에만 내립니다.
  if [ "$name" = "jaeger" ]; then
    "${COMPOSE[@]}" rm -sf jaeger >/dev/null 2>&1 || true
  fi
  rm -f "$ENV_FILE" "$ACTIVE_FILE"
  echo "== APM 백엔드를 내렸습니다. 의존 컨테이너(postgres 등)는 그대로입니다."
}

status() {
  local name
  name=$(cat "$ACTIVE_FILE" 2>/dev/null || echo "(없음)")
  echo "활성 APM: $name"
  [ -f "$ENV_FILE" ] && { echo "앱에 넘기는 설정($ENV_FILE):"; sed 's/^/   /' "$ENV_FILE"; }
  docker ps --format '   {{.Names}}\t{{.Status}}' | grep -E 'otel-collector|tempo|signoz|clickhouse|skywalking|pinpoint|hbase|datadog|jaeger' || echo "   (APM 컨테이너 없음)"
}

case "${1:-}" in
  up) shift; up "$@" ;;
  down) down ;;
  status) status ;;
  agents) shift; case "${1:-otel}" in otel) download_otel_agent; echo ;; skywalking) download_skywalking_agent; echo ;; *) echo "otel | skywalking" >&2; exit 2 ;; esac ;;
  *) usage; exit 2 ;;
esac

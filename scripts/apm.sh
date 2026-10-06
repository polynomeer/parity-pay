#!/usr/bin/env bash
# APM 백엔드를 바꿔 끼웁니다.
#
#   scripts/apm.sh up <name>     백엔드를 띄우고, pay-api가 쓸 환경변수를 .apm/env 에 적습니다
#   scripts/apm.sh down          띄운 APM 백엔드를 내립니다 (의존 컨테이너는 건드리지 않습니다)
#   scripts/apm.sh status        지금 무엇이 켜져 있는지
#   scripts/apm.sh agents        에이전트 jar를 내려받습니다 (저장소에 담지 않습니다)
#
# 이름: jaeger | zipkin | tempo | signoz | skywalking | pinpoint | datadog
#
# 애플리케이션 코드는 어떤 APM도 알지 못합니다. 두 가지 경로만 있습니다.
#
#   OTLP 계열 (jaeger·zipkin·tempo·signoz·datadog)
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
SKYWALKING_AGENT_VERSION=${SKYWALKING_AGENT_VERSION:-9.7.0}
PINPOINT_VERSION=${PINPOINT_VERSION:-3.1.1}

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

# Pinpoint 의 MySQL 비밀번호입니다. compose 는 이 변수가 없으면 뜨지 않습니다 — 공식 예시의
# admin/admin 을 그대로 적어 두지 않기 위해서입니다(ADR-011: 비밀값에 기본값을 두지 않습니다).
# 한 번 만들어 파일에 두고 다시 씁니다. 지우면 다음 기동에서 새로 만들고, 그때는 MySQL 볼륨도
# 함께 지워야 합니다.
PINPOINT_PW_FILE="$APM_DIR/pinpoint-mysql-password"
ensure_pinpoint_password() {
  if [ ! -s "$PINPOINT_PW_FILE" ]; then
    mkdir -p "$APM_DIR"
    LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24 > "$PINPOINT_PW_FILE"
    chmod 600 "$PINPOINT_PW_FILE"
  fi
  export PARITYPAY_PINPOINT_MYSQL_PASSWORD
  PARITYPAY_PINPOINT_MYSQL_PASSWORD=$(cat "$PINPOINT_PW_FILE")
}
ensure_pinpoint_password

# 이 호스트에는 다른 프로젝트의 관측 도구가 이미 떠 있습니다 — 실제로 16686·4318 이 다른
# Jaeger 에게 잡혀 있었습니다. 그래서 dev.sh 와 같은 규칙으로 비어 있는 포트를 찾습니다(ADR-013).
port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

# 이 실행에서 이미 배정한 포트입니다. 아직 아무것도 그 포트를 듣고 있지 않으므로 lsof 만으로는
# 두 서비스가 같은 포트를 받습니다 — 실제로 4327 과 4328 이 둘 다 4329 를 받았습니다.
# dev.sh 가 같은 이유로 같은 장치를 가지고 있습니다(ADR-013).
# pick_port 는 $(...) 안에서 돌기 때문에 변수로는 배정 결과가 돌아오지 않습니다. 파일에 적습니다 —
# dev.sh 가 같은 이유로 같은 장치를 가지고 있습니다(ADR-013). 이것이 없으면 4327 과 4328 이 둘 다
# 4329 를 받고, 8080 과 8081 이 둘 다 8084 를 받습니다(실제로 그랬습니다).
RESERVED_FILE="$APM_DIR/reserved-ports"

pick_port() {
  local port=$1
  while port_busy "$port" || grep -qx "$port" "$RESERVED_FILE" 2>/dev/null; do port=$((port + 1)); done
  if [ "$port" != "$1" ]; then
    echo "   ($1 이 잡혀 있어 $port 로 옮깁니다)" >&2
  fi
  mkdir -p "$APM_DIR"
  echo "$port" >> "$RESERVED_FILE"
  printf '%s' "$port"
}

# 이미 떠 있는 우리 컨테이너가 쥐고 있는 포트는 그대로 씁니다. 다시 고르면 포트가 한 칸씩 밀리고
# 앱이 보내는 주소까지 따라 바뀝니다 (dev.sh 와 같은 규칙).
own_running_port() {
  docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null | grep -q true || return 0
  docker port "$1" "$2/tcp" 2>/dev/null | head -1 | sed 's/.*://'
}

# $1 서비스 이름(로그용), $2 시작 포트, $3 컨테이너 이름, $4 컨테이너 포트
port_for() {
  local running
  running=$(own_running_port "$3" "$4")
  if [ -n "$running" ]; then
    echo "$running" >> "$RESERVED_FILE"
    printf '%s' "$running"
    return
  fi
  pick_port "$2"
}

# 백엔드별 호스트 포트를 정하고 내보냅니다. compose 와 .apm/env 가 같은 값을 봅니다.
assign_ports() {
  mkdir -p "$APM_DIR"
  : > "$RESERVED_FILE"
  export PARITYPAY_OTLP_GRPC_PORT=${PARITYPAY_OTLP_GRPC_PORT:-$(port_for otlp-grpc 4317 paritypay-otel-collector 4317)}
  export PARITYPAY_OTLP_HTTP_PORT=${PARITYPAY_OTLP_HTTP_PORT:-$(port_for otlp-http 4318 paritypay-otel-collector 4318)}
  export PARITYPAY_JAEGER_UI_PORT=${PARITYPAY_JAEGER_UI_PORT:-$(port_for jaeger-ui 16686 paritypay-jaeger 16686)}
  export PARITYPAY_TEMPO_PORT=${PARITYPAY_TEMPO_PORT:-$(port_for tempo 3200 paritypay-tempo 3200)}
  export PARITYPAY_SIGNOZ_PORT=${PARITYPAY_SIGNOZ_PORT:-$(port_for signoz-ui 8080 signoz-signoz-0 8080)}
  # SigNoz 는 자체 수집기가 4317·4318 을 쓰려 합니다. 우리 컬렉터가 그 자리에 있으므로 옮깁니다.
  export PARITYPAY_SIGNOZ_OTLP_GRPC_PORT=${PARITYPAY_SIGNOZ_OTLP_GRPC_PORT:-$(port_for signoz-otlp-grpc 4327 signoz-ingester-1 4317)}
  export PARITYPAY_SIGNOZ_OTLP_HTTP_PORT=${PARITYPAY_SIGNOZ_OTLP_HTTP_PORT:-$(port_for signoz-otlp-http 4328 signoz-ingester-1 4318)}
  export PARITYPAY_SKYWALKING_UI_PORT=${PARITYPAY_SKYWALKING_UI_PORT:-$(port_for skywalking-ui 18080 paritypay-skywalking-ui 8080)}
  export PARITYPAY_SKYWALKING_GRPC_PORT=${PARITYPAY_SKYWALKING_GRPC_PORT:-$(port_for skywalking-grpc 11800 paritypay-skywalking-oap 11800)}
  export PARITYPAY_SKYWALKING_REST_PORT=${PARITYPAY_SKYWALKING_REST_PORT:-$(port_for skywalking-rest 12800 paritypay-skywalking-oap 12800)}
  export PARITYPAY_ZIPKIN_PORT=${PARITYPAY_ZIPKIN_PORT:-$(port_for zipkin 9411 paritypay-zipkin 9411)}
  export PARITYPAY_PINPOINT_WEB_PORT=${PARITYPAY_PINPOINT_WEB_PORT:-$(port_for pinpoint-web 8081 paritypay-pinpoint-web 8080)}
  export PARITYPAY_PINPOINT_AGENT_PORT=${PARITYPAY_PINPOINT_AGENT_PORT:-$(port_for pinpoint-agent 9991 paritypay-pinpoint-collector 9991)}
  export PARITYPAY_PINPOINT_STAT_PORT=${PARITYPAY_PINPOINT_STAT_PORT:-$(port_for pinpoint-stat 9992 paritypay-pinpoint-collector 9992)}
  export PARITYPAY_PINPOINT_SPAN_PORT=${PARITYPAY_PINPOINT_SPAN_PORT:-$(port_for pinpoint-span 9993 paritypay-pinpoint-collector 9993)}
}

# 프로필마다 띄울 서비스입니다. 이름을 주지 않으면 compose 가 기본 파일 전체를 띄웁니다.
backend_services() {
  case "$1" in
    jaeger) echo "otel-collector jaeger" ;;
    zipkin) echo "otel-collector zipkin" ;;
    tempo) echo "otel-collector tempo" ;;
    signoz) echo "otel-collector" ;;   # SigNoz 자체는 생성된 compose 로 띄웁니다
    datadog) echo "otel-collector datadog-agent" ;;
    skywalking) echo "skywalking-banyandb skywalking-oap skywalking-ui" ;;
    pinpoint) echo "pinpoint-zookeeper pinpoint-zookeeper pinpoint-hbase pinpoint-mysql pinpoint-redis pinpoint-collector pinpoint-web" ;;
    *) echo "" ;;
  esac
}

otlp_based() {
  case "$1" in
    jaeger | zipkin | tempo | signoz | datadog) return 0 ;;
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
    # 이 메시지는 stderr 로 보냅니다. stdout 은 함수의 반환값(경로)이라, 여기에 한 줄이라도 섞이면
    # JAVA_TOOL_OPTIONS 에 그 문장이 그대로 들어갑니다 — 실제로 그렇게 깨졌습니다.
    echo "== SkyWalking Java 에이전트 $SKYWALKING_AGENT_VERSION 내려받기" >&2
    local tgz="$AGENT_DIR/skywalking-agent.tgz"
    curl -fsSL -o "$tgz" \
      "https://archive.apache.org/dist/skywalking/java-agent/$SKYWALKING_AGENT_VERSION/apache-skywalking-java-agent-$SKYWALKING_AGENT_VERSION.tgz"
    mkdir -p "$dir"
    tar -xzf "$tgz" -C "$dir" --strip-components=1
    rm -f "$tgz"
  fi
  printf '%s' "$dir/skywalking-agent.jar"
}

download_pinpoint_agent() {
  local dir="$AGENT_DIR/pinpoint-agent-$PINPOINT_VERSION"
  if [ ! -d "$dir" ]; then
    mkdir -p "$AGENT_DIR"
    echo "== Pinpoint Java 에이전트 $PINPOINT_VERSION 내려받기" >&2
    local tgz="$AGENT_DIR/pinpoint-agent.tgz"
    curl -fsSL -o "$tgz" \
      "https://github.com/pinpoint-apm/pinpoint/releases/download/v$PINPOINT_VERSION/pinpoint-agent-$PINPOINT_VERSION.tar.gz"
    mkdir -p "$dir"
    tar -xzf "$tgz" -C "$dir" --strip-components=1
    rm -f "$tgz"
  fi
  printf '%s' "$dir/pinpoint-bootstrap.jar"
}

# SigNoz 는 2025년에 docker-compose 설치를 폐기하고 자체 설치기(foundryctl)로 옮겼습니다. 그래서
# 우리 compose 프로필에 서비스를 적어 넣을 수 없고, 생성기가 만든 compose 를 그대로 띄웁니다.
# 설치기는 공식 GitHub 릴리스의 tarball 을 sha256 으로 검증해 내려받습니다 — `curl | bash` 로
# 통째로 실행하는 대신 같은 일을 여기서 단계별로 합니다.
FOUNDRY_REPO=SigNoz/foundry
SIGNOZ_DIR="$APM_DIR/signoz"

install_foundryctl() {
  local bin="$APM_DIR/bin/foundryctl"
  if [ -x "$bin" ]; then printf '%s' "$bin"; return; fi
  mkdir -p "$APM_DIR/bin"
  local tag tarball checksums expected actual tmp
  tag=${FOUNDRY_VERSION:-$(curl -sIL -o /dev/null -w '%{url_effective}' \
    "https://github.com/$FOUNDRY_REPO/releases/latest" | sed 's|.*/tag/||')}
  local os arch
  os=$(uname -s | tr '[:upper:]' '[:lower:]')
  case "$(uname -m)" in arm64 | aarch64) arch=arm64 ;; *) arch=amd64 ;; esac
  tarball="foundry_${os}_${arch}.tar.gz"
  checksums="foundry_${tag#v}_checksums.txt"
  tmp=$(mktemp -d)
  echo "== foundryctl $tag 내려받기 (SigNoz 공식 릴리스)" >&2
  curl -fsSL -o "$tmp/$tarball" "https://github.com/$FOUNDRY_REPO/releases/download/$tag/$tarball"
  curl -fsSL -o "$tmp/$checksums" "https://github.com/$FOUNDRY_REPO/releases/download/$tag/$checksums"
  expected=$(awk -v f="$tarball" '$2 == f || $2 == "*"f {print $1; exit}' "$tmp/$checksums")
  actual=$(shasum -a 256 "$tmp/$tarball" | awk '{print $1}')
  [ -n "$expected" ] && [ "$expected" = "$actual" ] || {
    echo "foundryctl 체크섬이 맞지 않습니다 (기대 $expected / 실제 $actual)" >&2
    exit 1
  }
  tar -xzf "$tmp/$tarball" -C "$tmp"
  install -m 0755 "$tmp/foundry_${os}_${arch}/bin/foundryctl" "$bin"
  rm -rf "$tmp"
  printf '%s' "$bin"
}

# 생성된 compose 의 호스트 포트를 우리가 고른 값으로 바꿉니다. forge 를 다시 돌리면 덮이므로
# 매번 합니다 — 생성 파일을 손으로 고치지 않는다는 뜻이기도 합니다.
signoz_remap_ports() {
  python3 - "$SIGNOZ_DIR/pours/deployment/compose.yaml" <<PYEOF
import re, sys
path = sys.argv[1]
text = open(path).read()
for container, host in (("4317", "${PARITYPAY_SIGNOZ_OTLP_GRPC_PORT}"),
                        ("4318", "${PARITYPAY_SIGNOZ_OTLP_HTTP_PORT}"),
                        ("8080", "${PARITYPAY_SIGNOZ_PORT}")):
    text = re.sub(r"- %s:%s\b" % (container, container), "- %s:%s" % (host, container), text)
open(path, "w").write(text)
PYEOF
}

signoz_up() {
  if [ -n "$(own_running_port signoz-signoz-0 8080)" ]; then
    echo "== SigNoz 는 이미 떠 있습니다 (다시 만들지 않습니다)"
    return
  fi
  local ctl
  ctl=$(install_foundryctl)
  mkdir -p "$SIGNOZ_DIR"
  # 벤더 예시와 같은 최소 casting 입니다 (docs/examples/docker/compose/casting.yaml).
  cat > "$SIGNOZ_DIR/casting.yaml" <<'YAMLEOF'
apiVersion: v1alpha1
kind: Installation
metadata:
  name: signoz
spec:
  deployment:
    flavor: compose
    mode: docker
YAMLEOF
  ( cd "$SIGNOZ_DIR" && "$OLDPWD/$ctl" forge --no-ledger --no-updater >/dev/null )
  signoz_remap_ports
  docker compose -f "$SIGNOZ_DIR/pours/deployment/compose.yaml" up -d
}

signoz_down() {
  [ -f "$SIGNOZ_DIR/pours/deployment/compose.yaml" ] || return 0
  docker compose -f "$SIGNOZ_DIR/pours/deployment/compose.yaml" down >/dev/null 2>&1 || true
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
    local jar
    jar=$(download_pinpoint_agent)
    {
      echo "# scripts/apm.sh up $name 이 만든 파일입니다."
      echo "PARITYPAY_APM=$name"
      echo "MANAGEMENT_TRACING_ENABLED=false"
      # 에이전트는 Collector 와 같은 버전이어야 합니다 — 둘 다 $PINPOINT_VERSION 으로 고정합니다.
      # agentId 는 24자 제한이 있고, 같은 값으로 두 프로세스가 붙으면 하나가 거절당합니다.
      # 샘플링은 1(전량)로 둡니다. release 프로필의 기본값은 20(5%)이라 그대로 두면 다른 도구와
      # 같은 조건이 아닙니다.
      printf 'JAVA_TOOL_OPTIONS=%s\n' \
        "-javaagent:$PWD/$jar -Dpinpoint.agentId=pay-api-01 -Dpinpoint.applicationName=pay-api -Dprofiler.transport.grpc.collector.ip=127.0.0.1 -Dprofiler.transport.grpc.agent.collector.port=${PARITYPAY_PINPOINT_AGENT_PORT:-9991} -Dprofiler.transport.grpc.stat.collector.port=${PARITYPAY_PINPOINT_STAT_PORT:-9992} -Dprofiler.transport.grpc.span.collector.port=${PARITYPAY_PINPOINT_SPAN_PORT:-9993} -Dprofiler.sampling.counting.sampling-rate=1"
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
  # 같은 이름으로 다시 올리는 경우에도 내립니다. 살려 두면 그 컨테이너가 4317 을 쥐고 있어 포트가
  # 매번 한 칸씩 밀리고, 앱이 보내는 주소까지 따라 바뀝니다.
  if [ -n "$previous" ] && [ "$previous" != "none" ]; then
    echo "== 앞선 백엔드 정리: $previous"
    # 우리 컬렉터만 내립니다. 백엔드 쪽(Jaeger·Tempo·SigNoz)은 이미 떠 있으면 그대로 두고 포트를
    # 물려받습니다 — SigNoz 는 recreate 가 ClickHouse 클러스터 메타데이터에 죽은 복제본을 남겨
    # 스키마 마이그레이션이 끝나지 않는 일이 실제로 있었습니다.
    "${COMPOSE[@]}" rm -sf otel-collector >/dev/null 2>&1 || true
    if [ "$previous" != "$name" ]; then
      # shellcheck disable=SC2086
      "${COMPOSE[@]}" rm -sf $(backend_services "$previous") >/dev/null 2>&1 || true
      [ "$previous" = "signoz" ] && signoz_down
    fi
  fi
  assign_ports
  write_env "$name"
  echo "== APM 백엔드 기동: $name"
  # 서비스를 이름으로 지정합니다. 프로필만 주면 compose 가 기본 파일의 모든 서비스(postgres·기관 등)를
  # 함께 띄웁니다 — 그 스택은 dev.sh 의 것이고, 여기서 건드리면 남의 컨테이너를 내리게 됩니다.
  if [ "$name" = "signoz" ]; then
    signoz_up
  fi
  local services
  services=$(backend_services "$name")
  # shellcheck disable=SC2086
  PARITYPAY_APM="$name" "${COMPOSE[@]}" --profile "apm-$name" up -d $services
  # 뜨지 않은 서비스가 있으면 알립니다. 조용히 지나가면 트레이스가 안 보이는 이유를 한참 뒤에
  # 찾게 됩니다 — 실제로 Jaeger 컨테이너가 뜨지 않은 채 측정을 시작한 적이 있습니다.
  local svc missing=""
  for svc in $services; do
    PARITYPAY_APM="$name" "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx "$svc" || missing="$missing $svc"
  done
  [ -n "$missing" ] && echo "!! 뜨지 않은 서비스:$missing — 위 compose 출력을 보십시오" >&2
  status
  case "$name" in
    jaeger) echo "   UI http://localhost:$PARITYPAY_JAEGER_UI_PORT" ;;
    zipkin) echo "   UI http://localhost:$PARITYPAY_ZIPKIN_PORT" ;;
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
  local targets="otel-collector zipkin tempo signoz signoz-clickhouse datadog-agent skywalking-banyandb skywalking-oap skywalking-ui pinpoint-zookeeper pinpoint-hbase pinpoint-mysql pinpoint-redis pinpoint-collector pinpoint-web"
  if [ -n "$name" ] && [ "$name" != "none" ]; then
    targets=$(backend_services "$name")
  fi
  # shellcheck disable=SC2086
  "${COMPOSE[@]}" rm -sf $targets >/dev/null 2>&1 || true
  if [ -z "$name" ] || [ "$name" = "signoz" ]; then signoz_down; fi
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
  docker ps --format '   {{.Names}}\t{{.Status}}' | grep -E 'otel-collector|zipkin|tempo|signoz|clickhouse|skywalking|pinpoint|hbase|datadog|jaeger' || echo "   (APM 컨테이너 없음)"
}

case "${1:-}" in
  up) shift; up "$@" ;;
  down) down ;;
  status) status ;;
  agents) shift; case "${1:-otel}" in otel) download_otel_agent; echo ;; skywalking) download_skywalking_agent; echo ;; pinpoint) download_pinpoint_agent; echo ;; *) echo "otel | skywalking | pinpoint" >&2; exit 2 ;; esac ;;
  *) usage; exit 2 ;;
esac

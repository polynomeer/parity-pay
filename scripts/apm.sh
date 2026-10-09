#!/usr/bin/env bash
# APM 백엔드를 바꿔 끼웁니다.
#
#   scripts/apm.sh up <name>     백엔드를 띄우고, pay-api가 쓸 환경변수를 .apm/env 에 적습니다
#   scripts/apm.sh down          띄운 APM 백엔드를 내립니다 (의존 컨테이너는 건드리지 않습니다)
#   scripts/apm.sh status        지금 무엇이 켜져 있는지
#   scripts/apm.sh agents        에이전트 jar를 내려받습니다 (저장소에 담지 않습니다)
#
# 이름
#   자체 호스팅  jaeger | zipkin | tempo | signoz | openobserve | uptrace | elastic
#                skywalking | pinpoint
#   SaaS(키 필요) datadog | newrelic | honeycomb | dynatrace | splunk
#   대조군        none
#
# 애플리케이션 코드는 어떤 APM도 알지 못합니다. 두 가지 경로만 있습니다.
#
#   OTLP 계열 (위에서 skywalking·pinpoint 를 뺀 전부)
#       앱 --(OTLP 4317/4318)--> otel-collector --(exporter)--> 백엔드
#       바뀌는 것은 컬렉터 설정 파일 하나입니다 (deploy/observability/otel/collector-<name>.yaml).
#
#   자체 에이전트 계열 (skywalking·pinpoint)
#       앱 JVM 에 -javaagent 를 바꿔 끼웁니다. OTel 에이전트는 끕니다 — 둘을 같이 달면
#       같은 호출이 두 번 계측되고 오버헤드 비교가 무의미해집니다.
#
# SaaS 쪽은 **컨테이너가 없습니다.** 받는 쪽이 남의 서비스이므로 컬렉터 하나만 뜨고, 키는 이
# 스크립트가 저장하지 않습니다 — 환경변수로 받아 컬렉터에 넘기고 끝입니다.
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

usage() { sed -n '2,31p' "$0" | sed 's/^# \{0,1\}//'; }

# 자체 호스팅 백엔드의 비밀값입니다. 벤더 예시들은 전부 알려진 값을 적어 둡니다 — Pinpoint 는
# admin/admin, Uptrace 는 secret: FIXME 와 project1_secret, Elastic 은 토큰 없음. 그대로 두면
# 배포가 알려진 값으로 조용히 뜹니다(ADR-011: 비밀값에 기본값을 두지 않습니다).
#
# 한 번 만들어 파일에 두고 다시 씁니다. 파일을 지우면 다음 기동에서 새로 만들고, 그때는 그 도구의
# 볼륨도 함께 지워야 합니다 — 저장소 안의 사용자·DB 비밀번호가 옛 값으로 남아 있습니다.
#
# **모든 값을 미리 만듭니다.** compose 는 어느 프로필을 띄우든 파일 전체를 치환하므로, 쓰지 않는
# 서비스의 `${VAR:?}` 하나만 비어 있어도 `docker compose ps` 조차 실패합니다.
# $3 에 complex 를 주면 소문자·대문자·숫자·특수문자를 각각 하나 이상 넣습니다. OpenObserve v1.x 는
# 그 조건을 만족하지 않으면 **기동 중에 패닉합니다** ("ZO_ROOT_USER_PASSWORD is too weak") —
# 영숫자 24자도 거절합니다. v0.14 는 받았고, 1.0 에서 막혔습니다.
ensure_secret() {
  local file="$APM_DIR/$1" len=${2:-24} class=${3:-alnum} value
  if [ ! -s "$file" ]; then
    mkdir -p "$APM_DIR"
    if [ "$class" = complex ]; then
      # 조건을 만족할 때까지 다시 뽑습니다. 고정 접미사를 붙이는 쪽이 짧지만, 그러면 모든
      # 설치의 비밀번호가 같은 꼬리를 갖습니다.
      while :; do
        value=$(LC_ALL=C tr -dc 'A-Za-z0-9_.!-' < /dev/urandom | head -c "$len")
        case "$value" in
          *[a-z]*) case "$value" in *[A-Z]*) case "$value" in *[0-9]*) case "$value" in
            *[_.!-]*) break ;; esac ;; esac ;; esac ;;
        esac
      done
    else
      value=$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "$len")
    fi
    printf '%s' "$value" > "$file"
    chmod 600 "$file"
  fi
  cat "$file"
}

ensure_secrets() {
  export PARITYPAY_PINPOINT_MYSQL_PASSWORD PARITYPAY_OPENOBSERVE_EMAIL \
    PARITYPAY_OPENOBSERVE_PASSWORD PARITYPAY_OPENOBSERVE_AUTH PARITYPAY_UPTRACE_SECRET \
    PARITYPAY_UPTRACE_ADMIN_EMAIL PARITYPAY_UPTRACE_ADMIN_PASSWORD \
    PARITYPAY_UPTRACE_PROJECT_TOKEN PARITYPAY_UPTRACE_CH_PASSWORD \
    PARITYPAY_UPTRACE_PG_PASSWORD PARITYPAY_ELASTIC_APM_TOKEN PARITYPAY_KIBANA_ENCRYPTION_KEY
  PARITYPAY_PINPOINT_MYSQL_PASSWORD=$(ensure_secret pinpoint-mysql-password 24)
  # 이메일은 비밀값이 아니라 로그인 식별자입니다.
  PARITYPAY_OPENOBSERVE_EMAIL=${PARITYPAY_OPENOBSERVE_EMAIL:-paritypay@local.test}
  PARITYPAY_OPENOBSERVE_PASSWORD=$(ensure_secret openobserve-password 24 complex)
  # OpenObserve 의 OTLP 는 Basic 인증입니다. 컬렉터에 넘기는 것은 base64(이메일:비밀번호) 입니다.
  PARITYPAY_OPENOBSERVE_AUTH=$(printf '%s:%s' \
    "$PARITYPAY_OPENOBSERVE_EMAIL" "$PARITYPAY_OPENOBSERVE_PASSWORD" | base64 | tr -d '\n')
  # Uptrace 는 secret 이 FIXME 이면 **뜨지 않습니다**(2.x 에서 막았습니다). 벤더는 openssl rand -hex 32
  # 를 권하고, 여기서는 같은 길이의 영숫자를 씁니다.
  PARITYPAY_UPTRACE_SECRET=$(ensure_secret uptrace-secret 64)
  PARITYPAY_UPTRACE_ADMIN_EMAIL=${PARITYPAY_UPTRACE_ADMIN_EMAIL:-paritypay@local.test}
  PARITYPAY_UPTRACE_ADMIN_PASSWORD=$(ensure_secret uptrace-admin-password 24)
  PARITYPAY_UPTRACE_PROJECT_TOKEN=$(ensure_secret uptrace-project-token 32)
  PARITYPAY_UPTRACE_CH_PASSWORD=$(ensure_secret uptrace-clickhouse-password 24)
  PARITYPAY_UPTRACE_PG_PASSWORD=$(ensure_secret uptrace-postgres-password 24)
  PARITYPAY_ELASTIC_APM_TOKEN=$(ensure_secret elastic-apm-token 32)
  # Kibana 는 32자 미만이면 거절합니다.
  PARITYPAY_KIBANA_ENCRYPTION_KEY=$(ensure_secret kibana-encryption-key 48)
}
ensure_secrets

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
  export PARITYPAY_OPENOBSERVE_PORT=${PARITYPAY_OPENOBSERVE_PORT:-$(port_for openobserve 5080 paritypay-openobserve 5080)}
  # Uptrace 는 화면과 OTLP/HTTP 가 같은 포트(컨테이너 80)입니다. 벤더 예시의 호스트 포트를 씁니다.
  export PARITYPAY_UPTRACE_PORT=${PARITYPAY_UPTRACE_PORT:-$(port_for uptrace 14318 paritypay-uptrace 80)}
  export PARITYPAY_KIBANA_PORT=${PARITYPAY_KIBANA_PORT:-$(port_for kibana 5601 paritypay-elastic-kibana 5601)}
  # Elasticsearch 는 앱이 쓰지 않습니다. 트레이스가 색인까지 갔는지 직접 확인하려고 엽니다.
  export PARITYPAY_ELASTICSEARCH_PORT=${PARITYPAY_ELASTICSEARCH_PORT:-$(port_for elasticsearch 9200 paritypay-elastic-elasticsearch 9200)}
}

# 프로필마다 띄울 서비스입니다. 이름을 주지 않으면 compose 가 기본 파일 전체를 띄웁니다.
backend_services() {
  case "$1" in
    jaeger) echo "otel-collector jaeger" ;;
    zipkin) echo "otel-collector zipkin" ;;
    tempo) echo "otel-collector tempo" ;;
    signoz) echo "otel-collector" ;;   # SigNoz 자체는 생성된 compose 로 띄웁니다
    openobserve) echo "otel-collector openobserve" ;;
    uptrace) echo "otel-collector uptrace-clickhouse uptrace-postgres uptrace-redis uptrace" ;;
    elastic) echo "otel-collector elastic-elasticsearch elastic-apm-server elastic-kibana" ;;
    # 받는 쪽이 SaaS 라 컨테이너가 없습니다 — 컬렉터 하나만 뜹니다.
    datadog | newrelic | honeycomb | dynatrace | splunk) echo "otel-collector" ;;
    skywalking) echo "skywalking-banyandb skywalking-oap skywalking-ui" ;;
    pinpoint) echo "pinpoint-zookeeper pinpoint-zookeeper pinpoint-hbase pinpoint-mysql pinpoint-redis pinpoint-collector pinpoint-web" ;;
    *) echo "" ;;
  esac
}

otlp_based() {
  case "$1" in
    jaeger | zipkin | tempo | signoz | openobserve | uptrace | elastic) return 0 ;;
    datadog | newrelic | honeycomb | dynatrace | splunk) return 0 ;;
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

# SaaS 백엔드는 키가 없으면 **시작하지 않습니다.** 띄워 놓고 조용히 아무것도 안 보내는 것이
# 최악입니다 — 측정했다고 믿은 뒤에 데이터가 없다는 것을 알게 됩니다.
#
# 이 스크립트는 **어떤 키도 저장하지 않습니다.** .apm/env 에도 적지 않고 환경변수로만 넘깁니다.
# 비밀값이 아닌 것(리전·주소)에만 기본값을 둡니다(ADR-011).
check_saas() {
  case "$1" in
    datadog)
      export DD_SITE=${DD_SITE:-datadoghq.com}
      [ -n "${DD_API_KEY:-}" ] && return 0
      saas_hint datadog "DD_API_KEY" \
        "DD_API_KEY=<키> DD_SITE=<사이트> scripts/apm.sh up datadog" \
        "키는 Organization Settings → API keys 에 있고, 사이트는 로그인 URL 입니다" \
        "(datadoghq.com · datadoghq.eu · ap1.datadoghq.com …). 기본값은 datadoghq.com 입니다."
      ;;
    newrelic)
      export NEW_RELIC_OTLP_ENDPOINT=${NEW_RELIC_OTLP_ENDPOINT:-https://otlp.nr-data.net}
      [ -n "${NEW_RELIC_LICENSE_KEY:-}" ] && return 0
      saas_hint newrelic "NEW_RELIC_LICENSE_KEY" \
        "NEW_RELIC_LICENSE_KEY=<키> scripts/apm.sh up newrelic" \
        "키는 API keys 화면의 **INGEST - LICENSE** 종류입니다 (USER 키가 아닙니다)." \
        "EU 계정이면 NEW_RELIC_OTLP_ENDPOINT=https://otlp.eu01.nr-data.net 도 함께 줍니다."
      ;;
    honeycomb)
      export HONEYCOMB_API_ENDPOINT=${HONEYCOMB_API_ENDPOINT:-https://api.honeycomb.io}
      [ -n "${HONEYCOMB_API_KEY:-}" ] && return 0
      saas_hint honeycomb "HONEYCOMB_API_KEY" \
        "HONEYCOMB_API_KEY=<키> scripts/apm.sh up honeycomb" \
        "키는 Environment settings → API keys 에서 만들고, 권한은 Send events 만 있으면 됩니다." \
        "EU 계정이면 HONEYCOMB_API_ENDPOINT=https://api.eu1.honeycomb.io 도 함께 줍니다."
      ;;
    dynatrace)
      # 주소에 테넌트 ID 가 들어가므로 기본값을 둘 수 없습니다. 토큰 종류에 따라 접두어도 다릅니다.
      if [ -n "${DT_ENDPOINT:-}" ] && [ -n "${DT_AUTHORIZATION:-}" ]; then return 0; fi
      if [ -n "${DT_ENDPOINT:-}" ] && [ -n "${DT_API_TOKEN:-}" ]; then
        export DT_AUTHORIZATION="Api-Token ${DT_API_TOKEN}"
        return 0
      fi
      if [ -n "${DT_ENDPOINT:-}" ] && [ -n "${DT_PLATFORM_TOKEN:-}" ]; then
        export DT_AUTHORIZATION="Bearer ${DT_PLATFORM_TOKEN}"
        return 0
      fi
      saas_hint dynatrace "DT_ENDPOINT 와 토큰" \
        "DT_ENDPOINT=https://<환경ID>.live.dynatrace.com/api/v2/otlp DT_API_TOKEN=<토큰> scripts/apm.sh up dynatrace" \
        "토큰 권한은 \"Ingest OpenTelemetry traces\" 하나입니다. 신형 platform token 이면" \
        "DT_API_TOKEN 대신 DT_PLATFORM_TOKEN 으로 줍니다 (접두어가 Bearer 로 바뀝니다)."
      ;;
    splunk)
      # realm 만 주면 주소를 만듭니다. realm 은 비밀값이 아니라 리전입니다.
      if [ -z "${SPLUNK_TRACES_ENDPOINT:-}" ] && [ -n "${SPLUNK_REALM:-}" ]; then
        export SPLUNK_TRACES_ENDPOINT="https://ingest.${SPLUNK_REALM}.signalfx.com/v2/trace/otlp"
      fi
      if [ -n "${SPLUNK_ACCESS_TOKEN:-}" ] && [ -n "${SPLUNK_TRACES_ENDPOINT:-}" ]; then return 0; fi
      saas_hint splunk "SPLUNK_ACCESS_TOKEN 과 SPLUNK_REALM" \
        "SPLUNK_ACCESS_TOKEN=<토큰> SPLUNK_REALM=us1 scripts/apm.sh up splunk" \
        "realm 은 로그인 주소에 나오는 리전입니다 (us0·us1·eu0·jp0 …)." \
        "자체 설치 Splunk Enterprise 에는 APM 화면이 없습니다 — 받는 쪽은 Observability Cloud 입니다."
      ;;
  esac
}

saas_hint() {
  local name=$1 what=$2; shift 2
  echo "$name 은 받는 쪽이 SaaS 입니다. $what 가 없어 진행하지 않습니다." >&2
  echo >&2
  echo "  $1" >&2; shift
  echo >&2
  while [ $# -gt 0 ]; do echo "$1" >&2; shift; done
  echo >&2
  echo "이 스크립트는 키를 저장하지 않습니다 — .apm/env 에도 적지 않습니다." >&2
  exit 2
}

up() {
  local name=${1:-}
  [ -n "$name" ] || { usage; exit 2; }
  if [ "$name" = "none" ]; then
    write_env none
    echo "== 에이전트 없음(대조군)으로 설정했습니다. scripts/dev.sh 로 앱을 다시 띄우십시오."
    return
  fi
  check_saas "$name"
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
    openobserve)
      echo "   UI http://localhost:$PARITYPAY_OPENOBSERVE_PORT"
      echo "   로그인 $PARITYPAY_OPENOBSERVE_EMAIL / 비밀번호는 $APM_DIR/openobserve-password"
      ;;
    uptrace)
      echo "   UI http://localhost:$PARITYPAY_UPTRACE_PORT"
      echo "   로그인 $PARITYPAY_UPTRACE_ADMIN_EMAIL / 비밀번호는 $APM_DIR/uptrace-admin-password"
      ;;
    elastic)
      echo "   UI http://localhost:$PARITYPAY_KIBANA_PORT/app/apm (Kibana 는 처음 뜨는 데 1분 넘게 걸립니다)"
      echo "   색인 확인 http://localhost:$PARITYPAY_ELASTICSEARCH_PORT/traces-apm*/_count"
      ;;
    datadog) echo "   UI https://app.${DD_SITE} (컨테이너 없음 — 받는 쪽이 SaaS 입니다)" ;;
    newrelic) echo "   UI https://one.newrelic.com (컨테이너 없음 — 받는 쪽이 SaaS 입니다)" ;;
    honeycomb) echo "   UI https://ui.honeycomb.io (컨테이너 없음 — 받는 쪽이 SaaS 입니다)" ;;
    dynatrace) echo "   UI ${DT_ENDPOINT%%/api/*} (컨테이너 없음 — 받는 쪽이 SaaS 입니다)" ;;
    splunk) echo "   UI https://app.${SPLUNK_REALM:-us1}.signalfx.com (컨테이너 없음 — 받는 쪽이 SaaS 입니다)" ;;
  esac
  echo
  echo "다음: scripts/dev.sh (또는 이미 떠 있으면 scripts/dev.sh down && scripts/dev.sh)"
}

down() {
  local name
  name=$(cat "$ACTIVE_FILE" 2>/dev/null || echo "")
  # stop/rm 을 서비스 이름으로 합니다. `down` 은 파일 전체를 내리므로 dev.sh 의 컨테이너까지 갑니다.
  local targets="otel-collector zipkin tempo signoz signoz-clickhouse skywalking-banyandb skywalking-oap skywalking-ui pinpoint-zookeeper pinpoint-hbase pinpoint-mysql pinpoint-redis pinpoint-collector pinpoint-web openobserve uptrace uptrace-clickhouse uptrace-postgres uptrace-redis elastic-elasticsearch elastic-apm-server elastic-kibana"
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
  # 도구 이름으로 거르면 **남의 프로젝트 컨테이너가 섞입니다** — 이 호스트에 다른 프로젝트의
  # elasticsearch·jaeger 가 떠 있고, 그것이 우리 백엔드인 것처럼 보였습니다. 우리 이름만 봅니다.
  docker ps --format '   {{.Names}}\t{{.Status}}' | grep -E 'paritypay-|signoz-' || echo "   (APM 컨테이너 없음)"
}

case "${1:-}" in
  up) shift; up "$@" ;;
  down) down ;;
  status) status ;;
  agents) shift; case "${1:-otel}" in otel) download_otel_agent; echo ;; skywalking) download_skywalking_agent; echo ;; pinpoint) download_pinpoint_agent; echo ;; *) echo "otel | skywalking | pinpoint" >&2; exit 2 ;; esac ;;
  *) usage; exit 2 ;;
esac

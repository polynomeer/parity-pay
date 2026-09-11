#!/usr/bin/env bash
# 배포 형태를 띄웁니다. 근거: ADR-011
#
# 비밀값은 이미지 안에 없고, 없으면 애플리케이션이 뜨지 않습니다. 이 스크립트는 **로컬 확인용**
# 값을 만들어 넣습니다. 운영에서는 비밀 관리 도구가 이 자리를 대신합니다.
#
# 사용: deploy/run.sh [up|down|logs]
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f docker-compose.deploy.yml)
ENV_FILE=deploy/.env

# 비밀값을 한 번 만들어 두고 재사용합니다. 매번 새로 만들면 재시작할 때마다 기존 세션과
# 웹훅 서명이 전부 무효가 됩니다.
if [ ! -f "$ENV_FILE" ]; then
  echo "== 비밀값 생성 ($ENV_FILE)"
  {
    echo "# deploy/run.sh가 만든 로컬 확인용 값입니다. 커밋되지 않습니다."
    echo "PARITYPAY_DB_PASSWORD=$(openssl rand -hex 16)"
    echo "PARITYPAY_JWT_SECRET=$(openssl rand -hex 32)"
    echo "PARITYPAY_WEBHOOK_SECRET=$(openssl rand -hex 16)"
    # 첫 운영자의 비밀번호입니다. 운영에서는 비밀 관리 도구가 이 자리를 대신합니다.
    echo "PARITYPAY_OPS_PASSWORD=$(openssl rand -hex 12)"
  } > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
fi
COMPOSE+=(--env-file "$ENV_FILE")

case "${1:-up}" in
  down)
    # 볼륨까지 지웁니다. 확인용 스택이라 남겨 둘 이유가 없습니다.
    "${COMPOSE[@]}" down -v
    exit 0
    ;;
  logs)
    shift
    "${COMPOSE[@]}" logs "$@"
    exit 0
    ;;
esac

# 자체 서명 인증서입니다. 쿠키에 Secure가 붙어 있어 TLS 없이는 브라우저가 저장하지 않습니다.
# 두 앱은 **호스트이름이 다릅니다** — app.localhost·ops.localhost. 포트만 다르면 쿠키가 서로
# 넘어갑니다. *.localhost는 브라우저가 /etc/hosts 없이 루프백으로 풉니다. 근거: ADR-011
#
# OpenSSL 3.0(ubuntu 24.04)에는 `-quiet`이 없습니다. 처음에 그 옵션을 쓰고 stderr까지 버려서
# CI가 이유 없이 죽었습니다. 키 생성 진행 표시가 나오더라도 오류를 숨기지 않습니다.
CERT_DIR=deploy/certs
if [ ! -f "$CERT_DIR/paritypay.crt" ]; then
  echo "== 자체 서명 인증서 생성 ($CERT_DIR)"
  mkdir -p "$CERT_DIR"
  openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
    -keyout "$CERT_DIR/paritypay.key" -out "$CERT_DIR/paritypay.crt" \
    -subj "/CN=paritypay.local" \
    -addext "subjectAltName=DNS:app.localhost,DNS:ops.localhost,DNS:localhost"
fi

echo "== 빌드와 기동"
"${COMPOSE[@]}" up -d --build

echo "== pay-api 대기"
for _ in $(seq 1 60); do
  status=$("${COMPOSE[@]}" ps --format '{{.Health}}' pay-api 2>/dev/null || true)
  [ "$status" = "healthy" ] && break
  sleep 3
done
[ "$("${COMPOSE[@]}" ps --format '{{.Health}}' pay-api)" = "healthy" ] || {
  echo "pay-api가 뜨지 않았습니다:"
  "${COMPOSE[@]}" logs --tail 40 pay-api
  exit 1
}

CUSTOMER_PORT=${PARITYPAY_CUSTOMER_PORT:-8181}
OPS_PORT=${PARITYPAY_OPS_PORT:-8182}
echo "== 준비됨"
echo "   고객 앱   https://app.localhost:${CUSTOMER_PORT}"
echo "   운영 콘솔 https://ops.localhost:${OPS_PORT}"
echo "   pay-api는 바깥에 열려 있지 않습니다 (ADR-011)"
echo "   인증서는 자체 서명이라 브라우저가 한 번 경고합니다."
echo
echo "   운영자 ops-operator@paritypay.local / $(grep PARITYPAY_OPS_PASSWORD "$ENV_FILE" | cut -d= -f2)"

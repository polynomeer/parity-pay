#!/usr/bin/env python3
"""M-011: 미확정 거래가 확정되기까지 걸리는 시간.

reports/11은 "실제 복구 지연 시간 분포. 스케줄 주기 설정값만 알고 있습니다"를 미측정으로 남겨
두었습니다. 그동안은 아무도 그 값을 필요로 하지 않았는데, 프론트엔드(DOC-14 FE-003)가 폴링을
언제까지 할지 정해야 하면서 필요해졌습니다.

**클라이언트가 겪는 것을 그대로 잽니다.** 202를 받은 순간부터 `GET`이 종결 상태를 보여 줄
때까지입니다. 서버 내부 로그가 아니라 조회 API로 재는 이유는, 화면이 볼 수 있는 것이 그것뿐이기
때문입니다.

두 경로를 나눠 잽니다. 둘은 성격이 완전히 다릅니다.

1. **외부에 기록이 있는 경우** (`TIMEOUT_AFTER_WITHDRAWAL`) — 자금은 이미 움직였고 조회 한 번이면
   확정됩니다. 정상적인 "응답만 유실된" 상황입니다.
2. **외부에 기록이 없는 경우** (`TIMEOUT_BEFORE_WITHDRAWAL`) — 없다고 한 번에 단정하지 않고
   `not-found-confirm-threshold`번 연속 확인한 뒤에야 FAILED입니다. 확인 사이에 백오프가 있으므로
   훨씬 깁니다. 화면이 견뎌야 하는 최악은 이쪽입니다.

사전 조건: docker compose로 postgres·mock-bank·mock-pg가 떠 있어야 하고, bootJar가 필요합니다.

사용:
    ./gradlew :apps:pay-api:bootJar
    python3 load-tests/recovery-latency-experiment.py \\
        --jar apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar --runs 12

근거: reports/11 M-011, docs/14-frontend-design.md §13 열린 질문 3, ADR-007
"""

import argparse
import json
import os
import signal
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

OPERATOR = {"email": "ops-operator@paritypay.local", "password": "local-ops-password"}
PASSWORD = "recovery-experiment-password"


def call(base, method, path, body=None, token=None, idempotency_key=None, timeout=60):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, (json.loads(raw) if raw else {})


def start_app(jar, port, log_path, java_bin):
    log = open(log_path, "w")
    process = subprocess.Popen(
        [java_bin, "-jar", jar],
        stdout=log,
        stderr=subprocess.STDOUT,
        env={
            **os.environ,
            "SPRING_PROFILES_ACTIVE": "local",
            "PARITYPAY_PORT": str(port),
            "PARITYPAY_DB_URL": os.environ.get("PARITYPAY_DB_URL", "jdbc:postgresql://localhost:5432/paritypay"),
            "PARITYPAY_KAFKA_SERVERS": os.environ.get("PARITYPAY_KAFKA_SERVERS", "localhost:9092"),
            "PARITYPAY_TRACE_SAMPLING": "0",
        },
    )
    for _ in range(90):
        if "Started ParityPayApplication" in Path(log_path).read_text(errors="ignore"):
            return process
        if process.poll() is not None:
            raise RuntimeError(f"앱이 뜨지 않았습니다: {log_path}")
        time.sleep(2)
    raise RuntimeError(f"앱 기동 시간 초과: {log_path}")


def provision(base, index):
    """충전할 수 있는 회원 하나를 만듭니다."""
    email = f"recovery-{int(time.time() * 1000)}-{index}@example.com"
    _, member = call(base, "POST", "/api/v1/members", {"email": email, "password": PASSWORD})
    _, token = call(base, "POST", "/api/v1/auth/tokens", {"email": email, "password": PASSWORD})
    access = token["accessToken"]
    _, account = call(
        base,
        "POST",
        "/api/v1/bank-accounts",
        {"bankCode": "004", "accountNumber": f"110{index:012d}", "initialBalance": 100_000_000},
        token=access,
    )
    return member["walletId"], account["bankAccountId"], access


def set_bank_mode(base, operator_token, mode):
    status, _ = call(base, "POST", "/api/v1/admin/mock-bank/mode", {"mode": mode}, token=operator_token)
    if status >= 400:
        raise RuntimeError(f"은행 모드를 {mode}로 바꾸지 못했습니다 (HTTP {status})")


def set_pg_mode(base, operator_token, mode):
    status, _ = call(base, "POST", "/api/v1/admin/mock-pg/mode", {"mode": mode}, token=operator_token)
    if status >= 400:
        raise RuntimeError(f"PG 모드를 {mode}로 바꾸지 못했습니다 (HTTP {status})")


def measure_payment(base, operator_token, index, mode, poll_interval, deadline_seconds):
    """카드 결제 경로입니다. 설정이 충전과 같은지 가정하지 않고 확인합니다."""
    wallet_id, _, access = provision(base, index)

    set_pg_mode(base, operator_token, mode)
    started = time.monotonic()
    key = f"recovery-pay-{mode}-{index}-{int(started * 1000)}"
    status, body = call(
        base,
        "POST",
        "/api/v1/payments",
        {
            "orderId": f"order-{key}",
            "walletId": wallet_id,
            "merchantId": "11111111-2222-3333-4444-555555555555",
            "amount": 10_000,
            "currency": "KRW",
            "method": "EXTERNAL_PG",
        },
        token=access,
        idempotency_key=key,
    )
    accepted_at = time.monotonic()
    set_pg_mode(base, operator_token, "NORMAL")

    if status != 202:
        return {"index": index, "mode": mode, "outcome": f"미확정이 아님(HTTP {status})", "seconds": None}

    payment_id = body["paymentId"]
    while time.monotonic() - accepted_at < deadline_seconds:
        _, view = call(base, "GET", f"/api/v1/payments/{payment_id}", token=access)
        if view.get("status") in ("APPROVED", "FAILED", "CANCELED"):
            return {
                "index": index,
                "mode": mode,
                "outcome": view["status"],
                "seconds": time.monotonic() - started,
            }
        time.sleep(poll_interval)
    return {"index": index, "mode": mode, "outcome": "시간 초과", "seconds": None}


def measure_one(base, operator_token, index, mode, poll_interval, deadline_seconds):
    """한 건을 미확정으로 만들고, 조회가 종결 상태를 보일 때까지 잽니다."""
    wallet_id, bank_account_id, access = provision(base, index)

    set_bank_mode(base, operator_token, mode)
    started = time.monotonic()
    status, body = call(
        base,
        "POST",
        "/api/v1/top-ups",
        {"walletId": wallet_id, "bankAccountId": bank_account_id, "amount": 10_000, "currency": "KRW"},
        token=access,
        idempotency_key=f"recovery-{mode}-{index}-{int(started * 1000)}",
    )
    accepted_at = time.monotonic()
    # 조회는 정상이어야 복구가 확정할 수 있습니다. 모드는 즉시 되돌립니다.
    set_bank_mode(base, operator_token, "NORMAL")

    if status != 202:
        return {"index": index, "mode": mode, "outcome": f"미확정이 아님(HTTP {status})", "seconds": None}

    top_up_id = body["topUpId"]
    first_seen = body.get("status")
    while time.monotonic() - accepted_at < deadline_seconds:
        _, view = call(base, "GET", f"/api/v1/top-ups/{top_up_id}", token=access)
        if view.get("status") in ("SUCCEEDED", "FAILED"):
            return {
                "index": index,
                "mode": mode,
                "outcome": view["status"],
                "accepted_as": first_seen,
                # 클라이언트가 요청을 보낸 순간부터입니다. 202를 받은 시점이 아니라 사용자가
                # 기다리기 시작한 시점이 기준입니다.
                "seconds": time.monotonic() - started,
            }
        time.sleep(poll_interval)
    return {"index": index, "mode": mode, "outcome": "시간 초과", "accepted_as": first_seen, "seconds": None}


def summarize(label, results):
    done = [r["seconds"] for r in results if r["seconds"] is not None]
    outcomes = {}
    for r in results:
        outcomes[r["outcome"]] = outcomes.get(r["outcome"], 0) + 1
    lines = [f"===== {label} ====="]
    lines.append(f"결과: {', '.join(f'{k} {v}건' for k, v in sorted(outcomes.items()))}")
    if not done:
        lines.append("확정된 건이 없어 분포를 낼 수 없습니다.")
        return "\n".join(lines), None
    done.sort()
    stats = {
        "n": len(done),
        "min": done[0],
        "median": statistics.median(done),
        "p95": done[min(len(done) - 1, int(len(done) * 0.95))],
        "max": done[-1],
    }
    lines.append(
        f"확정까지 (초)  n={stats['n']}  최소 {stats['min']:.1f}  중앙값 {stats['median']:.1f}  "
        f"p95 {stats['p95']:.1f}  최대 {stats['max']:.1f}"
    )
    lines.append("각 실행: " + ", ".join(f"{v:.1f}" for v in done))
    return "\n".join(lines), stats


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--java", default=os.environ.get("JAVA_BIN", "java"))
    parser.add_argument("--port", type=int, default=8092)
    parser.add_argument("--runs", type=int, default=12)
    parser.add_argument("--poll-interval", type=float, default=0.5)
    parser.add_argument("--deadline", type=float, default=900.0, help="한 건을 포기하기까지의 초")
    parser.add_argument("--out", default="/tmp/m011")
    parser.add_argument("--target", choices=("top-up", "payment", "both"), default="top-up")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    base = f"http://localhost:{args.port}"

    app = start_app(args.jar, args.port, out_dir / "app.log", args.java)
    try:
        _, operator = call(base, "POST", "/api/v1/auth/tokens", OPERATOR)
        operator_token = operator["accessToken"]

        scenarios = []
        if args.target in ("top-up", "both"):
            scenarios += [
                (measure_one, "TIMEOUT_AFTER_WITHDRAWAL", "충전 · 외부에 기록이 있음 (응답만 유실) → SUCCEEDED"),
                (measure_one, "TIMEOUT_BEFORE_WITHDRAWAL", "충전 · 외부에 기록이 없음 (연속 확인 필요) → FAILED"),
            ]
        if args.target in ("payment", "both"):
            scenarios += [
                (measure_payment, "TIMEOUT_AFTER_APPROVAL", "카드 결제 · 외부에 기록이 있음 → APPROVED"),
                (measure_payment, "TIMEOUT_BEFORE_APPROVAL", "카드 결제 · 외부에 기록이 없음 → FAILED"),
            ]

        report = []
        raw = []
        for measure, mode, label in scenarios:
            results = []
            for i in range(args.runs):
                result = measure(base, operator_token, i, mode, args.poll_interval, args.deadline)
                results.append(result)
                seconds = "확정 못함" if result["seconds"] is None else f"{result['seconds']:.1f}초"
                print(f"  [{mode}] {i + 1}/{args.runs} {result['outcome']} {seconds}", flush=True)
            raw.extend(results)
            text, _ = summarize(label, results)
            print("\n" + text + "\n", flush=True)
            report.append(text)

        (out_dir / "report.txt").write_text("\n\n".join(report) + "\n")
        (out_dir / "raw.json").write_text(json.dumps(raw, ensure_ascii=False, indent=2))
        print(f"원본 결과: {out_dir}/report.txt")
    finally:
        # 다음 사람이 쓰는 환경을 미확정 모드로 남겨 두지 않습니다.
        try:
            _, operator = call(base, "POST", "/api/v1/auth/tokens", OPERATOR)
            set_bank_mode(base, operator["accessToken"], "NORMAL")
        except Exception:
            pass
        if app.poll() is None:
            app.send_signal(signal.SIGTERM)
            try:
                app.wait(timeout=30)
            except subprocess.TimeoutExpired:
                app.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""M-007: 결제·취소 한 건이 트랜잭션 안에서 어떤 순서로 문장을 보내는지 봅니다.

`lock-wait-experiment.py`는 부하를 주고 **집계**를 봅니다. 집계로는 "잠금을 얼마나 오래 쥐고
있는가"를 알 수 없습니다. 여기서는 반대로 부하를 주지 않고 **한 건**을 문장 단위로 봅니다.

PostgreSQL에 모든 문장을 기록하게 한 뒤 결제 하나를 보내고, 그 백엔드의 BEGIN~COMMIT 구간을
꺼내 시각을 붙입니다. 잔액 차감 UPDATE가 지갑 행을 잠그고 잠금은 COMMIT에서 풀리므로,
그 두 지점 사이가 **다른 결제가 이 지갑에서 기다려야 하는 구간**입니다.

사전 조건: docker compose로 PostgreSQL·Mock Bank가 떠 있고, pay-api가 --port에서 돌고 있어야
합니다. 문장 기록은 이 스크립트가 켜고 끕니다.

사용:
    python3 load-tests/transaction-timeline.py --port 8080
    python3 load-tests/transaction-timeline.py --port 8080 --operation cancel

근거: reports/11 M-007
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from datetime import datetime

CONTAINER = os.environ.get("PG_CONTAINER", "paritypay-postgres")
PASSWORD = "load-test-password"
# 차감은 JDBC가, 증가는 JPQL이 만듭니다. Hibernate는 별칭을 붙여
# `set available_amount=(wbje1_0.available_amount+$1)` 형태로 내보내므로 문자열로 맞출 수 없습니다.
BALANCE_CHANGE = re.compile(r"(?is)update\s+wallet_balance\b.*available_amount")
HEAD = re.compile(
    r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+) UTC \[(\d+)\] LOG:  duration: ([\d.]+) ms  (\w+)[^:]*:\s*(.*)$"
)


def psql(sql, db="postgres"):
    out = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "paritypay", "-d", db, "-tA", "-q", "-c", sql],
        capture_output=True,
        text=True,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def post(base, path, body, token=None, idempotency_key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(
        base + path, data=json.dumps(body).encode(), headers=headers, method="POST"
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        raw = response.read().decode()
    return json.loads(raw) if raw else {}


def provision(base):
    """회원·계좌·충전까지 마치고 결제할 수 있는 지갑을 만듭니다."""
    stamp = int(time.time() * 1000)
    email = f"timeline-{stamp}@example.com"
    member = post(base, "/api/v1/members", {"email": email, "password": PASSWORD})
    token = post(base, "/api/v1/auth/tokens", {"email": email, "password": PASSWORD})["accessToken"]
    bank_account = post(
        base,
        "/api/v1/bank-accounts",
        {"bankCode": "004", "accountNumber": f"110{stamp:012d}"[:15], "initialBalance": 100_000_000},
        token,
    )["bankAccountId"]
    post(
        base,
        "/api/v1/top-ups",
        {"walletId": member["walletId"], "bankAccountId": bank_account, "amount": 1_000_000, "currency": "KRW"},
        token,
        f"timeline-topup-{stamp}",
    )
    return member["walletId"], token, stamp


def parse_log(text):
    """여러 줄로 나뉜 SQL을 한 항목으로 모읍니다."""
    entries = []
    current = None
    for line in text.splitlines():
        match = HEAD.match(line)
        if match:
            if current:
                entries.append(current)
            current = {
                "ts": match.group(1),
                "pid": match.group(2),
                "dur": float(match.group(3)),
                "kind": match.group(4),
                "sql": match.group(5),
            }
        elif current is not None and not re.match(r"^\d{4}-\d\d-\d\d", line):
            current["sql"] += " " + line.strip()
    if current:
        entries.append(current)
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--amount", type=int, default=1000)
    parser.add_argument("--operation", choices=("approve", "cancel"), default="approve")
    args = parser.parse_args()
    base = f"http://localhost:{args.port}"

    wallet_id, token, stamp = provision(base)
    # 취소를 보려면 취소할 결제가 먼저 있어야 합니다. 그 결제는 기록을 켜기 전에 만듭니다.
    payment_id = None
    if args.operation == "cancel":
        key = f"timeline-prepay-{stamp}"
        payment_id = post(
            base,
            "/api/v1/payments",
            {
                "orderId": f"order-{key}",
                "walletId": wallet_id,
                "merchantId": "11111111-2222-3333-4444-555555555555",
                "amount": args.amount,
                "currency": "KRW",
                "method": "PAY_MONEY",
            },
            token,
            key,
        )["paymentId"]
    # `ALTER DATABASE`는 **새 커넥션부터** 적용됩니다. 앱의 커넥션 풀은 기동 때 이미 열려 있으므로
    # 그 방식으로는 결제 문장이 하나도 기록되지 않습니다. 설정 파일을 고치고 reload해야 이미 열린
    # 백엔드에도 적용됩니다.
    psql("ALTER SYSTEM SET log_min_duration_statement = 0")
    psql("SELECT pg_reload_conf()")
    time.sleep(1)
    try:
        key = f"timeline-{args.operation}-{stamp}"
        started = time.monotonic()
        if args.operation == "cancel":
            post(
                base,
                f"/api/v1/payments/{payment_id}/cancellations",
                {"amount": args.amount, "currency": "KRW", "reason": "timeline"},
                token,
                key,
            )
        else:
            post(
                base,
                "/api/v1/payments",
                {
                    "orderId": f"order-{key}",
                    "walletId": wallet_id,
                    "merchantId": "11111111-2222-3333-4444-555555555555",
                    "amount": args.amount,
                    "currency": "KRW",
                    "method": "PAY_MONEY",
                },
                token,
                key,
            )
        elapsed = (time.monotonic() - started) * 1000
        time.sleep(1)
        # postgres는 stderr로 기록하지만 이미지에 따라 다를 수 있어 둘 다 봅니다.
        captured = subprocess.run(["docker", "logs", "--since", "2m", CONTAINER], capture_output=True, text=True)
        log = captured.stderr + captured.stdout
    finally:
        psql("ALTER SYSTEM RESET log_min_duration_statement")
        psql("SELECT pg_reload_conf()")

    entries = parse_log(log)
    hits = [i for i, e in enumerate(entries) if BALANCE_CHANGE.search(e["sql"]) and e["kind"] == "execute"]
    if not hits:
        print("잔액 문장을 찾지 못했습니다. 앱이 이 postgres를 쓰고 있는지 확인하세요.", file=sys.stderr)
        return 1

    pid = entries[hits[-1]]["pid"]
    own = [e for e in entries if e["pid"] == pid and e["kind"] in ("execute", "statement")]
    debit = own.index(entries[hits[-1]])
    start = debit
    while start > 0 and not own[start]["sql"].strip().startswith("BEGIN"):
        start -= 1
    end = debit
    while end < len(own) - 1 and not own[end]["sql"].strip().startswith("COMMIT"):
        end += 1

    def at(item):
        return datetime.strptime(item["ts"], "%Y-%m-%d %H:%M:%S.%f")

    t0 = at(own[start])
    print(f"{args.operation} 응답 {elapsed:.0f} ms / 백엔드 pid {pid}")
    print(f"{'경과ms':>8} {'실행ms':>7}  문장")
    marked = None
    for item in own[start : end + 1]:
        offset = (at(item) - t0).total_seconds() * 1000
        sql = " ".join(item["sql"].split())[:82]
        note = ""
        if BALANCE_CHANGE.search(item["sql"]):
            marked = offset
            note = "   <<< 잔액 변경: 여기부터 지갑 행 잠금"
        if sql.startswith("COMMIT") and marked is not None:
            note = f"   <<< 커밋: 잠금 해제 (보유 {offset - marked:.0f} ms)"
        print(f"{offset:8.1f} {item['dur']:7.3f}  {sql}{note}")

    hold = (at(own[end]) - at(own[debit])).total_seconds() * 1000
    inside = sum(item["dur"] for item in own[debit : end + 1])
    total = (at(own[end]) - t0).total_seconds() * 1000
    print()
    print(f"트랜잭션 {total:.0f} ms, 문장 실행 시간 합계 {sum(i['dur'] for i in own[start:end + 1]):.2f} ms")
    print(f"잠금 보유 {hold:.0f} ms, 그 안에서 DB가 실제로 실행한 시간 {inside:.2f} ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""M-014: 장애 시뮬레이터의 시나리오 아홉 개를 전부 실행하고 결과를 표로 모읍니다.

운영 콘솔의 장애 시뮬레이터(`apps/web-ops/src/lab/scenarios.ts`)는 시나리오를 하나씩 적용하고
사람이 고객 앱에서 충전·결제를 눌러 불변조건 카드를 보게 되어 있습니다. 그 아홉 개를 **같은
순서, 같은 기관 모드**로 스크립트가 돌리고, 사람이 눈으로 보던 것을 수치로 남깁니다.

시나리오마다 재는 것:

- 첫 응답 — HTTP 상태와 그때의 업무 상태 (201이면 즉시 확정, 202면 미확정)
- 종결까지 시간 — 요청을 보낸 순간부터 조회 API가 종결 상태를 보일 때까지. 화면이 보는 것과 같음
- 종결 상태와 복구 시도 횟수 (`attemptCount`), 수동 검토 전환 여부
- **원장 전기 횟수** — 같은 참조의 POSTED 원장 거래 수. 성공이면 정확히 1, 실패면 0 (INV-004)
- **잔액 변화** — 조회 스냅샷의 전후 차이. 충전 성공이면 +금액, 실패·미확정이면 0
- 웹훅 영수증 수 — 중복·역순 시나리오에서 기관이 보낸 알림 수와 우리가 받아들인 수
- 불변조건 게이지 — 시나리오 전후 `paritypay_invariant_*`가 전부 0인지

원장 전기 횟수와 웹훅 영수증은 API가 없으므로 DB를 **읽기만** 합니다(`docker exec psql`). 잔액은
API로 봅니다 — 스냅샷과 원장이 다르면 그것도 결과입니다.

사전 조건: `scripts/dev.sh`로 스택이 떠 있어야 합니다 (pay-api·mock-bank·mock-pg·postgres).

사용:
    python3 load-tests/scenario-matrix.py --runs 3 --out /tmp/scenario-matrix

근거: docs/15-ui-screen-plan.md §5, docs/09-consistency-recovery.md §9, ADR-007
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

OPERATOR = {"email": "ops-operator@paritypay.local", "password": "local-ops-password"}
PASSWORD = "scenario-matrix-password"
MERCHANT_ID = "3f1b7c64-9a2e-4c3d-8f11-5a7e2b9d4c60"
AMOUNT = 10_000

# apps/web-ops/src/lab/scenarios.ts와 같은 순서·같은 모드입니다. 다르면 이 실험은 시뮬레이터를
# 재는 것이 아닙니다.
SCENARIOS = [
    {"id": "normal", "label": "정상 처리", "target": "bank", "bankMode": "NORMAL",
     "expect": "SUCCEEDED", "note": "기준선"},
    {"id": "bank-explicit-failure", "label": "은행이 명시적으로 실패를 응답", "target": "bank",
     "bankMode": "EXPLICIT_FAILURE", "expect": "FAILED", "note": "결과를 아는 실패. 잔액 불변"},
    {"id": "bank-timeout-before", "label": "은행이 출금 전에 응답을 끊음", "target": "bank",
     "bankMode": "TIMEOUT_BEFORE_WITHDRAWAL", "expect": "FAILED", "note": "외부 기록 없음 → 연속 확인 뒤 FAILED"},
    {"id": "bank-timeout-after", "label": "은행이 출금 뒤에 응답을 끊음", "target": "bank",
     "bankMode": "TIMEOUT_AFTER_WITHDRAWAL", "expect": "SUCCEEDED", "note": "돈은 움직였고 응답만 유실 → 조회로 SUCCEEDED"},
    # 아래 둘은 웹훅을 끄고 잽니다. 시뮬레이터가 뜨는 로컬 스택은 기관에 웹훅 주소가 등록되어 있지
    # 않아 알림이 오지 않으므로, 그 조건과 같게 두어야 "조회만으로 확정"이 측정됩니다.
    {"id": "pg-timeout-after", "label": "카드 PG가 승인 뒤에 응답을 끊음", "target": "pg",
     "pgMode": "TIMEOUT_AFTER_APPROVAL", "webhookMode": "NONE",
     "expect": "APPROVED", "note": "UNKNOWN으로 보존 → 조회로 APPROVED"},
    {"id": "pg-query-down", "label": "카드 PG 상태 조회 장애", "target": "pg",
     "pgMode": "TIMEOUT_AFTER_APPROVAL", "webhookMode": "NONE", "statusQueryAvailable": False, "holdSeconds": 60,
     "expect": "APPROVED", "note": "조회 장애 동안 아무것도 단정하지 않음. 조회 복구 뒤 확정"},
    {"id": "webhook-duplicate", "label": "웹훅 중복 전달", "target": "pg", "webhookMode": "DUPLICATE",
     "expect": "APPROVED", "note": "같은 알림 두 번 → 영수증이 두 번째를 막음, 원장 1회"},
    # 역순은 같은 키의 **두 번째** 알림이 있어야 재현됩니다(WebhookSender: sequence > 1). 승인 한 건은
    # 알림이 하나뿐이라 시뮬레이터에서는 아무 일도 일어나지 않습니다. 기관이 같은 승인을 다시
    # 알리는 상황(재승인 호출)을 기관 API로 직접 만들어 순번 2 뒤에 순번 1이 오게 합니다.
    {"id": "webhook-out-of-order", "label": "웹훅 역순 도착", "target": "pg", "webhookMode": "OUT_OF_ORDER",
     "renotify": True, "expect": "APPROVED", "note": "오래된 알림이 나중에 → STALE로 버리고 상태 회귀 없음"},
    {"id": "webhook-none", "label": "웹훅을 아예 보내지 않음", "target": "pg", "webhookMode": "NONE",
     "expect": "APPROVED", "note": "알림 없이 조회만으로 확정"},
]


# ---------- HTTP ----------

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
        try:
            return e.code, (json.loads(raw) if raw else {})
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def text(base, path):
    with urllib.request.urlopen(base + path, timeout=30) as response:
        return response.read().decode()


# ---------- 준비 ----------

def operator_token(base):
    status, body = call(base, "POST", "/api/v1/auth/tokens", OPERATOR)
    if status != 200:
        raise RuntimeError(f"운영자 로그인 실패 HTTP {status}: {body}")
    return body["accessToken"]


def provision(base, tag):
    email = f"matrix-{tag}-{int(time.time() * 1000)}@example.com"
    _, member = call(base, "POST", "/api/v1/members", {"email": email, "password": PASSWORD})
    _, token = call(base, "POST", "/api/v1/auth/tokens", {"email": email, "password": PASSWORD})
    access = token["accessToken"]
    _, account = call(
        base, "POST", "/api/v1/bank-accounts",
        {"bankCode": "004", "accountNumber": f"110{int(time.time() * 1000) % 10**12:012d}",
         "initialBalance": 100_000_000},
        token=access,
    )
    return member["walletId"], account["bankAccountId"], access


def set_bank_mode(base, ops, mode):
    status, body = call(base, "POST", "/api/v1/admin/mock-bank/mode", {"mode": mode}, token=ops)
    if status >= 400:
        raise RuntimeError(f"은행 모드 {mode} 실패 HTTP {status}: {body}")


def set_pg(base, ops, mode=None, webhook_mode=None, status_query=None):
    body = {}
    if mode is not None:
        body["mode"] = mode
    if webhook_mode is not None:
        body["webhookMode"] = webhook_mode
    if status_query is not None:
        body["statusQueryAvailable"] = status_query
    status, resp = call(base, "POST", "/api/v1/admin/mock-pg/mode", body, token=ops)
    if status >= 400:
        raise RuntimeError(f"PG 모드 {body} 실패 HTTP {status}: {resp}")


def reset_institutions(base, ops):
    set_bank_mode(base, ops, "NORMAL")
    set_pg(base, ops, mode="NORMAL", webhook_mode="NORMAL", status_query=True)


def register_webhook_url(pg_base, url):
    """기관에 우리 웹훅 주소를 알려 줍니다. 로컬 스택은 기본값이 비어 있어 알림이 오지 않습니다."""
    status, body = call(pg_base, "POST", "/mock-pg/admin/behavior", {"webhookUrl": url})
    if status >= 400:
        raise RuntimeError(f"웹훅 주소 등록 실패 HTTP {status}: {body}")


# ---------- 관찰 ----------

def psql(container, sql):
    out = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "paritypay", "-d", "paritypay", "-tA", "-q", "-c", sql],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    return out


def posted_ledger_count(container, reference_id):
    return int(psql(container,
                    f"SELECT count(*) FROM ledger_transaction WHERE reference_id = '{reference_id}' AND status = 'POSTED'"))


def webhook_receipts(container, payment_id):
    """이 결제에 대해 **받아들인** 웹훅 수와 커서 순번입니다. 외부 키는 paymentId 그대로입니다
    (`MockPgApprovalAdapter`). 중복은 영수증 유니크가 막으므로 받아들인 수가 보낸 수보다 작습니다."""
    try:
        receipts = int(psql(container,
                            f"SELECT count(*) FROM webhook_receipt WHERE external_key = '{payment_id}'"))
        cursor = psql(container,
                      f"SELECT sequence_no FROM webhook_cursor WHERE external_key = '{payment_id}'")
        return {"accepted": receipts, "cursor": int(cursor) if cursor else None}
    except (subprocess.CalledProcessError, ValueError):
        return None


def recovery_row(container, kind, ref_id):
    """복구 작업이 남긴 시도 횟수와 수동 검토 표시입니다. 확정 뒤에도 표에 남습니다."""
    table, column = ("top_up_recovery", "top_up_id") if kind == "top-up" else ("payment_recovery", "payment_id")
    try:
        row = psql(container,
                   f"SELECT attempt_count, not_found_count, requires_manual_review FROM {table} WHERE {column} = '{ref_id}'")
    except subprocess.CalledProcessError:
        return {}
    if not row:
        return {}
    attempts, not_found, manual = row.split("|")
    return {"attempts": int(attempts), "notFound": int(not_found), "manualReview": manual == "t"}


def webhook_log_counts(api_log, payment_id):
    """pay-api 로그에서 이 키의 웹훅 처리 결과를 셉니다. 중복은 DEBUG, 역순(stale)은 INFO로 남습니다."""
    try:
        lines = Path(api_log).read_text(errors="ignore").splitlines()
    except OSError:
        return None
    mine = [l for l in lines if payment_id in l and "webhook" in l]
    return {
        "duplicate": sum("duplicate webhook" in l for l in mine),
        "stale": sum("stale webhook" in l for l in mine),
    }


def invariant_gauges(base):
    values = {}
    for line in text(base, "/actuator/prometheus").splitlines():
        if line.startswith("paritypay_invariant_") and "refresh" not in line:
            name, _, value = line.rpartition(" ")
            values[name.split("{")[0]] = float(value)
    return values


def wallet_available(base, access):
    _, view = call(base, "GET", "/api/v1/wallets/me", token=access)
    return view.get("available")


# ---------- 실행 ----------

def run_top_up(base, ops, container, scenario, index, poll, deadline):
    wallet_id, bank_account_id, access = provision(base, f"{scenario['id']}-{index}")
    before = wallet_available(base, access)
    set_bank_mode(base, ops, scenario["bankMode"])
    started = time.monotonic()
    key = f"matrix-{scenario['id']}-{index}-{int(started * 1000)}"
    status, body = call(
        base, "POST", "/api/v1/top-ups",
        {"walletId": wallet_id, "bankAccountId": bank_account_id, "amount": AMOUNT, "currency": "KRW"},
        token=access, idempotency_key=key,
    )
    first_status = body.get("status")
    # 조회는 정상이어야 복구가 확정할 수 있습니다. 시뮬레이터도 사람이 다음 시나리오를 고르며 되돌립니다.
    set_bank_mode(base, ops, "NORMAL")
    top_up_id = body.get("topUpId")
    terminal, seconds, last = None, None, {}
    if top_up_id:
        while time.monotonic() - started < deadline:
            _, last = call(base, "GET", f"/api/v1/top-ups/{top_up_id}", token=access)
            if last.get("status") in ("SUCCEEDED", "FAILED"):
                terminal = last["status"]
                seconds = time.monotonic() - started
                break
            time.sleep(poll)
    time.sleep(1.0)
    after = wallet_available(base, access)
    recovery = recovery_row(container, "top-up", top_up_id) if top_up_id else {}
    return {
        "scenario": scenario["id"], "index": index, "kind": "top-up",
        "httpStatus": status, "firstStatus": first_status, "terminal": terminal or last.get("status"),
        "seconds": seconds, "attempts": recovery.get("attempts"), "notFound": recovery.get("notFound"),
        "manualReview": recovery.get("manualReview"),
        "ledgerPosted": posted_ledger_count(container, top_up_id) if top_up_id else None,
        "balanceDelta": (after - before) if (before is not None and after is not None) else None,
        "referenceId": top_up_id,
    }


def renotify(pg_base, payment_id, order_id):
    """기관에 같은 승인을 다시 요청해 같은 키의 두 번째 알림을 만듭니다. 장부는 기관 것입니다."""
    status, body = call(pg_base, "POST", "/mock-pg/approvals",
                        {"externalKey": payment_id, "merchantId": MERCHANT_ID, "orderId": order_id, "amount": AMOUNT})
    return status


def run_payment(base, ops, container, scenario, index, poll, deadline, api_log=None, pg_base=None):
    wallet_id, _, access = provision(base, f"{scenario['id']}-{index}")
    before = wallet_available(base, access)
    set_pg(base, ops,
           mode=scenario.get("pgMode", "NORMAL"),
           webhook_mode=scenario.get("webhookMode", "NORMAL"),
           status_query=scenario.get("statusQueryAvailable", True))
    started = time.monotonic()
    key = f"matrix-{scenario['id']}-{index}-{int(started * 1000)}"
    order_id = f"order-{key}"
    status, body = call(
        base, "POST", "/api/v1/payments",
        {"orderId": order_id, "walletId": wallet_id, "merchantId": MERCHANT_ID,
         "amount": AMOUNT, "currency": "KRW", "method": "EXTERNAL_PG"},
        token=access, idempotency_key=key,
    )
    first_status = body.get("status")
    payment_id = body.get("paymentId")

    hold = scenario.get("holdSeconds", 0)
    held_observation = None
    if hold:
        # 조회 장애를 유지하는 동안 무엇이 단정되는지 봅니다. 아무것도 바뀌지 않아야 합니다.
        time.sleep(hold)
        _, during = call(base, "GET", f"/api/v1/payments/{payment_id}", token=access)
        held_observation = {
            "statusAfterHold": during.get("status"),
            "attemptsDuringHold": during.get("attemptCount"),
            "ledgerPostedDuringHold": posted_ledger_count(container, payment_id) if payment_id else None,
        }
    # 승인 모드는 즉시 정상으로 되돌립니다. 조회 가용성은 hold가 끝난 뒤 되돌립니다.
    set_pg(base, ops, mode="NORMAL", status_query=True)

    terminal, seconds, last = None, None, {}
    if payment_id:
        while time.monotonic() - started < deadline:
            _, last = call(base, "GET", f"/api/v1/payments/{payment_id}", token=access)
            if last.get("status") in ("APPROVED", "FAILED", "CANCELED"):
                terminal = last["status"]
                seconds = time.monotonic() - started
                break
            time.sleep(poll)
    renotified = None
    if scenario.get("renotify") and terminal == "APPROVED" and pg_base:
        time.sleep(2.0)
        renotified = renotify(pg_base, payment_id, order_id)
        time.sleep(3.0)
        _, last = call(base, "GET", f"/api/v1/payments/{payment_id}", token=access)
        terminal = last.get("status")
    # 웹훅은 승인 뒤에 비동기로 옵니다. 중복·역순이 도착할 시간을 줍니다.
    time.sleep(3.0)
    set_pg(base, ops, webhook_mode="NORMAL")
    after = wallet_available(base, access)
    recovery = recovery_row(container, "payment", payment_id) if payment_id else {}
    result = {
        "scenario": scenario["id"], "index": index, "kind": "payment",
        "httpStatus": status, "firstStatus": first_status, "terminal": terminal or last.get("status"),
        "seconds": seconds, "attempts": recovery.get("attempts"), "notFound": recovery.get("notFound"),
        "manualReview": recovery.get("manualReview"),
        "ledgerPosted": posted_ledger_count(container, payment_id) if payment_id else None,
        "balanceDelta": (after - before) if (before is not None and after is not None) else None,
        "webhookReceipts": webhook_receipts(container, payment_id) if payment_id else None,
        "webhookLog": webhook_log_counts(api_log, payment_id) if (api_log and payment_id) else None,
        "referenceId": payment_id,
    }
    if renotified is not None:
        result["renotifyHttp"] = renotified
    if held_observation:
        result["held"] = held_observation
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--pg-base", default="http://localhost:8091")
    parser.add_argument("--webhook-url", default="http://host.docker.internal:8080/api/v1/webhooks/mock-pg",
                        help="기관에 등록할 우리 웹훅 주소. 빈 문자열이면 등록하지 않고 스택의 설정을 그대로 씁니다")
    parser.add_argument("--container", default="paritypay-postgres")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--only", help="쉼표로 구분한 시나리오 id")
    parser.add_argument("--poll", type=float, default=1.0)
    parser.add_argument("--deadline", type=float, default=180.0)
    parser.add_argument("--api-log", default="/tmp/paritypay-dev/api.log")
    parser.add_argument("--out", default="/tmp/scenario-matrix")
    args = parser.parse_args()

    out = Path(args.out) / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out.mkdir(parents=True, exist_ok=True)
    ops = operator_token(args.base)
    if args.webhook_url:
        register_webhook_url(args.pg_base, args.webhook_url)
    reset_institutions(args.base, ops)

    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    selected = [s for s in SCENARIOS if not args.only or s["id"] in args.only.split(",")]
    gauges_before = invariant_gauges(args.base)
    print(f"== {len(selected)}개 시나리오 × {args.runs}회, 커밋 {commit}, 결과 {out}", flush=True)
    print(f"   불변조건 게이지(시작): {gauges_before}", flush=True)

    results = []
    per_scenario = {}
    for scenario in selected:
        print(f"\n## {scenario['label']} ({scenario['id']})", flush=True)
        rows = []
        for index in range(args.runs):
            if scenario["target"] == "bank":
                row = run_top_up(args.base, ops, args.container, scenario, index, args.poll, args.deadline)
            else:
                row = run_payment(args.base, ops, args.container, scenario, index, args.poll, args.deadline,
                                  api_log=args.api_log, pg_base=args.pg_base)
            rows.append(row)
            results.append(row)
            sec = "-" if row["seconds"] is None else f"{row['seconds']:.1f}s"
            print(f"   run {index}: HTTP {row['httpStatus']} {row['firstStatus']} → {row['terminal']} in {sec}, "
                  f"attempts={row['attempts']}, ledger={row['ledgerPosted']}, Δbalance={row['balanceDelta']}, "
                  f"webhooks={row.get('webhookReceipts')} log={row.get('webhookLog')} "
                  f"renotify={row.get('renotifyHttp')}", flush=True)
            reset_institutions(args.base, ops)
            (out / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
        gauges = invariant_gauges(args.base)
        per_scenario[scenario["id"]] = {"rows": rows, "gaugesAfter": gauges}
        print(f"   불변조건 게이지: {gauges}", flush=True)

    summary = {
        "commit": commit,
        "startedAt": out.name,
        "runs": args.runs,
        "gaugesBefore": gauges_before,
        "scenarios": [{"id": s["id"], "label": s["label"], "expect": s["expect"], "note": s["note"],
                       "gaugesAfter": per_scenario[s["id"]]["gaugesAfter"]} for s in selected],
        "results": results,
    }
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\n== 끝. {out}/summary.json", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""M-012: 미확정 거래가 한꺼번에 수백 건 쌓였을 때 확정까지 걸리는 시간.

M-011은 한가한 시스템에서 **한 건씩** 쟀고, 결과는 분포가 아니라 설정이 정하는 상수였습니다
(grace 30s + 주기 한 틱). 그 보고서가 남긴 미측정 항목이 이것입니다:

    복구 배치가 `batch-size: 50`이므로 미확정이 수백 건 쌓이면 뒤쪽 건은 더 기다릴 수 있습니다.

프론트엔드는 이 값 위에 서 있습니다. DOC-14 FE-003은 2초 간격으로 **90초까지** 폴링하고 그 뒤에는
"아직 확인 중"으로 남깁니다. 복구 작업은 5초마다 50건을 **순서대로** 조회하므로, 산술로는 N건이
동시에 쌓이면 마지막 건은 30 + 5·⌈N/50⌉초 근처에 확정됩니다 — N=500이면 80초, N=1000이면 130초.
산술이 아니라 측정으로 답합니다. 산술은 조회 한 건의 시간, 틱 안의 순서, 주입이 퍼지는 폭을 모릅니다.

**무엇을 재는가:** 각 건이 **자기 요청을 보낸 순간**부터 `GET`이 종결 상태를 보일 때까지입니다.
M-011과 같은 기준입니다. 폴링 간격은 클라이언트가 실제로 쓰는 2초입니다 — N건이 동시에 폴링하는
부하 자체도 조건의 일부이기 때문입니다.

**주입은 완전히 동시가 아닙니다.** 은행이 응답을 붙잡고 있는 동안(read-timeout) 요청 하나가 그만큼
차지하므로, 워커 수로 나눈 만큼 시간이 퍼집니다. 그 폭을 함께 기록합니다. 실제 장애도 그렇습니다 —
수백 건이 한 순간에 생기지 않고 몇 초에 걸쳐 쌓입니다.

사전 조건: docker compose로 postgres·redpanda·mock-bank·mock-pg가 떠 있어야 하고, bootJar가 필요합니다.

사용:
    ./gradlew :apps:pay-api:bootJar
    python3 load-tests/recovery-backlog-experiment.py \\
        --jar apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar --sizes 50,200,500,1000

근거: reports/11 M-011·M-012, docs/14-frontend-design.md §3 FE-003·§13 열린 질문 3
"""

import argparse
import importlib.util
import json
import os
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# M-011 스크립트의 도우미를 그대로 씁니다. 복사하면 둘이 어긋납니다.
_spec = importlib.util.spec_from_file_location(
    "recovery_latency", Path(__file__).with_name("recovery-latency-experiment.py")
)
_m011 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_m011)
call, start_app, provision, set_bank_mode, OPERATOR = (
    _m011.call,
    _m011.start_app,
    _m011.provision,
    _m011.set_bank_mode,
    _m011.OPERATOR,
)

# 클라이언트가 실제로 쓰는 값입니다(DOC-14 FE-003). 이 값을 넘긴 건이 곧 화면이 포기한 건입니다.
CLIENT_POLL_INTERVAL = 2.0
CLIENT_POLL_LIMIT = 90.0


def inject_one(base, session, offset):
    """미확정 충전 한 건을 만듭니다. 은행 모드는 호출자가 미리 맞춰 둡니다."""
    wallet_id, bank_account_id, access = session
    started = time.monotonic()
    status, body = call(
        base,
        "POST",
        "/api/v1/top-ups",
        {"walletId": wallet_id, "bankAccountId": bank_account_id, "amount": 10_000, "currency": "KRW"},
        token=access,
        idempotency_key=f"backlog-{offset}-{int(started * 1000)}",
    )
    return {
        "offset": offset,
        "started": started,
        "accepted_at": time.monotonic(),
        "http": status,
        "top_up_id": body.get("topUpId"),
        "access": access,
        "outcome": None,
        "seconds": None,
    }


def poll_until_settled(base, items, deadline_seconds, workers):
    """각 건을 2초 간격으로 조회합니다. 워커 하나가 여러 건을 돌아가며 봅니다."""
    lock = threading.Lock()
    pending = [item for item in items if item["http"] == 202]

    def worker(mine):
        while mine:
            for item in list(mine):
                elapsed = time.monotonic() - item["started"]
                _, view = call(base, "GET", f"/api/v1/top-ups/{item['top_up_id']}", token=item["access"])
                if view.get("status") in ("SUCCEEDED", "FAILED"):
                    with lock:
                        item["outcome"] = view["status"]
                        item["seconds"] = time.monotonic() - item["started"]
                    mine.remove(item)
                elif elapsed > deadline_seconds:
                    with lock:
                        item["outcome"] = "시간 초과"
                    mine.remove(item)
            time.sleep(CLIENT_POLL_INTERVAL)

    chunks = [pending[i::workers] for i in range(workers)]
    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(worker, [c for c in chunks if c]))


def run_size(base, operator_token, size, inject_workers, poll_workers, deadline):
    print(f"== N={size}: 회원 {size}명 준비", flush=True)
    sessions = [provision(base, size * 10_000 + i) for i in range(size)]

    set_bank_mode(base, operator_token, "TIMEOUT_AFTER_WITHDRAWAL")
    print(f"== N={size}: 주입 (워커 {inject_workers})", flush=True)
    injection_started = time.monotonic()
    with ThreadPoolExecutor(max_workers=inject_workers) as pool:
        items = list(pool.map(lambda pair: inject_one(base, pair[1], pair[0]), enumerate(sessions)))
    injection_window = time.monotonic() - injection_started
    # 조회는 정상이어야 복구가 확정할 수 있습니다. 마지막 응답을 받은 뒤 즉시 되돌립니다.
    set_bank_mode(base, operator_token, "NORMAL")

    accepted = sum(1 for i in items if i["http"] == 202)
    print(f"== N={size}: 202 {accepted}건, 주입 폭 {injection_window:.1f}초, 확정 대기", flush=True)
    poll_until_settled(base, items, deadline, poll_workers)

    for item in items:
        item.pop("access", None)
    return {"size": size, "injection_window": injection_window, "items": items}


def summarize(run):
    items = run["items"]
    done = sorted(i["seconds"] for i in items if i["seconds"] is not None)
    outcomes = {}
    for i in items:
        outcomes[i["outcome"] or f"HTTP {i['http']}"] = outcomes.get(i["outcome"] or f"HTTP {i['http']}", 0) + 1
    n = run["size"]
    lines = [f"===== N={n} =====", f"결과: {', '.join(f'{k} {v}건' for k, v in sorted(outcomes.items()))}"]
    lines.append(f"주입 폭: {run['injection_window']:.1f}초 (첫 요청부터 마지막 응답까지)")
    if not done:
        lines.append("확정된 건이 없어 분포를 낼 수 없습니다.")
        return "\n".join(lines), None
    over = sum(1 for s in done if s > CLIENT_POLL_LIMIT)
    stats = {
        "n": len(done),
        "min": done[0],
        "median": statistics.median(done),
        "p95": done[min(len(done) - 1, int(len(done) * 0.95))],
        "max": done[-1],
        "over_limit": over,
        # 산술 기대값: grace + 주기 × 필요한 틱 수. 실측과 나란히 두어 산술이 무엇을 놓쳤는지 봅니다.
        "arithmetic_last": 30 + 5 * -(-n // 50),
    }
    lines.append(
        f"확정까지 (초)  n={stats['n']}  최소 {stats['min']:.1f}  중앙값 {stats['median']:.1f}  "
        f"p95 {stats['p95']:.1f}  최대 {stats['max']:.1f}"
    )
    lines.append(
        f"클라이언트 한계 {CLIENT_POLL_LIMIT:.0f}초 초과: {over}건 ({over / len(done) * 100:.1f}%)  "
        f"— 산술로 예상한 마지막 건: {stats['arithmetic_last']}초"
    )
    # 5초 구간별 확정 건수. 틱이 보입니다.
    buckets = {}
    for s in done:
        b = int(s // 5) * 5
        buckets[b] = buckets.get(b, 0) + 1
    lines.append("5초 구간별 확정: " + ", ".join(f"{b}s {c}건" for b, c in sorted(buckets.items())))
    return "\n".join(lines), stats


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--java", default=os.environ.get("JAVA_BIN", "java"))
    parser.add_argument("--port", type=int, default=8093)
    parser.add_argument("--sizes", default="50,200,500")
    # 은행은 타임아웃된 요청을 read-timeout의 두 배 동안 스레드에 붙잡습니다(MockBankBehavior).
    # Tomcat 기본 200 스레드를 넘기면 뒤 요청은 처리되지 않고, 그러면 "응답만 유실"이 아니라
    # "기록 없음"이 되어 다른 경로를 재게 됩니다. 워커 50 × (읽기 타임아웃 0.5s, 붙잡기 1s)
    # = 동시 100건으로 그 아래에 둡니다.
    parser.add_argument("--inject-workers", type=int, default=50)
    parser.add_argument("--read-timeout", default="500ms",
                        help="pay-api의 은행 읽기 타임아웃. 시험 설정과 같은 값이며 복구 지연에는 영향이 없습니다")
    parser.add_argument("--poll-workers", type=int, default=50)
    parser.add_argument("--deadline", type=float, default=600.0, help="한 건을 포기하기까지의 초")
    parser.add_argument("--out", default="/tmp/m012")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    base = f"http://localhost:{args.port}"
    sizes = [int(s) for s in args.sizes.split(",")]

    # 환경변수가 local 프로필의 3s보다 우선합니다.
    os.environ["PARITYPAY_MOCKBANK_READTIMEOUT"] = args.read_timeout
    app = start_app(args.jar, args.port, out_dir / "app.log", args.java)
    try:
        _, operator = call(base, "POST", "/api/v1/auth/tokens", OPERATOR)
        operator_token = operator["accessToken"]

        report, raw = [], []
        for size in sizes:
            run = run_size(base, operator_token, size, args.inject_workers, args.poll_workers, args.deadline)
            text, stats = summarize(run)
            print(text, flush=True)
            report.append(text)
            raw.append({"size": size, "injection_window": run["injection_window"], "stats": stats,
                        "items": run["items"]})
            # 다음 크기가 이전 크기의 꼬리 위에서 시작하지 않게 합니다. 복구 큐가 비어야 합니다.
            time.sleep(10)

        (out_dir / "report.txt").write_text("\n\n".join(report) + "\n")
        (out_dir / "raw.json").write_text(json.dumps(raw, ensure_ascii=False, indent=1, default=str))
        print(f"\n원본 결과: {out_dir}/report.txt, {out_dir}/raw.json")
    finally:
        app.terminate()
        try:
            app.wait(timeout=30)
        except Exception:
            app.kill()


if __name__ == "__main__":
    sys.exit(main())

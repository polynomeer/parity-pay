#!/usr/bin/env python3
"""T11 — 동기화된 폭주에서 지터가 값을 하는가.

M-020은 재시도가 기관 요청을 정확히 3배로 만든다는 것을 쟀고, 지터가 봉우리를 낮추지 못했다는 결과로
끝났습니다. 그 실험의 부하는 초당 10건이 고르게 도착하는 것이어서 **펼칠 봉우리가 없었습니다**. 그리고
비교가 "즉시 재시도 vs 지수+지터"였으므로 백오프를 넣은 효과와 지터를 넣은 효과가 한 칸에 섞여 있었습니다.

이 실험은 둘 다 고칩니다.

1. 봉우리를 만듭니다. K개 요청을 같은 순간에 쏘고, 기관은 타임아웃보다 오래 붙잡습니다. 그러면 K개가
   **같은 순간에 타임아웃되고**, 재시도도 같은 순간에 나갑니다. 클라이언트 타임아웃이 동기화 장치입니다.
2. 대조군을 지수 백오프로 둡니다. `EXPONENTIAL`과 `EXPONENTIAL_JITTER`는 일정이 같고 지터만 다르므로,
   둘의 차이가 지터의 값입니다.

판정은 총량이 아니라 **봉우리**입니다. 지터는 기관이 받는 요청 수를 줄이지 않습니다. 그것을 줄이는 것은
재시도를 하지 않는 것뿐입니다.

실행:
    python3 load-tests/retry-stampede-experiment.py --jar <pay-api jar> --runs 3
"""

import argparse
import importlib.util
import json
import os
import statistics
import sys
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("ext_iso", HERE / "external-isolation-experiment.py")
ext = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ext)

# external-isolation-experiment.py 안에서는 main()의 지역 변수라 가져올 수 없습니다. 격리 장치를 끄는
# 이유는 M-020과 같습니다. 벌크헤드나 차단기가 켜져 있으면 폭주가 기관에 닿기 전에 잘리고, 그러면 재시도가
# 기관에 무엇을 하는지가 아니라 격리 장치가 무엇을 하는지를 재게 됩니다.
NO_ISOLATION = {"EXPERIMENT_BULKHEAD": "false", "EXPERIMENT_CIRCUIT": "false"}

# 기관이 붙잡는 시간은 클라이언트 타임아웃(3초)보다 길기만 하면 됩니다. 처음에 20초로 뒀다가 기관
# 컨테이너가 OOM으로 죽었습니다(Exited 137): 붙잡힌 요청 하나가 서블릿 스레드 하나를 그 시간 내내
# 쥐는데, 재시도로 물결이 셋이라 20초 창에서는 세 물결이 겹쳐 450개가 동시에 살아 있었고, 변형을
# 넘어가며 쌓였습니다. 8초면 물결이 최대 둘만 겹칩니다.
PG_HANG_MS = 8000

VARIANTS = {
    "no-retry": {"EXPERIMENT_RETRY": "false"},
    "retry-3-exponential": {"EXPERIMENT_RETRY": "true", "EXPERIMENT_RETRY_ATTEMPTS": "3",
                            "EXPERIMENT_RETRY_BACKOFF": "EXPONENTIAL"},
    "retry-3-jitter": {"EXPERIMENT_RETRY": "true", "EXPERIMENT_RETRY_ATTEMPTS": "3",
                       "EXPERIMENT_RETRY_BACKOFF": "EXPONENTIAL_JITTER"},
}


def post_payment(base, token, idempotency_key, body, timeout=180):
    """결제는 Idempotency-Key를 요구하고 공용 call()은 추가 헤더를 받지 않으므로 여기서만 직접 보냅니다."""
    request = urllib.request.Request(
        base + "/api/v1/payments",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}",
                 "Idempotency-Key": idempotency_key},
        method="POST")
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


def make_accounts(base, count):
    """폭주에 참여할 계정을 미리 만듭니다. 폭주 시점에 회원가입을 섞으면 그것도 같이 몰립니다."""
    accounts = []
    for _ in range(count):
        email = f"stampede-{uuid.uuid4()}@example.com"
        _, member = ext.call(base, "POST", "/api/v1/members",
                             {"email": email, "password": "probe-password-1"})
        _, token = ext.call(base, "POST", "/api/v1/auth/tokens",
                            {"email": email, "password": "probe-password-1"})
        accounts.append({"walletId": member["walletId"], "token": token["accessToken"]})
    return accounts


def stampede(base, accounts, merchant_id):
    """모든 요청을 같은 순간에 출발시킵니다.

    Barrier 대신 Event를 씁니다. 한 스레드가 예외로 죽으면 Barrier는 나머지를 깨뜨리고, 그때 남는 것은
    '일부만 출발한 폭주'입니다 — data-ops-lab T1에서 같은 실수를 한 적이 있어 ADR로 남겼습니다.
    """
    go = threading.Event()
    results = [None] * len(accounts)

    def one(index, account):
        key = f"stampede-{uuid.uuid4()}"
        body = {"orderId": f"order-{key}", "walletId": account["walletId"],
                "merchantId": merchant_id, "amount": 10000, "currency": "KRW",
                "method": "EXTERNAL_PG"}
        go.wait()
        started = time.monotonic()
        try:
            status, payload = post_payment(base, account["token"], key, body)
            outcome = f"{status}_{payload.get('status') if isinstance(payload, dict) else ''}"
        except Exception as exc:  # noqa: BLE001 - the outcome is data, not a failure of the run
            status, outcome = None, f"error:{type(exc).__name__}"
        results[index] = {"status": status, "outcome": outcome,
                          "elapsedMs": round((time.monotonic() - started) * 1000, 1)}

    threads = [threading.Thread(target=one, args=(i, a), daemon=True)
               for i, a in enumerate(accounts)]
    for t in threads:
        t.start()
    time.sleep(1.0)  # 스레드가 전부 go.wait()에 도달할 시간
    fired_at = time.time()
    go.set()
    for t in threads:
        t.join(timeout=240)
    return fired_at, results


def histogram(stamps, origin, bucket_ms=100, span_s=30):
    """기관 도착을 100 ms 칸으로 셉니다. 봉우리는 이 칸에서 읽습니다."""
    buckets = {}
    for s in stamps:
        offset = s - origin
        if 0 <= offset <= span_s:
            buckets[int(offset * 1000 // bucket_ms)] = buckets.get(int(offset * 1000 // bucket_ms), 0) + 1
    return buckets


def restart_institution(args):
    """변형마다 기관을 새로 띄웁니다.

    붙잡힌 요청이 쥐고 있던 스레드는 응답이 끝나야 풀리고, 그 잔재가 다음 변형으로 넘어갑니다. 한 번
    OOM으로 죽은 뒤로는 변형 사이에 재시작해 같은 상태에서 시작하게 합니다. 기관이 죽은 채로 도는
    실행은 '기관 요청 0건'으로 조용히 성공하므로, 여기서 떠 있는 것을 확인하고 넘어갑니다.
    """
    subprocess.run(["docker", "compose", "restart", "mock-pg"], cwd=str(HERE.parent),
                   capture_output=True, check=False)
    for _ in range(60):
        try:
            ext.call(args.pg_base, "GET", "/mock-pg/admin/approvals/count", timeout=3)
            return True
        except Exception:  # noqa: BLE001
            time.sleep(1)
    raise RuntimeError("기관이 다시 뜨지 않았습니다. 이 실행은 비교할 수 없습니다.")


def run_variant(args, variant, env, run_index):
    restart_institution(args)
    log_path = os.path.join(args.log_dir, f"t11-{variant}-{run_index}.log")
    app = ext.App(args.jar, args.port, args.db_url, args.kafka, log_path,
                  {**NO_ISOLATION, **env})
    base = f"http://localhost:{args.port}"
    ext.pg_reset(args.pg_base)
    try:
        app.start()
        accounts = make_accounts(base, args.clients)
        since = ext.now_iso()
        ext.pg_behavior(args.pg_base, approvalMode="HANG_BEFORE_PROCESSING", hangForMillis=PG_HANG_MS)
        approvals_before = ext.pg_approval_count(args.pg_base)

        fired_at, outcomes = stampede(base, accounts, args.merchant_id)

        # 마지막 재시도가 기관에 닿을 시간을 줍니다. 3회 시도 × (3초 타임아웃 + 최대 2초 백오프).
        time.sleep(args.settle)
        stamps = ext.pg_request_timestamps(args.pg_container, since)
        approvals_after = ext.pg_approval_count(args.pg_base)
        buckets = histogram(stamps, fired_at)
        # 첫 물결은 클라이언트가 만든 것이고 모든 조건에서 같습니다. 지터가 무엇을 하는지는 그 뒤에서만
        # 읽을 수 있으므로, 재시도 구간의 봉우리를 따로 냅니다.
        retry_window = [s for s in stamps if s - fired_at > args.first_wave_s]
        return {
            "variant": variant, "run": run_index, "since": since,
            "clients": args.clients, "pgHangMs": PG_HANG_MS, "env": env,
            "firedAt": fired_at,
            "pgRequestsSeen": len(stamps),
            "pgApprovals": approvals_after - approvals_before,
            "multiplier": round(len(stamps) / args.clients, 2) if args.clients else None,
            "peakPer100ms": ext.max_in_window(stamps, 0.1) if stamps else 0,
            "peakPer1s": ext.max_in_window(stamps, 1.0) if stamps else 0,
            "retryPeakPer100ms": ext.max_in_window(retry_window, 0.1) if retry_window else 0,
            "retryPeakPer1s": ext.max_in_window(retry_window, 1.0) if retry_window else 0,
            "retryWindowRequests": len(retry_window),
            "histogram100ms": {str(k): v for k, v in sorted(buckets.items())},
            "clientElapsedP50Ms": round(statistics.median(
                [o["elapsedMs"] for o in outcomes if o]), 1) if outcomes else None,
            "clientElapsedMaxMs": max((o["elapsedMs"] for o in outcomes if o), default=None),
            "outcomes": _tally(outcomes),
            # 기관이 한 건도 못 봤다면 폭주가 기관에 닿지 않은 것이고, 그 실행은 비교할 수 없습니다.
            # 첫 시도에서 merchantId가 UUID가 아니라 모든 결제가 500으로 끝났는데, 스크립트는 종료 코드
            # 0으로 "기관 요청 0건, 배수 0.0"을 적고 끝났습니다. 0은 결과처럼 생겼습니다.
            "valid": len(stamps) > 0 and sum(
                1 for k, v in _tally(outcomes).items() if k.startswith(("202", "201"))) > 0,
        }
    finally:
        # 순서를 뒤집고 서로 감쌉니다. 원래는 기관 설정 되돌리기가 먼저였는데, 기관이 죽어 있으면 그
        # 호출이 예외를 내고 app.stop()이 실행되지 않습니다. 그러면 pay-api가 포트를 쥔 채 남고 다음
        # 실행이 "Port 18081 was already in use"로 죽습니다 — 실제로 그렇게 됐습니다.
        try:
            ext.pg_behavior(args.pg_base, reset=True)
        except Exception as exc:  # noqa: BLE001
            print(f"  (기관 설정 복구 실패: {exc})", flush=True)
        app.stop()


def _tally(outcomes):
    counts = {}
    for o in outcomes:
        if o:
            counts[o["outcome"]] = counts.get(o["outcome"], 0) + 1
    return counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True)
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--db-url", default="jdbc:postgresql://localhost:15432/paritypay")
    parser.add_argument("--kafka", default="localhost:19092")
    parser.add_argument("--pg-base", default="http://localhost:8091")
    parser.add_argument("--pg-container", default="paritypay-mock-pg")
    parser.add_argument("--db-container", default="paritypay-postgres")
    # k6 스크립트가 쓰는 것과 같은 가맹점입니다. 이 필드는 UUID라 임의의 문자열을 넣으면 500이 나고,
    # 그러면 기관에 요청이 0건인 채로 실험이 "성공"합니다.
    parser.add_argument("--merchant-id", default="3f1b7c64-9a2e-4c3d-8f11-5a7e2b9d4c60")
    parser.add_argument("--clients", type=int, default=200)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--settle", type=float, default=25.0)
    parser.add_argument("--first-wave-s", type=float, default=1.0,
                        help="이 시각 이후의 기관 도착만 재시도로 셉니다")
    # 3.5초로 뒀을 때 지터 쪽만 재시도가 80건이 아니라 47건으로 세어졌습니다. 지터의 백오프는
    # [0, 0.5s]라 첫 재시도가 3.0~3.5초에 걸쳐 도착하는데, 그중 경계 앞의 것이 첫 물결로 분류된
    # 것입니다. 경계를 줄이는 쪽이 옳습니다 — 첫 물결은 발사 직후 0.2초 안에 다 도착하므로,
    # 1초 뒤의 도착은 전부 재시도입니다. 두 조건 모두 재시도가 80건으로 세어져야 맞습니다.
    parser.add_argument("--log-dir", default="/tmp/t11-retry-stampede")
    parser.add_argument("--out", default="/tmp/t11-retry-stampede/results.json")
    args = parser.parse_args()

    os.makedirs(args.log_dir, exist_ok=True)
    out = Path(args.out)
    results = []
    # A-B-A-B. 한 조건을 연속으로 돌리면 기계 상태가 그 조건에 쌓입니다.
    for run_index in range(1, args.runs + 1):
        for variant, env in VARIANTS.items():
            print(f"== t11 {variant} run{run_index}", flush=True)
            result = run_variant(args, variant, env, run_index)
            results.append(result)
            out.write_text(json.dumps(results, ensure_ascii=False, indent=1))
            if not result["valid"]:
                print(f"  !! 무효한 실행: 기관 요청 {result['pgRequestsSeen']}건, "
                      f"결과 {result['outcomes']}. 폭주가 기관에 닿지 않았습니다.", flush=True)
            brief = {k: v for k, v in result.items() if k not in ("histogram100ms", "env")}
            print("  " + json.dumps(brief, ensure_ascii=False)[:900], flush=True)
    print(f"== 끝. {out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""T12 — APM 에이전트를 켜면 처리량과 지연이 얼마나 달라지는가.

도구를 고르는 자리에서 "오버헤드는 1% 수준"이라는 문장은 벤더 문서에 늘 있습니다. 이 실험은 그
문장을 우리 부하에서 확인합니다. 비교 대상은 **에이전트의 종류**이고, 바뀌지 않는 것은 애플리케이션,
부하, 기관 대역, 데이터베이스입니다 (ADR-017이 그 조건을 만듭니다).

    none        에이전트 없음 — 대조군
    jaeger      OTel 자동계측 에이전트 → 컬렉터 → Jaeger
    tempo       같은 에이전트, 백엔드만 Tempo (같은 값이 나와야 맞습니다 — 에이전트가 같으므로)
    skywalking  SkyWalking 자체 에이전트 → OAP
    pinpoint    Pinpoint 자체 에이전트 → Collector

**앱은 이 스크립트가 띄웁니다.** dev.sh 의 인스턴스가 살아 있으면 같은 기계에서 CPU를 나눠 쓰고,
같은 토픽을 소비해 측정이 오염됩니다 — 먼저 내리십시오.

판정은 처리량(승인/초)과 승인 지연 p50·p95 입니다. 한 팔(arm)당 여러 번 돌려 중앙값을 씁니다.
부하는 P-001 과 같은 조건(VU 20, 서로 다른 지갑)이라 숫자를 reports/11 P-001 과 나란히 둘 수 있습니다.

사전 조건: docker compose up -d postgres redpanda mock-bank mock-pg, bootJar, 호스트에 k6

사용:
    J=apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar
    python3 load-tests/apm-overhead-experiment.py --jar $J --arms none,jaeger,skywalking --runs 3

근거: ADR-017, reports/14
"""

import argparse
import json
import os
import shutil
import statistics
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent


def read_apm_env():
    """scripts/apm.sh 가 적어 둔 설정입니다. 이 스크립트는 도구 이름을 해석하지 않습니다."""
    path = ROOT / ".apm" / "env"
    env = {}
    if not path.exists():
        return env
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        env[key] = value
    return env


def switch_apm(arm):
    subprocess.run([str(ROOT / "scripts" / "apm.sh"), "up", arm], cwd=ROOT, check=True)
    # 백엔드가 수신을 시작하기 전에 부하를 걸면 에이전트가 재시도 큐에 쌓으며 다른 일을 합니다.
    time.sleep(10)


def start_app(jar, port, log_path, extra_env):
    env = dict(os.environ)
    env.update({
        "SPRING_PROFILES_ACTIVE": "local",
        "PARITYPAY_PORT": str(port),
        "PARITYPAY_DB_URL": env.get("PARITYPAY_DB_URL", "jdbc:postgresql://localhost:5435/paritypay"),
        "PARITYPAY_KAFKA_SERVERS": env.get("PARITYPAY_KAFKA_SERVERS", "localhost:9092"),
        "PARITYPAY_TRACE_SAMPLING": "0",
    })
    env.update(extra_env)
    log = open(log_path, "w")
    process = subprocess.Popen([os.environ.get("JAVA", "java"), "-jar", jar],
                               stdout=log, stderr=log, env=env, cwd=ROOT)
    deadline = time.time() + 240
    while time.time() < deadline:
        if "Started ParityPayApplication" in Path(log_path).read_text(errors="ignore"):
            return process
        if process.poll() is not None:
            raise RuntimeError(f"pay-api 가 뜨지 못했습니다: {log_path}")
        time.sleep(1)
    process.kill()
    raise RuntimeError("pay-api 가 제 시간에 뜨지 않았습니다")


def stop_app(process):
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            process.kill()


def run_k6(port, out_json, vus):
    env = dict(os.environ)
    env.update({"BASE_URL": f"http://localhost:{port}", "VUS": str(vus)})
    subprocess.run(["k6", "run", "--quiet", "--summary-export", str(out_json),
                    str(HERE / "payment-baseline.js")], env=env, check=True,
                   stdout=subprocess.DEVNULL)
    summary = json.loads(Path(out_json).read_text())
    metrics = summary.get("metrics", {})
    approved = metrics.get("paritypay_payments_approved", {}).get("count", 0)
    duration = metrics.get("paritypay_payment_duration", {})
    # 측정 창은 payment-baseline.js 의 measured 시나리오(70초)입니다.
    return {
        "approved": approved,
        "p50Ms": duration.get("med"),
        "p95Ms": duration.get("p(95)"),
        "maxMs": duration.get("max"),
        "httpReqRate": metrics.get("http_reqs", {}).get("rate"),
        "failed": metrics.get("http_req_failed", {}).get("passes", 0),
    }


def container_memory():
    """APM 컨테이너가 쓰는 메모리입니다. 도구를 고르는 사람에게 설치 비용의 일부입니다."""
    out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.MemUsage}}"],
                         capture_output=True, text=True).stdout
    rows = {}
    for line in out.splitlines():
        name, _, usage = line.partition("\t")
        if any(k in name for k in ("otel-collector", "jaeger", "tempo", "signoz", "clickhouse",
                                   "keeper", "skywalking", "pinpoint", "hbase")):
            rows[name] = usage.split("/")[0].strip()
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--arms", default="none,jaeger")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--vus", type=int, default=20)
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--out", default="/tmp/t12-apm-overhead")
    parser.add_argument("--grouped", action="store_true",
                        help="팔별로 몰아서 돕니다. 기계 상태 변화가 결과에 섞이므로 기본은 번갈아입니다")
    args = parser.parse_args()

    if shutil.which("k6") is None:
        raise SystemExit("k6 가 없습니다. brew install k6")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT,
                            capture_output=True, text=True).stdout.strip()
    results = []
    arms = args.arms.split(",")
    # **번갈아 돌립니다.** 한 팔을 다 돌리고 다음 팔로 가면 그 사이의 기계 상태 변화가 전부 뒤쪽 팔의
    # 성과(또는 손해)로 보입니다 — 이 저장소는 그렇게 코드와 무관한 4.8배를 한 번 만들었습니다
    # (reports/11 M-007). 라운드마다 팔을 한 번씩 돕니다.
    rounds = [(arm, r) for r in range(1, args.runs + 1) for arm in arms]
    if args.grouped:
        rounds = [(arm, r) for arm in arms for r in range(1, args.runs + 1)]
    current = None
    for arm, run in rounds:
        if arm != current:
            switch_apm(arm)
            current = arm
        apm_env = read_apm_env()
        if True:
            print(f"== {arm} run {run}/{args.runs}", flush=True)
            app = start_app(args.jar, args.port, out_dir / f"{arm}-{run}-app.log", apm_env)
            try:
                measured = run_k6(args.port, out_dir / f"{arm}-{run}-k6.json", args.vus)
            finally:
                stop_app(app)
            measured.update({"arm": arm, "run": run, "commit": commit,
                             "agent": apm_env.get("JAVA_TOOL_OPTIONS", "(없음)"),
                             "containerMemory": container_memory(),
                             "at": datetime.now(timezone.utc).isoformat()})
            results.append(measured)
            print("   " + json.dumps({k: measured[k] for k in
                                      ("approved", "p50Ms", "p95Ms", "failed")}, ensure_ascii=False),
                  flush=True)
            (out_dir / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=1))

    print("\n| 팔 | 승인 건수(중앙값) | p50 ms | p95 ms | 실패 |")
    print("|---|---:|---:|---:|---:|")
    for arm in args.arms.split(","):
        rows = [r for r in results if r["arm"] == arm]
        if not rows:
            continue
        med = lambda key: statistics.median([r[key] for r in rows if r[key] is not None])
        print(f"| {arm} | {med('approved'):.0f} | {med('p50Ms'):.0f} | {med('p95Ms'):.0f} | "
              f"{sum(r['failed'] for r in rows)} |")
    print(f"\n원본: {out_dir}/results.json")


if __name__ == "__main__":
    main()

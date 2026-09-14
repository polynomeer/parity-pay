#!/usr/bin/env python3
"""M-013: 동시성을 올리면 단일 DB가 어디에서 먼저 막히는가.

reports/12 "다음 확장" 12번 — "운영 규모 환경에서의 단일 DB 한계와 모듈 분리 근거 측정
(ADR-001·002)" — 은 오래 남아 있었습니다. docs/05 §14는 모듈 분리를 검토할 조건 하나를 "단일 DB
잠금·커넥션 경합이 **측정 가능한** 병목"이라고 적어 두었고, 아무도 재지 않았습니다.

**이 환경에서 절대 한계는 잴 수 없습니다.** 부하 도구·앱·DB가 한 노트북의 CPU를 나눠 씁니다
(reports/11 §2). 잴 수 있는 것은 **모양**입니다 — VU를 10 → 20 → 40 → 80으로 올릴 때 처리량이
어디서 꺾이고, 그때 무엇이 기다리는가. 커넥션 풀 앞인가(앱 설정), 행 잠금인가(DB), CPU인가(기계).
그 답이 "DB를 나눠야 한다"인지 "풀을 키우면 된다"인지 "이 기계로는 더 못 잰다"인지를 가릅니다.

경합이 없는 조건(VU마다 지갑 하나, P-001과 같음)으로 잽니다. 같은 지갑 경합은 M-007이 이미 답했고
그것은 요구사항의 성질이지 DB 한계가 아닙니다.

두 풀 크기로 잽니다. 기본 20에서 40 VU를 넣으면 풀 앞에 줄이 서는 것은 산술이고, 그것은 DB 한계가
아니라 앱 설정입니다. 풀을 40으로 키운 뒤에도 같은 자리에서 꺾이면 그때 DB나 CPU를 봅니다.

M-007의 하니스(k6 + pg_stat_statements + pg_stat_activity 10 ms 샘플링 + HikariCP)를 그대로 쓰고,
DB 컨테이너·앱·k6의 CPU를 2초 간격으로 덧붙입니다.

사전 조건: docker compose up -d postgres mock-bank mock-pg, bootJar, 호스트에 k6

사용:
    python3 load-tests/db-saturation-experiment.py \\
        --jar apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar --vus 10,20,40,80 --pools 20,40

근거: reports/12 §12 다음 확장 12, docs/05-technical-design.md §14, ADR-001·002
"""

import argparse
import importlib.util
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

_spec = importlib.util.spec_from_file_location("lock_wait", Path(__file__).with_name("lock-wait-experiment.py"))
_m007 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_m007)


class CpuSampler(threading.Thread):
    """DB 컨테이너·앱·k6의 CPU와 호스트 부하를 2초마다 남깁니다. 어느 쪽이 먼저 차는지 봅니다."""

    def __init__(self):
        super().__init__(daemon=True)
        self.samples = []
        self._cputime = {}
        self._stop = threading.Event()

    def run(self):
        while not self._stop.is_set():
            at = time.monotonic()
            sample = {"at": at}
            try:
                out = subprocess.run(
                    ["docker", "stats", "--no-stream", "--format", "{{.CPUPerc}}", _m007.CONTAINER],
                    capture_output=True, text=True, timeout=10).stdout.strip().rstrip("%")
                sample["db_cpu"] = float(out) if out else None
            except (subprocess.SubprocessError, ValueError):
                sample["db_cpu"] = None
            # macOS의 `ps %cpu`는 프로세스 수명 전체의 평균이라 방금 뜬 JVM에서는 낮게 나옵니다.
            # 누적 CPU 시간의 차분을 벽시계 간격으로 나눠 순간 사용률을 만듭니다.
            for key, pattern in (("app_cpu", "pay-api-0.1.0-SNAPSHOT.jar"), ("k6_cpu", "k6 run")):
                try:
                    pids = subprocess.run(["pgrep", "-f", pattern], capture_output=True, text=True).stdout.split()
                    total = 0.0
                    for pid in pids:
                        ps = subprocess.run(["ps", "-o", "cputime=", "-p", pid], capture_output=True, text=True).stdout.strip()
                        total += parse_cputime(ps) if ps else 0.0
                    previous = self._cputime.get(key)
                    self._cputime[key] = (at, total)
                    if previous is not None and at > previous[0]:
                        sample[key] = max(0.0, 100.0 * (total - previous[1]) / (at - previous[0]))
                    else:
                        sample[key] = None
                except (subprocess.SubprocessError, ValueError):
                    sample[key] = None
            sample["load1"] = os.getloadavg()[0]
            self.samples.append(sample)
            self._stop.wait(2.0)

    def stop(self):
        self._stop.set()

    def window(self, start, seconds):
        return [s for s in self.samples if start <= s["at"] <= start + seconds]


def parse_cputime(text):
    """`ps cputime`의 `[[dd-]hh:]mm:ss.ss`를 초로 바꿉니다."""
    days = 0
    if "-" in text:
        d, text = text.split("-", 1)
        days = int(d)
    parts = [float(x) for x in text.split(":")]
    while len(parts) < 3:
        parts.insert(0, 0.0)
    return days * 86400 + parts[0] * 3600 + parts[1] * 60 + parts[2]


def mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def run_point(vus, pool, args, out_dir):
    tag = f"vus{vus}-pool{pool}"
    os.environ["SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE"] = str(pool)
    args.vus = vus
    cpu = CpuSampler()
    cpu.start()
    t0 = time.monotonic()
    result = _m007.run_mode("different-wallet", "saturation", args.jar, args, out_dir, tag)
    cpu.stop()
    # run_mode는 앱 기동 뒤 k6를 띄우고 window_start 초 뒤부터 window_seconds 동안 잽니다.
    # 앱 기동 시간은 모르므로 k6 요약의 측정 구간 대신, 창을 넉넉히 잡아 뒤쪽 절반을 씁니다.
    window = cpu.window(t0, 10_000)
    window = window[-max(1, args.window_seconds // 2):]
    pool_raw = result["pool_raw"]
    pending = [s.get("hikaricp_connections_pending", 0.0) for s in pool_raw] or [0.0]
    active = [s.get("hikaricp_connections_active", 0.0) for s in pool_raw] or [0.0]
    summary = result["k6"]
    return {
        "vus": vus,
        "pool": pool,
        "approved": result["counted"],
        "throughput": result["counted"] / args.window_seconds,
        "p50": _m007.metric(summary, "paritypay_payment_duration", "med"),
        "p95": _m007.metric(summary, "paritypay_payment_duration", "p(95)"),
        "failed_rate": _m007.metric(summary, "http_req_failed", "value"),
        "pool_active_mean": mean(active),
        "pool_pending_mean": mean(pending),
        "pool_pending_max": max(pending),
        "lock_wait_pct": _m007.lock_wait_pct(result),
        "wait_profile": result["wait_profile"][:6],
        "db_cpu": mean([s["db_cpu"] for s in window]),
        "app_cpu": mean([s["app_cpu"] for s in window]),
        "k6_cpu": mean([s["k6_cpu"] for s in window]),
        "load1": mean([s["load1"] for s in window]),
        "balance_update": _m007.balance_update(result),
    }


def render(points, cores):
    lines = ["| 풀 | VU | 승인/초 | p50 ms | p95 ms | 풀 active | 풀 pending 평균/최대 | Lock 대기 % | DB CPU % | 앱 CPU % | k6 CPU % | load1 |",
             "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for p in points:
        f = lambda v, d=1: "-" if v is None else f"{v:.{d}f}"
        lines.append(
            f"| {p['pool']} | {p['vus']} | {p['throughput']:.1f} | {f(p['p50'])} | {f(p['p95'])} | "
            f"{f(p['pool_active_mean'])} | {f(p['pool_pending_mean'])} / {p['pool_pending_max']:.0f} | "
            f"{p['lock_wait_pct']:.1f} | {f(p['db_cpu'],0)} | {f(p['app_cpu'],0)} | {f(p['k6_cpu'],0)} | {f(p['load1'])} |")
    lines.append(f"\n(CPU %는 코어 하나 = 100. 이 기계는 {cores}코어 = {cores * 100}%.)")
    for p in points:
        lines.append(f"\n[pool {p['pool']} · VU {p['vus']}] 대기 프로필 상위: " + ", ".join(
            f"{k}:{e} {pct}%" for k, e, _n, pct in p["wait_profile"]))
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--java", default=os.environ.get("JAVA_BIN", "java"))
    parser.add_argument("--port", type=int, default=8087)
    parser.add_argument("--out", default="/tmp/m013")
    parser.add_argument("--vus", default="10,20,40,80")
    parser.add_argument("--pools", default="20,40")
    parser.add_argument("--window-start", type=int, default=_m007.DEFAULT_WINDOW_START)
    parser.add_argument("--window-seconds", type=int, default=_m007.DEFAULT_WINDOW_SECONDS)
    parser.add_argument("--script", default=str(Path(__file__).with_name("payment-baseline.js")))
    parser.add_argument("--count-statement", default="insert into payment ")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    cores = os.cpu_count() or 0

    points = []
    # 풀·VU를 번갈아 돌리지 않고 순서대로 돕니다. 이 실험은 두 빌드를 비교하는 것이 아니라 한 빌드의
    # 곡선을 보는 것이라, 기계 상태 변화가 있어도 곡선의 모양(어디서 꺾이는가)은 남습니다.
    # run_point가 args.vus에 정수를 씁니다(M-007 하니스가 args에서 읽어 k6에 넘깁니다). 목록은 먼저 풀어 둡니다.
    vu_list = [int(v) for v in args.vus.split(",")]
    pool_list = [int(v) for v in args.pools.split(",")]
    for pool in pool_list:
        for vus in vu_list:
            point = run_point(vus, pool, args, out_dir)
            points.append(point)
            print(render([point], cores), flush=True)
            (out_dir / "raw.json").write_text(json.dumps(points, ensure_ascii=False, indent=1, default=str))

    report = render(points, cores)
    (out_dir / "report.txt").write_text(report + "\n")
    print("\n" + report)
    print(f"\n원본 결과: {out_dir}/report.txt, {out_dir}/raw.json")


if __name__ == "__main__":
    sys.exit(main())

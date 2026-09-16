#!/usr/bin/env python3
"""M-019~M-023: 외부기관이 느려지거나 죽었을 때 결제 서버가 어떻게 무너지고, 무엇이 그것을 막는가.

다섯 실험이 같은 하니스를 씁니다. 기관 장애는 Mock PG의 장애 모드로 만듭니다 — 기관이 소켓을 열어 둔 채
응답을 붙잡는 것(HANG_*)이 곧 TCP 수준의 "응답 없음"이고, 별도 프록시가 필요하지 않았습니다.

    exhaust    타임아웃 없음 + 기관 30초 지연. 플랫폼 스레드(붕괴) vs 가상 스레드(설계). jstack 1회 캡처
    retry      타임아웃 3초 + 승인 재시도. 없음 vs 3회 즉시 vs 3회 지수+지터. 기관이 받은 요청 수 / 클라이언트 요청 수
    circuit    차단기 상태 전이 시각과 OPEN 동안 들어온 결제의 운명, 회복 시간, 정합성 대조
    ratelimit  초당 상한 50. 고정 윈도 vs 토큰 버킷 vs 슬라이딩 윈도. 어느 1초 구간의 최대 통과 수
    bulkhead   exhaust의 붕괴 조건 + 벌크헤드 50. 무관한 API가 살아남는가

기본값에서 벗어나는 설정은 전부 `experiment-resilience` 프로필과 EXPERIMENT_* 환경변수로만 들어갑니다.
부하는 k6(`external-pg-load.js`)가 걸고, 이 스크립트는 그동안 1초마다 pay-api 지표를 긁고 250 ms마다
잔액 조회를 직접 찔러 "무관한 API가 무너지는 시각"을 잽니다.

사전 조건: docker compose로 postgres·redpanda·mock-pg가 떠 있고, pay-api bootJar와 k6가 있어야 합니다.
dev.sh로 띄운 pay-api가 살아 있으면 먼저 내립니다.

사용:
    J=apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar
    python3 load-tests/external-isolation-experiment.py exhaust  --jar $J --runs 3
    python3 load-tests/external-isolation-experiment.py retry    --jar $J --runs 3
    python3 load-tests/external-isolation-experiment.py circuit  --jar $J --runs 3
    python3 load-tests/external-isolation-experiment.py ratelimit --jar $J --runs 3
    python3 load-tests/external-isolation-experiment.py bulkhead --jar $J --runs 3

근거: reports/11 M-019~M-023, ADR-014, ADR-007
"""

import argparse
import json
import os
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

OPERATOR = {"email": "ops-operator@paritypay.local", "password": "local-ops-password"}
MERCHANT_ID = "3f1b7c64-9a2e-4c3d-8f11-5a7e2b9d4c60"


# ---------- HTTP ----------

def call(base, method, path, body=None, token=None, timeout=30):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
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


def text(base, path, timeout=10):
    with urllib.request.urlopen(base + path, timeout=timeout) as response:
        return response.read().decode()


def psql(container, database, sql):
    out = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "paritypay", "-d", database, "-tA", "-q",
         "-v", "ON_ERROR_STOP=1"],
        input=sql, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


# ---------- pay-api ----------

class App:
    def __init__(self, jar, port, db_url, kafka, log_path, extra_env):
        self.jar, self.port, self.db_url, self.kafka, self.log_path = jar, port, db_url, kafka, log_path
        self.extra_env = extra_env
        self.process = None

    def start(self):
        env = dict(os.environ)
        env.update({
            "SPRING_PROFILES_ACTIVE": "local,experiment-resilience",
            "PARITYPAY_PORT": str(self.port),
            "PARITYPAY_DB_URL": self.db_url,
            "PARITYPAY_KAFKA_SERVERS": self.kafka,
            "PARITYPAY_TRACE_SAMPLING": "0",
            "PARITYPAY_RECONCILIATION_ENABLED": "false",
        })
        env.update(self.extra_env)
        open(self.log_path, "w").close()
        log = open(self.log_path, "a")
        self.process = subprocess.Popen(
            [os.environ.get("JAVA", "java"), "-jar", self.jar], stdout=log, stderr=log, env=env)
        deadline = time.time() + 180
        while time.time() < deadline:
            if "Started ParityPayApplication" in self.read_log():
                return
            if self.process.poll() is not None:
                raise RuntimeError(f"pay-api exited during startup; see {self.log_path}")
            time.sleep(1)
        raise RuntimeError("pay-api did not start in time")

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()

    def read_log(self):
        try:
            return open(self.log_path, encoding="utf-8", errors="replace").read()
        except FileNotFoundError:
            return ""

    def circuit_transitions(self):
        """`pg circuit A -> B` 로그를 (시각, from, to)로 돌려줍니다."""
        out = []
        for line in self.read_log().splitlines():
            if "pg circuit " in line and " -> " in line:
                stamp = line[:23]
                tail = line.split("pg circuit ", 1)[1].strip()
                from_state, to_state = tail.split(" -> ")
                out.append((stamp, from_state, to_state))
        return out

    def jstack(self, path):
        java = os.environ.get("JAVA", "java")
        jstack = java[:-4] + "jstack" if java.endswith("java") else "jstack"
        subprocess.run([jstack, str(self.process.pid)], stdout=open(path, "w"), stderr=subprocess.STDOUT)
        dump = open(path, errors="ignore").read()
        return {
            "threads": dump.count('" #') + dump.count('" prio='),
            "socketRead": dump.count("socketRead") + dump.count("SocketInputStream") + dump.count("NioSocketImpl.read"),
            "tomcatExec": dump.count("http-nio-"),
            "path": path,
        }


# ---------- Mock PG ----------

def pg_behavior(pg_base, **fields):
    status, body = call(pg_base, "POST", "/mock-pg/admin/behavior", fields)
    if status >= 400:
        raise RuntimeError(f"mock-pg behavior {fields} failed HTTP {status}: {body}")


def pg_reset(pg_base):
    call(pg_base, "POST", "/mock-pg/admin/reset")
    pg_behavior(pg_base, reset=True)


def pg_approval_count(pg_base):
    status, body = call(pg_base, "GET", "/mock-pg/admin/approvals/count")
    return int(body) if isinstance(body, int) else int(str(body).strip('"') or 0)


def pg_request_timestamps(broker_container, since_iso):
    """기관이 받은 승인 요청 시각. HANG 모드의 'hanging ... approving' 로그와 NORMAL 모드의 표 시각을 씁니다."""
    out = subprocess.run(["docker", "logs", "--since", since_iso, broker_container], capture_output=True, text=True)
    stamps = []
    for line in out.stdout.splitlines() + out.stderr.splitlines():
        if "approving" in line:
            try:
                stamps.append(datetime.fromisoformat(line[:23]).timestamp())
            except ValueError:
                pass
    return stamps


def pg_approval_timestamps(db_container, since_iso):
    rows = psql(db_container, "paritypay_pg",
                f"SELECT extract(epoch FROM created_at) FROM mock_pg_approval WHERE created_at > '{since_iso}'::timestamptz ORDER BY created_at")
    return [float(r) for r in rows.splitlines() if r]


def max_in_window(stamps, window_seconds):
    stamps = sorted(stamps)
    best, j = 0, 0
    for i in range(len(stamps)):
        while stamps[i] - stamps[j] >= window_seconds:
            j += 1
        best = max(best, i - j + 1)
    return best


# ---------- 관찰 ----------

class Sampler(threading.Thread):
    """1초마다 지표를, 250 ms마다 잔액 조회 지연을 기록합니다."""

    METRICS = ["tomcat_threads_busy_threads", "tomcat_threads_config_max_threads", "hikaricp_connections_active",
               "hikaricp_connections_pending", "paritypay_pg_in_flight", "paritypay_pg_circuit_state",
               "paritypay_pg_circuit_failure_rate", "jvm_threads_live_threads"]

    def __init__(self, base, token):
        super().__init__(daemon=True)
        self.base, self.token = base, token
        self.samples, self.probes = [], []
        self._stop = threading.Event()
        self.t0 = time.monotonic()

    def run(self):
        next_metrics = 0.0
        while not self._stop.is_set():
            now = time.monotonic() - self.t0
            if now >= next_metrics:
                self.samples.append({"t": round(now, 1), **self.scrape()})
                next_metrics = now + 1.0
            started = time.monotonic()
            try:
                status, _ = call(self.base, "GET", "/api/v1/wallets/me", token=self.token, timeout=30)
            except Exception:
                status = 0
            self.probes.append((round(now, 2), round((time.monotonic() - started) * 1000), status))
            time.sleep(0.25)

    def scrape(self):
        try:
            body = text(self.base, "/actuator/prometheus", timeout=5)
        except Exception:
            return {"scrapeFailed": True}
        values = {}
        for line in body.splitlines():
            if line.startswith("#"):
                continue
            name = line.split("{")[0].split(" ")[0]
            if name in self.METRICS:
                values[name] = float(line.rsplit(" ", 1)[1])
            elif name == "paritypay_pg_rejected_total":
                reason = line.split('reason="')[1].split('"')[0] if 'reason="' in line else "?"
                values[f"rejected_{reason}"] = float(line.rsplit(" ", 1)[1])
        return values

    def stop(self):
        self._stop.set()

    def collapse_at(self, threshold_ms=1000):
        """무관한 API가 무너진 시각: 잔액 조회 지연이 threshold를 넘거나 실패한 첫 시각."""
        for t, ms, status in self.probes:
            if status != 200 or ms > threshold_ms:
                return t
        return None

    def probe_p95(self, from_t=0.0, to_t=None):
        values = [ms for t, ms, s in self.probes if t >= from_t and (to_t is None or t <= to_t)]
        if not values:
            return None
        values.sort()
        return values[min(len(values) - 1, int(len(values) * 0.95))]


def run_k6(script, base, pay_rps, read_rps, duration, out_json, extra_env=None):
    """k6를 돌리고 데이터 포인트를 전부 받아 결과별 건수와 지연 분포를 셉니다.

    summary-export에는 태그별 submetric이 없어 "어떤 결과가 몇 건"을 알 수 없습니다. --out json은 포인트
    단위라 크지만(수천 줄) 결과·시각을 전부 갖습니다.
    """
    env = dict(os.environ)
    env.update({"BASE_URL": base, "PAY_RPS": str(pay_rps), "READ_RPS": str(read_rps), "DURATION": duration,
                "MERCHANT_ID": MERCHANT_ID})
    env.update(extra_env or {})
    log = subprocess.run(["k6", "run", "--quiet", "--out", f"json={out_json}", script],
                         env=env, capture_output=True, text=True)
    if not Path(out_json).exists():
        raise RuntimeError("k6 produced no output:\n" + log.stdout[-2000:] + log.stderr[-2000:])
    outcomes, pay, read, read_failed, dropped = {}, [], [], 0, 0
    for line in open(out_json):
        try:
            point = json.loads(line)
        except json.JSONDecodeError:
            continue
        if point.get("type") != "Point":
            continue
        metric, data = point.get("metric"), point.get("data", {})
        if metric == "pg_payment_outcome":
            key = data.get("tags", {}).get("outcome", "?")
            outcomes[key] = outcomes.get(key, 0) + 1
        elif metric == "pg_payment_duration":
            pay.append((data["time"], data["value"]))
        elif metric == "balance_read_duration":
            read.append((data["time"], data["value"]))
        elif metric == "balance_read_failed":
            read_failed += 1
        elif metric == "dropped_iterations":
            dropped += 1

    def trend(points):
        values = sorted(v for _, v in points)
        if not values:
            return {}
        return {"avg": round(sum(values) / len(values), 1), "med": round(values[len(values) // 2], 1),
                "p(95)": round(values[min(len(values) - 1, int(len(values) * 0.95))], 1), "max": round(values[-1], 1)}

    def per_second_p95(points):
        """시작부터 초 단위로 묶은 p95. 무너지는 시각을 보는 데 씁니다."""
        if not points:
            return []
        t0 = min(datetime.fromisoformat(t).timestamp() for t, _ in points)
        buckets = {}
        for t, v in points:
            sec = int(datetime.fromisoformat(t).timestamp() - t0)
            buckets.setdefault(sec, []).append(v)
        series = []
        for sec in sorted(buckets):
            values = sorted(buckets[sec])
            series.append((sec, round(values[min(len(values) - 1, int(len(values) * 0.95))])))
        return series

    return {
        "payment": trend(pay),
        "read": trend(read),
        "paymentOutcomes": outcomes,
        "paymentRequests": len(pay),
        "readRequests": len(read),
        "readFailed": read_failed,
        "droppedIterations": dropped,
        "readP95PerSecond": per_second_p95(read),
        "paymentP95PerSecond": per_second_p95(pay),
    }


def operator_token(base):
    status, body = call(base, "POST", "/api/v1/auth/tokens", OPERATOR)
    if status != 200:
        raise RuntimeError(f"operator login failed HTTP {status}: {body}")
    return body["accessToken"]


def probe_account(base):
    email = f"probe-{int(time.time() * 1000)}@example.com"
    call(base, "POST", "/api/v1/members", {"email": email, "password": "probe-password-1"})
    _, token = call(base, "POST", "/api/v1/auth/tokens", {"email": email, "password": "probe-password-1"})
    return token["accessToken"]


def now_iso():
    return datetime.now(timezone.utc).isoformat()


# ---------- 실험 ----------

def run_variant(args, name, variant, app_env, pg_mode, pg_hang_ms, pay_rps, read_rps, duration, run_index,
                hold_then_restore=None, jstack_at=None):
    """pay-api 한 대를 변형 설정으로 띄우고, 기관 모드를 걸고, k6를 돌리고, 관찰을 모읍니다."""
    log_path = os.path.join(args.log_dir, f"{name}-{variant}-{run_index}.log")
    app = App(args.jar, args.port, args.db_url, args.kafka, log_path, app_env)
    base = f"http://localhost:{args.port}"
    pg_reset(args.pg_base)
    try:
        app.start()
        token = probe_account(base)
        since = now_iso()
        pg_behavior(args.pg_base, approvalMode=pg_mode, hangForMillis=pg_hang_ms)
        approvals_before = pg_approval_count(args.pg_base)
        sampler = Sampler(base, token)
        sampler.start()
        k6_out = os.path.join(args.log_dir, f"{name}-{variant}-{run_index}-k6.json")
        k6_thread = threading.Thread(
            target=lambda: setattr(run_variant, "_k6", run_k6(args.k6_script, base, pay_rps, read_rps, duration, k6_out)),
            daemon=True)
        run_variant._k6 = None
        k6_thread.start()
        jstack_info = None
        restored_at = None
        started = time.monotonic()
        while k6_thread.is_alive():
            elapsed = time.monotonic() - started
            if jstack_at is not None and jstack_info is None and elapsed >= jstack_at:
                jstack_info = app.jstack(os.path.join(args.log_dir, f"{name}-{variant}-{run_index}-jstack.txt"))
            if hold_then_restore is not None and restored_at is None and elapsed >= hold_then_restore:
                pg_behavior(args.pg_base, approvalMode="NORMAL")
                restored_at = round(elapsed, 1)
            time.sleep(0.5)
        k6_thread.join()
        sampler.stop()
        sampler.join(timeout=5)
        k6 = run_variant._k6
        approvals_after = pg_approval_count(args.pg_base)
        pg_stamps = pg_request_timestamps(args.pg_container, since) or pg_approval_timestamps(args.db_container, since)
        result = {
            "experiment": name, "variant": variant, "run": run_index, "since": since,
            "load": {"payRps": pay_rps, "readRps": read_rps, "duration": duration},
            "pgMode": pg_mode, "pgHangMs": pg_hang_ms, "env": app_env,
            "k6": k6,
            "pgApprovals": approvals_after - approvals_before,
            "pgRequestsSeen": len(pg_stamps),
            "pgMaxPer1s": max_in_window(pg_stamps, 1.0) if pg_stamps else None,
            "pgMaxPer100ms": max_in_window(pg_stamps, 0.1) if pg_stamps else None,
            "probeCollapseAtSec": sampler.collapse_at(),
            "probeP95Ms": sampler.probe_p95(),
            "samples": sampler.samples,
            "probes": sampler.probes,
            "circuitTransitions": app.circuit_transitions(),
            "restoredAtSec": restored_at,
            "jstack": jstack_info,
        }
        return result
    finally:
        pg_behavior(args.pg_base, reset=True)
        app.stop()


def consistency_check(args, result):
    """CIRCUIT_OPEN·BULKHEAD_FULL·RATE_LIMITED로 FAILED된 결제 중 기관에 승인 기록이 있는 것이 있는가. 0이어야 합니다."""
    since = result["since"]
    rows = psql(args.db_container, "paritypay",
                f"SELECT payment_id::text, failure_reason FROM payment WHERE status = 'FAILED' AND failure_reason IN "
                f"('CIRCUIT_OPEN','BULKHEAD_FULL','RATE_LIMITED') AND created_at > '{since}'::timestamptz").splitlines()
    ids = [r.split("|")[0] for r in rows if r]
    if not ids:
        return {"rejectedPayments": 0, "reachedInstitution": 0}
    reached = 0
    for chunk in range(0, len(ids), 500):
        id_list = ",".join(f"'{i}'" for i in ids[chunk:chunk + 500])
        reached += int(psql(args.db_container, "paritypay_pg",
                            f"SELECT count(*) FROM mock_pg_approval WHERE external_key IN ({id_list})"))
    unknown = int(psql(args.db_container, "paritypay",
                       f"SELECT count(*) FROM payment WHERE status = 'UNKNOWN' AND created_at > '{since}'::timestamptz"))
    return {"rejectedPayments": len(ids), "reachedInstitution": reached, "unknownPayments": unknown}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("experiment", choices=["exhaust", "retry", "circuit", "ratelimit", "bulkhead"])
    parser.add_argument("--jar", required=True)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--variants", help="쉼표로 구분. 비우면 실험의 기본 변형 전부")
    parser.add_argument("--port", type=int, default=8180)
    parser.add_argument("--db-url", default="jdbc:postgresql://localhost:5432/paritypay")
    parser.add_argument("--kafka", default="localhost:9092")
    parser.add_argument("--pg-base", default="http://localhost:8091")
    parser.add_argument("--pg-container", default="paritypay-mock-pg")
    parser.add_argument("--db-container", default="paritypay-postgres")
    parser.add_argument("--k6-script", default="load-tests/external-pg-load.js")
    parser.add_argument("--log-dir", default="/tmp/external-isolation")
    parser.add_argument("--out", default="/tmp/external-isolation")
    args = parser.parse_args()
    Path(args.log_dir).mkdir(parents=True, exist_ok=True)
    Path(args.out).mkdir(parents=True, exist_ok=True)
    out = Path(args.out) / f"{args.experiment}-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()

    NO_ISOLATION = {"EXPERIMENT_BULKHEAD": "false", "EXPERIMENT_CIRCUIT": "false"}
    plans = {
        # (a) 타임아웃 없음 + 30초 지연. 플랫폼 스레드는 붕괴, 가상 스레드는 설계 기본.
        "exhaust": {
            "platform-threads": dict(app_env={**NO_ISOLATION, "EXPERIMENT_VIRTUAL_THREADS": "false",
                                              "EXPERIMENT_PG_READ_TIMEOUT": "120s"},
                                     pg_mode="HANG_BEFORE_PROCESSING", pg_hang_ms=30000, pay_rps=10, read_rps=20,
                                     duration="75s", jstack_at=40),
            "virtual-threads": dict(app_env={**NO_ISOLATION, "EXPERIMENT_VIRTUAL_THREADS": "true",
                                             "EXPERIMENT_PG_READ_TIMEOUT": "120s"},
                                    pg_mode="HANG_BEFORE_PROCESSING", pg_hang_ms=30000, pay_rps=10, read_rps=20,
                                    duration="75s", jstack_at=40),
        },
        # (b) 타임아웃 3초, 기관은 승인한 뒤 6초 붙잡음 → 매 요청이 타임아웃. 재시도가 요청을 몇 배로 만드는가.
        "retry": {
            "no-retry": dict(app_env={**NO_ISOLATION, "EXPERIMENT_RETRY": "false"},
                             pg_mode="HANG_AFTER_PROCESSING", pg_hang_ms=6000, pay_rps=10, read_rps=5, duration="30s"),
            "retry-3-immediate": dict(app_env={**NO_ISOLATION, "EXPERIMENT_RETRY": "true", "EXPERIMENT_RETRY_ATTEMPTS": "3",
                                               "EXPERIMENT_RETRY_BACKOFF": "NONE"},
                                      pg_mode="HANG_AFTER_PROCESSING", pg_hang_ms=6000, pay_rps=10, read_rps=5, duration="30s"),
            "retry-3-jitter": dict(app_env={**NO_ISOLATION, "EXPERIMENT_RETRY": "true", "EXPERIMENT_RETRY_ATTEMPTS": "3",
                                            "EXPERIMENT_RETRY_BACKOFF": "EXPONENTIAL_JITTER"},
                                   pg_mode="HANG_AFTER_PROCESSING", pg_hang_ms=6000, pay_rps=10, read_rps=5, duration="30s"),
        },
        # (c) 차단기. 기관이 40초 동안 응답을 붙잡다가(6초 > 타임아웃 3초) 정상으로 돌아옵니다.
        "circuit": {
            "circuit-on": dict(app_env={"EXPERIMENT_CIRCUIT": "true", "EXPERIMENT_BULKHEAD": "true"},
                               pg_mode="HANG_BEFORE_PROCESSING", pg_hang_ms=6000, pay_rps=10, read_rps=5,
                               duration="90s", hold_then_restore=40),
            "circuit-off": dict(app_env={"EXPERIMENT_CIRCUIT": "false", "EXPERIMENT_BULKHEAD": "true"},
                                pg_mode="HANG_BEFORE_PROCESSING", pg_hang_ms=6000, pay_rps=10, read_rps=5,
                                duration="90s", hold_then_restore=40),
        },
        # (d) 기관 상한 50/s, 부하 80/s. 알고리즘 셋을 같은 부하로.
        "ratelimit": {
            alg: dict(app_env={"EXPERIMENT_RATE_LIMITER": "true", "EXPERIMENT_RATE_LIMIT": "50",
                               "EXPERIMENT_RATE_ALGORITHM": alg},
                      pg_mode="NORMAL", pg_hang_ms=0, pay_rps=80, read_rps=5, duration="20s")
            for alg in ("FIXED_WINDOW", "TOKEN_BUCKET", "SLIDING_WINDOW")
        },
        # (e) exhaust의 붕괴 조건에 벌크헤드만 켭니다.
        "bulkhead": {
            "platform-threads-bulkhead-50": dict(app_env={"EXPERIMENT_VIRTUAL_THREADS": "false",
                                                          "EXPERIMENT_PG_READ_TIMEOUT": "120s",
                                                          "EXPERIMENT_BULKHEAD": "true", "EXPERIMENT_BULKHEAD_MAX": "50",
                                                          "EXPERIMENT_CIRCUIT": "false"},
                                                 pg_mode="HANG_BEFORE_PROCESSING", pg_hang_ms=30000, pay_rps=10,
                                                 read_rps=20, duration="75s", jstack_at=40),
        },
    }
    plan = plans[args.experiment]
    variants = args.variants.split(",") if args.variants else list(plan)
    results = []
    print(f"== {args.experiment} × {args.runs}, 커밋 {commit}, 결과 {out}", flush=True)
    for variant in variants:
        spec = plan[variant]
        print(f"## {variant}: {spec['app_env']}", flush=True)
        for i in range(args.runs):
            r = run_variant(args, args.experiment, variant, spec["app_env"], spec["pg_mode"], spec["pg_hang_ms"],
                            spec["pay_rps"], spec["read_rps"], spec["duration"], i,
                            hold_then_restore=spec.get("hold_then_restore"), jstack_at=spec.get("jstack_at"))
            r["commitSha"] = commit
            if args.experiment in ("circuit", "ratelimit", "bulkhead"):
                r["consistency"] = consistency_check(args, r)
            results.append(r)
            out.write_text(json.dumps(results, ensure_ascii=False, indent=1))
            brief = {k: v for k, v in r.items() if k not in ("samples", "probes", "env")}
            brief["k6"] = {k: v for k, v in r["k6"].items() if "PerSecond" not in k}
            print("  " + json.dumps(brief, ensure_ascii=False)[:1500], flush=True)
    print(f"== 끝. {out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

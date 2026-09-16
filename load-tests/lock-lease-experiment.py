#!/usr/bin/env python3
"""M-024~M-028: 분산락 lease가 트랜잭션보다 먼저 만료되면 정말 정합성이 깨지는가 — 그리고 무엇이 그것을 막는가.

ParityPay는 잔액 차감에 분산락을 쓰지 않습니다(ADR-004). 이 실험은 그 결정을 뒤집기 위한 것이 아니라, "락이
있으니 안전하다"고 믿는 구현을 일부러 만들어(`experiment-lock` 프로필) 같은 부하·같은 sleep 아래에서 기본
경로(조건부 원자 UPDATE)와 대조하기 위한 것입니다. 실험 경로는 실험 뒤 채택하지 않습니다.

    lease      (a) TTL 200ms 락 안에서 300~500ms 멈춤 → 두 소유자. 겹침·drift·초과 승인
    watchdog   (b) 자동 연장 켬: 정상 진행 중 만료는 사라지는가 / SIGSTOP(프로세스 정지) 중에는 다시 생기는가
    fencing    (c) 쓰기 시점 fencing(흔한 설명) vs 읽는 순간 토큰 새기기. 거부 건수·drift
    control    (d) 분산락 없이 기본 경로 + 같은 sleep. 지표 전부 0인가, 처리량·p95
    redisdown  (e) 부하 중 Redis kill → fail-closed / fail-open / 대조군

소유 겹침은 로그가 아니라 표로 셉니다. 실험 경로는 락 안에서 소유 토큰과 시각을 `experiment_lock_hold`에
따로 남기고, 이 스크립트는 같은 지갑의 두 행이 [acquired_at, released_at]에서 겹치는 쌍을 SQL로 셉니다.
drift는 지갑 스냅샷(가용+처리중)과 원장 계산값(USER_PAY_MONEY 계정, POSTED)의 차이(INV-010)이고, 초과 승인은
원장 잔액이 음수가 된 만큼입니다(INV-003).

사전 조건: docker compose로 postgres·redpanda·mock-bank가 떠 있고 pay-api bootJar가 있어야 합니다. Redis는 이
스크립트가 `docker compose up -d redis`로 띄우고 끝나면 내립니다(--keep-redis로 남김).

사용:
    J=apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar
    python3 load-tests/lock-lease-experiment.py lease     --jar $J --runs 3
    python3 load-tests/lock-lease-experiment.py watchdog  --jar $J --runs 3
    python3 load-tests/lock-lease-experiment.py fencing   --jar $J --runs 3
    python3 load-tests/lock-lease-experiment.py control   --jar $J --runs 3
    python3 load-tests/lock-lease-experiment.py redisdown --jar $J --runs 3

근거: reports/11 M-024~M-028, ADR-004
"""

import argparse
import json
import os
import signal
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

MERCHANT_ID = "3f1b7c64-9a2e-4c3d-8f11-5a7e2b9d4c60"
PASSWORD = "lock-lease-password-1"


# ---------- HTTP ----------

def call(base, method, path, body=None, token=None, key=None, timeout=30):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["Idempotency-Key"] = key
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


def psql(container, sql):
    out = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "paritypay", "-d", "paritypay", "-tA", "-q",
         "-v", "ON_ERROR_STOP=1"],
        input=sql, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def docker(*args, check=True):
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=check)


# ---------- pay-api ----------

class App:
    def __init__(self, jar, port, db_url, kafka, log_path, extra_env):
        self.jar, self.port, self.db_url, self.kafka, self.log_path = jar, port, db_url, kafka, log_path
        self.extra_env = extra_env
        self.process = None

    def start(self):
        env = dict(os.environ)
        env.update({
            "SPRING_PROFILES_ACTIVE": "local,experiment-lock",
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
            # SIGSTOP 상태로 남아 있으면 terminate가 전달되지 않습니다.
            try:
                os.kill(self.process.pid, signal.SIGCONT)
            except ProcessLookupError:
                pass
            self.process.terminate()
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()

    def pause(self, seconds):
        """프로세스 전체를 멈춥니다 — 진짜 stop-the-world. Watchdog 스레드도 함께 멈춥니다."""
        os.kill(self.process.pid, signal.SIGSTOP)
        time.sleep(seconds)
        os.kill(self.process.pid, signal.SIGCONT)

    def read_log(self):
        try:
            return open(self.log_path, encoding="utf-8", errors="replace").read()
        except FileNotFoundError:
            return ""


# ---------- 준비 ----------

def provision_wallet(base, tag, initial_balance):
    email = f"lock-{tag}-{int(time.time() * 1_000_000)}@example.com"
    status, member = call(base, "POST", "/api/v1/members", {"email": email, "password": PASSWORD})
    if status >= 400:
        raise RuntimeError(f"member failed HTTP {status}: {member}")
    _, token = call(base, "POST", "/api/v1/auth/tokens", {"email": email, "password": PASSWORD})
    access = token["accessToken"]
    account = {}
    for attempt in range(5):
        # 갓 뜬 은행 대역의 첫 요청은 초기화 때문에 3초 타임아웃을 넘길 수 있습니다. 실험 대상이 아니므로 다시 시도합니다.
        status, account = call(
            base, "POST", "/api/v1/bank-accounts",
            {"bankCode": "004", "accountNumber": f"110{int(time.time() * 1_000_000) % 10**12:012d}",
             "initialBalance": 100_000_000},
            token=access)
        if status < 400:
            break
        time.sleep(2)
    if "bankAccountId" not in account:
        raise RuntimeError(f"bank account failed after retries: {account}")
    status, top_up = call(
        base, "POST", "/api/v1/top-ups",
        {"walletId": member["walletId"], "bankAccountId": account["bankAccountId"],
         "amount": initial_balance, "currency": "KRW"},
        token=access, key=f"lock-topup-{tag}-{int(time.time() * 1_000_000)}")
    if status >= 400:
        raise RuntimeError(f"top-up failed HTTP {status}: {top_up}")
    deadline = time.time() + 30
    while time.time() < deadline:
        _, view = call(base, "GET", f"/api/v1/top-ups/{top_up['topUpId']}", token=access)
        if view.get("status") == "SUCCEEDED":
            break
        if view.get("status") == "FAILED":
            raise RuntimeError(f"top-up failed: {view}")
        time.sleep(0.2)
    _, wallet = call(base, "GET", "/api/v1/wallets/me", token=access)
    if wallet.get("available") != initial_balance:
        raise RuntimeError(f"wallet {member['walletId']} funded {wallet.get('available')} != {initial_balance}")
    return {"walletId": member["walletId"], "token": access}


# ---------- 부하 ----------

class Load:
    """스레드 T개가 지갑 W개에 PAY_MONEY 결제를 쉬지 않고 보냅니다. 스레드 i는 지갑 i % W에 붙습니다."""

    def __init__(self, base, wallets, threads, amount, duration):
        self.base, self.wallets, self.threads, self.amount, self.duration = base, wallets, threads, amount, duration
        self.results = []
        self.lock = threading.Lock()
        self.stop = threading.Event()

    def worker(self, index):
        wallet = self.wallets[index % len(self.wallets)]
        counter = 0
        while not self.stop.is_set():
            counter += 1
            key = f"lock-{index}-{counter}-{int(time.time() * 1000)}"
            started = time.monotonic()
            try:
                status, body = call(
                    self.base, "POST", "/api/v1/payments",
                    {"orderId": f"order-{key}", "walletId": wallet["walletId"], "merchantId": MERCHANT_ID,
                     "amount": self.amount, "currency": "KRW", "method": "PAY_MONEY"},
                    token=wallet["token"], key=key, timeout=60)
                outcome = body.get("status") if status < 400 else body.get("code", f"http_{status}")
                message = body.get("message", "") if status >= 400 else ""
            except Exception as e:  # 연결 끊김 등
                status, outcome, message = 0, f"error_{type(e).__name__}", str(e)[:120]
            elapsed = time.monotonic() - started
            with self.lock:
                self.results.append((wallet["walletId"], status, outcome, message, elapsed))

    def run(self, on_tick=None):
        workers = [threading.Thread(target=self.worker, args=(i,), daemon=True) for i in range(self.threads)]
        for w in workers:
            w.start()
        started = time.monotonic()
        while time.monotonic() - started < self.duration:
            if on_tick:
                on_tick(time.monotonic() - started)
            time.sleep(0.25)
        self.stop.set()
        for w in workers:
            w.join(timeout=90)
        return time.monotonic() - started

    def summary(self, wall_seconds):
        outcomes = {}
        latencies = []
        for _, status, outcome, message, elapsed in self.results:
            label = outcome
            if status >= 400 and message:
                label = f"{outcome}:{message.split(';')[0][:48]}"
            outcomes[label] = outcomes.get(label, 0) + 1
            latencies.append(elapsed * 1000)
        latencies.sort()
        p = lambda q: latencies[min(len(latencies) - 1, int(len(latencies) * q))] if latencies else None
        return {
            "requests": len(self.results),
            "outcomes": dict(sorted(outcomes.items(), key=lambda kv: -kv[1])),
            "throughputPerSec": round(len(self.results) / wall_seconds, 2) if wall_seconds else None,
            "p50Ms": round(p(0.50), 1) if latencies else None,
            "p95Ms": round(p(0.95), 1) if latencies else None,
            "maxMs": round(latencies[-1], 1) if latencies else None,
        }


# ---------- 검증 (SQL) ----------

def wallet_state(db, wallet_id, initial_balance, amount):
    snapshot = psql(db, f"SELECT available_amount + pending_amount FROM wallet_balance WHERE wallet_id = '{wallet_id}'")
    ledger = psql(db, f"""
        SELECT coalesce(sum(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
          FROM ledger_entry e
          JOIN ledger_transaction t ON t.transaction_id = e.transaction_id
          JOIN ledger_account a ON a.account_id = e.account_id
         WHERE a.account_code = '2010' AND a.owner_id = '{wallet_id}' AND t.status = 'POSTED'""")
    approved = psql(db, f"SELECT count(*) FROM payment WHERE wallet_id = '{wallet_id}' AND status IN ('APPROVED','CONFIRMED')")
    snapshot, ledger, approved = int(snapshot), int(ledger), int(approved)
    affordable = initial_balance // amount
    return {
        "walletId": wallet_id,
        "snapshot": snapshot,
        "ledger": ledger,
        "drift": snapshot - ledger,
        "approved": approved,
        "affordable": affordable,
        "excessApprovals": max(0, approved - affordable),
        "ledgerNegative": ledger < 0,
    }


def hold_stats(db, run_id):
    scope = f"SELECT * FROM experiment_lock_hold WHERE run_id = '{run_id}'"
    counts = psql(db, f"""
        SELECT count(*), count(*) FILTER (WHERE released_at IS NULL), coalesce(sum(renewals), 0)
          FROM ({scope}) h""").split("|")
    overlaps = int(psql(db, f"""
        WITH h AS ({scope} AND released_at IS NOT NULL)
        SELECT count(*) FROM h a JOIN h b
          ON a.wallet_id = b.wallet_id AND a.hold_id < b.hold_id
         AND a.acquired_at < b.released_at AND b.acquired_at < a.released_at"""))
    owned_overlaps = int(psql(db, f"""
        WITH h AS ({scope} AND released_at IS NOT NULL AND token IS NOT NULL)
        SELECT count(*) FROM h a JOIN h b
          ON a.wallet_id = b.wallet_id AND a.hold_id < b.hold_id
         AND a.acquired_at < b.released_at AND b.acquired_at < a.released_at"""))
    # 겹친 두 소유자가 "둘 다 썼는가": b가 a의 쓰기 전에 읽고(a.wrote_at > b.acquired_at) 그 뒤에 썼다면 잃어버린 갱신입니다.
    lost_updates = int(psql(db, f"""
        WITH h AS ({scope} AND wrote_at IS NOT NULL AND outcome = 'APPROVED')
        SELECT count(*) FROM h a JOIN h b
          ON a.wallet_id = b.wallet_id AND a.hold_id <> b.hold_id
         AND b.acquired_at < a.wrote_at AND b.wrote_at > a.wrote_at"""))
    max_owners = psql(db, f"""
        WITH h AS ({scope} AND released_at IS NOT NULL AND token IS NOT NULL)
        SELECT coalesce(max(c), 0) FROM (
          SELECT (SELECT count(*) FROM h b WHERE b.wallet_id = a.wallet_id
                   AND b.acquired_at <= a.acquired_at AND b.released_at > a.acquired_at) AS c
            FROM h a) x""")
    release = psql(db, f"SELECT coalesce(release_result, 'NONE'), count(*) FROM ({scope}) h GROUP BY 1 ORDER BY 1")
    outcome = psql(db, f"SELECT coalesce(outcome, 'NONE'), count(*) FROM ({scope}) h GROUP BY 1 ORDER BY 1")
    to_map = lambda text: {row.split("|")[0]: int(row.split("|")[1]) for row in text.splitlines() if row}
    return {
        "holds": int(counts[0]),
        "unreleased": int(counts[1]),
        "renewals": int(counts[2]),
        "overlappingPairs": overlaps,
        "ownedOverlappingPairs": owned_overlaps,
        "lostUpdatePairs": lost_updates,
        "maxSimultaneousOwners": int(max_owners),
        "releaseResults": to_map(release),
        "outcomes": to_map(outcome),
    }


# ---------- 실험 ----------

def run_variant(args, name, variant, app_env, run_index, pause_every=None, pause_seconds=None,
                redis_kill_at=None, redis_restart_at=None):
    run_id = f"{name}-{variant}-{run_index}-{datetime.now(timezone.utc).strftime('%H%M%S')}"
    log_path = os.path.join(args.log_dir, f"{run_id}.log")
    env = {"EXPERIMENT_RUN_ID": run_id, "EXPERIMENT_REDIS_URI": f"redis://localhost:{args.redis_port}",
           "EXPERIMENT_LOCK_TTL": args.ttl, "EXPERIMENT_LOCK_HOLD_MIN_MS": str(args.hold_min),
           "EXPERIMENT_LOCK_HOLD_MAX_MS": str(args.hold_max), **app_env}
    app = App(args.jar, args.port, args.db_url, args.kafka, log_path, env)
    base = f"http://localhost:{args.port}"
    print(f"[{run_id}] starting pay-api {env}", flush=True)
    events = []
    try:
        app.start()
        wallets = [provision_wallet(base, f"{run_index}-{i}", args.initial_balance) for i in range(args.wallets)]
        load = Load(base, wallets, args.threads, args.amount, args.duration)
        state = {"next_pause": pause_every or 0, "killed": False, "restarted": False, "pauses": 0}

        def tick(elapsed):
            if pause_every and elapsed >= state["next_pause"]:
                app.pause(pause_seconds)
                state["pauses"] += 1
                state["next_pause"] = elapsed + pause_every
            if redis_kill_at is not None and not state["killed"] and elapsed >= redis_kill_at:
                docker("kill", args.redis_container)
                state["killed"] = True
                events.append({"atSec": round(elapsed, 1), "event": "redis killed"})
                print(f"[{run_id}] redis killed at {elapsed:.1f}s", flush=True)
            if redis_restart_at is not None and state["killed"] and not state["restarted"] and elapsed >= redis_restart_at:
                docker("start", args.redis_container)
                state["restarted"] = True
                events.append({"atSec": round(elapsed, 1), "event": "redis restarted"})
                print(f"[{run_id}] redis restarted at {elapsed:.1f}s", flush=True)

        wall = load.run(on_tick=tick)
        time.sleep(1.0)  # 마지막 afterCompletion 기록이 끝나도록
        summary = load.summary(wall)
        per_wallet = [wallet_state(args.db_container, w["walletId"], args.initial_balance, args.amount) for w in wallets]
        holds = hold_stats(args.db_container, run_id)
        rejected = sum(v for k, v in holds["outcomes"].items() if k.startswith("FENCE_REJECTED"))
        result = {
            "experiment": name, "variant": variant, "run": run_index, "runId": run_id,
            "env": env, "load": {"threads": args.threads, "wallets": args.wallets, "durationSec": args.duration,
                                 "initialBalance": args.initial_balance, "amount": args.amount},
            "pauses": state["pauses"], "pauseEverySec": pause_every, "pauseSeconds": pause_seconds,
            "events": events,
            "client": summary,
            "wallets": per_wallet,
            "totalDrift": sum(w["drift"] for w in per_wallet),
            "walletsWithDrift": sum(1 for w in per_wallet if w["drift"] != 0),
            "excessApprovals": sum(w["excessApprovals"] for w in per_wallet),
            "walletsLedgerNegative": sum(1 for w in per_wallet if w["ledgerNegative"]),
            "holds": holds,
            "fenceRejected": rejected,
        }
        print(f"[{run_id}] requests={summary['requests']} tps={summary['throughputPerSec']} p95={summary['p95Ms']}ms "
              f"ownedOverlaps={holds['ownedOverlappingPairs']} maxOwners={holds['maxSimultaneousOwners']} "
              f"drift={result['totalDrift']} excess={result['excessApprovals']} fenceRejected={rejected} "
              f"release={holds['releaseResults']}", flush=True)
        return result
    finally:
        app.stop()
        if redis_kill_at is not None:
            docker("start", args.redis_container, check=False)
            time.sleep(1)


def redis_up(args):
    env = dict(os.environ, PARITYPAY_REDIS_PORT=str(args.redis_port))
    subprocess.run(["docker", "compose", "up", "-d", "redis"], check=True, env=env, capture_output=True)
    for _ in range(30):
        ping = docker("exec", args.redis_container, "redis-cli", "ping", check=False)
        if ping.stdout.strip() == "PONG":
            return
        time.sleep(0.5)
    raise RuntimeError("redis did not answer PING")


def redis_down(args):
    subprocess.run(["docker", "compose", "rm", "-sf", "redis"], check=False, capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("experiment", choices=["lease", "watchdog", "fencing", "control", "redisdown"])
    parser.add_argument("--jar", required=True)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--first-run", type=int, default=1, help="유실된 회차만 다시 돌릴 때 번호를 잇습니다")
    parser.add_argument("--variants", help="쉼표로 구분. 비우면 실험의 기본 변형 전부")
    parser.add_argument("--port", type=int, default=8180)
    parser.add_argument("--db-url", default="jdbc:postgresql://localhost:5432/paritypay")
    parser.add_argument("--kafka", default="localhost:9092")
    parser.add_argument("--db-container", default="paritypay-postgres")
    parser.add_argument("--redis-container", default="paritypay-redis")
    parser.add_argument("--redis-port", type=int, default=6379)
    parser.add_argument("--threads", type=int, default=16)
    parser.add_argument("--wallets", type=int, default=4)
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--initial-balance", type=int, default=60_000)
    parser.add_argument("--amount", type=int, default=1_000)
    parser.add_argument("--ttl", default="200ms")
    parser.add_argument("--hold-min", type=int, default=300)
    parser.add_argument("--hold-max", type=int, default=500)
    parser.add_argument("--keep-redis", action="store_true")
    parser.add_argument("--log-dir", default="/tmp/lock-lease")
    parser.add_argument("--out", default="/tmp/lock-lease")
    args = parser.parse_args()
    Path(args.log_dir).mkdir(parents=True, exist_ok=True)
    Path(args.out).mkdir(parents=True, exist_ok=True)
    out = Path(args.out) / f"{args.experiment}-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.json"
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()

    plans = {
        # (a) lease 200ms < 락 안 300~500ms. 두 소유자가 동시에 씁니다.
        "lease": {
            "plain": dict(app_env={"EXPERIMENT_LOCK_MODE": "LOCK_PLAIN"}),
        },
        # (b) Watchdog. 느린 트랜잭션(같은 300~500ms)의 만료는 막지만, 프로세스가 멈추면(SIGSTOP 400ms, 1.5초마다)
        #     연장 스레드도 함께 멈춥니다. 두 변형의 차이는 정지 신호뿐입니다.
        "watchdog": {
            "renewing": dict(app_env={"EXPERIMENT_LOCK_MODE": "LOCK_PLAIN", "EXPERIMENT_LOCK_WATCHDOG": "true"}),
            "stop-the-world": dict(app_env={"EXPERIMENT_LOCK_MODE": "LOCK_PLAIN", "EXPERIMENT_LOCK_WATCHDOG": "true"},
                                   pause_every=1.5, pause_seconds=0.4),
        },
        # (c) fencing. c-1은 흔히 설명되는 "쓰기 시점 토큰 비교", c-2는 읽는 순간 토큰을 새기는 것.
        "fencing": {
            "fence-at-write": dict(app_env={"EXPERIMENT_LOCK_MODE": "LOCK_FENCE_WRITE"}),
            "fence-at-read": dict(app_env={"EXPERIMENT_LOCK_MODE": "LOCK_FENCE_CLAIM"}),
        },
        # (d) 대조군. 분산락 없음, 기본 경로, 같은 sleep.
        "control": {
            "conditional-update": dict(app_env={"EXPERIMENT_LOCK_MODE": "CONTROL"}),
        },
        # (e) 부하 중 20초에 Redis kill, 40초에 재기동.
        "redisdown": {
            "fail-closed": dict(app_env={"EXPERIMENT_LOCK_MODE": "LOCK_PLAIN", "EXPERIMENT_LOCK_ON_REDIS_DOWN": "FAIL_CLOSED"},
                                redis_kill_at=20, redis_restart_at=40),
            "fail-open": dict(app_env={"EXPERIMENT_LOCK_MODE": "LOCK_PLAIN", "EXPERIMENT_LOCK_ON_REDIS_DOWN": "FAIL_OPEN"},
                              redis_kill_at=20, redis_restart_at=40),
            "fence-at-read": dict(app_env={"EXPERIMENT_LOCK_MODE": "LOCK_FENCE_CLAIM", "EXPERIMENT_LOCK_ON_REDIS_DOWN": "FAIL_CLOSED"},
                                  redis_kill_at=20, redis_restart_at=40),
            "control": dict(app_env={"EXPERIMENT_LOCK_MODE": "CONTROL"}, redis_kill_at=20, redis_restart_at=40),
        },
    }
    plan = plans[args.experiment]
    variants = args.variants.split(",") if args.variants else list(plan)

    redis_up(args)
    results = []
    try:
        for variant in variants:
            spec = plan[variant]
            for run_index in range(args.first_run, args.first_run + args.runs):
                result = run_variant(args, args.experiment, variant, spec["app_env"], run_index,
                                     pause_every=spec.get("pause_every"), pause_seconds=spec.get("pause_seconds"),
                                     redis_kill_at=spec.get("redis_kill_at"), redis_restart_at=spec.get("redis_restart_at"))
                results.append(result)
                out.write_text(json.dumps({"commit": commit, "args": vars(args), "results": results},
                                          indent=2, ensure_ascii=False))
    finally:
        if not args.keep_redis:
            redis_down(args)
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()

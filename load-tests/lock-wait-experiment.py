#!/usr/bin/env python3
"""M-007: 동일 지갑 경합에서 시간이 실제로 어디에서 소모되는가.

P-002는 같은 지갑에 20 VU를 몰면 처리량이 5.8배 떨어지고 p95가 8.7배가 된다는 것을 재고,
그 원인을 "잔액 행 잠금이 직렬화 지점"이라고 적었습니다. 그런데 그 문장은 **측정이 아니라
추론**입니다. 그 실험은 애플리케이션 바깥에서 응답 시간만 봤고, 보고서도 "측정하지 않은 것:
DB CPU·락 대기·slow query. pg_stat_statements를 켜지 않았습니다"라고 남겨 두었습니다.

이 실험은 같은 부하를 주면서 서버 안쪽을 봅니다.

1. `pg_stat_statements` — 어느 문장이 시간을 쓰는지, 그 문장의 평균 실행 시간이 경합에서
   얼마나 늘어나는지.
2. `pg_stat_activity` 10 ms 샘플링 — 백엔드가 무엇을 기다리는지(`Lock:transactionid`인지,
   I/O인지, 아무것도 아닌지).
3. HikariCP `pending` — DB 앞이 아니라 커넥션 풀 앞에 줄이 서는지.

세 가지를 함께 봐야 답이 갈립니다. 행 잠금이 원인이면 잔액 UPDATE의 평균 실행 시간이 늘고
대기가 `Lock`으로 잡힙니다. 반대로 DB 문장은 그대로인데 응답만 느리다면 병목은 DB 밖입니다.

사전 조건
- `docker compose up -d postgres mock-bank mock-pg` (postgres는 pg_stat_statements가 적재된
  상태여야 합니다. compose에 넣어 두었습니다)
- `./gradlew :apps:pay-api:bootJar`
- 호스트에 k6

사용:
    # 한 빌드만 보기
    python3 load-tests/lock-wait-experiment.py \
        --jar apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar

    # 두 빌드를 번갈아 3회씩. 순차 비교는 기계 상태 변화를 결과로 오해하게 만듭니다.
    python3 load-tests/lock-wait-experiment.py --reps 3 \
        --jar before=/tmp/m007-jars/before.jar --jar after=/tmp/m007-jars/after.jar

근거: reports/11 M-007, P-002, ADR-004
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
from pathlib import Path

CONTAINER = os.environ.get("PG_CONTAINER", "paritypay-postgres")
DB = "paritypay"
# 샘플러는 측정 대상 데이터베이스가 아니라 유지보수 DB에 붙습니다. 샘플 테이블 쓰기가
# 측정 대상 DB의 버퍼·오토배큠에 섞이지 않게 하기 위해서입니다.
PROBE_DB = "postgres"

# 부하 스크립트의 시나리오 시계입니다(payment-baseline.js).
# warmup 0~20s, measured 25s 시작, 20s 램프업 → 40s 유지 → 10s 램프다운.
# 20 VU가 유지되는 구간은 시나리오 시작 기준 45~85초이고, 그 안쪽만 측정합니다.
DEFAULT_WINDOW_START = 50
DEFAULT_WINDOW_SECONDS = 34

TRUNCATE_SQL = """
TRUNCATE refresh_token, login_attempt, audit_log, password_reset_token,
         reconciliation_mismatch, reconciliation_run,
         settlement_item, settlement, order_confirmation, merchant,
         ledger_entry, ledger_transaction, ledger_account,
         idempotency_record, payment_cancellation, payment, top_up,
         outbox_event, consumed_event, wallet_transaction,
         wallet_balance, bank_account, wallet CASCADE
"""

# 서버 안에서 도는 샘플러입니다. docker exec 왕복이 수십 ms이므로 클라이언트에서 10 ms 간격을
# 만들 수 없습니다. UNLOGGED 테이블이라 WAL을 남기지 않습니다.
SAMPLER_SQL = """
CREATE UNLOGGED TABLE IF NOT EXISTS wait_sample (
    sampled_at timestamptz,
    state text,
    wait_event_type text,
    wait_event text,
    query text
);
TRUNCATE wait_sample;
DO $$
DECLARE
    deadline timestamptz := clock_timestamp() + interval '%d seconds';
BEGIN
    WHILE clock_timestamp() < deadline LOOP
        INSERT INTO wait_sample
        SELECT clock_timestamp(), state, wait_event_type, wait_event, left(query, 200)
          FROM pg_stat_activity
         WHERE datname = '%s'
           AND pid <> pg_backend_pid()
           AND state IS DISTINCT FROM 'idle';
        PERFORM pg_sleep(0.01);
    END LOOP;
END $$;
"""

STATEMENTS_SQL = """
SELECT calls,
       round(total_exec_time::numeric, 1),
       round(mean_exec_time::numeric, 3),
       round(max_exec_time::numeric, 1),
       round(coalesce(shared_blk_read_time, 0)::numeric, 1),
       replace(left(query, 110), E'\n', ' ')
  FROM pg_stat_statements
 WHERE dbid = (SELECT oid FROM pg_database WHERE datname = '%s')
 ORDER BY total_exec_time DESC
 LIMIT 15
""" % DB

WAIT_PROFILE_SQL = """
SELECT coalesce(wait_event_type, '(running)') AS kind,
       coalesce(wait_event, '-') AS event,
       count(*) AS samples,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS pct
  FROM wait_sample
 GROUP BY 1, 2
 ORDER BY samples DESC
 LIMIT 12
"""

WAIT_BY_STATEMENT_SQL = """
SELECT coalesce(wait_event, '(none)') AS event,
       count(*) AS samples,
       replace(left(query, 80), E'\n', ' ') AS statement
  FROM wait_sample
 WHERE wait_event_type = 'Lock'
 GROUP BY 1, 3
 ORDER BY samples DESC
 LIMIT 8
"""


def psql(sql, db=DB, timeout=600):
    out = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "paritypay", "-d", db,
         # 문장 본문에 파이프가 들어갈 수 있으므로 구분자는 유닛 세퍼레이터를 씁니다.
         "-tA", "-q", "-F", "\x1f", "-c", sql],
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def rows(sql, db=DB):
    text = psql(sql, db=db)
    return [line.split("\x1f") for line in text.splitlines() if line]


class PoolSampler(threading.Thread):
    """HikariCP 지표를 초 단위로 남깁니다. 줄이 DB 앞이 아니라 풀 앞에 서는지를 봅니다."""

    def __init__(self, port):
        super().__init__(daemon=True)
        self.url = f"http://localhost:{port}/actuator/prometheus"
        self.samples = []
        self._stop = threading.Event()

    def run(self):
        while not self._stop.is_set():
            try:
                with urllib.request.urlopen(self.url, timeout=3) as response:
                    body = response.read().decode()
                wanted = {}
                for line in body.splitlines():
                    for key in ("hikaricp_connections_pending", "hikaricp_connections_active"):
                        if line.startswith(key + "{"):
                            wanted[key] = float(line.rsplit(" ", 1)[1])
                if wanted:
                    self.samples.append(wanted)
            except (urllib.error.URLError, OSError, ValueError, IndexError):
                pass
            self._stop.wait(1.0)

    def stop(self):
        self._stop.set()

    def summary(self):
        if not self.samples:
            return "(수집 실패)"
        pending = [s.get("hikaricp_connections_pending", 0.0) for s in self.samples]
        active = [s.get("hikaricp_connections_active", 0.0) for s in self.samples]
        return (
            f"active 평균 {sum(active) / len(active):.1f} / 최대 {max(active):.0f}, "
            f"pending 평균 {sum(pending) / len(pending):.1f} / 최대 {max(pending):.0f} "
            f"({len(self.samples)} 표본)"
        )


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
            # 트레이스 수집이 켜져 있으면 이 실험이 재는 지연에 내보내기 비용이 섞입니다.
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


def stop_app(process):
    if process.poll() is None:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()


def run_mode(mode, variant, jar, args, out_dir, tag):
    """한 부하 모드를 돌리고 측정 창 안의 서버 쪽 지표를 돌려줍니다."""
    same_wallet = mode == "same-wallet"
    print(f"\n########## {tag}")
    app_log = out_dir / f"app-{tag}.log"
    app = start_app(jar, args.port, app_log, args.java)
    try:
        # 실행마다 같은 조건에서 시작합니다. member는 비우지 않습니다. 비우면 부트스트랩 운영자
        # 계정까지 사라집니다.
        psql(TRUNCATE_SQL)

        pool = PoolSampler(args.port)
        pool.start()

        k6_out = open(out_dir / f"k6-{tag}.txt", "w")
        k6 = subprocess.Popen(
            [
                "k6", "run",
                "--summary-export", str(out_dir / f"k6-{tag}.json"),
                "-e", f"BASE_URL=http://localhost:{args.port}",
                "-e", f"SAME_WALLET={'true' if same_wallet else 'false'}",
                "load-tests/payment-baseline.js",
            ],
            stdout=k6_out,
            stderr=subprocess.STDOUT,
        )
        started = time.monotonic()

        # 20 VU가 유지되는 구간 안쪽만 봅니다. 램프업 구간이 섞이면 평균이 흐려집니다.
        time.sleep(max(0.0, args.window_start - (time.monotonic() - started)))
        psql("SELECT pg_stat_statements_reset()")
        pool.samples.clear()
        sampler = subprocess.Popen(
            ["docker", "exec", "-i", CONTAINER, "psql", "-U", "paritypay", "-d", PROBE_DB, "-q", "-c",
             SAMPLER_SQL % (args.window_seconds, DB)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        window_started = time.monotonic()

        time.sleep(max(0.0, args.window_seconds - (time.monotonic() - window_started)))
        statements = rows(STATEMENTS_SQL)
        pool_summary = pool.summary()
        pool.stop()
        sampler.wait(timeout=60)

        wait_profile = rows(WAIT_PROFILE_SQL, db=PROBE_DB)
        wait_by_statement = rows(WAIT_BY_STATEMENT_SQL, db=PROBE_DB)

        k6.wait(timeout=300)
        k6_out.close()
        summary = json.loads((out_dir / f"k6-{tag}.json").read_text())

        return {
            "tag": tag,
            "mode": mode,
            "variant": variant,
            "k6": summary,
            "statements": statements,
            "wait_profile": wait_profile,
            "wait_by_statement": wait_by_statement,
            "pool": pool_summary,
        }
    finally:
        stop_app(app)


def metric(summary, name, field):
    node = summary.get("metrics", {}).get(name, {})
    return node.get(field)


def approved_in_window(result):
    """측정 창 안에서 실제로 커밋된 결제 수입니다. payment INSERT 호출 수가 그것입니다."""
    for calls, _total, _mean, _max, _read, query in result["statements"]:
        if query.startswith("insert into payment "):
            return int(calls)
    return 0


def balance_update(result):
    """잔액 UPDATE의 (호출 수, 평균 실행 ms)입니다. 경합은 여기 평균에 나타납니다."""
    for calls, _total, mean, _max, _read, query in result["statements"]:
        if "available_amount = available_amount" in query:
            return int(calls), float(mean)
    return 0, 0.0


def lock_wait_pct(result):
    total = sum(int(row[2]) for row in result["wait_profile"]) or 1
    locked = sum(int(row[2]) for row in result["wait_profile"] if row[0] == "Lock")
    return 100.0 * locked / total


def render(result, window_seconds):
    lines = [f"===== {result['tag']} ====="]
    summary = result["k6"]
    approved = metric(summary, "paritypay_payments_approved", "count") or 0
    rate = metric(summary, "http_reqs", "rate") or 0.0
    duration = summary.get("metrics", {}).get("paritypay_payment_duration", {})
    lines.append(
        f"승인 {approved:.0f} / 전체 요청률 {rate:.1f} req/s / 결제 지연 "
        f"p50 {duration.get('med', 0):.0f} ms p95 {duration.get('p(95)', 0):.0f} ms "
        f"max {duration.get('max', 0):.0f} ms"
    )
    lines.append(f"커넥션 풀: {result['pool']}")

    lines.append("")
    lines.append(f"-- pg_stat_statements 상위 (측정 창 {window_seconds}초) --")
    lines.append(f"{'calls':>7} {'total_ms':>10} {'mean_ms':>9} {'max_ms':>9}  statement")
    db_busy = 0.0
    for row in result["statements"]:
        calls, total, mean, mx, read_ms, query = row
        db_busy += float(total)
        lines.append(f"{calls:>7} {total:>10} {mean:>9} {mx:>9}  {query}")
    lines.append(f"상위 문장 실행 시간 합계 {db_busy:.0f} ms = 벽시계 초당 {db_busy / window_seconds / 1000:.2f} 초분")

    lines.append("")
    lines.append("-- 대기 이벤트 (10 ms 샘플링) --")
    total_samples = sum(int(row[2]) for row in result["wait_profile"]) or 1
    for kind, event, samples, pct in result["wait_profile"]:
        lines.append(f"{samples:>7} ({pct:>5}%)  {kind}:{event}")
    lines.append(f"표본 합계 {total_samples}")

    if result["wait_by_statement"]:
        lines.append("")
        lines.append("-- Lock 대기 중이던 문장 --")
        for event, samples, statement in result["wait_by_statement"]:
            lines.append(f"{samples:>7}  {event:<16} {statement}")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--jar",
        action="append",
        required=True,
        metavar="LABEL=PATH",
        help="측정할 빌드입니다. 여러 번 주면 반복마다 순서를 바꿔 번갈아 돌립니다.",
    )
    parser.add_argument("--java", default=os.environ.get("JAVA_BIN", "java"))
    parser.add_argument("--port", type=int, default=8086)
    parser.add_argument("--out", default="/tmp/m007")
    parser.add_argument("--reps", type=int, default=1)
    parser.add_argument("--window-start", type=int, default=DEFAULT_WINDOW_START)
    parser.add_argument("--window-seconds", type=int, default=DEFAULT_WINDOW_SECONDS)
    parser.add_argument("--modes", default="different-wallet,same-wallet")
    args = parser.parse_args()

    variants = []
    for item in args.jar:
        label, _, path = item.partition("=")
        variants.append((label, path) if path else ("build", label))

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    # pg_stat_statements가 없으면 이 실험은 성립하지 않습니다. 조용히 빈 표를 내지 않습니다.
    if psql("SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'") != "1":
        print("pg_stat_statements가 없습니다. docker compose up -d postgres 로 다시 만드세요.", file=sys.stderr)
        return 1

    modes = [m.strip() for m in args.modes.split(",")]
    report = []
    results = []
    for rep in range(1, args.reps + 1):
        # 반복마다 빌드 순서를 뒤집습니다. 기계가 실행 중에 느려지거나 빨라져도 그 흐름이
        # 한쪽 빌드에만 몰리지 않게 하기 위해서입니다. 앞선 측정에서 순차 비교가 4.8배를
        # 만들어냈는데, 그 차이는 코드가 아니라 실행 순서였습니다.
        order = variants if rep % 2 == 1 else list(reversed(variants))
        for label, jar in order:
            for mode in modes:
                tag = f"rep{rep}-{label}-{mode}"
                result = run_mode(mode, label, jar, args, out_dir, tag)
                result["rep"] = rep
                text = render(result, args.window_seconds)
                print(text)
                report.append(text)
                results.append(result)
                # 앞 실행의 여파 위에서 다음 실행을 시작하지 않습니다.
                time.sleep(15)

    report.append(compare(results, args.window_seconds))
    print(report[-1])
    (out_dir / "report.txt").write_text("\n\n".join(report) + "\n")
    print(f"\n원본 결과: {out_dir}/report.txt")
    return 0


def compare(results, window_seconds):
    """빌드×모드별로 반복의 중앙값을 모읍니다. 한 번의 값으로 결론을 내지 않기 위해서입니다."""
    lines = ["===== 요약 (반복 중앙값) =====",
             f"{'빌드':<10} {'모드':<18} {'결제/초':>9} {'잔액UPDATE평균ms':>16} {'Lock대기%':>10} {'p95 ms':>8}  반복값(결제/초)"]
    groups = {}
    for r in results:
        groups.setdefault((r["variant"], r["mode"]), []).append(r)
    for (variant, mode), items in groups.items():
        rates = [approved_in_window(r) / window_seconds for r in items]
        means = [balance_update(r)[1] for r in items]
        locks = [lock_wait_pct(r) for r in items]
        p95s = [r["k6"].get("metrics", {}).get("paritypay_payment_duration", {}).get("p(95)", 0) for r in items]
        lines.append(
            f"{variant:<10} {mode:<18} {statistics.median(rates):>9.1f} {statistics.median(means):>16.1f} "
            f"{statistics.median(locks):>10.1f} {statistics.median(p95s):>8.0f}  "
            + ", ".join(f"{v:.1f}" for v in rates)
        )
    return "\n".join(lines)


if __name__ == "__main__":
    sys.exit(main())

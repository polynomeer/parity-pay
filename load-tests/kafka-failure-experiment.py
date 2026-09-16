#!/usr/bin/env python3
"""M-015~M-018: 브로커 쪽에서 나는 장애 네 가지를 직접 만들고, 무엇이 막히고 무엇이 안 막히는지 셉니다.

지금까지의 Kafka 실험은 우리 쪽 프로세스를 죽이는 것이었습니다 — 발행기 다중화(M-001), 소비자
SIGKILL 재분배(M-005). 여기서는 **설정을 기본값에서 벗어나게 하거나 브로커 자체를 죽여서** 네 가지를
만들고, 기본 설정과 같은 조건에서 대조합니다.

    duplicate  소비자가 처리 뒤 커밋 전에 죽음.  컨테이너 BATCH 커밋(기본) vs 클라이언트 자동 커밋
    loss       발행 도중 브로커 SIGKILL.          acks=all(기본) vs acks=1 vs acks=0
    order      같은 결제의 확정·취소를 다른 파티션으로. 파티션 키 = Aggregate(기본) vs 랜덤
    poison     파싱할 수 없는 봉투 하나를 파티션에 넣음. 같은 파티션의 뒤 이벤트가 얼마나 멈추는가

기본값에서 벗어나는 설정은 전부 `experiment-kafka` 프로필(`application-experiment-kafka.yml`)과
환경변수로만 들어갑니다. 코드는 바꾸지 않습니다. 랜덤 파티션 키는 Outbox 표에 직접 적재하는 것으로
만듭니다 — 실제 발행 경로(`JdbcOutboxAppender`)는 Aggregate ID를 쓰고, 그 열이 곧 브로커의 키입니다.

**증명하지 못한 실행을 성공으로 세지 않습니다.** 재현하려던 현상(중복·유실·역전·정지)이 0건이면
그 실행은 "증명하지 못함"으로 표시합니다(M-005 관례).

사전 조건: docker compose로 postgres·redpanda가 떠 있고, pay-api bootJar가 있어야 합니다.
dev.sh로 띄운 pay-api가 살아 있으면 먼저 내립니다 — 같은 토픽을 소비해 측정을 오염시킵니다.

사용:
    J=apps/pay-api/build/libs/pay-api-0.1.0-SNAPSHOT.jar
    python3 load-tests/kafka-failure-experiment.py duplicate --jar $J --commit batch --runs 3
    python3 load-tests/kafka-failure-experiment.py duplicate --jar $J --commit auto  --runs 3
    python3 load-tests/kafka-failure-experiment.py loss      --jar $J --acks all,1,0 --runs 3
    python3 load-tests/kafka-failure-experiment.py order     --jar $J --key aggregate,random --runs 3
    python3 load-tests/kafka-failure-experiment.py poison    --jar $J --runs 3

근거: reports/11 M-015~M-018, ADR-005, ADR-006, docs/05-technical-design.md §9
"""

import argparse
import json
import os
import signal
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

TOPIC_DEFAULT = "paritypay.events"
PROJECTION_GROUP = "paritypay-wallet-projection"
SETTLEMENT_GROUP = "paritypay-settlement"


# ---------- 바깥 도구 ----------

def psql(container, sql):
    # SQL은 표준 입력으로 넘깁니다. 인자로 넘기면 ID 수천 개가 든 IN 목록에서 argv 길이 한계에 걸립니다.
    out = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "paritypay", "-d", "paritypay", "-tA", "-q",
         "-v", "ON_ERROR_STOP=1"],
        input=sql, capture_output=True, text=True,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def count(container, sql):
    return int(psql(container, sql) or 0)


def rpk(broker, *args, check=False):
    out = subprocess.run(["docker", "exec", broker, "rpk", *args], capture_output=True, text=True)
    if check and out.returncode != 0:
        raise RuntimeError(out.stderr.strip() or out.stdout.strip())
    return out


def docker(*args):
    return subprocess.run(["docker", *args], capture_output=True, text=True)


def now_utc():
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


class App:
    """pay-api 한 대. 실험 프로필과 환경변수로 설정 하나를 벗어나게 합니다."""

    def __init__(self, jar, port, db_url, kafka, log_path, extra_env=None):
        self.jar, self.port, self.db_url, self.kafka, self.log_path = jar, port, db_url, kafka, log_path
        self.extra_env = extra_env or {}
        self.process = None

    def start(self):
        env = dict(os.environ)
        env.update({
            "SPRING_PROFILES_ACTIVE": "local,experiment-kafka",
            "PARITYPAY_PORT": str(self.port),
            "PARITYPAY_DB_URL": self.db_url,
            "PARITYPAY_KAFKA_SERVERS": self.kafka,
            "PARITYPAY_TRACE_SAMPLING": "0",
            # 복구·대사 작업은 이 실험과 무관하고 로그만 늘립니다.
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
                raise RuntimeError(f"port {self.port} exited during startup; see {self.log_path}")
            time.sleep(1)
        raise RuntimeError(f"port {self.port} did not start in time")

    def kill(self):
        os.kill(self.process.pid, signal.SIGKILL)
        self.process.wait()

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

    def log_count(self, needle):
        return self.read_log().count(needle)


# ---------- 적재 ----------

def reset_tables(container):
    psql(container, "TRUNCATE outbox_event, consumed_event, wallet_transaction, settlement_item CASCADE")
    try:
        psql(container, "TRUNCATE dead_letter_event")
    except RuntimeError:
        pass  # 결함 M 수정 전 스키마


def reset_topic(broker, topic):
    rpk(broker, "topic", "delete", topic)
    rpk(broker, "topic", "delete", topic + ".dlt")
    time.sleep(1)
    rpk(broker, "topic", "create", topic, "-p", "3", "-r", "1", check=True)


def seed_top_ups(container, events, lead_seconds=0):
    """거래내역을 만드는 실제 이벤트로 적재합니다(M-005와 같음). 파티션 키 = 지갑 ID."""
    psql(container, f"""
        WITH seeded AS (
            SELECT n, gen_random_uuid() AS wallet_id, gen_random_uuid() AS top_up_id
              FROM generate_series(1, {events}) AS n
        )
        INSERT INTO outbox_event
            (event_id, event_type, event_version, aggregate_type, aggregate_id, partition_key,
             payload, trace_id, status, attempt_count, next_attempt_at, occurred_at, created_at)
        SELECT gen_random_uuid(), 'TopUpCompleted', 1, 'TopUp', wallet_id::text, wallet_id::text,
               jsonb_build_object('topUpId', top_up_id::text, 'walletId', wallet_id::text,
                                  'amount', 1000, 'currency', 'KRW',
                                  'ledgerTransactionId', gen_random_uuid()::text),
               NULL, 'PENDING', 0, now() + interval '{lead_seconds} second',
               now() + (n * interval '1 microsecond'), now()
          FROM seeded
    """)
    keys = count(container, "SELECT count(DISTINCT partition_key) FROM outbox_event")
    if keys < events:
        raise RuntimeError(f"적재가 잘못됐습니다: 파티션 키 {keys}종 (기대 {events} 이상)")


def seed_confirm_then_cancel(container, payments, key_mode, lead_seconds=3):
    """결제마다 구매확정(10,000원 정산 가능) 뒤 4,000원 취소. 결함 F가 고친 바로 그 조건입니다.

    key_mode='aggregate'면 둘 다 결제 ID가 파티션 키(설계), 'random'이면 서로 다른 랜덤 키(실험).
    확정이 먼저 일어난 사실(occurred_at)은 두 경우 모두 같습니다 — 다른 것은 키뿐입니다.
    """
    confirm_key = "payment_id::text" if key_mode == "aggregate" else "gen_random_uuid()::text"
    cancel_key = "payment_id::text" if key_mode == "aggregate" else "gen_random_uuid()::text"
    psql(container, f"""
        WITH seeded AS (
            SELECT n, gen_random_uuid() AS payment_id, gen_random_uuid() AS merchant_id,
                   gen_random_uuid() AS wallet_id, gen_random_uuid() AS cancellation_id
              FROM generate_series(1, {payments}) AS n
        ), confirmed AS (
            INSERT INTO outbox_event
                (event_id, event_type, event_version, aggregate_type, aggregate_id, partition_key,
                 payload, trace_id, status, attempt_count, next_attempt_at, occurred_at, created_at)
            SELECT gen_random_uuid(), 'OrderConfirmed', 1, 'Payment', payment_id::text, {confirm_key},
                   jsonb_build_object('orderId', 'order-' || n, 'paymentId', payment_id::text,
                                      'merchantId', merchant_id::text, 'settleableAmount', 10000,
                                      'currency', 'KRW', 'confirmedAt', to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS"Z"')),
                   NULL, 'PENDING', 0, now() + interval '{lead_seconds} second',
                   now() + (n * interval '2 microsecond'), now()
              FROM seeded
        )
        INSERT INTO outbox_event
            (event_id, event_type, event_version, aggregate_type, aggregate_id, partition_key,
             payload, trace_id, status, attempt_count, next_attempt_at, occurred_at, created_at)
        SELECT gen_random_uuid(), 'PaymentCancellationCompleted', 1, 'Payment', payment_id::text, {cancel_key},
               jsonb_build_object('cancellationId', cancellation_id::text, 'paymentId', payment_id::text,
                                  'walletId', wallet_id::text, 'merchantId', merchant_id::text,
                                  'amount', 4000, 'currency', 'KRW',
                                  'ledgerTransactionId', gen_random_uuid()::text),
               NULL, 'PENDING', 0, now() + interval '{lead_seconds} second',
               now() + (n * interval '2 microsecond') + interval '1 microsecond', now()
          FROM seeded
    """)


# ---------- 관찰 ----------

def topic_high_watermarks(broker, topic):
    out = rpk(broker, "topic", "describe", topic, "-p")
    marks = {}
    for line in out.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 6 and parts[0].isdigit() and parts[-1].isdigit():
            marks[int(parts[0])] = int(parts[-1])
    return marks


def read_topic_event_ids(broker, topic):
    """브로커에 실제로 들어간 eventId를 전부 읽습니다. 파싱 안 되는 레코드는 따로 셉니다."""
    total = sum(topic_high_watermarks(broker, topic).values())
    if total == 0:
        return [], 0
    out = subprocess.run(
        ["docker", "exec", broker, "rpk", "topic", "consume", topic, "-o", "start", "-n", str(total), "-f", "%v\n"],
        capture_output=True, text=True)
    ids, unparsable = [], 0
    for line in out.stdout.splitlines():
        if not line.strip():
            continue
        try:
            ids.append(json.loads(line)["eventId"])
        except (json.JSONDecodeError, KeyError, TypeError):
            unparsable += 1
    return ids, unparsable


def group_lag(broker, group):
    """소비자 그룹의 파티션별 lag."""
    out = rpk(broker, "group", "describe", group)
    lag = {}
    for line in out.stdout.splitlines():
        parts = line.split()
        # TOPIC PARTITION CURRENT-OFFSET LOG-START-OFFSET LOG-END-OFFSET LAG ...
        if len(parts) >= 6 and parts[1].isdigit():
            try:
                lag[int(parts[1])] = int(parts[5])
            except ValueError:
                pass
    return lag


def wait_until(predicate, timeout, interval=0.5, stable_for=0.0):
    deadline = time.time() + timeout
    stable_since = None
    while time.time() < deadline:
        if predicate():
            stable_since = stable_since or time.time()
            if time.time() - stable_since >= stable_for:
                return True
        else:
            stable_since = None
        time.sleep(interval)
    return False


# ---------- M-015 중복: BATCH 커밋 vs 자동 커밋 ----------

def run_duplicate(args, run_index, commit):
    container, broker = args.container, args.broker_container
    reset_tables(container)
    reset_topic(broker, args.topic)
    extra = {"SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS": str(args.max_poll_records)}
    if commit == "auto":
        extra["EXPERIMENT_CONSUMER_AUTO_COMMIT"] = "true"
    apps = [App(args.jar, args.base_port + i, args.db_url, args.kafka,
                os.path.join(args.log_dir, f"dup-{commit}-{run_index}-{i}.log"), extra) for i in range(2)]
    try:
        for app in apps:
            app.start()
        seed_top_ups(container, args.events)
        if not wait_until(lambda: count(container, "SELECT count(*) FROM wallet_transaction") > 0, 60):
            raise RuntimeError("소비가 시작되지 않았습니다")
        time.sleep(args.kill_after)
        before_kill = count(container, "SELECT count(*) FROM wallet_transaction")
        apps[1].kill()
        # 유실이 있으면 rows가 events에 닿지 않습니다. 죽은 인스턴스의 파티션은 세션 시간(45초)이 지나야
        # 재분배되므로, "멈췄다"는 판단은 그보다 훨씬 긴 창(90초)이 필요합니다. 짧게 잡으면 재분배
        # 전에 끝났다고 보고 살아남은 쪽을 내려 버려 유실처럼 보입니다 — 처음에 30초로 두고 그 실수를 했습니다.
        last = {"rows": -1, "at": time.time()}

        def settled():
            rows = count(container, "SELECT count(*) FROM wallet_transaction")
            pending = count(container, "SELECT count(*) FROM outbox_event WHERE status <> 'PUBLISHED'")
            if rows != last["rows"]:
                last["rows"], last["at"] = rows, time.time()
            return pending == 0 and (rows >= args.events or time.time() - last["at"] > 90)

        wait_until(settled, args.settle_timeout, interval=1.0, stable_for=10.0)
        rows = count(container, "SELECT count(*) FROM wallet_transaction")
        distinct = count(container,
                         "SELECT count(*) FROM (SELECT DISTINCT reference_type, reference_id FROM wallet_transaction) t")
        consumed = count(container,
                         f"SELECT count(*) FROM consumed_event WHERE consumer_name = 'wallet-transaction-projection'")
        duplicates = sum(a.log_count("skipping duplicate delivery") for a in apps)
        rebalances = sum(a.log_count("partitions assigned") for a in apps)
        result = {
            "experiment": "duplicate", "commit": commit, "run": run_index,
            "events": args.events, "rowsBeforeKill": before_kill, "rows": rows, "distinctRows": distinct,
            "consumed": consumed, "duplicateDeliveries": duplicates, "lost": args.events - rows,
            "rebalances": rebalances, "maxPollRecords": args.max_poll_records,
        }
        # 둘 다 "중복이 실제로 왔는가"가 증명 조건입니다(M-005 관례). 유실은 별도 열로 봅니다.
        result["proved"] = duplicates > 0
        return result
    finally:
        for app in apps:
            app.stop()


# ---------- M-016 유실: acks=all / 1 / 0 + 브로커 SIGKILL ----------

def run_loss(args, run_index, acks):
    container, broker = args.container, args.broker_container
    reset_tables(container)
    reset_topic(broker, args.topic)
    extra = {
        "EXPERIMENT_PRODUCER_ACKS": acks,
        "EXPERIMENT_PRODUCER_IDEMPOTENCE": "true" if acks == "all" else "false",
        # 브로커가 돌아온 뒤 재시도가 5분 백오프에 걸려 있으면 실험이 끝나지 않습니다. 재시도 간격만
        # 줄입니다 — 유실 여부와 무관합니다.
        "PARITYPAY_EVENTS_BASE_BACKOFF": "1s",
        "PARITYPAY_EVENTS_MAX_BACKOFF": "3s",
        "PARITYPAY_EVENTS_MAX_ATTEMPTS": "50",
    }
    app = App(args.jar, args.base_port, args.db_url, args.kafka,
              os.path.join(args.log_dir, f"loss-{acks}-{run_index}.log"), extra)
    try:
        app.start()
        seed_top_ups(container, args.events, lead_seconds=3)
        published = lambda: count(container, "SELECT count(*) FROM outbox_event WHERE status = 'PUBLISHED'")
        if not wait_until(lambda: published() >= args.kill_at, 120, interval=0.05):
            raise RuntimeError("발행이 kill 지점까지 진행되지 않았습니다")
        published_at_kill = published()
        kill_started = time.time()
        docker("kill", "-s", "KILL", broker)
        time.sleep(args.broker_down_seconds)
        docker("start", broker)
        if not wait_until(lambda: rpk(broker, "cluster", "health").returncode == 0, 120, interval=1.0):
            raise RuntimeError("브로커가 다시 뜨지 않았습니다")
        broker_back = time.time()
        # 발행기가 남은 것을 전부 처리할 때까지. FAILED로 끝난 것은 그대로 셉니다.
        wait_until(lambda: count(container, "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'") == 0,
                   args.settle_timeout, interval=1.0, stable_for=5.0)
        status_counts = dict(
            line.split("|") for line in psql(
                container, "SELECT status, count(*) FROM outbox_event GROUP BY status").splitlines())
        status_counts = {k: int(v) for k, v in status_counts.items()}
        published_ids = set(psql(container,
                                 "SELECT event_id FROM outbox_event WHERE status = 'PUBLISHED'").splitlines())
        broker_ids, unparsable = read_topic_event_ids(broker, args.topic)
        broker_set = set(broker_ids)
        published_but_missing = published_ids - broker_set
        in_broker_not_published = broker_set - published_ids
        broker_duplicates = len(broker_ids) - len(broker_set)
        send_failures = app.log_count("failed to publish") + app.log_count("NotLeaderOrFollower") + \
            app.log_count("NetworkException") + app.log_count("TimeoutException")
        result = {
            "experiment": "loss", "acks": acks, "run": run_index, "events": args.events,
            "publishedAtKill": published_at_kill, "brokerDownSeconds": round(broker_back - kill_started, 1),
            "outboxStatus": status_counts,
            "brokerRecords": len(broker_ids), "brokerDistinct": len(broker_set), "brokerDuplicates": broker_duplicates,
            "publishedButMissing": len(published_but_missing),
            "inBrokerNotPublished": len(in_broker_not_published),
            "unparsable": unparsable, "logFailures": send_failures,
        }
        # 대조군(acks=all)은 유실이 없어야 증명이고, 실험군(1·0)은 유실이 있어야 증명입니다.
        if acks == "all":
            result["proved"] = len(published_but_missing) == 0 and broker_duplicates == 0
        else:
            result["proved"] = len(published_but_missing) > 0 or broker_duplicates > 0
        return result
    finally:
        app.stop()


# ---------- M-017 순서 역전: 파티션 키 aggregate vs random ----------

def run_order(args, run_index, key_mode):
    container, broker = args.container, args.broker_container
    reset_tables(container)
    reset_topic(broker, args.topic)
    app = App(args.jar, args.base_port, args.db_url, args.kafka,
              os.path.join(args.log_dir, f"order-{key_mode}-{run_index}.log"))
    try:
        app.start()
        seed_confirm_then_cancel(container, args.payments, key_mode)
        expected_consumed = args.payments * 2
        wait_until(lambda: count(container,
                                 f"SELECT count(*) FROM consumed_event WHERE consumer_name = 'settlement-item-builder'")
                   >= expected_consumed, args.settle_timeout, interval=1.0, stable_for=5.0)
        # 도착 순서: 정산 소비자가 취소를 확정보다 먼저 처리한 결제 수
        inversions = count(container, """
            SELECT count(*) FROM (
              SELECT o1.aggregate_id,
                     max(CASE WHEN o1.event_type = 'OrderConfirmed' THEN c.consumed_at END) AS confirmed_at,
                     max(CASE WHEN o1.event_type = 'PaymentCancellationCompleted' THEN c.consumed_at END) AS canceled_at
                FROM outbox_event o1
                JOIN consumed_event c ON c.event_id = o1.event_id AND c.consumer_name = 'settlement-item-builder'
               GROUP BY o1.aggregate_id
            ) t WHERE canceled_at < confirmed_at""")
        # 금액: 결제별 정산 항목 합. 올바르면 10,000 − 1,000 − 4,000 + 400 = 5,400, 취소가 버려지면 9,000
        sums = psql(container,
                    "SELECT coalesce(sum(amount),0) AS net, count(*) FROM settlement_item GROUP BY payment_id").splitlines()
        nets = [int(s.split("|")[0]) for s in sums]
        correct = sum(1 for n in nets if n == 5400)
        overpaid = sum(1 for n in nets if n == 9000)
        other = len(nets) - correct - overpaid
        dropped_cancels = app.log_count("nothing to reverse")
        result = {
            "experiment": "order", "partitionKey": key_mode, "run": run_index, "payments": args.payments,
            "eventsConsumed": count(container,
                                    "SELECT count(*) FROM consumed_event WHERE consumer_name = 'settlement-item-builder'"),
            "arrivalInversions": inversions, "droppedCancellations": dropped_cancels,
            "paymentsCorrect5400": correct, "paymentsOverpaid9000": overpaid, "paymentsOther": other,
            "overpaidTotalWon": overpaid * 3600,
        }
        result["proved"] = inversions > 0 if key_mode == "random" else True
        return result
    finally:
        app.stop()


# ---------- M-018 poison message ----------

def run_poison(args, run_index):
    container, broker = args.container, args.broker_container
    reset_tables(container)
    reset_topic(broker, args.topic)
    app = App(args.jar, args.base_port, args.db_url, args.kafka,
              os.path.join(args.log_dir, f"poison-{run_index}.log"))
    try:
        app.start()
        # 먼저 정상 이벤트를 흘려 소비자가 붙게 합니다.
        seed_top_ups(container, 100)
        wait_until(lambda: count(container, "SELECT count(*) FROM wallet_transaction") >= 100, 60)
        marks_before = topic_high_watermarks(broker, args.topic)
        # 파티션 1에 파싱할 수 없는 레코드 하나를 넣습니다.
        poison_at = time.time()
        poison_at_iso = datetime.fromtimestamp(poison_at, timezone.utc).isoformat()
        subprocess.run(["docker", "exec", "-i", broker, "rpk", "topic", "produce", args.topic, "-p", "1"],
                       input="this is not a json envelope\n", capture_output=True, text=True, check=True)
        # 그 뒤로 정상 이벤트를 더 넣습니다. 키가 지갑 ID라 3개 파티션에 고루 갑니다.
        seed_top_ups(container, args.events)
        total_expected = 100 + args.events
        # 파티션 1의 lag가 0이 되는 순간 = poison을 지나간 순간
        stall_seconds = None
        deadline = time.time() + args.settle_timeout
        while time.time() < deadline:
            lag = group_lag(broker, PROJECTION_GROUP)
            marks = topic_high_watermarks(broker, args.topic)
            if lag.get(1, 1) == 0 and marks.get(1, 0) > marks_before.get(1, 0) + 1:
                stall_seconds = time.time() - poison_at
                break
            time.sleep(0.2)
        wait_until(lambda: count(container, "SELECT count(*) FROM wallet_transaction") >= total_expected,
                   args.settle_timeout, interval=1.0, stable_for=5.0)
        rows = count(container, "SELECT count(*) FROM wallet_transaction")
        # 정밀한 정지 시간: poison 뒤에 파티션 1에 들어간 첫 정상 레코드가 발행부터 소비까지 걸린 시간을,
        # 다른 파티션의 같은 시점 레코드와 비교합니다. 하니스의 폴링 해상도와 적재 시간이 섞이지 않습니다.
        latency = partition_latencies(container, broker, args.topic, poison_at_iso)
        error_lines = app.log_count("Error handler threw an exception") + app.log_count("Backoff")
        skipped = app.log_count("Skipping seek of") + app.log_count("skipping")
        try:
            dead_letter_rows = count(container, "SELECT count(*) FROM dead_letter_event")
        except RuntimeError:
            dead_letter_rows = None  # 결함 M 수정 전 스키마
        dlt_marks = topic_high_watermarks(broker, args.topic + ".dlt")
        result = {
            "experiment": "poison", "run": run_index, "validEvents": total_expected, "rows": rows,
            "deadLetterRows": dead_letter_rows, "dltRecords": sum(dlt_marks.values()) if dlt_marks else 0,
            "lost": total_expected - rows, "partition1StallSeconds": None if stall_seconds is None else round(stall_seconds, 2),
            "errorHandlerLogLines": error_lines, "retryAttemptsLogged": app.log_count("JsonParseException") +
            app.log_count("StreamReadException") + app.log_count("JacksonException"),
            "deadLetterTopic": args.topic + ".dlt", "retryTopic": "없음",
            "latencyAfterPoisonMs": latency,
        }
        result["proved"] = stall_seconds is not None
        return result
    finally:
        app.stop()


def partition_latencies(container, broker, topic, poison_at_iso):
    """파티션별 (소비 시각 − 발행 시각) ms — poison 뒤에 발행된 레코드만. 파티션 1의 첫 레코드 값이
    정지 시간이고, 다른 파티션의 중앙값이 기준선입니다."""
    total = sum(topic_high_watermarks(broker, topic).values())
    out = subprocess.run(
        ["docker", "exec", broker, "rpk", "topic", "consume", topic, "-o", "start", "-n", str(total),
         "-f", "%p\t%v\n"], capture_output=True, text=True)
    ids_by_partition = {}
    for line in out.stdout.splitlines():
        parts = line.split("\t", 1)
        if len(parts) != 2:
            continue
        try:
            event_id = json.loads(parts[1])["eventId"]
        except (json.JSONDecodeError, KeyError, TypeError):
            continue
        ids_by_partition.setdefault(int(parts[0]), []).append(event_id)
    result = {}
    for partition, ids in sorted(ids_by_partition.items()):
        id_list = ",".join(f"'{i}'" for i in ids)
        rows = psql(container, f"""
            SELECT round(extract(epoch FROM (c.consumed_at - o.published_at)) * 1000)
              FROM outbox_event o JOIN consumed_event c ON c.event_id = o.event_id
             WHERE c.consumer_name = 'wallet-transaction-projection'
               AND o.event_id IN ({id_list})
               AND o.published_at > '{poison_at_iso}'::timestamptz
             ORDER BY o.published_at""").splitlines()
        values = [int(float(r)) for r in rows if r]
        if values:
            values_sorted = sorted(values)
            result[f"p{partition}"] = {"first": values[0], "median": values_sorted[len(values_sorted) // 2],
                                       "max": values_sorted[-1], "n": len(values)}
    return result


# ---------- 진입 ----------

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("experiment", choices=["duplicate", "loss", "order", "poison"])
    parser.add_argument("--jar", required=True)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--events", type=int, default=20000)
    parser.add_argument("--payments", type=int, default=2000)
    parser.add_argument("--commit", default="batch", help="duplicate: batch | auto (쉼표 구분)")
    parser.add_argument("--acks", default="all", help="loss: all | 1 | 0 (쉼표 구분)")
    parser.add_argument("--key", default="aggregate", help="order: aggregate | random (쉼표 구분)")
    parser.add_argument("--max-poll-records", type=int, default=2000)
    parser.add_argument("--kill-after", type=float, default=3.0)
    parser.add_argument("--kill-at", type=int, default=5000, help="loss: PUBLISHED가 이 수에 닿으면 브로커를 죽입니다")
    parser.add_argument("--broker-down-seconds", type=float, default=5.0)
    parser.add_argument("--settle-timeout", type=float, default=600.0)
    parser.add_argument("--base-port", type=int, default=8180)
    parser.add_argument("--db-url", default="jdbc:postgresql://localhost:5432/paritypay")
    parser.add_argument("--kafka", default="localhost:9092")
    parser.add_argument("--container", default="paritypay-postgres")
    parser.add_argument("--broker-container", default="paritypay-redpanda")
    parser.add_argument("--topic", default=TOPIC_DEFAULT)
    parser.add_argument("--log-dir", default="/tmp/kafka-failure")
    parser.add_argument("--out", default="/tmp/kafka-failure")
    args = parser.parse_args()

    Path(args.log_dir).mkdir(parents=True, exist_ok=True)
    Path(args.out).mkdir(parents=True, exist_ok=True)
    out = Path(args.out) / f"{args.experiment}-{now_utc()}.json"
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    results = []

    def record(r):
        r["commitSha"] = commit
        results.append(r)
        out.write_text(json.dumps(results, ensure_ascii=False, indent=2))
        flag = "" if r.get("proved", True) else "  ← 증명하지 못함"
        print("  " + json.dumps({k: v for k, v in r.items() if k not in ("commitSha",)}, ensure_ascii=False) + flag,
              flush=True)

    print(f"== {args.experiment} × {args.runs}, 커밋 {commit}, 결과 {out}", flush=True)
    if args.experiment == "duplicate":
        for mode in args.commit.split(","):
            print(f"## 커밋 방식 {mode}", flush=True)
            for i in range(args.runs):
                record(run_duplicate(args, i, mode))
    elif args.experiment == "loss":
        for acks in args.acks.split(","):
            print(f"## acks={acks}", flush=True)
            for i in range(args.runs):
                record(run_loss(args, i, acks))
    elif args.experiment == "order":
        for key in args.key.split(","):
            print(f"## 파티션 키 {key}", flush=True)
            for i in range(args.runs):
                record(run_order(args, i, key))
    else:
        for i in range(args.runs):
            record(run_poison(args, i))
    print(f"== 끝. {out}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""M-005: 실제 재분배(rebalance) 중 중복 전달.

ADR-006은 전달이 at-least-once이고 중복은 소비자가 흡수한다고 주장합니다. 지금까지의 시험은 두
가지였습니다 — 같은 봉투를 차례로 세 번 넣기(F-004), 여덟 트랜잭션에서 동시에 소비하기(M-002).
둘 다 중복을 **우리가 만들어서** 넣은 것입니다.

여기서는 만들지 않습니다. 소비자 두 대를 띄우고 소비 도중 한 대를 SIGKILL로 죽입니다. Kafka가
재분배하면서 커밋되지 않은 오프셋을 살아남은 인스턴스에 다시 배달하고, 그때 생기는 중복이 진짜
중복입니다.

확인하는 것:

1. 중복이 실제로 발생했는가 — 발생하지 않았다면 이 시험은 아무것도 증명하지 못합니다.
   소비자가 남기는 "skipping duplicate delivery" 로그로 셉니다.
2. 그래도 결과가 한 번인가 — 거래내역 행 수가 이벤트 수와 정확히 같아야 합니다.
3. 유실이 없는가 — 죽은 인스턴스가 처리 중이던 것도 결국 처리되어야 합니다.

사전 조건: docker compose로 PostgreSQL·Redpanda가 떠 있고 bootJar가 빌드되어 있어야 합니다.
근거: reports/11 M-005, ADR-006
"""

import argparse
import json
import os
import signal
import subprocess
import time
import uuid

TOP_UP_EVENT = "TopUpCompleted"


def psql(container, sql):
    out = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "paritypay", "-d", "paritypay", "-tA", "-q", "-c", sql],
        capture_output=True,
        text=True,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


class Instance:
    def __init__(self, jar, port, db_url, kafka, log_path, max_poll_records):
        self.jar, self.port, self.db_url, self.kafka, self.log_path = jar, port, db_url, kafka, log_path
        self.max_poll_records = max_poll_records
        self.process = None

    def start(self):
        env = dict(os.environ)
        env.update({
            "SPRING_PROFILES_ACTIVE": "local",
            "PARITYPAY_PORT": str(self.port),
            "PARITYPAY_DB_URL": self.db_url,
            "PARITYPAY_KAFKA_SERVERS": self.kafka,
            "PARITYPAY_TRACE_SAMPLING": "0",
            # 오프셋은 poll 배치를 다 처리한 뒤 커밋됩니다. 배치가 짧으면 죽는 순간이 배치 사이에
            # 떨어져 미커밋 구간이 없고, 그러면 재분배가 일어나도 중복이 생기지 않습니다. 이 값은
            # 중복을 만들어내는 것이 아니라 운영에도 있는 창을 넓혀 관측 가능하게 하는 것입니다.
            "SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS": str(self.max_poll_records),
        })
        open(self.log_path, "w").close()
        log = open(self.log_path, "a")
        self.process = subprocess.Popen(
            [os.environ.get("JAVA", "java"), "-jar", self.jar], stdout=log, stderr=log, env=env
        )
        deadline = time.time() + 180
        while time.time() < deadline:
            if "Started ParityPayApplication" in self.read_log():
                return
            if self.process.poll() is not None:
                raise RuntimeError(f"port {self.port} exited during startup; see {self.log_path}")
            time.sleep(1)
        raise RuntimeError(f"port {self.port} did not start in time")

    def kill(self):
        """SIGKILL입니다. 정상 종료 훅이 돌면 오프셋이 깔끔하게 커밋되어 중복이 생기지 않습니다."""
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

    def duplicate_deliveries(self):
        return self.read_log().count("skipping duplicate delivery")

    def rebalances(self):
        # 파티션이 새로 할당될 때마다 spring-kafka가 남기는 줄입니다.
        return self.read_log().count("partitions assigned")


def seed(container, events):
    """거래내역을 만드는 실제 이벤트 종류로 적체를 만듭니다.

    BenchmarkEvent로는 안 됩니다. 프로젝션이 무시하므로 소비 이력만 남고 업무 결과가 생기지 않아,
    "결과가 정확히 한 번인가"를 확인할 수 없습니다.
    """
    # 지갑 ID를 CTE에서 행마다 만듭니다. LATERAL 안의 스칼라 하위질의로 만들면 PostgreSQL이 한 번만
    # 평가해 5,000행이 전부 같은 키가 됩니다. 처음에 그렇게 썼다가 발행이 18건/초로 떨어졌고,
    # 그것은 재분배와 무관한 head-of-line 직렬화였습니다(M-003의 K=1 값과 일치).
    psql(container, f"""
        WITH seeded AS (
            SELECT n, gen_random_uuid() AS wallet_id, gen_random_uuid() AS top_up_id
              FROM generate_series(1, {events}) AS n
        )
        INSERT INTO outbox_event
            (event_id, event_type, event_version, aggregate_type, aggregate_id, partition_key,
             payload, trace_id, status, attempt_count, next_attempt_at, occurred_at, created_at)
        SELECT gen_random_uuid(), '{TOP_UP_EVENT}', 1, 'TopUp', wallet_id::text, wallet_id::text,
               jsonb_build_object(
                   'topUpId', top_up_id::text,
                   'walletId', wallet_id::text,
                   'amount', 1000,
                   'currency', 'KRW',
                   'ledgerTransactionId', gen_random_uuid()::text),
               NULL, 'PENDING', 0, now(), now() + (n * interval '1 microsecond'), now()
          FROM seeded
    """)
    keys = int(psql(container, "SELECT count(DISTINCT partition_key) FROM outbox_event"))
    if keys != events:
        raise RuntimeError(f"적재가 잘못됐습니다: 파티션 키가 {keys}종입니다(기대 {events}종)")


def count(container, sql):
    return int(psql(container, sql))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True)
    parser.add_argument("--events", type=int, default=4000)
    parser.add_argument("--base-port", type=int, default=8180)
    parser.add_argument("--db-url", default="jdbc:postgresql://localhost:5435/paritypay")
    parser.add_argument("--kafka", default="localhost:9092")
    parser.add_argument("--container", default="paritypay-postgres")
    parser.add_argument("--broker-container", default="paritypay-redpanda")
    parser.add_argument("--topic", default="paritypay.events")
    parser.add_argument("--max-poll-records", type=int, default=2000)
    parser.add_argument("--kill-after", type=float, default=3.0,
                        help="소비가 시작된 뒤 한 대를 죽이기까지 기다리는 시간(초)")
    parser.add_argument("--settle-timeout", type=float, default=300.0)
    parser.add_argument("--log-dir", default="/tmp")
    args = parser.parse_args()

    print("== 준비: 표와 토픽을 비우고 소비자 두 대를 띄웁니다")
    psql(args.container, "TRUNCATE outbox_event, consumed_event, wallet_transaction CASCADE")
    subprocess.run(["docker", "exec", args.broker_container, "rpk", "topic", "delete", args.topic],
                   capture_output=True, text=True)
    time.sleep(2)

    instances = [
        Instance(args.jar, args.base_port + i, args.db_url, args.kafka,
                 os.path.join(args.log_dir, f"rebalance-{i}.log"), args.max_poll_records)
        for i in range(2)
    ]
    try:
        for instance in instances:
            instance.start()

        print(f"== 이벤트 {args.events}건을 넣고 소비가 시작되기를 기다립니다")
        seed(args.container, args.events)
        deadline = time.time() + 60
        while count(args.container, "SELECT count(*) FROM wallet_transaction") == 0:
            if time.time() > deadline:
                raise RuntimeError("소비가 시작되지 않았습니다")
            time.sleep(0.2)

        time.sleep(args.kill_after)
        consumed_before_kill = count(args.container, "SELECT count(*) FROM wallet_transaction")
        print(f"== 소비 중({consumed_before_kill}건 처리됨) 인스턴스 1을 SIGKILL 합니다")
        instances[1].kill()

        print("== 살아남은 인스턴스가 다 처리할 때까지 기다립니다")
        deadline = time.time() + args.settle_timeout
        stable_since = None
        while True:
            rows = count(args.container, "SELECT count(*) FROM wallet_transaction")
            pending = count(args.container, "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'")
            if rows >= args.events and pending == 0:
                # 재분배 후 재배달이 늦게 올 수 있으므로 잠시 더 지켜봅니다.
                stable_since = stable_since or time.time()
                if time.time() - stable_since > 10:
                    break
            else:
                stable_since = None
            if time.time() > deadline:
                raise RuntimeError(f"시간 안에 끝나지 않았습니다 (rows={rows} pending={pending})")
            time.sleep(0.5)

        rows = count(args.container, "SELECT count(*) FROM wallet_transaction")
        distinct_rows = count(
            args.container,
            "SELECT count(*) FROM (SELECT DISTINCT reference_type, reference_id FROM wallet_transaction) t")
        consumed = count(
            args.container,
            "SELECT count(*) FROM consumed_event WHERE consumer_name = 'wallet-transaction-projection'")
        duplicates = sum(instance.duplicate_deliveries() for instance in instances)
        rebalances = sum(instance.rebalances() for instance in instances)

        print()
        print(f"이벤트 발행       : {args.events}")
        print(f"거래내역 행       : {rows}")
        print(f"거래내역 고유 참조: {distinct_rows}")
        print(f"소비 이력         : {consumed}")
        print(f"중복 전달(로그)   : {duplicates}")
        print(f"파티션 재할당     : {rebalances}")
        print()
        problems = []
        if rows != args.events:
            problems.append(f"거래내역 행이 {rows}로 이벤트 수와 다릅니다")
        if rows != distinct_rows:
            problems.append(f"같은 참조가 두 줄 이상 생겼습니다 ({rows} 행 / {distinct_rows} 고유)")
        if duplicates == 0:
            problems.append("중복 전달이 한 건도 없었습니다 — 이 실행은 멱등성을 증명하지 못합니다")
        print("결과: " + ("문제 없음" if not problems else "; ".join(problems)))
        return 0 if not problems else 1
    finally:
        for instance in instances:
            instance.stop()


if __name__ == "__main__":
    raise SystemExit(main())

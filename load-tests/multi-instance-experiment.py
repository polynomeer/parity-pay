#!/usr/bin/env python3
"""M-001: 발행기 다중 인스턴스.

ADR-005는 발행기를 여러 대 돌려도 안전하다고 주장하고 근거로 ``FOR UPDATE SKIP LOCKED``를 듭니다.
이 실험 전까지 그 주장의 근거는 코드와 단위 시험뿐이었습니다. 여기서는 실제 JVM을 1·2·4대 띄우고
같은 적체를 비우게 한 뒤, 브로커에 실제로 들어간 것을 읽어 세 가지를 확인합니다.

1. 유실 — 토픽 메시지 수가 적체 건수보다 적으면 이벤트가 사라진 것입니다.
2. 중복 — 많으면 두 발행기가 같은 이벤트를 보낸 것입니다(설계상 허용이지만 양을 알아야 합니다).
3. 순서 — 같은 파티션 키 안에서 sequence가 뒤로 가면 순서가 깨진 것입니다. 정산 소비자가
   이 순서에 의존하므로 금액이 달라집니다(SettlementOrderDependencyTest).

이벤트는 SQL로 직접 넣습니다. API로 쌓으면 적체를 만드는 동안 DB와 API가 함께 바빠서 발행
처리량과 섞입니다. next_attempt_at을 미래로 두어 삽입이 끝난 뒤부터 발행이 시작되게 합니다.

사전 조건: docker compose로 PostgreSQL·Redpanda가 떠 있고 bootJar가 빌드되어 있어야 합니다.
사용: load-tests/multi-instance-experiment.py --jar apps/pay-api/build/libs/pay-api.jar
근거: reports/11 M-001, ADR-005
"""

import argparse
import json
import os
import subprocess
import time

TOPIC_DEFAULT = "paritypay.events"


def psql(container, sql):
    out = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "paritypay", "-d", "paritypay", "-tA", "-q", "-c", sql],
        capture_output=True,
        text=True,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def rpk(broker, *args):
    return subprocess.run(["docker", "exec", broker, "rpk", *args], capture_output=True, text=True)


class App:
    """발행기 한 대. 스레드가 아니라 프로세스여야 인스턴스 실험입니다."""

    def __init__(self, jar, port, db_url, kafka, log_path):
        self.jar, self.port, self.db_url, self.kafka, self.log_path = jar, port, db_url, kafka, log_path
        self.process = None

    def start(self):
        env = dict(os.environ)
        env.update({
            "SPRING_PROFILES_ACTIVE": "local",
            "PARITYPAY_PORT": str(self.port),
            "PARITYPAY_DB_URL": self.db_url,
            "PARITYPAY_KAFKA_SERVERS": self.kafka,
            "PARITYPAY_TRACE_SAMPLING": "0",
        })
        open(self.log_path, "w").close()
        log = open(self.log_path, "a")
        self.process = subprocess.Popen(
            [os.environ.get("JAVA", "java"), "-jar", self.jar], stdout=log, stderr=log, env=env
        )
        deadline = time.time() + 180
        while time.time() < deadline:
            if "Started ParityPayApplication" in open(self.log_path, encoding="utf-8", errors="replace").read():
                return
            if self.process.poll() is not None:
                raise RuntimeError(f"instance on port {self.port} exited during startup; see {self.log_path}")
            time.sleep(1)
        raise RuntimeError(f"instance on port {self.port} did not start in time")

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()


def seed(container, events, lead_seconds, partition_keys):
    """적체를 만듭니다. occurred_at을 n에 비례해 늘려 Aggregate 안의 순서를 정의합니다.

    partition_keys는 적체가 몇 개의 Aggregate에 흩어져 있는지입니다. 발행기는 파티션 키마다 선두
    하나만 집어가므로(순서 보장), 이 값이 곧 한 배치에 담길 수 있는 최대 건수입니다. 적체가 한
    지갑에 몰리는 상황(연속 충전, 부분취소 반복)이 곧 이 값이 작은 경우입니다.
    """
    start_at = psql(container, f"SELECT to_char(now() + interval '{lead_seconds} seconds',"
                               " 'YYYY-MM-DD\"T\"HH24:MI:SS.MSOF')")
    psql(container, f"""
        INSERT INTO outbox_event
            (event_id, event_type, event_version, aggregate_type, aggregate_id, partition_key,
             payload, trace_id, status, attempt_count, next_attempt_at, occurred_at, created_at)
        SELECT gen_random_uuid(), 'BenchmarkEvent', 1, 'BENCHMARK', 'agg-' || (n % {partition_keys}),
               'agg-' || (n % {partition_keys}),
               jsonb_build_object('sequence', n, 'amount', 1000, 'currency', 'KRW'),
               NULL, 'PENDING', 0, timestamptz '{start_at}',
               now() + (n * interval '1 microsecond'), now()
          FROM generate_series(1, {events}) AS n
    """)
    return start_at


def drain(container, start_at, timeout):
    while psql(container, f"SELECT (now() >= timestamptz '{start_at}')") != "t":
        time.sleep(0.2)
    begin = time.time()
    deadline = begin + timeout
    while psql(container, "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'") != "0":
        if time.time() > deadline:
            raise RuntimeError("outbox did not drain in time")
        time.sleep(0.2)
    return time.time() - begin


def topic_message_count(broker, topic):
    """파티션 워터마크의 합입니다. 이것이 브로커가 실제로 보관 중인 메시지 수입니다."""
    out = rpk(broker, "topic", "describe", topic, "-p")
    total = 0
    for line in out.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 6 and fields[0].isdigit():
            total += int(fields[5]) - int(fields[4])
    return total


def inspect_topic(broker, topic, expected):
    """브로커에 실제로 들어간 것을 읽습니다. DB의 PUBLISHED 표시는 우리가 쓴 것이라 증거가 아닙니다."""
    total = topic_message_count(broker, topic)
    if total == 0:
        return {"delivered": 0, "missing": expected, "duplicates": 0, "inversions": 0}
    # 정확히 보관 중인 만큼만 읽습니다. 더 요청하면 새 메시지를 기다리며 멈춥니다.
    out = subprocess.run(
        ["docker", "exec", broker, "rpk", "topic", "consume", topic,
         "-o", "start", "-n", str(total), "-f", "%k\t%v\n"],
        capture_output=True,
        text=True,
    )
    delivered = 0
    seen = set()
    duplicates = 0
    last_sequence = {}
    inversions = 0
    for line in out.stdout.splitlines():
        if "\t" not in line:
            continue
        key, value = line.split("\t", 1)
        try:
            envelope = json.loads(value)
        except json.JSONDecodeError:
            continue
        payload = envelope.get("payload") or {}
        if "sequence" not in payload:
            continue
        delivered += 1
        event_id = envelope.get("eventId")
        if event_id in seen:
            duplicates += 1
        seen.add(event_id)
        sequence = payload["sequence"]
        if key in last_sequence and sequence < last_sequence[key]:
            inversions += 1
        last_sequence[key] = sequence
    return {
        "delivered": delivered,
        "missing": max(0, expected - len(seen)),
        "duplicates": duplicates,
        "inversions": inversions,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True)
    parser.add_argument("--instances", default="1,2,4", help="쉼표로 구분한 발행기 대수")
    parser.add_argument("--events", type=int, default=20000)
    parser.add_argument(
        "--partition-keys",
        type=int,
        default=1000,
        help="적체가 흩어진 Aggregate 수. 작을수록 head-of-line 대기가 커집니다",
    )
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--base-port", type=int, default=8080)
    parser.add_argument("--db-url", default="jdbc:postgresql://localhost:5435/paritypay")
    parser.add_argument("--kafka", default="localhost:9092")
    parser.add_argument("--container", default="paritypay-postgres")
    parser.add_argument("--broker-container", default="paritypay-redpanda")
    parser.add_argument("--topic", default=TOPIC_DEFAULT)
    parser.add_argument("--lead", type=int, default=15)
    parser.add_argument("--drain-timeout", type=float, default=600.0)
    parser.add_argument("--log-dir", default="/tmp")
    args = parser.parse_args()

    counts = [int(x) for x in args.instances.split(",")]
    results = []

    for count in counts:
        apps = [
            App(args.jar, args.base_port + i, args.db_url, args.kafka,
                os.path.join(args.log_dir, f"multi-instance-{count}-{i}.log"))
            for i in range(count)
        ]
        for run in range(1, args.runs + 1):
            print(f"== 발행기 {count}대, 파티션 키 {args.partition_keys}종, run {run}/{args.runs}")
            psql(args.container, "TRUNCATE outbox_event, consumed_event")
            # 토픽을 비웁니다. 이전 실행이 남긴 메시지가 있으면 중복·순서 계산이 그것까지 셉니다.
            rpk(args.broker_container, "topic", "delete", args.topic)
            time.sleep(2)

            for app in apps:
                app.start()
            try:
                start_at = seed(args.container, args.events, args.lead, args.partition_keys)
                elapsed = drain(args.container, start_at, args.drain_timeout)
            finally:
                for app in apps:
                    app.stop()

            published = int(psql(args.container, "SELECT count(*) FROM outbox_event WHERE status = 'PUBLISHED'"))
            failed = int(psql(args.container, "SELECT count(*) FROM outbox_event WHERE status = 'FAILED'"))
            topic = inspect_topic(args.broker_container, args.topic, args.events)
            rate = published / elapsed if elapsed else 0.0
            results.append((count, run, published, failed, elapsed, rate, topic))
            print(f"   published={published} failed={failed} elapsed={elapsed:.1f}s rate={rate:.1f}/s "
                  f"delivered={topic['delivered']} missing={topic['missing']} "
                  f"duplicates={topic['duplicates']} inversions={topic['inversions']}")

    print()
    print(f"파티션 키 {args.partition_keys}종, 이벤트 {args.events}건")
    print("발행기 | run | 발행 | 실패 | 소요(s) | 처리량(/s) | 브로커 도착 | 유실 | 중복 | 순서역전")
    for count, run, published, failed, elapsed, rate, topic in results:
        print(f"{count:>5} | {run:>3} | {published:>4} | {failed:>4} | {elapsed:>7.1f} | {rate:>10.1f} | "
              f"{topic['delivered']:>11} | {topic['missing']:>4} | {topic['duplicates']:>4} | {topic['inversions']:>8}")


if __name__ == "__main__":
    main()

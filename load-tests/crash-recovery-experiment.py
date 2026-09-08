#!/usr/bin/env python3
"""F-001·F-002 프로세스 강제 종료 재현.

지금까지 두 시나리오는 "커밋 직전에 예외를 던진다", "같은 키로 다시 부른다"처럼 경계를 흉내 내는
테스트로만 검증했습니다. 흉내는 우리가 생각한 지점에서만 멈춥니다. 여기서는 실제로 트래픽이 흐르는
동안 `SIGKILL`로 프로세스를 죽이고, 재시작한 뒤에 돈이 어떻게 됐는지 셉니다.

무엇을 보는가

  F-001  커밋 전에 죽은 요청은 흔적이 없어야 합니다. 같은 키로 다시 보내면 그때 한 번 일어납니다.
  F-002  커밋 후 응답 전에 죽은 요청은 이미 일어난 것이며, 같은 키로 다시 보내면 기존 결과가
         그대로 나와야 합니다. 두 번 일어나면 안 됩니다.

응답을 받지 못한 요청이 둘 중 어느 쪽이었는지는 재시작 후 DB가 알려줍니다. 행이 없으면 F-001,
있으면 F-002입니다. 어느 쪽이든 최종 상태는 "정확히 1회"여야 합니다(INV-004).

사전 조건: docker compose로 PostgreSQL·Redpanda가 떠 있고, bootJar가 빌드되어 있어야 합니다.
사용: python3 load-tests/crash-recovery-experiment.py --jar <path> [--clients 8] [--kill-after 6]
"""

import argparse
import json
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

AMOUNT = 1000
PASSWORD = "crash-test-password"


def psql(container, sql):
    """실험 관측은 앱을 거치지 않고 DB에서 직접 셉니다. 앱이 죽은 동안에도 봐야 하기 때문입니다."""
    out = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "paritypay", "-d", "paritypay", "-tA", "-F", "|", "-c", sql],
        capture_output=True,
        text=True,
        check=True,
    )
    return [line.split("|") for line in out.stdout.strip().splitlines() if line]


def request(base, method, path, body=None, token=None, key=None, timeout=10):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if key:
        req.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


class App:
    """앱 프로세스. 죽이고 다시 띄우는 것이 실험의 핵심이라 수명을 직접 관리합니다."""

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
        log = open(self.log_path, "a")
        self.process = subprocess.Popen(
            [os.environ.get("JAVA", "java"), "-jar", self.jar], stdout=log, stderr=log, env=env
        )
        deadline = time.time() + 120
        while time.time() < deadline:
            if "Started ParityPayApplication" in open(self.log_path, encoding="utf-8", errors="replace").read():
                return
            if self.process.poll() is not None:
                raise RuntimeError("application exited during startup; see " + self.log_path)
            time.sleep(1)
        raise RuntimeError("application did not start in time")

    def kill(self):
        """SIGTERM이 아니라 SIGKILL입니다. 정상 종료 훅이 돌면 그것은 크래시가 아닙니다."""
        os.kill(self.process.pid, signal.SIGKILL)
        self.process.wait()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True)
    parser.add_argument("--port", type=int, default=8085)
    parser.add_argument("--db-url", default="jdbc:postgresql://localhost:5435/paritypay")
    parser.add_argument("--kafka", default="localhost:9092")
    parser.add_argument("--container", default="paritypay-postgres")
    parser.add_argument("--broker-container", default="paritypay-redpanda")
    parser.add_argument("--topic", default="paritypay.events")
    parser.add_argument("--clients", type=int, default=8)
    parser.add_argument("--kill-after", type=float, default=6.0)
    parser.add_argument("--settle-timeout", type=float, default=300.0)
    parser.add_argument("--log", default="/tmp/crash-experiment.log")
    args = parser.parse_args()

    base = f"http://localhost:{args.port}"
    app = App(args.jar, args.port, args.db_url, args.kafka, args.log)

    print("== 준비: 업무 데이터를 비우고 앱을 띄웁니다")
    psql(args.container, """
        TRUNCATE refresh_token, login_attempt, audit_log,
                 ledger_entry, ledger_transaction, ledger_account,
                 idempotency_record, payment_cancellation, payment, top_up,
                 outbox_event, consumed_event, wallet_transaction,
                 settlement_item, settlement,
                 mock_bank_withdrawal, mock_bank_account,
                 wallet_balance, bank_account, wallet, member CASCADE
    """)
    # 토픽도 비웁니다. 이전 실행이 남긴 이벤트가 있으면 소비자가 그것부터 읽느라 이번 실행의
    # 이벤트에 닿지 못하고, 프로젝션 검증이 실제와 다른 이유로 실패합니다.
    subprocess.run(
        ["docker", "exec", args.broker_container, "rpk", "topic", "delete", args.topic],
        capture_output=True,
        text=True,
    )
    app.start()

    email = f"crash-{uuid.uuid4()}@example.com"
    _, member = request(base, "POST", "/api/v1/members", {"email": email, "password": PASSWORD})
    _, tokens = request(base, "POST", "/api/v1/auth/tokens", {"email": email, "password": PASSWORD})
    token = tokens["accessToken"]
    wallet_id = member["walletId"]
    _, bank = request(
        base,
        "POST",
        "/api/v1/bank-accounts",
        {"bankCode": "004", "accountNumber": "110" + str(uuid.uuid4().int)[:9], "initialBalance": 10_000_000_000},
        token=token,
    )
    bank_account_id = bank["bankAccountId"]

    sent, answered = [], {}
    lock = threading.Lock()
    stop = threading.Event()

    def client(index):
        counter = 0
        while not stop.is_set():
            key = f"crash-{index}-{counter}"
            counter += 1
            with lock:
                sent.append(key)
            try:
                status, body = request(
                    base,
                    "POST",
                    "/api/v1/top-ups",
                    {
                        "walletId": wallet_id,
                        "bankAccountId": bank_account_id,
                        "amount": AMOUNT,
                        "currency": "KRW",
                    },
                    token=token,
                    key=key,
                    timeout=5,
                )
                with lock:
                    answered[key] = (status, body.get("status"))
            except Exception:
                # 죽는 순간의 요청입니다. 응답을 받지 못한 것 자체가 관측 결과입니다.
                pass

    print(f"== 부하 시작: 클라이언트 {args.clients}개, {args.kill_after}초 뒤 SIGKILL")
    threads = [threading.Thread(target=client, args=(i,), daemon=True) for i in range(args.clients)]
    for thread in threads:
        thread.start()
    time.sleep(args.kill_after)

    app.kill()
    killed_at = time.time()
    stop.set()
    for thread in threads:
        thread.join(timeout=10)

    with lock:
        in_flight = [key for key in sent if key not in answered]
    print(f"   보낸 요청 {len(sent)}건, 응답 받은 요청 {len(answered)}건, 응답 못 받은 요청 {len(in_flight)}건")

    # 죽은 직후의 사실. 응답을 못 받은 요청이 커밋 전이었는지(F-001) 후였는지(F-002) 여기서 갈립니다.
    committed = {row[0] for row in psql(args.container, "SELECT idempotency_key FROM top_up")}
    f001 = [key for key in in_flight if key not in committed]
    f002 = [key for key in in_flight if key in committed]
    print(f"   F-001(행 없음) {len(f001)}건, F-002(행 있음) {len(f002)}건")

    print("== 재시작")
    app.start()
    restarted_at = time.time()

    # 복구 작업이 미확정 충전을 정리할 때까지 기다립니다. 죽는 순간 외부 호출 직전이었던 건은
    # 외부에 기록이 없다고 연속으로 확인돼야 실패로 확정되므로(NOT_FOUND 임계치) 시간이 걸립니다.
    deadline = time.time() + args.settle_timeout
    unsettled = None
    while time.time() < deadline:
        unsettled = int(psql(args.container, "SELECT count(*) FROM top_up WHERE status IN ('PROCESSING','UNKNOWN','REQUESTED')")[0][0])
        if unsettled == 0:
            break
        time.sleep(2)
    settled_at = time.time()
    print(f"   미확정 충전 {unsettled}건, 복구까지 {settled_at - restarted_at:.1f}초")

    # 이벤트도 크래시를 넘어 살아남아야 합니다. 재시작한 발행기가 이어서 발행합니다.
    outbox_deadline = time.time() + 60
    outbox_pending = None
    while time.time() < outbox_deadline:
        outbox_pending = int(psql(args.container, "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'")[0][0])
        if outbox_pending == 0:
            break
        time.sleep(2)
    print(f"   미발행 이벤트 {outbox_pending}건")

    print("== 응답을 못 받은 요청을 같은 멱등 키로 재전송")
    replay = {}
    for key in in_flight:
        status, body = request(
            base,
            "POST",
            "/api/v1/top-ups",
            {"walletId": wallet_id, "bankAccountId": bank_account_id, "amount": AMOUNT, "currency": "KRW"},
            token=token,
            key=key,
            timeout=15,
        )
        replay[key] = (status, body.get("status"), body.get("topUpId"))

    print("== 검증")
    checks = []

    def check(name, actual, expected):
        ok = actual == expected
        checks.append(ok)
        print(f"   [{'PASS' if ok else 'FAIL'}] {name}: {actual} (기대 {expected})")

    duplicate_keys = psql(args.container, """
        SELECT count(*) FROM (
            SELECT idempotency_key FROM top_up GROUP BY wallet_id, idempotency_key HAVING count(*) > 1
        ) duplicated
    """)[0][0]
    check("멱등 키당 충전 행 (INV-004)", int(duplicate_keys), 0)

    double_withdrawal = psql(args.container, """
        SELECT count(*) FROM (
            SELECT external_key FROM mock_bank_withdrawal GROUP BY external_key HAVING count(*) > 1
        ) duplicated
    """)[0][0]
    check("외부 출금 중복", int(double_withdrawal), 0)

    double_posting = psql(args.container, """
        SELECT count(*) FROM (
            SELECT reference_id FROM ledger_transaction WHERE reference_type = 'TOP_UP'
             GROUP BY reference_id HAVING count(*) > 1
        ) duplicated
    """)[0][0]
    check("업무 참조당 원장 거래 (INV-004)", int(double_posting), 0)

    succeeded, ledger_txs, withdrawals = psql(args.container, """
        SELECT (SELECT count(*) FROM top_up WHERE status = 'SUCCEEDED'),
               (SELECT count(*) FROM ledger_transaction WHERE reference_type = 'TOP_UP'),
               (SELECT count(*) FROM mock_bank_withdrawal WHERE status = 'SUCCEEDED')
    """)[0]
    check("성공한 충전 = 원장 거래 수", int(ledger_txs), int(succeeded))
    check("성공한 충전 = 외부 출금 수", int(withdrawals), int(succeeded))

    balance, ledger_balance = psql(args.container, f"""
        SELECT (SELECT available_amount + pending_amount FROM wallet_balance WHERE wallet_id = '{wallet_id}'),
               (SELECT coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
                  FROM ledger_entry e JOIN ledger_account la ON la.account_id = e.account_id
                 WHERE la.account_code = '2010' AND la.owner_id = '{wallet_id}')
    """)[0]
    check("잔액 스냅샷 = 원장 (INV-010)", int(balance), int(ledger_balance))
    check("잔액 = 성공 건수 x 금액", int(balance), int(succeeded) * AMOUNT)

    unbalanced = psql(args.container, """
        SELECT count(*) FROM (
            SELECT transaction_id FROM ledger_entry GROUP BY transaction_id
            HAVING sum(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0
        ) x
    """)[0][0]
    check("불균형 원장 거래 (INV-001)", int(unbalanced), 0)

    orphan = psql(args.container, """
        SELECT count(*) FROM mock_bank_withdrawal w
         WHERE NOT EXISTS (SELECT 1 FROM top_up t WHERE t.top_up_id::text = w.external_key)
    """)[0][0]
    check("내부 기록 없는 외부 출금", int(orphan), 0)

    replayed_ok = sum(1 for status, _, _ in replay.values() if status in (200, 201, 202))
    check("재전송이 모두 결과를 돌려줌", replayed_ok, len(replay))

    check("미확정으로 남은 충전", unsettled, 0)
    check("미발행 이벤트", outbox_pending, 0)
    check("발행 포기(FAILED) 이벤트", int(psql(args.container, "SELECT count(*) FROM outbox_event WHERE status = 'FAILED'")[0][0]), 0)

    # 프로젝션은 이벤트 소비 결과이므로 발행보다 늦게 도착합니다. 잠깐 기다렸다가 셉니다.
    projection_deadline = time.time() + 60
    projected = 0
    while time.time() < projection_deadline:
        projected = int(psql(args.container, "SELECT count(*) FROM wallet_transaction WHERE reference_type = 'TOP_UP'")[0][0])
        if projected >= int(succeeded):
            break
        time.sleep(2)
    check("거래내역 프로젝션 = 성공한 충전", int(projected), int(succeeded))

    statuses = psql(args.container, "SELECT status, count(*) FROM top_up GROUP BY status ORDER BY status")
    print("   최종 충전 상태: " + ", ".join(f"{row[0]}={row[1]}" for row in statuses))

    print()
    print(f"kill 시각 기준 응답 못 받은 요청 {len(in_flight)}건 (F-001 {len(f001)} / F-002 {len(f002)})")
    print(f"성공한 충전 {succeeded}건, 잔액 {balance}원, 복구 대기 {settled_at - restarted_at:.1f}초")
    print(f"프로세스 정지 시각부터 재시작 완료까지 {restarted_at - killed_at:.1f}초")
    app.kill()
    return 0 if all(checks) else 1


if __name__ == "__main__":
    sys.exit(main())

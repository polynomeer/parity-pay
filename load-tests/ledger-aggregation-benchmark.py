#!/usr/bin/env python3
"""M-006: 원장 집계 비용.

ADR-008은 원장을 진실로 두고 `wallet_balance`를 조회 스냅샷으로 씁니다. 그 결정의 대가는 **검증과
재구축이 원장 전체를 다시 더한다**는 것이고, 지금까지 측정한 규모는 지갑당 1,170건이었습니다.

두 가지를 잽니다.

1. 계정 하나의 집계 — 잔액 검증(`GET .../ledger-verification`)과 재구축이 쓰는 경로입니다.
   지갑당 항목 수가 늘면 어떻게 되는지 봅니다.
2. 전 지갑 불일치 탐지 — `paritypay.invariant.balance_snapshot_drift` 지표의 쿼리입니다.
   이것은 Prometheus가 긁을 때마다(기본 10~15초) 돕니다. 지갑 수와 전체 항목 수에 따라 어떻게
   변하는지가 이 지표를 계속 켜 둘 수 있는지를 정합니다.

시간은 클라이언트가 아니라 `EXPLAIN ANALYZE`의 Execution Time으로 잽니다. docker exec 왕복이
수십 ms이므로 클라이언트에서 재면 빠른 쿼리가 왕복 시간에 묻힙니다.

사전 조건: docker compose로 PostgreSQL이 떠 있고 마이그레이션이 적용되어 있어야 합니다.
근거: reports/11 M-006, ADR-008
"""

import argparse
import re
import statistics
import subprocess

TARGET_ACCOUNT = "11111111-1111-1111-1111-111111111111"
TARGET_WALLET = "22222222-2222-2222-2222-222222222222"
COUNTERPART_ACCOUNT = "33333333-3333-3333-3333-333333333333"

# 지표가 쓰는 쿼리입니다. InvariantMetrics와 같은 문장이어야 의미가 있습니다.
DRIFT_SQL = """
SELECT count(*)
  FROM wallet_balance wb
  JOIN ledger_account la
    ON la.owner_id = wb.wallet_id AND la.account_code = '2010'
 WHERE wb.available_amount + wb.pending_amount <> (
       SELECT coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount
                                ELSE -e.amount END), 0)
         FROM ledger_entry e
        WHERE e.account_id = la.account_id)
"""

ACCOUNT_SQL = f"""
SELECT coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END), 0) AS debit_total,
       coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END), 0) AS credit_total
  FROM ledger_entry e
 WHERE e.account_id = '{TARGET_ACCOUNT}'
"""


def psql(container, sql):
    out = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "paritypay", "-d", "paritypay", "-tA", "-q", "-c", sql],
        capture_output=True,
        text=True,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def execution_ms(container, sql, runs):
    """EXPLAIN ANALYZE를 여러 번 돌려 Execution Time의 중앙값을 돌려줍니다."""
    times = []
    for _ in range(runs):
        plan = psql(container, "EXPLAIN (ANALYZE, COSTS OFF) " + sql)
        match = re.search(r"Execution Time: ([\d.]+) ms", plan)
        if not match:
            raise RuntimeError("Execution Time을 찾지 못했습니다:\n" + plan)
        times.append(float(match.group(1)))
    return statistics.median(times), plan


def reset(container):
    psql(container, """
        TRUNCATE ledger_entry, ledger_transaction, ledger_account,
                 wallet_balance, wallet, member CASCADE
    """)


def seed_account(container, entries):
    """대상 계정에 항목 `entries`건을 만듭니다. 거래마다 차변·대변 한 쌍이라 INV-001을 지킵니다."""
    psql(container, f"""
        INSERT INTO ledger_account (account_id, account_code, owner_type, owner_id, currency, status, created_at)
        VALUES ('{TARGET_ACCOUNT}', '2010', 'WALLET', '{TARGET_WALLET}', 'KRW', 'ACTIVE', now()),
               ('{COUNTERPART_ACCOUNT}', '1010', 'CORPORATE', NULL, 'KRW', 'ACTIVE', now())
        ON CONFLICT DO NOTHING
    """)
    psql(container, f"""
        WITH t AS (
          INSERT INTO ledger_transaction
              (transaction_id, transaction_type, reference_type, reference_id, currency, status,
               effective_at, created_at)
          SELECT gen_random_uuid(), 'TOP_UP', 'SEED', gen_random_uuid(), 'KRW', 'POSTED', now(), now()
            FROM generate_series(1, {entries})
          RETURNING transaction_id)
        INSERT INTO ledger_entry (entry_id, transaction_id, account_id, direction, amount, created_at)
        SELECT gen_random_uuid(), t.transaction_id, a.account_id, a.direction, 1000, now()
          FROM t CROSS JOIN (VALUES ('{TARGET_ACCOUNT}'::uuid, 'CREDIT'),
                                    ('{COUNTERPART_ACCOUNT}'::uuid, 'DEBIT')) AS a(account_id, direction)
    """)
    psql(container, "ANALYZE ledger_entry")


def seed_wallets(container, wallets, entries_each):
    """지갑 `wallets`개를 만들고 각각에 항목 `entries_each`건을 답니다. 스냅샷은 원장과 일치시킵니다."""
    psql(container, f"""
        INSERT INTO member (member_id, email, password_hash, status, created_at)
        SELECT gen_random_uuid(), 'seed-' || n || '@example.com', 'x', 'ACTIVE', now()
          FROM generate_series(1, {wallets}) AS n
    """)
    psql(container, """
        INSERT INTO wallet (wallet_id, member_id, currency, status, created_at)
        SELECT gen_random_uuid(), member_id, 'KRW', 'ACTIVE', now() FROM member
    """)
    psql(container, f"""
        INSERT INTO ledger_account (account_id, account_code, owner_type, owner_id, currency, status, created_at)
        SELECT gen_random_uuid(), '2010', 'WALLET', wallet_id, 'KRW', 'ACTIVE', now() FROM wallet;
        INSERT INTO ledger_account (account_id, account_code, owner_type, owner_id, currency, status, created_at)
        VALUES ('{COUNTERPART_ACCOUNT}', '1010', 'CORPORATE', NULL, 'KRW', 'ACTIVE', now())
        ON CONFLICT DO NOTHING
    """)
    psql(container, f"""
        WITH pairs AS (
            SELECT la.account_id, n
              FROM ledger_account la
             CROSS JOIN generate_series(1, {entries_each}) AS n
             WHERE la.account_code = '2010'
        ), t AS (
          INSERT INTO ledger_transaction
              (transaction_id, transaction_type, reference_type, reference_id, currency, status,
               effective_at, created_at)
          SELECT gen_random_uuid(), 'TOP_UP', 'SEED', gen_random_uuid(), 'KRW', 'POSTED', now(), now()
            FROM pairs
          RETURNING transaction_id
        ), numbered AS (
          SELECT transaction_id, row_number() OVER () AS rn FROM t
        ), targets AS (
          SELECT account_id, row_number() OVER () AS rn FROM pairs
        )
        INSERT INTO ledger_entry (entry_id, transaction_id, account_id, direction, amount, created_at)
        SELECT gen_random_uuid(), n.transaction_id, x.account_id, x.direction, 1000, now()
          FROM numbered n
          JOIN targets tg ON tg.rn = n.rn
         CROSS JOIN LATERAL (VALUES (tg.account_id, 'CREDIT'),
                                    ('{COUNTERPART_ACCOUNT}'::uuid, 'DEBIT')) AS x(account_id, direction)
    """)
    # 스냅샷을 원장과 맞춰 둡니다. 어긋난 채로 재면 탐지 쿼리가 일찍 멈춰 비용이 과소평가됩니다.
    psql(container, f"""
        INSERT INTO wallet_balance (wallet_id, available_amount, pending_amount, version, updated_at)
        SELECT w.wallet_id, {entries_each} * 1000, 0, 0, now() FROM wallet w
    """)
    psql(container, "ANALYZE ledger_entry; ANALYZE wallet_balance; ANALYZE ledger_account")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--container", default="paritypay-postgres")
    parser.add_argument("--account-scales", default="1170,10000,100000,500000")
    parser.add_argument("--drift-wallets", default="100,1000")
    parser.add_argument("--drift-entries-each", type=int, default=200)
    parser.add_argument("--runs", type=int, default=5)
    args = parser.parse_args()

    print("== 1. 계정 하나의 집계 (검증·재구축 경로)")
    print("항목 수 | 실행 시간(중앙값)")
    account_plan = ""
    for entries in [int(x) for x in args.account_scales.split(",")]:
        reset(args.container)
        seed_account(args.container, entries)
        median, account_plan = execution_ms(args.container, ACCOUNT_SQL, args.runs)
        print(f"{entries:>7} | {median:>10.2f} ms")
    print()
    print("마지막 구성의 실행 계획:")
    print(account_plan)
    print()

    print("== 2. 전 지갑 불일치 탐지 (지표 쿼리, 스크레이프마다 실행)")
    print("지갑 수 | 지갑당 항목 | 전체 항목 | 실행 시간(중앙값)")
    drift_plan = ""
    for wallets in [int(x) for x in args.drift_wallets.split(",")]:
        reset(args.container)
        seed_wallets(args.container, wallets, args.drift_entries_each)
        total = int(psql(args.container, "SELECT count(*) FROM ledger_entry"))
        median, drift_plan = execution_ms(args.container, DRIFT_SQL, max(3, args.runs // 2))
        print(f"{wallets:>7} | {args.drift_entries_each:>11} | {total:>9} | {median:>10.2f} ms")
    print()
    print("마지막 구성의 실행 계획:")
    print(drift_plan)


if __name__ == "__main__":
    main()

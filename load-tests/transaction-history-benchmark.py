#!/usr/bin/env python3
"""M-008: 거래내역 조회(FR-008)가 데이터가 쌓였을 때 어떻게 되는가.

`docs/08` §9는 커서 페이지네이션을 **정확성** 때문에 골랐다고 적습니다 — 금액 변동 중에 offset으로
넘기면 중복·누락이 생기기 때문입니다. 성능은 근거로 적혀 있지 않고 재지도 않았습니다. 보고서의
"남은 위험"에 마지막까지 남아 있던 항목입니다.

두 가지를 잽니다.

1. **깊은 페이지의 비용.** 커서 조회가 깊이와 무관하게 일정한지. 튜플 비교
   `(occurred_at, transaction_id) < (?, ?)`가 인덱스 조건(Index Cond)으로 들어가지 않고 필터로
   떨어지면, 깊은 페이지는 앞의 행을 전부 훑게 됩니다. 계획을 함께 확인합니다.
2. **같은 깊이에서 OFFSET과의 차이.** 커서를 고른 것은 정확성 때문이지만, 그 선택이 성능에서도
   무엇을 피한 것인지 숫자로 남깁니다.

시간은 클라이언트가 아니라 `EXPLAIN ANALYZE`의 Execution Time으로 잽니다. docker exec 왕복이
수십 ms이므로 클라이언트에서 재면 빠른 질의가 왕복 시간에 묻힙니다.

사전 조건: docker compose로 PostgreSQL이 떠 있고 마이그레이션이 적용되어 있어야 합니다.
**이 스크립트는 `wallet_transaction`을 비우고 합성 데이터로 채웁니다.** 로컬 전용입니다.

사용:
    python3 load-tests/transaction-history-benchmark.py --rows 1000000

근거: reports/11 M-008, docs/08 §9
"""

import argparse
import re
import statistics
import subprocess
import sys

CONTAINER = "paritypay-postgres"
TARGET_WALLET = "44444444-4444-4444-4444-444444444444"
LIMIT = 20


def psql(sql, timeout=1800):
    out = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "paritypay", "-d", "paritypay",
         "-tA", "-q", "-F", "\x1f", "-c", sql],
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def seed(rows, other_wallets, rows_per_other):
    """대상 지갑에 rows건, 다른 지갑들에도 데이터를 넣습니다.

    다른 지갑이 없으면 인덱스의 첫 컬럼이 무의미해져 실제와 다른 계획이 나옵니다."""
    psql("TRUNCATE wallet_transaction")
    psql(f"""
        INSERT INTO wallet_transaction
            (transaction_id, wallet_id, transaction_type, direction, amount, currency,
             reference_type, reference_id, occurred_at, created_at)
        SELECT gen_random_uuid(), '{TARGET_WALLET}', 'PAYMENT', 'DEBIT', 1000, 'KRW',
               'PAYMENT', 'target-' || g,
               -- 같은 시각이 겹치도록 초 단위로 만듭니다. 튜플 비교가 필요한 조건이 실제로 생깁니다.
               timestamptz '2026-01-01 00:00:00+00' + (g / 10) * interval '1 second',
               now()
          FROM generate_series(1, {rows}) g
    """)
    if other_wallets > 0:
        psql(f"""
            INSERT INTO wallet_transaction
                (transaction_id, wallet_id, transaction_type, direction, amount, currency,
                 reference_type, reference_id, occurred_at, created_at)
            SELECT gen_random_uuid(), w.wallet_id, 'PAYMENT', 'DEBIT', 1000, 'KRW',
                   'PAYMENT', 'other-' || w.n || '-' || g,
                   timestamptz '2026-01-01 00:00:00+00' + (g / 10) * interval '1 second',
                   now()
              FROM (SELECT n, gen_random_uuid() AS wallet_id
                      FROM generate_series(1, {other_wallets}) n) w
             CROSS JOIN generate_series(1, {rows_per_other}) g
        """)
    psql("ANALYZE wallet_transaction")
    total = psql("SELECT count(*) FROM wallet_transaction")
    mine = psql(f"SELECT count(*) FROM wallet_transaction WHERE wallet_id = '{TARGET_WALLET}'")
    return int(total), int(mine)


def cursor_at(depth):
    """정렬 순서에서 depth번째 행의 커서를 돌려줍니다."""
    row = psql(f"""
        SELECT occurred_at, transaction_id
          FROM wallet_transaction
         WHERE wallet_id = '{TARGET_WALLET}'
         ORDER BY occurred_at DESC, transaction_id DESC
        OFFSET {depth} LIMIT 1
    """)
    if not row:
        return None
    occurred_at, transaction_id = row.split("\x1f")
    return occurred_at, transaction_id


def execution_ms(sql, runs=5):
    times = []
    plan = ""
    for _ in range(runs):
        plan = psql("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + sql)
        match = re.search(r"Execution Time: ([\d.]+) ms", plan)
        if not match:
            raise RuntimeError("Execution Time을 찾지 못했습니다:\n" + plan)
        times.append(float(match.group(1)))
    return statistics.median(times), plan


def plan_shape(plan):
    """계획에서 이 실험이 보려는 두 가지만 뽑습니다: 스캔 방식과 실제로 읽은 행 수."""
    scan = next((l.strip() for l in plan.splitlines() if "Scan" in l), "?")
    scan = re.sub(r"\s*\(actual.*", "", scan).lstrip("-> ").strip()
    rows_removed = sum(
        int(m.group(1)) for m in re.finditer(r"Rows Removed by Filter: (\d+)", plan)
    )
    index_cond = any("Index Cond" in l and "occurred_at" in l for l in plan.splitlines())
    return scan, rows_removed, index_cond


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rows", type=int, default=1_000_000, help="대상 지갑의 거래 수")
    parser.add_argument("--other-wallets", type=int, default=500)
    parser.add_argument("--rows-per-other", type=int, default=200)
    parser.add_argument("--depths", default="0,100,10000,100000,500000")
    parser.add_argument("--runs", type=int, default=5)
    args = parser.parse_args()

    total, mine = seed(args.rows, args.other_wallets, args.rows_per_other)
    print(f"전체 {total:,}건, 대상 지갑 {mine:,}건, 페이지 크기 {LIMIT}\n")

    print("-- 커서 조회 (구현이 쓰는 문장) --")
    print(f"{'깊이':>9} {'실행 ms':>9} {'필터로 버린 행':>14}  계획")
    for depth in [int(d) for d in args.depths.split(",")]:
        if depth == 0:
            sql = f"""
                SELECT * FROM wallet_transaction
                 WHERE wallet_id = '{TARGET_WALLET}'
                 ORDER BY occurred_at DESC, transaction_id DESC
                 LIMIT {LIMIT}
            """
        else:
            cursor = cursor_at(depth)
            if cursor is None:
                continue
            sql = f"""
                SELECT * FROM wallet_transaction
                 WHERE wallet_id = '{TARGET_WALLET}'
                   AND (occurred_at, transaction_id) < (timestamptz '{cursor[0]}', '{cursor[1]}')
                 ORDER BY occurred_at DESC, transaction_id DESC
                 LIMIT {LIMIT}
            """
        ms, plan = execution_ms(sql, args.runs)
        scan, removed, index_cond = plan_shape(plan)
        note = "" if index_cond or depth == 0 else "  ← 튜플 비교가 인덱스 조건이 아닙니다"
        print(f"{depth:>9,} {ms:>9.2f} {removed:>14,}  {scan[:52]}{note}")

    print("\n-- 같은 깊이를 OFFSET으로 (쓰지 않는 방식, 비교용) --")
    print(f"{'깊이':>9} {'실행 ms':>9}")
    for depth in [int(d) for d in args.depths.split(",")]:
        sql = f"""
            SELECT * FROM wallet_transaction
             WHERE wallet_id = '{TARGET_WALLET}'
             ORDER BY occurred_at DESC, transaction_id DESC
            OFFSET {depth} LIMIT {LIMIT}
        """
        ms, _ = execution_ms(sql, args.runs)
        print(f"{depth:>9,} {ms:>9.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

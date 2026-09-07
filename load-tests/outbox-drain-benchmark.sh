#!/usr/bin/env bash
# P-003 재측정: 발행기가 쌓인 이벤트를 초당 몇 건 해소하는지 잽니다.
#
# 발행 경로만 재려고 이벤트를 SQL로 직접 넣습니다. API 부하로 쌓으면 적체를 만드는 동안 DB·API가
# 함께 바빠서 발행 처리량과 섞입니다. next_attempt_at을 미래로 두어 삽입이 끝난 뒤에 발행이
# 시작되게 하고, 그 시점부터 PENDING이 0이 될 때까지를 잽니다.
#
# 사전 조건: docker compose up -d, 애플리케이션 실행(발행기 활성)
# 사용: EVENTS=20000 RUNS=3 load-tests/outbox-drain-benchmark.sh
# 근거: reports/11 P-003
set -euo pipefail

EVENTS=${EVENTS:-20000}
RUNS=${RUNS:-3}
# 삽입이 끝나기 전에 발행이 시작되지 않도록 두는 여유입니다.
LEAD=${LEAD:-15}
CONTAINER=${PG_CONTAINER:-paritypay-postgres}

psql_run() { docker exec -i "$CONTAINER" psql -U paritypay -d paritypay -tA -q "$@"; }

pending() { psql_run -c "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'"; }

for run in $(seq 1 "$RUNS"); do
  # 소비 이력도 비웁니다. 실행마다 같은 조건에서 재기 위해서입니다.
  psql_run -c "TRUNCATE outbox_event, consumed_event" >/dev/null
  start_at=$(psql_run -c "SELECT to_char(now() + interval '${LEAD} seconds', 'YYYY-MM-DD\"T\"HH24:MI:SS.MSOF')")

  psql_run -c "
    INSERT INTO outbox_event
        (event_id, event_type, event_version, aggregate_type, aggregate_id, partition_key,
         payload, trace_id, status, attempt_count, next_attempt_at, occurred_at, created_at)
    SELECT gen_random_uuid(), 'BenchmarkEvent', 1, 'BENCHMARK', 'agg-' || (n % 1000),
           'agg-' || (n % 1000),
           jsonb_build_object('sequence', n, 'amount', 1000, 'currency', 'KRW'),
           NULL, 'PENDING', 0, timestamptz '${start_at}', now(), now()
      FROM generate_series(1, ${EVENTS}) AS n" >/dev/null

  # 발행 시작 시각까지 기다립니다.
  while [ "$(psql_run -c "SELECT (now() >= timestamptz '${start_at}')")" != "t" ]; do sleep 0.2; done
  begin=$(date +%s.%N)

  # 촘촘히 물으면 측정 자체가 DB에 부하를 겁니다. 0.2초 간격이면 해상도는 충분합니다.
  while [ "$(pending)" != "0" ]; do sleep 0.2; done
  end=$(date +%s.%N)

  published=$(psql_run -c "SELECT count(*) FROM outbox_event WHERE status = 'PUBLISHED'")
  failed=$(psql_run -c "SELECT count(*) FROM outbox_event WHERE status = 'FAILED'")
  elapsed=$(echo "$end - $begin" | bc)
  rate=$(echo "scale=1; $published / $elapsed" | bc)
  printf 'run %d: published=%s failed=%s elapsed=%.1fs rate=%s events/s\n' \
    "$run" "$published" "$failed" "$elapsed" "$rate"
done

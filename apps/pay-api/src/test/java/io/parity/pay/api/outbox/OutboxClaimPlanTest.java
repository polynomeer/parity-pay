package io.parity.pay.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 선점 쿼리의 실행 계획.
 *
 * <p>시간을 재지 않고 계획의 모양을 봅니다. 시간 단언은 CI에서 흔들리지만, 정렬 노드가 생겼는지는
 * 결정적입니다.
 *
 * <p>왜 이걸 지키는가: 정렬 키를 {@code occurred_at}에서 {@code (occurred_at, event_id)}로 되돌리면
 * 인덱스 순서와 달라져 정렬 노드가 생깁니다. 그 노드는 LIMIT의 약 1.3배를 미리 당겨오는데, 후보가
 * 그보다 적으면 더 채울 것이 없는데도 남은 적체 전체를 훑습니다. 파티션 키 100종·적체 20,000건에서
 * 131.9ms 대 0.94ms였고, 발행 처리량으로는 1,284.7 대 2,769.8건/초였습니다.
 *
 * <p>동점은 정렬이 아니라 {@code NOT EXISTS}가 가릅니다. 정렬 키를 줄여도 한 파티션 키에서 두 건이
 * 함께 나오지 않습니다 — 그것은 {@code OutboxMultiInstanceTest}가 지킵니다.
 *
 * <p>근거: reports/11 M-003, ADR-005
 */
class OutboxClaimPlanTest extends AbstractIntegrationTest {

    /** 후보 수를 배치 크기와 같게 만듭니다. 정렬 노드가 있으면 여기서 전체 스캔이 됩니다. */
    private static final int PARTITION_KEYS = 100;

    private static final int EVENTS = 20_000;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE outbox_event");
        jdbcTemplate.update(
                """
                INSERT INTO outbox_event
                    (event_id, event_type, event_version, aggregate_type, aggregate_id, partition_key,
                     payload, status, attempt_count, next_attempt_at, occurred_at, created_at)
                SELECT gen_random_uuid(), 'PlanProbe', 1, 'PROBE', 'agg-' || (n %% %d),
                       'agg-' || (n %% %d), '{}'::jsonb, 'PENDING', 0, now(),
                       now() + (n * interval '1 microsecond'), now()
                  FROM generate_series(1, %d) AS n
                """
                        .formatted(PARTITION_KEYS, PARTITION_KEYS, EVENTS));
        jdbcTemplate.execute("ANALYZE outbox_event");
    }

    @Test
    @DisplayName("선점 쿼리에 정렬 노드가 생기지 않는다")
    void theClaimQueryDoesNotSort() {
        String plan = explainClaim(PARTITION_KEYS);

        // 정렬이 필요 없어야 인덱스 순서를 그대로 흘려보내며 LIMIT에서 멈춥니다.
        assertThat(plan).doesNotContain("Sort");
    }

    @Test
    @DisplayName("후보가 배치 크기와 같아도 남은 적체 전체를 훑지 않는다")
    void theScanStopsAtTheBatchSize() {
        String plan = explainClaim(PARTITION_KEYS);

        long scanned = scannedRows(plan);
        // 후보(선두)는 정확히 100건이고 배치도 100건입니다. 정렬 노드가 있던 때는 후보를 다 찾고도
        // 더 채우려고 20,000건을 훑었습니다.
        assertThat(scanned).isLessThanOrEqualTo(2L * PARTITION_KEYS);
    }

    private String explainClaim(int batchSize) {
        StringBuilder plan = new StringBuilder();
        jdbcTemplate.query(
                "EXPLAIN (ANALYZE, COSTS OFF) " + OutboxRepository.CLAIM_BATCH_SQL,
                rs -> {
                    plan.append(rs.getString(1)).append('\n');
                },
                Timestamp.from(Instant.now()),
                batchSize);
        return plan.toString();
    }

    /** 계획에서 바깥 스캔이 실제로 읽은 행 수를 꺼냅니다. */
    private long scannedRows(String plan) {
        long max = 0;
        for (String line : plan.lines().toList()) {
            if (!line.contains("Index Scan") && !line.contains("Seq Scan")) {
                continue;
            }
            int at = line.indexOf("rows=", line.indexOf("actual"));
            if (at < 0) {
                continue;
            }
            int end = at + 5;
            while (end < line.length() && Character.isDigit(line.charAt(end))) {
                end++;
            }
            max = Math.max(max, Long.parseLong(line.substring(at + 5, end)));
        }
        return max;
    }
}

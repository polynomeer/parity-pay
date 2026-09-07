package io.parity.pay.wallet.adapter.out.persistence;

import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.wallet.application.port.out.TopUpRecoveryRepository;
import io.parity.pay.wallet.domain.TopUpStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 복구 스케줄 저장소. 선점과 예약만 하므로 JDBC를 직접 사용합니다. */
@Repository
class TopUpRecoveryPersistenceAdapter implements TopUpRecoveryRepository {

    private static final RowMapper<PendingRecovery> ROW_MAPPER = (rs, rowNum) -> new PendingRecovery(
            TopUpId.of(rs.getObject("top_up_id", UUID.class)),
            TopUpStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt_count"),
            rs.getInt("not_found_count"),
            rs.getBoolean("requires_manual_review"));

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    TopUpRecoveryPersistenceAdapter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public List<PendingRecovery> claimDue(
            Instant now, Instant graceCutoff, Instant leaseUntil, int limit) {
        // UNKNOWN뿐 아니라 오래된 PROCESSING도 대상입니다. 외부 호출 직전·도중에 프로세스가 죽으면
        // 거래는 PROCESSING인 채로 남고, 아무도 건드리지 않으면 영원히 미확정입니다.
        List<PendingRecovery> claimed = jdbcTemplate.query(
                """
                SELECT t.top_up_id,
                       t.status,
                       coalesce(r.attempt_count, 0)             AS attempt_count,
                       coalesce(r.not_found_count, 0)           AS not_found_count,
                       coalesce(r.requires_manual_review, false) AS requires_manual_review
                  FROM top_up t
                  LEFT JOIN top_up_recovery r ON r.top_up_id = t.top_up_id
                 WHERE t.status IN ('UNKNOWN', 'PROCESSING')
                   AND t.requested_at <= ?
                   AND (r.next_check_at IS NULL OR r.next_check_at <= ?)
                   AND coalesce(r.requires_manual_review, false) = false
                 ORDER BY t.requested_at
                 LIMIT ?
                 FOR UPDATE OF t SKIP LOCKED
                """,
                ROW_MAPPER,
                Timestamp.from(graceCutoff),
                Timestamp.from(now),
                limit);

        for (PendingRecovery pending : claimed) {
            // 조회하는 동안 다른 인스턴스가 같은 건을 집어가지 않도록 리스를 겁니다.
            upsert(
                    pending.topUpId(),
                    pending.attemptCount(),
                    pending.notFoundCount(),
                    leaseUntil,
                    false,
                    null,
                    null);
        }
        return claimed;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void scheduleRetry(
            TopUpId topUpId,
            int attemptCount,
            int notFoundCount,
            Instant nextCheckAt,
            String lastError,
            Instant checkedAt) {
        upsert(topUpId, attemptCount, notFoundCount, nextCheckAt, false, lastError, checkedAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void markManualReview(
            TopUpId topUpId, int attemptCount, String lastError, Instant checkedAt) {
        // 다시 자동으로 집어가지 않도록 아주 먼 미래로 미루고 수동 검토 표시를 남깁니다.
        upsert(
                topUpId,
                attemptCount,
                0,
                checkedAt.plusSeconds(365L * 24 * 60 * 60),
                true,
                lastError,
                checkedAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void clear(TopUpId topUpId) {
        jdbcTemplate.update("DELETE FROM top_up_recovery WHERE top_up_id = ?", topUpId.value());
    }

    @Override
    public List<PendingRecovery> findManualReview(int limit) {
        return jdbcTemplate.query(
                """
                SELECT t.top_up_id, t.status, r.attempt_count, r.not_found_count,
                       r.requires_manual_review
                  FROM top_up_recovery r
                  JOIN top_up t ON t.top_up_id = r.top_up_id
                 WHERE r.requires_manual_review = true
                 ORDER BY r.updated_at DESC
                 LIMIT ?
                """,
                ROW_MAPPER,
                limit);
    }

    private void upsert(
            TopUpId topUpId,
            int attemptCount,
            int notFoundCount,
            Instant nextCheckAt,
            boolean requiresManualReview,
            String lastError,
            Instant checkedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO top_up_recovery
                    (top_up_id, attempt_count, not_found_count, next_check_at,
                     requires_manual_review, last_error, last_checked_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (top_up_id) DO UPDATE
                   SET attempt_count = excluded.attempt_count,
                       not_found_count = excluded.not_found_count,
                       next_check_at = excluded.next_check_at,
                       requires_manual_review = excluded.requires_manual_review,
                       last_error = excluded.last_error,
                       last_checked_at = coalesce(excluded.last_checked_at, top_up_recovery.last_checked_at),
                       updated_at = excluded.updated_at
                """,
                topUpId.value(),
                attemptCount,
                notFoundCount,
                Timestamp.from(nextCheckAt),
                requiresManualReview,
                lastError == null || lastError.length() <= 500
                        ? lastError
                        : lastError.substring(0, 500),
                checkedAt == null ? null : Timestamp.from(checkedAt),
                Timestamp.from(clock.instant()));
    }
}

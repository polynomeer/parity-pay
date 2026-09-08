package io.parity.pay.payment.adapter.out.persistence;

import io.parity.pay.payment.application.port.out.CancellationRecoveryRepository;
import io.parity.pay.payment.domain.CancellationStatus;
import io.parity.pay.shared.id.CancellationId;
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

/** 취소 복구 스케줄 저장소. 선점과 예약만 하므로 JDBC를 직접 사용합니다. */
@Repository
class CancellationRecoveryPersistenceAdapter implements CancellationRecoveryRepository {

    private static final RowMapper<PendingRecovery> ROW_MAPPER = (rs, rowNum) -> new PendingRecovery(
            CancellationId.of(rs.getObject("cancellation_id", UUID.class)),
            CancellationStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt_count"),
            rs.getInt("not_found_count"),
            rs.getBoolean("requires_manual_review"));

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    CancellationRecoveryPersistenceAdapter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public List<PendingRecovery> claimDue(Instant now, Instant graceCutoff, Instant leaseUntil, int limit) {
        // UNKNOWN뿐 아니라 오래된 PROCESSING도 대상입니다. 외부 호출 직전·도중에 프로세스가 죽으면
        // 취소는 PROCESSING인 채로 남고, 아무도 건드리지 않으면 영원히 미확정입니다.
        List<PendingRecovery> claimed = jdbcTemplate.query(
                """
                SELECT t.cancellation_id,
                       t.status,
                       coalesce(r.attempt_count, 0)             AS attempt_count,
                       coalesce(r.not_found_count, 0)           AS not_found_count,
                       coalesce(r.requires_manual_review, false) AS requires_manual_review
                  FROM payment_cancellation t
                  LEFT JOIN cancellation_recovery r ON r.cancellation_id = t.cancellation_id
                 WHERE t.status IN ('UNKNOWN', 'PROCESSING')
                   -- 외부 환불이 있는 취소만 대상입니다. 페이머니 취소는 한 트랜잭션으로 끝납니다.
                   AND EXISTS (
                       SELECT 1 FROM payment p
                        WHERE p.payment_id = t.payment_id AND p.method = 'EXTERNAL_PG')
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
                    pending.cancellationId(),
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
            CancellationId cancellationId,
            int attemptCount,
            int notFoundCount,
            Instant nextCheckAt,
            String lastError,
            Instant checkedAt) {
        upsert(cancellationId, attemptCount, notFoundCount, nextCheckAt, false, lastError, checkedAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void markManualReview(CancellationId cancellationId, int attemptCount, String lastError, Instant checkedAt) {
        // 다시 자동으로 집어가지 않도록 아주 먼 미래로 미루고 수동 검토 표시를 남깁니다.
        upsert(cancellationId, attemptCount, 0, checkedAt.plusSeconds(365L * 24 * 60 * 60), true, lastError, checkedAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void clear(CancellationId cancellationId) {
        jdbcTemplate.update("DELETE FROM cancellation_recovery WHERE cancellation_id = ?", cancellationId.value());
    }

    @Override
    public List<PendingRecovery> findManualReview(int limit) {
        return jdbcTemplate.query(
                """
                SELECT t.cancellation_id, t.status, r.attempt_count, r.not_found_count,
                       r.requires_manual_review
                  FROM cancellation_recovery r
                  JOIN top_up t ON t.cancellation_id = r.cancellation_id
                 WHERE r.requires_manual_review = true
                 ORDER BY r.updated_at DESC
                 LIMIT ?
                """,
                ROW_MAPPER,
                limit);
    }

    private void upsert(
            CancellationId cancellationId,
            int attemptCount,
            int notFoundCount,
            Instant nextCheckAt,
            boolean requiresManualReview,
            String lastError,
            Instant checkedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO cancellation_recovery
                    (cancellation_id, attempt_count, not_found_count, next_check_at,
                     requires_manual_review, last_error, last_checked_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (cancellation_id) DO UPDATE
                   SET attempt_count = excluded.attempt_count,
                       not_found_count = excluded.not_found_count,
                       next_check_at = excluded.next_check_at,
                       requires_manual_review = excluded.requires_manual_review,
                       last_error = excluded.last_error,
                       last_checked_at = coalesce(excluded.last_checked_at, cancellation_recovery.last_checked_at),
                       updated_at = excluded.updated_at
                """,
                cancellationId.value(),
                attemptCount,
                notFoundCount,
                Timestamp.from(nextCheckAt),
                requiresManualReview,
                lastError == null || lastError.length() <= 500 ? lastError : lastError.substring(0, 500),
                checkedAt == null ? null : Timestamp.from(checkedAt),
                Timestamp.from(clock.instant()));
    }
}

package io.parity.pay.settlement.adapter.out.persistence;

import io.parity.pay.settlement.application.port.out.SettlementRecoveryRepository;
import io.parity.pay.shared.id.SettlementId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 지급 복구 스케줄 저장소. */
@Repository
class SettlementRecoveryPersistenceAdapter implements SettlementRecoveryRepository {

    private static final RowMapper<PendingPayoutRecovery> ROW_MAPPER = (rs, rowNum) -> new PendingPayoutRecovery(
            SettlementId.of(rs.getObject("settlement_id", UUID.class)),
            rs.getInt("attempt_count"),
            rs.getInt("not_found_count"));

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    SettlementRecoveryPersistenceAdapter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<PendingPayoutRecovery> claimDue(Instant now, Instant leaseUntil, int limit) {
        List<PendingPayoutRecovery> claimed = jdbcTemplate.query(
                """
                SELECT s.settlement_id,
                       coalesce(r.attempt_count, 0)   AS attempt_count,
                       coalesce(r.not_found_count, 0) AS not_found_count
                  FROM settlement s
                  LEFT JOIN settlement_recovery r ON r.settlement_id = s.settlement_id
                 WHERE s.status IN ('UNKNOWN', 'PAYING')
                   AND (r.next_check_at IS NULL OR r.next_check_at <= ?)
                   AND coalesce(r.requires_manual_review, false) = false
                 ORDER BY s.updated_at
                 LIMIT ?
                 FOR UPDATE OF s SKIP LOCKED
                """,
                ROW_MAPPER,
                Timestamp.from(now),
                limit);

        for (PendingPayoutRecovery pending : claimed) {
            upsert(
                    pending.settlementId(),
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
    @Transactional
    public void scheduleRetry(
            SettlementId settlementId,
            int attemptCount,
            int notFoundCount,
            Instant nextCheckAt,
            String lastError,
            Instant checkedAt) {
        upsert(settlementId, attemptCount, notFoundCount, nextCheckAt, false, lastError, checkedAt);
    }

    @Override
    @Transactional
    public void markManualReview(SettlementId settlementId, int attemptCount, String lastError, Instant checkedAt) {
        upsert(settlementId, attemptCount, 0, checkedAt.plusSeconds(365L * 24 * 60 * 60), true, lastError, checkedAt);
    }

    @Override
    @Transactional
    public void clear(SettlementId settlementId) {
        jdbcTemplate.update("DELETE FROM settlement_recovery WHERE settlement_id = ?", settlementId.value());
    }

    private void upsert(
            SettlementId settlementId,
            int attemptCount,
            int notFoundCount,
            Instant nextCheckAt,
            boolean requiresManualReview,
            String lastError,
            Instant checkedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO settlement_recovery
                    (settlement_id, attempt_count, not_found_count, next_check_at,
                     requires_manual_review, last_error, last_checked_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (settlement_id) DO UPDATE
                   SET attempt_count = excluded.attempt_count,
                       not_found_count = excluded.not_found_count,
                       next_check_at = excluded.next_check_at,
                       requires_manual_review = excluded.requires_manual_review,
                       last_error = excluded.last_error,
                       last_checked_at = coalesce(excluded.last_checked_at,
                                                  settlement_recovery.last_checked_at),
                       updated_at = excluded.updated_at
                """,
                settlementId.value(),
                attemptCount,
                notFoundCount,
                Timestamp.from(nextCheckAt),
                requiresManualReview,
                lastError == null || lastError.length() <= 500 ? lastError : lastError.substring(0, 500),
                checkedAt == null ? null : Timestamp.from(checkedAt),
                Timestamp.from(clock.instant()));
    }
}

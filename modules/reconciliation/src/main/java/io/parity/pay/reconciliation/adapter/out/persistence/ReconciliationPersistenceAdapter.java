package io.parity.pay.reconciliation.adapter.out.persistence;

import io.parity.pay.reconciliation.application.port.out.ReconciliationRepository;
import io.parity.pay.reconciliation.domain.MismatchType;
import io.parity.pay.reconciliation.domain.ReconciliationMismatch;
import io.parity.pay.reconciliation.domain.ReconciliationMismatch.ResolutionStatus;
import io.parity.pay.shared.id.LedgerTransactionId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 대사 결과 저장소. */
@Repository
class ReconciliationPersistenceAdapter implements ReconciliationRepository {

    private static final RowMapper<ReconciliationMismatch> ROW_MAPPER = (rs, rowNum) -> new ReconciliationMismatch(
            rs.getObject("mismatch_id", UUID.class),
            rs.getObject("run_id", UUID.class),
            MismatchType.valueOf(rs.getString("mismatch_type")),
            rs.getString("reference_type"),
            rs.getString("reference_id"),
            rs.getString("external_reference_id"),
            rs.getObject("internal_amount", Long.class),
            rs.getObject("external_amount", Long.class),
            rs.getString("currency"),
            rs.getString("detail"),
            ResolutionStatus.valueOf(rs.getString("resolution_status")),
            rs.getString("resolution_type"),
            rs.getString("resolved_by"),
            rs.getString("resolution_reason"),
            rs.getObject("adjustment_ledger_transaction_id", UUID.class) == null
                    ? null
                    : LedgerTransactionId.of(rs.getObject("adjustment_ledger_transaction_id", UUID.class)),
            rs.getTimestamp("detected_at").toInstant(),
            rs.getTimestamp("resolved_at") == null
                    ? null
                    : rs.getTimestamp("resolved_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    ReconciliationPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveRun(
            UUID runId,
            String runType,
            Instant windowStart,
            Instant windowEnd,
            int internalCount,
            int externalCount,
            int mismatchCount,
            Instant startedAt,
            Instant finishedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation_run
                    (run_id, run_type, window_start, window_end, internal_count, external_count,
                     mismatch_count, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                runType,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd),
                internalCount,
                externalCount,
                mismatchCount,
                Timestamp.from(startedAt),
                Timestamp.from(finishedAt));
    }

    @Override
    public void updateRunMismatchCount(UUID runId, int mismatchCount) {
        jdbcTemplate.update("UPDATE reconciliation_run SET mismatch_count = ? WHERE run_id = ?", mismatchCount, runId);
    }

    @Override
    public boolean appendIfAbsent(ReconciliationMismatch mismatch) {
        // 부분 유니크 인덱스가 "미해결 상태의 같은 차이"를 한 건으로 유지합니다.
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO reconciliation_mismatch
                    (mismatch_id, run_id, mismatch_type, reference_type, reference_id,
                     external_reference_id, internal_amount, external_amount, currency, detail,
                     resolution_status, detected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                ON CONFLICT (reference_type, reference_id, mismatch_type)
                    WHERE resolution_status = 'OPEN'
                    DO NOTHING
                """,
                mismatch.mismatchId(),
                mismatch.runId(),
                mismatch.type().name(),
                mismatch.referenceType(),
                mismatch.referenceId(),
                mismatch.externalReferenceId(),
                mismatch.internalAmount(),
                mismatch.externalAmount(),
                mismatch.currency(),
                mismatch.detail(),
                Timestamp.from(mismatch.detectedAt()));
        return inserted == 1;
    }

    @Override
    public Optional<ReconciliationMismatch> findById(UUID mismatchId) {
        List<ReconciliationMismatch> rows = jdbcTemplate.query(
                "SELECT * FROM reconciliation_mismatch WHERE mismatch_id = ?", ROW_MAPPER, mismatchId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<ReconciliationMismatch> findOpen(MismatchType type, int limit) {
        if (type == null) {
            return jdbcTemplate.query(
                    """
                    SELECT * FROM reconciliation_mismatch
                     WHERE resolution_status = 'OPEN'
                     ORDER BY detected_at DESC
                     LIMIT ?
                    """,
                    ROW_MAPPER,
                    limit);
        }
        return jdbcTemplate.query(
                """
                SELECT * FROM reconciliation_mismatch
                 WHERE resolution_status = 'OPEN' AND mismatch_type = ?
                 ORDER BY detected_at DESC
                 LIMIT ?
                """,
                ROW_MAPPER,
                type.name(),
                limit);
    }

    @Override
    public void resolve(ReconciliationMismatch resolved) {
        jdbcTemplate.update(
                """
                UPDATE reconciliation_mismatch
                   SET resolution_status = ?, resolution_type = ?, resolved_by = ?,
                       resolution_reason = ?, adjustment_ledger_transaction_id = ?, resolved_at = ?
                 WHERE mismatch_id = ? AND resolution_status = 'OPEN'
                """,
                resolved.resolutionStatus().name(),
                resolved.resolutionType(),
                resolved.resolvedBy(),
                resolved.resolutionReason(),
                resolved.adjustmentLedgerTransactionId() == null
                        ? null
                        : resolved.adjustmentLedgerTransactionId().value(),
                resolved.resolvedAt() == null ? null : Timestamp.from(resolved.resolvedAt()),
                resolved.mismatchId());
    }
}

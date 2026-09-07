package io.parity.pay.api.reconciliation;

import io.parity.pay.reconciliation.application.port.out.ReconciliationSourcePort;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.ExternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.InternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.Outcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 대사 대상 기록 조회 어댑터.
 *
 * <p>여러 모듈의 테이블을 함께 읽는 곳은 여기 한 곳입니다. reconciliation 모듈은 어떤 테이블이
 * 있는지 알지 못하고, 조립 지점인 이 어댑터가 읽어 정규화된 기록으로 넘깁니다.
 * 근거: docs/05-technical-design.md §5
 *
 * <p>외부 참조 키는 업무 ID를 그대로 사용합니다. 외부기관에 요청할 때 쓰는 멱등 키가 업무 ID이므로,
 * 우리 쪽 상태가 미확정이어도 같은 키로 짝을 찾을 수 있습니다.
 */
@Component
class JdbcReconciliationSourceAdapter implements ReconciliationSourcePort {

    private final JdbcTemplate jdbcTemplate;

    JdbcReconciliationSourceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<InternalRecord> loadInternalTopUps(Instant windowStart, Instant windowEnd) {
        return jdbcTemplate.query(
                """
                SELECT t.top_up_id, t.status, t.requested_amount, t.currency, t.requested_at,
                       EXISTS (
                           SELECT 1 FROM ledger_transaction lt
                            WHERE lt.reference_type = 'TOP_UP'
                              AND lt.reference_id = t.top_up_id
                              AND lt.transaction_type = 'TOP_UP_COMPLETED'
                       ) AS has_ledger
                  FROM top_up t
                 WHERE t.requested_at BETWEEN ? AND ?
                """,
                (rs, rowNum) -> new InternalRecord(
                        "TOP_UP",
                        rs.getString("top_up_id"),
                        rs.getString("top_up_id"),
                        topUpOutcome(rs.getString("status")),
                        rs.getLong("requested_amount"),
                        rs.getString("currency"),
                        rs.getBoolean("has_ledger"),
                        rs.getTimestamp("requested_at").toInstant()),
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd));
    }

    @Override
    public List<ExternalRecord> loadExternalWithdrawals(Instant windowStart, Instant windowEnd) {
        return jdbcTemplate.query(
                """
                SELECT external_key, status, amount, created_at
                  FROM mock_bank_withdrawal
                 WHERE created_at BETWEEN ? AND ?
                """,
                (rs, rowNum) -> new ExternalRecord(
                        rs.getString("external_key"),
                        externalOutcome(rs.getString("status")),
                        rs.getLong("amount"),
                        "KRW",
                        rs.getTimestamp("created_at").toInstant()),
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd));
    }

    @Override
    public List<InternalRecord> loadInternalPayouts(Instant windowStart, Instant windowEnd) {
        return jdbcTemplate.query(
                """
                SELECT s.settlement_id, s.status, s.net_amount, s.currency, s.updated_at,
                       EXISTS (
                           SELECT 1 FROM ledger_transaction lt
                            WHERE lt.reference_type = 'SETTLEMENT'
                              AND lt.reference_id = s.settlement_id
                              AND lt.transaction_type = 'SETTLEMENT_PAID'
                       ) AS has_ledger
                  FROM settlement s
                 WHERE s.status IN ('PAYING', 'PAID', 'FAILED', 'UNKNOWN')
                   AND s.updated_at BETWEEN ? AND ?
                """,
                (rs, rowNum) -> new InternalRecord(
                        "SETTLEMENT",
                        rs.getString("settlement_id"),
                        rs.getString("settlement_id"),
                        payoutOutcome(rs.getString("status")),
                        rs.getLong("net_amount"),
                        rs.getString("currency"),
                        rs.getBoolean("has_ledger"),
                        rs.getTimestamp("updated_at").toInstant()),
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd));
    }

    @Override
    public List<ExternalRecord> loadExternalPayouts(Instant windowStart, Instant windowEnd) {
        return jdbcTemplate.query(
                """
                SELECT external_key, status, amount, created_at
                  FROM mock_bank_payout
                 WHERE created_at BETWEEN ? AND ?
                """,
                (rs, rowNum) -> new ExternalRecord(
                        rs.getString("external_key"),
                        externalOutcome(rs.getString("status")),
                        rs.getLong("amount"),
                        "KRW",
                        rs.getTimestamp("created_at").toInstant()),
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd));
    }

    /** 업무 상태를 대사 관점으로 정규화합니다. 확정되지 않은 상태는 모두 PENDING입니다. */
    private static Outcome topUpOutcome(String status) {
        return switch (status) {
            case "SUCCEEDED" -> Outcome.SUCCEEDED;
            case "FAILED" -> Outcome.FAILED;
            default -> Outcome.PENDING;
        };
    }

    private static Outcome payoutOutcome(String status) {
        return switch (status) {
            case "PAID" -> Outcome.SUCCEEDED;
            case "FAILED" -> Outcome.FAILED;
            default -> Outcome.PENDING;
        };
    }

    private static Outcome externalOutcome(String status) {
        return "SUCCEEDED".equals(status) ? Outcome.SUCCEEDED : Outcome.FAILED;
    }
}

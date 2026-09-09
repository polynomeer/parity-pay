package io.parity.pay.api.reconciliation;

import io.parity.pay.api.mockbank.BankUnknownResultException;
import io.parity.pay.api.mockbank.MockBankClient;
import io.parity.pay.reconciliation.application.port.out.ReconciliationSourcePort;
import io.parity.pay.reconciliation.application.port.out.ReconciliationSourceUnavailableException;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.ExternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.InternalRecord;
import io.parity.pay.reconciliation.domain.ReconciliationRecords.Outcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 대사 대상 기록 조회 어댑터.
 *
 * <p><b>안쪽은 DB에서, 바깥쪽은 기관에서</b> 읽습니다. 우리 기록(충전·정산)은 우리 표에서 읽고,
 * 기관 기록은 기관의 명세 API로 받습니다.
 *
 * <p>예전에는 기관의 표(`mock_bank_withdrawal`, `mock_bank_payout`)를 같은 데이터베이스에서 직접
 * 읽었습니다. 그러면 대사가 절대 실패하지 않습니다 — 조회는 언제나 성공하고, 결과가 비어 있으면
 * "기관에 기록이 없다"가 됩니다. 실제 대사는 파일이나 API로 받고, 못 받는 날이 있습니다. 기관을
 * 별도 프로세스로 떼어 놓고도 이 경로만 데이터베이스를 가로질러 있었습니다.
 *
 * <p>여러 모듈의 테이블을 함께 읽는 곳은 여전히 여기 한 곳입니다. reconciliation 모듈은 어떤 테이블이
 * 있는지 알지 못합니다. 근거: docs/05-technical-design.md §5·§10
 *
 * <p>외부 참조 키는 업무 ID를 그대로 사용합니다. 외부기관에 요청할 때 쓰는 멱등 키가 업무 ID이므로,
 * 우리 쪽 상태가 미확정이어도 같은 키로 짝을 찾을 수 있습니다.
 */
@Component
class JdbcReconciliationSourceAdapter implements ReconciliationSourcePort {

    private final JdbcTemplate jdbcTemplate;
    private final MockBankClient bankClient;

    JdbcReconciliationSourceAdapter(JdbcTemplate jdbcTemplate, MockBankClient bankClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.bankClient = bankClient;
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
        return statement(() -> bankClient.withdrawalStatement(windowStart, windowEnd), "withdrawal statement");
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
        return statement(() -> bankClient.payoutStatement(windowStart, windowEnd), "payout statement");
    }

    /**
     * 기관 명세를 받아 대사 기록으로 옮깁니다.
     *
     * <p>받지 못하면 빈 목록이 아니라 예외입니다. 빈 목록으로 넘기면 대사는 "기관에 기록이 하나도
     * 없다"로 읽고 우리 쪽 기록 전부를 불일치로 올립니다. 운영자는 존재하지 않는 문제를 놓고 보정을
     * 검토하게 되고, 그것이 대사가 할 수 있는 가장 나쁜 일입니다.
     */
    private List<ExternalRecord> statement(Supplier<List<MockBankClient.StatementLine>> fetch, String what) {
        try {
            return fetch.get().stream()
                    .map(line -> new ExternalRecord(
                            line.externalKey(),
                            externalOutcome(line.status()),
                            line.amount(),
                            line.currency(),
                            line.occurredAt()))
                    .toList();
        } catch (BankUnknownResultException e) {
            throw new ReconciliationSourceUnavailableException("could not fetch " + what + " from the bank", e);
        }
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

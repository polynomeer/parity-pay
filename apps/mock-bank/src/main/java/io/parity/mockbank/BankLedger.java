package io.parity.mockbank;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이 기관의 장부.
 *
 * <p>외부기관도 자체 멱등성을 가진다는 전제를 재현합니다. 같은 {@code externalKey}로 재요청해도
 * 출금·지급은 한 번만 일어납니다. 근거: docs/09-consistency-recovery.md §7
 */
@Component
class BankLedger {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    BankLedger(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    Outcome withdraw(String accountNumberToken, long amount, String externalKey) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT status FROM mock_bank_withdrawal WHERE external_key = ?", externalKey);
        if (!existing.isEmpty()) {
            // 이미 처리한 요청입니다. 중복 출금하지 않고 같은 결과를 돌려줍니다.
            return "SUCCEEDED".equals(existing.get(0).get("status"))
                    ? Outcome.succeeded(externalKey)
                    : Outcome.failed("MOCK_BANK_WITHDRAWAL_FAILED");
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE mock_bank_account
                   SET balance = balance - ?
                 WHERE account_number_token = ?
                   AND balance >= ?
                """,
                amount,
                accountNumberToken,
                amount);
        if (updated != 1) {
            return Outcome.failed("MOCK_BANK_INSUFFICIENT_FUNDS");
        }

        UUID accountId = jdbcTemplate.queryForObject(
                "SELECT mock_account_id FROM mock_bank_account WHERE account_number_token = ?",
                UUID.class,
                accountNumberToken);
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO mock_bank_withdrawal
                        (withdrawal_id, external_key, mock_account_id, amount, status, created_at)
                    VALUES (?, ?, ?, ?, 'SUCCEEDED', ?)
                    """,
                    UUID.randomUUID(),
                    externalKey,
                    accountId,
                    amount,
                    Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            // 동시에 같은 키가 처리되었습니다. 출금은 여전히 한 번입니다.
            return Outcome.succeeded(externalKey);
        }
        return Outcome.succeeded(externalKey);
    }

    @Transactional
    void openAccount(UUID accountId, String accountNumberToken, long balance, String currency) {
        jdbcTemplate.update(
                """
                INSERT INTO mock_bank_account
                    (mock_account_id, account_number_token, balance, currency, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (account_number_token) DO NOTHING
                """,
                accountId,
                accountNumberToken,
                balance,
                currency,
                Timestamp.from(clock.instant()));
    }

    @Transactional
    Outcome payout(String externalKey, UUID merchantId, long amount) {
        List<Map<String, Object>> existing =
                jdbcTemplate.queryForList("SELECT status FROM mock_bank_payout WHERE external_key = ?", externalKey);
        if (!existing.isEmpty()) {
            return "SUCCEEDED".equals(existing.get(0).get("status"))
                    ? Outcome.succeeded(externalKey)
                    : Outcome.failed("MOCK_BANK_PAYOUT_FAILED");
        }
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO mock_bank_payout
                        (payout_id, external_key, merchant_id, amount, status, created_at)
                    VALUES (?, ?, ?, ?, 'SUCCEEDED', ?)
                    """,
                    UUID.randomUUID(),
                    externalKey,
                    merchantId,
                    amount,
                    Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            return Outcome.succeeded(externalKey);
        }
        return Outcome.succeeded(externalKey);
    }

    /** 기관에 남은 사실만 답합니다. 우리 쪽 상태는 모릅니다. */
    Optional<String> withdrawalStatus(String externalKey) {
        return firstStatus("SELECT status FROM mock_bank_withdrawal WHERE external_key = ?", externalKey);
    }

    Optional<String> payoutStatus(String externalKey) {
        return firstStatus("SELECT status FROM mock_bank_payout WHERE external_key = ?", externalKey);
    }

    private Optional<String> firstStatus(String sql, String externalKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, externalKey);
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of((String) rows.get(0).get("status"));
    }

    /**
     * 기간 내 출금 명세입니다.
     *
     * <p>실제 은행이 대사용으로 주는 것이 이런 명세입니다. 우리 쪽이 기관의 표를 직접 읽는 대신
     * 이 경로로 받아야, 기관이 명세를 주지 못하는 상황이 우리 쪽 코드에 드러납니다.
     */
    List<Statement> withdrawalStatement(java.time.Instant from, java.time.Instant to) {
        return statement("mock_bank_withdrawal", from, to);
    }

    /** 기간 내 지급 명세입니다. */
    List<Statement> payoutStatement(java.time.Instant from, java.time.Instant to) {
        return statement("mock_bank_payout", from, to);
    }

    private List<Statement> statement(String table, java.time.Instant from, java.time.Instant to) {
        return jdbcTemplate.query(
                "SELECT external_key, status, amount, created_at FROM " + table
                        + " WHERE created_at BETWEEN ? AND ? ORDER BY created_at",
                (rs, rowNum) -> new Statement(
                        rs.getString("external_key"),
                        rs.getString("status"),
                        rs.getLong("amount"),
                        "KRW",
                        rs.getTimestamp("created_at").toInstant()),
                Timestamp.from(from),
                Timestamp.from(to));
    }

    /** 명세 한 줄. 기관이 알려주는 사실만 담습니다. */
    record Statement(String externalKey, String status, long amount, String currency, java.time.Instant occurredAt) {}

    record Outcome(boolean succeeded, String externalReferenceId, String failureReason) {

        static Outcome succeeded(String externalReferenceId) {
            return new Outcome(true, externalReferenceId, null);
        }

        static Outcome failed(String failureReason) {
            return new Outcome(false, null, failureReason);
        }
    }
}

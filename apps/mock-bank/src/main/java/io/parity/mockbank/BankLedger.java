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
     * 건별 기록 전체입니다.
     *
     * <p>상태만 돌려주던 것으로는 우리 쪽 타임라인이 금액과 시각을 채울 수 없어 기관의 표를 직접
     * 읽고 있었습니다. 기관이 알려줄 수 있는 사실이므로 여기서 답합니다.
     */
    Optional<Statement> withdrawalRecord(String externalKey) {
        return firstRecord("mock_bank_withdrawal", externalKey);
    }

    Optional<Statement> payoutRecord(String externalKey) {
        return firstRecord("mock_bank_payout", externalKey);
    }

    private Optional<Statement> firstRecord(String table, String externalKey) {
        List<Statement> rows = jdbcTemplate.query(
                "SELECT external_key, status, amount, created_at FROM " + table + " WHERE external_key = ?",
                (rs, rowNum) -> new Statement(
                        rs.getString("external_key"),
                        rs.getString("status"),
                        rs.getLong("amount"),
                        "KRW",
                        rs.getTimestamp("created_at").toInstant()),
                externalKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 자기 표를 비웁니다. 시험이 우리 데이터베이스로 기관을 초기화할 수 없게 된 뒤에 필요해졌습니다. */
    @Transactional
    void reset() {
        jdbcTemplate.execute("TRUNCATE mock_bank_withdrawal, mock_bank_payout, mock_bank_account CASCADE");
    }

    /** 시험이 "기관 쪽 기록"을 만들거나 바꾸는 통로입니다. 운영에 배포되지 않는 앱입니다. */
    @Transactional
    void amendWithdrawal(String externalKey, Long amount, boolean delete) {
        // externalKey가 없으면 전부입니다. 시험이 "기관 쪽 기록이 통째로 사라진 날"이나 "금액이
        // 전부 다르게 적힌 날"을 만들 때 씁니다.
        if (delete) {
            if (externalKey == null) {
                jdbcTemplate.update("DELETE FROM mock_bank_withdrawal");
            } else {
                jdbcTemplate.update("DELETE FROM mock_bank_withdrawal WHERE external_key = ?", externalKey);
            }
            return;
        }
        if (amount == null) {
            return;
        }
        if (externalKey == null) {
            jdbcTemplate.update("UPDATE mock_bank_withdrawal SET amount = ?", amount);
        } else {
            jdbcTemplate.update(
                    "UPDATE mock_bank_withdrawal SET amount = ? WHERE external_key = ?", amount, externalKey);
        }
    }

    /** 계좌 잔액 합계입니다. 시험이 계좌 토큰을 모르는 경우에 씁니다(토큰은 우리 쪽에서 해시로 만듭니다). */
    long totalAccountBalance() {
        Long value = jdbcTemplate.queryForObject("SELECT coalesce(sum(balance), 0) FROM mock_bank_account", Long.class);
        return value == null ? 0L : value;
    }

    /** 우리에게 기록이 없는 외부 출금(EXTERNAL_ONLY)을 만들기 위한 통로입니다. */
    @Transactional
    void insertOrphanWithdrawal(String externalKey, long amount, String accountToken) {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO mock_bank_account
                    (mock_account_id, account_number_token, balance, currency, created_at)
                VALUES (?, ?, ?, 'KRW', ?)
                ON CONFLICT (account_number_token) DO NOTHING
                """,
                accountId,
                accountToken,
                500_000L,
                Timestamp.from(clock.instant()));
        UUID existing = jdbcTemplate.queryForObject(
                "SELECT mock_account_id FROM mock_bank_account WHERE account_number_token = ?",
                UUID.class,
                accountToken);
        jdbcTemplate.update(
                """
                INSERT INTO mock_bank_withdrawal
                    (withdrawal_id, external_key, mock_account_id, amount, status, created_at)
                VALUES (?, ?, ?, ?, 'SUCCEEDED', ?)
                """,
                UUID.randomUUID(),
                externalKey,
                existing,
                amount,
                Timestamp.from(clock.instant()));
    }

    /** 계좌 잔액입니다. 시험이 "기관에서 돈이 나갔는가"를 확인하는 데 씁니다. */
    Optional<Long> accountBalance(String accountToken) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT balance FROM mock_bank_account WHERE account_number_token = ?", Long.class, accountToken);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 표별 행 수입니다. 시험이 "기관에 몇 건 남았는가"를 확인하는 데 씁니다. */
    long count(String table, String status) {
        String sql = "SELECT count(*) FROM "
                + switch (table) {
                    case "withdrawals" -> "mock_bank_withdrawal";
                    case "payouts" -> "mock_bank_payout";
                    default -> throw new IllegalArgumentException("unknown table: " + table);
                };
        Long value = status == null
                ? jdbcTemplate.queryForObject(sql, Long.class)
                : jdbcTemplate.queryForObject(sql + " WHERE status = ?", Long.class, status);
        return value == null ? 0L : value;
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

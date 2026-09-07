package io.parity.pay.api.mockbank;

import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.money.Money;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mock Bank의 계좌 잔액과 출금 기록.
 *
 * <p>외부기관도 자체 멱등성을 가진다는 전제를 재현합니다. 같은 {@code externalKey}로 재요청해도
 * 출금은 한 번만 일어납니다. 근거: docs/09-consistency-recovery.md §7
 */
@Component
public class MockBankLedger {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    MockBankLedger(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** 내부 트랜잭션과 분리된 외부기관의 트랜잭션입니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WithdrawalOutcome withdraw(String accountNumberToken, Money amount, String externalKey) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT status FROM mock_bank_withdrawal WHERE external_key = ?", externalKey);
        if (!existing.isEmpty()) {
            // 이미 처리한 요청입니다. 중복 출금하지 않고 같은 결과를 돌려줍니다.
            String status = (String) existing.get(0).get("status");
            return "SUCCEEDED".equals(status)
                    ? WithdrawalOutcome.succeeded(externalKey)
                    : WithdrawalOutcome.failed("MOCK_BANK_WITHDRAWAL_FAILED");
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE mock_bank_account
                   SET balance = balance - ?
                 WHERE account_number_token = ?
                   AND balance >= ?
                """,
                amount.amount(),
                accountNumberToken,
                amount.amount());

        if (updated != 1) {
            return WithdrawalOutcome.failed("MOCK_BANK_INSUFFICIENT_FUNDS");
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
                    amount.amount(),
                    java.sql.Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            // 동시에 같은 키가 처리되었습니다. 출금은 여전히 한 번입니다.
            return WithdrawalOutcome.succeeded(externalKey);
        }
        return WithdrawalOutcome.succeeded(externalKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void openAccount(BankAccountId bankAccountId, String accountNumberToken, Money initialBalance) {
        jdbcTemplate.update(
                """
                INSERT INTO mock_bank_account
                    (mock_account_id, account_number_token, balance, currency, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (account_number_token) DO NOTHING
                """,
                bankAccountId.value(),
                accountNumberToken,
                initialBalance.amount(),
                initialBalance.currency().name(),
                java.sql.Timestamp.from(clock.instant()));
    }

    /**
     * 외부기관에 남은 요청 기록을 조회합니다. 기록이 없으면 비어 있는 값을 돌려줍니다.
     *
     * <p>실제 기관의 조회 API에 해당합니다. 우리 쪽 상태와 무관하게 외부의 사실만 답합니다.
     */
    public java.util.Optional<String> findWithdrawalStatus(String externalKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status FROM mock_bank_withdrawal WHERE external_key = ?", externalKey);
        return rows.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of((String) rows.get(0).get("status"));
    }

    /**
     * 판매자 지급을 기록합니다. 외부기관 쪽 멱등성을 재현하므로 같은 키로 재요청해도 한 번만
     * 지급됩니다. 근거: docs/04-payment-policy.md §8
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WithdrawalOutcome payout(String externalKey, UUID merchantId, Money amount) {
        List<Map<String, Object>> existing =
                jdbcTemplate.queryForList("SELECT status FROM mock_bank_payout WHERE external_key = ?", externalKey);
        if (!existing.isEmpty()) {
            return "SUCCEEDED".equals(existing.get(0).get("status"))
                    ? WithdrawalOutcome.succeeded(externalKey)
                    : WithdrawalOutcome.failed("MOCK_BANK_PAYOUT_FAILED");
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
                    amount.amount(),
                    java.sql.Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            return WithdrawalOutcome.succeeded(externalKey);
        }
        return WithdrawalOutcome.succeeded(externalKey);
    }

    public java.util.Optional<String> findPayoutStatus(String externalKey) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList("SELECT status FROM mock_bank_payout WHERE external_key = ?", externalKey);
        return rows.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of((String) rows.get(0).get("status"));
    }

    public long balanceOf(String accountNumberToken) {
        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM mock_bank_account WHERE account_number_token = ?", Long.class, accountNumberToken);
        return balance == null ? 0L : balance;
    }

    public record WithdrawalOutcome(boolean succeeded, String externalReferenceId, String failureReason) {

        static WithdrawalOutcome succeeded(String externalReferenceId) {
            return new WithdrawalOutcome(true, externalReferenceId, null);
        }

        static WithdrawalOutcome failed(String reason) {
            return new WithdrawalOutcome(false, null, reason);
        }
    }
}

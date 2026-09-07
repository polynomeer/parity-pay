package io.parity.pay.api.mockbank;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.money.Money;
import io.parity.pay.wallet.application.port.out.BankWithdrawalPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Mock Bank 출금 어댑터.
 *
 * <p>현재는 같은 프로세스 안의 대역입니다. 실제 네트워크 지연·연결 끊김을 주입하려면 Phase 4에서
 * {@code apps/mock-bank}로 분리하고 HTTP 클라이언트로 교체합니다.
 * 근거: docs/13-implementation-checklist.md Phase 4
 */
@Component
class MockBankWithdrawalAdapter implements BankWithdrawalPort {

    private final MockBankLedger mockBankLedger;
    private final MockBankBehavior behavior;
    private final JdbcTemplate jdbcTemplate;

    MockBankWithdrawalAdapter(MockBankLedger mockBankLedger, MockBankBehavior behavior, JdbcTemplate jdbcTemplate) {
        this.mockBankLedger = mockBankLedger;
        this.behavior = behavior;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BankWithdrawalResult withdraw(BankAccountId bankAccountId, Money amount, TopUpId externalIdempotencyKey) {
        String accountNumberToken = accountNumberTokenOf(bankAccountId);
        String externalKey = externalIdempotencyKey.toString();

        return switch (behavior.mode()) {
            case NORMAL -> toResult(mockBankLedger.withdraw(accountNumberToken, amount, externalKey));
            case EXPLICIT_FAILURE -> BankWithdrawalResult.failed("MOCK_BANK_DECLINED");
            case TIMEOUT_BEFORE_WITHDRAWAL ->
            // 외부는 아무것도 하지 않았지만 내부는 그 사실을 알 수 없습니다.
            throw new MockBankTimeoutException("mock bank timed out before processing");
            case TIMEOUT_AFTER_WITHDRAWAL -> {
                mockBankLedger.withdraw(accountNumberToken, amount, externalKey);
                // 외부는 출금을 마쳤지만 응답이 유실됩니다(F-006).
                throw new MockBankTimeoutException("mock bank timed out after processing");
            }
        };
    }

    @Override
    public WithdrawalStatus getStatus(TopUpId externalIdempotencyKey) {
        if (!behavior.statusQueryAvailable()) {
            // 조회 API 자체가 응답하지 않습니다. 결과를 "없음"으로 단정하면 안 됩니다.
            return WithdrawalStatus.UNAVAILABLE;
        }
        return mockBankLedger
                .findWithdrawalStatus(externalIdempotencyKey.toString())
                .map(status -> "SUCCEEDED".equals(status) ? WithdrawalStatus.SUCCEEDED : WithdrawalStatus.FAILED)
                .orElse(WithdrawalStatus.NOT_FOUND);
    }

    private static BankWithdrawalResult toResult(MockBankLedger.WithdrawalOutcome outcome) {
        return outcome.succeeded()
                ? BankWithdrawalResult.succeeded(outcome.externalReferenceId())
                : BankWithdrawalResult.failed(outcome.failureReason());
    }

    private String accountNumberTokenOf(BankAccountId bankAccountId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT account_number_token FROM bank_account WHERE bank_account_id = ? AND status = 'ACTIVE'",
                    String.class,
                    bankAccountId.value());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "bank account not found");
        }
    }

    /** 네트워크 타임아웃을 흉내 냅니다. 실패가 아니라 결과 불명확입니다. 근거: ADR-007 */
    static class MockBankTimeoutException extends RuntimeException {
        MockBankTimeoutException(String message) {
            super(message);
        }
    }
}

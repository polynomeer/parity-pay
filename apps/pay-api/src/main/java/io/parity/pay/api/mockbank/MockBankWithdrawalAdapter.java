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
 * 외부 은행 출금 어댑터.
 *
 * <p>기관은 이제 다른 프로세스에 있고, 이 어댑터는 HTTP로 부릅니다. 응답을 받지 못하면 결과를
 * 모르는 것이며, 그 판단은 {@link MockBankClient}가 예외 하나로 모아 줍니다. 여기서는 그 예외를
 * 잡지 않습니다 — 호출하는 서비스가 `UNKNOWN` 보존을 담당합니다. 근거: ADR-007
 */
@Component
class MockBankWithdrawalAdapter implements BankWithdrawalPort {

    private final MockBankClient client;
    private final JdbcTemplate jdbcTemplate;

    MockBankWithdrawalAdapter(MockBankClient client, JdbcTemplate jdbcTemplate) {
        this.client = client;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BankWithdrawalResult withdraw(BankAccountId bankAccountId, Money amount, TopUpId externalIdempotencyKey) {
        MockBankClient.TransferResponse response = client.withdraw(
                externalIdempotencyKey.toString(), accountNumberTokenOf(bankAccountId), amount.amount());
        return response.succeeded()
                ? BankWithdrawalResult.succeeded(response.externalReferenceId())
                : BankWithdrawalResult.failed(response.failureReason());
    }

    @Override
    public WithdrawalStatus getStatus(TopUpId externalIdempotencyKey) {
        try {
            return client.withdrawalStatus(externalIdempotencyKey.toString())
                    .map(status -> "SUCCEEDED".equals(status) ? WithdrawalStatus.SUCCEEDED : WithdrawalStatus.FAILED)
                    .orElse(WithdrawalStatus.NOT_FOUND);
        } catch (BankUnknownResultException e) {
            // 물어보지 못한 것과 "기록이 없다"는 다릅니다. 후자로 취급하면 조회 장애가 곧
            // "돈이 안 나갔다"는 결론이 됩니다. 근거: docs/09-consistency-recovery.md §7
            return WithdrawalStatus.UNAVAILABLE;
        }
    }

    /**
     * 계좌번호 원문 대신 토큰을 기관에 보냅니다.
     *
     * <p>토큰은 우리 DB에 있습니다. 기관이 그것을 자기 계좌와 맞추는 것은 대역의 단순화이며, 실제
     * 기관이라면 기관이 발급한 식별자를 씁니다.
     */
    private String accountNumberTokenOf(BankAccountId bankAccountId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT account_number_token FROM bank_account WHERE bank_account_id = ?",
                    String.class,
                    bankAccountId.value());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "bank account not found");
        }
    }
}

package io.parity.pay.api.mockbank;

import io.parity.pay.settlement.application.port.out.MerchantPayoutPort;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.money.Money;
import org.springframework.stereotype.Component;

/**
 * 판매자 지급 어댑터.
 *
 * <p>정산 ID를 외부 멱등 키로 사용합니다. 같은 정산을 두 번 지급 요청해도 외부에서 한 번만
 * 처리됩니다. 근거: docs/04-payment-policy.md §8
 */
@Component
class MockBankPayoutAdapter implements MerchantPayoutPort {

    private final MockBankLedger mockBankLedger;
    private final MockBankBehavior behavior;

    MockBankPayoutAdapter(MockBankLedger mockBankLedger, MockBankBehavior behavior) {
        this.mockBankLedger = mockBankLedger;
        this.behavior = behavior;
    }

    @Override
    public PayoutResult pay(SettlementId settlementId, MerchantId merchantId, Money amount) {
        String externalKey = settlementId.toString();

        return switch (behavior.payoutMode()) {
            case NORMAL -> toResult(mockBankLedger.payout(externalKey, merchantId.value(), amount));
            case EXPLICIT_FAILURE -> PayoutResult.failed("MOCK_BANK_PAYOUT_DECLINED");
            case TIMEOUT_BEFORE_WITHDRAWAL ->
                throw new IllegalStateException("mock bank timed out before processing payout");
            case TIMEOUT_AFTER_WITHDRAWAL -> {
                mockBankLedger.payout(externalKey, merchantId.value(), amount);
                // 지급은 끝났지만 응답이 유실됩니다(F-010).
                throw new IllegalStateException("mock bank timed out after processing payout");
            }
        };
    }

    @Override
    public PayoutStatus getStatus(SettlementId settlementId) {
        if (!behavior.payoutStatusQueryAvailable()) {
            return PayoutStatus.UNAVAILABLE;
        }
        return mockBankLedger
                .findPayoutStatus(settlementId.toString())
                .map(status -> "SUCCEEDED".equals(status) ? PayoutStatus.SUCCEEDED : PayoutStatus.FAILED)
                .orElse(PayoutStatus.NOT_FOUND);
    }

    private static PayoutResult toResult(MockBankLedger.WithdrawalOutcome outcome) {
        return outcome.succeeded()
                ? PayoutResult.succeeded(outcome.externalReferenceId())
                : PayoutResult.failed(outcome.failureReason());
    }
}

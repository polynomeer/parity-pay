package io.parity.pay.api.mockbank;

import io.parity.pay.settlement.application.port.out.MerchantPayoutPort;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.money.Money;
import org.springframework.stereotype.Component;

/**
 * 판매자 지급 어댑터.
 *
 * <p>출금과 같은 규칙입니다. 응답을 받지 못하면 지급이 나갔는지 모르며, 그 상태를 그대로 보존해야
 * 합니다(F-010). 근거: ADR-007
 */
@Component
class MockBankPayoutAdapter implements MerchantPayoutPort {

    private final MockBankClient client;

    MockBankPayoutAdapter(MockBankClient client) {
        this.client = client;
    }

    @Override
    public PayoutResult pay(SettlementId settlementId, MerchantId merchantId, Money amount) {
        MockBankClient.TransferResponse response =
                client.payout(settlementId.toString(), merchantId.value(), amount.amount());
        return response.succeeded()
                ? PayoutResult.succeeded(response.externalReferenceId())
                : PayoutResult.failed(response.failureReason());
    }

    @Override
    public PayoutStatus getStatus(SettlementId settlementId) {
        try {
            return client.payoutStatus(settlementId.toString())
                    .map(status -> "SUCCEEDED".equals(status) ? PayoutStatus.SUCCEEDED : PayoutStatus.FAILED)
                    .orElse(PayoutStatus.NOT_FOUND);
        } catch (BankUnknownResultException e) {
            return PayoutStatus.UNAVAILABLE;
        }
    }
}

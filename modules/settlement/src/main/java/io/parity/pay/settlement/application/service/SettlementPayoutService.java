package io.parity.pay.settlement.application.service;

import io.parity.pay.settlement.application.port.in.SettlementUseCases.SettlementView;
import io.parity.pay.settlement.application.port.out.MerchantPayoutPort;
import io.parity.pay.settlement.application.port.out.MerchantPayoutPort.PayoutResult;
import io.parity.pay.settlement.domain.Settlement;
import io.parity.pay.shared.id.SettlementId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 판매자 지급.
 *
 * <p>외부 지급 호출은 어떤 트랜잭션에도 속하지 않습니다. 응답이 유실되면 실패로 단정하지 않고
 * {@code UNKNOWN}으로 보존한 뒤 조회로 확정합니다. 재지급은 하지 않습니다.
 * 근거: docs/04-payment-policy.md §8, ADR-007
 */
@Service
public class SettlementPayoutService {

    private static final Logger log = LoggerFactory.getLogger(SettlementPayoutService.class);

    private final SettlementPayoutTransactions transactions;
    private final MerchantPayoutPort merchantPayoutPort;

    public SettlementPayoutService(
            SettlementPayoutTransactions transactions, MerchantPayoutPort merchantPayoutPort) {
        this.transactions = transactions;
        this.merchantPayoutPort = merchantPayoutPort;
    }

    public SettlementView pay(SettlementId settlementId) {
        Settlement paying = transactions.beginPayout(settlementId);

        PayoutResult result;
        try {
            result = merchantPayoutPort.pay(paying.id(), paying.merchantId(), paying.netAmount());
        } catch (RuntimeException e) {
            log.warn("payout outcome is unknown for settlement {}", paying.id(), e);
            result = PayoutResult.unknown();
        }

        Settlement settled = switch (result.outcome()) {
            case SUCCEEDED -> transactions.completePayout(paying.id(), result.externalReferenceId());
            case FAILED -> transactions.failPayout(paying.id(), result.failureReason());
            case UNKNOWN -> transactions.markUnknown(paying.id());
        };
        return SettlementView.of(settled);
    }
}

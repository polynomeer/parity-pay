package io.parity.pay.settlement.application.port.in;

import io.parity.pay.settlement.domain.Settlement;
import io.parity.pay.settlement.domain.SettlementStatus;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 정산 유스케이스. 근거: FR-016, docs/04-payment-policy.md §8
 *
 * <p>계산과 지급을 나눕니다. 계산은 내부 트랜잭션으로 끝나지만 지급은 외부 호출을 포함하므로 결과가
 * 불명확할 수 있습니다.
 */
public interface SettlementUseCases {

    /** 기간 내 정산 대상 항목을 모아 회차를 만듭니다. */
    SettlementView calculate(MerchantId merchantId, LocalDate periodStart, LocalDate periodEnd);

    /** 계산된 정산을 판매자에게 지급합니다. */
    SettlementView pay(SettlementId settlementId);

    /** 분쟁·위험 등으로 지급을 보류합니다. */
    SettlementView hold(SettlementId settlementId, String reason);

    SettlementView release(SettlementId settlementId);

    SettlementView get(SettlementId settlementId);

    List<SettlementView> listByMerchant(MerchantId merchantId, int limit);

    record SettlementView(
            SettlementId settlementId,
            MerchantId merchantId,
            LocalDate periodStart,
            LocalDate periodEnd,
            Money grossAmount,
            Money cancellationAmount,
            Money feeAmount,
            long adjustmentAmount,
            Money netAmount,
            SettlementStatus status,
            String externalReferenceId,
            Instant paidAt) {

        public static SettlementView of(Settlement settlement) {
            return new SettlementView(
                    settlement.id(),
                    settlement.merchantId(),
                    settlement.periodStart(),
                    settlement.periodEnd(),
                    settlement.grossAmount(),
                    settlement.cancellationAmount(),
                    settlement.feeAmount(),
                    settlement.adjustmentAmount(),
                    settlement.netAmount(),
                    settlement.status(),
                    settlement.externalReferenceId(),
                    settlement.paidAt());
        }
    }
}

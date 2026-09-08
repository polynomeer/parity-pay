package io.parity.pay.settlement.application.port.in;

import io.parity.pay.settlement.domain.Settlement;
import io.parity.pay.settlement.domain.SettlementStatus;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 정산 회차를 밖으로 내보내는 형태입니다.
 *
 * <p>도메인 객체를 그대로 노출하지 않습니다. 어댑터가 응답을 만들 때 필요한 것만 담습니다.
 * 근거: FR-016, docs/05-technical-design.md §5
 */
public record SettlementView(
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

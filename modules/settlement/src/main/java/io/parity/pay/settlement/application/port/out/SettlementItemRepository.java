package io.parity.pay.settlement.application.port.out;

import io.parity.pay.settlement.domain.SettlementItem;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.SettlementId;
import java.time.Instant;
import java.util.List;

public interface SettlementItemRepository {

    /**
     * 정산 항목을 추가합니다. 같은 업무 사실로 만들어진 항목이 이미 있으면 아무것도 하지 않습니다.
     *
     * @return 실제로 추가되었으면 {@code true}. 근거: docs/04-payment-policy.md §8 (중복 정산 금지)
     */
    boolean append(SettlementItem item);

    /** 아직 정산 회차에 묶이지 않은 항목입니다. */
    List<SettlementItem> findEligible(MerchantId merchantId, Instant until, int limit);

    List<SettlementItem> findByPaymentId(PaymentId paymentId);

    List<SettlementItem> findBySettlementId(SettlementId settlementId);

    /** 항목을 정산 회차에 묶고 SETTLED로 표시합니다. */
    void assignToSettlement(List<SettlementItem> items, SettlementId settlementId);
}

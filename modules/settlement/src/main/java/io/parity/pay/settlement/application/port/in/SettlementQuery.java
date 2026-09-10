package io.parity.pay.settlement.application.port.in;

import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import java.util.List;

/**
 * 정산 조회 포트.
 *
 * <p>조회만 있습니다. 계산·지급·보류는 운영 작업이고 호출자가 다릅니다. 판매자 화면은 이 포트만
 * 필요하며, 볼 수 있는 범위를 정하는 것은 이 포트를 쓰는 어댑터의 책임입니다.
 *
 * <p>근거: FR-016, docs/02-prd.md §6
 */
public interface SettlementQuery {

    SettlementView get(SettlementId settlementId);

    List<SettlementView> listByMerchant(MerchantId merchantId, int limit);

    /**
     * 이 정산액이 어떤 업무 사실에서 왔는지입니다.
     *
     * <p>합계만으로는 판매자가 금액을 확인할 수 없습니다. DOC-15 §3.3이 요구한 것은
     * <b>정산 금액의 근거를 추적할 수 있게 하는 것</b>이고, 그러려면 항목이 필요합니다.
     *
     * <p>금액은 부호가 있습니다 — SALE(+), CANCELLATION(-), FEE(-), ADJUSTMENT(+/-). 원장이
     * 아니므로 방향을 부호로 표현하며, 항목 합계가 곧 순액입니다(INV-008).
     */
    List<SettlementItemView> itemsOf(SettlementId settlementId);

    /**
     * 정산 항목 하나입니다.
     *
     * @param sourceReferenceId 이 항목을 만들어낸 업무 사실. 중복 정산을 막는 키이기도 합니다
     */
    record SettlementItemView(
            java.util.UUID itemId,
            java.util.UUID paymentId,
            String itemType,
            long amount,
            String currency,
            String status,
            String sourceReferenceId,
            java.time.Instant occurredAt) {}
}

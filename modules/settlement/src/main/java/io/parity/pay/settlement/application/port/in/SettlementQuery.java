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
}

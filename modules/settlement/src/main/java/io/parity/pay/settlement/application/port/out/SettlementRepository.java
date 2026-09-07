package io.parity.pay.settlement.application.port.out;

import io.parity.pay.settlement.domain.Settlement;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository {

    Optional<Settlement> findById(SettlementId settlementId);

    List<Settlement> findByMerchant(MerchantId merchantId, int limit);

    Settlement save(Settlement settlement);
}

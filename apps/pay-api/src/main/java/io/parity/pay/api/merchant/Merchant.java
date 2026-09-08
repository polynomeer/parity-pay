package io.parity.pay.api.merchant;

import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import java.time.Instant;

/** 등록된 판매자와 그 계정 주인. */
public record Merchant(MerchantId id, String name, MemberId ownerMemberId, String status, Instant createdAt) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}

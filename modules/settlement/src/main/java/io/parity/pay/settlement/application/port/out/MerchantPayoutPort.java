package io.parity.pay.settlement.application.port.out;

import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.money.Money;

/**
 * 판매자 지급 포트.
 *
 * <p>충전과 같은 원칙입니다. 호출은 트랜잭션 밖에서 하고, 타임아웃은 실패가 아니라 결과 불명확이며,
 * 재지급보다 상태 조회를 먼저 합니다. 근거: docs/04-payment-policy.md §8, ADR-007
 */
public interface MerchantPayoutPort {

    PayoutResult pay(SettlementId settlementId, MerchantId merchantId, Money amount);

    PayoutStatus getStatus(SettlementId settlementId);

    enum Outcome {
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    enum PayoutStatus {
        SUCCEEDED,
        FAILED,
        NOT_FOUND,
        UNAVAILABLE
    }

    record PayoutResult(Outcome outcome, String externalReferenceId, String failureReason) {

        public static PayoutResult succeeded(String externalReferenceId) {
            return new PayoutResult(Outcome.SUCCEEDED, externalReferenceId, null);
        }

        public static PayoutResult failed(String reason) {
            return new PayoutResult(Outcome.FAILED, null, reason);
        }

        public static PayoutResult unknown() {
            return new PayoutResult(Outcome.UNKNOWN, null, null);
        }
    }
}

package io.parity.pay.payment.application.port.out;

import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.money.Money;
import java.util.UUID;

/**
 * 외부 PG 승인 포트.
 *
 * <p>호출은 DB 트랜잭션 밖에서 이루어집니다. 결과는 승인·거절·불명확 셋 중 하나이며, 타임아웃은
 * 거절이 아니라 {@code UNKNOWN}입니다. 근거: docs/05-technical-design.md §7, ADR-007
 */
public interface PgApprovalPort {

    /**
     * 이 어댑터가 대표하는 외부 기관의 식별자입니다.
     *
     * <p>PG 미수금은 기관별로 나뉩니다. 어느 PG에게 받을 돈인지 구분되지 않으면 정산도 대사도 할 수
     * 없습니다. 기관이 여럿이 되면 이 값은 포트가 아니라 승인 결과가 들고 와야 합니다.
     */
    UUID providerId();

    /**
     * 승인을 요청합니다.
     *
     * @param paymentId 외부에 전달하는 업무 멱등 키입니다. 같은 결제로 재요청해도 외부에서 중복
     *     승인이 일어나지 않아야 합니다.
     */
    PgApprovalResult approve(PaymentId paymentId, MerchantId merchantId, Money amount, String orderId);

    /**
     * 외부에 남은 승인 결과를 조회합니다.
     *
     * <p>결과를 모를 때 같은 승인을 다시 보내지 않고 이 조회를 먼저 합니다. 외부 멱등성이
     * 불완전하면 재요청은 이중 청구를 만듭니다. 근거: docs/09-consistency-recovery.md §7
     */
    ApprovalStatus getStatus(PaymentId paymentId);

    enum Outcome {
        APPROVED,
        DECLINED,
        /** 결과를 모릅니다. 승인됐을 수도 있습니다. */
        UNKNOWN
    }

    enum ApprovalStatus {
        /** 외부에 승인 기록이 있습니다. */
        APPROVED,
        /** 외부에 거절 기록이 있습니다. */
        DECLINED,
        /** 외부에 요청 기록 자체가 없습니다. 청구가 일어나지 않았을 가능성이 높습니다. */
        NOT_FOUND,
        /** 조회 자체가 실패했습니다. 결과에 대한 정보가 없습니다. */
        UNAVAILABLE
    }

    record PgApprovalResult(Outcome outcome, String externalReferenceId, String failureReason) {

        public static PgApprovalResult approved(String externalReferenceId) {
            return new PgApprovalResult(Outcome.APPROVED, externalReferenceId, null);
        }

        public static PgApprovalResult declined(String failureReason) {
            return new PgApprovalResult(Outcome.DECLINED, null, failureReason);
        }

        public static PgApprovalResult unknown(String externalReferenceId) {
            return new PgApprovalResult(Outcome.UNKNOWN, externalReferenceId, null);
        }
    }
}

package io.parity.pay.payment.application.port.out;

import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.money.Money;

/**
 * 외부 PG 환불 포트.
 *
 * <p>승인과 같은 규칙입니다. 트랜잭션 밖에서 호출하고, 타임아웃은 실패가 아니라 {@code UNKNOWN}이며,
 * 결과를 모를 때는 환불을 다시 보내지 않고 조회합니다. 환불 재요청은 이중 환불이 됩니다.
 *
 * <p>근거: ADR-007, docs/09-consistency-recovery.md §7
 */
public interface PgRefundPort {

    /**
     * 환불을 요청합니다.
     *
     * @param cancellationId 외부에 전달하는 업무 멱등 키입니다. 같은 취소로 재요청해도 외부에서
     *     중복 환불이 일어나지 않아야 합니다.
     */
    PgRefundResult refund(CancellationId cancellationId, PaymentId paymentId, Money amount);

    RefundStatus getStatus(CancellationId cancellationId);

    enum Outcome {
        REFUNDED,
        DECLINED,
        UNKNOWN
    }

    enum RefundStatus {
        REFUNDED,
        DECLINED,
        /** 외부에 환불 기록이 없습니다. 돈이 돌아가지 않았을 가능성이 높습니다. */
        NOT_FOUND,
        UNAVAILABLE
    }

    record PgRefundResult(Outcome outcome, String externalReferenceId, String failureReason) {

        public static PgRefundResult refunded(String externalReferenceId) {
            return new PgRefundResult(Outcome.REFUNDED, externalReferenceId, null);
        }

        public static PgRefundResult declined(String failureReason) {
            return new PgRefundResult(Outcome.DECLINED, null, failureReason);
        }

        public static PgRefundResult unknown(String externalReferenceId) {
            return new PgRefundResult(Outcome.UNKNOWN, externalReferenceId, null);
        }
    }
}

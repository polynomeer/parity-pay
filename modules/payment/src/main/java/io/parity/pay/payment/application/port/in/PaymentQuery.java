package io.parity.pay.payment.application.port.in;

import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.PaymentView;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase.CancellationView;
import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.PaymentId;

/** 결제 조회. 근거: FR-006 */
public interface PaymentQuery {

    PaymentView getPayment(MemberId memberId, PaymentId paymentId);

    PaymentView getPaymentByOrderId(MemberId memberId, String orderId);

    /**
     * 취소 한 건의 상태입니다. 환불 응답이 유실되면 취소는 {@code UNKNOWN}으로 남고 복구가 조회로
     * 확정합니다. 클라이언트가 그 확정을 기다리려면 취소 자체를 볼 수 있어야 합니다(결함 L).
     */
    CancellationView getCancellation(MemberId memberId, PaymentId paymentId, CancellationId cancellationId);
}

package io.parity.pay.payment.application.port.in;

import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.PaymentView;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.PaymentId;

/** 결제 조회. 근거: FR-006 */
public interface PaymentQuery {

    PaymentView getPayment(MemberId memberId, PaymentId paymentId);

    PaymentView getPaymentByOrderId(MemberId memberId, String orderId);
}

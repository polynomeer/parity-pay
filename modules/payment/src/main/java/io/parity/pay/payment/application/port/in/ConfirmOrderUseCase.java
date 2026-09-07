package io.parity.pay.payment.application.port.in;

import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.PaymentId;
import java.time.Instant;

/**
 * 구매확정. 근거: docs/04-payment-policy.md §8
 *
 * <p>결제 승인 즉시 판매자에게 지급하지 않습니다. 구매확정이 있어야 정산 대상이 됩니다.
 */
public interface ConfirmOrderUseCase {

    OrderConfirmationView confirm(MemberId memberId, PaymentId paymentId);

    record OrderConfirmationView(PaymentId paymentId, String orderId, Instant confirmedAt, boolean newlyConfirmed) {}
}

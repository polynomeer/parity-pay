package io.parity.pay.payment.application.port.in;

import io.parity.pay.payment.domain.Payment;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.payment.domain.PaymentStatus;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import java.time.Instant;

/**
 * 페이머니 결제 승인. 근거: FR-005, docs/04-payment-policy.md §4
 *
 * <p>결제·원장·잔액·멱등 기록이 하나의 로컬 트랜잭션에서 커밋됩니다.
 */
public interface ApprovePaymentUseCase {

    PaymentView approve(ApprovePaymentCommand command);

    record ApprovePaymentCommand(
            MemberId memberId,
            String orderId,
            WalletId walletId,
            MerchantId merchantId,
            Money amount,
            PaymentMethod method,
            IdempotencyKey idempotencyKey) {}

    record PaymentView(
            PaymentId paymentId,
            String orderId,
            PaymentStatus status,
            Money requestedAmount,
            Money approvedAmount,
            Money canceledAmount,
            Money cancellableAmount,
            Instant approvedAt) {

        public static PaymentView of(Payment payment) {
            return new PaymentView(
                    payment.id(),
                    payment.orderId(),
                    payment.status(),
                    payment.requestedAmount(),
                    payment.approvedAmount(),
                    payment.completedCancellationAmount(),
                    payment.cancellableAmount(),
                    payment.approvedAt());
        }
    }
}

package io.parity.pay.payment.application.port.in;

import io.parity.pay.payment.domain.CancellationStatus;
import io.parity.pay.payment.domain.PaymentCancellation;
import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import java.time.Instant;

/**
 * 결제 취소(전액·부분). 근거: FR-007, FR-013, docs/04-payment-policy.md §6
 *
 * <p>누적 취소액은 승인액을 넘을 수 없습니다(INV-005). 동시 요청은 저장소의 조건부 UPDATE로
 * 차단합니다.
 */
public interface CancelPaymentUseCase {

    CancellationView cancel(CancelPaymentCommand command);

    record CancelPaymentCommand(
            MemberId memberId,
            PaymentId paymentId,
            Money amount,
            String reason,
            IdempotencyKey idempotencyKey) {}

    record CancellationView(
            CancellationId cancellationId,
            PaymentId paymentId,
            CancellationStatus status,
            Money requestedAmount,
            Money completedAmount,
            Money paymentCanceledAmount,
            Instant completedAt) {

        public static CancellationView of(PaymentCancellation cancellation, Money paymentCanceledAmount) {
            return new CancellationView(
                    cancellation.id(),
                    cancellation.paymentId(),
                    cancellation.status(),
                    cancellation.requestedAmount(),
                    cancellation.completedAmount(),
                    paymentCanceledAmount,
                    cancellation.completedAt());
        }
    }
}

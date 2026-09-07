package io.parity.pay.payment.application.service;

import io.parity.pay.payment.application.event.PaymentEvents;
import io.parity.pay.payment.application.port.in.ConfirmOrderUseCase;
import io.parity.pay.payment.application.port.out.OrderConfirmationRepository;
import io.parity.pay.payment.application.port.out.PaymentRepository;
import io.parity.pay.payment.domain.Payment;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.event.OutboxAppender;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.PaymentId;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매확정.
 *
 * <p>확정 기록과 이벤트가 같은 트랜잭션에 커밋됩니다. 같은 결제를 여러 번 확정해도 정산 대상은 한
 * 번만 만들어집니다. 근거: ADR-005, INV-004
 */
@Service
public class OrderConfirmationService implements ConfirmOrderUseCase {

    private final PaymentRepository paymentRepository;
    private final OrderConfirmationRepository orderConfirmationRepository;
    private final OutboxAppender outboxAppender;
    private final Clock clock;

    public OrderConfirmationService(
            PaymentRepository paymentRepository,
            OrderConfirmationRepository orderConfirmationRepository,
            OutboxAppender outboxAppender,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.orderConfirmationRepository = orderConfirmationRepository;
        this.outboxAppender = outboxAppender;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OrderConfirmationView confirm(MemberId memberId, PaymentId paymentId) {
        Payment payment = paymentRepository
                .findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "payment not found"));
        payment.requireOwnedBy(memberId);

        if (!payment.isApproved()) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "only an approved payment can be confirmed: " + payment.status());
        }

        Instant now = clock.instant();
        boolean newlyConfirmed = orderConfirmationRepository.confirm(payment.id(), payment.orderId(), now);
        if (!newlyConfirmed) {
            Instant confirmedAt =
                    orderConfirmationRepository.findConfirmedAt(payment.id()).orElse(now);
            return new OrderConfirmationView(payment.id(), payment.orderId(), confirmedAt, false);
        }

        // 확정된 금액은 승인액에서 이미 완료된 취소를 뺀 값입니다. 확정 시점의 사실을 그대로 보냅니다.
        outboxAppender.append(PaymentEvents.orderConfirmed(payment, now));
        return new OrderConfirmationView(payment.id(), payment.orderId(), now, true);
    }
}

package io.parity.pay.payment.application.port.out;

import io.parity.pay.shared.id.PaymentId;
import java.time.Instant;
import java.util.Optional;

/**
 * 구매확정 기록.
 *
 * <p>주문 모듈이 없으므로 결제를 기준으로 기록합니다. 실제 서비스에서는 주문 컨텍스트가 소유할
 * 사실이며, 여기서는 정산 대상을 만들어내는 트리거 역할만 합니다.
 */
public interface OrderConfirmationRepository {

    /**
     * 구매확정을 기록합니다.
     *
     * @return 이번에 새로 확정되었으면 {@code true}. 이미 확정된 결제면 {@code false}이며 이벤트를
     *     다시 발행하지 않습니다.
     */
    boolean confirm(PaymentId paymentId, String orderId, Instant confirmedAt);

    Optional<Instant> findConfirmedAt(PaymentId paymentId);
}

package io.parity.pay.payment.application.port.out;

import io.parity.pay.payment.domain.Payment;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.money.Money;
import java.util.Optional;

public interface PaymentRepository {

    Optional<Payment> findById(PaymentId paymentId);

    Optional<Payment> findActiveByOrderId(String orderId);

    /** 이 회원이 이 주문으로 만든 결제 중 살아 있는 것, 없으면 미확정, 없으면 최신 시도입니다. */
    Optional<Payment> findForOrder(MemberId memberId, String orderId);

    Payment save(Payment payment);

    /**
     * 취소 가능액이 남아 있을 때만 처리중 취소액을 늘립니다.
     *
     * <p>조회 후 계산 후 저장이 아니라 조건부 단일 UPDATE입니다. 동시에 도착한 취소 요청이 모두
     * "여유가 있다"고 판단하는 경쟁 조건을 막습니다.
     *
     * @return 갱신된 행 수. 0이면 취소 불가 상태이거나 취소 가능액 초과입니다. 근거: INV-005
     */
    int reserveCancellation(PaymentId paymentId, Money amount);

    /**
     * 예약된 취소를 확정합니다. 누적 취소액이 승인액과 같아지면 결제 상태가 CANCELED가 됩니다.
     *
     * @return 갱신된 행 수. 0이면 예약된 금액이 부족합니다.
     */
    int completeCancellation(PaymentId paymentId, Money amount);

    /** 취소가 실패했을 때 예약 금액만 되돌립니다. */
    int releaseCancellation(PaymentId paymentId, Money amount);
}

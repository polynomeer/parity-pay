package io.parity.pay.payment.application.event;

import io.parity.pay.payment.domain.Payment;
import io.parity.pay.payment.domain.PaymentCancellation;
import io.parity.pay.shared.event.EventEnvelope;
import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.id.WalletId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 결제 도메인 이벤트.
 *
 * <p>파티션 키는 결제 ID입니다. 같은 결제의 승인·취소 이벤트가 순서대로 전달되어야 소비자가 상태
 * 회귀 없이 처리할 수 있습니다. 근거: docs/05-technical-design.md §9
 */
public final class PaymentEvents {

    public static final String PAYMENT_APPROVED = "PaymentApproved";
    public static final String PAYMENT_CANCELLATION_COMPLETED = "PaymentCancellationCompleted";
    public static final String ORDER_CONFIRMED = "OrderConfirmed";

    private PaymentEvents() {}

    public static EventEnvelope paymentApproved(Payment payment, LedgerTransactionId ledgerTransactionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", payment.id().toString());
        payload.put("orderId", payment.orderId());
        payload.put("merchantId", payment.merchantId().toString());
        payload.put("walletId", payment.walletId().toString());
        payload.put("amount", payment.approvedAmount().amount());
        payload.put("currency", payment.approvedAmount().currency().name());
        payload.put("ledgerTransactionId", ledgerTransactionId.toString());
        return EventEnvelope.of(
                PAYMENT_APPROVED,
                1,
                "Payment",
                payment.id().toString(),
                payment.approvedAt(),
                null,
                payload);
    }

    /**
     * 구매확정. 정산 대상을 만들어내는 사실입니다.
     *
     * <p>정산 소비자가 판매자·금액을 알아야 하므로 payload에 함께 담습니다.
     * {@code settleableAmount}는 확정 시점 기준으로 이미 취소된 금액을 뺀 값입니다.
     */
    public static EventEnvelope orderConfirmed(Payment payment, java.time.Instant confirmedAt) {
        long settleableAmount = payment.approvedAmount().amount()
                - payment.completedCancellationAmount().amount();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", payment.orderId());
        payload.put("paymentId", payment.id().toString());
        payload.put("merchantId", payment.merchantId().toString());
        payload.put("settleableAmount", settleableAmount);
        payload.put("currency", payment.approvedAmount().currency().name());
        payload.put("confirmedAt", confirmedAt.toString());
        return EventEnvelope.of(
                ORDER_CONFIRMED, 1, "Payment", payment.id().toString(), confirmedAt, null, payload);
    }

    public static EventEnvelope cancellationCompleted(
            PaymentCancellation cancellation,
            WalletId walletId,
            io.parity.pay.shared.id.MerchantId merchantId,
            LedgerTransactionId ledgerTransactionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cancellationId", cancellation.id().toString());
        payload.put("paymentId", cancellation.paymentId().toString());
        payload.put("walletId", walletId.toString());
        payload.put("merchantId", merchantId.toString());
        payload.put("amount", cancellation.completedAmount().amount());
        payload.put("currency", cancellation.completedAmount().currency().name());
        payload.put("ledgerTransactionId", ledgerTransactionId.toString());
        return EventEnvelope.of(
                PAYMENT_CANCELLATION_COMPLETED,
                1,
                "Payment",
                cancellation.paymentId().toString(),
                cancellation.completedAt(),
                null,
                payload);
    }
}

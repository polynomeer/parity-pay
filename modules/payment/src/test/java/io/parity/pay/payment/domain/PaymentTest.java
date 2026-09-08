package io.parity.pay.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.of("payment-key-0001");

    @Test
    @DisplayName("승인하면 승인액이 요청액으로 확정된다")
    void approveSetsApprovedAmount() {
        Payment approved = approvedPayment(30_000);

        assertThat(approved.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.approvedAmount()).isEqualTo(Money.krw(30_000));
        assertThat(approved.cancellableAmount()).isEqualTo(Money.krw(30_000));
        assertThat(approved.approvedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("AC-005: 부분 취소 후 상태는 PARTIALLY_CANCELED이고 잔여 취소액이 줄어든다")
    void partialCancellationKeepsPaymentCancellable() {
        Payment partiallyCanceled = approvedPayment(30_000)
                .reserveCancellation(Money.krw(10_000), NOW)
                .completeCancellation(Money.krw(10_000), NOW);

        assertThat(partiallyCanceled.status()).isEqualTo(PaymentStatus.PARTIALLY_CANCELED);
        assertThat(partiallyCanceled.completedCancellationAmount()).isEqualTo(Money.krw(10_000));
        assertThat(partiallyCanceled.cancellableAmount()).isEqualTo(Money.krw(20_000));
        assertThat(partiallyCanceled.status().isCancellable()).isTrue();
    }

    @Test
    @DisplayName("누적 취소액이 승인액과 같아지면 CANCELED가 되고 더 취소할 수 없다")
    void fullCancellationClosesPayment() {
        Payment canceled = approvedPayment(30_000)
                .reserveCancellation(Money.krw(30_000), NOW)
                .completeCancellation(Money.krw(30_000), NOW);

        assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceled.cancellableAmount()).isEqualTo(Money.krw(0));
        assertThatThrownBy(() -> canceled.reserveCancellation(Money.krw(1), NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("INV-005: 예약 중인 금액을 포함해 승인액을 넘는 취소는 거절된다")
    void reservedAmountCountsTowardTheCap() {
        Payment reserved = approvedPayment(30_000).reserveCancellation(Money.krw(20_000), NOW);

        assertThat(reserved.cancellableAmount()).isEqualTo(Money.krw(10_000));
        assertThatThrownBy(() -> reserved.reserveCancellation(Money.krw(10_001), NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.CANCELLATION_AMOUNT_EXCEEDED));
    }

    @Test
    @DisplayName("취소가 실패하면 예약만 풀리고 결제 상태는 그대로다")
    void releasingReservationKeepsStatus() {
        Payment released = approvedPayment(30_000)
                .reserveCancellation(Money.krw(10_000), NOW)
                .releaseCancellation(Money.krw(10_000), NOW);

        assertThat(released.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(released.cancellableAmount()).isEqualTo(Money.krw(30_000));
        assertThat(released.completedCancellationAmount()).isEqualTo(Money.krw(0));
    }

    @Test
    @DisplayName("승인되지 않은 결제는 취소할 수 없다")
    void onlyApprovedPaymentsCanBeCanceled() {
        Payment processing = newPayment(30_000).process(NOW);

        assertThatThrownBy(() -> processing.reserveCancellation(Money.krw(1_000), NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PROCESSING");
    }

    @Test
    @DisplayName("READY에서 바로 승인으로 건너뛸 수 없다")
    void rejectsSkippingProcessing() {
        assertThatThrownBy(() -> newPayment(30_000).approve(NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("READY to APPROVED");
    }

    @Test
    @DisplayName("INV-005: 불변조건을 깨는 결제 객체는 만들 수 없다")
    void constructorEnforcesCancellationCap() {
        Payment approved = approvedPayment(30_000);

        assertThatThrownBy(() -> new Payment(
                        approved.id(),
                        approved.orderId(),
                        approved.memberId(),
                        approved.walletId(),
                        approved.merchantId(),
                        approved.requestedAmount(),
                        approved.approvedAmount(),
                        Money.krw(20_000),
                        Money.krw(20_000),
                        approved.method(),
                        PaymentStatus.PARTIALLY_CANCELED,
                        approved.idempotencyKey(),
                        approved.externalReferenceId(),
                        approved.failureReason(),
                        approved.createdAt(),
                        approved.approvedAt(),
                        NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.CANCELLATION_AMOUNT_EXCEEDED));
    }

    private static Payment approvedPayment(long amount) {
        return newPayment(amount).process(NOW).approve(NOW);
    }

    private static Payment newPayment(long amount) {
        return Payment.request(
                "order-1",
                MemberId.generate(),
                WalletId.generate(),
                MerchantId.generate(),
                Money.krw(amount),
                PaymentMethod.PAY_MONEY,
                KEY,
                NOW);
    }
}

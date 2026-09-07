package io.parity.pay.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.statistics.Statistics;

/**
 * 임의의 취소 시퀀스에 대한 속성.
 *
 * <p>예제 기반 테스트는 우리가 생각해 낸 순서만 확인합니다. 취소는 예약·확정·해제가 임의 순서로
 * 섞이고 금액도 제각각이라 사람이 고른 몇 가지 조합으로는 부족합니다. 여기서는 순서와 금액을
 * 무작위로 만들고, 어떤 시퀀스에서도 깨지면 안 되는 것만 확인합니다.
 *
 * <p>금액 0은 만들지 않습니다. 쓰기 경로가 양수만 받으므로(BR-002) 도달할 수 없는 입력입니다.
 *
 * <p>실패하면 jqwik이 seed와 축소된 반례를 출력합니다. 반례는 예제 테스트로 옮겨 코드에 남깁니다.
 * 근거: CLAUDE.md §6, INV-005, docs/04-payment-policy.md §6
 */
class PaymentCancellationPropertiesTest {

    private static final Instant T0 = Instant.parse("2026-09-07T00:00:00Z");

    @Property
    @Label("INV-005: 어떤 취소 시퀀스에서도 완료 취소액 + 처리중 취소액 <= 승인액")
    void cancellationAmountsNeverExceedTheApprovedAmount(
            @ForAll("approvedAmounts") long approved, @ForAll("operations") List<Operation> operations) {
        Payment payment = approvedPayment(approved);
        Money previousCompleted = payment.completedCancellationAmount();

        for (Operation operation : operations) {
            payment = apply(payment, operation);

            // INV-005
            assertThat(payment.completedCancellationAmount().plus(payment.processingCancellationAmount()))
                    .isLessThanOrEqualTo(payment.approvedAmount());
            // 확정된 취소는 되돌아가지 않습니다. 되돌리려면 새 분개가 필요하지, 숫자를 줄이지 않습니다.
            assertThat(payment.completedCancellationAmount()).isGreaterThanOrEqualTo(previousCompleted);
            previousCompleted = payment.completedCancellationAmount();
        }
    }

    @Property
    @Label("상태는 완료 취소액이 결정한다: 0이면 APPROVED, 전액이면 CANCELED, 그 사이면 PARTIALLY_CANCELED")
    void statusFollowsTheCompletedAmount(
            @ForAll("approvedAmounts") long approved, @ForAll("operations") List<Operation> operations) {
        Payment payment = approvedPayment(approved);

        for (Operation operation : operations) {
            payment = apply(payment, operation);

            Money completed = payment.completedCancellationAmount();
            if (completed.isZero()) {
                assertThat(payment.status()).isEqualTo(PaymentStatus.APPROVED);
            } else if (completed.equals(payment.approvedAmount())) {
                assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELED);
            } else {
                assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_CANCELED);
            }
        }

        // 생성기가 한쪽으로 쏠리면 위 단정은 통과하면서도 아무것도 검증하지 않습니다. 세 상태가
        // 모두 나오는지 확인해 그 상황을 실패로 만듭니다.
        Statistics.label("도달한 결제 상태").collect(payment.status());
        Statistics.label("도달한 결제 상태").coverage(coverage -> {
            coverage.check(PaymentStatus.APPROVED).percentage(p -> p > 1.0);
            coverage.check(PaymentStatus.PARTIALLY_CANCELED).percentage(p -> p > 1.0);
            coverage.check(PaymentStatus.CANCELED).percentage(p -> p > 1.0);
        });
    }

    @Property
    @Label("취소 가능액은 항상 승인액 - 완료 - 처리중이다")
    void cancellableAmountIsWhatIsLeft(
            @ForAll("approvedAmounts") long approved, @ForAll("operations") List<Operation> operations) {
        Payment payment = approvedPayment(approved);

        for (Operation operation : operations) {
            payment = apply(payment, operation);

            Money expected = payment.approvedAmount()
                    .minus(payment.completedCancellationAmount())
                    .minus(payment.processingCancellationAmount());
            assertThat(payment.cancellableAmount()).isEqualTo(expected);
        }
    }

    @Property
    @Label("전액 취소가 끝난 결제는 더 이상 취소를 받지 않는다")
    void fullyCanceledPaymentRejectsFurtherCancellation(
            @ForAll("approvedAmounts") long approved, @ForAll("operations") List<Operation> operations) {
        Payment payment = approvedPayment(approved);
        for (Operation operation : operations) {
            payment = apply(payment, operation);
        }

        if (payment.status() != PaymentStatus.CANCELED) {
            return;
        }
        Payment canceled = payment;
        assertThatThrownBy(() -> canceled.reserveCancellation(Money.krw(1), T0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Property
    @Label("예약 거부 사유는 상태와 남은 금액으로 결정된다")
    void reservationRejectionReasonIsDeterminedByState(
            @ForAll("approvedAmounts") long approved,
            @ForAll("operations") List<Operation> operations,
            @ForAll("percents") int percent) {
        Payment payment = approvedPayment(approved);
        for (Operation operation : operations) {
            payment = apply(payment, operation);
        }

        Money requested = portion(payment.approvedAmount(), percent);
        if (!requested.isPositive()) {
            return;
        }

        boolean cancellable = payment.status().isCancellable();
        boolean withinCapacity = !requested.isGreaterThan(payment.cancellableAmount());
        Payment current = payment;

        if (cancellable && withinCapacity) {
            assertThat(current.reserveCancellation(requested, T0).processingCancellationAmount())
                    .isEqualTo(current.processingCancellationAmount().plus(requested));
            return;
        }
        ErrorCode expected = cancellable ? ErrorCode.CANCELLATION_AMOUNT_EXCEEDED : ErrorCode.INVALID_STATE_TRANSITION;
        assertThatThrownBy(() -> current.reserveCancellation(requested, T0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(expected);
    }

    /**
     * 명령 하나를 적용합니다.
     *
     * <p>거부된 명령은 상태를 바꾸지 않습니다. Payment가 불변이므로 예외를 삼키고 이전 인스턴스를
     * 그대로 돌려주는 것으로 충분합니다. 무엇이 거부되는지는 별도 속성에서 확인합니다.
     */
    private static Payment apply(Payment payment, Operation operation) {
        try {
            return switch (operation.kind()) {
                case RESERVE -> {
                    Money amount = portion(payment.approvedAmount(), operation.percent());
                    yield amount.isPositive() ? payment.reserveCancellation(amount, T0) : payment;
                }
                case COMPLETE -> {
                    Money amount = portion(payment.processingCancellationAmount(), operation.percent());
                    yield amount.isPositive() ? payment.completeCancellation(amount, T0) : payment;
                }
                case RELEASE -> {
                    Money amount = portion(payment.processingCancellationAmount(), operation.percent());
                    yield amount.isPositive() ? payment.releaseCancellation(amount, T0) : payment;
                }
            };
        } catch (BusinessException e) {
            return payment;
        }
    }

    /** 기준 금액의 일정 비율입니다. 100%는 전액이며, 전액 취소에 도달할 수 있어야 합니다. */
    private static Money portion(Money base, int percent) {
        if (percent >= 100) {
            return base;
        }
        return Money.of(base.amount() * percent / 100, base.currency());
    }

    private static Payment approvedPayment(long approved) {
        return Payment.request(
                        "order-" + approved,
                        MemberId.generate(),
                        WalletId.generate(),
                        MerchantId.generate(),
                        Money.of(approved, CurrencyCode.KRW),
                        PaymentMethod.PAY_MONEY,
                        IdempotencyKey.of("prop-" + approved),
                        T0)
                .process(T0)
                .approve(T0);
    }

    @Provide
    Arbitrary<Long> approvedAmounts() {
        // 100원 미만이면 비율 계산이 대부분 0이 되어 시퀀스가 의미를 잃습니다.
        return Arbitraries.longs().between(100L, 10_000_000L);
    }

    /**
     * 취소 비율입니다.
     *
     * <p>고르게 뽑으면 전액 취소(100%)가 거의 나오지 않아 CANCELED 상태에 도달하지 못합니다.
     * 균등 분포가 아니라 경계값을 자주 뽑는 분포를 씁니다. 실제로 도달하는지는 통계 검사가
     * 확인합니다.
     */
    @Provide
    Arbitrary<Integer> percents() {
        return Arbitraries.frequencyOf(
                Tuple.of(4, Arbitraries.integers().between(1, 99)),
                Tuple.of(4, Arbitraries.just(100)),
                Tuple.of(1, Arbitraries.integers().between(101, 120)));
    }

    @Provide
    Arbitrary<List<Operation>> operations() {
        Arbitrary<Operation> operation = Combinators.combine(Arbitraries.of(Operation.Kind.values()), percents())
                .as(Operation::new);
        return operation.list().ofMinSize(1).ofMaxSize(12);
    }

    record Operation(Kind kind, int percent) {
        enum Kind {
            RESERVE,
            COMPLETE,
            RELEASE
        }
    }
}

package io.parity.pay.payment.domain;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.money.Money;

/**
 * 취소 가능액 계산.
 *
 * <p>{@code 승인액 - 완료 취소액 - 처리중 취소액}입니다. 처리 중 금액을 빼는 이유는 동시에 도착한
 * 취소 요청들이 각각 "아직 여유가 있다"고 판단해 승인액을 초과하는 것을 막기 위해서입니다.
 *
 * <p>근거: docs/04-payment-policy.md §6, INV-005
 */
public record CancellationCapacity(Money approved, Money completed, Money processing) {

    public CancellationCapacity {
        approved.requireSameCurrency(completed);
        approved.requireSameCurrency(processing);
    }

    public Money remaining() {
        return approved.minus(completed).minus(processing);
    }

    /** 요청액이 취소 가능액을 넘으면 예외입니다. */
    public void requireCancellable(Money requested) {
        approved.requireSameCurrency(requested);
        if (!requested.isPositive()) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "cancellation amount must be positive");
        }
        if (requested.isGreaterThan(remaining())) {
            throw new BusinessException(
                    ErrorCode.CANCELLATION_AMOUNT_EXCEEDED,
                    "cancellation amount exceeds the remaining cancellable amount");
        }
    }
}

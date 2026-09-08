package io.parity.pay.payment.domain;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * 취소 Aggregate.
 *
 * <p>원결제를 참조하는 독립 Aggregate이며 자체 멱등 키를 가집니다. 원결제 원장을 수정하지 않고 새
 * 상쇄 분개(JE-004)를 만듭니다. 근거: docs/04-payment-policy.md §6, ADR-009
 */
public record PaymentCancellation(
        CancellationId id,
        PaymentId paymentId,
        Money requestedAmount,
        Money completedAmount,
        String reason,
        CancellationStatus status,
        IdempotencyKey idempotencyKey,
        String externalReferenceId,
        Instant requestedAt,
        Instant completedAt) {

    public PaymentCancellation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");

        if (!requestedAmount.isPositive()) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "cancellation amount must be positive");
        }
        requestedAmount.requireSameCurrency(completedAmount);
        if (completedAmount.isGreaterThan(requestedAmount)) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "completed amount must not exceed requested amount");
        }
    }

    public static PaymentCancellation request(
            PaymentId paymentId, Money amount, String reason, IdempotencyKey idempotencyKey, Instant now) {
        return new PaymentCancellation(
                CancellationId.generate(),
                paymentId,
                amount,
                Money.zero(amount.currency()),
                reason,
                CancellationStatus.REQUESTED,
                idempotencyKey,
                null,
                now,
                null);
    }

    public PaymentCancellation begin() {
        requireTransitionTo(CancellationStatus.PROCESSING);
        return new PaymentCancellation(
                id,
                paymentId,
                requestedAmount,
                completedAmount,
                reason,
                CancellationStatus.PROCESSING,
                idempotencyKey,
                externalReferenceId,
                requestedAt,
                null);
    }

    public PaymentCancellation complete(Instant now) {
        requireTransitionTo(CancellationStatus.COMPLETED);
        return new PaymentCancellation(
                id,
                paymentId,
                requestedAmount,
                requestedAmount,
                reason,
                CancellationStatus.COMPLETED,
                idempotencyKey,
                externalReferenceId,
                requestedAt,
                now);
    }

    public PaymentCancellation fail(Instant now) {
        requireTransitionTo(CancellationStatus.FAILED);
        return new PaymentCancellation(
                id,
                paymentId,
                requestedAmount,
                Money.zero(requestedAmount.currency()),
                reason,
                CancellationStatus.FAILED,
                idempotencyKey,
                externalReferenceId,
                requestedAt,
                now);
    }

    /**
     * 외부 환불이 확인되어 완료합니다. 외부 참조를 함께 남깁니다.
     *
     * <p>이 값이 없으면 나중에 무엇을 조회해 대사할지 알 수 없습니다.
     */
    public PaymentCancellation completeExternally(String externalReferenceId, Instant now) {
        requireTransitionTo(CancellationStatus.COMPLETED);
        return new PaymentCancellation(
                id,
                paymentId,
                requestedAmount,
                requestedAmount,
                reason,
                CancellationStatus.COMPLETED,
                idempotencyKey,
                externalReferenceId == null ? this.externalReferenceId : externalReferenceId,
                requestedAt,
                now);
    }

    /**
     * 환불 결과를 모릅니다.
     *
     * <p>실패로 적지 않습니다. 돈이 이미 돌아갔을 수 있고, 실패로 적으면 같은 금액을 다시 환불할
     * 수 있게 됩니다. 근거: ADR-007
     */
    public PaymentCancellation markUnknown(String externalReferenceId, Instant now) {
        requireTransitionTo(CancellationStatus.UNKNOWN);
        return new PaymentCancellation(
                id,
                paymentId,
                requestedAmount,
                completedAmount,
                reason,
                CancellationStatus.UNKNOWN,
                idempotencyKey,
                externalReferenceId == null ? this.externalReferenceId : externalReferenceId,
                requestedAt,
                now);
    }

    public boolean isCompleted() {
        return status == CancellationStatus.COMPLETED;
    }

    private void requireTransitionTo(CancellationStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "cancellation cannot move from " + status + " to " + next);
        }
    }
}

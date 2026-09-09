package io.parity.pay.payment.domain;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * 결제 Aggregate.
 *
 * <p>불변 객체이며 전이 메서드는 새 인스턴스를 반환합니다. 취소 금액은 두 개로 나눠 관리합니다.
 *
 * <ul>
 *   <li>{@code completedCancellationAmount} — 원장까지 반영된 취소액
 *   <li>{@code processingCancellationAmount} — 예약되었지만 아직 확정되지 않은 취소액
 * </ul>
 *
 * <p>둘의 합은 승인액을 넘을 수 없습니다(INV-005). 근거: docs/06-domain-state-design.md §2·§6
 */
public record Payment(
        PaymentId id,
        String orderId,
        MemberId memberId,
        WalletId walletId,
        MerchantId merchantId,
        Money requestedAmount,
        Money approvedAmount,
        Money completedCancellationAmount,
        Money processingCancellationAmount,
        PaymentMethod method,
        PaymentStatus status,
        IdempotencyKey idempotencyKey,
        // 외부 PG가 준 승인 참조입니다. 페이머니 결제는 외부 호출이 없어 null입니다.
        String externalReferenceId,
        // 외부가 거절한 이유입니다. 사용자에게 그대로 보여 주지 않습니다.
        String failureReason,
        Instant createdAt,
        Instant approvedAt,
        Instant updatedAt) {

    public Payment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(memberId, "memberId must not be null");
        Objects.requireNonNull(walletId, "walletId must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (!requestedAmount.isPositive()) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "payment amount must be positive");
        }
        requestedAmount.requireSameCurrency(approvedAmount);
        requestedAmount.requireSameCurrency(completedCancellationAmount);
        requestedAmount.requireSameCurrency(processingCancellationAmount);

        if (approvedAmount.isGreaterThan(requestedAmount)) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "approved amount must not exceed requested amount");
        }
        // INV-005
        if (completedCancellationAmount.plus(processingCancellationAmount).isGreaterThan(approvedAmount)) {
            throw new BusinessException(
                    ErrorCode.CANCELLATION_AMOUNT_EXCEEDED, "cancellation amounts must not exceed the approved amount");
        }
    }

    public static Payment request(
            String orderId,
            MemberId memberId,
            WalletId walletId,
            MerchantId merchantId,
            Money amount,
            PaymentMethod method,
            IdempotencyKey idempotencyKey,
            Instant now) {
        Money zero = Money.zero(amount.currency());
        return new Payment(
                PaymentId.generate(),
                orderId,
                memberId,
                walletId,
                merchantId,
                amount,
                zero,
                zero,
                zero,
                method,
                PaymentStatus.READY,
                idempotencyKey,
                null,
                null,
                now,
                null,
                now);
    }

    public Payment process(Instant now) {
        requireTransitionTo(PaymentStatus.PROCESSING);
        return withStatus(PaymentStatus.PROCESSING, approvedAmount, approvedAt, now);
    }

    /** 승인은 금융 확정입니다. 잔액 차감과 결제 분개(JE-003)가 같은 트랜잭션에 함께 있습니다. */
    public Payment approve(Instant now) {
        requireTransitionTo(PaymentStatus.APPROVED);
        return withStatus(PaymentStatus.APPROVED, requestedAmount, now, now);
    }

    /**
     * 외부 PG가 승인했습니다.
     *
     * <p>외부 참조를 함께 기록합니다. 이 값이 없으면 나중에 무엇을 조회해 대사할지 알 수 없습니다.
     * 근거: docs/09-consistency-recovery.md §7
     */
    public Payment approveExternally(String externalReferenceId, Instant now) {
        requireTransitionTo(PaymentStatus.APPROVED);
        if (externalReferenceId == null || externalReferenceId.isBlank()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "an externally approved payment must carry the provider reference");
        }
        return withExternal(PaymentStatus.APPROVED, requestedAmount, externalReferenceId, null, now, now);
    }

    /** 외부가 명시적으로 거절했습니다. 결과를 아는 실패이므로 UNKNOWN이 아닙니다. */
    public Payment declineExternally(String failureReason, Instant now) {
        requireTransitionTo(PaymentStatus.FAILED);
        return withExternal(
                PaymentStatus.FAILED,
                Money.zero(requestedAmount.currency()),
                externalReferenceId,
                failureReason,
                approvedAt,
                now);
    }

    /**
     * 외부 결과를 모릅니다.
     *
     * <p>타임아웃을 실패로 단정하지 않습니다. 돈이 움직였을 수도 있으므로 상태를 보존하고 조회로
     * 확정합니다. 근거: ADR-007, CLAUDE.md §3
     */
    public Payment markUnknown(String externalReferenceId, Instant now) {
        requireTransitionTo(PaymentStatus.UNKNOWN);
        return withExternal(PaymentStatus.UNKNOWN, approvedAmount, externalReferenceId, failureReason, approvedAt, now);
    }

    public boolean isUnknown() {
        return status == PaymentStatus.UNKNOWN;
    }

    public Payment fail(Instant now) {
        requireTransitionTo(PaymentStatus.FAILED);
        return withStatus(PaymentStatus.FAILED, Money.zero(requestedAmount.currency()), approvedAt, now);
    }

    /**
     * 취소 가능액을 예약합니다.
     *
     * <p>실제 동시성 차단은 저장소의 조건부 UPDATE가 담당하고, 이 메서드는 같은 규칙을 도메인에서
     * 표현해 단위 테스트로 검증할 수 있게 합니다.
     */
    public Payment reserveCancellation(Money amount, Instant now) {
        requireCancellable();
        capacity().requireCancellable(amount);
        return new Payment(
                id,
                orderId,
                memberId,
                walletId,
                merchantId,
                requestedAmount,
                approvedAmount,
                completedCancellationAmount,
                processingCancellationAmount.plus(amount),
                method,
                status,
                idempotencyKey,
                externalReferenceId,
                failureReason,
                createdAt,
                approvedAt,
                now);
    }

    /** 예약된 취소를 확정합니다. 누적 취소액이 승인액과 같아지면 결제는 CANCELED입니다. */
    public Payment completeCancellation(Money amount, Instant now) {
        if (amount.isGreaterThan(processingCancellationAmount)) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "completing more than the reserved cancellation amount");
        }
        Money completed = completedCancellationAmount.plus(amount);
        PaymentStatus next =
                completed.equals(approvedAmount) ? PaymentStatus.CANCELED : PaymentStatus.PARTIALLY_CANCELED;
        requireTransitionTo(next);
        return new Payment(
                id,
                orderId,
                memberId,
                walletId,
                merchantId,
                requestedAmount,
                approvedAmount,
                completed,
                processingCancellationAmount.minus(amount),
                method,
                next,
                idempotencyKey,
                externalReferenceId,
                failureReason,
                createdAt,
                approvedAt,
                now);
    }

    /** 취소가 실패하면 예약 금액만 풀고 결제 상태는 바꾸지 않습니다. */
    public Payment releaseCancellation(Money amount, Instant now) {
        return new Payment(
                id,
                orderId,
                memberId,
                walletId,
                merchantId,
                requestedAmount,
                approvedAmount,
                completedCancellationAmount,
                processingCancellationAmount.minus(amount),
                method,
                status,
                idempotencyKey,
                externalReferenceId,
                failureReason,
                createdAt,
                approvedAt,
                now);
    }

    public CancellationCapacity capacity() {
        return new CancellationCapacity(approvedAmount, completedCancellationAmount, processingCancellationAmount);
    }

    public Money cancellableAmount() {
        return capacity().remaining();
    }

    public boolean isApproved() {
        return status == PaymentStatus.APPROVED || status == PaymentStatus.PARTIALLY_CANCELED;
    }

    public void requireOwnedBy(MemberId candidate) {
        if (!memberId.equals(candidate)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "payment not found");
        }
    }

    private void requireCancellable() {
        if (!status.isCancellable()) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "payment cannot be canceled in status " + status);
        }
    }

    private void requireTransitionTo(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "payment cannot move from " + status + " to " + next);
        }
    }

    private Payment withStatus(PaymentStatus next, Money approved, Instant approvedAt, Instant updatedAt) {
        return withExternal(next, approved, externalReferenceId, failureReason, approvedAt, updatedAt);
    }

    private Payment withExternal(
            PaymentStatus next,
            Money approved,
            String externalReferenceId,
            String failureReason,
            Instant approvedAt,
            Instant updatedAt) {
        return new Payment(
                id,
                orderId,
                memberId,
                walletId,
                merchantId,
                requestedAmount,
                approved,
                completedCancellationAmount,
                processingCancellationAmount,
                method,
                next,
                idempotencyKey,
                externalReferenceId,
                failureReason,
                createdAt,
                approvedAt,
                updatedAt);
    }
}

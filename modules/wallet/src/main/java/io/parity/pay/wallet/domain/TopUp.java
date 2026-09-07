package io.parity.pay.wallet.domain;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * 충전 Aggregate.
 *
 * <p>상태 전이는 {@link TopUpStatus}의 허용표를 따릅니다. 허용되지 않은 전이는
 * {@code INVALID_STATE_TRANSITION}입니다. 근거: docs/06-domain-state-design.md §4·§5
 *
 * <p>불변 객체이며 전이 메서드는 새 인스턴스를 반환합니다.
 */
public record TopUp(
        TopUpId id,
        WalletId walletId,
        BankAccountId bankAccountId,
        Money requestedAmount,
        Money completedAmount,
        TopUpStatus status,
        IdempotencyKey idempotencyKey,
        String externalReferenceId,
        String failureReason,
        Instant requestedAt,
        Instant completedAt) {

    public TopUp {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(walletId, "walletId must not be null");
        Objects.requireNonNull(bankAccountId, "bankAccountId must not be null");
        Objects.requireNonNull(requestedAmount, "requestedAmount must not be null");
        Objects.requireNonNull(completedAmount, "completedAmount must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");

        if (!requestedAmount.isPositive()) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "top-up amount must be positive");
        }
        requestedAmount.requireSameCurrency(completedAmount);
        if (completedAmount.isGreaterThan(requestedAmount)) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "completed amount must not exceed requested amount");
        }
    }

    public static TopUp request(
            WalletId walletId, BankAccountId bankAccountId, Money amount, IdempotencyKey idempotencyKey, Instant now) {
        return new TopUp(
                TopUpId.generate(),
                walletId,
                bankAccountId,
                amount,
                Money.zero(amount.currency()),
                TopUpStatus.REQUESTED,
                idempotencyKey,
                null,
                null,
                now,
                null);
    }

    /** 외부 출금 요청 직전 상태입니다. */
    public TopUp begin() {
        requireTransitionTo(TopUpStatus.PROCESSING);
        return new TopUp(
                id,
                walletId,
                bankAccountId,
                requestedAmount,
                completedAmount,
                TopUpStatus.PROCESSING,
                idempotencyKey,
                externalReferenceId,
                null,
                requestedAt,
                null);
    }

    /** 외부 출금이 성공했습니다. 이 전이에는 충전 분개(JE-001)가 따라옵니다. */
    public TopUp succeed(String externalReferenceId, Instant now) {
        requireTransitionTo(TopUpStatus.SUCCEEDED);
        return new TopUp(
                id,
                walletId,
                bankAccountId,
                requestedAmount,
                requestedAmount,
                TopUpStatus.SUCCEEDED,
                idempotencyKey,
                externalReferenceId,
                null,
                requestedAt,
                now);
    }

    /** 외부가 명시적으로 실패를 응답했습니다. 타임아웃은 여기로 오지 않습니다. */
    public TopUp fail(String reason, Instant now) {
        requireTransitionTo(TopUpStatus.FAILED);
        return new TopUp(
                id,
                walletId,
                bankAccountId,
                requestedAmount,
                Money.zero(requestedAmount.currency()),
                TopUpStatus.FAILED,
                idempotencyKey,
                externalReferenceId,
                reason,
                requestedAt,
                now);
    }

    /**
     * 외부 결과를 알 수 없습니다. 실패로 확정하지 않고 보존한 뒤 조회로 확정합니다.
     * 근거: ADR-007, docs/09-consistency-recovery.md §7
     */
    public TopUp markUnknown(String externalReferenceId) {
        requireTransitionTo(TopUpStatus.UNKNOWN);
        return new TopUp(
                id,
                walletId,
                bankAccountId,
                requestedAmount,
                completedAmount,
                TopUpStatus.UNKNOWN,
                idempotencyKey,
                externalReferenceId,
                null,
                requestedAt,
                null);
    }

    public boolean isSucceeded() {
        return status == TopUpStatus.SUCCEEDED;
    }

    private void requireTransitionTo(TopUpStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "top-up cannot move from " + status + " to " + next);
        }
    }
}

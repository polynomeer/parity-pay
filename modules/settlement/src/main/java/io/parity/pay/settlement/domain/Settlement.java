package io.parity.pay.settlement.domain;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 정산 헤더.
 *
 * <p>순액은 구성 항목의 합과 같아야 합니다(INV-008). 지급이 완료된 정산은 외부 지급 참조를
 * 가집니다(INV-009). 두 규칙은 DB CHECK 제약으로도 강제합니다.
 *
 * <p>근거: docs/06-domain-state-design.md §2, docs/04-payment-policy.md §8
 */
public record Settlement(
        SettlementId id,
        MerchantId merchantId,
        LocalDate periodStart,
        LocalDate periodEnd,
        Money grossAmount,
        Money cancellationAmount,
        Money feeAmount,
        long adjustmentAmount,
        Money netAmount,
        CurrencyCode currency,
        SettlementStatus status,
        String externalReferenceId,
        String holdReason,
        Instant createdAt,
        Instant updatedAt,
        Instant paidAt) {

    public Settlement {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(periodStart, "periodStart must not be null");
        Objects.requireNonNull(periodEnd, "periodEnd must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        // INV-008: 순액은 구성 항목 계산과 일치해야 합니다.
        long expected = grossAmount.amount() - cancellationAmount.amount() - feeAmount.amount()
                + adjustmentAmount;
        if (expected != netAmount.amount()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "settlement net amount " + netAmount.amount() + " does not match items " + expected);
        }
        // INV-009: 지급 완료 정산은 외부 지급 참조를 가집니다.
        if (status == SettlementStatus.PAID && externalReferenceId == null) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "paid settlement must carry an external payout reference");
        }
    }

    public Settlement beginPayout(Instant now) {
        requireTransitionTo(SettlementStatus.PAYING);
        return withStatus(SettlementStatus.PAYING, externalReferenceId, holdReason, now, paidAt);
    }

    public Settlement completePayout(String externalReferenceId, Instant now) {
        requireTransitionTo(SettlementStatus.PAID);
        if (externalReferenceId == null || externalReferenceId.isBlank()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "payout reference is required to complete a settlement");
        }
        return withStatus(SettlementStatus.PAID, externalReferenceId, null, now, now);
    }

    public Settlement failPayout(String reason, Instant now) {
        requireTransitionTo(SettlementStatus.FAILED);
        return withStatus(SettlementStatus.FAILED, externalReferenceId, reason, now, paidAt);
    }

    /** 외부 지급 결과를 모릅니다. 재지급하지 않고 조회로 확정합니다. 근거: ADR-007 */
    public Settlement markUnknown(Instant now) {
        requireTransitionTo(SettlementStatus.UNKNOWN);
        return withStatus(SettlementStatus.UNKNOWN, externalReferenceId, holdReason, now, paidAt);
    }

    public Settlement hold(String reason, Instant now) {
        requireTransitionTo(SettlementStatus.HELD);
        return withStatus(SettlementStatus.HELD, externalReferenceId, reason, now, paidAt);
    }

    public Settlement release(Instant now) {
        requireTransitionTo(SettlementStatus.CALCULATED);
        return withStatus(SettlementStatus.CALCULATED, externalReferenceId, null, now, paidAt);
    }

    public boolean isPaid() {
        return status == SettlementStatus.PAID;
    }

    private void requireTransitionTo(SettlementStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "settlement cannot move from " + status + " to " + next);
        }
    }

    private Settlement withStatus(
            SettlementStatus next,
            String externalReferenceId,
            String holdReason,
            Instant updatedAt,
            Instant paidAt) {
        return new Settlement(
                id,
                merchantId,
                periodStart,
                periodEnd,
                grossAmount,
                cancellationAmount,
                feeAmount,
                adjustmentAmount,
                netAmount,
                currency,
                next,
                externalReferenceId,
                holdReason,
                createdAt,
                updatedAt,
                paidAt);
    }
}

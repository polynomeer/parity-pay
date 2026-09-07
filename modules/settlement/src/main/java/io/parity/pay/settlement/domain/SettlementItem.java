package io.parity.pay.settlement.domain;

import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.money.CurrencyCode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 정산 항목.
 *
 * <p>금액은 부호가 있습니다. 원장이 아니므로 방향을 부호로 표현합니다(원장 항목은 INV-002에 따라
 * 항상 양수입니다). 순액은 항목 금액의 단순 합이며, 이것이 INV-008의 검증식입니다.
 *
 * <p>{@code sourceReferenceId}는 이 항목을 만들어낸 업무 사실입니다. 같은 사실이 두 번 정산에
 * 들어가지 않도록 DB 유니크 키로 강제합니다. 근거: docs/04-payment-policy.md §8
 */
public record SettlementItem(
        UUID itemId,
        SettlementId settlementId,
        MerchantId merchantId,
        PaymentId paymentId,
        SettlementItemType type,
        long amount,
        CurrencyCode currency,
        SettlementItemStatus status,
        String sourceReferenceId,
        Instant occurredAt) {

    public SettlementItem {
        Objects.requireNonNull(itemId, "itemId must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(sourceReferenceId, "sourceReferenceId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (amount == 0L) {
            throw new IllegalArgumentException("settlement item amount must not be zero");
        }
        if (type.isNegative() && amount > 0) {
            throw new IllegalArgumentException(type + " item amount must be negative: " + amount);
        }
        if (type == SettlementItemType.SALE && amount < 0) {
            throw new IllegalArgumentException("SALE item amount must be positive: " + amount);
        }
    }

    public static SettlementItem sale(
            MerchantId merchantId,
            PaymentId paymentId,
            long amount,
            CurrencyCode currency,
            Instant occurredAt) {
        return new SettlementItem(
                UUID.randomUUID(),
                null,
                merchantId,
                paymentId,
                SettlementItemType.SALE,
                amount,
                currency,
                SettlementItemStatus.ELIGIBLE,
                paymentId.toString(),
                occurredAt);
    }

    public static SettlementItem fee(
            MerchantId merchantId,
            PaymentId paymentId,
            long feeAmount,
            CurrencyCode currency,
            Instant occurredAt) {
        return new SettlementItem(
                UUID.randomUUID(),
                null,
                merchantId,
                paymentId,
                SettlementItemType.FEE,
                -Math.abs(feeAmount),
                currency,
                SettlementItemStatus.ELIGIBLE,
                paymentId.toString(),
                occurredAt);
    }

    public static SettlementItem cancellation(
            MerchantId merchantId,
            PaymentId paymentId,
            String cancellationId,
            long amount,
            CurrencyCode currency,
            Instant occurredAt) {
        return new SettlementItem(
                UUID.randomUUID(),
                null,
                merchantId,
                paymentId,
                SettlementItemType.CANCELLATION,
                -Math.abs(amount),
                currency,
                SettlementItemStatus.ELIGIBLE,
                cancellationId,
                occurredAt);
    }

    /**
     * 이미 지급된 정산에 속한 결제가 취소된 경우입니다.
     *
     * <p>지급 완료된 정산을 고치지 않고 다음 회차에 반영할 조정 항목을 만듭니다.
     * 근거: docs/04-payment-policy.md §8, ADR-009
     */
    public static SettlementItem adjustment(
            MerchantId merchantId,
            PaymentId paymentId,
            String sourceReferenceId,
            long signedAmount,
            CurrencyCode currency,
            Instant occurredAt) {
        return new SettlementItem(
                UUID.randomUUID(),
                null,
                merchantId,
                paymentId,
                SettlementItemType.ADJUSTMENT,
                signedAmount,
                currency,
                SettlementItemStatus.ELIGIBLE,
                sourceReferenceId,
                occurredAt);
    }

    public enum SettlementItemStatus {
        ELIGIBLE,
        SETTLED
    }
}

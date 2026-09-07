package io.parity.pay.wallet.domain;

import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 거래내역 한 줄.
 *
 * <p>이벤트로부터 만들어지는 프로젝션이며 진실의 원천이 아닙니다. 전부 지우고 이벤트를 재생해 다시
 * 만들 수 있어야 합니다. 근거: FR-008, ADR-006
 */
public record WalletTransactionEntry(
        UUID transactionId,
        WalletId walletId,
        WalletTransactionType type,
        TransactionDirection direction,
        Money amount,
        String referenceType,
        String referenceId,
        LedgerTransactionId ledgerTransactionId,
        Instant occurredAt) {

    public WalletTransactionEntry {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(walletId, "walletId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(referenceType, "referenceType must not be null");
        Objects.requireNonNull(referenceId, "referenceId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("transaction amount must be positive: " + amount);
        }
    }

    /** 지갑 잔액이 늘어난 거래인지 여부입니다. */
    public enum TransactionDirection {
        CREDIT,
        DEBIT
    }

    public enum WalletTransactionType {
        TOP_UP,
        PAYMENT,
        PAYMENT_CANCELLATION
    }
}

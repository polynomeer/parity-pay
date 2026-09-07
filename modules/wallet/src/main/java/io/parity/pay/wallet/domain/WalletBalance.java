package io.parity.pay.wallet.domain;

import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * 잔액 스냅샷.
 *
 * <p>진실의 원천은 원장이며 이 값은 조회용 파생 데이터입니다. 원장 전기와 같은 트랜잭션에서만
 * 갱신합니다. 근거: ADR-008, INV-010
 */
public record WalletBalance(WalletId walletId, Money available, Money pending, long version, Instant updatedAt) {

    public WalletBalance {
        Objects.requireNonNull(walletId, "walletId must not be null");
        Objects.requireNonNull(available, "available must not be null");
        Objects.requireNonNull(pending, "pending must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        available.requireSameCurrency(pending);
    }

    /** 원장 잔액과 비교할 값입니다. 근거: docs/07-ledger-journal-catalog.md §6 */
    public Money ledgerEquivalent() {
        return available.plus(pending);
    }
}

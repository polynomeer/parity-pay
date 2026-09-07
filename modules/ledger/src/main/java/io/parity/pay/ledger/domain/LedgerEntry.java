package io.parity.pay.ledger.domain;

import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.id.LedgerEntryId;
import io.parity.pay.shared.money.Money;
import java.util.Objects;

/**
 * 전기된 원장 항목. POSTED 이후에는 수정·삭제하지 않습니다.
 *
 * <p>근거: INV-006, ADR-009
 */
public record LedgerEntry(
        LedgerEntryId id, LedgerAccountId accountId, Direction direction, Money money) {

    public LedgerEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(money, "money must not be null");
        if (!money.isPositive()) {
            throw new IllegalArgumentException("entry amount must be positive: " + money);
        }
    }

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }

    /** 계정의 정상 잔액 방향을 기준으로 부호가 있는 금액을 계산합니다. */
    public long signedAmountFor(Direction normalBalance) {
        return direction == normalBalance ? money().amount() : -money().amount();
    }
}

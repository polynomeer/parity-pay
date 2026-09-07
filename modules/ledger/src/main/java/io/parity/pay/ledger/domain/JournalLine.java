package io.parity.pay.ledger.domain;

import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.money.Money;
import java.util.Objects;

/**
 * 분개 한 줄. 금액은 항상 양수이며 증감은 {@link Direction}으로 표현합니다.
 *
 * <p>근거: INV-002, docs/06-domain-state-design.md §3
 */
public record JournalLine(LedgerAccountId accountId, Direction direction, Money money) {

    public JournalLine {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(money, "money must not be null");
        if (!money.isPositive()) {
            throw new IllegalArgumentException("journal line amount must be positive: " + money);
        }
    }

    public static JournalLine debit(LedgerAccountId accountId, Money money) {
        return new JournalLine(accountId, Direction.DEBIT, money);
    }

    public static JournalLine credit(LedgerAccountId accountId, Money money) {
        return new JournalLine(accountId, Direction.CREDIT, money);
    }

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }
}

package io.parity.pay.ledger.domain;

import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.money.CurrencyCode;
import java.util.Objects;
import java.util.UUID;

/**
 * 원장 계정. 소유자 차원이 있는 계정은 소유자마다 하나씩 존재합니다.
 *
 * <p>근거: docs/07-ledger-journal-catalog.md §3, §4
 */
public record LedgerAccount(LedgerAccountId id, AccountCode code, UUID ownerId, CurrencyCode currency, boolean active) {

    public LedgerAccount {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (code.ownerType().ownerIdRequired() && ownerId == null) {
            throw new IllegalArgumentException("ownerId is required for account " + code);
        }
        if (!code.ownerType().ownerIdRequired() && ownerId != null) {
            throw new IllegalArgumentException("ownerId must be null for account " + code);
        }
    }

    public OwnerType ownerType() {
        return code.ownerType();
    }

    public AccountClass accountClass() {
        return code.accountClass();
    }

    public Direction normalBalance() {
        return code.normalBalance();
    }
}

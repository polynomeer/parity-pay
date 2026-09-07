package io.parity.pay.ledger.domain;

/**
 * 계정 소유자 차원.
 *
 * <p>{@code CORPORATE}처럼 소유자가 하나뿐인 계정은 ownerId가 없습니다.
 * 근거: docs/07-ledger-journal-catalog.md §3
 */
public enum OwnerType {
    CORPORATE(false),
    WALLET(true),
    MERCHANT(true),
    PROVIDER(true),
    EXTERNAL(true),
    ADJUSTMENT(true);

    private final boolean ownerIdRequired;

    OwnerType(boolean ownerIdRequired) {
        this.ownerIdRequired = ownerIdRequired;
    }

    public boolean ownerIdRequired() {
        return ownerIdRequired;
    }
}

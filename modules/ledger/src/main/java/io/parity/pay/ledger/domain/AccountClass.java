package io.parity.pay.ledger.domain;

/** 계정 분류와 정상 잔액 방향. 근거: docs/07-ledger-journal-catalog.md §3 */
public enum AccountClass {
    ASSET(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT),
    EXPENSE(Direction.DEBIT),
    /** 정산 조정 계정. 정상 잔액 방향이 상황에 따라 달라집니다. */
    CLEARING(Direction.DEBIT);

    private final Direction normalBalance;

    AccountClass(Direction normalBalance) {
        this.normalBalance = normalBalance;
    }

    public Direction normalBalance() {
        return normalBalance;
    }
}

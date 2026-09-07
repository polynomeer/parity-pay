package io.parity.pay.ledger.domain;

/**
 * 분개 방향. 금액에 음수를 쓰지 않고 방향으로 증감을 표현합니다.
 *
 * <p>근거: docs/07-ledger-journal-catalog.md §2, INV-002
 */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}

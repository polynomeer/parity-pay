package io.parity.pay.reconciliation.domain;

/**
 * 불일치 분류. 근거: docs/09-consistency-recovery.md §10
 *
 * <p>{@code autoResolvable}은 자동 보정을 허용하는지 여부입니다. 금액 불일치와 외부에만 존재하는
 * 기록은 원인을 모른 채 숫자를 맞추면 진짜 문제를 덮게 되므로 자동 보정하지 않습니다.
 * 근거: docs/04-payment-policy.md §9
 */
public enum MismatchType {
    /** 내부에만 있습니다. 우리는 성공으로 알고 있는데 외부에 기록이 없습니다. */
    INTERNAL_ONLY(false),
    /** 외부에만 있습니다. 외부는 처리했는데 우리에게 기록이 없습니다. */
    EXTERNAL_ONLY(false),
    /** 양쪽에 있지만 상태가 다릅니다. */
    STATUS_MISMATCH(false),
    /** 양쪽에 있지만 금액이 다릅니다. 절대 자동으로 맞추지 않습니다. */
    AMOUNT_MISMATCH(false),
    /** 같은 외부 참조를 가리키는 내부 기록이 둘 이상입니다. */
    DUPLICATE(false),
    /** 업무는 성공했는데 원장 거래가 없습니다. 원장이 진실이므로 즉시 조사 대상입니다. */
    LEDGER_MISSING(false);

    private final boolean autoResolvable;

    MismatchType(boolean autoResolvable) {
        this.autoResolvable = autoResolvable;
    }

    public boolean autoResolvable() {
        return autoResolvable;
    }
}

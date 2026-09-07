package io.parity.pay.settlement.domain;

/** 정산 항목 유형. 근거: docs/04-payment-policy.md §8 */
public enum SettlementItemType {
    /** 판매 대금. 양수입니다. */
    SALE(false),
    /** 취소로 회수할 금액. 음수입니다. */
    CANCELLATION(true),
    /** 플랫폼 수수료. 음수입니다. */
    FEE(true),
    /** 이미 지급된 회차에 대한 보정. 부호가 상황에 따라 다릅니다. */
    ADJUSTMENT(false);

    private final boolean negative;

    SettlementItemType(boolean negative) {
        this.negative = negative;
    }

    boolean isNegative() {
        return negative;
    }
}

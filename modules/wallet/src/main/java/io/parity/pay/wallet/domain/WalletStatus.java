package io.parity.pay.wallet.domain;

/** 지갑 상태. 근거: docs/04-payment-policy.md §2, docs/06-domain-state-design.md §4 */
public enum WalletStatus {
    /** 모든 거래를 허용합니다. */
    ACTIVE,
    /** 조회와 환불 수령은 허용하지만 신규 충전·결제·송금은 금지합니다. */
    SUSPENDED,
    /** 최종 상태입니다. 신규 거래를 허용하지 않습니다. */
    CLOSED;

    /** 신규 지출(결제·송금·출금) 가능 여부입니다. */
    public boolean canSpend() {
        return this == ACTIVE;
    }

    /** 신규 충전 가능 여부입니다. */
    public boolean canTopUp() {
        return this == ACTIVE;
    }
}

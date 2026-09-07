package io.parity.pay.payment.domain;

/** 결제 수단. MVP는 페이머니 하나입니다. 근거: docs/03-mvp-scope.md §2 */
public enum PaymentMethod {
    /** 내부 원장 기반 페이머니 결제. 외부기관 호출이 없으므로 단일 로컬 트랜잭션으로 처리합니다. */
    PAY_MONEY
}

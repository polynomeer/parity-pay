package io.parity.pay.payment.domain;

/** 결제 수단. 근거: docs/03-mvp-scope.md §2, docs/04-payment-policy.md §4 */
public enum PaymentMethod {
    /** 내부 원장 기반 페이머니 결제. 외부기관 호출이 없으므로 단일 로컬 트랜잭션으로 처리합니다. */
    PAY_MONEY,

    /**
     * 외부 PG 결제(카드 등).
     *
     * <p>돈이 지갑에서 나가지 않고 외부기관을 거칩니다. 로컬 DB와 외부기관을 하나의 원자적
     * 트랜잭션으로 볼 수 없으므로, 요청을 먼저 커밋하고 외부를 호출한 뒤 결과를 별도 트랜잭션에
     * 확정합니다. 결과를 모르면 실패가 아니라 {@code UNKNOWN}입니다.
     *
     * <p>근거: docs/04-payment-policy.md §4, ADR-007
     */
    EXTERNAL_PG;

    /** 외부기관 호출이 필요한 수단인지. 호출이 있으면 결과가 불명확할 수 있습니다. */
    public boolean callsExternalProvider() {
        return this == EXTERNAL_PG;
    }
}

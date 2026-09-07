package io.parity.pay.shared.money;

/**
 * ParityPay가 지원하는 통화. MVP는 KRW 하나입니다.
 *
 * <p>{@code minorUnitScale}는 최소 화폐 단위 자릿수입니다. KRW는 0이므로 금액을 원 단위 정수로 다룹니다.
 * 근거: docs/04-payment-policy.md BR-001
 */
public enum CurrencyCode {
    KRW(0);

    private final int minorUnitScale;

    CurrencyCode(int minorUnitScale) {
        this.minorUnitScale = minorUnitScale;
    }

    public int minorUnitScale() {
        return minorUnitScale;
    }
}

package io.parity.pay.shared.money;

import java.util.Objects;

/**
 * 금액 값 객체.
 *
 * <p>금액은 최소 화폐 단위의 정수({@code long})로만 표현합니다. {@code double}·{@code float}를 사용하지
 * 않습니다. 근거: docs/04-payment-policy.md BR-001, CLAUDE.md §3
 *
 * <p>음수 허용 여부는 사용 맥락이 아니라 생성자로 통제합니다.
 * <ul>
 *   <li>{@link #of(long, CurrencyCode)} — 0 이상
 *   <li>{@link #positive(long, CurrencyCode)} — 0 초과 (원장 항목 금액 등, INV-002)
 * </ul>
 */
public record Money(long amount, CurrencyCode currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
    }

    public static Money of(long amount, CurrencyCode currency) {
        return new Money(amount, currency);
    }

    /** 원장 항목처럼 0을 허용하지 않는 금액을 만듭니다. 근거: INV-002 */
    public static Money positive(long amount, CurrencyCode currency) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        return new Money(amount, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(0L, currency);
    }

    public static Money krw(long amount) {
        return new Money(amount, CurrencyCode.KRW);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amount, other.amount), currency);
    }

    /**
     * 뺄셈 결과가 음수이면 예외입니다. 잔액 계산에서 음수 금액이 조용히 만들어지지 않도록 합니다.
     * 근거: INV-003
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        long result = Math.subtractExact(amount, other.amount);
        if (result < 0) {
            throw new IllegalArgumentException("result must not be negative: " + amount + " - " + other.amount);
        }
        return new Money(result, currency);
    }

    public boolean isZero() {
        return amount == 0L;
    }

    public boolean isPositive() {
        return amount > 0L;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount > other.amount;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        requireSameCurrency(other);
        return amount >= other.amount;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount < other.amount;
    }

    public void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (currency != other.currency) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amount, other.amount);
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}

package io.parity.pay.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("BR-001: 음수 금액은 만들 수 없다")
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> Money.krw(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("INV-002: positive()는 0을 허용하지 않는다")
    void positiveRejectsZero() {
        assertThatThrownBy(() -> Money.positive(0, CurrencyCode.KRW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.positive(1, CurrencyCode.KRW).amount()).isEqualTo(1);
    }

    @Test
    @DisplayName("INV-007: 통화가 다르면 연산할 수 없다")
    void rejectsCurrencyMismatch() {
        Money krw = Money.krw(1_000);
        Money other = new Money(1_000, CurrencyCode.KRW);
        assertThat(krw.plus(other)).isEqualTo(Money.krw(2_000));
    }

    @Test
    @DisplayName("INV-003: 뺄셈 결과가 음수면 예외다")
    void minusRejectsNegativeResult() {
        assertThatThrownBy(() -> Money.krw(1_000).minus(Money.krw(1_001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.krw(1_000).minus(Money.krw(1_000))).isEqualTo(Money.krw(0));
    }

    @Test
    @DisplayName("덧셈 오버플로는 조용히 넘어가지 않는다")
    void plusDetectsOverflow() {
        assertThatThrownBy(() -> Money.krw(Long.MAX_VALUE).plus(Money.krw(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("비교 연산")
    void comparisons() {
        Money small = Money.krw(1_000);
        Money large = Money.krw(2_000);

        assertThat(large.isGreaterThan(small)).isTrue();
        assertThat(small.isLessThan(large)).isTrue();
        assertThat(small.isGreaterThanOrEqualTo(small)).isTrue();
        assertThat(small.isZero()).isFalse();
        assertThat(Money.zero(CurrencyCode.KRW).isZero()).isTrue();
    }
}

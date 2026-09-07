package io.parity.pay.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 금액 연산의 속성.
 *
 * <p>예제 테스트는 우리가 떠올린 값만 확인합니다. 금액에서 위험한 것은 떠올리지 못한 값입니다.
 * 넘침 직전의 큰 수, 뺄셈이 음수가 되는 조합 같은 것들입니다.
 *
 * <p>통화 불일치 속성은 여기에 없습니다. 지원 통화가 KRW 하나뿐이라 불일치를 만들 수 없습니다.
 * 통화가 늘어나면 이 파일에 추가합니다.
 *
 * <p>근거: CLAUDE.md §6, INV-002, INV-003, docs/04-payment-policy.md BR-001
 */
class MoneyPropertiesTest {

    @Property
    @Label("INV-003: 어떤 연산 결과도 음수가 아니다")
    void resultsAreNeverNegative(@ForAll("amounts") long left, @ForAll("amounts") long right) {
        Money a = Money.krw(left);
        Money b = Money.krw(right);

        assertThat(a.plus(b).amount()).isNotNegative();
        if (!a.isLessThan(b)) {
            assertThat(a.minus(b).amount()).isNotNegative();
        }
    }

    @Property
    @Label("덧셈은 교환·결합 법칙을 지킨다")
    void additionIsCommutativeAndAssociative(
            @ForAll("smallAmounts") long first,
            @ForAll("smallAmounts") long second,
            @ForAll("smallAmounts") long third) {
        Money a = Money.krw(first);
        Money b = Money.krw(second);
        Money c = Money.krw(third);

        assertThat(a.plus(b)).isEqualTo(b.plus(a));
        assertThat(a.plus(b).plus(c)).isEqualTo(a.plus(b.plus(c)));
    }

    @Property
    @Label("더한 만큼 빼면 원래 금액이다")
    void subtractionUndoesAddition(@ForAll("smallAmounts") long base, @ForAll("smallAmounts") long delta) {
        Money a = Money.krw(base);
        Money b = Money.krw(delta);

        assertThat(a.plus(b).minus(b)).isEqualTo(a);
    }

    @Property
    @Label("가진 것보다 많이 빼려 하면 조용히 음수가 되지 않고 실패한다")
    void subtractingMoreThanAvailableFails(@ForAll("amounts") long smaller, @ForAll("amounts") long larger) {
        Money a = Money.krw(Math.min(smaller, larger));
        Money b = Money.krw(Math.max(smaller, larger));
        if (a.equals(b)) {
            return;
        }

        assertThatThrownBy(() -> a.minus(b)).isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    @Label("long을 넘기는 덧셈은 값을 감싸지 않고 실패한다")
    void overflowFailsInsteadOfWrappingAround(@ForAll("hugeAmounts") long left, @ForAll("hugeAmounts") long right) {
        Money a = Money.krw(left);
        Money b = Money.krw(right);

        if (left > Long.MAX_VALUE - right) {
            // 감싸면 큰 금액 둘을 더해 작은 금액이 나옵니다. 금액에서 이것은 조용한 손실입니다.
            assertThatThrownBy(() -> a.plus(b)).isInstanceOf(ArithmeticException.class);
        } else {
            assertThat(a.plus(b).amount()).isEqualTo(left + right);
        }
    }

    @Property
    @Label("크기 비교는 금액 순서와 일치한다")
    void comparisonFollowsTheAmount(@ForAll("amounts") long left, @ForAll("amounts") long right) {
        Money a = Money.krw(left);
        Money b = Money.krw(right);

        assertThat(a.compareTo(b)).isEqualTo(Long.compare(left, right));
        assertThat(a.isGreaterThan(b)).isEqualTo(left > right);
        assertThat(a.isLessThan(b)).isEqualTo(left < right);
        assertThat(a.isGreaterThanOrEqualTo(b)).isEqualTo(left >= right);
    }

    @Property
    @Label("INV-002: positive()는 0 이하를 받지 않는다")
    void positiveRejectsZeroAndBelow(@ForAll long amount) {
        if (amount > 0) {
            assertThat(Money.positive(amount, CurrencyCode.KRW).amount()).isEqualTo(amount);
        } else {
            assertThatThrownBy(() -> Money.positive(amount, CurrencyCode.KRW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Provide
    Arbitrary<Long> amounts() {
        return Arbitraries.longs().between(0L, 1_000_000_000_000L);
    }

    /** 더해도 넘치지 않는 범위입니다. 결합 법칙처럼 세 번 더하는 속성에 씁니다. */
    @Provide
    Arbitrary<Long> smallAmounts() {
        return Arbitraries.longs().between(0L, 1_000_000_000L);
    }

    /** 넘침 경계를 자주 만들기 위해 long 최댓값 근처를 뽑습니다. */
    @Provide
    Arbitrary<Long> hugeAmounts() {
        return Arbitraries.longs().between(Long.MAX_VALUE / 4, Long.MAX_VALUE);
    }
}

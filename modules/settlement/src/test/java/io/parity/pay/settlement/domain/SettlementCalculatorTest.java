package io.parity.pay.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.money.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);
    private static final MerchantId MERCHANT = MerchantId.generate();

    @Test
    @DisplayName("JE-007: 수수료는 만분율 정수로 계산하고 원 단위 미만은 버린다")
    void feeUsesBasisPoints() {
        assertThat(SettlementCalculator.feeFor(30_000, 1_000)).isEqualTo(3_000);
        assertThat(SettlementCalculator.feeFor(33_333, 1_000)).isEqualTo(3_333);
        assertThat(SettlementCalculator.feeFor(9, 1_000)).isZero();
        assertThat(SettlementCalculator.feeFor(30_000, 0)).isZero();
    }

    @Test
    @DisplayName("INV-008: 순액은 항목 금액의 합과 같다")
    void netEqualsSumOfItems() {
        PaymentId payment = PaymentId.generate();
        List<SettlementItem> items = List.of(
                SettlementItem.sale(MERCHANT, payment, 30_000, CurrencyCode.KRW, NOW),
                SettlementItem.fee(MERCHANT, payment, 3_000, CurrencyCode.KRW, NOW),
                SettlementItem.cancellation(MERCHANT, payment, "c-1", 10_000, CurrencyCode.KRW, NOW),
                SettlementItem.adjustment(MERCHANT, payment, "c-1:FEE", 1_000, CurrencyCode.KRW, NOW));

        Settlement settlement = SettlementCalculator.calculate(MERCHANT, START, END, CurrencyCode.KRW, items, NOW);

        long sumOfItems = items.stream().mapToLong(SettlementItem::amount).sum();
        assertThat(settlement.netAmount().amount()).isEqualTo(sumOfItems).isEqualTo(18_000);
        assertThat(settlement.grossAmount().amount()).isEqualTo(30_000);
        assertThat(settlement.feeAmount().amount()).isEqualTo(3_000);
        assertThat(settlement.cancellationAmount().amount()).isEqualTo(10_000);
        assertThat(settlement.adjustmentAmount()).isEqualTo(1_000);
        assertThat(settlement.status()).isEqualTo(SettlementStatus.CALCULATED);
    }

    @Test
    @DisplayName("순액이 음수면 회차를 만들지 않는다")
    void negativeNetIsRejected() {
        PaymentId payment = PaymentId.generate();
        List<SettlementItem> items = List.of(
                SettlementItem.sale(MERCHANT, payment, 10_000, CurrencyCode.KRW, NOW),
                SettlementItem.cancellation(MERCHANT, payment, "c-2", 30_000, CurrencyCode.KRW, NOW));

        assertThatThrownBy(() -> SettlementCalculator.calculate(MERCHANT, START, END, CurrencyCode.KRW, items, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("항목이 없으면 정산할 것이 없다")
    void emptyItemsAreRejected() {
        assertThatThrownBy(() -> SettlementCalculator.calculate(MERCHANT, START, END, CurrencyCode.KRW, List.of(), NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("INV-009: 외부 지급 참조 없이 PAID 상태를 만들 수 없다")
    void paidRequiresPayoutReference() {
        Settlement calculated = SettlementCalculator.calculate(
                MERCHANT,
                START,
                END,
                CurrencyCode.KRW,
                List.of(SettlementItem.sale(MERCHANT, PaymentId.generate(), 10_000, CurrencyCode.KRW, NOW)),
                NOW);

        assertThatThrownBy(() -> calculated.beginPayout(NOW).completePayout(null, NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("payout reference");
    }

    @Test
    @DisplayName("지급 완료된 정산은 다시 지급할 수 없다")
    void paidSettlementIsFinal() {
        Settlement paid = SettlementCalculator.calculate(
                        MERCHANT,
                        START,
                        END,
                        CurrencyCode.KRW,
                        List.of(SettlementItem.sale(MERCHANT, PaymentId.generate(), 10_000, CurrencyCode.KRW, NOW)),
                        NOW)
                .beginPayout(NOW)
                .completePayout("payout-1", NOW);

        assertThat(paid.isPaid()).isTrue();
        assertThatThrownBy(() -> paid.beginPayout(NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("실패한 지급은 다시 시도할 수 있다")
    void failedPayoutCanBeRetried() {
        Settlement failed = SettlementCalculator.calculate(
                        MERCHANT,
                        START,
                        END,
                        CurrencyCode.KRW,
                        List.of(SettlementItem.sale(MERCHANT, PaymentId.generate(), 10_000, CurrencyCode.KRW, NOW)),
                        NOW)
                .beginPayout(NOW)
                .failPayout("declined", NOW);

        assertThat(failed.beginPayout(NOW).status()).isEqualTo(SettlementStatus.PAYING);
    }
}

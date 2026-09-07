package io.parity.pay.settlement.domain;

import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 정산 계산 도메인 서비스.
 *
 * <p>I/O를 수행하지 않습니다. 항목 목록을 받아 헤더를 만듭니다. 근거: docs/06-domain-state-design.md §8
 *
 * <p>수수료는 basis point(만분율) 정수로 계산합니다. 비율 계산에 부동소수점을 쓰지 않기 위해서이며,
 * 원 단위 미만은 버립니다(플랫폼이 아니라 판매자에게 유리한 방향). 근거: BR-001
 */
public final class SettlementCalculator {

    private SettlementCalculator() {}

    /** 승인액에 대한 플랫폼 수수료를 계산합니다. */
    public static long feeFor(long grossAmount, int feeBasisPoints) {
        if (feeBasisPoints < 0 || feeBasisPoints > 10_000) {
            throw new IllegalArgumentException("fee basis points must be between 0 and 10000");
        }
        return Math.floorDiv(grossAmount * feeBasisPoints, 10_000L);
    }

    /**
     * 항목을 모아 정산 헤더를 만듭니다.
     *
     * <p>순액이 음수이면 헤더를 만들지 않습니다. 이번 회차에 회수할 금액이 지급할 금액보다 크다는
     * 뜻이고, 이는 다음 회차 이월 또는 별도 회수 절차의 대상입니다.
     * 근거: docs/04-payment-policy.md §8, docs/07-ledger-journal-catalog.md JE-009
     */
    public static Settlement calculate(
            MerchantId merchantId,
            LocalDate periodStart,
            LocalDate periodEnd,
            CurrencyCode currency,
            List<SettlementItem> items,
            Instant now) {
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "settlement requires at least one eligible item");
        }

        long gross = 0L;
        long cancellation = 0L;
        long fee = 0L;
        long adjustment = 0L;

        for (SettlementItem item : items) {
            if (item.currency() != currency) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "settlement items must share a single currency");
            }
            switch (item.type()) {
                case SALE -> gross += item.amount();
                case CANCELLATION -> cancellation += Math.abs(item.amount());
                case FEE -> fee += Math.abs(item.amount());
                case ADJUSTMENT -> adjustment += item.amount();
            }
        }

        long net = gross - cancellation - fee + adjustment;
        if (net < 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "settlement net amount is negative (" + net + "); carry over to the next period");
        }

        return new Settlement(
                SettlementId.generate(),
                merchantId,
                periodStart,
                periodEnd,
                Money.of(gross, currency),
                Money.of(cancellation, currency),
                Money.of(fee, currency),
                adjustment,
                Money.of(net, currency),
                currency,
                SettlementStatus.CALCULATED,
                null,
                null,
                now,
                now,
                null);
    }
}

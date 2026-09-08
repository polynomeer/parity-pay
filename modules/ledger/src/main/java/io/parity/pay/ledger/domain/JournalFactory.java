package io.parity.pay.ledger.domain;

import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 업무 사건을 분개로 변환하는 도메인 서비스.
 *
 * <p>I/O를 수행하지 않습니다. 계정 ID는 애플리케이션 서비스가 미리 조회해 전달합니다.
 * 근거: docs/06-domain-state-design.md §8
 *
 * <p>여기 없는 분개를 코드 곳곳에서 즉석으로 만들지 않습니다. 새 분개는
 * docs/07-ledger-journal-catalog.md에 JE 번호로 먼저 정의합니다.
 */
public final class JournalFactory {

    private JournalFactory() {}

    /**
     * JE-001 페이머니 충전.
     *
     * <p>법인 은행 자산이 증가하고 사용자에게 지급할 페이머니 의무가 증가합니다.
     */
    public static Journal topUpCompleted(
            TopUpId topUpId,
            LedgerAccountId bankDepositAccountId,
            LedgerAccountId userPayMoneyAccountId,
            Money amount,
            Instant effectiveAt) {
        return new Journal(
                ReferenceType.TOP_UP,
                topUpId.value(),
                TransactionType.TOP_UP_COMPLETED,
                amount.currency(),
                effectiveAt,
                List.of(
                        JournalLine.debit(bankDepositAccountId, amount),
                        JournalLine.credit(userPayMoneyAccountId, amount)));
    }

    /**
     * JE-003 페이머니 결제 승인.
     *
     * <p>사용자에 대한 지급 의무가 판매자에 대한 지급 의무로 이전됩니다. 시스템 전체의 부채 총액은
     * 변하지 않습니다.
     */
    public static Journal paymentApproved(
            PaymentId paymentId,
            LedgerAccountId userPayMoneyAccountId,
            LedgerAccountId merchantPayableAccountId,
            Money amount,
            Instant effectiveAt) {
        return new Journal(
                ReferenceType.PAYMENT,
                paymentId.value(),
                TransactionType.PAYMENT_APPROVED,
                amount.currency(),
                effectiveAt,
                List.of(
                        JournalLine.debit(userPayMoneyAccountId, amount),
                        JournalLine.credit(merchantPayableAccountId, amount)));
    }

    /**
     * JE-013 외부 PG 결제 승인.
     *
     * <p>페이머니 결제(JE-003)와 다릅니다. 돈이 지갑에서 나가지 않으므로 사용자 부채는 그대로이고,
     * PG에게 받을 돈(자산)이 늘면서 판매자에게 줄 의무(부채)가 생깁니다.
     *
     * <p>근거: docs/07-ledger-journal-catalog.md JE-013
     */
    public static Journal pgPaymentApproved(
            PaymentId paymentId,
            LedgerAccountId pgReceivableAccountId,
            LedgerAccountId merchantPayableAccountId,
            Money amount,
            Instant effectiveAt) {
        return new Journal(
                ReferenceType.PAYMENT,
                paymentId.value(),
                TransactionType.PAYMENT_APPROVED,
                amount.currency(),
                effectiveAt,
                List.of(
                        JournalLine.debit(pgReceivableAccountId, amount),
                        JournalLine.credit(merchantPayableAccountId, amount)));
    }

    /**
     * JE-004 결제 취소 + JE-009 정산 후 환불.
     *
     * <p>취소 시점에 판매자 지급예정금이 남아 있으면 거기에서 차감합니다. 이미 정산이 지급되어
     * 남은 금액이 부족하면 부족분만큼 판매자 미수금으로 기록합니다. 판매자에게 이미 나간 돈을
     * 되돌려받아야 하는 채권이기 때문입니다.
     *
     * <p>계정 정상 잔액 방향을 어기는 음수 잔액을 만들지 않기 위한 처리이기도 합니다.
     * 근거: docs/07-ledger-journal-catalog.md JE-004·JE-009
     */
    public static Journal paymentCanceled(
            CancellationId cancellationId,
            LedgerAccountId merchantPayableAccountId,
            LedgerAccountId merchantReceivableAccountId,
            LedgerAccountId userPayMoneyAccountId,
            Money fromPayable,
            Money fromReceivable,
            Instant effectiveAt) {
        Money total = fromPayable.plus(fromReceivable);
        List<JournalLine> lines = new ArrayList<>();
        if (fromPayable.isPositive()) {
            lines.add(JournalLine.debit(merchantPayableAccountId, fromPayable));
        }
        if (fromReceivable.isPositive()) {
            lines.add(JournalLine.debit(merchantReceivableAccountId, fromReceivable));
        }
        lines.add(JournalLine.credit(userPayMoneyAccountId, total));

        return new Journal(
                ReferenceType.PAYMENT_CANCELLATION,
                cancellationId.value(),
                TransactionType.PAYMENT_CANCELED,
                total.currency(),
                effectiveAt,
                lines);
    }

    /**
     * JE-014 외부 PG 결제 환불.
     *
     * <p>환불은 결제한 곳으로 돌아갑니다. 카드로 받았으면 카드로 돌려주므로 사용자 페이머니는
     * 늘지 않고, PG에게 받을 돈이 줄어듭니다.
     *
     * <p>차변 쪽은 JE-004와 같습니다. 판매자 지급예정금이 남아 있으면 거기서 빼고, 이미 정산이
     * 나갔으면 부족분이 판매자 미수금이 됩니다.
     *
     * <p>근거: docs/07-ledger-journal-catalog.md JE-014
     */
    public static Journal pgPaymentRefunded(
            CancellationId cancellationId,
            LedgerAccountId merchantPayableAccountId,
            LedgerAccountId merchantReceivableAccountId,
            LedgerAccountId pgReceivableAccountId,
            Money fromPayable,
            Money fromReceivable,
            Instant effectiveAt) {
        Money total = fromPayable.plus(fromReceivable);
        List<JournalLine> lines = new ArrayList<>();
        if (fromPayable.isPositive()) {
            lines.add(JournalLine.debit(merchantPayableAccountId, fromPayable));
        }
        if (fromReceivable.isPositive()) {
            lines.add(JournalLine.debit(merchantReceivableAccountId, fromReceivable));
        }
        lines.add(JournalLine.credit(pgReceivableAccountId, total));

        return new Journal(
                ReferenceType.PAYMENT_CANCELLATION,
                cancellationId.value(),
                TransactionType.PAYMENT_CANCELED,
                total.currency(),
                effectiveAt,
                lines);
    }

    /**
     * JE-007 플랫폼 수수료 인식.
     *
     * <p>판매자에게 줄 의무 일부가 우리 수익으로 바뀝니다.
     */
    public static Journal feeRecognized(
            SettlementId settlementId,
            LedgerAccountId merchantPayableAccountId,
            LedgerAccountId platformFeeRevenueAccountId,
            Money feeAmount,
            Instant effectiveAt) {
        return new Journal(
                ReferenceType.SETTLEMENT,
                settlementId.value(),
                TransactionType.FEE_RECOGNIZED,
                feeAmount.currency(),
                effectiveAt,
                List.of(
                        JournalLine.debit(merchantPayableAccountId, feeAmount),
                        JournalLine.credit(platformFeeRevenueAccountId, feeAmount)));
    }

    /**
     * JE-008 판매자 정산 지급.
     *
     * <p>판매자에 대한 지급 의무가 사라지고 법인 은행 자산이 줄어듭니다.
     */
    public static Journal settlementPaid(
            SettlementId settlementId,
            LedgerAccountId merchantPayableAccountId,
            LedgerAccountId bankDepositAccountId,
            Money netAmount,
            Instant effectiveAt) {
        return new Journal(
                ReferenceType.SETTLEMENT,
                settlementId.value(),
                TransactionType.SETTLEMENT_PAID,
                netAmount.currency(),
                effectiveAt,
                List.of(
                        JournalLine.debit(merchantPayableAccountId, netAmount),
                        JournalLine.credit(bankDepositAccountId, netAmount)));
    }

    /**
     * JE-012 운영 보정.
     *
     * <p>고정된 분개가 아니라 근거에 따라 계정이 달라집니다. 그래서 계정과 금액을 호출자가
     * 지정하되, 참조는 반드시 보정 건을 가리키게 해서 어떤 근거로 만들어졌는지 추적할 수 있게
     * 합니다. 보정은 기존 원장을 고치는 것이 아니라 새 분개입니다.
     *
     * <p>근거: docs/07-ledger-journal-catalog.md JE-012, ADR-009
     */
    public static Journal operationalAdjustment(
            UUID adjustmentId,
            LedgerAccountId debitAccountId,
            LedgerAccountId creditAccountId,
            Money amount,
            Instant effectiveAt) {
        if (debitAccountId.equals(creditAccountId)) {
            throw new UnbalancedJournalException("adjustment must move value between two different accounts");
        }
        return new Journal(
                ReferenceType.ADJUSTMENT,
                adjustmentId,
                TransactionType.OPERATIONAL_ADJUSTMENT,
                amount.currency(),
                effectiveAt,
                List.of(JournalLine.debit(debitAccountId, amount), JournalLine.credit(creditAccountId, amount)));
    }

    /**
     * JE-002 형태의 역분개.
     *
     * <p>원거래를 수정하지 않고 방향만 뒤집은 새 분개를 만듭니다. 근거: ADR-009
     */
    public static Journal reversalOf(
            LedgerTransaction original,
            ReferenceType referenceType,
            UUID referenceId,
            TransactionType transactionType,
            Instant effectiveAt) {
        List<JournalLine> reversedLines = original.entries().stream()
                .map(entry ->
                        new JournalLine(entry.accountId(), entry.direction().opposite(), entry.money()))
                .toList();
        return new Journal(
                referenceType, referenceId, transactionType, original.currency(), effectiveAt, reversedLines);
    }
}

package io.parity.pay.ledger.domain;

import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 아직 전기되지 않은 분개.
 *
 * <p>생성 시점에 원장 불변조건을 검증하므로, 균형이 맞지 않는 Journal 객체는 존재할 수 없습니다.
 *
 * <ul>
 *   <li>INV-001 차변 합계 = 대변 합계
 *   <li>INV-002 모든 항목 금액 &gt; 0 ({@link JournalLine}에서 검증)
 *   <li>INV-007 하나의 원장 거래는 하나의 통화만 사용
 * </ul>
 *
 * <p>근거: docs/07-ledger-journal-catalog.md §2, §7
 */
public record Journal(
        ReferenceType referenceType,
        UUID referenceId,
        TransactionType transactionType,
        CurrencyCode currency,
        Instant effectiveAt,
        List<JournalLine> lines) {

    public Journal {
        Objects.requireNonNull(referenceType, "referenceType must not be null");
        Objects.requireNonNull(referenceId, "referenceId must not be null");
        Objects.requireNonNull(transactionType, "transactionType must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
        Objects.requireNonNull(lines, "lines must not be null");

        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new UnbalancedJournalException("journal must have at least two lines");
        }

        long debitTotal = 0L;
        long creditTotal = 0L;
        for (JournalLine line : lines) {
            if (line.money().currency() != currency) {
                // INV-007
                throw new UnbalancedJournalException("journal must use a single currency: expected "
                        + currency
                        + " but found "
                        + line.money().currency());
            }
            if (line.isDebit()) {
                debitTotal = Math.addExact(debitTotal, line.money().amount());
            } else {
                creditTotal = Math.addExact(creditTotal, line.money().amount());
            }
        }
        if (debitTotal != creditTotal) {
            // INV-001
            throw new UnbalancedJournalException(
                    "debit total " + debitTotal + " does not match credit total " + creditTotal);
        }
        if (debitTotal == 0L) {
            throw new UnbalancedJournalException("journal total must be positive");
        }
    }

    /** 차변 합계입니다. INV-001에 따라 대변 합계와 같습니다. */
    public Money total() {
        long sum = lines.stream()
                .filter(JournalLine::isDebit)
                .mapToLong(line -> line.money().amount())
                .sum();
        return Money.of(sum, currency);
    }
}

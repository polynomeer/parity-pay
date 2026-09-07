package io.parity.pay.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JournalTest {

    private static final LedgerAccountId BANK = LedgerAccountId.generate();
    private static final LedgerAccountId USER = LedgerAccountId.generate();
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    @Test
    @DisplayName("INV-001: 차변과 대변이 다르면 Journal을 만들 수 없다")
    void rejectsUnbalancedJournal() {
        assertThatThrownBy(() -> journal(List.of(
                        JournalLine.debit(BANK, Money.krw(100_000)), JournalLine.credit(USER, Money.krw(99_999)))))
                .isInstanceOf(UnbalancedJournalException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("INV-002: 금액이 0인 항목은 만들 수 없다")
    void rejectsZeroAmountLine() {
        assertThatThrownBy(() -> JournalLine.debit(BANK, Money.krw(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("항목이 하나뿐이면 분개가 아니다")
    void rejectsSingleLine() {
        assertThatThrownBy(() -> journal(List.of(JournalLine.debit(BANK, Money.krw(100)))))
                .isInstanceOf(UnbalancedJournalException.class)
                .hasMessageContaining("at least two lines");
    }

    @Test
    @DisplayName("균형 잡힌 분개는 총액을 계산할 수 있다")
    void acceptsBalancedJournal() {
        Journal journal = journal(List.of(
                JournalLine.debit(BANK, Money.krw(100_000)),
                JournalLine.credit(USER, Money.krw(30_000)),
                JournalLine.credit(USER, Money.krw(70_000))));

        assertThat(journal.total()).isEqualTo(Money.krw(100_000));
        assertThat(journal.lines()).hasSize(3);
    }

    @Test
    @DisplayName("lines는 방어적으로 복사되어 외부에서 변경할 수 없다")
    void copiesLines() {
        List<JournalLine> lines = new java.util.ArrayList<>(
                List.of(JournalLine.debit(BANK, Money.krw(1_000)), JournalLine.credit(USER, Money.krw(1_000))));
        Journal journal = journal(lines);

        lines.clear();

        assertThat(journal.lines()).hasSize(2);
        assertThatThrownBy(() -> journal.lines().add(JournalLine.debit(BANK, Money.krw(1))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Journal journal(List<JournalLine> lines) {
        return new Journal(
                ReferenceType.TOP_UP,
                UUID.randomUUID(),
                TransactionType.TOP_UP_COMPLETED,
                CurrencyCode.KRW,
                NOW,
                lines);
    }
}

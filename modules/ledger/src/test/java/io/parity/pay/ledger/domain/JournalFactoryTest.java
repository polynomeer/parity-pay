package io.parity.pay.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JournalFactoryTest {

    private static final LedgerAccountId BANK = LedgerAccountId.generate();
    private static final LedgerAccountId USER = LedgerAccountId.generate();
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    @Test
    @DisplayName("JE-001: 충전은 은행예치금 차변, 사용자 페이머니 대변이다")
    void topUpJournalMatchesCatalog() {
        Journal journal =
                JournalFactory.topUpCompleted(TopUpId.generate(), BANK, USER, Money.krw(100_000), NOW);

        assertThat(journal.transactionType()).isEqualTo(TransactionType.TOP_UP_COMPLETED);
        assertThat(journal.referenceType()).isEqualTo(ReferenceType.TOP_UP);
        assertThat(journal.lines())
                .containsExactly(
                        JournalLine.debit(BANK, Money.krw(100_000)),
                        JournalLine.credit(USER, Money.krw(100_000)));
    }

    @Test
    @DisplayName("JE-002 / ADR-009: 원분개와 역분개의 계정별 순효과는 0이다")
    void reversalNetsToZero() {
        Journal original =
                JournalFactory.topUpCompleted(TopUpId.generate(), BANK, USER, Money.krw(100_000), NOW);
        LedgerTransaction posted =
                LedgerTransaction.post(LedgerTransactionId.generate(), original, NOW);

        Journal reversal = JournalFactory.reversalOf(
                posted, ReferenceType.TOP_UP, UUID.randomUUID(), TransactionType.TOP_UP_REVERSED, NOW);

        Map<LedgerAccountId, Long> net = new HashMap<>();
        posted.entries().forEach(entry -> net.merge(
                entry.accountId(), entry.signedAmountFor(Direction.DEBIT), Long::sum));
        reversal.lines().forEach(line -> net.merge(
                line.accountId(),
                line.isDebit() ? line.money().amount() : -line.money().amount(),
                Long::sum));

        assertThat(net).containsOnly(
                org.assertj.core.api.Assertions.entry(BANK, 0L),
                org.assertj.core.api.Assertions.entry(USER, 0L));
    }
}

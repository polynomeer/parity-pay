package io.parity.pay.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.shared.id.CancellationId;
import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.id.LedgerTransactionId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.SettlementId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * 임의의 분개 시퀀스에 대한 속성.
 *
 * <p>분개 카탈로그의 각 항목은 예제 테스트로 하나씩 확인합니다. 여기서 보는 것은 그 분개들을 임의
 * 순서와 임의 금액으로 섞었을 때도 원장 전체가 균형을 유지하는가입니다. 한 건씩 맞는 것과 전체가
 * 맞는 것은 다른 주장입니다.
 *
 * <p>실패하면 jqwik이 seed와 축소된 반례를 출력합니다. 반례는 예제 테스트로 옮깁니다.
 * 근거: CLAUDE.md §6, INV-001, INV-002, INV-007, docs/07-ledger-journal-catalog.md
 */
class LedgerJournalPropertiesTest {

    private static final Instant T0 = Instant.parse("2026-09-07T00:00:00Z");

    /** 계정을 몇 개로 제한해야 시퀀스가 같은 계정을 여러 번 건드리고 합계가 의미를 갖습니다. */
    private static final List<LedgerAccountId> ACCOUNTS = List.of(
            LedgerAccountId.generate(),
            LedgerAccountId.generate(),
            LedgerAccountId.generate(),
            LedgerAccountId.generate(),
            LedgerAccountId.generate());

    @Property
    @Label("INV-001·INV-002·INV-007: 카탈로그가 만드는 모든 분개는 균형·양수·단일 통화다")
    void everyJournalIsBalanced(@ForAll("journalSpecs") JournalSpec spec) {
        Journal journal = spec.build();

        long debit = 0L;
        long credit = 0L;
        for (JournalLine line : journal.lines()) {
            // INV-002
            assertThat(line.money().amount()).isPositive();
            // INV-007
            assertThat(line.money().currency()).isEqualTo(journal.currency());
            if (line.isDebit()) {
                debit += line.money().amount();
            } else {
                credit += line.money().amount();
            }
        }
        // INV-001
        assertThat(debit).isEqualTo(credit).isPositive();

        Statistics.label("분개 종류").collect(spec.kind());
        Statistics.label("분개 종류").coverage(coverage -> {
            for (JournalSpec.Kind kind : JournalSpec.Kind.values()) {
                coverage.check(kind).percentage(p -> p > 5.0);
            }
        });
    }

    @Property
    @Label("INV-001: 임의의 분개 시퀀스를 전기해도 전 계정 순합은 0이다")
    void anySequenceLeavesTheLedgerBalanced(@ForAll("journalSequences") List<JournalSpec> specs) {
        Map<LedgerAccountId, Long> netByAccount = new HashMap<>();

        for (JournalSpec spec : specs) {
            for (JournalLine line : spec.build().lines()) {
                long signed =
                        line.isDebit() ? line.money().amount() : -line.money().amount();
                netByAccount.merge(line.accountId(), signed, Long::sum);
            }
        }

        // 계정별 잔액은 얼마든 될 수 있지만, 전체를 더하면 반드시 0입니다. 이것이 이중부기가
        // 지키는 것이고, 조정 계정으로 차이를 숨기면 여기서 드러납니다.
        assertThat(netByAccount.values().stream().mapToLong(Long::longValue).sum())
                .isZero();
    }

    @Property
    @Label("ADR-009: 역분개는 원거래의 계정별 효과를 정확히 상쇄한다")
    void reversalCancelsTheOriginalExactly(@ForAll("journalSpecs") JournalSpec spec) {
        Journal original = spec.build();
        LedgerTransaction posted = LedgerTransaction.post(LedgerTransactionId.generate(), original, T0);

        Journal reversal = JournalFactory.reversalOf(
                posted, ReferenceType.ADJUSTMENT, UUID.randomUUID(), TransactionType.OPERATIONAL_ADJUSTMENT, T0);

        Map<LedgerAccountId, Long> net = new HashMap<>();
        for (JournalLine line : original.lines()) {
            net.merge(
                    line.accountId(),
                    line.isDebit() ? line.money().amount() : -line.money().amount(),
                    Long::sum);
        }
        for (JournalLine line : reversal.lines()) {
            net.merge(
                    line.accountId(),
                    line.isDebit() ? line.money().amount() : -line.money().amount(),
                    Long::sum);
        }

        assertThat(net.values()).allMatch(value -> value == 0L);
        // 역분개도 원장 거래이므로 금액이 남아 있어야 합니다. 균형은 Journal 생성자가 이미
        // 강제하므로(불균형이면 객체가 만들어지지 않습니다) 여기서는 총액이 원거래와 같은지 봅니다.
        assertThat(reversal.total()).isEqualTo(original.total());
    }

    @Provide
    Arbitrary<JournalSpec> journalSpecs() {
        return Combinators.combine(
                        Arbitraries.of(JournalSpec.Kind.values()),
                        Arbitraries.longs().between(1L, 100_000_000L),
                        Arbitraries.integers().between(0, 100),
                        Arbitraries.of(ACCOUNTS),
                        Arbitraries.of(ACCOUNTS))
                .as(JournalSpec::new);
    }

    @Provide
    Arbitrary<List<JournalSpec>> journalSequences() {
        return journalSpecs().list().ofMinSize(1).ofMaxSize(20);
    }

    /**
     * 분개 하나를 만들 재료.
     *
     * <p>계정 두 개를 뽑되 같은 계정이 나올 수 있으므로, 두 계정이 달라야 하는 분개는 다음 계정으로
     * 밀어서 씁니다. 생성기에서 걸러내면 그만큼 시도가 버려집니다.
     */
    record JournalSpec(Kind kind, long amount, int splitPercent, LedgerAccountId first, LedgerAccountId second) {

        enum Kind {
            TOP_UP,
            PAYMENT,
            CANCELLATION,
            FEE,
            SETTLEMENT_PAYOUT,
            ADJUSTMENT
        }

        Journal build() {
            Money money = Money.of(amount, CurrencyCode.KRW);
            LedgerAccountId other = distinctSecond();
            return switch (kind) {
                case TOP_UP -> JournalFactory.topUpCompleted(TopUpId.generate(), first, other, money, T0);
                case PAYMENT -> JournalFactory.paymentApproved(PaymentId.generate(), first, other, money, T0);
                case CANCELLATION -> {
                    Money fromPayable = Money.of(amount * splitPercent / 100, CurrencyCode.KRW);
                    Money fromReceivable = money.minus(fromPayable);
                    yield JournalFactory.paymentCanceled(
                            CancellationId.generate(), first, third(), other, fromPayable, fromReceivable, T0);
                }
                case FEE -> JournalFactory.feeRecognized(SettlementId.generate(), first, other, money, T0);
                case SETTLEMENT_PAYOUT -> JournalFactory.settlementPaid(
                        SettlementId.generate(), first, other, money, T0);
                case ADJUSTMENT -> JournalFactory.operationalAdjustment(UUID.randomUUID(), first, other, money, T0);
            };
        }

        private LedgerAccountId distinctSecond() {
            if (!second.equals(first)) {
                return second;
            }
            int next = (ACCOUNTS.indexOf(first) + 1) % ACCOUNTS.size();
            return ACCOUNTS.get(next);
        }

        private LedgerAccountId third() {
            int index = (ACCOUNTS.indexOf(first) + 2) % ACCOUNTS.size();
            return ACCOUNTS.get(index);
        }
    }
}

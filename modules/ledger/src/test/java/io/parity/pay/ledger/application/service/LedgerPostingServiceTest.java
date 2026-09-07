package io.parity.pay.ledger.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.Journal;
import io.parity.pay.ledger.domain.JournalFactory;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.ledger.domain.LedgerTransactionStatus;
import io.parity.pay.ledger.testing.InMemoryLedgerAccountRepository;
import io.parity.pay.ledger.testing.InMemoryLedgerTransactionRepository;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.id.LedgerAccountId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LedgerPostingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    private InMemoryLedgerAccountRepository accountRepository;
    private InMemoryLedgerTransactionRepository transactionRepository;
    private LedgerPostingService postingService;
    private LedgerBalanceService balanceService;

    private LedgerAccount bankAccount;
    private LedgerAccount userAccount;

    @BeforeEach
    void setUp() {
        accountRepository = new InMemoryLedgerAccountRepository();
        transactionRepository = new InMemoryLedgerTransactionRepository();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        postingService = new LedgerPostingService(transactionRepository, accountRepository, clock);
        balanceService = new LedgerBalanceService(transactionRepository, accountRepository);

        bankAccount = accountRepository.save(new LedgerAccount(
                LedgerAccountId.generate(), AccountCode.BANK_DEPOSIT, null, CurrencyCode.KRW, true));
        userAccount = accountRepository.save(new LedgerAccount(
                LedgerAccountId.generate(),
                AccountCode.USER_PAY_MONEY,
                WalletId.generate().value(),
                CurrencyCode.KRW,
                true));
    }

    @Test
    @DisplayName("전기하면 POSTED 거래와 균형 잡힌 항목이 생성된다")
    void postsBalancedTransaction() {
        LedgerTransaction posted = postingService.post(topUpJournal(TopUpId.generate(), 100_000));

        assertThat(posted.status()).isEqualTo(LedgerTransactionStatus.POSTED);
        assertThat(posted.entries()).hasSize(2);
        assertThat(posted.entries().stream().filter(e -> e.isDebit()).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("INV-004: 같은 업무 참조로 100번 전기해도 POSTED 거래는 한 건이다")
    void isIdempotentPerBusinessReference() {
        TopUpId topUpId = TopUpId.generate();

        LedgerTransaction first = postingService.post(topUpJournal(topUpId, 100_000));
        for (int i = 0; i < 99; i++) {
            LedgerTransaction repeated = postingService.post(topUpJournal(topUpId, 100_000));
            assertThat(repeated.id()).isEqualTo(first.id());
        }

        assertThat(transactionRepository.size()).isEqualTo(1);
        assertThat(balanceService.balanceOf(userAccount.id())).isEqualTo(Money.krw(100_000));
    }

    @Test
    @DisplayName("비활성 계정에는 전기할 수 없다")
    void rejectsInactiveAccount() {
        accountRepository.save(new LedgerAccount(
                bankAccount.id(), AccountCode.BANK_DEPOSIT, null, CurrencyCode.KRW, false));

        assertThatThrownBy(() -> postingService.post(topUpJournal(TopUpId.generate(), 1_000)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not active");
    }

    @Test
    @DisplayName("§6: 사용자 페이머니 잔액은 대변 합계에서 차변 합계를 뺀 값이다")
    void calculatesBalanceByNormalBalanceDirection() {
        postingService.post(topUpJournal(TopUpId.generate(), 100_000));
        postingService.post(topUpJournal(TopUpId.generate(), 50_000));

        assertThat(balanceService.balanceOf(userAccount.id())).isEqualTo(Money.krw(150_000));
        assertThat(balanceService.balanceOf(bankAccount.id())).isEqualTo(Money.krw(150_000));
    }

    private Journal topUpJournal(TopUpId topUpId, long amount) {
        return JournalFactory.topUpCompleted(
                topUpId, bankAccount.id(), userAccount.id(), Money.krw(amount), NOW);
    }
}

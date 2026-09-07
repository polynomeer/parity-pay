package io.parity.pay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.ledger.application.port.in.LedgerBalanceQuery;
import io.parity.pay.ledger.application.port.in.PostJournalUseCase;
import io.parity.pay.ledger.application.port.in.ResolveLedgerAccountUseCase;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.ledger.domain.Journal;
import io.parity.pay.ledger.domain.JournalFactory;
import io.parity.pay.ledger.domain.LedgerAccount;
import io.parity.pay.ledger.domain.LedgerTransaction;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 PostgreSQL에서 원장 불변조건이 지켜지는지 검증합니다.
 *
 * <p>T-001(전기), INV-001(균형), INV-004(중복 방지), INV-006(불변성)을 다룹니다.
 */
class LedgerPostingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PostJournalUseCase postJournal;

    @Autowired
    private ResolveLedgerAccountUseCase resolveAccount;

    @Autowired
    private LedgerBalanceQuery balanceQuery;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LedgerAccount bankAccount;
    private LedgerAccount userAccount;

    @BeforeEach
    void setUp() {
        // 원장은 append-only이므로 DELETE가 트리거에 막힙니다(INV-006). 테스트 격리에는 TRUNCATE만
        // 사용하며, 운영 코드와 운영 절차에서는 어떤 경우에도 사용하지 않습니다.
        jdbcTemplate.execute("TRUNCATE ledger_entry, ledger_transaction, ledger_account CASCADE");

        bankAccount = resolveAccount.resolveCorporate(AccountCode.BANK_DEPOSIT, CurrencyCode.KRW);
        userAccount = resolveAccount.resolve(
                AccountCode.USER_PAY_MONEY, WalletId.generate().value(), CurrencyCode.KRW);
    }

    @Test
    @DisplayName("T-001: 전기하면 균형 잡힌 거래와 항목이 한 트랜잭션에 저장된다")
    void postsBalancedTransaction() {
        TopUpId topUpId = TopUpId.generate();

        LedgerTransaction posted =
                transactionTemplate.execute(status -> postJournal.post(topUpJournal(topUpId, 100_000)));

        assertThat(posted).isNotNull();
        Long entryCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entry WHERE transaction_id = ?",
                Long.class,
                posted.id().value());
        assertThat(entryCount).isEqualTo(2L);
        assertThat(balanceQuery.balanceOf(userAccount.id())).isEqualTo(Money.krw(100_000));
        assertThat(balanceQuery.balanceOf(bankAccount.id())).isEqualTo(Money.krw(100_000));
    }

    @Test
    @DisplayName("INV-004: 같은 업무 참조를 반복 전기해도 원장 거래는 한 건이다")
    void isIdempotentForSameBusinessReference() {
        TopUpId topUpId = TopUpId.generate();

        LedgerTransaction first =
                transactionTemplate.execute(status -> postJournal.post(topUpJournal(topUpId, 100_000)));
        LedgerTransaction second =
                transactionTemplate.execute(status -> postJournal.post(topUpJournal(topUpId, 100_000)));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(countTransactions()).isEqualTo(1L);
        assertThat(balanceQuery.balanceOf(userAccount.id())).isEqualTo(Money.krw(100_000));
    }

    @Test
    @DisplayName("INV-004: 유니크 제약이 애플리케이션 검사를 우회한 중복 삽입도 막는다")
    void databaseRejectsDuplicateBusinessReference() {
        TopUpId topUpId = TopUpId.generate();
        transactionTemplate.execute(status -> postJournal.post(topUpJournal(topUpId, 100_000)));

        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO ledger_transaction
                            (transaction_id, transaction_type, reference_type, reference_id,
                             currency, status, effective_at, created_at)
                        VALUES (?, 'TOP_UP_COMPLETED', 'TOP_UP', ?, 'KRW', 'POSTED', now(), now())
                        """,
                        UUID.randomUUID(),
                        topUpId.value()))
                .hasStackTraceContaining("uq_ledger_transaction_reference");
    }

    @Test
    @DisplayName("INV-006: 확정 원장 항목은 UPDATE·DELETE할 수 없다")
    void postedEntriesAreImmutable() {
        LedgerTransaction posted =
                transactionTemplate.execute(status -> postJournal.post(topUpJournal(TopUpId.generate(), 100_000)));

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE ledger_entry SET amount = amount + 1 WHERE transaction_id = ?",
                        posted.id().value()))
                .hasStackTraceContaining("INV-006");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM ledger_entry WHERE transaction_id = ?",
                        posted.id().value()))
                .hasStackTraceContaining("INV-006");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM ledger_transaction WHERE transaction_id = ?",
                        posted.id().value()))
                .hasStackTraceContaining("INV-006");
    }

    @Test
    @DisplayName("INV-001: 불균형 분개는 커밋 시점에 DB가 거부한다")
    void databaseRejectsUnbalancedTransaction() {
        UUID transactionId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.update(
                            """
                            INSERT INTO ledger_transaction
                                (transaction_id, transaction_type, reference_type, reference_id,
                                 currency, status, effective_at, created_at)
                            VALUES (?, 'TOP_UP_COMPLETED', 'TOP_UP', ?, 'KRW', 'POSTED', now(), now())
                            """,
                            transactionId,
                            UUID.randomUUID());
                    jdbcTemplate.update(
                            """
                            INSERT INTO ledger_entry
                                (entry_id, transaction_id, account_id, direction, amount, created_at)
                            VALUES (?, ?, ?, 'DEBIT', 100000, now())
                            """,
                            UUID.randomUUID(),
                            transactionId,
                            bankAccount.id().value());
                    jdbcTemplate.update(
                            """
                            INSERT INTO ledger_entry
                                (entry_id, transaction_id, account_id, direction, amount, created_at)
                            VALUES (?, ?, ?, 'CREDIT', 99999, now())
                            """,
                            UUID.randomUUID(),
                            transactionId,
                            userAccount.id().value());
                }))
                .hasStackTraceContaining("INV-001");

        assertThat(countTransactions()).isZero();
    }

    @Test
    @DisplayName("INV-002: 0원 이하 항목은 DB가 거부한다")
    void databaseRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO ledger_entry
                            (entry_id, transaction_id, account_id, direction, amount, created_at)
                        VALUES (?, ?, ?, 'DEBIT', 0, now())
                        """,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        bankAccount.id().value()))
                .hasStackTraceContaining("ck_ledger_entry_amount_positive");
    }

    private Journal topUpJournal(TopUpId topUpId, long amount) {
        return JournalFactory.topUpCompleted(
                topUpId, bankAccount.id(), userAccount.id(), Money.krw(amount), Instant.parse("2026-09-05T00:00:00Z"));
    }

    private Long countTransactions() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_transaction", Long.class);
    }
}

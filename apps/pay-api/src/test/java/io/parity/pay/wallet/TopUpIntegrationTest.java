package io.parity.pay.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.api.mockbank.MockBankBehavior;
import io.parity.pay.api.mockbank.MockBankClient;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpCommand;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpView;
import io.parity.pay.wallet.application.port.in.WalletQuery;
import io.parity.pay.wallet.domain.TopUpStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 충전 종단 시나리오.
 *
 * <p>AC-001(정상 충전), NFR-003(멱등성), AC-004/F-006(승인 후 응답 유실), INV-003, INV-010을 다룹니다.
 */
class TopUpIntegrationTest extends AbstractIntegrationTest {

    private static final long BANK_BALANCE = 1_000_000L;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private RequestTopUpUseCase requestTopUp;

    @Autowired
    private WalletQuery walletQuery;

    @Autowired
    private MockBankBehavior mockBankBehavior;

    @Autowired
    private MockBankClient bankClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MemberId memberId;
    private WalletId walletId;
    private BankAccountId bankAccountId;

    @BeforeEach
    void setUp() {
        mockBankBehavior.reset();
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, top_up,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);

        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember("buyer@example.com", "password1234");
        memberId = registered.memberId();
        walletId = WalletId.of(registered.walletId());
        bankAccountId = onboardingService.linkBankAccount(memberId, "004", "110-1234-5678", Money.krw(BANK_BALANCE));
    }

    @Test
    @DisplayName("AC-001: 충전이 성공하면 잔액·원장·스냅샷이 함께 맞는다")
    void topUpSucceeds() {
        TopUpView view = topUp("top-up-key-00001", 100_000);

        assertThat(view.status()).isEqualTo(TopUpStatus.SUCCEEDED);
        assertThat(view.completedAmount()).isEqualTo(Money.krw(100_000));
        assertThat(availableBalance()).isEqualTo(100_000L);
        assertThat(ledgerTransactionCount()).isEqualTo(1L);

        // INV-010: 스냅샷과 원장 재생값이 일치합니다.
        WalletQuery.BalanceVerification verification = walletQuery.verifyAgainstLedger(walletId);
        assertThat(verification.matches()).isTrue();
        assertThat(verification.ledger()).isEqualTo(Money.krw(100_000));

        // 외부기관 쪽에서도 출금은 한 번입니다.
        assertThat(bankBalance()).isEqualTo(BANK_BALANCE - 100_000);
    }

    @Test
    @DisplayName("NFR-003: 같은 멱등 키로 100번 요청해도 금액은 한 번만 이동한다")
    void repeatedRequestsWithSameKeyMoveMoneyOnce() {
        String key = "top-up-key-00002";

        TopUpView first = topUp(key, 100_000);
        for (int i = 0; i < 99; i++) {
            TopUpView repeated = topUp(key, 100_000);
            assertThat(repeated.topUpId()).isEqualTo(first.topUpId());
        }

        assertThat(availableBalance()).isEqualTo(100_000L);
        assertThat(topUpCount()).isEqualTo(1L);
        assertThat(ledgerTransactionCount()).isEqualTo(1L);
        assertThat(bankBalance()).isEqualTo(BANK_BALANCE - 100_000);
    }

    @Test
    @DisplayName("NFR-003: 같은 멱등 키로 동시에 20건이 도착해도 금액은 한 번만 이동한다")
    void concurrentRequestsWithSameKeyMoveMoneyOnce() throws Exception {
        String key = "top-up-key-00003";
        int concurrency = 20;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
            List<Callable<TopUpView>> tasks = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                tasks.add(() -> {
                    start.await();
                    return topUp(key, 50_000);
                });
            }
            List<Future<TopUpView>> futures = new ArrayList<>();
            for (Callable<TopUpView> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();

            List<UUID> topUpIds = new ArrayList<>();
            for (Future<TopUpView> future : futures) {
                topUpIds.add(future.get().topUpId().value());
            }
            assertThat(topUpIds).containsOnly(topUpIds.get(0));
        }

        assertThat(availableBalance()).isEqualTo(50_000L);
        assertThat(topUpCount()).isEqualTo(1L);
        assertThat(ledgerTransactionCount()).isEqualTo(1L);
        assertThat(bankBalance()).isEqualTo(BANK_BALANCE - 50_000);
    }

    @Test
    @DisplayName("외부가 명시적으로 실패를 응답하면 FAILED로 확정하고 금액은 움직이지 않는다")
    void explicitFailureDoesNotMoveMoney() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.EXPLICIT_FAILURE);

        TopUpView view = topUp("top-up-key-00004", 100_000);

        assertThat(view.status()).isEqualTo(TopUpStatus.FAILED);
        assertThat(availableBalance()).isZero();
        assertThat(ledgerTransactionCount()).isZero();
        assertThat(bankBalance()).isEqualTo(BANK_BALANCE);
    }

    @Test
    @DisplayName("AC-004/F-006: 외부 승인 후 응답이 유실되면 실패로 단정하지 않고 UNKNOWN으로 보존한다")
    void lostResponseAfterWithdrawalBecomesUnknown() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_AFTER_WITHDRAWAL);

        TopUpView view = topUp("top-up-key-00005", 100_000);

        assertThat(view.status()).isEqualTo(TopUpStatus.UNKNOWN);
        // 아직 확정되지 않았으므로 잔액과 원장에는 아무 효과가 없습니다.
        assertThat(availableBalance()).isZero();
        assertThat(ledgerTransactionCount()).isZero();
        // 외부기관에서는 이미 출금이 일어났습니다. 이 차이가 복구 작업과 대사의 대상입니다.
        assertThat(bankBalance()).isEqualTo(BANK_BALANCE - 100_000);
        assertThat(idempotencyStatus("top-up-key-00005")).isEqualTo("RECOVERY_REQUIRED");
    }

    @Test
    @DisplayName("외부 처리 전 타임아웃도 UNKNOWN이다. 실패로 확정하지 않는다")
    void lostResponseBeforeWithdrawalBecomesUnknown() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_BEFORE_WITHDRAWAL);

        TopUpView view = topUp("top-up-key-00006", 100_000);

        assertThat(view.status()).isEqualTo(TopUpStatus.UNKNOWN);
        assertThat(availableBalance()).isZero();
        assertThat(bankBalance()).isEqualTo(BANK_BALANCE);
    }

    @Test
    @DisplayName("은행 잔액이 부족하면 명시적 실패로 확정한다")
    void insufficientBankBalanceFails() {
        TopUpView view = topUp("top-up-key-00007", BANK_BALANCE + 1);

        assertThat(view.status()).isEqualTo(TopUpStatus.FAILED);
        assertThat(availableBalance()).isZero();
        assertThat(bankBalance()).isEqualTo(BANK_BALANCE);
    }

    @Test
    @DisplayName("여러 번 충전하면 잔액과 원장이 함께 누적된다")
    void multipleTopUpsAccumulate() {
        topUp("top-up-key-00008", 100_000);
        topUp("top-up-key-00009", 30_000);

        assertThat(availableBalance()).isEqualTo(130_000L);
        assertThat(ledgerTransactionCount()).isEqualTo(2L);
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();
    }

    private TopUpView topUp(String idempotencyKey, long amount) {
        return requestTopUp.requestTopUp(new TopUpCommand(
                memberId, walletId, bankAccountId, Money.krw(amount), IdempotencyKey.of(idempotencyKey)));
    }

    private long availableBalance() {
        Long balance = jdbcTemplate.queryForObject(
                "SELECT available_amount FROM wallet_balance WHERE wallet_id = ?", Long.class, walletId.value());
        return balance == null ? 0L : balance;
    }

    private long bankBalance() {
        return bankClient.totalAccountBalance();
    }

    private Long ledgerTransactionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_transaction", Long.class);
    }

    private Long topUpCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM top_up", Long.class);
    }

    private String idempotencyStatus(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_record WHERE idempotency_key = ?", String.class, key);
    }
}

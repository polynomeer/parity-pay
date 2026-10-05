package io.parity.pay.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.api.mockbank.MockBankBehavior;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpCommand;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpView;
import io.parity.pay.wallet.application.service.TopUpRecoveryService;
import io.parity.pay.wallet.domain.TopUpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 기관이 창을 선언하지 않았으면 "없음"을 확정하지 않고 사람에게 넘깁니다 (ADR-016).
 *
 * <p>음수 설정은 "선언하지 않음"입니다(설정에서 비우는 것과 같습니다). 기다릴 근거도 확정할 근거도
 * 없으므로 자동 확정을 포기합니다 — 처치 갈래가 지키는 것이 무엇인지는 reports/11 M-030에 있습니다.
 */
@TestPropertySource(properties = "paritypay.recovery.top-up.not-found-settle-after=-1s")
class NotFoundWithoutWindowTest extends AbstractIntegrationTest {

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private RequestTopUpUseCase requestTopUp;

    @Autowired
    private TopUpRecoveryService recoveryService;

    @Autowired
    private MockBankBehavior mockBankBehavior;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MemberId memberId;
    private BankAccountId bankAccountId;
    private java.util.UUID walletId;

    @BeforeEach
    void setUp() {
        mockBankBehavior.reset();
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment,
                         top_up_recovery, top_up, audit_log,
                         outbox_event, consumed_event, wallet_transaction,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember("no-window@example.com", "password1234");
        memberId = registered.memberId();
        walletId = registered.walletId();
        bankAccountId = onboardingService.linkBankAccount(memberId, "004", "110-5555-3333", Money.krw(1_000_000L));
    }

    @Test
    @DisplayName("창이 선언되지 않았으면 확정하지 않고 수동 검토로 넘긴다")
    void escalatesInsteadOfSettling() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_BEFORE_WITHDRAWAL);
        TopUpView view = topUp("no-window-key-00001");
        assertThat(view.status()).isEqualTo(TopUpStatus.UNKNOWN);
        mockBankBehavior.reset();

        assertThat(recoveryService.resolveDue()).isZero();
        jdbcTemplate.update("UPDATE top_up_recovery SET next_check_at = now() - interval '1 minute'");
        assertThat(recoveryService.resolveDue()).isZero();

        // 기준 횟수를 채웠지만 기다릴 창이 없습니다. 추측으로 확정하지 않고 사람이 봅니다.
        assertThat(requiresManualReview(view.topUpId())).isTrue();
        assertThat(topUpStatus(view.topUpId())).isEqualTo("UNKNOWN");
        assertThat(availableBalance()).isZero();
        assertThat(ledgerTransactionCount()).isZero();

        // 수동 검토로 넘긴 건은 자동 배치가 더 이상 집어가지 않습니다.
        assertThat(recoveryService.resolveDue()).isZero();
    }

    private TopUpView topUp(String key) {
        return requestTopUp.requestTopUp(new TopUpCommand(
                memberId,
                io.parity.pay.shared.id.WalletId.of(walletId),
                bankAccountId,
                Money.krw(100_000L),
                IdempotencyKey.of(key)));
    }

    private String topUpStatus(TopUpId topUpId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM top_up WHERE top_up_id = ?", String.class, topUpId.value());
    }

    private boolean requiresManualReview(TopUpId topUpId) {
        Boolean flag = jdbcTemplate.queryForObject(
                "SELECT requires_manual_review FROM top_up_recovery WHERE top_up_id = ?",
                Boolean.class,
                topUpId.value());
        return Boolean.TRUE.equals(flag);
    }

    private long availableBalance() {
        Long amount = jdbcTemplate.queryForObject(
                "SELECT available_amount FROM wallet_balance WHERE wallet_id = ?", Long.class, walletId);
        return amount == null ? 0L : amount;
    }

    private long ledgerTransactionCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_transaction", Long.class);
        return count == null ? 0L : count;
    }
}

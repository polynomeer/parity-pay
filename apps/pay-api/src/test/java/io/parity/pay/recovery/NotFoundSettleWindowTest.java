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
 * 기관이 보장한 창이 닫히기 전에는 "없음"을 실패로 확정하지 않습니다 (ADR-016).
 *
 * <p>기본 시험 설정은 창을 `0s`로 둡니다 — 시험 대역은 요청 안에서 기록하거나 영원히 기록하지 않으므로
 * 기다릴 것이 없습니다. 이 클래스는 창을 **1시간**으로 바꿔, 받아 두고 아직 기록하지 않았을 수 있는
 * 구간에서 복구가 무엇을 하는지 봅니다. 그 구간이 모델 검사가 찾은 반례가 사는 곳입니다(reports/11 M-030).
 */
@TestPropertySource(properties = "paritypay.recovery.top-up.not-found-settle-after=1h")
class NotFoundSettleWindowTest extends AbstractIntegrationTest {

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
                onboardingService.registerMember("window@example.com", "password1234");
        memberId = registered.memberId();
        walletId = registered.walletId();
        bankAccountId = onboardingService.linkBankAccount(memberId, "004", "110-5555-4444", Money.krw(1_000_000L));
    }

    @Test
    @DisplayName("창이 열려 있는 동안에는 연속 '없음'이 기준을 넘어도 UNKNOWN으로 남는다 (M-030 반례 구간)")
    void staysUnknownWhileTheInstitutionCouldStillRecordIt() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_BEFORE_WITHDRAWAL);
        TopUpView view = topUp("window-key-00001");
        assertThat(view.status()).isEqualTo(TopUpStatus.UNKNOWN);
        mockBankBehavior.reset();

        // 기준 횟수(2회)를 넘겨 네 번 물어도 확정하지 않습니다. 기관이 아직 기록할 수 있는 창입니다.
        for (int i = 0; i < 4; i++) {
            assertThat(recoveryService.resolveDue()).isZero();
            jdbcTemplate.update("UPDATE top_up_recovery SET next_check_at = now() - interval '1 minute'"
                    + " WHERE requires_manual_review = false");
        }

        assertThat(topUpStatus(view.topUpId())).isEqualTo("UNKNOWN");
        assertThat(notFoundCount(view.topUpId())).isGreaterThanOrEqualTo(2);
        assertThat(requiresManualReview(view.topUpId())).isFalse();
        assertThat(availableBalance()).isZero();
        assertThat(ledgerTransactionCount()).isZero();
    }

    @Test
    @DisplayName("창이 닫히면 같은 '없음'이 실패로 확정된다")
    void settlesOnceTheWindowHasClosed() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_BEFORE_WITHDRAWAL);
        TopUpView view = topUp("window-key-00002");
        mockBankBehavior.reset();

        assertThat(recoveryService.resolveDue()).isZero();
        jdbcTemplate.update("UPDATE top_up_recovery SET next_check_at = now() - interval '1 minute'");
        assertThat(recoveryService.resolveDue()).isZero();

        // 요청 시각을 두 시간 앞으로 밀어 창을 닫습니다. 시간을 실제로 기다리면 1시간짜리 시험이 됩니다.
        jdbcTemplate.update(
                "UPDATE top_up SET requested_at = requested_at - interval '2 hours' WHERE top_up_id = ?",
                view.topUpId().value());
        jdbcTemplate.update("UPDATE top_up_recovery SET next_check_at = now() - interval '1 minute'");

        assertThat(recoveryService.resolveDue()).isEqualTo(1);
        assertThat(topUpStatus(view.topUpId())).isEqualTo("FAILED");
        assertThat(availableBalance()).isZero();
        assertThat(ledgerTransactionCount()).isZero();
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

    private int notFoundCount(TopUpId topUpId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT not_found_count FROM top_up_recovery WHERE top_up_id = ?", Integer.class, topUpId.value());
        return count == null ? 0 : count;
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

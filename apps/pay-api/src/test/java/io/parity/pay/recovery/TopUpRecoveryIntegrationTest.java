package io.parity.pay.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.api.mockbank.MockBankBehavior;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.api.outbox.OutboxPublisher;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.TopUpId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpCommand;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpView;
import io.parity.pay.wallet.application.port.in.WalletQuery;
import io.parity.pay.wallet.application.service.TopUpRecoveryService;
import io.parity.pay.wallet.domain.TopUpStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 외부 결과 불명확 거래의 복구.
 *
 * <p>F-006(승인 후 응답 유실 → 수렴), F-009(조회 장애 → 백오프 후 수동 검토), 재시작으로 PROCESSING에
 * 남은 거래의 복구, ADR-007의 "타임아웃은 실패가 아니다"를 다룹니다.
 */
class TopUpRecoveryIntegrationTest extends AbstractIntegrationTest {

    private static final long BANK_BALANCE = 1_000_000L;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private RequestTopUpUseCase requestTopUp;

    @Autowired
    private TopUpRecoveryService recoveryService;

    @Autowired
    private WalletQuery walletQuery;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private MockBankBehavior mockBankBehavior;

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
                         idempotency_record, payment_cancellation, payment,
                         top_up_recovery, top_up, audit_log,
                         outbox_event, consumed_event, wallet_transaction,
                         mock_bank_withdrawal, mock_bank_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);

        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember("recovery@example.com", "password1234");
        memberId = registered.memberId();
        walletId = WalletId.of(registered.walletId());
        bankAccountId = onboardingService.linkBankAccount(
                memberId, "004", "110-7777-6666", Money.krw(BANK_BALANCE));
    }

    @Test
    @DisplayName("F-006: 승인 후 응답이 유실된 충전이 조회로 SUCCEEDED에 수렴한다")
    void unknownAfterWithdrawalConvergesToSucceeded() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_AFTER_WITHDRAWAL);
        TopUpView view = topUp("recovery-key-00001", 100_000);
        assertThat(view.status()).isEqualTo(TopUpStatus.UNKNOWN);
        assertThat(availableBalance()).isZero();

        // 외부는 응답만 잃었을 뿐 출금은 처리했습니다. 조회하면 성공이 확인됩니다.
        mockBankBehavior.reset();
        int settled = recoveryService.resolveDue();

        assertThat(settled).isEqualTo(1);
        assertThat(topUpStatus(view.topUpId())).isEqualTo("SUCCEEDED");
        assertThat(availableBalance()).isEqualTo(100_000L);
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();
        assertThat(ledgerTransactionCount()).isEqualTo(1L);
        // 복구가 끝나면 스케줄 흔적을 남기지 않습니다.
        assertThat(recoveryRowCount()).isZero();
    }

    @Test
    @DisplayName("복구로 확정된 충전도 이벤트를 발행한다")
    void recoveredTopUpEmitsEvent() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_AFTER_WITHDRAWAL);
        topUp("recovery-key-00002", 100_000);
        mockBankBehavior.reset();

        recoveryService.resolveDue();

        assertThat(outboxEventTypes()).contains("TopUpCompleted");
        assertThat(outboxPublisher.publishBatch()).isPositive();
    }

    @Test
    @DisplayName("복구를 여러 번 돌려도 금액은 한 번만 움직인다")
    void repeatedRecoveryIsIdempotent() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_AFTER_WITHDRAWAL);
        topUp("recovery-key-00003", 100_000);
        mockBankBehavior.reset();

        recoveryService.resolveDue();
        recoveryService.resolveDue();
        recoveryService.resolveDue();

        assertThat(availableBalance()).isEqualTo(100_000L);
        assertThat(ledgerTransactionCount()).isEqualTo(1L);
        assertThat(bankBalance()).isEqualTo(BANK_BALANCE - 100_000);
    }

    @Test
    @DisplayName("외부에 기록이 없으면 한 번의 조회로 단정하지 않고, 반복 확인 후 실패로 확정한다")
    void missingExternalRecordIsConfirmedBeforeFailing() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_BEFORE_WITHDRAWAL);
        TopUpView view = topUp("recovery-key-00004", 100_000);
        assertThat(view.status()).isEqualTo(TopUpStatus.UNKNOWN);
        mockBankBehavior.reset();

        // 첫 조회에서 "없음"을 봤지만 아직 확정하지 않습니다.
        assertThat(recoveryService.resolveDue()).isZero();
        assertThat(topUpStatus(view.topUpId())).isEqualTo("UNKNOWN");
        assertThat(notFoundCount(view.topUpId())).isEqualTo(1);

        // 기준 횟수(2회)를 채우면 자금이 움직이지 않았다고 보고 실패로 확정합니다.
        assertThat(recoveryService.resolveDue()).isEqualTo(1);
        assertThat(topUpStatus(view.topUpId())).isEqualTo("FAILED");
        assertThat(availableBalance()).isZero();
        assertThat(bankBalance()).isEqualTo(BANK_BALANCE);
    }

    @Test
    @DisplayName("F-009: 조회 API가 죽어 있으면 아무것도 확정하지 않고 재시도한다")
    void statusQueryFailureDoesNotSettleAnything() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_AFTER_WITHDRAWAL);
        TopUpView view = topUp("recovery-key-00005", 100_000);
        mockBankBehavior.setMode(MockBankBehavior.Mode.NORMAL);
        mockBankBehavior.setStatusQueryAvailable(false);

        assertThat(recoveryService.resolveDue()).isZero();

        assertThat(topUpStatus(view.topUpId())).isEqualTo("UNKNOWN");
        assertThat(availableBalance()).isZero();
        assertThat(attemptCount(view.topUpId())).isEqualTo(1);
        assertThat(requiresManualReview(view.topUpId())).isFalse();
    }

    @Test
    @DisplayName("F-009: 조회 장애가 계속되면 자동 재시도를 멈추고 수동 검토로 넘긴다")
    void repeatedQueryFailuresEscalateToManualReview() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_AFTER_WITHDRAWAL);
        TopUpView view = topUp("recovery-key-00006", 100_000);
        mockBankBehavior.setMode(MockBankBehavior.Mode.NORMAL);
        mockBankBehavior.setStatusQueryAvailable(false);

        // max-attempts=3 (테스트 설정). 백오프가 지났다고 보고 다음 라운드를 돌립니다.
        // 실제 시간이 흐르기를 기다리면 백오프 길이에 따라 결과가 달라지는 테스트가 됩니다.
        for (int i = 0; i < 3; i++) {
            recoveryService.resolveDue();
            jdbcTemplate.update(
                    "UPDATE top_up_recovery SET next_check_at = now() - interval '1 minute'"
                            + " WHERE requires_manual_review = false");
        }

        assertThat(requiresManualReview(view.topUpId())).isTrue();
        assertThat(topUpStatus(view.topUpId())).isEqualTo("UNKNOWN");

        // 수동 검토로 넘어간 건은 자동 배치가 더 이상 집어가지 않습니다.
        assertThat(recoveryService.resolveDue()).isZero();
        assertThat(attemptCount(view.topUpId())).isEqualTo(3);
    }

    @Test
    @DisplayName("재시작으로 PROCESSING에 남은 충전도 복구 대상이다")
    void abandonedProcessingTopUpIsRecovered() {
        // 외부 호출 직전에 프로세스가 죽어 PROCESSING으로 남은 상태를 만듭니다.
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_AFTER_WITHDRAWAL);
        TopUpView view = topUp("recovery-key-00007", 100_000);
        jdbcTemplate.update(
                "UPDATE top_up SET status = 'PROCESSING' WHERE top_up_id = ?", view.topUpId().value());
        mockBankBehavior.reset();

        assertThat(recoveryService.resolveDue()).isEqualTo(1);

        assertThat(topUpStatus(view.topUpId())).isEqualTo("SUCCEEDED");
        assertThat(availableBalance()).isEqualTo(100_000L);
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();
    }

    @Test
    @DisplayName("이미 확정된 충전은 복구 대상에서 조용히 빠진다")
    void settledTopUpIsNotReprocessed() {
        topUp("recovery-key-00008", 100_000);

        assertThat(recoveryService.resolveDue()).isZero();
        assertThat(availableBalance()).isEqualTo(100_000L);
        assertThat(ledgerTransactionCount()).isEqualTo(1L);
    }

    private TopUpView topUp(String key, long amount) {
        return requestTopUp.requestTopUp(new TopUpCommand(
                memberId, walletId, bankAccountId, Money.krw(amount), IdempotencyKey.of(key)));
    }

    private String topUpStatus(TopUpId topUpId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM top_up WHERE top_up_id = ?", String.class, topUpId.value());
    }

    private int attemptCount(TopUpId topUpId) {
        return recoveryRow(topUpId) == null
                ? 0
                : ((Number) recoveryRow(topUpId).get("attempt_count")).intValue();
    }

    private int notFoundCount(TopUpId topUpId) {
        return recoveryRow(topUpId) == null
                ? 0
                : ((Number) recoveryRow(topUpId).get("not_found_count")).intValue();
    }

    private boolean requiresManualReview(TopUpId topUpId) {
        Map<String, Object> row = recoveryRow(topUpId);
        return row != null && (Boolean) row.get("requires_manual_review");
    }

    private Map<String, Object> recoveryRow(TopUpId topUpId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT * FROM top_up_recovery WHERE top_up_id = ?", topUpId.value());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long recoveryRowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM top_up_recovery", Long.class);
        return count == null ? 0L : count;
    }

    private long availableBalance() {
        Long balance = jdbcTemplate.queryForObject(
                "SELECT available_amount FROM wallet_balance WHERE wallet_id = ?",
                Long.class,
                walletId.value());
        return balance == null ? 0L : balance;
    }

    private long bankBalance() {
        Long balance = jdbcTemplate.queryForObject("SELECT balance FROM mock_bank_account", Long.class);
        return balance == null ? 0L : balance;
    }

    private Long ledgerTransactionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_transaction", Long.class);
    }

    private java.util.List<String> outboxEventTypes() {
        return jdbcTemplate.queryForList("SELECT event_type FROM outbox_event", String.class);
    }
}

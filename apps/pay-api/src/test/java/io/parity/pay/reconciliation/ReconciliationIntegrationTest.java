package io.parity.pay.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.api.mockbank.MockBankBehavior;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.support.ApiAuth;
import io.parity.pay.ledger.domain.AccountCode;
import io.parity.pay.reconciliation.application.service.MismatchResolutionService;
import io.parity.pay.reconciliation.application.service.ReconciliationService;
import io.parity.pay.reconciliation.domain.MismatchType;
import io.parity.pay.reconciliation.domain.ReconciliationMismatch;
import io.parity.pay.reconciliation.domain.ReconciliationMismatch.ResolutionStatus;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpCommand;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 내부·외부 기록 대사.
 *
 * <p>FR-017(대사와 불일치 분류), F-010(원장·기록 차이 탐지), docs/09 §10(보정은 새 분개),
 * 이중 승인 통제를 다룹니다.
 */
class ReconciliationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private RequestTopUpUseCase requestTopUp;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private MismatchResolutionService resolutionService;

    @Autowired
    private MockBankBehavior mockBankBehavior;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

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
                         reconciliation_mismatch, reconciliation_run,
                         settlement_recovery, settlement_item, settlement, order_confirmation,
                         mock_bank_payout,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment,
                         top_up_recovery, top_up, audit_log,
                         outbox_event, consumed_event, wallet_transaction,
                         mock_bank_withdrawal, mock_bank_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);

        // 보정에는 실제 승인 권한을 가진 운영자가 필요합니다.
        operatorBootstrap.createConfiguredOperators();

        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember("recon@example.com", "password1234");
        memberId = registered.memberId();
        walletId = WalletId.of(registered.walletId());
        bankAccountId = onboardingService.linkBankAccount(
                memberId, "004", "110-1111-0000", Money.krw(1_000_000));
    }

    @Test
    @DisplayName("정상 거래만 있으면 불일치가 없다")
    void cleanLedgerProducesNoMismatch() {
        topUp("recon-key-000001", 100_000);

        ReconciliationService.ReconciliationSummary summary =
                reconciliationService.runTopUpReconciliation();

        assertThat(summary.internalCount()).isEqualTo(1);
        assertThat(summary.externalCount()).isEqualTo(1);
        assertThat(summary.mismatchCount()).isZero();
    }

    @Test
    @DisplayName("INTERNAL_ONLY: 우리는 충전 성공인데 외부에 출금 기록이 없다")
    void internalOnlyIsDetected() {
        TopUpView view = topUp("recon-key-000002", 100_000);
        // 외부 기록이 사라진 상황을 만듭니다(기관 장애·데이터 유실 등).
        jdbcTemplate.update("DELETE FROM mock_bank_withdrawal");

        reconciliationService.runTopUpReconciliation();

        assertThat(openMismatches()).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.INTERNAL_ONLY);
            assertThat(mismatch.referenceId()).isEqualTo(view.topUpId().toString());
            assertThat(mismatch.amountDifference()).isEqualTo(100_000);
        });
        assertThat(outboxEventTypes()).contains("ReconciliationMismatchDetected");
    }

    @Test
    @DisplayName("AMOUNT_MISMATCH: 같은 거래인데 금액이 다르다")
    void amountMismatchIsDetected() {
        topUp("recon-key-000003", 100_000);
        jdbcTemplate.update("UPDATE mock_bank_withdrawal SET amount = 90000");

        reconciliationService.runTopUpReconciliation();

        assertThat(openMismatches()).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.AMOUNT_MISMATCH);
            assertThat(mismatch.internalAmount()).isEqualTo(100_000);
            assertThat(mismatch.externalAmount()).isEqualTo(90_000);
            // 금액 차이는 자동 보정 대상이 아닙니다.
            assertThat(mismatch.type().autoResolvable()).isFalse();
        });
    }

    @Test
    @DisplayName("EXTERNAL_ONLY: 외부는 출금했는데 우리에게 기록이 없다")
    void externalOnlyIsDetected() {
        jdbcTemplate.update(
                """
                INSERT INTO mock_bank_account
                    (mock_account_id, account_number_token, balance, currency, created_at)
                VALUES (?, 'orphan-token', 500000, 'KRW', now())
                """,
                UUID.randomUUID());
        jdbcTemplate.update(
                """
                INSERT INTO mock_bank_withdrawal
                    (withdrawal_id, external_key, mock_account_id, amount, status, created_at)
                VALUES (?, 'orphan-external-key', ?, 70000, 'SUCCEEDED', now())
                """,
                UUID.randomUUID(),
                jdbcTemplate.queryForObject(
                        "SELECT mock_account_id FROM mock_bank_account WHERE account_number_token = 'orphan-token'",
                        UUID.class));

        reconciliationService.runTopUpReconciliation();

        assertThat(openMismatches()).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.EXTERNAL_ONLY);
            assertThat(mismatch.externalAmount()).isEqualTo(70_000);
            assertThat(mismatch.internalAmount()).isNull();
        });
    }

    @Test
    @DisplayName("LEDGER_MISSING: 업무는 성공인데 원장 거래가 없다")
    void ledgerMissingIsDetected() {
        // 원장 없이 성공한 충전 행만 만들어, 원장이 빠진 상태를 재현합니다.
        UUID orphanTopUpId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO top_up
                    (top_up_id, wallet_id, bank_account_id, requested_amount, completed_amount,
                     currency, status, idempotency_key, requested_at, completed_at)
                VALUES (?, ?, ?, 50000, 50000, 'KRW', 'SUCCEEDED', 'orphan-key-0001', ?, ?)
                """,
                orphanTopUpId,
                walletId.value(),
                bankAccountId.value(),
                Timestamp.from(Instant.now().minusSeconds(60)),
                Timestamp.from(Instant.now().minusSeconds(60)));
        jdbcTemplate.update(
                """
                INSERT INTO mock_bank_withdrawal
                    (withdrawal_id, external_key, mock_account_id, amount, status, created_at)
                VALUES (?, ?, ?, 50000, 'SUCCEEDED', now())
                """,
                UUID.randomUUID(),
                orphanTopUpId.toString(),
                bankAccountId.value());

        reconciliationService.runTopUpReconciliation();

        assertThat(openMismatches())
                .extracting(ReconciliationMismatch::type)
                .containsExactly(MismatchType.LEDGER_MISSING);
    }

    @Test
    @DisplayName("같은 차이가 대사를 반복해도 한 건만 열려 있다")
    void repeatedRunsDoNotDuplicateOpenMismatches() {
        topUp("recon-key-000004", 100_000);
        jdbcTemplate.update("DELETE FROM mock_bank_withdrawal");

        reconciliationService.runTopUpReconciliation();
        reconciliationService.runTopUpReconciliation();
        reconciliationService.runTopUpReconciliation();

        assertThat(openMismatches()).hasSize(1);
        assertThat(mismatchCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("조치 없이 해결하면 사유가 남고, 원인이 그대로면 다음 대사에서 다시 열린다")
    void resolvingWithoutFixingTheCauseReopensTheMismatch() {
        topUp("recon-key-000005", 100_000);
        jdbcTemplate.update("DELETE FROM mock_bank_withdrawal");
        reconciliationService.runTopUpReconciliation();
        UUID mismatchId = openMismatches().get(0).mismatchId();

        ReconciliationMismatch resolved = resolutionService.resolveWithoutAdjustment(
                mismatchId, "ops-1", "기관 지연으로 판단", false);

        assertThat(resolved.resolutionStatus()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(resolved.resolvedBy()).isEqualTo("ops-1");
        assertThat(openMismatches()).isEmpty();

        // 근본 원인이 남아 있으면 다음 대사에서 다시 올라옵니다. 문제를 덮을 수 없습니다.
        reconciliationService.runTopUpReconciliation();
        assertThat(openMismatches()).hasSize(1);
        assertThat(mismatchCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("보정은 원장을 고치지 않고 새 분개를 만든다")
    void adjustmentCreatesANewJournal() {
        topUp("recon-key-000006", 100_000);
        jdbcTemplate.update("UPDATE mock_bank_withdrawal SET amount = 90000");
        reconciliationService.runTopUpReconciliation();
        UUID mismatchId = openMismatches().get(0).mismatchId();
        long ledgerCountBefore = ledgerTransactionCount();

        ReconciliationMismatch resolved = resolutionService.resolveWithAdjustment(
                mismatchId,
                ApiAuth.OPS_OPERATOR,
                ApiAuth.OPS_APPROVER,
                "기관 확인 결과 실제 출금은 90,000원",
                AccountCode.SETTLEMENT_CLEARING,
                AccountCode.BANK_DEPOSIT,
                UUID.randomUUID(),
                null,
                10_000);

        assertThat(resolved.resolutionStatus()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(resolved.adjustmentLedgerTransactionId()).isNotNull();
        assertThat(resolved.resolvedBy())
                .contains(ApiAuth.OPS_OPERATOR)
                .contains(ApiAuth.OPS_APPROVER);
        // 기존 원장은 그대로 있고 보정 분개가 새로 추가됩니다.
        assertThat(ledgerTransactionCount()).isEqualTo(ledgerCountBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM ledger_transaction WHERE transaction_type = 'OPERATIONAL_ADJUSTMENT'",
                        Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("요청자와 승인자가 같으면 보정할 수 없다")
    void adjustmentRequiresTwoDifferentPeople() {
        topUp("recon-key-000007", 100_000);
        jdbcTemplate.update("UPDATE mock_bank_withdrawal SET amount = 90000");
        reconciliationService.runTopUpReconciliation();
        UUID mismatchId = openMismatches().get(0).mismatchId();

        assertThatThrownBy(() -> resolutionService.resolveWithAdjustment(
                        mismatchId,
                        ApiAuth.OPS_OPERATOR,
                        ApiAuth.OPS_OPERATOR,
                        "혼자 처리",
                        AccountCode.SETTLEMENT_CLEARING,
                        AccountCode.BANK_DEPOSIT,
                        UUID.randomUUID(),
                        null,
                        10_000))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("other than the requester");

        assertThat(openMismatches()).hasSize(1);
    }

    @Test
    @DisplayName("승인 권한이 없는 사람은 보정을 승인할 수 없다")
    void approverMustHaveApprovalAuthority() {
        topUp("recon-key-000010", 100_000);
        jdbcTemplate.update("UPDATE mock_bank_withdrawal SET amount = 90000");
        reconciliationService.runTopUpReconciliation();
        UUID mismatchId = openMismatches().get(0).mismatchId();

        // 읽기 권한만 있는 운영자는 승인자가 될 수 없습니다.
        assertThatThrownBy(() -> resolutionService.resolveWithAdjustment(
                        mismatchId,
                        ApiAuth.OPS_OPERATOR,
                        ApiAuth.OPS_VIEWER,
                        "권한 없는 승인",
                        AccountCode.SETTLEMENT_CLEARING,
                        AccountCode.BANK_DEPOSIT,
                        UUID.randomUUID(),
                        null,
                        10_000))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("approval authority");

        // 존재하지 않는 사람도 승인자가 될 수 없습니다.
        assertThatThrownBy(() -> resolutionService.resolveWithAdjustment(
                        mismatchId,
                        ApiAuth.OPS_OPERATOR,
                        "nobody@example.com",
                        "유령 승인",
                        AccountCode.SETTLEMENT_CLEARING,
                        AccountCode.BANK_DEPOSIT,
                        UUID.randomUUID(),
                        null,
                        10_000))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not a known operator");

        assertThat(openMismatches()).hasSize(1);
    }

    @Test
    @DisplayName("이미 해결된 불일치는 다시 해결할 수 없다")
    void resolvedMismatchCannotBeResolvedAgain() {
        topUp("recon-key-000008", 100_000);
        jdbcTemplate.update("DELETE FROM mock_bank_withdrawal");
        reconciliationService.runTopUpReconciliation();
        UUID mismatchId = openMismatches().get(0).mismatchId();
        resolutionService.resolveWithoutAdjustment(mismatchId, "ops-1", "확인 완료", true);

        assertThatThrownBy(() ->
                        resolutionService.resolveWithoutAdjustment(mismatchId, "ops-2", "다시", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already IGNORED");
    }

    @Test
    @DisplayName("미확정 상태로 오래 남은 거래도 대사에서 드러난다")
    void longPendingTopUpIsReported() {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_BEFORE_WITHDRAWAL);
        topUp("recon-key-000009", 100_000);

        reconciliationService.runTopUpReconciliation();

        assertThat(openMismatches())
                .extracting(ReconciliationMismatch::type)
                .containsExactly(MismatchType.STATUS_MISMATCH);
        assertThat(openMismatches().get(0).detail()).contains("pending");
    }

    private TopUpView topUp(String key, long amount) {
        return requestTopUp.requestTopUp(new TopUpCommand(
                memberId, walletId, bankAccountId, Money.krw(amount), IdempotencyKey.of(key)));
    }

    private List<ReconciliationMismatch> openMismatches() {
        return resolutionService.findOpen(null, 50);
    }

    private long mismatchCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation_mismatch", Long.class);
        return count == null ? 0L : count;
    }

    private long ledgerTransactionCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_transaction", Long.class);
        return count == null ? 0L : count;
    }

    private List<String> outboxEventTypes() {
        return jdbcTemplate.queryForList("SELECT event_type FROM outbox_event", String.class);
    }
}

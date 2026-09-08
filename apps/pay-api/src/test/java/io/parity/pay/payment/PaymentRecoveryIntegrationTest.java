package io.parity.pay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.api.mockpg.MockPgBehavior;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.PaymentView;
import io.parity.pay.payment.application.service.PaymentRecoveryService;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 미확정 PG 결제의 복구.
 *
 * <p>F-006을 결제에서 재현합니다. 외부는 승인했는데 응답이 유실된 상태에서 시작해, 복구가 조회로
 * 최종 상태에 수렴시키는지 봅니다. 승인을 다시 보내지 않는다는 것이 핵심입니다 — 재요청은 이중
 * 청구를 만듭니다.
 *
 * <p>근거: ADR-007, docs/09-consistency-recovery.md §7·§8
 */
class PaymentRecoveryIntegrationTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 30_000L;

    @Autowired
    private ApprovePaymentUseCase approvePayment;

    @Autowired
    private PaymentRecoveryService recoveryService;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private MockPgBehavior mockPgBehavior;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MemberId memberId;
    private WalletId walletId;
    private MerchantId merchantId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt, audit_log, password_reset_token,
                         settlement_item, settlement, order_confirmation, merchant,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_recovery, payment_cancellation, payment, top_up,
                         outbox_event, consumed_event, wallet_transaction,
                         mock_pg_approval, mock_bank_withdrawal, mock_bank_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        mockPgBehavior.reset();

        OnboardingService.RegisteredMember member =
                onboardingService.registerMember("pg-recovery@example.com", "password1234");
        memberId = member.memberId();
        walletId = WalletId.of(member.walletId());
        merchantId = MerchantId.generate();
    }

    @AfterEach
    void tearDown() {
        mockPgBehavior.reset();
    }

    @Test
    @DisplayName("F-006: 승인 후 응답이 유실된 결제는 조회로 APPROVED에 수렴한다")
    void unknownAfterApprovalConvergesToApproved() {
        mockPgBehavior.setMode(MockPgBehavior.Mode.TIMEOUT_AFTER_APPROVAL);
        PaymentId paymentId = approve("rec-key-1", "order-rec-1").paymentId();
        assertThat(statusOf(paymentId)).isEqualTo("UNKNOWN");

        // 외부는 정상으로 돌아왔지만 승인을 다시 보내지는 않습니다. 조회만 합니다.
        mockPgBehavior.setMode(MockPgBehavior.Mode.NORMAL);
        int settled = recoveryService.resolveDue();

        assertThat(settled).isEqualTo(1);
        assertThat(statusOf(paymentId)).isEqualTo("APPROVED");
        assertThat(ledgerTransactionCount()).isEqualTo(1L);
        assertThat(externalApprovalCount()).isEqualTo(1L);
        assertThat(recoveryRowCount()).isZero();
    }

    @Test
    @DisplayName("INV-004: 복구를 반복해도 원장 거래와 외부 승인은 한 번뿐이다")
    void repeatedRecoveryIsIdempotent() {
        mockPgBehavior.setMode(MockPgBehavior.Mode.TIMEOUT_AFTER_APPROVAL);
        PaymentId paymentId = approve("rec-key-2", "order-rec-2").paymentId();
        mockPgBehavior.setMode(MockPgBehavior.Mode.NORMAL);

        recoveryService.resolveDue();
        recoveryService.resolveDue();
        recoveryService.resolveNow(paymentId);

        assertThat(statusOf(paymentId)).isEqualTo("APPROVED");
        assertThat(ledgerTransactionCount()).isEqualTo(1L);
        assertThat(externalApprovalCount()).isEqualTo(1L);
        assertThat(outboxEventCount("PaymentApproved")).isEqualTo(1L);
    }

    @Test
    @DisplayName("외부에 기록이 없다는 것을 연속으로 확인해야 거절로 확정한다")
    void notFoundIsConfirmedBeforeSettlingAsFailed() {
        // 승인 전에 끊겼습니다. 외부에는 아무 기록도 없습니다.
        mockPgBehavior.setMode(MockPgBehavior.Mode.TIMEOUT_BEFORE_APPROVAL);
        PaymentId paymentId = approve("rec-key-3", "order-rec-3").paymentId();
        mockPgBehavior.setMode(MockPgBehavior.Mode.NORMAL);

        // 임계치(테스트 설정 2회)에 도달하기 전에는 확정하지 않습니다.
        recoveryService.resolveDue();
        assertThat(statusOf(paymentId)).isEqualTo("UNKNOWN");
        assertThat(notFoundCountOf(paymentId)).isEqualTo(1);

        advanceRecoverySchedule();
        recoveryService.resolveDue();

        assertThat(statusOf(paymentId)).isEqualTo("FAILED");
        assertThat(paymentRow(paymentId).get("failure_reason")).isEqualTo("EXTERNAL_RECORD_NOT_FOUND");
        assertThat(ledgerTransactionCount()).isZero();
    }

    @Test
    @DisplayName("F-009: 조회가 되지 않으면 아무것도 확정하지 않고 결국 사람에게 넘긴다")
    void unavailableStatusQueryEscalatesToManualReview() {
        mockPgBehavior.setMode(MockPgBehavior.Mode.TIMEOUT_AFTER_APPROVAL);
        PaymentId paymentId = approve("rec-key-4", "order-rec-4").paymentId();

        // 승인 결과를 모르는데 조회까지 실패합니다.
        mockPgBehavior.setStatusQueryAvailable(false);
        for (int attempt = 0; attempt < 3; attempt++) {
            recoveryService.resolveDue();
            advanceRecoverySchedule();
        }

        assertThat(statusOf(paymentId)).isEqualTo("UNKNOWN");
        assertThat(ledgerTransactionCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT requires_manual_review FROM payment_recovery WHERE payment_id = ?",
                        Boolean.class,
                        paymentId.value()))
                .isTrue();
    }

    private PaymentView approve(String idempotencyKey, String orderId) {
        return approvePayment.approve(new ApprovePaymentCommand(
                memberId,
                orderId,
                walletId,
                merchantId,
                Money.of(AMOUNT, CurrencyCode.KRW),
                PaymentMethod.EXTERNAL_PG,
                IdempotencyKey.of(idempotencyKey)));
    }

    /** 백오프가 밀리초 단위라 루프 속도에 따라 흔들립니다. 다음 점검 시각을 직접 당깁니다. */
    private void advanceRecoverySchedule() {
        jdbcTemplate.update("UPDATE payment_recovery SET next_check_at = now() - interval '1 minute'");
    }

    private String statusOf(PaymentId paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment WHERE payment_id = ?", String.class, paymentId.value());
    }

    private Map<String, Object> paymentRow(PaymentId paymentId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, failure_reason, external_reference_id FROM payment WHERE payment_id = ?",
                paymentId.value());
    }

    private int notFoundCountOf(PaymentId paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT not_found_count FROM payment_recovery WHERE payment_id = ?", Integer.class, paymentId.value());
    }

    private long recoveryRowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM payment_recovery", Long.class);
        return count == null ? 0L : count;
    }

    private long ledgerTransactionCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_transaction WHERE reference_type = 'PAYMENT'", Long.class);
        return count == null ? 0L : count;
    }

    private long outboxEventCount(String eventType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Long.class, eventType);
        return count == null ? 0L : count;
    }

    private long externalApprovalCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM mock_pg_approval WHERE status = 'APPROVED'", Long.class);
        return count == null ? 0L : count;
    }
}

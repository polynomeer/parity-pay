package io.parity.pay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.api.mockpg.MockPgBehavior;
import io.parity.pay.api.mockpg.MockPgClient;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase.CancelPaymentCommand;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase.CancellationView;
import io.parity.pay.payment.application.service.CancellationRecoveryService;
import io.parity.pay.payment.domain.CancellationStatus;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 외부 PG 환불.
 *
 * <p>여기서 지키는 것은 하나입니다. **돈은 결제한 곳으로 돌아갑니다.** 카드로 받은 돈을 페이머니로
 * 돌려주면 사용자는 카드 청구를 그대로 진 채 페이머니를 받게 되고, 우리 원장에서는 부채가 두 번
 * 생깁니다.
 *
 * <p>근거: docs/07-ledger-journal-catalog.md JE-014, ADR-007
 */
class PgRefundIntegrationTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 30_000L;
    private static final long PARTIAL = 10_000L;

    @Autowired
    private ApprovePaymentUseCase approvePayment;

    @Autowired
    private CancelPaymentUseCase cancelPayment;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private MockPgBehavior mockPgBehavior;

    @Autowired
    private CancellationRecoveryService cancellationRecovery;

    @Autowired
    private MockPgClient pgClient;

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
                         idempotency_record, cancellation_recovery, payment_recovery,
                         payment_cancellation, payment, top_up,
                         outbox_event, consumed_event, wallet_transaction,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        mockPgBehavior.reset();

        OnboardingService.RegisteredMember member =
                onboardingService.registerMember("pg-refund@example.com", "password1234");
        memberId = member.memberId();
        walletId = WalletId.of(member.walletId());
        merchantId = MerchantId.generate();
    }

    @AfterEach
    void tearDown() {
        mockPgBehavior.reset();
    }

    @Test
    @DisplayName("JE-014: 환불은 PG 미수금을 줄이고 지갑에는 아무것도 넣지 않는다")
    void refundReducesTheReceivableAndLeavesTheWalletAlone() {
        PaymentId paymentId = approve("ref-key-1", "order-ref-1");

        CancellationView view = cancel(paymentId, "cancel-key-1", AMOUNT);

        assertThat(view.status()).isEqualTo(CancellationStatus.COMPLETED);
        // 승인으로 늘었던 것이 환불로 그대로 상쇄됩니다.
        assertThat(balanceOf("1020")).isZero();
        assertThat(balanceOf("2030")).isZero();
        // 카드로 낸 돈을 페이머니로 주지 않습니다.
        assertThat(availableBalance()).isZero();
        assertThat(balanceOf("2010")).isZero();
        assertThat(refundCount("REFUNDED")).isEqualTo(1L);
    }

    @Test
    @DisplayName("부분 환불은 남은 금액만큼만 미수금을 줄인다")
    void partialRefundLeavesTheRemainder() {
        PaymentId paymentId = approve("ref-key-2", "order-ref-2");

        cancel(paymentId, "cancel-key-2", PARTIAL);

        assertThat(balanceOf("1020")).isEqualTo(AMOUNT - PARTIAL);
        assertThat(balanceOf("2030")).isEqualTo(AMOUNT - PARTIAL);
        assertThat(availableBalance()).isZero();
    }

    @Test
    @DisplayName("F-007: 환불 후 응답이 유실되면 UNKNOWN으로 보존하고 예약을 풀지 않는다")
    void lostResponseAfterRefundIsPreservedAsUnknown() {
        PaymentId paymentId = approve("ref-key-3", "order-ref-3");
        mockPgBehavior.setRefundMode(MockPgBehavior.Mode.TIMEOUT_AFTER_APPROVAL);

        CancellationView view = cancel(paymentId, "cancel-key-3", AMOUNT);

        assertThat(view.status()).isEqualTo(CancellationStatus.UNKNOWN);
        // 확정 전까지 원장에는 취소 분개가 없습니다.
        assertThat(cancellationLedgerCount()).isZero();
        // 예약은 풀지 않습니다. 풀면 같은 금액을 다시 환불할 수 있게 됩니다.
        assertThat(processingCancellationAmount(paymentId)).isEqualTo(AMOUNT);
        // 외부에는 환불이 이미 나갔습니다.
        assertThat(refundCount("REFUNDED")).isEqualTo(1L);
    }

    @Test
    @DisplayName("외부가 환불을 거절하면 예약을 풀어 다시 취소할 수 있게 한다")
    void declinedRefundReleasesTheReservation() {
        PaymentId paymentId = approve("ref-key-4", "order-ref-4");
        mockPgBehavior.setRefundMode(MockPgBehavior.Mode.EXPLICIT_DECLINE);

        CancellationView view = cancel(paymentId, "cancel-key-4", AMOUNT);

        assertThat(view.status()).isEqualTo(CancellationStatus.FAILED);
        assertThat(processingCancellationAmount(paymentId)).isZero();
        assertThat(cancellationLedgerCount()).isZero();
        // 승인의 효과는 그대로 남아 있습니다. 환불이 안 됐으므로 돈은 아직 우리 쪽에 있습니다.
        assertThat(balanceOf("1020")).isEqualTo(AMOUNT);
    }

    @Test
    @DisplayName("F-007: 미확정 환불은 조회로 COMPLETED에 수렴하고, 환불을 다시 보내지 않는다")
    void unknownRefundConvergesToCompleted() {
        PaymentId paymentId = approve("ref-key-5", "order-ref-5");
        mockPgBehavior.setRefundMode(MockPgBehavior.Mode.TIMEOUT_AFTER_APPROVAL);
        cancel(paymentId, "cancel-key-5", AMOUNT);

        mockPgBehavior.setRefundMode(MockPgBehavior.Mode.NORMAL);
        int settled = cancellationRecovery.resolveDue();

        assertThat(settled).isEqualTo(1);
        assertThat(cancellationStatus()).isEqualTo("COMPLETED");
        assertThat(cancellationLedgerCount()).isEqualTo(1L);
        // 환불은 한 번만 나갔습니다. 복구가 조회만 했기 때문입니다.
        assertThat(refundCount("REFUNDED")).isEqualTo(1L);
        assertThat(balanceOf("1020")).isZero();
        assertThat(availableBalance()).isZero();
    }

    @Test
    @DisplayName("복구를 반복해도 환불과 취소 분개는 한 번뿐이다")
    void repeatedRefundRecoveryIsIdempotent() {
        PaymentId paymentId = approve("ref-key-6", "order-ref-6");
        mockPgBehavior.setRefundMode(MockPgBehavior.Mode.TIMEOUT_AFTER_APPROVAL);
        cancel(paymentId, "cancel-key-6", AMOUNT);
        mockPgBehavior.setRefundMode(MockPgBehavior.Mode.NORMAL);

        cancellationRecovery.resolveDue();
        cancellationRecovery.resolveDue();

        assertThat(cancellationStatus()).isEqualTo("COMPLETED");
        assertThat(cancellationLedgerCount()).isEqualTo(1L);
        assertThat(refundCount("REFUNDED")).isEqualTo(1L);
    }

    @Test
    @DisplayName("환불 기록이 외부에 없다는 것이 연속 확인되면 예약을 풀고 실패로 확정한다")
    void missingRefundRecordReleasesTheReservation() {
        PaymentId paymentId = approve("ref-key-7", "order-ref-7");
        mockPgBehavior.setRefundMode(MockPgBehavior.Mode.TIMEOUT_BEFORE_APPROVAL);
        cancel(paymentId, "cancel-key-7", AMOUNT);
        mockPgBehavior.setRefundMode(MockPgBehavior.Mode.NORMAL);

        cancellationRecovery.resolveDue();
        assertThat(cancellationStatus()).isEqualTo("UNKNOWN");
        assertThat(processingCancellationAmount(paymentId)).isEqualTo(AMOUNT);

        jdbcTemplate.update("UPDATE cancellation_recovery SET next_check_at = now() - interval '1 minute'");
        cancellationRecovery.resolveDue();

        assertThat(cancellationStatus()).isEqualTo("FAILED");
        assertThat(processingCancellationAmount(paymentId)).isZero();
        assertThat(cancellationLedgerCount()).isZero();
    }

    private String cancellationStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_cancellation ORDER BY requested_at DESC LIMIT 1", String.class);
    }

    private PaymentId approve(String idempotencyKey, String orderId) {
        return approvePayment
                .approve(new ApprovePaymentCommand(
                        memberId,
                        orderId,
                        walletId,
                        merchantId,
                        Money.of(AMOUNT, CurrencyCode.KRW),
                        PaymentMethod.EXTERNAL_PG,
                        IdempotencyKey.of(idempotencyKey)))
                .paymentId();
    }

    private CancellationView cancel(PaymentId paymentId, String idempotencyKey, long amount) {
        return cancelPayment.cancel(new CancelPaymentCommand(
                memberId,
                paymentId,
                Money.of(amount, CurrencyCode.KRW),
                "CUSTOMER_REQUEST",
                IdempotencyKey.of(idempotencyKey)));
    }

    private long balanceOf(String accountCode) {
        Long sum = jdbcTemplate.queryForObject(
                """
                SELECT coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END), 0)::bigint
                  FROM ledger_entry e
                  JOIN ledger_account a ON a.account_id = e.account_id
                 WHERE a.account_code = ?
                """,
                Long.class,
                accountCode);
        return Math.abs(sum == null ? 0L : sum);
    }

    private long availableBalance() {
        return jdbcTemplate.queryForObject(
                "SELECT available_amount FROM wallet_balance WHERE wallet_id = ?", Long.class, walletId.value());
    }

    private long processingCancellationAmount(PaymentId paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT processing_cancellation_amount FROM payment WHERE payment_id = ?",
                Long.class,
                paymentId.value());
    }

    private long cancellationLedgerCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_transaction WHERE reference_type = 'PAYMENT_CANCELLATION'", Long.class);
        return count == null ? 0L : count;
    }

    private long refundCount(String status) {
        return pgClient.count("refunds", status);
    }
}

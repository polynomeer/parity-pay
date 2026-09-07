package io.parity.pay.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.api.operations.TransactionTimelineService;
import io.parity.pay.api.outbox.OutboxPublisher;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.PaymentView;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase.CancelPaymentCommand;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpCommand;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpView;
import java.time.Duration;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 거래 타임라인. 근거: FR-012, DoD-07
 *
 * <p>운영자가 식별자 하나로 사실을 재구성할 수 있는지 확인합니다.
 */
class TransactionTimelineTest extends AbstractIntegrationTest {

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private RequestTopUpUseCase requestTopUp;

    @Autowired
    private ApprovePaymentUseCase approvePayment;

    @Autowired
    private CancelPaymentUseCase cancelPayment;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private TransactionTimelineService timelineService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MemberId memberId;
    private WalletId walletId;
    private BankAccountId bankAccountId;
    private MerchantId merchantId;

    @BeforeEach
    void setUp() {
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

        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember("timeline@example.com", "password1234");
        memberId = registered.memberId();
        walletId = WalletId.of(registered.walletId());
        merchantId = MerchantId.generate();
        bankAccountId = onboardingService.linkBankAccount(
                memberId, "004", "110-2222-3333", Money.krw(1_000_000));
    }

    @Test
    @DisplayName("DoD-07: 결제 ID 하나로 업무·원장·이벤트를 한 번에 본다")
    void timelineByPaymentId() {
        requestTopUp.requestTopUp(new TopUpCommand(
                memberId,
                walletId,
                bankAccountId,
                Money.krw(100_000),
                IdempotencyKey.of("timeline-topup-001")));
        PaymentView payment = approvePayment.approve(new ApprovePaymentCommand(
                memberId,
                "order-timeline-1",
                walletId,
                merchantId,
                Money.krw(30_000),
                PaymentMethod.PAY_MONEY,
                IdempotencyKey.of("timeline-payment-001")));
        cancelPayment.cancel(new CancelPaymentCommand(
                memberId,
                payment.paymentId(),
                Money.krw(10_000),
                "PARTIAL_RETURN",
                IdempotencyKey.of("timeline-cancel-001")));
        outboxPublisher.publishBatch();

        TransactionTimelineService.Timeline timeline =
                timelineService.of(payment.paymentId().toString());

        assertThat(timeline.countsByKind())
                .containsKeys("PAYMENT", "PAYMENT_CANCELLATION", "LEDGER", "EVENT");
        // 결제 승인 분개와 취소 분개가 모두 잡힙니다.
        assertThat(timeline.countsByKind().get("LEDGER")).isEqualTo(2L);
        // 시간순으로 정렬되어 있어야 운영자가 순서를 읽을 수 있습니다.
        assertThat(timeline.entries())
                .isSortedAccordingTo(
                        Comparator.comparing(TransactionTimelineService.TimelineEntry::occurredAt));
    }

    @Test
    @DisplayName("주문 ID로도 같은 거래를 찾을 수 있다")
    void timelineByOrderId() {
        requestTopUp.requestTopUp(new TopUpCommand(
                memberId,
                walletId,
                bankAccountId,
                Money.krw(100_000),
                IdempotencyKey.of("timeline-topup-002")));
        approvePayment.approve(new ApprovePaymentCommand(
                memberId,
                "order-timeline-2",
                walletId,
                merchantId,
                Money.krw(20_000),
                PaymentMethod.PAY_MONEY,
                IdempotencyKey.of("timeline-payment-002")));

        TransactionTimelineService.Timeline timeline = timelineService.of("order-timeline-2");

        assertThat(timeline.countsByKind()).containsKey("PAYMENT");
        assertThat(timeline.entries())
                .anySatisfy(entry -> assertThat(entry.detail()).contains("order-timeline-2"));
    }

    @Test
    @DisplayName("충전 ID로 조회하면 외부기관 기록까지 함께 보인다")
    void timelineIncludesExternalRecords() {
        TopUpView topUp = requestTopUp.requestTopUp(new TopUpCommand(
                memberId,
                walletId,
                bankAccountId,
                Money.krw(100_000),
                IdempotencyKey.of("timeline-topup-003")));
        outboxPublisher.publishBatch();

        TransactionTimelineService.Timeline timeline =
                timelineService.of(topUp.topUpId().toString());

        assertThat(timeline.countsByKind())
                .containsKeys("TOP_UP", "LEDGER", "EXTERNAL_WITHDRAWAL", "EVENT");
    }

    @Test
    @DisplayName("존재하지 않는 식별자는 빈 타임라인을 돌려준다")
    void unknownReferenceReturnsEmptyTimeline() {
        TransactionTimelineService.Timeline timeline =
                timelineService.of("00000000-0000-0000-0000-000000000000");

        assertThat(timeline.entries()).isEmpty();
    }

    @Test
    @DisplayName("불변조건 지표는 정상 상태에서 모두 0이다")
    void invariantMetricsAreZeroWhenHealthy() {
        requestTopUp.requestTopUp(new TopUpCommand(
                memberId,
                walletId,
                bankAccountId,
                Money.krw(100_000),
                IdempotencyKey.of("timeline-topup-004")));
        approvePayment.approve(new ApprovePaymentCommand(
                memberId,
                "order-timeline-4",
                walletId,
                merchantId,
                Money.krw(30_000),
                PaymentMethod.PAY_MONEY,
                IdempotencyKey.of("timeline-payment-004")));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(unbalancedLedgerTransactions()).isZero();
                    assertThat(negativeBalances()).isZero();
                    assertThat(snapshotDrift()).isZero();
                });
    }

    private long unbalancedLedgerTransactions() {
        return count(
                """
                SELECT count(*) FROM (
                    SELECT e.transaction_id FROM ledger_entry e
                     GROUP BY e.transaction_id
                    HAVING coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END), 0)
                         <> coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END), 0)
                ) unbalanced
                """);
    }

    private long negativeBalances() {
        return count("SELECT count(*) FROM wallet_balance WHERE available_amount < 0");
    }

    private long snapshotDrift() {
        return count(
                """
                SELECT count(*)
                  FROM wallet_balance wb
                  JOIN ledger_account la
                    ON la.owner_id = wb.wallet_id AND la.account_code = '2010'
                 WHERE wb.available_amount + wb.pending_amount <> (
                       SELECT coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount
                                                ELSE -e.amount END), 0)
                         FROM ledger_entry e WHERE e.account_id = la.account_id)
                """);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}

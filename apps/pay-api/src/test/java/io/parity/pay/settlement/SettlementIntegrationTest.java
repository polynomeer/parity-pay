package io.parity.pay.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.parity.pay.api.mockbank.MockBankBehavior;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.api.outbox.OutboxPublisher;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.PaymentView;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase.CancelPaymentCommand;
import io.parity.pay.payment.application.port.in.ConfirmOrderUseCase;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.settlement.application.port.in.SettlementUseCases.SettlementView;
import io.parity.pay.settlement.application.service.SettlementPayoutService;
import io.parity.pay.settlement.application.service.SettlementRecoveryService;
import io.parity.pay.settlement.adapter.in.messaging.SettlementItemConsumer;
import io.parity.pay.settlement.application.service.SettlementService;
import io.parity.pay.settlement.domain.SettlementItem;
import io.parity.pay.settlement.domain.SettlementStatus;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.id.BankAccountId;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase;
import io.parity.pay.wallet.application.port.in.RequestTopUpUseCase.TopUpCommand;
import io.parity.pay.wallet.application.port.in.WalletQuery;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 정산 종단 시나리오.
 *
 * <p>FR-016(정산 계산·지급), INV-008(항목 합 = 순액), INV-009(지급 완료는 외부 참조 보유),
 * F-010(지급 응답 유실 후 수렴), JE-007·JE-008·JE-009를 다룹니다.
 */
class SettlementIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2020, 1, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2099, 12, 31);

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private RequestTopUpUseCase requestTopUp;

    @Autowired
    private ApprovePaymentUseCase approvePayment;

    @Autowired
    private CancelPaymentUseCase cancelPayment;

    @Autowired
    private ConfirmOrderUseCase confirmOrder;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private SettlementPayoutService payoutService;

    @Autowired
    private SettlementRecoveryService recoveryService;

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
    private MerchantId merchantId;

    @BeforeEach
    void setUp() {
        mockBankBehavior.reset();
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
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
                onboardingService.registerMember("settle@example.com", "password1234");
        memberId = registered.memberId();
        walletId = WalletId.of(registered.walletId());
        merchantId = MerchantId.generate();

        BankAccountId bankAccountId = onboardingService.linkBankAccount(
                memberId, "004", "110-3333-2222", Money.krw(1_000_000));
        requestTopUp.requestTopUp(new TopUpCommand(
                memberId,
                walletId,
                bankAccountId,
                Money.krw(200_000),
                IdempotencyKey.of("settlement-topup-001")));
    }

    @Test
    @DisplayName("구매확정된 결제만 정산 대상이 된다")
    void onlyConfirmedPaymentsBecomeSettlementItems() {
        PaymentView paid = pay("order-s1", "settle-payment-0001", 30_000);
        deliverEvents();

        // 아직 구매확정 전이므로 정산 항목이 없습니다.
        assertThat(settlementItemCount()).isZero();

        confirmOrder.confirm(memberId, paid.paymentId());
        deliverEvents();

        // 판매 항목과 수수료 항목이 함께 생깁니다.
        assertThat(itemAmounts()).containsExactlyInAnyOrder(30_000L, -3_000L);
    }

    @Test
    @DisplayName("같은 결제를 여러 번 확정해도 정산 항목은 한 번만 생긴다")
    void repeatedConfirmationCreatesItemsOnce() {
        PaymentView paid = pay("order-s2", "settle-payment-0002", 30_000);

        confirmOrder.confirm(memberId, paid.paymentId());
        confirmOrder.confirm(memberId, paid.paymentId());
        confirmOrder.confirm(memberId, paid.paymentId());
        deliverEvents();

        assertThat(settlementItemCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("INV-008: 정산 순액은 항목 금액의 합과 같고 수수료가 원장에 인식된다")
    void calculateProducesBalancedSettlement() {
        confirmOrder.confirm(memberId, pay("order-s3", "settle-payment-0003", 30_000).paymentId());
        confirmOrder.confirm(memberId, pay("order-s4", "settle-payment-0004", 20_000).paymentId());
        deliverEvents();

        SettlementView settlement =
                settlementService.calculate(merchantId, PERIOD_START, PERIOD_END);

        assertThat(settlement.grossAmount()).isEqualTo(Money.krw(50_000));
        assertThat(settlement.feeAmount()).isEqualTo(Money.krw(5_000));
        assertThat(settlement.netAmount()).isEqualTo(Money.krw(45_000));

        List<SettlementItem> items = settlementService.itemsOf(settlement.settlementId());
        assertThat(items.stream().mapToLong(SettlementItem::amount).sum())
                .isEqualTo(settlement.netAmount().amount());

        // JE-007: 수수료만큼 판매자 지급예정금이 줄고 플랫폼 수익이 잡힙니다.
        assertThat(merchantPayableBalance()).isEqualTo(45_000L);
        assertThat(accountBalance("4010")).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("이미 정산된 항목은 다음 회차에 다시 들어가지 않는다")
    void settledItemsAreNotSettledAgain() {
        confirmOrder.confirm(memberId, pay("order-s5", "settle-payment-0005", 30_000).paymentId());
        deliverEvents();
        settlementService.calculate(merchantId, PERIOD_START, PERIOD_END);

        assertThatThrownBy(() -> settlementService.calculate(merchantId, PERIOD_START, PERIOD_END))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no eligible settlement items");
    }

    @Test
    @DisplayName("JE-008: 지급이 완료되면 지급예정금이 비고 은행 자산이 줄어든다")
    void payoutClearsMerchantPayable() {
        confirmOrder.confirm(memberId, pay("order-s6", "settle-payment-0006", 30_000).paymentId());
        deliverEvents();
        SettlementView settlement = settlementService.calculate(merchantId, PERIOD_START, PERIOD_END);

        SettlementView paid = payoutService.pay(settlement.settlementId());

        assertThat(paid.status()).isEqualTo(SettlementStatus.PAID);
        assertThat(paid.externalReferenceId()).isNotNull();
        assertThat(merchantPayableBalance()).isZero();
        assertThat(mockBankPayoutCount()).isEqualTo(1L);
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();
    }

    @Test
    @DisplayName("F-010: 지급 응답이 유실되면 UNKNOWN으로 보존하고 조회로 확정한다")
    void lostPayoutResponseConvergesToPaid() {
        confirmOrder.confirm(memberId, pay("order-s7", "settle-payment-0007", 30_000).paymentId());
        deliverEvents();
        SettlementView settlement = settlementService.calculate(merchantId, PERIOD_START, PERIOD_END);

        mockBankBehavior.setPayoutMode(MockBankBehavior.Mode.TIMEOUT_AFTER_WITHDRAWAL);
        SettlementView unknown = payoutService.pay(settlement.settlementId());

        assertThat(unknown.status()).isEqualTo(SettlementStatus.UNKNOWN);
        // 아직 확정되지 않았으므로 원장에는 지급 분개가 없습니다.
        assertThat(merchantPayableBalance()).isEqualTo(27_000L);

        mockBankBehavior.reset();
        assertThat(recoveryService.resolveDue()).isEqualTo(1);

        assertThat(settlementStatus(settlement)).isEqualTo("PAID");
        assertThat(merchantPayableBalance()).isZero();
        // 외부에서도 지급은 한 번만 일어났습니다.
        assertThat(mockBankPayoutCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("지급이 명시적으로 실패하면 FAILED가 되고 다시 시도할 수 있다")
    void failedPayoutCanBeRetried() {
        confirmOrder.confirm(memberId, pay("order-s8", "settle-payment-0008", 30_000).paymentId());
        deliverEvents();
        SettlementView settlement = settlementService.calculate(merchantId, PERIOD_START, PERIOD_END);

        mockBankBehavior.setPayoutMode(MockBankBehavior.Mode.EXPLICIT_FAILURE);
        assertThat(payoutService.pay(settlement.settlementId()).status())
                .isEqualTo(SettlementStatus.FAILED);
        assertThat(merchantPayableBalance()).isEqualTo(27_000L);

        mockBankBehavior.reset();
        assertThat(payoutService.pay(settlement.settlementId()).status())
                .isEqualTo(SettlementStatus.PAID);
        assertThat(merchantPayableBalance()).isZero();
    }

    @Test
    @DisplayName("정산 전 취소는 회차에서 차감되고 수수료도 환급된다")
    void cancellationBeforeSettlementReducesTheAmount() {
        PaymentView payment = pay("order-s9", "settle-payment-0009", 30_000);
        confirmOrder.confirm(memberId, payment.paymentId());
        cancel(payment.paymentId(), "settle-cancel-0001", 10_000);
        deliverEvents();

        SettlementView settlement = settlementService.calculate(merchantId, PERIOD_START, PERIOD_END);

        // 30,000 판매 - 3,000 수수료 - 10,000 취소 + 1,000 수수료 환급
        assertThat(settlement.grossAmount()).isEqualTo(Money.krw(30_000));
        assertThat(settlement.cancellationAmount()).isEqualTo(Money.krw(10_000));
        assertThat(settlement.adjustmentAmount()).isEqualTo(1_000L);
        assertThat(settlement.netAmount()).isEqualTo(Money.krw(18_000));
    }

    @Test
    @DisplayName("JE-009: 지급이 끝난 뒤의 취소는 판매자 미수금으로 남고 다음 회차 조정 항목이 된다")
    void cancellationAfterPayoutBecomesReceivableAndAdjustment() {
        PaymentView payment = pay("order-s10", "settle-payment-0010", 30_000);
        confirmOrder.confirm(memberId, payment.paymentId());
        deliverEvents();
        SettlementView settlement = settlementService.calculate(merchantId, PERIOD_START, PERIOD_END);
        payoutService.pay(settlement.settlementId());
        assertThat(merchantPayableBalance()).isZero();

        // 이미 지급이 끝난 뒤 취소가 들어옵니다.
        cancel(payment.paymentId(), "settle-cancel-0002", 10_000);
        deliverEvents();

        // 지급예정금이 없으므로 판매자 미수금(1030)으로 기록됩니다. 원장 잔액은 음수가 되지 않습니다.
        assertThat(merchantPayableBalance()).isZero();
        assertThat(accountBalance("1030")).isEqualTo(10_000L);
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();

        // 이미 지급된 회차는 고치지 않고 다음 회차에 반영할 조정 항목이 생깁니다.
        List<String> pendingTypes = jdbcTemplate.queryForList(
                "SELECT item_type FROM settlement_item WHERE status = 'ELIGIBLE'", String.class);
        assertThat(pendingTypes).containsExactlyInAnyOrder("ADJUSTMENT", "ADJUSTMENT");
        assertThat(settlementStatus(settlement)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("보류된 정산은 지급할 수 없고, 해제하면 다시 지급할 수 있다")
    void heldSettlementCannotBePaid() {
        confirmOrder.confirm(memberId, pay("order-s11", "settle-payment-0011", 30_000).paymentId());
        deliverEvents();
        SettlementView settlement = settlementService.calculate(merchantId, PERIOD_START, PERIOD_END);

        settlementService.hold(settlement.settlementId(), "분쟁 조사 중");

        assertThatThrownBy(() -> payoutService.pay(settlement.settlementId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HELD");

        settlementService.release(settlement.settlementId());
        assertThat(payoutService.pay(settlement.settlementId()).status())
                .isEqualTo(SettlementStatus.PAID);
    }

    @Test
    @DisplayName("정산 이벤트도 Outbox로 발행된다")
    void settlementEventsAreEmitted() {
        confirmOrder.confirm(memberId, pay("order-s12", "settle-payment-0012", 30_000).paymentId());
        deliverEvents();
        SettlementView settlement = settlementService.calculate(merchantId, PERIOD_START, PERIOD_END);
        payoutService.pay(settlement.settlementId());

        assertThat(outboxEventTypes()).contains("SettlementCreated", "SettlementPaid");
    }

    /**
     * Outbox에 쌓인 이벤트를 발행하고 정산 소비자가 전부 처리할 때까지 기다립니다.
     *
     * <p>소비자는 관심 없는 이벤트도 소비 이력에 남기므로, 발행 건수와 소비 건수가 같아지면 이번
     * 배치가 모두 처리된 것입니다.
     */
    private void deliverEvents() {
        outboxPublisher.publishBatch();
        await().atMost(Duration.ofSeconds(20))
                .until(() -> outboxPendingCount() == 0L
                        && publishedCount() == consumedCount(SettlementItemConsumer.CONSUMER_NAME));
    }

    private long publishedCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status = 'PUBLISHED'", Long.class);
        return count == null ? 0L : count;
    }

    private long consumedCount(String consumerName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM consumed_event WHERE consumer_name = ?", Long.class, consumerName);
        return count == null ? 0L : count;
    }

    private PaymentView pay(String orderId, String key, long amount) {
        return approvePayment.approve(new ApprovePaymentCommand(
                memberId,
                orderId,
                walletId,
                merchantId,
                Money.krw(amount),
                PaymentMethod.PAY_MONEY,
                IdempotencyKey.of(key)));
    }

    private void cancel(PaymentId paymentId, String key, long amount) {
        cancelPayment.cancel(new CancelPaymentCommand(
                memberId, paymentId, Money.krw(amount), "TEST", IdempotencyKey.of(key)));
    }

    private long settlementItemCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM settlement_item", Long.class);
        return count == null ? 0L : count;
    }

    private List<Long> itemAmounts() {
        return jdbcTemplate.queryForList("SELECT amount FROM settlement_item", Long.class);
    }

    private long outboxPendingCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'", Long.class);
        return count == null ? 0L : count;
    }

    private String settlementStatus(SettlementView settlement) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM settlement WHERE settlement_id = ?",
                String.class,
                settlement.settlementId().value());
    }

    private long merchantPayableBalance() {
        return accountBalance("2030");
    }

    /** 계정의 정상 잔액 방향 기준 잔액입니다. */
    private long accountBalance(String accountCode) {
        Long balance = jdbcTemplate.queryForObject(
                """
                SELECT coalesce(sum(
                    CASE
                        WHEN a.account_code IN ('1010', '1020', '1030', '5010')
                            THEN CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END
                        ELSE CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END
                    END), 0)
                  FROM ledger_entry e
                  JOIN ledger_account a ON a.account_id = e.account_id
                 WHERE a.account_code = ?
                """,
                Long.class,
                accountCode);
        return balance == null ? 0L : balance;
    }

    private long mockBankPayoutCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM mock_bank_payout", Long.class);
        return count == null ? 0L : count;
    }

    private List<String> outboxEventTypes() {
        return jdbcTemplate.queryForList("SELECT event_type FROM outbox_event", String.class);
    }
}

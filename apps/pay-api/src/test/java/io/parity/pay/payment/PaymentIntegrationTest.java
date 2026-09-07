package io.parity.pay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.PaymentView;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase;
import io.parity.pay.payment.application.port.in.CancelPaymentUseCase.CancelPaymentCommand;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.payment.domain.PaymentStatus;
import io.parity.pay.shared.error.BusinessException;
import io.parity.pay.shared.error.ErrorCode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결제·취소 종단 시나리오.
 *
 * <p>AC-002(중복 결제), AC-003(잔액 경합), AC-005(부분 취소), T-007(동시 부분 취소),
 * INV-001·003·005·010을 다룹니다.
 */
class PaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private RequestTopUpUseCase requestTopUp;

    @Autowired
    private ApprovePaymentUseCase approvePayment;

    @Autowired
    private CancelPaymentUseCase cancelPayment;

    @Autowired
    private WalletQuery walletQuery;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MemberId memberId;
    private WalletId walletId;
    private MerchantId merchantId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment, top_up,
                         mock_bank_withdrawal, mock_bank_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);

        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember("payer@example.com", "password1234");
        memberId = registered.memberId();
        walletId = WalletId.of(registered.walletId());
        merchantId = MerchantId.generate();

        BankAccountId bankAccountId =
                onboardingService.linkBankAccount(memberId, "004", "110-1234-5678", Money.krw(1_000_000));
        requestTopUp.requestTopUp(new TopUpCommand(
                memberId, walletId, bankAccountId, Money.krw(50_000), IdempotencyKey.of("payment-test-topup")));
    }

    @Test
    @DisplayName("결제가 승인되면 잔액이 줄고 판매자 지급예정금이 늘어난다")
    void approvePaymentMovesLiability() {
        PaymentView view = pay("order-1", "payment-key-00001", 30_000);

        assertThat(view.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(availableBalance()).isEqualTo(20_000L);
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();
        // JE-003: 사용자 부채 감소, 판매자 지급예정금 증가. 시스템 전체 부채 총액은 그대로입니다.
        assertThat(merchantPayableBalance()).isEqualTo(30_000L);
        assertThat(ledgerTransactionCount()).isEqualTo(2L); // 충전 + 결제
    }

    @Test
    @DisplayName("AC-002: 같은 멱등 키로 동시에 30건이 도착해도 승인은 한 건이다")
    void concurrentRequestsWithSameKeyApproveOnce() throws Exception {
        String key = "payment-key-00002";
        int concurrency = 30;
        CountDownLatch start = new CountDownLatch(1);

        List<PaymentView> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
            List<Callable<PaymentView>> tasks = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                tasks.add(() -> {
                    start.await();
                    return pay("order-2", key, 30_000);
                });
            }
            List<Future<PaymentView>> futures = new ArrayList<>();
            for (Callable<PaymentView> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();
            for (Future<PaymentView> future : futures) {
                results.add(future.get());
            }
        }

        assertThat(results)
                .extracting(PaymentView::paymentId)
                .containsOnly(results.get(0).paymentId());
        assertThat(paymentCount()).isEqualTo(1L);
        assertThat(availableBalance()).isEqualTo(20_000L);
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();
    }

    @Test
    @DisplayName("AC-003: 잔액 50,000에 40,000 결제 두 건이 동시에 오면 한 건만 승인된다")
    void concurrentPaymentsCannotOverdrawBalance() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        pay("order-concurrent-" + index, "payment-key-00003" + index, 40_000);
                        approved.incrementAndGet();
                    } catch (BusinessException e) {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
                        insufficient.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(approved.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(1);
        assertThat(availableBalance()).isEqualTo(10_000L);
        assertThat(paymentCount()).isEqualTo(1L);
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();
    }

    @Test
    @DisplayName("잔액보다 큰 결제는 INSUFFICIENT_BALANCE로 확정 실패하고 아무 흔적을 남기지 않는다")
    void insufficientBalanceIsRejected() {
        assertThatThrownBy(() -> pay("order-3", "payment-key-00004", 50_001))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

        assertThat(availableBalance()).isEqualTo(50_000L);
        assertThat(paymentCount()).isZero();
    }

    @Test
    @DisplayName("FR-007: 전액 취소하면 잔액이 복구되고 결제는 CANCELED가 된다")
    void fullCancellationRestoresBalance() {
        PaymentView payment = pay("order-4", "payment-key-00005", 30_000);

        cancel(payment.paymentId(), "cancel-key-00001", 30_000);

        assertThat(availableBalance()).isEqualTo(50_000L);
        assertThat(paymentStatus(payment.paymentId())).isEqualTo("CANCELED");
        assertThat(merchantPayableBalance()).isZero();
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();
        // 원장은 수정되지 않고 상쇄 분개가 새로 생깁니다. 충전 + 결제 + 취소 = 3건
        assertThat(ledgerTransactionCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("AC-005: 부분 취소 후 결제는 PARTIALLY_CANCELED이고 남은 금액을 다시 취소할 수 있다")
    void partialCancellation() {
        PaymentView payment = pay("order-5", "payment-key-00006", 30_000);

        cancel(payment.paymentId(), "cancel-key-00002", 10_000);

        assertThat(paymentStatus(payment.paymentId())).isEqualTo("PARTIALLY_CANCELED");
        assertThat(availableBalance()).isEqualTo(30_000L);
        assertThat(merchantPayableBalance()).isEqualTo(20_000L);

        cancel(payment.paymentId(), "cancel-key-00003", 20_000);

        assertThat(paymentStatus(payment.paymentId())).isEqualTo("CANCELED");
        assertThat(availableBalance()).isEqualTo(50_000L);
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();
    }

    @Test
    @DisplayName("INV-005: 취소 가능액을 넘는 요청은 거절된다")
    void cancellationBeyondApprovedAmountIsRejected() {
        PaymentView payment = pay("order-6", "payment-key-00007", 30_000);
        cancel(payment.paymentId(), "cancel-key-00004", 20_000);

        assertThatThrownBy(() -> cancel(payment.paymentId(), "cancel-key-00005", 10_001))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                        .isEqualTo(ErrorCode.CANCELLATION_AMOUNT_EXCEEDED));

        assertThat(availableBalance()).isEqualTo(40_000L);
    }

    @Test
    @DisplayName("T-007: 동시 부분 취소 6건이 승인액을 초과하지 않는다")
    void concurrentPartialCancellationsRespectTheCap() throws Exception {
        PaymentView payment = pay("order-7", "payment-key-00008", 30_000);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        cancel(payment.paymentId(), "cancel-key-0001" + index, 10_000);
                        completed.incrementAndGet();
                    } catch (BusinessException e) {
                        // 남은 취소 가능액이 부족하면 CANCELLATION_AMOUNT_EXCEEDED,
                        // 이미 전액 취소되어 결제가 CANCELED가 된 뒤면 INVALID_STATE_TRANSITION입니다.
                        // 둘 다 "더 취소할 수 없다"는 같은 결론이며 사용자가 할 수 있는 행동만 다릅니다.
                        assertThat(e.errorCode())
                                .isIn(ErrorCode.CANCELLATION_AMOUNT_EXCEEDED, ErrorCode.INVALID_STATE_TRANSITION);
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        // 30,000원짜리 결제에 10,000원 취소는 최대 3건만 성공해야 합니다.
        assertThat(completed.get()).isEqualTo(3);
        assertThat(rejected.get()).isEqualTo(3);
        assertThat(availableBalance()).isEqualTo(50_000L);
        assertThat(paymentStatus(payment.paymentId())).isEqualTo("CANCELED");
        assertThat(walletQuery.verifyAgainstLedger(walletId).matches()).isTrue();
    }

    @Test
    @DisplayName("같은 주문에 두 번 결제할 수 없다")
    void orderCannotBePaidTwice() {
        pay("order-8", "payment-key-00009", 10_000);

        assertThatThrownBy(() -> pay("order-8", "payment-key-00010", 10_000))
                .isInstanceOf(BusinessException.class)
                .satisfies(e ->
                        assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        assertThat(availableBalance()).isEqualTo(40_000L);
    }

    @Test
    @DisplayName("같은 취소 키를 반복해도 환불은 한 번만 일어난다")
    void repeatedCancellationKeyRefundsOnce() {
        PaymentView payment = pay("order-9", "payment-key-00011", 30_000);

        for (int i = 0; i < 5; i++) {
            cancel(payment.paymentId(), "cancel-key-00020", 10_000);
        }

        assertThat(availableBalance()).isEqualTo(30_000L);
        assertThat(cancellationCount()).isEqualTo(1L);
    }

    private PaymentView pay(String orderId, String idempotencyKey, long amount) {
        return approvePayment.approve(new ApprovePaymentCommand(
                memberId,
                orderId,
                walletId,
                merchantId,
                Money.krw(amount),
                PaymentMethod.PAY_MONEY,
                IdempotencyKey.of(idempotencyKey)));
    }

    private void cancel(PaymentId paymentId, String idempotencyKey, long amount) {
        cancelPayment.cancel(new CancelPaymentCommand(
                memberId, paymentId, Money.krw(amount), "TEST", IdempotencyKey.of(idempotencyKey)));
    }

    private long availableBalance() {
        Long balance = jdbcTemplate.queryForObject(
                "SELECT available_amount FROM wallet_balance WHERE wallet_id = ?", Long.class, walletId.value());
        return balance == null ? 0L : balance;
    }

    private long merchantPayableBalance() {
        Long balance = jdbcTemplate.queryForObject(
                """
                SELECT coalesce(sum(case when e.direction = 'CREDIT' then e.amount else -e.amount end), 0)
                  FROM ledger_entry e
                  JOIN ledger_account a ON a.account_id = e.account_id
                 WHERE a.account_code = '2030'
                """,
                Long.class);
        return balance == null ? 0L : balance;
    }

    private String paymentStatus(PaymentId paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment WHERE payment_id = ?", String.class, paymentId.value());
    }

    private Long paymentCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM payment", Long.class);
    }

    private Long cancellationCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM payment_cancellation", Long.class);
    }

    private Long ledgerTransactionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_transaction", Long.class);
    }
}

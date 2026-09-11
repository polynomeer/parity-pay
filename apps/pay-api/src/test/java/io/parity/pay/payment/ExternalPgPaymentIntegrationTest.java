package io.parity.pay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.api.mockpg.MockPgBehavior;
import io.parity.pay.api.mockpg.MockPgClient;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.PaymentView;
import io.parity.pay.payment.application.port.in.PaymentQuery;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.payment.domain.PaymentStatus;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 외부 PG 결제.
 *
 * <p>페이머니 결제와 다른 점은 하나입니다. **돈이 우리 밖에서 움직입니다.** 그래서 로컬 트랜잭션
 * 하나로 끝낼 수 없고, 결과를 모르는 상태가 존재합니다.
 *
 * <p>여기서 고정하는 것:
 *
 * <ul>
 *   <li>승인되면 PG 미수금이 늘고 판매자 지급예정금이 늘며, **지갑 잔액은 그대로다** (JE-013)
 *   <li>거절되면 원장에 아무것도 남지 않는다
 *   <li>결과를 모르면 실패로 적지 않고 UNKNOWN으로 보존한다. 원장·잔액·이벤트에 효과가 없다
 * </ul>
 *
 * <p>근거: docs/04-payment-policy.md §4, ADR-007, docs/07-ledger-journal-catalog.md JE-013
 */
class ExternalPgPaymentIntegrationTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 30_000L;

    @Autowired
    private ApprovePaymentUseCase approvePayment;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private MockPgBehavior mockPgBehavior;

    @Autowired
    private PaymentQuery paymentQuery;

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
                         idempotency_record, payment_cancellation, payment, top_up,
                         outbox_event, consumed_event, wallet_transaction,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        mockPgBehavior.reset();

        OnboardingService.RegisteredMember member =
                onboardingService.registerMember("pg-buyer@example.com", "password1234");
        memberId = member.memberId();
        walletId = WalletId.of(member.walletId());
        merchantId = MerchantId.generate();
    }

    @AfterEach
    void tearDown() {
        mockPgBehavior.reset();
    }

    @Test
    @DisplayName("JE-013: PG 승인은 PG 미수금과 판매자 지급예정금을 늘리고 지갑 잔액은 건드리지 않는다")
    void approvedPgPaymentPostsReceivableAndPayable() {
        PaymentView view = approve("pg-key-1", "order-pg-1");

        assertThat(view.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(view.approvedAmount()).isEqualTo(Money.of(AMOUNT, CurrencyCode.KRW));

        assertThat(balanceOf("1020")).isEqualTo(AMOUNT);
        assertThat(balanceOf("2030")).isEqualTo(AMOUNT);
        // 지갑은 그대로입니다. 사용자가 카드로 냈으므로 페이머니가 줄 이유가 없습니다.
        assertThat(availableBalance()).isZero();
        assertThat(balanceOf("2010")).isZero();

        // 외부 참조를 남깁니다. 이것이 없으면 나중에 무엇을 조회해 대사할지 알 수 없습니다.
        Map<String, Object> row = paymentRow();
        assertThat(row.get("external_reference_id")).isNotNull();
        assertThat(row.get("method")).isEqualTo("EXTERNAL_PG");
    }

    @Test
    @DisplayName("외부가 거절하면 FAILED로 남고 원장에는 아무것도 없다")
    void declinedPgPaymentLeavesNoLedgerEffect() {
        mockPgBehavior.setMode(MockPgBehavior.Mode.EXPLICIT_DECLINE);

        PaymentView view = approve("pg-key-2", "order-pg-2");

        assertThat(view.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(ledgerTransactionCount()).isZero();
        assertThat(paymentRow().get("failure_reason")).isEqualTo("MOCK_PG_DECLINED");
    }

    @Test
    @DisplayName("F-006: 승인 후 응답이 유실되면 실패가 아니라 UNKNOWN으로 보존한다")
    void lostResponseAfterApprovalIsPreservedAsUnknown() {
        mockPgBehavior.setMode(MockPgBehavior.Mode.TIMEOUT_AFTER_APPROVAL);

        PaymentView view = approve("pg-key-3", "order-pg-3");

        assertThat(view.status()).isEqualTo(PaymentStatus.UNKNOWN);
        // 외부에는 승인이 남아 있지만 우리는 아직 모릅니다. 확정 전까지 원장에 효과가 없어야 합니다.
        assertThat(ledgerTransactionCount()).isZero();
        assertThat(outboxEventCount("PaymentApproved")).isZero();
        assertThat(externalApprovalCount()).isEqualTo(1L);
    }

    /**
     * FR-006·ADR-007: 주문번호 조회는 "실패"를 먼저 말하면 안 됩니다. 미확정 시도가 있으면 나중에 승인으로
     * 확정될 수 있으므로, 같은 주문에 그 뒤 실패한 시도가 있어도 미확정 쪽이 답입니다.
     */
    @Test
    @DisplayName("FR-006: 같은 주문에 미확정 시도와 실패한 시도가 있으면 미확정이 답이다")
    void lookupPrefersUnknownOverLaterFailure() {
        mockPgBehavior.setMode(MockPgBehavior.Mode.TIMEOUT_AFTER_APPROVAL);
        PaymentView unknown = approve("pg-key-lookup-1", "order-pg-lookup");
        assertThat(unknown.status()).isEqualTo(PaymentStatus.UNKNOWN);

        // 사용자가 결과를 모른 채 새 키로 다시 시도했고 이번엔 거절됐습니다. 더 최신이지만 답이 아닙니다.
        mockPgBehavior.setMode(MockPgBehavior.Mode.EXPLICIT_DECLINE);
        PaymentView failed = approve("pg-key-lookup-2", "order-pg-lookup");
        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);

        PaymentView found = paymentQuery.getPaymentByOrderId(memberId, "order-pg-lookup");

        assertThat(found.paymentId()).isEqualTo(unknown.paymentId());
        assertThat(found.status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("승인 전에 끊겨도 UNKNOWN이다. 외부에 기록이 없다는 것은 조회로만 확인한다")
    void lostResponseBeforeApprovalIsAlsoUnknown() {
        mockPgBehavior.setMode(MockPgBehavior.Mode.TIMEOUT_BEFORE_APPROVAL);

        PaymentView view = approve("pg-key-4", "order-pg-4");

        assertThat(view.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(ledgerTransactionCount()).isZero();
        assertThat(externalApprovalCount()).isZero();
    }

    @Test
    @DisplayName("INV-004: 같은 멱등 키로 다시 요청해도 외부 승인과 원장은 한 번뿐이다")
    void repeatedRequestApprovesOnce() {
        PaymentView first = approve("pg-key-5", "order-pg-5");
        PaymentView second = approve("pg-key-5", "order-pg-5");

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(ledgerTransactionCount()).isEqualTo(1L);
        assertThat(externalApprovalCount()).isEqualTo(1L);
        assertThat(balanceOf("2030")).isEqualTo(AMOUNT);
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

    /** 계정 코드의 잔액입니다. 대변 잔액 계정은 부호가 반대이므로 절댓값으로 비교합니다. */
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
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT available_amount FROM wallet_balance WHERE wallet_id = ?", Long.class, walletId.value());
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    private Map<String, Object> paymentRow() {
        return jdbcTemplate.queryForMap(
                "SELECT method, status, external_reference_id, failure_reason FROM payment ORDER BY created_at DESC"
                        + " LIMIT 1");
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
        return pgClient.count("approvals", "APPROVED");
    }
}

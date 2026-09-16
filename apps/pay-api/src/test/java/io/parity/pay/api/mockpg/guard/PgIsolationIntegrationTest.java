package io.parity.pay.api.mockpg.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.api.mockpg.MockPgBehavior;
import io.parity.pay.api.mockpg.MockPgClient;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.PaymentView;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.payment.domain.PaymentStatus;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-014: 차단기가 열린 동안 들어온 결제의 운명.
 *
 * <p>요청이 기관에 나가지 않았으므로 미확정이 아니라 알려진 실패입니다. 미확정으로 두면 복구 작업이
 * 존재하지 않는 기록을 세 번씩 묻습니다. 실제 부하 아래의 전이 시각은 reports/11 M-021에 있습니다.
 */
class PgIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ApprovePaymentUseCase approvePayment;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private MockPgBehavior mockPgBehavior;

    @Autowired
    private PgCallGuard guard;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockPgClient pgClient;

    private MemberId memberId;
    private WalletId walletId;
    private MerchantId merchantId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt, audit_log, payment_recovery,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment, top_up,
                         outbox_event, consumed_event, wallet_transaction,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        guard.resetCircuitForTests();
        OnboardingService.RegisteredMember registered =
                onboardingService.registerMember("isolation@example.com", "password1234");
        memberId = registered.memberId();
        walletId = WalletId.of(registered.walletId());
        merchantId = MerchantId.generate();
    }

    @AfterEach
    void tearDown() {
        mockPgBehavior.setMode(MockPgBehavior.Mode.NORMAL);
        // 다음 시험 클래스가 같은 컨텍스트를 씁니다. 열어 둔 채 나가면 그쪽 결제가 거절됩니다.
        guard.resetCircuitForTests();
    }

    @Test
    @DisplayName("ADR-014: 기관이 연속으로 응답하지 않으면 차단기가 열리고, 그 뒤 결제는 기관에 가지 않은 채 FAILED(CIRCUIT_OPEN)이다")
    void paymentsDuringOpenCircuitFailFastWithoutReachingTheInstitution() {
        mockPgBehavior.setMode(MockPgBehavior.Mode.TIMEOUT_BEFORE_APPROVAL);
        // 최소 호출 수(10)를 채웁니다. 전부 타임아웃 → UNKNOWN → 실패율 100%.
        for (int i = 0; i < 10; i++) {
            PaymentView view = approve("open-" + i);
            assertThat(view.status()).isEqualTo(PaymentStatus.UNKNOWN);
        }
        long approvalsBefore = mockPgApprovals();

        PaymentView rejected = approve("while-open");

        assertThat(rejected.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(rejected.failureReason()).isEqualTo("CIRCUIT_OPEN");
        // 기관에 도달하지 않았습니다. 도달했다면 미확정이어야 했을 것입니다.
        assertThat(mockPgApprovals()).isEqualTo(approvalsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE status = 'UNKNOWN'", Long.class))
                .isEqualTo(10L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM payment WHERE payment_id = ?",
                        String.class,
                        rejected.paymentId().value()))
                .isEqualTo("FAILED");

        // 차단기를 닫으면 같은 결제 경로가 다시 기관에 갑니다.
        mockPgBehavior.setMode(MockPgBehavior.Mode.NORMAL);
        guard.resetCircuitForTests();
        PaymentView approved = approve("after-close");
        assertThat(approved.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(mockPgApprovals()).isEqualTo(approvalsBefore + 1);
    }

    private PaymentView approve(String key) {
        return approvePayment.approve(new ApprovePaymentCommand(
                memberId,
                "order-" + key,
                walletId,
                merchantId,
                Money.of(10_000L, CurrencyCode.KRW),
                PaymentMethod.EXTERNAL_PG,
                IdempotencyKey.of(key + "-" + UUID.randomUUID())));
    }

    /** 기관의 장부는 기관의 데이터베이스에 있습니다. 기관 API로 셉니다. */
    private long mockPgApprovals() {
        return pgClient.count("approvals", "APPROVED");
    }
}

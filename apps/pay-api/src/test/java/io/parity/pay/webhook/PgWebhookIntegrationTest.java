package io.parity.pay.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.mockpg.MockPgBehavior;
import io.parity.pay.api.onboarding.OnboardingService;
import io.parity.pay.api.webhook.WebhookSignature;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase;
import io.parity.pay.payment.application.port.in.ApprovePaymentUseCase.ApprovePaymentCommand;
import io.parity.pay.payment.domain.PaymentMethod;
import io.parity.pay.shared.id.MemberId;
import io.parity.pay.shared.id.MerchantId;
import io.parity.pay.shared.id.PaymentId;
import io.parity.pay.shared.id.WalletId;
import io.parity.pay.shared.idempotency.IdempotencyKey;
import io.parity.pay.shared.money.CurrencyCode;
import io.parity.pay.shared.money.Money;
import io.parity.pay.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F-008 웹훅 중복·역순.
 *
 * <p>웹훅은 인증 없이 열려 있는 유일한 쓰기 경로이고, 기관은 at-least-once로 보냅니다. 여기서
 * 지키는 것은 세 가지입니다.
 *
 * <ol>
 *   <li>서명 없는 요청은 아무것도 하지 못합니다.
 *   <li>같은 알림이 두 번 와도 효과는 한 번입니다.
 *   <li>오래된 알림이 나중에 도착해도 최신 상태를 되돌리지 않습니다.
 * </ol>
 *
 * <p>그리고 가장 중요한 전제 — <b>웹훅은 payload를 믿지 않습니다.</b> "승인됐다"는 말로 돈을 움직이지
 * 않고 기관에 직접 물어보는 복구 경로를 촉발할 뿐입니다. 그래서 위조된 웹훅이 서명을 통과하더라도
 * 만들어낼 수 있는 최악은 불필요한 조회 한 번입니다.
 *
 * <p>근거: F-008, ADR-007, docs/09-consistency-recovery.md §11
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot 4는 TestRestTemplate 빈을 자동으로 만들지 않습니다. 3.5에서는 RANDOM_PORT만으로
// 주입됐습니다. 기반 클래스에 두면 웹 서버가 없는 시험까지 깨지므로 여기에 붙입니다.
@AutoConfigureTestRestTemplate
class PgWebhookIntegrationTest extends AbstractIntegrationTest {

    private static final long AMOUNT = 30_000L;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ApprovePaymentUseCase approvePayment;

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private MockPgBehavior mockPgBehavior;

    @Autowired
    private WebhookSignature signature;

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
                         webhook_receipt, webhook_cursor,
                         outbox_event, consumed_event, wallet_transaction,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        // 기관에 우리 주소를 등록합니다. 실제 PG도 가맹점이 콜백 주소를 등록합니다.
        mockPgBehavior.setWebhookUrl("http://localhost:" + port + "/api/v1/webhooks/mock-pg");
        // 기본은 기관이 알림을 보내지 않는 상태입니다. 손으로 보내는 시험에서 기관의 알림이 먼저
        // 도착해 확정해버리면 무엇을 재고 있는지 알 수 없습니다. 기관이 실제로 보내는 것을 보는
        // 시험은 아래에서 모드를 직접 켭니다.
        mockPgBehavior.setWebhookMode("NONE");

        OnboardingService.RegisteredMember member =
                onboardingService.registerMember("webhook@example.com", "password1234");
        memberId = member.memberId();
        walletId = WalletId.of(member.walletId());
        merchantId = MerchantId.generate();
    }

    @Test
    @DisplayName("서명이 없거나 틀리면 아무것도 하지 않는다")
    void unsignedWebhooksAreRejected() {
        String body = payload(
                UUID.randomUUID().toString(),
                "PAYMENT_APPROVED",
                UUID.randomUUID().toString(),
                1);

        assertThat(post(body, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(body, Instant.now().toString(), "deadbeef").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // 시각이 허용 범위를 벗어나면 서명이 맞아도 거절합니다(재전송 창을 좁힙니다).
        String stale = Instant.now().minus(Duration.ofHours(2)).toString();
        assertThat(post(body, stale, signature.sign(stale, body)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(receiptCount()).isZero();
    }

    @Test
    @DisplayName("같은 알림이 두 번 와도 수신 이력은 한 건이고 효과도 한 번이다")
    void duplicateWebhooksHaveOneEffect() {
        PaymentId paymentId = approveWithLostResponse("wh-key-1", "order-wh-1");
        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");

        String eventId = UUID.randomUUID().toString();
        String body = payload(eventId, "PAYMENT_APPROVED", paymentId.toString(), 1);

        assertThat(send(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        // 재전송은 원문 그대로 옵니다 — eventId도 같습니다.
        ResponseEntity<java.util.Map> second = send(body);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("result")).isEqualTo("DUPLICATE");
        assertThat(receiptCount()).isEqualTo(1L);
        // 확정은 한 번만 일어났고 원장도 한 건입니다.
        assertThat(paymentStatus(paymentId)).isEqualTo("APPROVED");
        assertThat(paymentLedgerCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("오래된 알림이 나중에 도착해도 상태를 되돌리지 않는다")
    void staleWebhooksDoNotRegressTheState() {
        PaymentId paymentId = approveWithLostResponse("wh-key-2", "order-wh-2");

        // 최신 알림이 먼저 도착해 확정됩니다.
        send(payload(UUID.randomUUID().toString(), "PAYMENT_APPROVED", paymentId.toString(), 5));
        assertThat(paymentStatus(paymentId)).isEqualTo("APPROVED");

        // 뒤늦게 도착한 이전 알림입니다. eventId가 다르므로 중복으로는 걸리지 않습니다.
        ResponseEntity<java.util.Map> late =
                send(payload(UUID.randomUUID().toString(), "PAYMENT_DECLINED", paymentId.toString(), 3));

        assertThat(late.getBody().get("result")).isEqualTo("STALE");
        assertThat(paymentStatus(paymentId)).isEqualTo("APPROVED");
        assertThat(paymentLedgerCount()).isEqualTo(1L);
        assertThat(cursorSequence(paymentId)).isEqualTo(5L);
    }

    @Test
    @DisplayName("기관이 보낸 웹훅이 미확정 결제를 복구 작업보다 먼저 확정한다")
    void aRealWebhookSettlesTheUnknownPayment() {
        // 기관은 응답을 끊지만 웹훅은 보냅니다. 그것이 웹훅의 값어치입니다 — 복구 주기를 기다리지
        // 않고 그 자리에서 확정됩니다.
        mockPgBehavior.setWebhookMode("NORMAL");
        PaymentId paymentId = approveWithLostResponse("wh-key-3", "order-wh-3");

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(paymentStatus(paymentId)).isEqualTo("APPROVED"));
        assertThat(paymentLedgerCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("기관이 같은 알림을 두 번 보내도 결과는 한 번이다")
    void aRealDuplicateFromTheInstitutionIsAbsorbed() {
        mockPgBehavior.setWebhookMode("DUPLICATE");
        PaymentId paymentId = approveWithLostResponse("wh-key-4", "order-wh-4");

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(paymentStatus(paymentId)).isEqualTo("APPROVED"));

        // 두 번 왔지만 수신 이력은 한 건입니다 — 재전송은 같은 eventId로 옵니다.
        assertThat(receiptCount()).isEqualTo(1L);
        assertThat(paymentLedgerCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("기관이 역순으로 보내도 최신 상태가 유지된다")
    void aRealOutOfOrderDeliveryIsIgnored() {
        mockPgBehavior.setWebhookMode("OUT_OF_ORDER");
        PaymentId first = approveWithLostResponse("wh-key-5", "order-wh-5");
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(paymentStatus(first)).isEqualTo("APPROVED"));

        // 같은 결제에 대해 두 번째 알림이 나가면 그 뒤에 순번이 더 작은 알림이 따라옵니다.
        send(payload(UUID.randomUUID().toString(), "PAYMENT_APPROVED", first.toString(), 99));
        ResponseEntity<java.util.Map> stale =
                send(payload(UUID.randomUUID().toString(), "PAYMENT_APPROVED", first.toString(), 2));

        assertThat(stale.getBody().get("result")).isEqualTo("STALE");
        assertThat(paymentStatus(first)).isEqualTo("APPROVED");
        assertThat(paymentLedgerCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("우리가 모르는 키로 온 웹훅은 200으로 받고 아무것도 하지 않는다")
    void webhooksForUnknownKeysAreAcknowledged() {
        ResponseEntity<java.util.Map> response = send(payload(
                UUID.randomUUID().toString(),
                "PAYMENT_APPROVED",
                UUID.randomUUID().toString(),
                1));

        // 400을 주면 기관이 계속 재전송합니다. 우리 일이 아닌 것을 두고 그럴 이유가 없습니다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("result")).isEqualTo("IGNORED");
        assertThat(paymentLedgerCount()).isZero();
    }

    private PaymentId approveWithLostResponse(String key, String orderId) {
        mockPgBehavior.setMode(MockPgBehavior.Mode.TIMEOUT_AFTER_APPROVAL);
        PaymentId paymentId = approvePayment
                .approve(new ApprovePaymentCommand(
                        memberId,
                        orderId,
                        walletId,
                        merchantId,
                        Money.of(AMOUNT, CurrencyCode.KRW),
                        PaymentMethod.EXTERNAL_PG,
                        IdempotencyKey.of(key)))
                .paymentId();
        mockPgBehavior.setMode(MockPgBehavior.Mode.NORMAL);
        return paymentId;
    }

    private ResponseEntity<java.util.Map> send(String body) {
        String timestamp = Instant.now().toString();
        return post(body, timestamp, signature.sign(timestamp, body));
    }

    private ResponseEntity<java.util.Map> post(String body, String timestamp, String signatureHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (timestamp != null) {
            headers.add("X-Webhook-Timestamp", timestamp);
        }
        if (signatureHeader != null) {
            headers.add("X-Webhook-Signature", signatureHeader);
        }
        return restTemplate.postForEntity(
                "/api/v1/webhooks/mock-pg", new HttpEntity<>(body, headers), java.util.Map.class);
    }

    private String payload(String eventId, String eventType, String externalKey, long sequence) {
        return "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"externalKey\":\"%s\",\"sequence\":%d,\"amount\":%d}"
                .formatted(eventId, eventType, externalKey, sequence, AMOUNT);
    }

    private String paymentStatus(PaymentId paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment WHERE payment_id = ?", String.class, paymentId.value());
    }

    private long paymentLedgerCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_transaction WHERE transaction_type = 'PAYMENT_APPROVED'", Long.class);
    }

    private long receiptCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM webhook_receipt", Long.class);
    }

    private long cursorSequence(PaymentId paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT sequence_no FROM webhook_cursor WHERE external_key = ?", Long.class, paymentId.toString());
    }
}

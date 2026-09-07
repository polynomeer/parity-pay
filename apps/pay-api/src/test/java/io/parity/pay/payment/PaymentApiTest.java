package io.parity.pay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.mockbank.MockBankBehavior;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** 결제·취소 HTTP 계약. 근거: docs/08-db-api-event-spec.md §4·§5 */
@SpringBootTest(
        classes = ParityPayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    @Autowired
    private MockBankBehavior mockBankBehavior;

    private UUID walletId;
    private String accessToken;
    private final UUID merchantId = UUID.randomUUID();

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
        // 컨텍스트를 공유하므로 다른 테스트가 남긴 장애 주입 설정을 되돌립니다.
        mockBankBehavior.reset();
        operatorBootstrap.createConfiguredOperators();

        ApiAuth.Session session =
                ApiAuth.registerAndLogin(restTemplate, "api-payer@example.com", "password1234");
        walletId = session.walletId();
        accessToken = session.accessToken();

        ResponseEntity<Map> bankAccount = restTemplate.exchange(
                "/api/v1/bank-accounts",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "bankCode", "004",
                                "accountNumber", "110-1234-5678",
                                "initialBalance", 1_000_000),
                        ApiAuth.bearer(accessToken)),
                Map.class);
        UUID bankAccountId = UUID.fromString((String) bankAccount.getBody().get("bankAccountId"));

        restTemplate.exchange(
                "/api/v1/top-ups",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "walletId", walletId.toString(),
                                "bankAccountId", bankAccountId.toString(),
                                "amount", 50_000,
                                "currency", "KRW"),
                        ApiAuth.bearer(accessToken, "api-payer-topup-001")),
                Map.class);
    }

    @Test
    @DisplayName("결제 승인은 201과 취소 가능액을 함께 돌려준다")
    void approveReturnsCreated() {
        ResponseEntity<Map> response = pay("order-api-1", "api-payment-0001", 30_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("APPROVED");
        assertThat(response.getBody().get("approvedAmount")).isEqualTo(30_000);
        assertThat(response.getBody().get("cancellableAmount")).isEqualTo(30_000);
    }

    @Test
    @DisplayName("잔액이 부족하면 409 INSUFFICIENT_BALANCE이며 잔액을 노출하지 않는다")
    void insufficientBalanceReturnsConflict() {
        ResponseEntity<Map> response = pay("order-api-2", "api-payment-0002", 60_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(response.getBody().get("message").toString()).doesNotContain("50000");
    }

    @Test
    @DisplayName("부분 취소는 201과 누적 취소액을 돌려준다")
    void partialCancellationReturnsCreated() {
        ResponseEntity<Map> payment = pay("order-api-3", "api-payment-0003", 30_000);
        String paymentId = (String) payment.getBody().get("paymentId");

        ResponseEntity<Map> cancellation = restTemplate.exchange(
                "/api/v1/payments/" + paymentId + "/cancellations",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("amount", 10_000, "currency", "KRW", "reason", "PARTIAL_RETURN"),
                        ApiAuth.bearer(accessToken, "api-cancel-0001")),
                Map.class);

        assertThat(cancellation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cancellation.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(cancellation.getBody().get("paymentCanceledAmount")).isEqualTo(10_000);

        ResponseEntity<Map> reloaded = restTemplate.exchange(
                "/api/v1/payments/" + paymentId,
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(accessToken)),
                Map.class);
        assertThat(reloaded.getBody().get("status")).isEqualTo("PARTIALLY_CANCELED");
        assertThat(reloaded.getBody().get("cancellableAmount")).isEqualTo(20_000);
    }

    @Test
    @DisplayName("취소 가능액을 넘으면 409 CANCELLATION_AMOUNT_EXCEEDED")
    void overCancellationReturnsConflict() {
        ResponseEntity<Map> payment = pay("order-api-4", "api-payment-0004", 30_000);
        String paymentId = (String) payment.getBody().get("paymentId");

        ResponseEntity<Map> cancellation = restTemplate.exchange(
                "/api/v1/payments/" + paymentId + "/cancellations",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("amount", 30_001, "currency", "KRW", "reason", "TEST"),
                        ApiAuth.bearer(accessToken, "api-cancel-0002")),
                Map.class);

        assertThat(cancellation.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cancellation.getBody().get("code")).isEqualTo("CANCELLATION_AMOUNT_EXCEEDED");
    }

    @Test
    @DisplayName("다른 사용자의 결제는 조회되지 않는다")
    void otherMembersPaymentIsNotVisible() {
        ResponseEntity<Map> payment = pay("order-api-5", "api-payment-0005", 10_000);
        String paymentId = (String) payment.getBody().get("paymentId");

        ApiAuth.Session other =
                ApiAuth.registerAndLogin(restTemplate, "api-other-payer@example.com", "password1234");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/payments/" + paymentId,
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(other.accessToken())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_FOUND");
    }

    private ResponseEntity<Map> pay(String orderId, String idempotencyKey, long amount) {
        return restTemplate.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "orderId", orderId,
                                "walletId", walletId.toString(),
                                "merchantId", merchantId.toString(),
                                "amount", amount,
                                "currency", "KRW",
                                "method", "PAY_MONEY"),
                        ApiAuth.bearer(accessToken, idempotencyKey)),
                Map.class);
    }
}

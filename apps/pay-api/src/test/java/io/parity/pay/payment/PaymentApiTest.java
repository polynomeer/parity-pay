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
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** 결제·취소 HTTP 계약. 근거: docs/08-db-api-event-spec.md §4·§5 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot 4는 TestRestTemplate 빈을 자동으로 만들지 않습니다. 3.5에서는 RANDOM_PORT만으로
// 주입됐습니다. 기반 클래스에 두면 웹 서버가 없는 시험까지 깨지므로 여기에 붙입니다.
@AutoConfigureTestRestTemplate
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
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        // 컨텍스트를 공유하므로 다른 테스트가 남긴 장애 주입 설정을 되돌립니다.
        mockBankBehavior.reset();
        operatorBootstrap.createConfiguredOperators();

        ApiAuth.Session session = ApiAuth.registerAndLogin(restTemplate, "api-payer@example.com", "password1234");
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
                                "walletId",
                                walletId.toString(),
                                "bankAccountId",
                                bankAccountId.toString(),
                                "amount",
                                50_000,
                                "currency",
                                "KRW"),
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

        ApiAuth.Session other = ApiAuth.registerAndLogin(restTemplate, "api-other-payer@example.com", "password1234");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/payments/" + paymentId,
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(other.accessToken())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_FOUND");
    }

    /**
     * FR-006: `orderId`로도 조회할 수 있어야 합니다. 멱등 키를 잃은 사용자(앱 삭제, 다른 기기)의 유일한
     * 복구 경로입니다. DOC-14 §13이 "필요한지 판단하지 않았다"고 적어 두었는데, PRD는 처음부터 Must로
     * 요구하고 있었고 서비스에 구현까지 있었으며 HTTP로만 열려 있지 않았습니다.
     */
    @Test
    @DisplayName("FR-006: 주문번호로 결제를 찾는다")
    void findsPaymentByOrderId() {
        ResponseEntity<Map> approved = pay("order-lookup-1", "api-lookup-0001", 30_000);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> found = byOrderId("order-lookup-1", accessToken);

        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody().get("paymentId"))
                .isEqualTo(approved.getBody().get("paymentId"));
        assertThat(found.getBody().get("status")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("FR-006: 한 주문에 실패한 시도가 있어도 살아 있는 결제를 돌려준다")
    void activePaymentWinsOverFailedAttempts() {
        // 잔액 50,000에 60,000을 시도해 실패시킨 뒤, 같은 주문으로 성공시킵니다.
        assertThat(pay("order-lookup-2", "api-lookup-0002", 60_000).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> approved = pay("order-lookup-2", "api-lookup-0003", 30_000);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> found = byOrderId("order-lookup-2", accessToken);

        assertThat(found.getBody().get("paymentId"))
                .isEqualTo(approved.getBody().get("paymentId"));
        assertThat(found.getBody().get("status")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("FR-006: 남의 주문번호는 없는 것으로 보인다")
    void otherMembersOrderIsInvisible() {
        assertThat(pay("order-lookup-3", "api-lookup-0004", 30_000).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ApiAuth.Session other = ApiAuth.registerAndLogin(restTemplate, "api-other@example.com", "password1234");

        ResponseEntity<Map> found = byOrderId("order-lookup-3", other.accessToken());

        // 403이면 "그 주문번호가 존재한다"가 샙니다. 주문번호는 클라이언트가 만드는 값이라 추측이 쉽습니다.
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("FR-006: 결제가 없는 주문번호는 404")
    void unknownOrderIsNotFound() {
        assertThat(byOrderId("order-never-paid", accessToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<Map> byOrderId(String orderId, String token) {
        return restTemplate.exchange(
                "/api/v1/payments?orderId=" + orderId,
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(token)),
                Map.class);
    }

    private ResponseEntity<Map> pay(String orderId, String idempotencyKey, long amount) {
        return restTemplate.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "orderId",
                                orderId,
                                "walletId",
                                walletId.toString(),
                                "merchantId",
                                merchantId.toString(),
                                "amount",
                                amount,
                                "currency",
                                "KRW",
                                "method",
                                "PAY_MONEY"),
                        ApiAuth.bearer(accessToken, idempotencyKey)),
                Map.class);
    }
}

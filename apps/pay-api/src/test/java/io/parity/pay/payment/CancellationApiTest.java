package io.parity.pay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.mockpg.MockPgBehavior;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.payment.application.service.CancellationRecoveryService;
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

/**
 * 취소의 HTTP 계약 — 결함 L.
 *
 * <p>환불 응답이 유실되면 취소는 {@code UNKNOWN}으로 남는데, HTTP는 그것을 {@code 201}로 답하고
 * 있었습니다. 충전·결제는 같은 상황에 {@code 202 + Location}으로 답합니다. 클라이언트는 201을
 * "끝났다"로 읽으므로 확정되지 않은 취소를 취소됨으로 보여 줬고, 취소를 조회할 API가 없어 기다릴
 * 방법도 없었습니다. M-012의 취소 경로를 재려다 드러났습니다. 근거: ADR-007, DOC-14 FE-002
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class CancellationApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    @Autowired
    private MockPgBehavior mockPgBehavior;

    @Autowired
    private CancellationRecoveryService cancellationRecovery;

    private UUID walletId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt, audit_log,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment, top_up,
                         outbox_event, consumed_event, wallet_transaction,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        mockPgBehavior.reset();
        operatorBootstrap.createConfiguredOperators();
        ApiAuth.Session session = ApiAuth.registerAndLogin(restTemplate, "cancel-api@example.com", "password1234");
        walletId = session.walletId();
        accessToken = session.accessToken();
    }

    @Test
    @DisplayName("결함 L: 환불 응답이 유실된 취소는 201이 아니라 202이고, 조회로 확정을 기다릴 수 있다")
    void unsettledCancellationIsAcceptedNotCreated() {
        String paymentId = approveExternal("order-cancel-api-1", "cancel-api-pay-1");
        mockPgBehavior.setRefundMode(MockPgBehavior.Mode.TIMEOUT_AFTER_APPROVAL);

        ResponseEntity<Map> accepted = cancel(paymentId, "cancel-api-1", 10_000);

        // 결과를 모르는데 201이면 클라이언트는 "취소됨"으로 보여 줍니다.
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody().get("status")).isEqualTo("UNKNOWN");
        String location = accepted.getHeaders().getFirst("Location");
        assertThat(location)
                .isEqualTo("/api/v1/payments/" + paymentId + "/cancellations/"
                        + accepted.getBody().get("cancellationId"));

        // Location이 실제로 열리고, 아직 확정 전입니다.
        ResponseEntity<Map> pending = get(location);
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pending.getBody().get("status")).isEqualTo("UNKNOWN");

        // 복구가 조회로 확정하면 같은 주소가 종결 상태를 보여 줍니다.
        mockPgBehavior.reset();
        assertThat(cancellationRecovery.resolveDue()).isEqualTo(1);
        ResponseEntity<Map> settled = get(location);
        assertThat(settled.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(settled.getBody().get("paymentCanceledAmount")).isEqualTo(10_000);
    }

    @Test
    @DisplayName("확정된 취소는 그대로 201이다")
    void settledCancellationIsStillCreated() {
        String paymentId = approveExternal("order-cancel-api-2", "cancel-api-pay-2");

        ResponseEntity<Map> created = cancel(paymentId, "cancel-api-2", 10_000);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("남의 결제의 취소는 없는 것으로 보인다")
    void otherMembersCancellationIsInvisible() {
        String paymentId = approveExternal("order-cancel-api-3", "cancel-api-pay-3");
        ResponseEntity<Map> created = cancel(paymentId, "cancel-api-3", 10_000);
        ApiAuth.Session other = ApiAuth.registerAndLogin(restTemplate, "cancel-other@example.com", "password1234");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/payments/" + paymentId + "/cancellations/"
                        + created.getBody().get("cancellationId"),
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(other.accessToken())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** 외부 PG 결제입니다. 환불이 외부로 나가야 응답 유실을 만들 수 있습니다. */
    private String approveExternal(String orderId, String key) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "orderId",
                                orderId,
                                "walletId",
                                walletId.toString(),
                                "merchantId",
                                UUID.randomUUID().toString(),
                                "amount",
                                30_000,
                                "currency",
                                "KRW",
                                "method",
                                "EXTERNAL_PG"),
                        ApiAuth.bearer(accessToken, key)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("paymentId");
    }

    private ResponseEntity<Map> cancel(String paymentId, String key, long amount) {
        return restTemplate.exchange(
                "/api/v1/payments/" + paymentId + "/cancellations",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("amount", amount, "currency", "KRW", "reason", "PARTIAL_RETURN"),
                        ApiAuth.bearer(accessToken, key)),
                Map.class);
    }

    private ResponseEntity<Map> get(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(ApiAuth.bearer(accessToken)), Map.class);
    }
}

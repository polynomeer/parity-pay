package io.parity.pay.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.mockbank.MockBankBehavior;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import java.util.List;
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

/**
 * 운영자 API와 감사 로그.
 *
 * <p>운영자가 미확정 거래를 찾고 안전한 재조회를 요청할 수 있는지, 그 작업이 지울 수 없는 기록으로
 * 남는지 확인합니다. 근거: FR-012, NFR-006, docs/09-consistency-recovery.md §10
 */
@SpringBootTest(
        classes = ParityPayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperationsApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MockBankBehavior mockBankBehavior;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    private UUID walletId;
    private UUID bankAccountId;
    private String customerToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        mockBankBehavior.reset();
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment,
                         top_up_recovery, top_up, audit_log,
                         outbox_event, consumed_event, wallet_transaction,
                         mock_bank_withdrawal, mock_bank_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);

        operatorBootstrap.createConfiguredOperators();
        operatorToken = ApiAuth.login(restTemplate, ApiAuth.OPS_OPERATOR, ApiAuth.OPS_PASSWORD);

        ApiAuth.Session session =
                ApiAuth.registerAndLogin(restTemplate, "ops@example.com", "password1234");
        walletId = session.walletId();
        customerToken = session.accessToken();

        ResponseEntity<Map> bankAccount = restTemplate.exchange(
                "/api/v1/bank-accounts",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "bankCode", "004",
                                "accountNumber", "110-5555-4444",
                                "initialBalance", 1_000_000),
                        ApiAuth.bearer(customerToken)),
                Map.class);
        bankAccountId = UUID.fromString((String) bankAccount.getBody().get("bankAccountId"));
    }

    @Test
    @DisplayName("FR-012: 미확정 충전을 운영자가 조회할 수 있다")
    void unresolvedTopUpsAreVisibleToOperators() {
        String topUpId = createUnknownTopUp("ops-key-000001");

        ResponseEntity<List> response = restTemplate.exchange(
                "/api/v1/admin/top-ups?status=UNKNOWN",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(operatorToken)),
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        Map<String, Object> row = (Map<String, Object>) response.getBody().get(0);
        assertThat(row.get("topUpId")).isEqualTo(topUpId);
        assertThat(row.get("status")).isEqualTo("UNKNOWN");
        assertThat(row.get("requiresManualReview")).isEqualTo(false);
    }

    @Test
    @DisplayName("운영자 재조회로 UNKNOWN이 확정되고 감사 로그가 남는다")
    void manualResolveSettlesAndIsAudited() {
        String topUpId = createUnknownTopUp("ops-key-000002");
        mockBankBehavior.reset();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/top-ups/" + topUpId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("reason", "고객 문의 - 충전 반영 확인"), ApiAuth.bearer(operatorToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("SUCCEEDED");
        assertThat(response.getBody().get("changed")).isEqualTo(true);

        Map<String, Object> audit = jdbcTemplate.queryForMap("SELECT * FROM audit_log");
        assertThat(audit.get("actor")).isEqualTo(ApiAuth.OPS_OPERATOR);
        assertThat(audit.get("action")).isEqualTo("TOP_UP_RECOVERY_RESOLVE");
        assertThat(audit.get("before_state")).isEqualTo("UNKNOWN");
        assertThat(audit.get("after_state")).isEqualTo("SUCCEEDED");
        assertThat(audit.get("result")).isEqualTo("SUCCEEDED");
        assertThat(audit.get("reason")).isEqualTo("고객 문의 - 충전 반영 확인");
    }

    @Test
    @DisplayName("결과가 바뀌지 않은 재조회도 기록으로 남는다")
    void noChangeIsAuditedToo() {
        String topUpId = createUnknownTopUp("ops-key-000003");
        // 조회 API가 죽어 있어 아무것도 확정할 수 없는 상황입니다.
        mockBankBehavior.setStatusQueryAvailable(false);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/top-ups/" + topUpId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "대사 차이 확인"), ApiAuth.bearer(operatorToken)),
                Map.class);

        assertThat(response.getBody().get("changed")).isEqualTo(false);
        Map<String, Object> audit = jdbcTemplate.queryForMap("SELECT * FROM audit_log");
        assertThat(audit.get("result")).isEqualTo("NO_CHANGE");
        assertThat(audit.get("after_state")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("사유 없는 운영 작업은 거절된다")
    void reasonIsRequired() {
        String topUpId = createUnknownTopUp("ops-key-000004");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/top-ups/" + topUpId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", ""), ApiAuth.bearer(operatorToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Long.class)).isZero();
    }

    @Test
    @DisplayName("NFR-006: 감사 로그는 지우거나 고칠 수 없다")
    void auditLogIsAppendOnly() {
        String topUpId = createUnknownTopUp("ops-key-000005");
        mockBankBehavior.reset();
        restTemplate.exchange(
                "/api/v1/admin/top-ups/" + topUpId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "확인"), ApiAuth.bearer(operatorToken)),
                Map.class);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_log SET actor = 'someone-else'"))
                .hasStackTraceContaining("NFR-006");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_log"))
                .hasStackTraceContaining("NFR-006");
    }

    /** 승인 후 응답이 유실되어 UNKNOWN으로 남은 충전을 만듭니다. */
    private String createUnknownTopUp(String idempotencyKey) {
        mockBankBehavior.setMode(MockBankBehavior.Mode.TIMEOUT_AFTER_WITHDRAWAL);
        HttpHeaders headers = ApiAuth.bearer(customerToken, idempotencyKey);
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/top-ups",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "walletId", walletId.toString(),
                                "bankAccountId", bankAccountId.toString(),
                                "amount", 100_000,
                                "currency", "KRW"),
                        headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return (String) response.getBody().get("topUpId");
    }

    /** 일반 사용자는 운영 API를 쓸 수 없어야 합니다. */
    @Test
    @DisplayName("일반 사용자는 운영 API에 접근할 수 없다")
    void customersCannotUseOperationsApi() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/top-ups?status=UNKNOWN",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(customerToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("RISK_BLOCKED");
    }

    @Test
    @DisplayName("읽기 권한만 있는 운영자는 재조회를 실행할 수 없다")
    void viewerCannotTriggerRecovery() {
        String topUpId = createUnknownTopUp("ops-key-000010");
        String viewerToken = ApiAuth.login(restTemplate, ApiAuth.OPS_VIEWER, ApiAuth.OPS_PASSWORD);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/top-ups/" + topUpId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "확인"), ApiAuth.bearer(viewerToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Long.class)).isZero();
    }
}

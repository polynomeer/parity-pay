package io.parity.pay.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import java.util.List;
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
 * 원장 조회 API (FE-M5).
 *
 * <p>운영자가 결제 한 건에서 원장까지 도달해 차변·대변이 맞는지 볼 수 있어야 합니다. 그전까지는
 * 원장 모듈에 조회 경로가 아예 없었습니다.
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class LedgerAdminApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private io.parity.pay.api.security.OperatorBootstrap operatorBootstrap;

    private String operatorToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt, outbox_event,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment, top_up,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        operatorBootstrap.createConfiguredOperators();
        operatorToken = ApiAuth.login(restTemplate, ApiAuth.OPS_VIEWER, ApiAuth.OPS_PASSWORD);
    }

    @Test
    @DisplayName("원장 거래를 항목·계정 코드와 함께 돌려주고 차대가 맞는지 알려 준다")
    void returnsEntriesWithAccountCodes() {
        UUID transactionId = postTopUpJournal();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/ledger/transactions/" + transactionId,
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(operatorToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        // 화면이 스스로 더하지 않아도 되게 서버가 합계와 균형 여부를 줍니다.
        assertThat(body.get("debitTotal")).isEqualTo(body.get("creditTotal"));
        assertThat(body.get("balanced")).isEqualTo(true);

        List<?> entries = (List<?>) body.get("entries");
        assertThat(entries).hasSize(2);
        // 계정 ID만 오면 운영자가 읽을 수 없습니다. 코드가 함께 와야 합니다.
        assertThat(entries).allSatisfy(entry -> {
            Map<?, ?> row = (Map<?, ?>) entry;
            assertThat((String) row.get("accountCode")).isNotBlank().isNotEqualTo("UNKNOWN");
        });
    }

    @Test
    @DisplayName("없는 원장 거래는 404다")
    void missingTransaction() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/ledger/transactions/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(operatorToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("운영자 권한이 없으면 볼 수 없다")
    void requiresOperatorRole() {
        ApiAuth.Session customer =
                ApiAuth.registerAndLogin(restTemplate, "ledger-customer@example.com", "password1234");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/ledger/transactions/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(customer.accessToken())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("원장을 고치는 경로는 제공하지 않는다")
    void hasNoWritePath() {
        // 확정 원장은 트리거가 수정을 막습니다(INV-006). API도 그 사실과 어긋나면 안 됩니다.
        UUID transactionId = postTopUpJournal();

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/ledger/transactions/" + transactionId,
                HttpMethod.DELETE,
                new HttpEntity<>(ApiAuth.bearer(operatorToken)),
                Map.class);

        assertThat(response.getStatusCode())
                .isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    /** 실제 충전을 태워 JE-001 분개 하나를 만듭니다. 합성 데이터가 아니라 진짜 원장입니다. */
    private UUID postTopUpJournal() {
        ApiAuth.Session session =
                ApiAuth.registerAndLogin(restTemplate, "ledger-" + UUID.randomUUID() + "@example.com", "password1234");
        ResponseEntity<Map> account = restTemplate.exchange(
                "/api/v1/bank-accounts",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("bankCode", "004", "accountNumber", "110-9999-0001", "initialBalance", 1_000_000),
                        ApiAuth.bearer(session.accessToken())),
                Map.class);
        restTemplate.exchange(
                "/api/v1/top-ups",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "walletId",
                                session.walletId().toString(),
                                "bankAccountId",
                                account.getBody().get("bankAccountId"),
                                "amount",
                                50_000,
                                "currency",
                                "KRW"),
                        ApiAuth.bearer(session.accessToken(), "ledger-topup-" + UUID.randomUUID())),
                Map.class);

        List<UUID> ids = jdbcTemplate.queryForList(
                "SELECT transaction_id FROM ledger_transaction ORDER BY created_at DESC LIMIT 1", UUID.class);
        assertThat(ids).as("충전이 원장 거래를 만들어야 합니다").isNotEmpty();
        return ids.get(0);
    }
}

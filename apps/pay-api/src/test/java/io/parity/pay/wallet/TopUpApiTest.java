package io.parity.pay.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import io.parity.pay.api.security.OperatorBootstrap;
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
 * HTTP 계약 검증. 근거: docs/08-db-api-event-spec.md §4, docs/10-test-strategy.md §8
 *
 * <p>상태 코드가 정책과 일치하는지 확인합니다. 특히 결과가 불명확한 요청은 실패(4xx·5xx)가 아니라
 * 202로 응답해야 합니다.
 */
@SpringBootTest(
        classes = ParityPayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TopUpApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    private UUID walletId;
    private UUID bankAccountId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, top_up, mock_bank_withdrawal, mock_bank_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        // 회원 테이블을 비웠으므로 운영자 계정을 다시 만듭니다.
        operatorBootstrap.createConfiguredOperators();
        setMockBankMode("NORMAL");

        ApiAuth.Session session = ApiAuth.registerAndLogin(
                restTemplate, "api-buyer@example.com", "password1234");
        walletId = session.walletId();
        accessToken = session.accessToken();

        ResponseEntity<Map> bankAccount = restTemplate.exchange(
                "/api/v1/bank-accounts",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("bankCode", "004", "accountNumber", "110-1234-5678", "initialBalance", 500_000),
                        ApiAuth.bearer(accessToken)),
                Map.class);
        assertThat(bankAccount.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        bankAccountId = UUID.fromString((String) bankAccount.getBody().get("bankAccountId"));
    }

    @Test
    @DisplayName("충전 성공은 201, 재요청은 같은 topUpId를 돌려준다")
    void topUpReturnsCreatedAndIsIdempotent() {
        ResponseEntity<Map> first = requestTopUp("api-key-000001", 100_000);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("status")).isEqualTo("SUCCEEDED");

        ResponseEntity<Map> repeated = requestTopUp("api-key-000001", 100_000);
        assertThat(repeated.getBody().get("topUpId")).isEqualTo(first.getBody().get("topUpId"));

        ResponseEntity<Map> balance = restTemplate.exchange(
                "/api/v1/wallets/" + walletId,
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(accessToken)),
                Map.class);
        assertThat(balance.getBody().get("available")).isEqualTo(100_000);
    }

    @Test
    @DisplayName("같은 키에 다른 본문이 오면 409 IDEMPOTENCY_KEY_REUSED")
    void sameKeyWithDifferentBodyIsRejected() {
        requestTopUp("api-key-000002", 100_000);

        ResponseEntity<Map> conflict = requestTopUp("api-key-000002", 200_000);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("결과가 불명확하면 실패가 아니라 202와 조회 위치를 준다")
    void unknownResultReturnsAccepted() {
        setMockBankMode("TIMEOUT_AFTER_WITHDRAWAL");

        ResponseEntity<Map> response = requestTopUp("api-key-000003", 100_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("status")).isEqualTo("UNKNOWN");
        assertThat(response.getHeaders().getLocation()).isNotNull();
    }

    @Test
    @DisplayName("다른 사용자의 지갑은 조회되지 않는다")
    void otherMembersWalletIsNotVisible() {
        ApiAuth.Session other =
                ApiAuth.registerAndLogin(restTemplate, "api-other@example.com", "password1234");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/wallets/" + walletId,
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(other.accessToken())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("토큰이 없으면 401이다")
    void anonymousRequestIsRejected() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/wallets/" + walletId,
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("일반 사용자는 장애 주입 API를 쓸 수 없다")
    void customerCannotInjectFailures() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/mock-bank/mode",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("mode", "EXPLICIT_FAILURE"), ApiAuth.bearer(accessToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("긴 계좌번호는 400으로 거절한다 (500이 아니라)")
    void overlongAccountNumberIsRejectedAsBadRequest() {
        // 마스킹된 값이 저장 컬럼을 넘겨 500으로 실패하던 경로입니다.
        // 입력 문제는 저장 단계가 아니라 입력에서 막아야 합니다.
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/bank-accounts",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "bankCode", "004",
                                "accountNumber", "1".repeat(64),
                                "initialBalance", 1_000),
                        ApiAuth.bearer(accessToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("허용 길이 안의 계좌번호는 마스킹되어 저장된다")
    void accountNumberIsStoredMasked() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/bank-accounts",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "bankCode", "004",
                                "accountNumber", "9876543210987654321234",
                                "initialBalance", 1_000),
                        ApiAuth.bearer(accessToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String masked = jdbcTemplate.queryForObject(
                "SELECT account_number_masked FROM bank_account WHERE bank_account_id = ?::uuid",
                String.class,
                response.getBody().get("bankAccountId"));
        // 원문이 남지 않고, 뒤 4자리만 보입니다.
        assertThat(masked).endsWith("1234").doesNotContain("987654321098");
        assertThat(masked.length()).isLessThanOrEqualTo(30);
    }

    @Test
    @DisplayName("0원 이하 금액은 400으로 거절한다")
    void nonPositiveAmountIsRejected() {
        ResponseEntity<Map> response = requestTopUp("api-key-000004", 0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    /** 장애 주입은 운영자 권한이 필요합니다. */
    private void setMockBankMode(String mode) {
        String operatorToken =
                ApiAuth.login(restTemplate, ApiAuth.OPS_OPERATOR, ApiAuth.OPS_PASSWORD);
        restTemplate.exchange(
                "/api/v1/admin/mock-bank/mode",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("mode", mode), ApiAuth.bearer(operatorToken)),
                Void.class);
    }

    private ResponseEntity<Map> requestTopUp(String idempotencyKey, long amount) {
        HttpHeaders headers = ApiAuth.bearer(accessToken, idempotencyKey);
        return restTemplate.exchange(
                "/api/v1/top-ups",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "walletId", walletId.toString(),
                                "bankAccountId", bankAccountId.toString(),
                                "amount", amount,
                                "currency", "KRW"),
                        headers),
                Map.class);
    }

}

package io.parity.pay.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 인증 계약.
 *
 * <p>근거: docs/02-prd.md §6(권한 모델), docs/05-technical-design.md §11(토큰 수명·철회, 로그인 실패
 * 제한), NFR-007
 */
@SpringBootTest(
        classes = ParityPayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
                         reconciliation_mismatch, reconciliation_run,
                         settlement_recovery, settlement_item, settlement, order_confirmation,
                         mock_bank_payout,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment,
                         top_up_recovery, top_up, audit_log,
                         outbox_event, consumed_event, wallet_transaction,
                         mock_bank_withdrawal, mock_bank_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        operatorBootstrap.createConfiguredOperators();
    }

    @Test
    @DisplayName("가입한 사용자는 로그인해 토큰을 받고 CUSTOMER 역할을 가진다")
    void loginIssuesTokens() {
        restTemplate.postForEntity(
                "/api/v1/members",
                Map.of("email", "auth-user@example.com", "password", "password1234"),
                Map.class);

        ResponseEntity<Map> tokens = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                Map.of("email", "auth-user@example.com", "password", "password1234"),
                Map.class);

        assertThat(tokens.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokens.getBody().get("tokenType")).isEqualTo("Bearer");
        assertThat(tokens.getBody().get("accessToken")).isNotNull();
        assertThat(tokens.getBody().get("refreshToken")).isNotNull();
        assertThat((List<Object>) tokens.getBody().get("roles"))
                .containsExactly((Object) "CUSTOMER");
    }

    @Test
    @DisplayName("비밀번호가 틀리면 계정 존재 여부를 알려주지 않는다")
    void wrongPasswordDoesNotRevealAccountExistence() {
        restTemplate.postForEntity(
                "/api/v1/members",
                Map.of("email", "auth-known@example.com", "password", "password1234"),
                Map.class);

        ResponseEntity<Map> wrongPassword = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                Map.of("email", "auth-known@example.com", "password", "wrong-password"),
                Map.class);
        ResponseEntity<Map> unknownAccount = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                Map.of("email", "auth-unknown@example.com", "password", "password1234"),
                Map.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 두 경우의 응답이 같아야 계정 존재 여부가 새지 않습니다.
        assertThat(wrongPassword.getBody().get("code")).isEqualTo(unknownAccount.getBody().get("code"));
        assertThat(wrongPassword.getBody().get("message"))
                .isEqualTo(unknownAccount.getBody().get("message"));
    }

    @Test
    @DisplayName("로그인 실패가 반복되면 계정을 잠근다")
    void repeatedFailuresLockTheAccount() {
        restTemplate.postForEntity(
                "/api/v1/members",
                Map.of("email", "auth-lock@example.com", "password", "password1234"),
                Map.class);

        // max-login-failures=3 (테스트 설정)
        for (int i = 0; i < 3; i++) {
            restTemplate.postForEntity(
                    "/api/v1/auth/tokens",
                    Map.of("email", "auth-lock@example.com", "password", "wrong"),
                    Map.class);
        }

        ResponseEntity<Map> afterLock = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                // 올바른 비밀번호여도 잠금이 우선입니다.
                Map.of("email", "auth-lock@example.com", "password", "password1234"),
                Map.class);

        assertThat(afterLock.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(afterLock.getBody().get("code")).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("리프레시 토큰은 교환할 때마다 회전하고, 쓴 토큰은 다시 쓸 수 없다")
    void refreshTokensRotate() {
        restTemplate.postForEntity(
                "/api/v1/members",
                Map.of("email", "auth-refresh@example.com", "password", "password1234"),
                Map.class);
        ResponseEntity<Map> first = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                Map.of("email", "auth-refresh@example.com", "password", "password1234"),
                Map.class);
        String refreshToken = (String) first.getBody().get("refreshToken");

        ResponseEntity<Map> refreshed = restTemplate.postForEntity(
                "/api/v1/auth/tokens/refresh", Map.of("refreshToken", refreshToken), Map.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().get("refreshToken")).isNotEqualTo(refreshToken);

        ResponseEntity<Map> reuse = restTemplate.postForEntity(
                "/api/v1/auth/tokens/refresh", Map.of("refreshToken", refreshToken), Map.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reuse.getBody().get("message").toString()).contains("revoked");
    }

    @Test
    @DisplayName("로그아웃하면 리프레시 토큰이 모두 철회된다")
    void logoutRevokesRefreshTokens() {
        ApiAuth.Session session =
                ApiAuth.registerAndLogin(restTemplate, "auth-logout@example.com", "password1234");
        ResponseEntity<Map> tokens = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                Map.of("email", "auth-logout@example.com", "password", "password1234"),
                Map.class);
        String refreshToken = (String) tokens.getBody().get("refreshToken");

        restTemplate.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(ApiAuth.bearer(session.accessToken())),
                Void.class);

        ResponseEntity<Map> refresh = restTemplate.postForEntity(
                "/api/v1/auth/tokens/refresh", Map.of("refreshToken", refreshToken), Map.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("위조된 토큰은 거부한다")
    void forgedTokenIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhdHRhY2tlciJ9.not-a-valid-signature");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/wallets/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("가입은 공개이지만 계좌 연결부터는 인증이 필요하다")
    void registrationIsPublicButTheRestIsNot() {
        ResponseEntity<Map> registration = restTemplate.postForEntity(
                "/api/v1/members",
                Map.of("email", "auth-public@example.com", "password", "password1234"),
                Map.class);
        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> withoutToken = restTemplate.postForEntity(
                "/api/v1/bank-accounts",
                Map.of("bankCode", "004", "accountNumber", "110-0000-0000", "initialBalance", 1000),
                Map.class);
        assertThat(withoutToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("헬스와 지표는 인증 없이 수집하고, 나머지 actuator는 운영자만 본다")
    void healthAndMetricsAreScrapable() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> scrape =
                restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Phase 6에서 만든 불변조건 지표가 실제로 노출되는지 확인합니다.
        assertThat(scrape.getBody())
                .contains("paritypay_invariant_unbalanced_ledger_transactions")
                .contains("paritypay_outbox_pending");

        // 나머지 actuator 엔드포인트는 인증이 필요합니다.
        assertThat(restTemplate.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("인증된 호출자의 잘못된 경로는 404이며 500으로 부풀리지 않는다")
    void unknownPathReturnsNotFound() {
        ApiAuth.Session session =
                ApiAuth.registerAndLogin(restTemplate, "auth-404@example.com", "password1234");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/does-not-exist",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(session.accessToken())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("미인증 호출자에게는 경로 존재 여부도 알려주지 않는다")
    void anonymousCallersCannotProbePaths() {
        // 없는 경로든 있는 경로든 똑같이 401입니다.
        assertThat(restTemplate.getForEntity("/api/v1/does-not-exist", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity("/api/v1/wallets/" + UUID.randomUUID(), Map.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

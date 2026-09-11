package io.parity.pay.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import io.parity.pay.support.RefreshCookies;
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
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot 4는 TestRestTemplate 빈을 자동으로 만들지 않습니다. 3.5에서는 RANDOM_PORT만으로
// 주입됐습니다. 기반 클래스에 두면 웹 서버가 없는 시험까지 깨지므로 여기에 붙입니다.
@AutoConfigureTestRestTemplate
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
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment,
                         top_up_recovery, top_up, audit_log,
                         outbox_event, consumed_event, wallet_transaction,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        operatorBootstrap.createConfiguredOperators();
    }

    @Test
    @DisplayName("가입한 사용자는 로그인해 토큰을 받고 CUSTOMER 역할을 가진다")
    void loginIssuesTokens() {
        restTemplate.postForEntity(
                "/api/v1/members", Map.of("email", "auth-user@example.com", "password", "password1234"), Map.class);

        ResponseEntity<Map> tokens = restTemplate.postForEntity(
                "/api/v1/auth/tokens", Map.of("email", "auth-user@example.com", "password", "password1234"), Map.class);

        assertThat(tokens.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokens.getBody().get("tokenType")).isEqualTo("Bearer");
        assertThat(tokens.getBody().get("accessToken")).isNotNull();
        assertThat((List<Object>) tokens.getBody().get("roles")).containsExactly((Object) "CUSTOMER");

        // ADR-010: 리프레시 토큰은 본문에 없습니다. 다시 넣으면 localStorage로 돌아가는 것입니다.
        assertThat(tokens.getBody()).doesNotContainKey("refreshToken");
        assertThat(RefreshCookies.setCookie(tokens)).isNotBlank();
    }

    @Test
    @DisplayName("리프레시 쿠키는 스크립트가 읽을 수 없고 인증 경로 밖으로 나가지 않는다")
    void refreshCookieCarriesItsProtections() {
        restTemplate.postForEntity(
                "/api/v1/members", Map.of("email", "auth-cookie@example.com", "password", "password1234"), Map.class);

        ResponseEntity<Map> tokens = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                Map.of("email", "auth-cookie@example.com", "password", "password1234"),
                Map.class);

        String cookie = RefreshCookies.setCookie(tokens);
        // HttpOnly가 이 결정의 전부입니다 — 스크립트가 뚫려도 리프레시 토큰은 나가지 않습니다.
        assertThat(cookie).containsIgnoringCase("HttpOnly");
        // SameSite=Lax가 CSRF를 막습니다. 별도 CSRF 토큰을 두지 않는 근거입니다.
        assertThat(cookie).contains("SameSite=Lax");
        // 다른 API 요청에 실릴 이유가 없습니다.
        assertThat(cookie).contains("Path=/api/v1/auth");
    }

    @Test
    @DisplayName("쿠키가 없으면 재발급할 수 없다 — 본문으로는 우회할 수 없다")
    void refreshWithoutTheCookieFails() {
        ApiAuth.registerAndLogin(restTemplate, "auth-nocookie@example.com", "password1234");
        ResponseEntity<Map> tokens = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                Map.of("email", "auth-nocookie@example.com", "password", "password1234"),
                Map.class);

        ResponseEntity<Map> missing = refresh(new HttpHeaders());

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().get("code")).isEqualTo("INVALID_REQUEST");

        // 본문으로 되돌아가는 경로가 남아 있으면 이 결정은 없는 것과 같습니다. 쿠키에서 값을
        // 꺼내 본문으로 보내도 받아주지 않아야 합니다.
        String token = RefreshCookies.value(tokens).split("=", 2)[1];
        ResponseEntity<Map> viaBody =
                restTemplate.postForEntity("/api/v1/auth/tokens/refresh", Map.of("refreshToken", token), Map.class);
        assertThat(viaBody.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 계정 존재 여부를 알려주지 않는다")
    void wrongPasswordDoesNotRevealAccountExistence() {
        restTemplate.postForEntity(
                "/api/v1/members", Map.of("email", "auth-known@example.com", "password", "password1234"), Map.class);

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
        assertThat(wrongPassword.getBody().get("code"))
                .isEqualTo(unknownAccount.getBody().get("code"));
        assertThat(wrongPassword.getBody().get("message"))
                .isEqualTo(unknownAccount.getBody().get("message"));
    }

    @Test
    @DisplayName("로그인 실패가 반복되면 계정을 잠근다")
    void repeatedFailuresLockTheAccount() {
        restTemplate.postForEntity(
                "/api/v1/members", Map.of("email", "auth-lock@example.com", "password", "password1234"), Map.class);

        // max-login-failures=3 (테스트 설정)
        for (int i = 0; i < 3; i++) {
            restTemplate.postForEntity(
                    "/api/v1/auth/tokens", Map.of("email", "auth-lock@example.com", "password", "wrong"), Map.class);
        }

        ResponseEntity<Map> afterLock = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                // 올바른 비밀번호여도 잠금이 우선입니다.
                Map.of("email", "auth-lock@example.com", "password", "password1234"),
                Map.class);

        // 상수 이름은 Spring Framework 7에서 UNPROCESSABLE_CONTENT로 바뀌었습니다. 값이 계약입니다.
        assertThat(afterLock.getStatusCode().value()).isEqualTo(422);
        assertThat(afterLock.getBody().get("code")).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("리프레시 토큰은 교환할 때마다 회전하고, 쓴 토큰은 다시 쓸 수 없다")
    void refreshTokensRotate() {
        restTemplate.postForEntity(
                "/api/v1/members", Map.of("email", "auth-refresh@example.com", "password", "password1234"), Map.class);
        ResponseEntity<Map> first = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                Map.of("email", "auth-refresh@example.com", "password", "password1234"),
                Map.class);
        // 브라우저가 하는 일을 손으로 합니다 — Set-Cookie를 받아 두었다가 Cookie로 돌려보냅니다.
        String cookie = RefreshCookies.value(first);

        ResponseEntity<Map> refreshed = refresh(RefreshCookies.carrying(cookie));
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 새 쿠키가 심어지고, 값이 달라야 회전한 것입니다.
        assertThat(RefreshCookies.value(refreshed)).isNotEqualTo(cookie);
        assertThat(refreshed.getBody()).doesNotContainKey("refreshToken");

        ResponseEntity<Map> reuse = refresh(RefreshCookies.carrying(cookie));
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reuse.getBody().get("message").toString()).contains("revoked");
    }

    /**
     * 회전의 "두 번 쓰면 거절"은 순차 요청에서만 참이었습니다. 동시에 오면 둘 다 SELECT에서 "철회 안
     * 됨"을 보고 둘 다 새 토큰을 받았습니다. 액세스 토큰을 메모리로 옮기자 탭마다 뜰 때 재발급을 하게
     * 되어 이 경로가 실제로 밟힙니다. 철회를 원자적으로 판정해야 합니다.
     */
    @Test
    @DisplayName("같은 리프레시 쿠키로 동시에 재발급하면 정확히 하나만 성공한다")
    void concurrentRefreshWithTheSameCookieSucceedsOnce() throws Exception {
        restTemplate.postForEntity(
                "/api/v1/members", Map.of("email", "auth-race@example.com", "password", "password1234"), Map.class);
        ResponseEntity<Map> first = restTemplate.postForEntity(
                "/api/v1/auth/tokens", Map.of("email", "auth-race@example.com", "password", "password1234"), Map.class);
        String cookie = RefreshCookies.value(first);

        int attempts = 8;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(attempts);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(attempts);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                barrier.await();
                return refresh(RefreshCookies.carrying(cookie)).getStatusCode().value();
            }));
        }
        long succeeded = 0;
        for (java.util.concurrent.Future<Integer> result : results) {
            if (result.get() == 200) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
    }

    @Test
    @DisplayName("로그아웃하면 리프레시 토큰이 모두 철회된다")
    void logoutRevokesRefreshTokens() {
        ApiAuth.Session session = ApiAuth.registerAndLogin(restTemplate, "auth-logout@example.com", "password1234");
        ResponseEntity<Map> tokens = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                Map.of("email", "auth-logout@example.com", "password", "password1234"),
                Map.class);
        String cookie = RefreshCookies.value(tokens);

        ResponseEntity<Void> loggedOut = restTemplate.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(ApiAuth.bearer(session.accessToken())),
                Void.class);

        assertThat(refresh(RefreshCookies.carrying(cookie)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 서버에서 철회하는 것만으로는 브라우저에 죽은 쿠키가 남습니다. 함께 지웁니다.
        assertThat(RefreshCookies.setCookie(loggedOut)).contains("Max-Age=0");
    }

    /** 브라우저처럼 쿠키만 들고 재발급을 요청합니다. 본문은 비어 있습니다. */
    private ResponseEntity<Map> refresh(HttpHeaders headers) {
        return restTemplate.exchange(
                "/api/v1/auth/tokens/refresh", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }

    @Test
    @DisplayName("위조된 토큰은 거부한다")
    void forgedTokenIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhdHRhY2tlciJ9.not-a-valid-signature");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/wallets/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("가입은 공개이지만 계좌 연결부터는 인증이 필요하다")
    void registrationIsPublicButTheRestIsNot() {
        ResponseEntity<Map> registration = restTemplate.postForEntity(
                "/api/v1/members", Map.of("email", "auth-public@example.com", "password", "password1234"), Map.class);
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

        ResponseEntity<String> scrape = restTemplate.getForEntity("/actuator/prometheus", String.class);
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
    @DisplayName("원장 검증 조회는 본인 지갑이라도 회원에게 열려 있지 않다")
    void ledgerVerificationIsOperatorOnly() {
        ApiAuth.Session session = ApiAuth.registerAndLogin(restTemplate, "auth-verify@example.com", "password1234");

        // 회원 경로(/api/v1/wallets) 아래에 있고 자기 지갑인데도 막혀야 합니다. 경로만 보고
        // 권한을 짐작하면 놓치는 자리라, 계약으로 고정합니다.
        ResponseEntity<Map> asMember = restTemplate.exchange(
                "/api/v1/wallets/" + session.walletId() + "/ledger-verification",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(session.accessToken())),
                Map.class);
        assertThat(asMember.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 같은 지갑을 운영자는 볼 수 있어야 합니다. 앞의 단언만 있으면 경로 오타로도 통과합니다.
        String viewerToken = ApiAuth.login(restTemplate, ApiAuth.OPS_VIEWER, ApiAuth.OPS_PASSWORD);
        ResponseEntity<Map> asOperator = restTemplate.exchange(
                "/api/v1/wallets/" + session.walletId() + "/ledger-verification",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(viewerToken)),
                Map.class);
        assertThat(asOperator.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("인증된 호출자의 잘못된 경로는 404이며 500으로 부풀리지 않는다")
    void unknownPathReturnsNotFound() {
        ApiAuth.Session session = ApiAuth.registerAndLogin(restTemplate, "auth-404@example.com", "password1234");

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
        assertThat(restTemplate
                        .getForEntity("/api/v1/does-not-exist", Map.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate
                        .getForEntity("/api/v1/wallets/" + UUID.randomUUID(), Map.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

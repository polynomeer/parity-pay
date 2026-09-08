package io.parity.pay.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.api.security.RecordedPasswordResetDelivery;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import java.util.Map;
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
 * 비밀번호 변경·재설정.
 *
 * <p>확인하는 것은 "바뀐다"가 아니라 바뀐 뒤에 남는 것들입니다. 옛 비밀번호가 죽는가, 남아 있던
 * 세션이 끊기는가, 쓰고 남은 재설정 토큰이 죽는가, 계정 존재 여부가 새어 나가지 않는가.
 *
 * <p>근거: docs/05-technical-design.md §11, NFR-007, docs/10-test-strategy.md §9
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordManagementTest extends AbstractIntegrationTest {

    private static final String EMAIL = "password-owner@example.com";
    private static final String OLD_PASSWORD = "old-password-1234";
    private static final String NEW_PASSWORD = "new-password-5678";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    @Autowired
    private RecordedPasswordResetDelivery delivery;

    private ApiAuth.Session session;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE password_reset_token, refresh_token, login_attempt, audit_log,
                         outbox_event, consumed_event, wallet_transaction,
                         ledger_entry, ledger_transaction, ledger_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        operatorBootstrap.createConfiguredOperators();
        session = ApiAuth.registerAndLogin(restTemplate, EMAIL, OLD_PASSWORD);
    }

    @Test
    @DisplayName("현재 비밀번호를 알면 바꿀 수 있고, 옛 비밀번호는 더 이상 통하지 않는다")
    void passwordChangeReplacesTheOldOne() {
        ResponseEntity<Void> changed = changePassword(session.accessToken(), OLD_PASSWORD, NEW_PASSWORD);

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(loginStatus(EMAIL, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
        assertThat(loginStatus(EMAIL, OLD_PASSWORD)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 남아 있던 리프레시 토큰이 모두 죽는다")
    void changingThePasswordRevokesExistingSessions() {
        // 다른 기기의 세션입니다.
        ResponseEntity<Map> otherDevice = restTemplate.postForEntity(
                "/api/v1/auth/tokens", Map.of("email", EMAIL, "password", OLD_PASSWORD), Map.class);
        String otherRefreshToken = (String) otherDevice.getBody().get("refreshToken");

        changePassword(session.accessToken(), OLD_PASSWORD, NEW_PASSWORD);

        ResponseEntity<Map> refresh = restTemplate.postForEntity(
                "/api/v1/auth/tokens/refresh", Map.of("refreshToken", otherRefreshToken), Map.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM refresh_token WHERE revoke_reason = 'PASSWORD_CHANGED'", Long.class))
                .isPositive();
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 바뀌지 않고 실패로 기록된다")
    void wrongCurrentPasswordIsRejectedAndCounted() {
        ResponseEntity<Map> response = changePasswordForError(session.accessToken(), "not-the-password", NEW_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
        // 이 경로로 잠금을 우회해 비밀번호를 추측할 수 없어야 합니다. 실패 횟수를 먼저 봅니다.
        // 로그인에 성공하면 실패 기록이 지워지므로 순서가 중요합니다.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT failed_count FROM login_attempt WHERE email = ?", Integer.class, EMAIL))
                .isEqualTo(1);
        assertThat(loginStatus(EMAIL, OLD_PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("같은 비밀번호로는 바꿀 수 없다")
    void changingToTheSamePasswordIsRejected() {
        ResponseEntity<Map> response = changePasswordForError(session.accessToken(), OLD_PASSWORD, OLD_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("가입하지 않은 이메일로 재설정을 요청해도 같은 응답이고 토큰은 생기지 않는다")
    void resetRequestDoesNotRevealWhetherTheAccountExists() {
        ResponseEntity<Void> known = requestReset(EMAIL);
        ResponseEntity<Void> unknown = requestReset("nobody@example.com");

        assertThat(known.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM password_reset_token", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("재설정 토큰으로 새 비밀번호를 설정하고 로그인한다")
    void resetTokenSetsANewPassword() {
        requestReset(EMAIL);
        String token = deliveredToken();

        ResponseEntity<Void> confirmed = confirmReset(token, NEW_PASSWORD);

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(loginStatus(EMAIL, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
        assertThat(loginStatus(EMAIL, OLD_PASSWORD)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("토큰은 한 번만 쓸 수 있다")
    void resetTokenIsSingleUse() {
        requestReset(EMAIL);
        String token = deliveredToken();
        confirmReset(token, NEW_PASSWORD);

        ResponseEntity<Map> second = confirmResetForError(token, "another-password-999");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(loginStatus(EMAIL, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("만료된 토큰은 쓸 수 없다")
    void expiredResetTokenIsRejected() {
        requestReset(EMAIL);
        String token = deliveredToken();
        jdbcTemplate.update("UPDATE password_reset_token SET expires_at = now() - interval '1 minute'");

        ResponseEntity<Map> response = confirmResetForError(token, NEW_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(loginStatus(EMAIL, OLD_PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("새 토큰을 발급하면 이전 토큰은 죽는다")
    void issuingANewTokenInvalidatesThePreviousOne() {
        requestReset(EMAIL);
        String first = deliveredToken();
        requestReset(EMAIL);
        String second = deliveredToken();
        assertThat(second).isNotEqualTo(first);

        assertThat(confirmResetForError(first, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(confirmReset(second, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("재설정하면 남아 있던 세션과 남은 토큰이 함께 죽는다")
    void resetRevokesSessionsAndRemainingTokens() {
        String refreshToken = issueRefreshToken();
        requestReset(EMAIL);
        String token = deliveredToken();

        confirmReset(token, NEW_PASSWORD);

        ResponseEntity<Map> refresh = restTemplate.postForEntity(
                "/api/v1/auth/tokens/refresh", Map.of("refreshToken", refreshToken), Map.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM password_reset_token WHERE used_at IS NULL"
                                + " AND invalidated_at IS NULL",
                        Long.class))
                .isZero();
    }

    @Test
    @DisplayName("잠긴 계정은 재설정으로 풀린다")
    void resetClearsTheLoginLock() {
        for (int i = 0; i < 3; i++) {
            loginStatus(EMAIL, "wrong-password-" + i);
        }
        // LIMIT_EXCEEDED는 422입니다. 근거: docs/08-db-api-event-spec.md §5
        assertThat(loginStatus(EMAIL, OLD_PASSWORD)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        requestReset(EMAIL);
        confirmReset(deliveredToken(), NEW_PASSWORD);

        assertThat(loginStatus(EMAIL, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("토큰 없이 비밀번호를 바꿀 수 없다")
    void changingPasswordRequiresAuthentication() {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/auth/password",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD), new HttpHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("토큰 원문은 저장하지 않는다")
    void rawTokenIsNotStored() {
        requestReset(EMAIL);
        String token = deliveredToken();

        Long matches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_token WHERE token_hash = ?", Long.class, token);

        assertThat(matches).isZero();
    }

    private String issueRefreshToken() {
        ResponseEntity<Map> tokens = restTemplate.postForEntity(
                "/api/v1/auth/tokens", Map.of("email", EMAIL, "password", OLD_PASSWORD), Map.class);
        return (String) tokens.getBody().get("refreshToken");
    }

    /** 사용자가 메일을 확인하는 자리입니다. 테스트 프로필에서는 메모리에 담깁니다. */
    private String deliveredToken() {
        return delivery.lastTokenFor(EMAIL).orElseThrow();
    }

    private ResponseEntity<Void> changePassword(String accessToken, String current, String next) {
        return restTemplate.exchange(
                "/api/v1/auth/password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("currentPassword", current, "newPassword", next), ApiAuth.bearer(accessToken)),
                Void.class);
    }

    private ResponseEntity<Map> changePasswordForError(String accessToken, String current, String next) {
        return restTemplate.exchange(
                "/api/v1/auth/password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("currentPassword", current, "newPassword", next), ApiAuth.bearer(accessToken)),
                Map.class);
    }

    private ResponseEntity<Void> requestReset(String email) {
        return restTemplate.postForEntity("/api/v1/auth/password-reset", Map.of("email", email), Void.class);
    }

    private ResponseEntity<Void> confirmReset(String token, String newPassword) {
        return restTemplate.postForEntity(
                "/api/v1/auth/password-reset/confirm", Map.of("token", token, "newPassword", newPassword), Void.class);
    }

    private ResponseEntity<Map> confirmResetForError(String token, String newPassword) {
        return restTemplate.postForEntity(
                "/api/v1/auth/password-reset/confirm", Map.of("token", token, "newPassword", newPassword), Map.class);
    }

    private HttpStatus loginStatus(String email, String password) {
        return (HttpStatus) restTemplate
                .postForEntity("/api/v1/auth/tokens", Map.of("email", email, "password", password), Map.class)
                .getStatusCode();
    }
}

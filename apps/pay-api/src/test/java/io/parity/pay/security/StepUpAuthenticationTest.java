package io.parity.pay.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 재인증(step-up).
 *
 * <p>원장을 움직이는 운영 작업은 세션이 살아 있다는 것만으로는 부족합니다. 방금 비밀번호를 다시
 * 확인했다는 짧은 증거가 있어야 합니다. 근거: docs/14-frontend-design.md §13 열린 질문 4
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class StepUpAuthenticationTest extends AbstractIntegrationTest {

    private static final String ADJUSTMENT_PATH =
            "/api/v1/admin/reconciliation/mismatches/" + UUID.randomUUID() + "/adjustments";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String operatorToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE refresh_token, login_attempt, member CASCADE");
        operatorBootstrap.createConfiguredOperators();
        operatorToken = ApiAuth.login(restTemplate, ApiAuth.OPS_OPERATOR, ApiAuth.OPS_PASSWORD);
    }

    @Test
    @DisplayName("비밀번호를 다시 확인하면 짧게 사는 증거를 받는다")
    void reauthIssuesAShortLivedProof() {
        ResponseEntity<Map> response = reauth(ApiAuth.OPS_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("reauthToken")).isNotNull();
        // 5분이면 승인 화면에서 비밀번호를 치고 제출하기에 충분하고, 자리를 비운 단말에는 부족합니다.
        assertThat(((Number) response.getBody().get("expiresIn")).longValue()).isLessThanOrEqualTo(300L);
    }

    @Test
    @DisplayName("증거 없이는 보정 분개를 만들 수 없다 — 세션이 있어도")
    void adjustmentWithoutProofIsRefused() {
        ResponseEntity<Map> response = adjust(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("액세스 토큰은 증거가 아니다 — 같은 키로 서명됐어도")
    void accessTokenIsNotAProof() {
        ResponseEntity<Map> response = adjust(operatorToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("다른 사람의 증거로 내 작업을 승인할 수 없다")
    void anotherPersonsProofIsRefused() {
        String approverToken = ApiAuth.login(restTemplate, ApiAuth.OPS_APPROVER, ApiAuth.OPS_PASSWORD);
        String approverProof = (String) restTemplate
                .exchange(
                        "/api/v1/auth/reauth",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("password", ApiAuth.OPS_PASSWORD), ApiAuth.bearer(approverToken)),
                        Map.class)
                .getBody()
                .get("reauthToken");

        // 운영자 세션으로, 승인자의 증거를 들고 옵니다.
        ResponseEntity<Map> response = adjust(approverProof);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("유효한 증거가 있으면 재인증 관문을 지나 업무 검사로 넘어간다")
    void validProofPassesTheGate() {
        String proof = (String) reauth(ApiAuth.OPS_PASSWORD).getBody().get("reauthToken");

        ResponseEntity<Map> response = adjust(proof);

        // 관문을 지났다는 증거는 그 다음 검사가 답하는 것입니다 — 없는 불일치라 404입니다.
        // 관문에서 막혔다면 400 INVALID_REQUEST였을 것입니다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("재인증 실패는 로그인 실패와 같이 세어 잠금을 우회하지 못한다")
    void failedReauthCountsTowardLockout() {
        // max-login-failures=3 (테스트 설정)
        for (int i = 0; i < 3; i++) {
            assertThat(reauth("wrong-password").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<Map> locked = restTemplate.postForEntity(
                "/api/v1/auth/tokens",
                Map.of("email", ApiAuth.OPS_OPERATOR, "password", ApiAuth.OPS_PASSWORD),
                Map.class);

        assertThat(locked.getStatusCode().value()).isEqualTo(422);
        assertThat(locked.getBody().get("code")).isEqualTo("LIMIT_EXCEEDED");
    }

    private ResponseEntity<Map> reauth(String password) {
        return restTemplate.exchange(
                "/api/v1/auth/reauth",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", password), ApiAuth.bearer(operatorToken)),
                Map.class);
    }

    private ResponseEntity<Map> adjust(String proof) {
        HttpHeaders headers = ApiAuth.bearer(operatorToken);
        // 실제 승인자입니다. 없는 승인자면 이중 승인 검사가 먼저 400을 내어 관문의 400과 구분되지 않습니다.
        String approverId = jdbcTemplate.queryForObject(
                "SELECT member_id FROM member WHERE email = ?", String.class, ApiAuth.OPS_APPROVER);
        headers.set("X-Approver-Id", approverId);
        if (proof != null) {
            headers.set("X-Reauth-Token", proof);
        }
        return restTemplate.exchange(
                ADJUSTMENT_PATH,
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "reason", "test",
                                "debitAccount", "BANK_DEPOSIT",
                                "creditAccount", "USER_PAY_MONEY",
                                "amount", 1),
                        headers),
                Map.class);
    }
}

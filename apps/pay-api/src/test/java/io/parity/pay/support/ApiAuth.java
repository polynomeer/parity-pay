package io.parity.pay.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * API 테스트용 인증 도우미.
 *
 * <p>테스트가 헤더에 사용자 ID를 적어 넣는 대신 실제 로그인 흐름을 거치게 합니다. 인증을 우회하는
 * 테스트는 인증이 실제로 동작하는지 알려주지 못합니다.
 */
public final class ApiAuth {

    public static final String OPS_VIEWER = "ops-viewer@test.local";
    public static final String OPS_OPERATOR = "ops-operator@test.local";
    public static final String OPS_APPROVER = "ops-approver@test.local";
    public static final String OPS_PASSWORD = "test-ops-password";

    private ApiAuth() {}

    /** 회원을 만들고 로그인해 토큰과 식별자를 돌려줍니다. */
    public static Session registerAndLogin(TestRestTemplate rest, String email, String password) {
        ResponseEntity<Map> member = rest.postForEntity(
                "/api/v1/members", Map.of("email", email, "password", password), Map.class);
        assertThat(member.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String accessToken = login(rest, email, password);
        return new Session(
                UUID.fromString((String) member.getBody().get("memberId")),
                UUID.fromString((String) member.getBody().get("walletId")),
                accessToken);
    }

    public static String login(TestRestTemplate rest, String email, String password) {
        ResponseEntity<Map> tokens = rest.postForEntity(
                "/api/v1/auth/tokens", Map.of("email", email, "password", password), Map.class);
        assertThat(tokens.getStatusCode())
                .as("login should succeed for %s", email)
                .isEqualTo(HttpStatus.OK);
        return (String) tokens.getBody().get("accessToken");
    }

    public static HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }

    public static HttpHeaders bearer(String accessToken, String idempotencyKey) {
        HttpHeaders headers = bearer(accessToken);
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    public record Session(UUID memberId, UUID walletId, String accessToken) {}
}

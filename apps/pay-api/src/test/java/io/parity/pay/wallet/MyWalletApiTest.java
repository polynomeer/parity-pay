package io.parity.pay.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
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
 * FR-004: 호출자 자신의 지갑 조회.
 *
 * <p>이 경로가 없던 동안 `walletId`는 가입 응답에만 있었고 토큰에도 들어 있지 않았습니다. 즉 다른
 * 기기에서 로그인한 클라이언트는 자기 지갑을 조회할 방법이 없었습니다. 프론트엔드(FE-M1)를
 * 붙이면서 드러난 공백입니다.
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class MyWalletApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, top_up,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
    }

    @Test
    @DisplayName("가입 응답의 지갑 ID 없이도 자기 잔액을 조회할 수 있다")
    void returnsCallerWallet() {
        ApiAuth.Session session = ApiAuth.registerAndLogin(restTemplate, "me-wallet@example.com", "password1234");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/wallets/me",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(session.accessToken())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 토큰만으로 도달한 지갑이 가입 때 만들어진 그 지갑이어야 합니다.
        assertThat(UUID.fromString((String) response.getBody().get("walletId"))).isEqualTo(session.walletId());
        assertThat(response.getBody().get("available")).isEqualTo(0);
        assertThat(response.getBody().get("currency")).isEqualTo("KRW");
        // 스냅샷 기준 시각이 있어야 화면이 "언제 기준인지"를 밝힐 수 있습니다(FE-005).
        assertThat(response.getBody().get("asOf")).isNotNull();
    }

    @Test
    @DisplayName("다른 사람의 지갑이 아니라 자기 지갑만 나온다")
    void neverReturnsAnotherMembersWallet() {
        ApiAuth.Session first = ApiAuth.registerAndLogin(restTemplate, "me-first@example.com", "password1234");
        ApiAuth.Session second = ApiAuth.registerAndLogin(restTemplate, "me-second@example.com", "password1234");
        assertThat(first.walletId()).isNotEqualTo(second.walletId());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/wallets/me",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(second.accessToken())),
                Map.class);

        assertThat(UUID.fromString((String) response.getBody().get("walletId"))).isEqualTo(second.walletId());
    }

    @Test
    @DisplayName("토큰 없이는 조회할 수 없다")
    void requiresAuthentication() {
        ResponseEntity<Map> response = restTemplate.exchange("/api/v1/wallets/me", HttpMethod.GET, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

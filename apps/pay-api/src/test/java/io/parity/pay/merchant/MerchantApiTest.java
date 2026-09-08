package io.parity.pay.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 판매자 권한.
 *
 * <p>여기서 확인하는 것은 "판매자가 자기 정산을 본다"보다 "판매자가 남의 정산을 못 본다"입니다.
 * 조회 범위가 요청이 아니라 토큰에서 결정되는지가 핵심입니다.
 *
 * <p>정산 행은 SQL로 만듭니다. 결제·구매확정·정산 계산을 전부 거치는 것은 정산 계산 테스트의
 * 몫이고, 여기서 확인할 것은 권한과 범위입니다.
 *
 * <p>근거: FR-016, docs/02-prd.md §6, docs/10-test-strategy.md §9
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    private String sellerEmail;
    private String otherSellerEmail;
    private UUID merchantId;
    private UUID otherMerchantId;
    private UUID ownSettlementId;
    private UUID otherSettlementId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt, audit_log,
                         settlement_item, settlement, merchant,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment, top_up,
                         outbox_event, consumed_event, wallet_transaction,
                         mock_bank_withdrawal, mock_bank_account,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        operatorBootstrap.createConfiguredOperators();

        sellerEmail = "seller@example.com";
        otherSellerEmail = "other-seller@example.com";
        ApiAuth.registerAndLogin(restTemplate, sellerEmail, "password1234");
        ApiAuth.registerAndLogin(restTemplate, otherSellerEmail, "password1234");

        merchantId = registerMerchant(sellerEmail, "커피가게");
        otherMerchantId = registerMerchant(otherSellerEmail, "빵집");

        ownSettlementId = insertSettlement(merchantId, 100_000L);
        otherSettlementId = insertSettlement(otherMerchantId, 999_000L);
    }

    @Test
    @DisplayName("판매자는 자기 정산만 목록에서 본다")
    void merchantSeesOnlyItsOwnSettlements() {
        ResponseEntity<List> settlements = get("/api/v1/merchant/settlements", merchantToken(sellerEmail), List.class);

        assertThat(settlements.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> body = settlements.getBody();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("settlementId")).isEqualTo(ownSettlementId.toString());
        assertThat(body.get(0).get("netAmount")).isEqualTo(100_000);
        // 외부 지급 참조는 판매자에게 주지 않습니다.
        assertThat(body.get(0)).doesNotContainKey("externalReferenceId");
    }

    @Test
    @DisplayName("다른 판매자의 정산은 식별자를 알아도 보이지 않는다")
    void anotherMerchantsSettlementIsNotFound() {
        ResponseEntity<Map> response =
                get("/api/v1/merchant/settlements/" + otherSettlementId, merchantToken(sellerEmail), Map.class);

        // 403이 아니라 404입니다. 볼 수 없다고 알려주면 그 정산이 존재한다고 알려주는 셈입니다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("자기 정산 단건은 조회된다")
    void ownSettlementIsVisible() {
        ResponseEntity<Map> response =
                get("/api/v1/merchant/settlements/" + ownSettlementId, merchantToken(sellerEmail), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("netAmount")).isEqualTo(100_000);
    }

    @Test
    @DisplayName("내 판매자 정보로 자기 merchantId를 확인한다")
    void merchantCanReadItsOwnProfile() {
        ResponseEntity<Map> response = get("/api/v1/merchant/me", merchantToken(sellerEmail), Map.class);

        assertThat(response.getBody().get("merchantId")).isEqualTo(merchantId.toString());
        assertThat(response.getBody().get("name")).isEqualTo("커피가게");
    }

    @Test
    @DisplayName("일반 사용자는 판매자 경로에 접근할 수 없다")
    void customerCannotUseMerchantApi() {
        ApiAuth.Session customer = ApiAuth.registerAndLogin(restTemplate, "buyer@example.com", "password1234");

        ResponseEntity<Map> response = get("/api/v1/merchant/settlements", customer.accessToken(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("판매자는 운영자 정산 경로를 쓸 수 없다")
    void merchantCannotUseTheAdminSettlementApi() {
        ResponseEntity<Map> response =
                get("/api/v1/admin/settlements?merchantId=" + merchantId, merchantToken(sellerEmail), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("판매자 등록은 운영자만 할 수 있다")
    void onlyOperatorsCanRegisterMerchants() {
        ApiAuth.Session customer = ApiAuth.registerAndLogin(restTemplate, "self-promoter@example.com", "password1234");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/admin/merchants",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("name", "내가 만든 가게", "ownerEmail", "self-promoter@example.com", "reason", "직접 등록"),
                        ApiAuth.bearer(customer.accessToken())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("같은 회원을 두 판매자로 등록할 수 없다")
    void aMemberRepresentsAtMostOneMerchant() {
        ResponseEntity<Map> response = registerMerchantResponse(sellerEmail, "두 번째 가게");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("MERCHANT 역할만 있고 판매자 등록이 없으면 거부한다")
    void roleWithoutRegistrationIsRejected() {
        // 역할만 손으로 붙인 상태입니다. 역할은 문이고 등록은 신원이므로 둘 다 있어야 합니다.
        ApiAuth.Session orphan = ApiAuth.registerAndLogin(restTemplate, "role-only@example.com", "password1234");
        jdbcTemplate.update("UPDATE member SET roles = 'CUSTOMER,MERCHANT' WHERE member_id = ?", orphan.memberId());
        String token = ApiAuth.login(restTemplate, "role-only@example.com", "password1234");

        ResponseEntity<Map> response = get("/api/v1/merchant/settlements", token, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("RISK_BLOCKED");
    }

    @Test
    @DisplayName("판매자 등록은 감사 로그에 남는다")
    void registrationIsAudited() {
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT actor, action, resource_id, after_state FROM audit_log"
                        + " WHERE action = 'MERCHANT_REGISTER' AND resource_id = ?",
                merchantId.toString());

        assertThat(audit.get("actor")).isEqualTo(ApiAuth.OPS_OPERATOR);
        assertThat(audit.get("after_state")).isEqualTo("owner=" + sellerEmail);
    }

    private <T> ResponseEntity<T> get(String path, String token, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(ApiAuth.bearer(token)), type);
    }

    /** 역할이 바뀐 뒤에 발급된 토큰이어야 새 권한이 담깁니다. */
    private String merchantToken(String email) {
        return ApiAuth.login(restTemplate, email, "password1234");
    }

    private UUID registerMerchant(String ownerEmail, String name) {
        ResponseEntity<Map> response = registerMerchantResponse(ownerEmail, name);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("merchantId"));
    }

    private ResponseEntity<Map> registerMerchantResponse(String ownerEmail, String name) {
        String operatorToken = ApiAuth.login(restTemplate, ApiAuth.OPS_OPERATOR, ApiAuth.OPS_PASSWORD);
        return restTemplate.exchange(
                "/api/v1/admin/merchants",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("name", name, "ownerEmail", ownerEmail, "reason", "판매자 온보딩"),
                        ApiAuth.bearer(operatorToken)),
                Map.class);
    }

    private UUID insertSettlement(UUID merchant, long netAmount) {
        UUID settlementId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO settlement
                    (settlement_id, merchant_id, period_start, period_end, gross_amount,
                     cancellation_amount, fee_amount, adjustment_amount, net_amount, currency,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, 0, 0, ?, 'KRW', 'CALCULATED', ?, ?)
                """,
                settlementId,
                merchant,
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-07"),
                netAmount,
                netAmount,
                Timestamp.from(Instant.parse("2026-09-08T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-08T00:00:00Z")));
        return settlementId;
    }
}

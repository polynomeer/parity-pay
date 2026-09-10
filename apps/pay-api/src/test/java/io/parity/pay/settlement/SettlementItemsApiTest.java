package io.parity.pay.settlement;

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
 * 정산 항목 노출 (FE-M7).
 *
 * <p>지금까지 정산 응답은 합계 금액만 줬습니다. 판매자가 "이 금액이 어디서 왔는가"를 확인할
 * 방법이 없었습니다.
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SettlementItemsApiTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private io.parity.pay.api.security.OperatorBootstrap operatorBootstrap;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                """
                TRUNCATE refresh_token, login_attempt, settlement_item, settlement,
                         order_confirmation, merchant, outbox_event,
                         ledger_entry, ledger_transaction, ledger_account,
                         idempotency_record, payment_cancellation, payment, top_up,
                         wallet_balance, bank_account, wallet, member CASCADE
                """);
        operatorBootstrap.createConfiguredOperators();
    }

    @Test
    @DisplayName("판매자는 자기 정산의 항목을 볼 수 있다")
    void merchantSeesOwnItems() {
        Fixture fixture = createSettlementWithItems();

        ResponseEntity<List> response = restTemplate.exchange(
                "/api/v1/merchant/settlements/" + fixture.settlementId() + "/items",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(fixture.merchantToken())),
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> items = response.getBody();
        assertThat(items).hasSize(2);
        // 근거를 추적하려면 어떤 결제에서 왔는지가 있어야 합니다.
        assertThat(items).allSatisfy(item -> {
            Map<?, ?> row = (Map<?, ?>) item;
            assertThat(row.get("paymentId")).isNotNull();
            assertThat((String) row.get("sourceReferenceId")).isNotBlank();
        });
    }

    @Test
    @DisplayName("항목 합계가 정산 순액과 같다 (INV-008)")
    void itemsSumToNetAmount() {
        Fixture fixture = createSettlementWithItems();

        ResponseEntity<List> items = restTemplate.exchange(
                "/api/v1/merchant/settlements/" + fixture.settlementId() + "/items",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(fixture.merchantToken())),
                List.class);
        ResponseEntity<Map> header = restTemplate.exchange(
                "/api/v1/merchant/settlements/" + fixture.settlementId(),
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(fixture.merchantToken())),
                Map.class);

        long sum = items.getBody().stream()
                .mapToLong(item -> ((Number) ((Map<?, ?>) item).get("amount")).longValue())
                .sum();
        // 화면이 항목을 더한 값과 헤더의 순액이 다르면 판매자는 어느 쪽도 믿을 수 없습니다.
        assertThat(sum).isEqualTo(((Number) header.getBody().get("netAmount")).longValue());
    }

    @Test
    @DisplayName("남의 정산 항목은 볼 수 없다")
    void cannotSeeOtherMerchantsItems() {
        Fixture fixture = createSettlementWithItems();
        ApiAuth.Session stranger = ApiAuth.registerAndLogin(
                restTemplate, "stranger-" + UUID.randomUUID() + "@example.com", "password1234");

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/merchant/settlements/" + fixture.settlementId() + "/items",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(stranger.accessToken())),
                Map.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    private record Fixture(UUID settlementId, String merchantToken) {}

    /** 정산 하나와 항목 둘(매출 + 수수료)을 만듭니다. */
    private Fixture createSettlementWithItems() {
        String operatorToken = ApiAuth.login(restTemplate, ApiAuth.OPS_OPERATOR, ApiAuth.OPS_PASSWORD);
        String email = "merchant-" + UUID.randomUUID() + "@example.com";
        // 회원을 먼저 만들어야 운영자가 그 이메일로 판매자를 연결할 수 있습니다.
        ApiAuth.registerAndLogin(restTemplate, email, "password1234");

        ResponseEntity<Map> created = restTemplate.exchange(
                "/api/v1/admin/merchants",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("name", "테스트 판매자", "ownerEmail", email, "reason", "FE-M7 시험"),
                        ApiAuth.bearer(operatorToken)),
                Map.class);
        UUID merchantId = UUID.fromString((String) created.getBody().get("merchantId"));

        UUID settlementId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO settlement (settlement_id, merchant_id, period_start, period_end,
                                        gross_amount, cancellation_amount, fee_amount, adjustment_amount,
                                        net_amount, currency, status, created_at, updated_at)
                VALUES (?, ?, DATE '2026-09-01', DATE '2026-09-07', 50000, 0, 5000, 0, 45000, 'KRW',
                        'CALCULATED', now(), now())
                """,
                settlementId,
                merchantId);
        insertItem(settlementId, merchantId, "SALE", 50_000);
        insertItem(settlementId, merchantId, "FEE", -5_000);

        // 판매자 역할을 받으려면 다시 로그인해야 합니다.
        return new Fixture(settlementId, ApiAuth.login(restTemplate, email, "password1234"));
    }

    private void insertItem(UUID settlementId, UUID merchantId, String type, long amount) {
        jdbcTemplate.update(
                """
                INSERT INTO settlement_item (item_id, settlement_id, merchant_id, payment_id, item_type,
                                             amount, currency, status, source_reference_id, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'KRW', 'SETTLED', ?, now(), now())
                """,
                UUID.randomUUID(),
                settlementId,
                merchantId,
                UUID.randomUUID(),
                type,
                amount,
                type + "-" + UUID.randomUUID());
    }
}

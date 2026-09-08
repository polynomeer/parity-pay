package io.parity.pay.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.parity.pay.settlement.adapter.in.messaging.SettlementItemConsumer;
import io.parity.pay.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정산 소비자의 순서 의존성.
 *
 * <p>발행기가 왜 Aggregate 안의 순서를 지켜야 하는지 보여 주는 시험입니다. 정산 소비자는 취소를
 * 받았을 때 그 결제의 판매 항목이 <b>이미 있는지</b>로 처리를 가릅니다. 항목이 없으면 "구매확정 전에
 * 취소된 결제"로 보고 아무것도 만들지 않습니다. 그 판단은 취소가 구매확정보다 먼저 도착했다는
 * 사실이 곧 업무상 먼저 일어났다는 뜻일 때만 옳습니다.
 *
 * <p>발행기를 여러 대 돌리면 그 전제가 깨집니다(M-001에서 400건 중 18건 역전). 그래서 선점 쿼리가
 * 파티션 키별 선두만 집어가도록 바꿨고, 이 테스트는 그 규칙이 지키는 금액이 얼마인지를 기록합니다.
 *
 * <p>근거: ADR-005, ADR-006, reports/11 M-001, docs/04-payment-policy.md §8
 */
class SettlementOrderDependencyTest extends AbstractIntegrationTest {

    private static final long APPROVED = 10_000L;
    private static final long CANCELED = 4_000L;

    @Autowired
    private SettlementItemConsumer consumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE settlement_item, consumed_event CASCADE");
    }

    @Test
    @DisplayName("구매확정 다음에 취소가 오면 정산 순액에서 취소분과 수수료가 함께 정리된다")
    void inOrderDeliveryProducesTheCorrectNetAmount() {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        deliver(orderConfirmed(paymentId, merchantId, APPROVED));
        deliver(cancellationCompleted(paymentId, merchantId, CANCELED));

        // 판매 10,000 - 수수료 1,000 - 취소 4,000 + 수수료 환급 400 = 5,400
        assertThat(netAmount(paymentId)).isEqualTo(5_400L);
    }

    @Test
    @DisplayName("취소가 구매확정보다 먼저 오면 취소가 정산에 반영되지 않는다")
    void reorderedDeliveryOverpaysTheMerchant() {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        // 같은 두 사실을, 순서만 바꿔 전달합니다.
        deliver(cancellationCompleted(paymentId, merchantId, CANCELED));
        deliver(orderConfirmed(paymentId, merchantId, APPROVED));

        // 취소는 "확정 전 취소"로 오해되어 버려지고, 판매 항목은 취소를 모르는 전액으로 남습니다.
        // 판매자에게 3,600원이 더 나갑니다. 이것이 발행 순서가 지키는 금액입니다.
        assertThat(netAmount(paymentId)).isEqualTo(9_000L);
        assertThat(netAmount(paymentId) - 5_400L).isEqualTo(3_600L);
    }

    private void deliver(Map<String, Object> envelope) {
        transactionTemplate.executeWithoutResult(status -> consumer.consume(objectMapper.valueToTree(envelope)));
    }

    private Map<String, Object> orderConfirmed(UUID paymentId, UUID merchantId, long settleable) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", "order-" + paymentId);
        payload.put("paymentId", paymentId.toString());
        payload.put("merchantId", merchantId.toString());
        payload.put("settleableAmount", settleable);
        payload.put("currency", "KRW");
        payload.put("confirmedAt", Instant.now().toString());
        return envelope("OrderConfirmed", payload);
    }

    private Map<String, Object> cancellationCompleted(UUID paymentId, UUID merchantId, long amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cancellationId", UUID.randomUUID().toString());
        payload.put("paymentId", paymentId.toString());
        payload.put("walletId", UUID.randomUUID().toString());
        payload.put("merchantId", merchantId.toString());
        payload.put("amount", amount);
        payload.put("currency", "KRW");
        payload.put("ledgerTransactionId", UUID.randomUUID().toString());
        return envelope("PaymentCancellationCompleted", payload);
    }

    private Map<String, Object> envelope(String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        return envelope;
    }

    private long netAmount(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM settlement_item WHERE payment_id = ?", Long.class, paymentId);
    }
}

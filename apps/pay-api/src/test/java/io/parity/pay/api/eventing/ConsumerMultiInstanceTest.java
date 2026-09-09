package io.parity.pay.api.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import io.parity.pay.settlement.adapter.in.messaging.SettlementItemConsumer;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.wallet.adapter.in.messaging.WalletTransactionProjectionConsumer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 같은 소비자 그룹의 다중 인스턴스 경쟁.
 *
 * <p>ADR-006은 전달이 at-least-once이고 중복은 소비자가 흡수한다고 주장합니다. 지금까지의 시험은
 * 같은 봉투를 <b>차례로</b> 세 번 전달하는 것이었습니다(F-004·T-008). 차례로 오는 중복은 두 번째
 * 호출이 이미 커밋된 소비 이력을 보므로 쉬운 경우입니다.
 *
 * <p>여기서는 <b>동시에</b> 전달합니다. 재분배(rebalance) 직후 같은 이벤트가 두 인스턴스에 걸치는
 * 상황이 이것이고, 조회 후 삽입이었다면 여기서 무너집니다.
 *
 * <p>스레드마다 트랜잭션과 커넥션이 따로이므로 DB 관점에서는 인스턴스가 여럿인 것과 같습니다.
 *
 * <p>근거: ADR-006, docs/09-consistency-recovery.md §6
 */
class ConsumerMultiInstanceTest extends AbstractIntegrationTest {

    private static final int CONSUMERS = 8;

    @Autowired
    private WalletTransactionProjectionConsumer projectionConsumer;

    @Autowired
    private SettlementItemConsumer settlementConsumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE wallet_transaction, consumed_event, settlement_item CASCADE");
    }

    @Test
    @DisplayName("같은 이벤트를 인스턴스 8대가 동시에 소비해도 내역은 한 줄만 생긴다")
    void concurrentDeliveryOfTheSameEventProducesOneEffect() throws Exception {
        JsonNode envelope = topUpCompleted();

        runConcurrently(() -> {
            projectionConsumer.consume(envelope);
            return null;
        });

        assertThat(walletTransactionCount()).isEqualTo(1L);
        assertThat(consumedCount(WalletTransactionProjectionConsumer.CONSUMER_NAME))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("소비 이력이 없어도 업무 유니크 키가 동시 중복을 막는다")
    void theBusinessUniqueKeyHoldsWithoutTheConsumedHistory() throws Exception {
        JsonNode envelope = topUpCompleted();

        // 소비 이력이 유실된 상황을 만듭니다. 두 번째 방어선만 남습니다.
        runConcurrently(() -> {
            jdbcTemplate.update(
                    "DELETE FROM consumed_event WHERE consumer_name = ?",
                    WalletTransactionProjectionConsumer.CONSUMER_NAME);
            projectionConsumer.consume(envelope);
            return null;
        });

        assertThat(walletTransactionCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("소비자가 서로 다르면 같은 이벤트를 각자 한 번씩 소비한다")
    void differentConsumersDoNotBlockEachOther() throws Exception {
        JsonNode envelope = cancellationCompleted();

        runConcurrently(() -> {
            // 두 소비자를 함께 호출해 같은 이벤트를 동시에 집게 만듭니다.
            projectionConsumer.consume(envelope);
            settlementConsumer.consume(envelope);
            return null;
        });

        assertThat(consumedCount(WalletTransactionProjectionConsumer.CONSUMER_NAME))
                .isEqualTo(1L);
        assertThat(consumedCount(SettlementItemConsumer.CONSUMER_NAME)).isEqualTo(1L);
        // 소비자별 이력이 따로이므로 프로젝션은 자기 몫을 그대로 처리합니다.
        assertThat(walletTransactionCount()).isEqualTo(1L);
    }

    /** 모든 소비자를 같은 순간에 풀어놓습니다. 순차 실행이면 이 시험은 F-004와 다르지 않습니다. */
    private void runConcurrently(Callable<Void> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(CONSUMERS)) {
            List<Future<Void>> futures = IntStream.range(0, CONSUMERS)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return work.call();
                    }))
                    .toList();
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    failures.add(e.getCause() == null ? e : e.getCause());
                }
            }
            // 중복을 막는 것과 예외로 실패하는 것은 다릅니다. 소비자가 던지면 Kafka는 그 이벤트를
            // 다시 배달하고, 같은 일이 반복됩니다.
            assertThat(failures).isEmpty();
        }
    }

    private JsonNode topUpCompleted() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("topUpId", UUID.randomUUID().toString());
        payload.put("walletId", UUID.randomUUID().toString());
        payload.put("amount", 10_000L);
        payload.put("currency", "KRW");
        payload.put("ledgerTransactionId", UUID.randomUUID().toString());
        return objectMapper.valueToTree(envelope("TopUpCompleted", payload));
    }

    private JsonNode cancellationCompleted() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cancellationId", UUID.randomUUID().toString());
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("walletId", UUID.randomUUID().toString());
        payload.put("merchantId", UUID.randomUUID().toString());
        payload.put("amount", 4_000L);
        payload.put("currency", "KRW");
        payload.put("ledgerTransactionId", UUID.randomUUID().toString());
        return objectMapper.valueToTree(envelope("PaymentCancellationCompleted", payload));
    }

    private Map<String, Object> envelope(String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        return envelope;
    }

    private long walletTransactionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM wallet_transaction", Long.class);
    }

    private long consumedCount(String consumerName) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM consumed_event WHERE consumer_name = ?", Long.class, consumerName);
    }
}

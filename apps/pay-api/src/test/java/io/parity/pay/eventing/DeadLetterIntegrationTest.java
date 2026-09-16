package io.parity.pay.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.parity.pay.ParityPayApplication;
import io.parity.pay.api.security.OperatorBootstrap;
import io.parity.pay.support.AbstractIntegrationTest;
import io.parity.pay.support.ApiAuth;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 결함 M(reports/11 M-018) 회귀 시험 — 소비자가 처리할 수 없는 레코드는 조용히 버려지지 않는다.
 *
 * <p>고치기 전에는 spring-kafka 기본 핸들러가 10회 즉시 재시도한 뒤 ERROR 로그 한 줄만 남기고 오프셋을
 * 넘겼습니다. 지금은 {@code dead_letter_event}에 남고 DLT 토픽으로 가며 지표가 오르고, 운영자가 원
 * 토픽으로 다시 흘려보낼 수 있습니다. 같은 파티션의 뒤 이벤트는 계속 흘러야 합니다.
 *
 * <p>근거: ADR-006, docs/09-consistency-recovery.md §6
 */
@SpringBootTest(classes = ParityPayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DeadLetterIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "paritypay.events";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OperatorBootstrap operatorBootstrap;

    @Autowired
    private MeterRegistry meterRegistry;

    // 컨테이너 주소는 @ServiceConnection이 넣어 주므로 프로퍼티가 아니라 연결 정보 빈에서 읽습니다.
    // 프로퍼티로 읽으면 application.yml의 기본값(localhost:9092)이 나와 다른 브로커를 보게 됩니다.
    @Autowired
    private KafkaConnectionDetails kafkaConnectionDetails;

    private String operatorToken;
    private String viewerToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE dead_letter_event, consumed_event, wallet_transaction, audit_log CASCADE");
        operatorBootstrap.createConfiguredOperators();
        operatorToken = ApiAuth.login(restTemplate, ApiAuth.OPS_OPERATOR, ApiAuth.OPS_PASSWORD);
        viewerToken = ApiAuth.login(restTemplate, ApiAuth.OPS_VIEWER, ApiAuth.OPS_PASSWORD);
    }

    @Test
    @DisplayName("결함 M: 파싱할 수 없는 레코드는 DLT와 표에 남고, 같은 파티션의 뒤 이벤트는 계속 흐른다")
    void unparsableRecordIsDeadLetteredAndThePartitionKeepsFlowing() throws Exception {
        String poisonKey = "poison-" + UUID.randomUUID();
        kafkaTemplate.send(TOPIC, poisonKey, "this is not a json envelope").get();

        // 소비자 그룹 둘(거래내역 프로젝션·정산 항목)이 각각 한 줄씩 남깁니다.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(openDeadLetters()).isEqualTo(2L));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT consumer_group, record_key, event_id, event_type, error FROM dead_letter_event");
        assertThat(rows)
                .extracting(row -> row.get("consumer_group"))
                .containsExactlyInAnyOrder("paritypay-wallet-projection", "paritypay-settlement");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("record_key")).isEqualTo(poisonKey);
            assertThat(row.get("event_id")).isNull();
            assertThat(row.get("event_type")).isNull();
            assertThat((String) row.get("error")).contains("Exception");
        });

        // DLT 토픽에도 실제로 들어갔습니다. DB 행은 우리가 쓴 것이라 그것만으로는 증거가 아닙니다.
        assertThat(deadLetterTopicRecords(poisonKey)).isGreaterThanOrEqualTo(2);

        // 지표가 올랐습니다. 경보는 이것을 봅니다.
        assertThat(meterRegistry.find("paritypay.consumer.dead_letters").counters())
                .isNotEmpty()
                .allSatisfy(counter -> assertThat(counter.count()).isGreaterThanOrEqualTo(1.0));
        assertThat(meterRegistry
                        .get("paritypay.consumer.dead_letters_open")
                        .gauge()
                        .value())
                .isEqualTo(2.0);

        // 같은 키(= 같은 파티션)로 뒤에 온 정상 이벤트는 처리됩니다. 파티션이 멈추지 않았습니다.
        UUID walletId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        kafkaTemplate.send(TOPIC, poisonKey, topUpEnvelope(eventId, walletId)).get();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM wallet_transaction WHERE wallet_id = ?", Long.class, walletId))
                .isEqualTo(1L));
    }

    @Test
    @DisplayName("결함 M: 운영자가 DLT 레코드를 보고 원 토픽으로 다시 흘려보낼 수 있다")
    void operatorsCanListAndRetryDeadLetters() throws Exception {
        kafkaTemplate.send(TOPIC, "poison", "still not json").get();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(openDeadLetters()).isEqualTo(2L));

        ResponseEntity<List> listed = restTemplate.exchange(
                "/api/v1/admin/dead-letters",
                HttpMethod.GET,
                new HttpEntity<>(ApiAuth.bearer(viewerToken)),
                List.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = listed.getBody();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsKeys("deadLetterId", "consumerGroup", "error", "status");
        // payload는 목록에 없습니다. 업무 데이터를 늘어놓지 않습니다.
        assertThat(rows.get(0)).doesNotContainKey("payload");
        String deadLetterId = (String) rows.get(0).get("deadLetterId");

        // 조회만 되는 역할은 되돌리지 못합니다.
        ResponseEntity<Map> forbidden = restTemplate.exchange(
                "/api/v1/admin/dead-letters/" + deadLetterId + "/retry",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "viewer must not"), ApiAuth.bearer(viewerToken)),
                Map.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> retried = restTemplate.exchange(
                "/api/v1/admin/dead-letters/" + deadLetterId + "/retry",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "consumer fixed, replaying"), ApiAuth.bearer(operatorToken)),
                Map.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().get("changed")).isEqualTo(true);
        assertThat(retried.getBody().get("status")).isEqualTo("RETRIED");

        // 다시 흘려보낸 것은 여전히 파싱이 안 되므로 새 DLT 행이 생깁니다 — 재처리가 원 토픽을 실제로
        // 거쳤다는 증거입니다. 원래 행은 RETRIED로 남고 다시 되돌릴 수 없습니다.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(openDeadLetters()).isGreaterThanOrEqualTo(3L));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM dead_letter_event WHERE dead_letter_id = ?::uuid",
                        String.class,
                        deadLetterId))
                .isEqualTo("RETRIED");
        ResponseEntity<Map> again = restTemplate.exchange(
                "/api/v1/admin/dead-letters/" + deadLetterId + "/retry",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "twice"), ApiAuth.bearer(operatorToken)),
                Map.class);
        assertThat(again.getBody().get("changed")).isEqualTo(false);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM audit_log WHERE action = 'DEAD_LETTER_RETRY'", Long.class))
                .isEqualTo(2L);
    }

    private long openDeadLetters() {
        Long count =
                jdbcTemplate.queryForObject("SELECT count(*) FROM dead_letter_event WHERE status = 'OPEN'", Long.class);
        return count == null ? 0 : count;
    }

    private int deadLetterTopicRecords(String key) {
        Properties props = new Properties();
        props.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafkaConnectionDetails.getConsumer().getBootstrapServers()));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        int found = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC + ".dlt"));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && found < 2) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (var record : records) {
                    if (key.equals(record.key())) {
                        found++;
                        assertThat(record.headers().lastHeader("x-original-topic"))
                                .isNotNull();
                        assertThat(record.headers().lastHeader("x-consumer-group"))
                                .isNotNull();
                    }
                }
            }
        }
        return found;
    }

    private static String topUpEnvelope(UUID eventId, UUID walletId) {
        return """
                {"eventId":"%s","eventType":"TopUpCompleted","eventVersion":1,"aggregateType":"TopUp",
                 "aggregateId":"%s","partitionKey":"%s","occurredAt":"2026-09-16T00:00:00Z","traceId":null,
                 "payload":{"topUpId":"%s","walletId":"%s","amount":1000,"currency":"KRW",
                            "ledgerTransactionId":"%s"}}
                """
                .formatted(eventId, walletId, walletId, UUID.randomUUID(), walletId, UUID.randomUUID());
    }
}

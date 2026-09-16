package io.parity.pay.api.eventing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 소비자가 재시도를 다 쓰고도 처리하지 못한 레코드를 받아 두는 곳.
 *
 * <p>결함 M(reports/11 M-018) 전에는 spring-kafka 기본 핸들러가 10회 즉시 재시도한 뒤 ERROR 로그 한
 * 줄만 남기고 오프셋을 넘겼습니다. 쓰레기 레코드라면 그것으로 충분하지만, 우리 이벤트가 역직렬화
 * 버그로 같은 경로를 타면 거래내역·정산 효과가 조용히 빠지고 아무 지표도 그것을 잡지 못합니다.
 *
 * <p>여기서는 세 가지를 하고 나서야 오프셋이 넘어갑니다.
 *
 * <ol>
 *   <li>{@code dead_letter_event}에 한 줄 — 운영자가 보고 다시 흘려보낼 수 있는 목록입니다.
 *   <li>DLT 토픽으로 발행 — 브로커 쪽 기록이고, 확인({@code acks=all})을 기다립니다.
 *   <li>카운터 증가 — 경보의 근거입니다.
 * </ol>
 *
 * <p>DB 기록이나 DLT 발행이 실패하면 예외를 그대로 던집니다. 그러면 오류 핸들러가 오프셋을 넘기지
 * 않고 같은 레코드를 다시 시도합니다 — 어디에도 남기지 못한 레코드를 건너뛰는 것보다 파티션이
 * 멈추는 편이 낫습니다. 같은 레코드는 (그룹·토픽·파티션·오프셋) 유니크로 한 줄만 남습니다.
 *
 * <p>근거: ADR-006, docs/09-consistency-recovery.md §6, reports/11 M-018
 */
@Component
public class DeadLetterRecoverer implements ConsumerAwareRecordRecoverer {

    public static final String DLT_SUFFIX = ".dlt";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    DeadLetterRecoverer(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        String group = consumer == null ? "unknown" : consumer.groupMetadata().groupId();
        String payload = String.valueOf(record.value());
        String key = record.key() == null ? null : String.valueOf(record.key());
        Envelope envelope = tryParse(payload);
        String error = describe(exception);

        jdbcTemplate.update(
                """
                INSERT INTO dead_letter_event
                    (dead_letter_id, consumer_group, topic, partition_no, record_offset, record_key,
                     payload, event_id, event_type, error, status, failed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                ON CONFLICT (consumer_group, topic, partition_no, record_offset) DO NOTHING
                """,
                UUID.randomUUID(),
                group,
                record.topic(),
                record.partition(),
                record.offset(),
                key,
                payload,
                envelope.eventId(),
                envelope.eventType(),
                error,
                Timestamp.from(clock.instant()));

        ProducerRecord<String, String> dead = new ProducerRecord<>(record.topic() + DLT_SUFFIX, key, payload);
        dead.headers().add("x-original-topic", record.topic().getBytes(StandardCharsets.UTF_8));
        dead.headers()
                .add(
                        "x-original-partition",
                        Integer.toString(record.partition()).getBytes(StandardCharsets.UTF_8));
        dead.headers().add("x-original-offset", Long.toString(record.offset()).getBytes(StandardCharsets.UTF_8));
        dead.headers().add("x-consumer-group", group.getBytes(StandardCharsets.UTF_8));
        dead.headers().add("x-exception", error.getBytes(StandardCharsets.UTF_8));
        try {
            // 확인을 기다립니다. DLT에 실제로 들어간 뒤에야 원 토픽의 오프셋을 넘길 수 있습니다.
            kafkaTemplate.send(dead).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing to the dead-letter topic", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish to the dead-letter topic", e);
        }

        Counter.builder("paritypay.consumer.dead_letters")
                .description("재시도를 다 쓰고 DLT로 보낸 레코드 수")
                .tag("consumer_group", group)
                .register(meterRegistry)
                .increment();
        log.error(
                "dead-lettered {}-{}@{} for {} ({}): {}",
                record.topic(),
                record.partition(),
                record.offset(),
                group,
                envelope.eventType() == null ? "unparsable" : envelope.eventType(),
                error);
    }

    private Envelope tryParse(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            UUID eventId =
                    node.hasNonNull("eventId") ? parseUuid(node.get("eventId").asText()) : null;
            String eventType =
                    node.hasNonNull("eventType") ? node.get("eventType").asText() : null;
            return new Envelope(eventId, eventType);
        } catch (JacksonException e) {
            return new Envelope(null, null);
        }
    }

    private static UUID parseUuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code error} 열에 남길 한 줄입니다. 원인 예외가 있으면 그것을 씁니다. */
    private static String describe(Exception exception) {
        Throwable cause = exception;
        // 원인 사슬을 따라가되 순환·과도한 깊이는 잘라 냅니다.
        for (int depth = 0; depth < 10 && cause.getCause() != null; depth++) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        String line = cause.getClass().getSimpleName() + ": " + message;
        return line.length() > 2000 ? line.substring(0, 2000) : line;
    }

    private record Envelope(UUID eventId, String eventType) {}
}

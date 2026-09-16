package io.parity.pay.api.eventing;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 운영자용 DLT 조회와 재처리.
 *
 * <p>Outbox 쪽({@code OutboxAdminService})과 같은 모양입니다 — 지표가 숫자를 알려 주면 여기서 그 정체를
 * 보고 다시 흘려보냅니다. 다시 흘려보내는 것은 **원 토픽에 같은 키로 같은 payload를 다시 발행**하는
 * 것입니다. 소비자가 멱등이므로 이미 처리된 것이 섞여 있어도 효과는 한 번이고, 아직 고치지 않은
 * 코드로 다시 실패하면 새 DLT 행이 생깁니다. 내용을 고쳐서 보내는 기능은 없습니다 — 그것은
 * 이벤트를 위조하는 것입니다.
 *
 * <p>근거: reports/11 M-018 결함 M, ADR-006
 */
@Service
public class DeadLetterAdminService {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;

    DeadLetterAdminService(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        // 경보의 근거입니다. 카운터는 "얼마나 자주"를, 이 게이지는 "지금 몇 건이 운영자를 기다리는가"를
        // 말합니다. 0이 아니면 거래내역·정산 어딘가에 빠진 이벤트가 있을 수 있습니다.
        Gauge.builder("paritypay.consumer.dead_letters_open", this, self -> self.countOpen())
                .description("운영자 확인을 기다리는 DLT 레코드 수")
                .register(meterRegistry);
    }

    long countOpen() {
        Long count =
                jdbcTemplate.queryForObject("SELECT count(*) FROM dead_letter_event WHERE status = 'OPEN'", Long.class);
        return count == null ? 0 : count;
    }

    /** payload는 담지 않습니다. 목록 화면에 업무 데이터를 늘어놓지 않습니다(Outbox와 같은 이유). */
    public List<DeadLetterSummary> list(String status, int limit) {
        return jdbcTemplate.query(
                """
                SELECT dead_letter_id, consumer_group, topic, partition_no, record_offset, record_key,
                       event_id, event_type, error, status, failed_at, retried_at, retried_by
                  FROM dead_letter_event
                 WHERE status = ?
                 ORDER BY failed_at
                 LIMIT ?
                """,
                (rs, rowNum) -> new DeadLetterSummary(
                        rs.getObject("dead_letter_id", UUID.class),
                        rs.getString("consumer_group"),
                        rs.getString("topic"),
                        rs.getInt("partition_no"),
                        rs.getLong("record_offset"),
                        rs.getString("record_key"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("error"),
                        rs.getString("status"),
                        rs.getTimestamp("failed_at").toInstant(),
                        rs.getTimestamp("retried_at") == null
                                ? null
                                : rs.getTimestamp("retried_at").toInstant(),
                        rs.getString("retried_by")),
                status,
                limit);
    }

    /**
     * 원 토픽에 같은 키·같은 payload로 다시 발행하고 {@code RETRIED}로 표시합니다.
     *
     * <p>발행 확인을 기다린 뒤에 상태를 바꿉니다. 먼저 바꾸면 발행이 실패했을 때 운영자 목록에서
     * 사라진 채 아무 데도 없는 레코드가 됩니다.
     */
    public RetryOutcome retry(UUID deadLetterId, String operatorId) {
        List<Row> rows = jdbcTemplate.query(
                "SELECT topic, record_key, payload, status, consumer_group FROM dead_letter_event WHERE dead_letter_id = ?",
                (rs, rowNum) -> new Row(
                        rs.getString("topic"),
                        rs.getString("record_key"),
                        rs.getString("payload"),
                        rs.getString("status"),
                        rs.getString("consumer_group")),
                deadLetterId);
        if (rows.isEmpty()) {
            return new RetryOutcome(deadLetterId, null, false, "not found");
        }
        Row row = rows.get(0);
        if (!"OPEN".equals(row.status())) {
            return new RetryOutcome(deadLetterId, row.status(), false, "already " + row.status());
        }
        try {
            kafkaTemplate.send(row.topic(), row.key(), row.payload()).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while republishing a dead letter", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to republish a dead letter", e);
        }
        Instant now = clock.instant();
        jdbcTemplate.update(
                "UPDATE dead_letter_event SET status = 'RETRIED', retried_at = ?, retried_by = ? WHERE dead_letter_id = ?",
                Timestamp.from(now),
                operatorId,
                deadLetterId);
        return new RetryOutcome(
                deadLetterId, "RETRIED", true, "republished to " + row.topic() + " for " + row.consumerGroup());
    }

    private record Row(String topic, String key, String payload, String status, String consumerGroup) {}

    public record DeadLetterSummary(
            UUID deadLetterId,
            String consumerGroup,
            String topic,
            int partition,
            long offset,
            String recordKey,
            UUID eventId,
            String eventType,
            String error,
            String status,
            Instant failedAt,
            Instant retriedAt,
            String retriedBy) {}

    public record RetryOutcome(UUID deadLetterId, String status, boolean changed, String detail) {}
}

package io.parity.pay.api.outbox;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 저장소.
 *
 * <p>발행 대상 선점에 {@code FOR UPDATE SKIP LOCKED}를 사용합니다. 발행기를 여러 개 띄워도 같은
 * 이벤트를 두 인스턴스가 동시에 집어가지 않고, 잠긴 행은 건너뛰므로 서로를 기다리지 않습니다.
 * 근거: docs/09-consistency-recovery.md §5
 */
@Repository
class OutboxRepository {

    private static final RowMapper<OutboxRecord> ROW_MAPPER = (rs, rowNum) -> new OutboxRecord(
            rs.getObject("event_id", UUID.class),
            rs.getString("event_type"),
            rs.getInt("event_version"),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getString("partition_key"),
            rs.getString("payload"),
            rs.getString("trace_id"),
            rs.getInt("attempt_count"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 업무 트랜잭션 안에서만 호출됩니다. 근거: ADR-005 */
    @Transactional(propagation = Propagation.MANDATORY)
    void append(
            UUID eventId,
            String eventType,
            int eventVersion,
            String aggregateType,
            String aggregateId,
            String partitionKey,
            String payloadJson,
            String traceId,
            Instant occurredAt,
            Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO outbox_event
                    (event_id, event_type, event_version, aggregate_type, aggregate_id,
                     partition_key, payload, trace_id, status, attempt_count, next_attempt_at,
                     occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?, ?)
                """,
                eventId,
                eventType,
                eventVersion,
                aggregateType,
                aggregateId,
                partitionKey,
                payloadJson,
                traceId,
                Timestamp.from(now),
                Timestamp.from(occurredAt),
                Timestamp.from(now));
    }

    /**
     * 발행 대상을 선점합니다. 호출자의 트랜잭션이 끝날 때까지 잠금이 유지됩니다.
     *
     * <p>파티션 키마다 <b>가장 앞선 미발행 이벤트 하나만</b> 집어갑니다. 앞선 형제가 아직 PENDING이면
     * 뒤 이벤트는 후보에서 빠집니다.
     *
     * <p>발행기가 한 대일 때는 발생 순서 정렬만으로 충분했습니다. 여러 대가 되면 같은 결제의 승인과
     * 취소가 서로 다른 발행기의 배치로 나뉘고, 어느 쪽이 먼저 브로커에 닿을지는 경쟁으로 정해집니다.
     * 실제로 400건 중 18건이 역전됐습니다(M-001). 설계는 이 순서에 의존합니다 — 정산 소비자는
     * 취소를 받았을 때 그 결제의 판매 항목이 있는지로 처리 방식을 가르므로, 순서가 뒤집히면 판매자에게
     * 나가는 금액이 달라집니다.
     *
     * <p>앞선 형제가 PENDING인 동안은 그 형제를 잡은 발행기가 아직 커밋하지 않았다는 뜻입니다.
     * 커밋은 브로커 ACK 뒤에 일어나므로, 뒤 이벤트가 후보가 되는 시점에는 앞 이벤트가 이미 브로커에
     * 들어가 있습니다. 발행기가 몇 대든 한 Aggregate 안의 순서가 유지됩니다.
     *
     * <p>대가는 head-of-line 대기입니다. 한 이벤트가 계속 실패하면 같은 Aggregate의 뒤 이벤트가
     * 함께 멈춥니다. 순서를 지키려면 그래야 하고, 멈춘 사실은 적체 지표로 드러납니다.
     *
     * <p>근거: ADR-005, reports/11 M-001, docs/05-technical-design.md §9
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<OutboxRecord> claimBatch(int batchSize, Instant now) {
        return jdbcTemplate.query(
                """
                SELECT o.event_id, o.event_type, o.event_version, o.aggregate_type, o.aggregate_id,
                       o.partition_key, o.payload::text AS payload, o.trace_id, o.attempt_count, o.occurred_at
                  FROM outbox_event o
                 WHERE o.status = 'PENDING'
                   AND o.next_attempt_at <= ?
                   AND NOT EXISTS (
                       SELECT 1
                         FROM outbox_event earlier
                        WHERE earlier.partition_key = o.partition_key
                          AND earlier.status = 'PENDING'
                          AND (earlier.occurred_at, earlier.event_id) < (o.occurred_at, o.event_id))
                 ORDER BY o.occurred_at, o.event_id
                 LIMIT ?
                 FOR UPDATE OF o SKIP LOCKED
                """,
                ROW_MAPPER,
                Timestamp.from(now),
                batchSize);
    }

    /**
     * 확인된 이벤트를 한 번의 UPDATE로 발행 완료 처리합니다.
     *
     * <p>건별 UPDATE는 배치 크기만큼 왕복을 만듭니다. 상태를 바꾸는 조건은 동일하므로 한 문장으로
     * 묶습니다. 여기 들어오는 것은 브로커가 받았다고 답한 이벤트뿐입니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void markPublished(List<UUID> eventIds, Instant now) {
        if (eventIds.isEmpty()) {
            return;
        }
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                            UPDATE outbox_event
                               SET status = 'PUBLISHED',
                                   attempt_count = attempt_count + 1,
                                   published_at = ?,
                                   last_error = NULL
                             WHERE event_id = ANY (?)
                            """);
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setArray(2, connection.createArrayOf("uuid", eventIds.toArray()));
            return statement;
        });
    }

    /** 발행에 실패했지만 재시도할 수 있는 상태입니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    void markForRetry(UUID eventId, String error, Instant nextAttemptAt) {
        jdbcTemplate.update(
                """
                UPDATE outbox_event
                   SET attempt_count = attempt_count + 1,
                       next_attempt_at = ?,
                       last_error = ?
                 WHERE event_id = ?
                """,
                Timestamp.from(nextAttemptAt),
                truncate(error),
                eventId);
    }

    /** 최대 재시도를 넘겼습니다. 운영자가 조회·재처리할 대상이 됩니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    void markFailed(UUID eventId, String error) {
        jdbcTemplate.update(
                """
                UPDATE outbox_event
                   SET status = 'FAILED',
                       attempt_count = attempt_count + 1,
                       last_error = ?
                 WHERE event_id = ?
                """,
                truncate(error),
                eventId);
    }

    long countByStatus(String status) {
        Long count =
                jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event WHERE status = ?", Long.class, status);
        return count == null ? 0L : count;
    }

    /** 가장 오래된 미발행 이벤트의 나이(초)입니다. 적체를 재는 핵심 지표입니다. */
    double oldestPendingAgeSeconds(Instant now) {
        Timestamp oldest = jdbcTemplate.queryForObject(
                "SELECT min(occurred_at) FROM outbox_event WHERE status = 'PENDING'", Timestamp.class);
        if (oldest == null) {
            return 0.0d;
        }
        return Math.max(0.0d, (now.toEpochMilli() - oldest.getTime()) / 1000.0d);
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}

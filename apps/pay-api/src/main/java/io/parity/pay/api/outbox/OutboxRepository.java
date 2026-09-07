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
     * <p>같은 Aggregate의 순서를 지키려면 파티션 키 단위 순서가 필요하지만, 여기서는 발생 순서로
     * 정렬해 가져오고 브로커 파티션 키로 순서를 보장합니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<OutboxRecord> claimBatch(int batchSize, Instant now) {
        return jdbcTemplate.query(
                """
                SELECT event_id, event_type, event_version, aggregate_type, aggregate_id,
                       partition_key, payload::text AS payload, trace_id, attempt_count, occurred_at
                  FROM outbox_event
                 WHERE status = 'PENDING'
                   AND next_attempt_at <= ?
                 ORDER BY occurred_at, event_id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
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
        jdbcTemplate.update(
                connection -> {
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
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status = ?", Long.class, status);
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

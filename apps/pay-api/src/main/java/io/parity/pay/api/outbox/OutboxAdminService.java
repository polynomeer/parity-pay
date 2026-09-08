package io.parity.pay.api.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자용 Outbox 조회와 재처리.
 *
 * <p>최대 시도를 넘긴 이벤트는 {@code FAILED}로 남고 발행기가 더 이상 집어가지 않습니다. 지금까지
 * 그 이벤트를 보고 다시 흘려보내는 방법은 SQL뿐이었습니다. 적체 지표가 숫자를 알려 주는데 그 숫자를
 * 다룰 방법이 없으면 지표는 경보로만 남습니다.
 *
 * <p>여기서 하는 일은 상태를 {@code PENDING}으로 되돌리는 것뿐입니다. 이벤트 내용을 고치거나 직접
 * 브로커로 보내지 않습니다. 발행은 언제나 발행기를 거칩니다 — 그래야 확인 시점과 중복 처리 규칙이
 * 한 곳에만 있습니다.
 *
 * <p>근거: docs/09-consistency-recovery.md §5, ADR-005, docs/13 Phase 3
 */
@Service
public class OutboxAdminService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    OutboxAdminService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * 이벤트 목록입니다. payload는 담지 않습니다.
     *
     * <p>운영자가 알아야 하는 것은 "무엇이 몇 번 실패했고 마지막 오류가 무엇인가"이고, 그 판단에
     * payload는 필요 없습니다. 목록 화면에 업무 데이터를 늘어놓지 않습니다.
     */
    public List<OutboxEventSummary> list(String status, int limit) {
        return jdbcTemplate.query(
                """
                SELECT event_id, event_type, aggregate_type, aggregate_id, partition_key,
                       status, attempt_count, last_error, occurred_at, next_attempt_at, published_at
                  FROM outbox_event
                 WHERE status = ?
                 ORDER BY occurred_at
                 LIMIT ?
                """,
                (rs, rowNum) -> new OutboxEventSummary(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("partition_key"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getString("last_error"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getTimestamp("next_attempt_at").toInstant(),
                        rs.getTimestamp("published_at") == null
                                ? null
                                : rs.getTimestamp("published_at").toInstant()),
                status,
                limit);
    }

    /**
     * 실패한 이벤트 하나를 다시 발행 대상으로 되돌립니다.
     *
     * <p>{@code FAILED}에서만 됩니다. {@code PUBLISHED}를 되돌리면 브로커에 같은 이벤트가 한 번 더
     * 나갑니다. 소비자가 중복을 흡수하므로 사고는 아니지만, 그것은 운영자가 우연히 누를 일이
     * 아니라 별도로 판단할 일입니다.
     *
     * <p>시도 횟수를 0으로 되돌립니다. 그대로 두면 한 번 시도하고 곧바로 다시 {@code FAILED}가
     * 됩니다. 되돌리기 전의 횟수는 응답과 감사 로그에 남습니다.
     */
    @Transactional
    public RequeueOutcome requeue(UUID eventId) {
        List<OutboxEventSummary> found = jdbcTemplate.query(
                """
                SELECT event_id, event_type, aggregate_type, aggregate_id, partition_key,
                       status, attempt_count, last_error, occurred_at, next_attempt_at, published_at
                  FROM outbox_event
                 WHERE event_id = ?
                 FOR UPDATE
                """,
                (rs, rowNum) -> new OutboxEventSummary(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("partition_key"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getString("last_error"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getTimestamp("next_attempt_at").toInstant(),
                        rs.getTimestamp("published_at") == null
                                ? null
                                : rs.getTimestamp("published_at").toInstant()),
                eventId);

        if (found.isEmpty()) {
            return new RequeueOutcome(eventId, "NOT_FOUND", false, 0, false, "no such outbox event");
        }
        OutboxEventSummary event = found.get(0);
        if (!"FAILED".equals(event.status())) {
            return new RequeueOutcome(
                    eventId, event.status(), false, event.attemptCount(), false, "only FAILED events can be requeued");
        }

        boolean laterSiblingPublished = laterSiblingPublished(event);
        jdbcTemplate.update(
                """
                UPDATE outbox_event
                   SET status = 'PENDING', attempt_count = 0, next_attempt_at = ?, last_error = NULL
                 WHERE event_id = ?
                """,
                Timestamp.from(clock.instant()),
                eventId);

        return new RequeueOutcome(
                eventId,
                "PENDING",
                true,
                event.attemptCount(),
                laterSiblingPublished,
                laterSiblingPublished
                        ? "requeued, but a later event of the same aggregate is already published;"
                                + " this one will arrive out of order"
                        : "requeued");
    }

    /**
     * 같은 Aggregate의 뒤 이벤트가 이미 나갔는지 봅니다.
     *
     * <p>발행기는 파티션 키마다 선두 하나만 집어가므로 정상 경로에서는 순서가 유지됩니다. 그러나
     * {@code FAILED}가 된 이벤트는 더 이상 선두로 취급되지 않아 뒤 이벤트가 먼저 나갑니다. 그 상태에서
     * 되돌리면 순서가 뒤집힌 채 도착합니다. 막지는 않고 사실을 알려 줍니다 — 되돌리지 않으면 이벤트가
     * 영영 나가지 않는 것이고, 어느 쪽이 나은지는 이벤트 종류에 달렸습니다.
     *
     * <p>근거: reports/11 M-001
     */
    private boolean laterSiblingPublished(OutboxEventSummary event) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM outbox_event
                 WHERE partition_key = ?
                   AND status = 'PUBLISHED'
                   AND (occurred_at, event_id) > (?, ?)
                """,
                Long.class,
                event.partitionKey(),
                Timestamp.from(event.occurredAt()),
                event.eventId());
        return count != null && count > 0;
    }

    public record OutboxEventSummary(
            UUID eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            String partitionKey,
            String status,
            int attemptCount,
            String lastError,
            Instant occurredAt,
            Instant nextAttemptAt,
            Instant publishedAt) {}

    public record RequeueOutcome(
            UUID eventId,
            String status,
            boolean changed,
            int previousAttemptCount,
            boolean laterSiblingPublished,
            String detail) {}
}

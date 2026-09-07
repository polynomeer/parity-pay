package io.parity.pay.shared.event;

import io.parity.pay.shared.id.EventId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 이벤트 봉투. 근거: docs/08-db-api-event-spec.md §6
 *
 * <ul>
 *   <li>{@code eventId}는 전역 유일하며 소비자 멱등성의 기준입니다.
 *   <li>{@code occurredAt}은 업무 사실이 일어난 시각입니다(발행 시각이 아닙니다).
 *   <li>{@code partitionKey}는 순서가 필요한 Aggregate ID입니다.
 *   <li>payload에는 민감정보와 전체 계좌번호를 넣지 않습니다.
 * </ul>
 *
 * <p>이벤트는 과거 사실이므로 이름을 명령형으로 짓지 않습니다({@code PaymentApproved} O,
 * {@code ApprovePayment} X). 근거: docs/05-technical-design.md §9
 */
public record EventEnvelope(
        EventId eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        String aggregateId,
        String partitionKey,
        Instant occurredAt,
        String traceId,
        Map<String, Object> payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(partitionKey, "partitionKey must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload must not be null"));
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be at least 1");
        }
    }

    public static EventEnvelope of(
            String eventType,
            int eventVersion,
            String aggregateType,
            String aggregateId,
            Instant occurredAt,
            String traceId,
            Map<String, Object> payload) {
        return new EventEnvelope(
                EventId.generate(),
                eventType,
                eventVersion,
                aggregateType,
                aggregateId,
                aggregateId,
                occurredAt,
                traceId,
                payload);
    }
}
